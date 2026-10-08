package com.apoorvdarshan.calorietracker.ui.coach

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.services.speech.AudioRecorder
import com.apoorvdarshan.calorietracker.services.speech.NativeSpeechRecognizer
import com.apoorvdarshan.calorietracker.services.speech.SttEvent
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WhatsApp-style inline voice recorder for the Coach input bar.
 *
 *  - Press-and-hold the mic to record; release to send.
 *  - Slide left past the threshold before releasing to cancel (discard).
 *  - A quick tap locks into hands-free recording, exposing explicit Send / Cancel.
 *
 * Honors the user's configured STT provider: NATIVE streams via the on-device
 * recognizer (live partial text); recorded-audio providers record to a file and are
 * transcribed on stop via SpeechService, exactly like VoiceInputSheet.
 */
enum class VoicePhase { Idle, Holding, Locked, Transcribing }

@Stable
class CoachVoiceController(
    context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val onTranscript: (String) -> Unit,
) {
    var phase by mutableStateOf(VoicePhase.Idle)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    var liveText by mutableStateOf("")
        private set
    var cancelArmed by mutableStateOf(false)
        private set

    // Refreshed from composition so begin() records with the current settings.
    var provider: SpeechProvider = SpeechProvider.NATIVE
    var nativeLocale: String? = null

    private val recorder = AudioRecorder(context)
    private val native = NativeSpeechRecognizer(context)
    private var listenJob: Job? = null
    private var timerJob: Job? = null
    private var committed = ""

    val recording: Boolean get() = phase == VoicePhase.Holding || phase == VoicePhase.Locked

    fun hasMicPermission(): Boolean = native.hasMicPermission()

    fun begin() {
        if (phase != VoicePhase.Idle) return
        phase = VoicePhase.Holding
        cancelArmed = false
        committed = ""
        liveText = ""
        startTimer()
        if (provider == SpeechProvider.NATIVE) startNative() else recorder.start()
    }

    fun lock() {
        if (phase == VoicePhase.Holding) {
            phase = VoicePhase.Locked
            cancelArmed = false
        }
    }

    fun updateDrag(dxPx: Float, thresholdPx: Float) {
        if (phase == VoicePhase.Holding) cancelArmed = dxPx < -thresholdPx
    }

    fun cancel() {
        listenJob?.cancel(); listenJob = null
        timerJob?.cancel(); timerJob = null
        if (provider != SpeechProvider.NATIVE) recorder.cancel()
        reset()
    }

    fun stopAndSend() {
        timerJob?.cancel(); timerJob = null
        if (provider == SpeechProvider.NATIVE) {
            listenJob?.cancel(); listenJob = null
            val text = liveText.trim()
            reset()
            if (text.isNotEmpty()) onTranscript(text)
        } else {
            phase = VoicePhase.Transcribing
            scope.launch {
                val text = try {
                    val file = recorder.stop()
                    if (file != null) container.speechService.transcribeRecordedAudio(file).trim() else ""
                } catch (e: Exception) {
                    container.hostedUi.handle(e)
                    ""
                }
                reset()
                if (text.isNotEmpty()) onTranscript(text)
            }
        }
    }

    private fun reset() {
        phase = VoicePhase.Idle
        elapsedMs = 0L
        liveText = ""
        cancelArmed = false
        committed = ""
    }

    private fun startTimer() {
        val startAt = System.currentTimeMillis()
        elapsedMs = 0L
        timerJob = scope.launch {
            while (isActive) {
                elapsedMs = System.currentTimeMillis() - startAt
                delay(100)
            }
        }
    }

    private var nativeMode = CoachNativeMode.OFFLINE_LANGUAGE

    private fun startNative() {
        nativeMode = CoachNativeMode.OFFLINE_LANGUAGE
        launchNativeLoop()
    }

    // Mirrors VoiceInputSheet: spin a fresh recognizer session, accumulating Finals
    // so a pause doesn't end recording. On a language/offline-model error (common on
    // devices with no offline model) fall back offline -> online -> auto-locale; on a
    // transient end-of-session (timeout / no-match / busy / disconnect) just re-arm.
    private fun launchNativeLoop() {
        listenJob?.cancel()
        val mode = nativeMode
        listenJob = scope.launch {
            native.listen(
                locale = if (mode == CoachNativeMode.ONLINE_AUTO) null else nativeLocale,
                preferOffline = mode == CoachNativeMode.OFFLINE_LANGUAGE
            ).collectLatest { ev ->
                when (ev) {
                    is SttEvent.Partial -> liveText = join(committed, ev.text)
                    is SttEvent.Final -> {
                        committed = join(committed, ev.text)
                        liveText = committed
                        if (recording) {
                            delay(250)
                            if (recording) launchNativeLoop()
                        }
                    }
                    is SttEvent.Error -> {
                        val fallback = if (NativeSpeechRecognizer.isLanguageSupportError(ev.code)) {
                            nativeMode.next()
                        } else null
                        if (fallback != null && recording) {
                            nativeMode = fallback
                            delay(300)
                            if (recording) launchNativeLoop()
                            return@collectLatest
                        }
                        if (NativeSpeechRecognizer.isRecoverableSessionError(ev.code) && recording) {
                            delay(300)
                            if (recording) launchNativeLoop()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun join(a: String, b: String): String {
        val bt = b.trim()
        return if (a.isBlank()) bt else if (bt.isBlank()) a else "$a $bt"
    }
}

fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Native STT fallback ladder: on-device model -> online (same locale) -> online (auto). */
private enum class CoachNativeMode { OFFLINE_LANGUAGE, ONLINE_LANGUAGE, ONLINE_AUTO }

private fun CoachNativeMode.next(): CoachNativeMode? = when (this) {
    CoachNativeMode.OFFLINE_LANGUAGE -> CoachNativeMode.ONLINE_LANGUAGE
    CoachNativeMode.ONLINE_LANGUAGE -> CoachNativeMode.ONLINE_AUTO
    CoachNativeMode.ONLINE_AUTO -> null
}

/**
 * The trailing mic button carrying the press/hold/slide gesture. Kept at a stable
 * call site so an in-flight press survives the input bar re-laying-out around it.
 */
@Composable
fun CoachMicButton(controller: CoachVoiceController) {
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted -> the next press records */ }

    val holding = controller.phase == VoicePhase.Holding
    Box(
        modifier = Modifier
            .size(40.dp)
            .scale(if (holding) 1.18f else 1f)
            .clip(CircleShape)
            .background(
                if (holding) AppColors.Calorie
                else AppColors.Calorie.copy(alpha = 0.12f)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!controller.hasMicPermission()) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        do {
                            val e = awaitPointerEvent()
                        } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    val downTime = System.currentTimeMillis()
                    val cancelPx = 110.dp.toPx()
                    val slop = 26.dp.toPx()
                    controller.begin()
                    down.consume()
                    var dx = 0f
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) break
                        dx = ch.position.x - down.position.x
                        controller.updateDrag(dx, cancelPx)
                        ch.consume()
                    }
                    val held = System.currentTimeMillis() - downTime
                    when {
                        dx < -cancelPx -> controller.cancel()
                        held < 240 && kotlin.math.abs(dx) < slop -> controller.lock()
                        else -> controller.stopAndSend()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = stringResource(R.string.cd_hold_to_record),
            tint = if (holding) Color.White else AppColors.Calorie,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** Left-region content shown while recording (replaces the media pill + text field). */
@Composable
fun CoachRecordingIndicator(controller: CoachVoiceController, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "recPulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dotAlpha"
    )
    Row(
        modifier = modifier.padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (controller.phase == VoicePhase.Transcribing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = AppColors.Calorie,
                strokeWidth = 2.dp
            )
            Text(
                "Transcribing…",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            return@Row
        }

        Box(
            Modifier
                .size(9.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(Color(0xFFFF3B30))
        )
        Text(
            formatElapsed(controller.elapsedMs),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        val armed = controller.cancelArmed
        val live = controller.liveText
        Text(
            when {
                controller.phase == VoicePhase.Holding && armed -> stringResource(R.string.voice_release_to_cancel)
                controller.phase == VoicePhase.Holding -> stringResource(R.string.voice_slide_to_cancel)
                live.isNotBlank() -> live
                else -> stringResource(R.string.voice_listening)
            },
            fontSize = 15.sp,
            color = if (armed) Color(0xFFFF3B30)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

/** Small circular cancel (trash) button used in the locked recording state. */
@Composable
fun CoachVoiceCancelButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(R.string.cd_cancel_recording),
            tint = Color(0xFFFF3B30),
            modifier = Modifier.size(18.dp)
        )
    }
}
