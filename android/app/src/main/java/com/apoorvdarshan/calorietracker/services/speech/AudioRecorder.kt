package com.apoorvdarshan.calorietracker.services.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Records 16 kHz mono PCM WAV, accepted by both local Whisper and every remote STT route. */
class AudioRecorder(private val context: Context) {
    private var recorder: AudioRecord? = null
    private var writer: Thread? = null
    private var output: File? = null
    private var recording: AtomicBoolean? = null
    private var writerFailed: AtomicBoolean? = null
    private val discardFiles = ConcurrentHashMap.newKeySet<File>()

    fun start(): File? = runCatching {
        val dir = File(context.cacheDir, "fudai-stt").apply { mkdirs() }
        val file = File(dir, "rec-${System.currentTimeMillis()}.wav")
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimum > 0) { "The microphone does not support 16 kHz PCM recording." }
        val bufferSize = maxOf(minimum, SAMPLE_RATE / 2)
        @Suppress("MissingPermission")
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be initialized." }

        FileOutputStream(file).use { it.write(ByteArray(WAV_HEADER_BYTES)) }
        recorder = audioRecord
        output = file
        audioRecord.startRecording()
        val active = AtomicBoolean(true)
        val failed = AtomicBoolean(false)
        recording = active
        writerFailed = failed
        writer = Thread(
            { writePcm(audioRecord, file, bufferSize, active, failed) },
            "fudai-wav-recorder"
        ).also(Thread::start)
        file
    }.onFailure { cancel() }.getOrNull()

    /** Stop + release. Returns a complete WAV file or null on failure. */
    fun stop(): File? {
        val audioRecord = recorder ?: return null
        recording?.set(false)
        runCatching { audioRecord.stop() }
        val activeWriter = writer
        runCatching { activeWriter?.join(2_000) }
        val file = output
        if (activeWriter?.isAlive == true) {
            file?.let(discardFiles::add)
            activeWriter.interrupt()
            runCatching { activeWriter.join(250) }
            if (!activeWriter.isAlive) {
                file?.let {
                    discardFiles.remove(it)
                    it.delete()
                }
            }
            clearSession()
            return null
        }
        // The writer owns AudioRecord.release(), guaranteeing native reads and file writes have
        // ended before the WAV header is patched. A missing writer means start failed early.
        if (activeWriter == null) runCatching { audioRecord.release() }
        val failed = writerFailed?.get() == true
        clearSession()
        if (failed || file == null || file.length() <= WAV_HEADER_BYTES) {
            file?.delete()
            return null
        }
        return runCatching {
            writeWavHeader(file)
            file
        }.getOrElse {
            file.delete()
            null
        }
    }

    fun cancel() {
        recording?.set(false)
        val audioRecord = recorder
        val activeWriter = writer
        val file = output
        file?.let(discardFiles::add)
        audioRecord?.let { activeRecorder ->
            runCatching { activeRecorder.stop() }
        }
        runCatching { activeWriter?.join(2_000) }
        if (activeWriter?.isAlive == true) {
            activeWriter.interrupt()
            runCatching { activeWriter.join(250) }
        }
        if (activeWriter == null) runCatching { audioRecord?.release() }
        if (activeWriter?.isAlive != true) {
            file?.let {
                discardFiles.remove(it)
                it.delete()
            }
        }
        clearSession()
    }

    private fun writePcm(
        audioRecord: AudioRecord,
        file: File,
        bufferSize: Int,
        active: AtomicBoolean,
        failed: AtomicBoolean
    ) {
        try {
            FileOutputStream(file, true).use { stream ->
                val buffer = ByteArray(bufferSize)
                while (active.get()) {
                    when (val count = audioRecord.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_NON_BLOCKING
                    )) {
                        AudioRecord.ERROR_DEAD_OBJECT,
                        AudioRecord.ERROR_INVALID_OPERATION -> break
                        AudioRecord.ERROR_BAD_VALUE,
                        AudioRecord.ERROR -> {
                            failed.set(true)
                            break
                        }
                        0 -> Thread.sleep(5)
                        else -> if (count > 0) stream.write(buffer, 0, count)
                    }
                }
                stream.fd.sync()
            }
        } catch (_: Throwable) {
            failed.set(true)
        } finally {
            runCatching { audioRecord.release() }
            if (failed.get() || discardFiles.remove(file)) file.delete()
        }
    }

    private fun clearSession() {
        recorder = null
        writer = null
        output = null
        recording = null
        writerFailed = null
    }

    private fun writeWavHeader(file: File) {
        val dataBytes = file.length() - WAV_HEADER_BYTES
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.writeBytes("RIFF")
            wav.writeLittleEndianInt((dataBytes + 36).toInt())
            wav.writeBytes("WAVEfmt ")
            wav.writeLittleEndianInt(16)
            wav.writeLittleEndianShort(1)
            wav.writeLittleEndianShort(1)
            wav.writeLittleEndianInt(SAMPLE_RATE)
            wav.writeLittleEndianInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
            wav.writeLittleEndianShort(BYTES_PER_SAMPLE)
            wav.writeLittleEndianShort(BITS_PER_SAMPLE)
            wav.writeBytes("data")
            wav.writeLittleEndianInt(dataBytes.toInt())
        }
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
        write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte()))
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
        write(byteArrayOf(value.toByte(), (value shr 8).toByte()))
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        const val WAV_HEADER_BYTES = 44
    }
}
