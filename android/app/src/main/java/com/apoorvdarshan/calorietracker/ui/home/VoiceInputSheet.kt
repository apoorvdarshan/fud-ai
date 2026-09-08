package com.apoorvdarshan.calorietracker.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.services.speech.AudioRecorder
import com.apoorvdarshan.calorietracker.services.speech.NativeSpeechRecognizer
import com.apoorvdarshan.calorietracker.services.speech.SttEvent
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class NativeRecognitionMode {
    OFFLINE_LANGUAGE,
    ONLINE_LANGUAGE,
    ONLINE_AUTO
}

private fun NativeRecognitionMode.nextAfterLanguageError(): NativeRecognitionMode? = when (this) {
    NativeRecognitionMode.OFFLINE_LANGUAGE -> NativeRecognitionMode.ONLINE_LANGUAGE
    NativeRecognitionMode.ONLINE_LANGUAGE -> NativeRecognitionMode.ONLINE_AUTO
    NativeRecognitionMode.ONLINE_AUTO -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInputSheet(
    container: AppContainer,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Wait for the persisted configuration before auto-starting so a remote
    // provider is never briefly mistaken for the native default.
    val persistedProvider by container.prefs.selectedSpeechProvider.collectAsState(initial = null)
    val configuredProvider = persistedProvider ?: return
    val persistedSpeechLanguage by container.prefs.selectedSpeechLanguage(configuredProvider)
        .collectAsState(initial = null)
    val configuredLanguage = persistedSpeechLanguage ?: return
    val draft = rememberSaveable(saver = VoiceInputDraftState.Saver) {
        VoiceInputDraftState(configuredProvider, configuredLanguage)
    }
    val provider = draft.provider
    val speechLanguage = draft.speechLanguage
    val micDeniedMsg = stringResource(R.string.voice_mic_permission_denied)
    val micStartFailedMsg = stringResource(R.string.voice_mic_start_failed)
    val transcriptionFailedMsg = stringResource(R.string.voice_transcription_failed)

    var phase by draft::phase
    var transcript by draft::transcript
    // Native SpeechRecognizer naturally finalizes after a silence window. To
    // make recording continuous (no auto-stop), we accumulate every final
    // segment here and immediately re-arm the recognizer; the displayed
    // [transcript] is committed + the in-flight partial.
    var committed by draft::committed
    var error by draft::error
    val recorder = remember(ctx) { AudioRecorder(ctx) }
    val native = remember(ctx) { NativeSpeechRecognizer(ctx) }
    var nativeJob by remember { mutableStateOf<Job?>(null) }
    var transcriptionJob by remember { mutableStateOf<Job?>(null) }
    var nativeRecognitionMode by remember { mutableStateOf(NativeRecognitionMode.OFFLINE_LANGUAGE) }
    var permissionRequestedHere by remember { mutableStateOf(false) }

    fun discardRecording() {
        transcriptionJob?.cancel()
        recorder.cancel()
        draft.recordedFilePath?.let { File(it).delete() }
        draft.recordedFilePath = null
    }

    fun dismiss() {
        nativeJob?.cancel()
        discardRecording()
        onDismiss()
    }

    fun transcribeRecording() {
        val file = draft.recordedFilePath?.let(::File)?.takeIf { it.isFile }
        if (file == null) {
            draft.recordedFilePath = null
            error = transcriptionFailedMsg
            phase = VoicePhase.IDLE
            return
        }
        error = null
        phase = VoicePhase.TRANSCRIBING
        transcriptionJob = scope.launch {
            var uploadFile: File? = null
            try {
                // SpeechService deletes its input after every attempt. Retain the
                // original so rotation/cancellation can offer a deliberate retry.
                val copy = withContext(Dispatchers.IO) {
                    File.createTempFile("transcribe-", ".wav", file.parentFile).also {
                        uploadFile = it
                        file.copyTo(it, overwrite = true)
                    }
                }
                transcript = container.speechService.transcribeRecordedAudio(copy)
                committed = transcript
                phase = VoicePhase.REVIEWING
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                error = e.localizedMessage ?: transcriptionFailedMsg
                phase = VoicePhase.REVIEWING
            } finally {
                uploadFile?.delete()
            }
        }
    }

    // Internal helper: spin up a fresh native recognizer session, appending
    // to [committed] on each Final event so a long pause doesn't end the
    // recording session. Only invoked while phase == RECORDING and provider
    // == NATIVE.
    //
    // Re-arming after Final / soft errors is what makes the recording feel
    // continuous (Android's online SpeechRecognizer naturally tears down
    // after silence and on transient server hiccups). A small delay before
    // re-arm avoids the "ERROR_SERVER_DISCONNECTED (11)" / "RECOGNIZER_BUSY
    // (8)" loop that Android's speech service throws when you start a new
    // session with the previous one not fully torn down.
    fun launchNativeListenerLoop() {
        nativeJob?.cancel()
        val recognitionMode = nativeRecognitionMode
        nativeJob = scope.launch {
            native.listen(
                locale = if (recognitionMode == NativeRecognitionMode.ONLINE_AUTO) null else speechLanguage.nativeLocaleTag(),
                preferOffline = recognitionMode == NativeRecognitionMode.OFFLINE_LANGUAGE
            ).collectLatest { event ->
                when (event) {
                    is SttEvent.Partial -> {
                        transcript = (committed + " " + event.text).trim()
                    }
                    is SttEvent.Final -> {
                        committed = (committed + " " + event.text).trim()
                        transcript = committed
                        if (phase == VoicePhase.RECORDING) {
                            kotlinx.coroutines.delay(250)
                            if (phase == VoicePhase.RECORDING) launchNativeListenerLoop()
                        }
                    }
                    is SttEvent.Error -> {
                        val fallbackMode = if (NativeSpeechRecognizer.isLanguageSupportError(event.code)) {
                            recognitionMode.nextAfterLanguageError()
                        } else {
                            null
                        }
                        if (fallbackMode != null && phase == VoicePhase.RECORDING) {
                            nativeRecognitionMode = fallbackMode
                            kotlinx.coroutines.delay(300)
                            if (phase == VoicePhase.RECORDING) launchNativeListenerLoop()
                            return@collectLatest
                        }

                        // Codes that mean "session ended, try again" are part
                        // of normal continuous-listening — just re-arm without
                        // showing the user an error or dropping back to IDLE.
                        // 6 = SPEECH_TIMEOUT, 7 = NO_MATCH,
                        // 8 = RECOGNIZER_BUSY, 11 = SERVER_DISCONNECTED.
                        val recoverable = NativeSpeechRecognizer.isRecoverableSessionError(event.code)
                        if (recoverable && phase == VoicePhase.RECORDING) {
                            kotlinx.coroutines.delay(300)
                            if (phase == VoicePhase.RECORDING) launchNativeListenerLoop()
                        } else {
                            error = event.message
                            phase = VoicePhase.IDLE
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun startRecordingNow() {
        discardRecording()
        transcript = ""
        committed = ""
        error = null
        if (provider == SpeechProvider.NATIVE) {
            nativeRecognitionMode = NativeRecognitionMode.OFFLINE_LANGUAGE
            phase = VoicePhase.RECORDING
            launchNativeListenerLoop()
        } else {
            val file = recorder.start()
            if (file == null) {
                error = micStartFailedMsg
            } else {
                draft.recordedFilePath = file.absolutePath
                phase = VoicePhase.RECORDING
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && permissionRequestedHere) {
            startRecordingNow()
        } else if (!granted) {
            error = micDeniedMsg
        }
        permissionRequestedHere = false
    }

    fun requestRecording() {
        if (native.hasMicPermission()) {
            startRecordingNow()
        } else {
            permissionRequestedHere = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        if (!draft.autoStartAttempted) {
            draft.autoStartAttempted = true
            requestRecording()
        }
    }

    DisposableEffect(recorder, native) {
        onDispose {
            nativeJob?.cancel()
            transcriptionJob?.cancel()
            // The filename was saved when capture began. Finalize its WAV header
            // on recreation, without deleting the captured audio or reopening mic.
            recorder.stop()
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Provider pill — pink capsule with a bolt + provider name (mirrors iOS).
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(AppColors.Calorie.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = AppColors.Calorie,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(provider.displayNameRes),
                    color = AppColors.Calorie,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(20.dp))

            // Always-visible transcript box (gray rounded surface). Shows placeholder
            // when empty, "Transcribing…" while remote upload is running, or the live
            // transcript otherwise.
            val hasPendingAudio = draft.recordedFilePath != null && transcript.isBlank()
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                when {
                    phase == VoicePhase.TRANSCRIBING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = AppColors.Calorie,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                stringResource(R.string.voice_transcribing_format, stringResource(provider.displayNameRes)),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    transcript.isNotEmpty() -> {
                        // Editable in REVIEW phase, read-only otherwise (live partial).
                        if (phase == VoicePhase.REVIEWING) {
                            OutlinedTextField(
                                value = transcript,
                                onValueChange = { transcript = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(transcript, fontSize = 16.sp)
                        }
                    }
                    hasPendingAudio && phase != VoicePhase.RECORDING -> {
                        Text(
                            stringResource(R.string.voice_recording_saved),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    else -> {
                        Text(
                            if (phase == VoicePhase.RECORDING) stringResource(R.string.voice_listening) else stringResource(R.string.voice_tap_to_start),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                contentAlignment = Alignment.Center
            ) {
                MicButton(
                    phase = phase,
                    onToggle = {
                        when (phase) {
                            // IDLE only happens after an error or initial gate
                            // before mic permission. Tapping the mic restarts.
                            VoicePhase.IDLE -> requestRecording()
                            VoicePhase.RECORDING -> {
                                if (provider == SpeechProvider.NATIVE) {
                                    nativeJob?.cancel()
                                    phase = VoicePhase.REVIEWING
                                } else {
                                    val file = recorder.stop()
                                    if (file != null) {
                                        draft.recordedFilePath = file.absolutePath
                                        transcribeRecording()
                                    } else {
                                        draft.recordedFilePath = null
                                        phase = VoicePhase.IDLE
                                    }
                                }
                            }
                            // Tapping the mic again after a transcript is shown
                            // discards it and starts a fresh recording — same
                            // "retry" behavior the user expects.
                            VoicePhase.REVIEWING -> requestRecording()
                            VoicePhase.TRANSCRIBING -> Unit
                        }
                    }
                )
            }

            // Analyze / Cancel — iOS match: borderedProminent pink capsule, then
            // a secondary Cancel text button. Native is one-tap (stops the live
            // recognizer and submits in one click); recorded-audio STT is two-tap (mic to
            // stop+transcribe, then Analyze on the reviewed transcript).
            val canTranscribe = hasPendingAudio && phase != VoicePhase.RECORDING
            val canAnalyze = (transcript.isNotBlank() || canTranscribe) && phase != VoicePhase.TRANSCRIBING
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (canTranscribe) {
                        transcribeRecording()
                        return@Button
                    }
                    if (provider == SpeechProvider.NATIVE && phase == VoicePhase.RECORDING) {
                        nativeJob?.cancel()
                        phase = VoicePhase.REVIEWING
                    }
                    if (transcript.trim().isNotEmpty()) {
                        discardRecording()
                        onSubmit(transcript.trim())
                    }
                },
                enabled = canAnalyze,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    stringResource(if (canTranscribe) R.string.voice_transcribe else R.string.action_analyze),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = ::dismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun MicButton(phase: VoicePhase, onToggle: () -> Unit) {
    val recording = phase == VoicePhase.RECORDING
    // iOS has a slow 0.8s ease-in-out pulse to 1.15x while recording.
    val infinite = rememberInfiniteTransition(label = "micPulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (recording) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val scale by animateFloatAsState(
        targetValue = if (recording) pulse else 1f,
        animationSpec = tween(200),
        label = "micScale"
    )
    val bgBrush = if (recording)
        Brush.linearGradient(listOf(Color(0xFFFF3B30), Color(0xFFFF6B60))) // iOS red.fill
    else
        Brush.linearGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd))
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size((80 * scale).dp)
            .clip(CircleShape)
            .background(bgBrush)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (recording) Icons.Filled.Mic else Icons.Filled.MicNone,
            contentDescription = if (recording) stringResource(R.string.voice_stop) else stringResource(R.string.voice_record),
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}
