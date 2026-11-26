package com.apoorvdarshan.calorietracker.ui.voice

import android.animation.ValueAnimator
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascot as Mascot
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascotProfile
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlin.math.PI
import kotlin.math.sin

internal data class VoiceMascotPose(val bob: Float, val tilt: Float, val blink: Float, val mouth: Float, val stretch: Float) {
    companion object {
        val Still = VoiceMascotPose(0f, 0f, 0f, 0f, 0f)

        fun sample(mode: OrbMode, elapsed: Double, level: Float, animated: Boolean, phase: Double = 0.0): VoiceMascotPose {
            if (!animated) return Still
            val meter = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
            val time = elapsed + phase
            val cycle = time % 4.7
            val blink = maxOf(pulse(cycle, 4.16, 0.28), 0.85f * pulse(cycle, 4.45, 0.20))
            val sway = sin(time * 2.1).toFloat()
            val breath = 0.016f * sway
            return when (mode) {
                OrbMode.IDLE -> {
                    val beat = time % 5.2
                    val hop = pulse(beat, 0.44, 0.68)
                    val squash = pulse(beat, 0.20, 0.24) + pulse(beat, 1.07, 0.26)
                    VoiceMascotPose(0.012f * sway - 0.042f * hop, 1.8f * sway + 2f * hop,
                        blink, 0f, breath + 0.044f * hop - 0.046f * squash)
                }
                OrbMode.LISTENING -> {
                    val nod = pulse(time % 3.4, 0.3, 0.55)
                    VoiceMascotPose(0.008f * sway - 0.014f - 0.022f * meter + 0.017f * nod,
                        -3.6f + sway - 2.1f * meter + 2.4f * nod, blink, 0f, breath + 0.025f * meter - 0.021f * nod)
                }
                OrbMode.THINKING -> {
                    val ponder = sin(time * 2.4).toFloat()
                    VoiceMascotPose(-0.008f - 0.014f * ponder, 5.5f * ponder, blink, 0f, -0.012f - breath)
                }
                OrbMode.SPEAKING -> {
                    val mouth = ((meter - 0.16f) / 0.84f).coerceAtLeast(0f)
                    VoiceMascotPose(0.009f * sway - 0.034f * mouth, (2f + mouth) * sway,
                        blink, mouth, breath + 0.052f * mouth)
                }
            }
        }

        private fun pulse(time: Double, start: Double, duration: Double): Float {
            val fraction = (time - start) / duration
            if (fraction <= 0 || fraction >= 1) return 0f
            val wave = sin(PI * fraction)
            return (wave * wave).toFloat()
        }
    }
}

@Composable
internal fun VoiceMascot(
    mode: OrbMode,
    modifier: Modifier = Modifier,
    profile: VoiceMascotProfile = VoiceMascotProfile(),
    motionEnabled: Boolean = true,
    compact: Boolean = false,
    level: () -> Float = { 0f }
) {
    val mascot = profile.mascot
    if (mascot == Mascot.ORB) {
        LiveOrb(
            mode = mode,
            tint = profile.options.color.rgb?.let { Color(0xFF000000L or it.toLong()) } ?: AppColors.Calorie,
            modifier = modifier.scale(1f / 0.56f),
            level = level,
            clearBackground = true,
            motionEnabled = motionEnabled
        )
        return
    }
    val body = ImageBitmap.imageResource(mascot.body)
    val eyelids = ImageBitmap.imageResource(mascot.blink)
    val tint = profile.options.color.rgb?.let { ColorFilter.tint(Color(0xFF000000L or it.toLong())) }
    val colorLayer = if (tint != null) ImageBitmap.imageResource(mascot.colorLayer) else null
    val blinkTint = if (tint != null) mascot.blinkColor?.let { ImageBitmap.imageResource(it) } else null
    val currentMode by rememberUpdatedState(mode)
    val currentLevel by rememberUpdatedState(level)
    val phase by rememberUpdatedState(mascot.ordinal * 0.17)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animated = motionEnabled && lifecycle.isAtLeast(Lifecycle.State.STARTED) && ValueAnimator.areAnimatorsEnabled()
    var pose by remember { mutableStateOf(VoiceMascotPose.Still) }
    LaunchedEffect(animated) {
        if (!animated) {
            pose = VoiceMascotPose.Still
            return@LaunchedEffect
        }
        var last = Long.MIN_VALUE
        var start = Long.MIN_VALUE
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val interval = if (compact && currentMode == OrbMode.IDLE) 56_000_000L else 30_000_000L
                if (last == Long.MIN_VALUE || now - last >= interval) {
                    if (start == Long.MIN_VALUE) start = now
                    pose = VoiceMascotPose.sample(currentMode, (now - start) / 1e9, currentLevel(),
                        animated = true, phase = phase)
                    last = now
                }
            }
        }
    }
    Canvas(modifier) {
        val frame = if (animated) pose else VoiceMascotPose.Still
        val side = size.minDimension.toInt()
        if (side <= 0) return@Canvas
        val spriteSize = IntSize(side, side)
        val bob = frame.bob * (if (compact) 1.3f else 1f)
        val tilt = frame.tilt * (if (compact) 1.45f else 1f)
        withTransform({
            translate((size.width - side) / 2, (size.height - side) / 2 + side * bob)
            rotate(tilt, Offset(side / 2f, side / 2f))
            scale(1 - 0.65f * frame.stretch, 1 + frame.stretch, Offset(side / 2f, side.toFloat()))
        }) {
            drawImage(body, dstSize = spriteSize)
            if (colorLayer != null) drawImage(colorLayer, dstSize = spriteSize, colorFilter = tint)
            if (frame.blink > 0f) {
                drawImage(eyelids, dstSize = spriteSize, alpha = frame.blink)
                if (blinkTint != null) drawImage(blinkTint, dstSize = spriteSize, alpha = frame.blink, colorFilter = tint)
            }
            val x = side * mascot.mouthX
            val y = side * mascot.mouthY
            val ink = Color(0xFF3B0426)
            val halfWidth = side * (0.031f + frame.mouth * 0.022f)
            val path = Path().apply { moveTo(x - halfWidth, y) }
            if (frame.mouth < 0.06f) {
                path.quadraticTo(x, y + side * 0.022f, x + halfWidth, y)
                drawPath(path, ink, style = Stroke(side * 0.009f, cap = StrokeCap.Round))
            } else {
                val h = side * (0.012f + frame.mouth * 0.055f)
                path.quadraticTo(x, y - h * 0.68f, x + halfWidth, y)
                path.quadraticTo(x, y + h * 1.55f, x - halfWidth, y)
                path.close()
                drawPath(path, ink)
                if (frame.mouth > 0.32f) {
                    drawOval(Color(0xFFFF598C), Offset(x - halfWidth * 0.49f, y + h * 0.35f),
                        Size(halfWidth * 0.98f, h * 0.30f))
                }
            }
        }
    }
}
