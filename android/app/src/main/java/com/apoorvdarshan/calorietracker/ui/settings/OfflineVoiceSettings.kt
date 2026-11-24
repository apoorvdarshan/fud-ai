package com.apoorvdarshan.calorietracker.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelId
import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelIneligibility
import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelManager
import com.apoorvdarshan.calorietracker.services.ondevice.OfflineVoiceNative
import com.apoorvdarshan.calorietracker.services.ondevice.OfflineVoiceTurn
import com.apoorvdarshan.calorietracker.services.speech.AudioRecorder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun OfflineVoiceSettings(models: LocalModelManager, liveActive: Boolean) {
    val id = LocalModelId.LFM3_AUDIO
    val states by models.states.collectAsState()
    val model = states.getValue(id)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember(context, models, scope) { OfflineVoiceSession(context, models, scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available = remember(model.eligible) { model.eligible && OfflineVoiceNative.available }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (voice.acceptPermission && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (granted && !liveActive) voice.start()
            else if (!granted) voice.error = R.string.voice_mic_permission_denied
        }
        voice.acceptPermission = false
    }
    DisposableEffect(lifecycle, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voice.stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            voice.stop()
        }
    }
    LaunchedEffect(liveActive) { if (liveActive) voice.stop() }
    LaunchedEffect(voice.phase) {
        if (voice.phase == OfflineVoicePhase.RECORDING) {
            delay(10_000)
            voice.send()
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.offline_voice), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.offline_voice_description), style = MaterialTheme.typography.bodyMedium)
        LocalModelRow(
            state = if (model.eligible && !available) model.copy(
                eligible = false, ineligibility = LocalModelIneligibility.UNSUPPORTED_ABI
            ) else model,
            onDownload = { models.download(id) },
            onDelete = { if (!voice.busy) scope.launch { models.delete(id) } },
            actionsEnabled = !voice.busy
        )
        if (available && model.executable) {
            when (voice.phase) {
                OfflineVoicePhase.IDLE -> Button(
                    enabled = !liveActive && !voice.busy,
                    modifier = Modifier.testTag("voiceSettings.offline.speak"),
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) voice.start()
                        else {
                            voice.acceptPermission = true
                            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                ) { Text(stringResource(R.string.offline_voice_speak)) }
                OfflineVoicePhase.RECORDING -> {
                    Button(onClick = voice::send) { Text(stringResource(R.string.offline_voice_send)) }
                    TextButton(onClick = voice::stop) { Text(stringResource(R.string.action_cancel)) }
                }
                OfflineVoicePhase.WORKING -> {
                    Text(
                        stringResource(R.string.offline_voice_preparing),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    TextButton(onClick = voice::stop) { Text(stringResource(R.string.action_cancel)) }
                }
                OfflineVoicePhase.SPEAKING -> {
                    Button(onClick = voice::stop) { Text(stringResource(R.string.offline_voice_stop)) }
                }
            }
            voice.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

private enum class OfflineVoicePhase { IDLE, RECORDING, WORKING, SPEAKING }

/** Main-thread UI/audio ownership; inference and native file validation run off main. */
private class OfflineVoiceSession(
    private val context: Context,
    private val models: LocalModelManager,
    private val scope: CoroutineScope
) {
    private val directory = OfflineVoiceCache.directory(context)
    private val recorder = AudioRecorder(context, directory, maxSeconds = 10)
    private var player: MediaPlayer? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var turn by mutableStateOf<OfflineVoiceTurn?>(null)
    private var epoch = 0
    var phase by mutableStateOf(OfflineVoicePhase.IDLE)
        private set
    var error by mutableStateOf<Int?>(null)
    var acceptPermission = false
    val busy get() = phase != OfflineVoicePhase.IDLE || turn != null

    fun start() {
        if (busy || !models.isExecutable(LocalModelId.LFM3_AUDIO)) return
        error = null
        epoch++
        if (recorder.start() != null) phase = OfflineVoicePhase.RECORDING
        else error = R.string.offline_voice_recording_error
    }

    fun send() {
        if (phase != OfflineVoicePhase.RECORDING) return
        val input = recorder.stop() ?: run {
            phase = OfflineVoicePhase.IDLE
            error = R.string.offline_voice_recording_error
            return
        }
        val active = runCatching { OfflineVoiceTurn() }.getOrNull() ?: run {
            input.delete()
            phase = OfflineVoicePhase.IDLE
            error = R.string.offline_voice_reply_error
            return
        }
        turn = active
        phase = OfflineVoicePhase.WORKING
        val request = epoch
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var output: File? = null
            try {
                val reply = File.createTempFile("reply-", ".wav", directory)
                output = reply
                val code = withContext(Dispatchers.IO) {
                    models.useOfflineVoiceFiles { files -> active.reply(files, input, reply) }
                }
                if (request != epoch) return@launch
                when (code) {
                    0 -> play(reply)
                    4 -> fail(R.string.offline_voice_recording_error)
                    else -> fail(R.string.offline_voice_reply_error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (request == epoch) fail(R.string.offline_voice_reply_error)
            } finally {
                active.close()
                turn = null
                input.delete()
                output?.delete()
            }
        }
    }

    private fun play(file: File) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
            .build()
        check(audioManager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focus = request
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(attributes)
        mp.setOnCompletionListener { if (player === it) stop() }
        mp.setOnErrorListener { source, _, _ ->
            if (player === source) fail(R.string.offline_voice_reply_error)
            true
        }
        // MediaPlayer keeps an open fd; the file can be unlinked after prepare.
        file.inputStream().use { mp.setDataSource(it.fd) }
        mp.prepare()
        mp.start()
        phase = OfflineVoicePhase.SPEAKING
    }

    private fun fail(message: Int) {
        stop()
        error = message
    }

    fun stop() {
        epoch++
        acceptPermission = false
        turn?.cancel()
        recorder.cancel()
        player?.release()
        player = null
        focus?.let { audioManager?.abandonAudioFocusRequest(it) }
        focus = null
        phase = OfflineVoicePhase.IDLE
    }
}

private object OfflineVoiceCache {
    private val processFolder = UUID.randomUUID().toString()
    private var cleaned = false

    fun directory(context: Context): File {
        val root = File(context.cacheDir, "fud-offline-voice")
        if (!cleaned) {
            root.listFiles()?.filter { it.name != processFolder }?.forEach { old ->
                old.listFiles()?.forEach { it.delete() }
                old.delete()
            }
            cleaned = true
        }
        return File(root, processFolder).apply { mkdirs() }
    }
}
