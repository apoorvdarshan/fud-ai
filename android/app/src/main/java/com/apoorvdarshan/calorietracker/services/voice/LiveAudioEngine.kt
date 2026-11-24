package com.apoorvdarshan.calorietracker.services.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Output only: this interface cannot open or read a microphone. */
interface LivePlaybackIO {
    /** Everything scheduled has played (main thread). */
    var onPlaybackFinished: (() -> Unit)?

    /** The system took the audio away, e.g. a phone call (main thread). */
    var onInterrupted: (() -> Unit)?

    val isPlaying: Boolean

    fun startPlayback(outputSampleRate: Int)
    fun play(pcm16: ByteArray)
    fun stop()
}

/** Microphone in, assistant voice out. The controller drives it; tests could use a fake. */
interface LiveAudioIO : LivePlaybackIO {
    /** Microphone audio (16-bit mono PCM at the input rate), on the recording thread. */
    var onMicrophoneAudio: ((ByteArray) -> Unit)?

    fun start(inputSampleRate: Int, outputSampleRate: Int)

    /** Milliseconds played since playback last started from silence. */
    val playbackPositionMs: Long

    /** Where the next queued chunk will start, on the same timeline. */
    val scheduledEndMs: Long

    /** Barge-in: drops everything not yet heard. */
    fun stopPlayback()

    /** 0 (silence) to 1 (loud speech). */
    val microphoneLevel: Float
    val playbackLevel: Float
}

class LiveAudioError(message: String) : Exception(message)

private const val DRAIN_TOLERANCE_FRAMES = 240L

/** Enqueue, written-frame accounting and the idle transition share this same lock. */
internal fun markLivePlaybackDrained(
    lock: Any,
    playing: AtomicBoolean,
    queue: Collection<*>,
    framesWritten: AtomicLong,
    playbackHead: () -> Long,
): Boolean = synchronized(lock) {
    if (!playing.get() || queue.isNotEmpty()) return@synchronized false
    if (playbackHead() + DRAIN_TOLERANCE_FRAMES < framesWritten.get()) return@synchronized false
    playing.compareAndSet(true, false)
}

/**
 * Android audio for the live session. Recording uses the `VOICE_COMMUNICATION` source with
 * the platform's acoustic echo canceler and noise suppressor when the device has them, so
 * the assistant's own voice from the loudspeaker doesn't reach the model and interrupt
 * it. Playback runs on the voice-communication path (the echo canceler's reference),
 * routed to a headset when one is connected and to the loudspeaker otherwise.
 */
class LiveAudioEngine(context: Context) : LiveAudioIO {
    override var onMicrophoneAudio: ((ByteArray) -> Unit)? = null
    override var onPlaybackFinished: (() -> Unit)? = null
    override var onInterrupted: (() -> Unit)? = null

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val playbackLock = Any()

    private class Chunk(val bytes: ByteArray, val generation: Int)

    private val queue = LinkedBlockingQueue<Chunk>()
    private val generation = AtomicInteger(0)
    private val framesQueued = AtomicLong(0)
    private val framesWritten = AtomicLong(0)
    private val playing = AtomicBoolean(false)
    private var outputRate = 24_000

    @Volatile private var micLevel = 0f
    @Volatile private var speakerLevel = 0f

    private var focusRequest: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var changedSpeakerphone = false
    private var changedRoute = false

    override val isPlaying: Boolean get() = playing.get()
    override val microphoneLevel: Float get() = micLevel
    override val playbackLevel: Float get() = if (playing.get()) speakerLevel else 0f

    override val playbackPositionMs: Long
        get() {
            val frames = track?.let { runCatching { it.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrNull() } ?: 0L
            return frames * 1_000 / outputRate
        }

    override val scheduledEndMs: Long get() = framesQueued.get() * 1_000 / outputRate

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            mainHandler.post { onInterrupted?.invoke() }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(inputSampleRate: Int, outputSampleRate: Int) {
        requestFocus(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        routeForConversation()
        val player = openOutput(outputSampleRate, AudioAttributes.USAGE_VOICE_COMMUNICATION)
        val recorder = startRecording(inputSampleRate)
        running.set(true)
        val chunkBytes = inputSampleRate * 2 * MIC_CHUNK_MS / 1_000
        recordThread = Thread({ readLoop(recorder, chunkBytes) }, "fudai-live-mic").also { it.start() }
        playThread = Thread({ playLoop(player) }, "fudai-live-speaker").also { it.start() }
        VoiceLog.info(TAG, "Audio started (echo canceler: ${echoCanceler != null})")
    }

    /** Settings previews use media output; no recording, permission request or call routing. */
    override fun startPlayback(outputSampleRate: Int) {
        requestFocus(AudioAttributes.USAGE_MEDIA)
        val player = openOutput(outputSampleRate, AudioAttributes.USAGE_MEDIA)
        running.set(true)
        playThread = Thread({ playLoop(player) }, "fudai-voice-preview").also { it.start() }
    }

    private fun openOutput(outputSampleRate: Int, usage: Int): AudioTrack {
        outputRate = outputSampleRate

        val minOut = AudioTrack.getMinBufferSize(outputRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minOut <= 0) throw LiveAudioError("The speaker doesn't support $outputRate Hz audio.")
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minOut, outputRate * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (player.state != AudioTrack.STATE_INITIALIZED) {
            player.release()
            throw LiveAudioError("The speaker could not be opened.")
        }
        track = player
        player.play()
        return player
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(inputSampleRate: Int): AudioRecord {
        val minIn = AudioRecord.getMinBufferSize(inputSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minIn <= 0) throw LiveAudioError("The microphone doesn't support $inputSampleRate Hz audio.")
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                inputSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minIn, inputSampleRate)
            )
        } catch (error: Exception) {
            throw LiveAudioError("The microphone could not be opened.")
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw LiveAudioError("The microphone could not be opened.")
        }
        record = recorder
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw LiveAudioError("The microphone is in use by another app.")
        }
        return recorder
    }

    override fun play(pcm16: ByteArray) {
        synchronized(playbackLock) {
            if (!running.get() || pcm16.size < 2) return
            playing.set(true)
            framesQueued.addAndGet((pcm16.size / 2).toLong())
            queue.offer(Chunk(pcm16, generation.get()))
        }
    }

    override fun stopPlayback() {
        synchronized(playbackLock) {
            generation.incrementAndGet()
            queue.clear()
            track?.let { player ->
                runCatching {
                    player.pause()
                    player.flush()
                    player.play()
                }
            }
            framesQueued.set(0)
            framesWritten.set(0)
            speakerLevel = 0f
            playing.set(false)
        }
    }

    override fun stop() {
        running.set(false)
        generation.incrementAndGet()
        queue.clear()
        runCatching { record?.stop() }
        // Unblock a speaker write before waiting for its thread to exit.
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        recordThread?.join(500)
        playThread?.join(500)
        recordThread = null
        playThread = null
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        runCatching { record?.release() }
        record = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
        }
        runCatching { track?.release() }
        track = null
        playing.set(false)
        micLevel = 0f
        speakerLevel = 0f
        restoreRoute()
        abandonFocus()
        onMicrophoneAudio = null
        onPlaybackFinished = null
        onInterrupted = null
        VoiceLog.info(TAG, "Audio stopped")
    }

    private fun readLoop(recorder: AudioRecord, chunkBytes: Int) {
        val buffer = ByteArray(chunkBytes)
        while (running.get()) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read < 0) {
                if (running.get()) {
                    VoiceLog.warn(TAG, "Microphone read failed: $read")
                    mainHandler.post { onInterrupted?.invoke() }
                }
                break
            }
            if (read == 0) continue
            val chunk = buffer.copyOf(read)
            micLevel = loudness(chunk)
            onMicrophoneAudio?.invoke(chunk)
        }
    }

    private fun playLoop(player: AudioTrack) {
        val sliceBytes = max(2, outputRate * 2 * PLAYBACK_SLICE_MS / 1_000 and 1.inv())
        while (running.get()) {
            val chunk = queue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
            if (chunk == null) {
                checkDrained(player)
                continue
            }
            if (chunk.generation != generation.get()) continue
            var offset = 0
            // Small slices keep a barge-in from letting much stale audio through.
            while (offset < chunk.bytes.size && running.get() && chunk.generation == generation.get()) {
                val length = min(sliceBytes, chunk.bytes.size - offset)
                val written = synchronized(playbackLock) {
                    if (chunk.generation != generation.get() || !running.get()) 0
                    else player.write(chunk.bytes, offset, length, AudioTrack.WRITE_BLOCKING)
                }
                if (written <= 0) {
                    if (written < 0 && running.get() && chunk.generation == generation.get()) {
                        mainHandler.post { onInterrupted?.invoke() }
                    }
                    break
                }
                speakerLevel = loudness(chunk.bytes, offset, written)
                offset += written
            }
            synchronized(playbackLock) {
                if (chunk.generation == generation.get()) framesWritten.addAndGet((offset / 2).toLong())
            }
        }
    }

    private fun checkDrained(player: AudioTrack) {
        val drained = markLivePlaybackDrained(playbackLock, playing, queue, framesWritten) {
            runCatching { player.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(Long.MAX_VALUE)
        }
        if (drained) {
            speakerLevel = 0f
            // A new chunk may arrive after unlocking; the controller rechecks isPlaying.
            mainHandler.post { onPlaybackFinished?.invoke() }
        }
    }

    // MARK: - Routing and focus

    private fun requestFocus(usage: Int) {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
            focusRequest = request
            val result = manager.requestAudioFocus(request)
            if (usage == AudioAttributes.USAGE_MEDIA && result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw LiveAudioError("Audio focus is unavailable.")
            }
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Conversation mode, on a headset when connected and the loudspeaker otherwise. */
    private fun routeForConversation() {
        val manager = audioManager ?: return
        previousMode = manager.mode
        changedRoute = true
        runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = manager.availableCommunicationDevices
            val preferred = HEADSET_TYPES.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            preferred?.let { runCatching { manager.setCommunicationDevice(it) } }
        } else {
            @Suppress("DEPRECATION")
            val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val headset = outputs.any { it.type in HEADSET_TYPES }
            @Suppress("DEPRECATION")
            if (!headset && !manager.isSpeakerphoneOn) {
                runCatching { manager.isSpeakerphoneOn = true }
                changedSpeakerphone = true
            }
        }
    }

    private fun restoreRoute() {
        if (!changedRoute) return
        changedRoute = false
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.clearCommunicationDevice() }
        } else if (changedSpeakerphone) {
            @Suppress("DEPRECATION")
            runCatching { manager.isSpeakerphoneOn = false }
            changedSpeakerphone = false
        }
        runCatching { manager.mode = previousMode }
    }

    companion object {
        private const val TAG = "LiveAudio"
        private const val MIC_CHUNK_MS = 40
        private const val PLAYBACK_SLICE_MS = 20
        private const val QUEUE_POLL_MS = 40L

        private val HEADSET_TYPES = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )

        /** Maps -55 dBFS (room noise) ... -15 dBFS (close speech) onto 0...1, like the iOS orb. */
        fun loudness(pcm16: ByteArray, offset: Int = 0, length: Int = pcm16.size): Float {
            val samples = length / 2
            if (samples == 0) return 0f
            var sum = 0.0
            for (index in 0 until samples) {
                val low = pcm16[offset + index * 2].toInt() and 0xFF
                val high = pcm16[offset + index * 2 + 1].toInt()
                val value = ((high shl 8) or low).toShort() / 32_768.0
                sum += value * value
            }
            val rms = sqrt(sum / samples)
            val decibels = 20 * log10(max(rms, 1e-7))
            return ((decibels + 55) / 40).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
