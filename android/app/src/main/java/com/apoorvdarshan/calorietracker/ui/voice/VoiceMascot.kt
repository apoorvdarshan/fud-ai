package com.apoorvdarshan.calorietracker.ui.voice

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import kotlin.math.abs
import kotlin.math.sin

internal data class VoiceMascotPose(val bob: Float, val tilt: Float, val blink: Float, val mouth: Float) {
    companion object {
        val Still = VoiceMascotPose(0f, 0f, 0f, 0f)

        fun sample(mode: OrbMode, time: Double, level: Float, animated: Boolean): VoiceMascotPose {
            if (!animated) return Still
            val meter = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
            val blink = (1 - abs(time % 4.7 - 4.3) / 0.105).coerceAtLeast(0.0).toFloat()
            val sway = sin(time * 2.1).toFloat()
            return when (mode) {
                OrbMode.IDLE -> VoiceMascotPose(0.014f * sway, sway, blink, 0f)
                OrbMode.LISTENING -> VoiceMascotPose(0.012f * sway - 0.022f * meter, sway - 2.5f * meter, blink, 0f)
                OrbMode.THINKING -> VoiceMascotPose(0.020f * sway, 3f * sin(time * 2.7).toFloat(), blink, 0f)
                OrbMode.SPEAKING -> {
                    val mouth = ((meter - 0.16f) / 0.84f).coerceAtLeast(0f)
                    VoiceMascotPose(0.010f * sway - 0.020f * mouth, 1.7f * sway, blink, mouth)
                }
            }
        }
    }
}

@Composable
internal fun VoiceMascot(mode: OrbMode, modifier: Modifier = Modifier, level: () -> Float = { 0f }) {
    val body = ImageBitmap.imageResource(R.drawable.voice_mascot)
    val eyelids = ImageBitmap.imageResource(R.drawable.voice_mascot_blink)
    val currentMode by rememberUpdatedState(mode)
    val currentLevel by rememberUpdatedState(level)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animated = lifecycle.isAtLeast(Lifecycle.State.STARTED) && ValueAnimator.areAnimatorsEnabled()
    var pose by remember { mutableStateOf(VoiceMascotPose.Still) }
    LaunchedEffect(animated) {
        if (!animated) {
            pose = VoiceMascotPose.Still
            return@LaunchedEffect
        }
        var last = Long.MIN_VALUE
        while (true) {
            withFrameNanos { now ->
                if (last == Long.MIN_VALUE || now - last >= 30_000_000L) {
                    pose = VoiceMascotPose.sample(currentMode, now / 1e9, currentLevel(), animated = true)
                    last = now
                }
            }
        }
    }
    Canvas(modifier) {
        val frame = pose
        val side = size.minDimension.toInt()
        if (side <= 0) return@Canvas
        val spriteSize = IntSize(side, side)
        withTransform({
            translate((size.width - side) / 2, (size.height - side) / 2 + side * frame.bob)
            rotate(frame.tilt, Offset(side / 2f, side / 2f))
        }) {
            drawImage(body, dstSize = spriteSize)
            if (frame.blink > 0f) drawImage(eyelids, dstSize = spriteSize, alpha = frame.blink)
            val x = side * 0.477f
            val y = side * 0.787f
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
