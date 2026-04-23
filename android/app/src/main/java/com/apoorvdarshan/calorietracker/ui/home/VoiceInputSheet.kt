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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.services.speech.AudioRecorder
import com.apoorvdarshan.calorietracker.services.speech.NativeSpeechRecognizer
import com.apoorvdarshan.calorietracker.services.speech.SttEvent
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

private enum class VoicePhase { IDLE, RECORDING, REVIEWING, TRANSCRIBING }

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

    val provider by container.prefs.selectedSpeechProvider.collectAsState(initial = SpeechProvider.NATIVE)

    var phase by remember { mutableStateOf(VoicePhase.IDLE) }
    var transcript by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val recorder = remember(ctx) { AudioRecorder(ctx) }
    val native = remember(ctx) { NativeSpeechRecognizer(ctx) }
    var recordedFile by remember { mutableStateOf<File?>(null) }
    var nativeJob by remember { mutableStateOf<Job?>(null) }

    // Reusable "start a fresh recording" lambda — called from auto-start on
    // sheet open, from the mic-tap retry in REVIEWING phase, and (implicitly)
    // never again until one of those triggers fires.
    fun startRecordingNow() {
        transcript = ""
        error = null
        if (provider == SpeechProvider.NATIVE) {
            phase = VoicePhase.RECORDING
            nativeJob?.cancel()
            nativeJob = scope.launch {
                native.listen().collectLatest { event ->
                    when (event) {
                        is SttEvent.Partial -> transcript = event.text
                        is SttEvent.Final -> {
                            transcript = event.text
                            phase = VoicePhase.REVIEWING
                        }
                        is SttEvent.Error -> {
                            error = event.message
                            phase = VoicePhase.IDLE
                        }
                        else -> Unit
                    }
                }
            }
        } else {
            val file = recorder.start()
            if (file == null) {
                error = "Couldn't start the mic. Check permissions."
            } else {
                recordedFile = file
                phase = VoicePhase.RECORDING
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecordingNow()
        else error = "Microphone permission denied."
    }

    // Mirror iOS `onAppear { startRecording() }` — start listening as soon as
    // the sheet opens, requesting mic permission first if needed.
    LaunchedEffect(Unit) {
        if (native.hasMicPermission()) startRecordingNow()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) {
        onDispose {
            nativeJob?.cancel()
            recorder.cancel()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            nativeJob?.cancel()
            recorder.cancel()
            onDismiss()
        },
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
                    provider.displayName,
                    color = AppColors.Calorie,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(20.dp))

            // Always-visible transcript box (gray rounded surface). Shows placeholder
            // when empty, "Transcribing…" while remote upload is running, or the live
            // transcript otherwise.
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
                                "Transcribing via ${provider.displayName}…",
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
                    else -> {
                        Text(
                            if (phase == VoicePhase.RECORDING) "Listening…" else "Tap the mic to start",
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
                            VoicePhase.IDLE -> startRecordingNow()
                            VoicePhase.RECORDING -> {
                                if (provider == SpeechProvider.NATIVE) {
                                    nativeJob?.cancel()
                                    phase = VoicePhase.REVIEWING
                                } else {
                                    val file = recorder.stop()
                                    if (file != null) {
                                        phase = VoicePhase.TRANSCRIBING
                                        scope.launch {
                                            try {
                                                transcript = container.speechService.transcribeRemote(file)
                                                phase = VoicePhase.REVIEWING
                                            } catch (e: Throwable) {
                                                error = e.localizedMessage ?: "Transcription failed"
                                                phase = VoicePhase.IDLE
                                            }
                                        }
                                    } else {
                                        phase = VoicePhase.IDLE
                                    }
                                }
                            }
                            // Tapping the mic again after a transcript is shown
                            // discards it and starts a fresh recording — same
                            // "retry" behavior the user expects.
                            VoicePhase.REVIEWING -> startRecordingNow()
                            VoicePhase.TRANSCRIBING -> Unit
                        }
                    }
                )
            }

            // Analyze / Cancel — iOS match: borderedProminent pink capsule, then
            // a secondary Cancel text button. Native is one-tap (stops the live
            // recognizer and submits in one click); remote is two-tap (mic to
            // stop+transcribe, then Analyze on the reviewed transcript).
            val canAnalyze = transcript.trim().isNotEmpty() && phase != VoicePhase.TRANSCRIBING
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (provider == SpeechProvider.NATIVE && phase == VoicePhase.RECORDING) {
                        nativeJob?.cancel()
                        phase = VoicePhase.REVIEWING
                    }
                    if (transcript.trim().isNotEmpty()) onSubmit(transcript.trim())
                },
                enabled = canAnalyze,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Analyze", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                nativeJob?.cancel()
                recorder.cancel()
                onDismiss()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
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
            contentDescription = if (recording) "Stop" else "Record",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}
