package com.apoorvdarshan.calorietracker.ui.voice

import android.animation.ValueAnimator
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import com.apoorvdarshan.calorietracker.services.voice.VoiceLog
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The live assistant's orb: a holographic sphere of rings, glowing filaments and sparks
 * around a bright core, which swells with the voice of whoever is talking. Android 13+
 * draws the iOS Metal shader (ios/calorietracker/Views/LiveOrb/LiveOrb.metal) ported to
 * AGSL; older versions draw an animated Canvas version of the same parts. It animates
 * only while on screen and the app is started, and stays still when system animations
 * are off.
 *
 * [clearBackground] lets the orb glow over the overlay's dark scrim; otherwise it draws
 * its own deep background.
 */
@Composable
fun LiveOrb(
    mode: OrbMode,
    tint: Color,
    modifier: Modifier = Modifier,
    level: () -> Float = { 0f },
    clearBackground: Boolean = false,
    motionEnabled: Boolean = true
) {
    val currentMode by rememberUpdatedState(mode)
    val currentLevel by rememberUpdatedState(level)
    val motion = remember { LiveOrbMotion() }
    var frame by remember { mutableStateOf(motion.still(mode)) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val animate = motionEnabled && visible && ValueAnimator.areAnimatorsEnabled()

    LaunchedEffect(animate) {
        if (!animate) {
            frame = motion.still(currentMode)
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { nanos ->
                frame = motion.advance(nanos, currentMode, currentLevel())
            }
            // A resting orb (the Home button) drifts slowly; half the frame rate saves battery.
            if (currentMode == OrbMode.IDLE && frame.active < 0.02f) delay(IDLE_FRAME_MS)
        }
    }

    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) OrbShader.create() else null }
    if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val brush = remember(shader) { ShaderBrush(shader) }
        Canvas(modifier) {
            val current = frame
            OrbShader.bind(shader, size.width, size.height, current, tint, clearBackground)
            drawRect(brush)
        }
    } else {
        FallbackLiveOrb(frame, tint, modifier, clearBackground)
    }
}

/** Shared fallback entry point, also rendered directly by the visual regression tests. */
@Composable
internal fun FallbackLiveOrb(frame: LiveOrbFrame, tint: Color, modifier: Modifier, clearBackground: Boolean) {
    Canvas(modifier.graphicsLayer {
        // Add the filaments together in a transparent buffer, rather than into
        // the screen behind them, where Plus would wash out on light themes.
        compositingStrategy = if (clearBackground) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }) {
        drawFallbackOrb(frame, tint, clearBackground)
    }
}

private const val IDLE_FRAME_MS = 16L

/** Phases and eased moods for one frame. The shader only sees phases, never speeds. */
data class LiveOrbFrame(
    val time: Float = 0f,
    val spin: Float = 0f,
    val flow: Float = 0f,
    val level: Float = 0f,
    val active: Float = 0f,
    val thinking: Float = 0f,
    val speaking: Float = 0f
)

/**
 * Eases the orb between moods and keeps its rotation continuous when its speed changes
 * (LiveOrbMotion in LiveOrbView.swift).
 */
class LiveOrbMotion {
    private var start = -1L
    private var last = -1L
    private var frame = LiveOrbFrame()

    fun advance(nanos: Long, mode: OrbMode, level: Float): LiveOrbFrame {
        if (start < 0) start = nanos
        val dt = if (last < 0) 0f else ((nanos - last) / 1e9f).coerceIn(0f, 0.1f)
        last = nanos

        fun approach(value: Float, target: Float, rate: Float): Float = value + (target - value) * (1 - exp(-rate * dt))

        val loudness = level.coerceIn(0f, 1f)
        // Rise fast with the voice, fall back more slowly, like a VU meter.
        val newLevel = approach(frame.level, loudness, if (loudness > frame.level) 24f else 7f)
        val active = approach(frame.active, if (mode == OrbMode.IDLE) 0f else 1f, 3f)
        val thinking = approach(frame.thinking, if (mode == OrbMode.THINKING) 1f else 0f, 4f)
        val speaking = approach(frame.speaking, if (mode == OrbMode.SPEAKING) 1f else 0f, 4f)
        frame = LiveOrbFrame(
            time = (nanos - start) / 1e9f,
            spin = frame.spin + dt * (0.22f + 0.18f * active + 1.5f * thinking + 0.6f * newLevel),
            flow = frame.flow + dt * (0.25f + 0.3f * active + 0.5f * thinking + 2.4f * newLevel),
            level = newLevel,
            active = active,
            thinking = thinking,
            speaking = speaking
        )
        return frame
    }

    /** A settled frame for the given mood, for when animations are off. */
    fun still(mode: OrbMode): LiveOrbFrame = frame.copy(
        level = 0f,
        active = if (mode == OrbMode.IDLE) 0f else 1f,
        thinking = if (mode == OrbMode.THINKING) 1f else 0f,
        speaking = if (mode == OrbMode.SPEAKING) 1f else 0f
    ).also { frame = it }
}

// MARK: - AGSL (Android 13+)

internal object OrbShader {
    /** Compiles the shader and checks its uniforms; throws with Skia's message if it can't. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun compile(): RuntimeShader =
        RuntimeShader(SOURCE).also { bind(it, 1f, 1f, LiveOrbFrame(), Color.White, clear = false) }

    /** Null when the shader doesn't compile here; the Canvas version is drawn instead. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun create(): RuntimeShader? = runCatching { compile() }
        .onFailure { VoiceLog.warn("LiveOrb", "AGSL orb unavailable: ${it.message}") }
        .getOrNull()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun bind(shader: RuntimeShader, width: Float, height: Float, frame: LiveOrbFrame, tint: Color, clear: Boolean) {
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("time", frame.time)
        shader.setFloatUniform("spin", frame.spin)
        shader.setFloatUniform("flow", frame.flow)
        shader.setFloatUniform("level", frame.level)
        shader.setFloatUniform("activity", frame.active)
        shader.setFloatUniform("thinking", frame.thinking)
        shader.setFloatUniform("speaking", frame.speaking)
        shader.setFloatUniform("tint", tint.red, tint.green, tint.blue)
        shader.setFloatUniform("clearBackground", if (clear) 1f else 0f)
    }

    // A line-by-line port of LiveOrb.metal. Coordinates are in units of the orb's radius;
    // reversed smoothstep edges are written as 1 - smoothstep, which AGSL defines.
    private const val SOURCE = """
uniform float2 size;
uniform float time;
uniform float spin;
uniform float flow;
uniform float level;
uniform float activity;
uniform float thinking;
uniform float speaking;
uniform float3 tint;
uniform float clearBackground;

const float PI = 3.14159265;

float hash13(float3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float noise3(float3 x) {
    float3 i = floor(x);
    float3 f = fract(x);
    float3 u = f * f * (3.0 - 2.0 * f);
    float a = hash13(i);
    float b = hash13(i + float3(1.0, 0.0, 0.0));
    float c = hash13(i + float3(0.0, 1.0, 0.0));
    float d = hash13(i + float3(1.0, 1.0, 0.0));
    float e = hash13(i + float3(0.0, 0.0, 1.0));
    float g = hash13(i + float3(1.0, 0.0, 1.0));
    float h = hash13(i + float3(0.0, 1.0, 1.0));
    float k = hash13(i + float3(1.0, 1.0, 1.0));
    return mix(mix(mix(a, b, u.x), mix(c, d, u.x), u.y),
               mix(mix(e, g, u.x), mix(h, k, u.x), u.y), u.z);
}

float3 rotateX(float3 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return float3(p.x, c * p.y - s * p.z, s * p.y + c * p.z);
}

float3 rotateY(float3 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return float3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
}

float ring(float2 p, float radius, float3 n, float width, float dashes, float dashShift) {
    float rr = radius * radius - dot(p, p);
    if (rr <= 0.0) return 0.0;
    float h = sqrt(rr);
    float3 u = normalize(abs(n.y) < 0.95 ? cross(n, float3(0.0, 1.0, 0.0)) : cross(n, float3(1.0, 0.0, 0.0)));
    float3 v = cross(n, u);
    float total = 0.0;
    for (int side = 0; side < 2; side++) {
        float z = side == 0 ? h : -h;
        float3 s = float3(p, z);
        float2 slope = n.xy - n.z * p / z;
        float d = abs(dot(s, n)) / max(length(slope), 0.05);
        float glowLine = exp(-(d * d) / (width * width)) + 0.22 * exp(-d / (width * 5.0));
        if (dashes > 0.0) {
            float angle = atan(dot(s, v), dot(s, u)) / (2.0 * PI) + 0.5;
            float dash = fract(angle * dashes + dashShift);
            glowLine *= smoothstep(0.0, 0.06, dash) * (1.0 - smoothstep(0.66, 0.78, dash));
        }
        total += glowLine * (side == 0 ? 1.0 : 0.3);
    }
    return total * smoothstep(0.0, 0.003, rr);
}

float filaments(float3 s, float phase, float energy) {
    float3 q = s * 3.0 + float3(0.0, phase * 0.35, phase * 0.2);
    float n = noise3(q) * 0.62 + noise3(q * 2.1 + 7.3) * 0.28 + noise3(q * 4.4 - 3.1) * 0.1;
    float m = noise3(q * 1.3 - 11.0 + phase * 0.15);
    float veins = pow(1.0 - abs(n * 2.0 - 1.0), mix(34.0, 24.0, energy));
    float second = pow(1.0 - abs(m * 2.0 - 1.0), mix(48.0, 32.0, energy)) * 0.45;
    return veins + second;
}

float sparks(float3 s, float t) {
    float3 cell = floor(s * 8.0);
    float rnd = hash13(cell);
    if (rnd < 0.7) return 0.0;
    float3 offset = fract(s * 8.0) - 0.5;
    float twinkle = 0.5 + 0.5 * sin(t * (1.5 + rnd * 5.0) + rnd * 40.0);
    return exp(-dot(offset, offset) * 90.0) * twinkle * twinkle;
}

float iris(float2 p, float radius, float width, float dashes, float shift) {
    float d = abs(length(p) - radius);
    float angle = atan(p.y, p.x) / (2.0 * PI) + 0.5;
    float dash = fract(angle * dashes + shift);
    float gate = smoothstep(0.0, 0.05, dash) * (1.0 - smoothstep(0.6, 0.7, dash));
    return (exp(-(d * d) / (width * width)) + 0.2 * exp(-d / (width * 4.0))) * gate;
}

half4 main(float2 position) {
    float scale = min(size.x, size.y) * 0.5;
    float2 raw = (position - size * 0.5) / scale;

    float energy = clamp(0.22 * activity + 0.9 * level + 0.3 * speaking + 0.25 * thinking, 0.0, 1.3);
    float breathe = 1.0 + 0.015 * sin(time * 1.7) * (1.0 - activity * 0.5);
    float radius = 0.56 * breathe * (1.0 + 0.07 * level);
    float2 p = raw / radius;
    float r = length(p);

    float glow = 0.0;

    if (r < 1.6) {
        float3 n1 = rotateY(rotateX(float3(0.0, 1.0, 0.0), 1.15), spin * 0.9);
        float3 n2 = rotateY(rotateX(float3(0.0, 0.0, 1.0), 0.85 + 0.25 * sin(spin * 0.6)), -spin * 0.55);
        float3 n3 = rotateY(rotateX(float3(0.0, 1.0, 0.0), 0.32), -spin * 0.7 + 1.0);
        float3 n4 = normalize(float3(cos(spin * 0.45 + 2.0), 0.45 * sin(spin * 0.45 + 2.0), 0.75));
        float w = 0.011;
        float rings = ring(p, 1.0, n1, w, 0.0, 0.0)
                    + ring(p, 1.0, n2, w, 5.0, spin * 0.2)
                    + ring(p, 1.0, n3, w * 0.8, 0.0, 0.0)
                    + ring(p, 1.0, n4, w * 0.8, 9.0, -spin * 0.3);
        glow += rings * (0.38 + 0.4 * energy);

        float3 o1 = rotateY(rotateX(float3(0.0, 1.0, 0.0), 1.28), spin * 0.25);
        float3 o2 = rotateY(rotateX(float3(0.0, 1.0, 0.0), -1.05), -spin * 0.18 + 0.7);
        float orbits = ring(p, 1.22, o1, 0.009, 3.0, spin * 0.1)
                     + ring(p, 1.4, o2, 0.007, 11.0, -spin * 0.15) * 0.7;
        glow += orbits * (0.3 + 0.3 * energy);
    }

    if (r < 1.0) {
        float h = sqrt(1.0 - r * r);
        float energyLines = 0.2 + 0.5 * energy;
        for (int side = 0; side < 2; side++) {
            float3 s = float3(p, side == 0 ? h : -h);
            float3 turned = rotateY(rotateX(s, 0.45), flow * 0.4);
            float weight = side == 0 ? 1.0 : 0.32;
            glow += filaments(turned, flow, energy) * energyLines * weight;
            glow += sparks(turned, time) * (0.5 + 0.7 * energy) * weight;
        }
        glow += pow(1.0 - h, 3.0) * (0.35 + 0.15 * energy);
    }

    float pulse = 1.0 + 0.35 * thinking * sin(time * 7.0);
    float core = exp(-r * r / (0.015 + 0.025 * energy)) * (0.65 + 0.75 * energy) * pulse;
    float lens = iris(p, 0.15 * (1.0 + 0.3 * level), 0.012, 6.0, spin * 0.5)
               + iris(p, 0.26 * (1.0 + 0.2 * level), 0.008, 14.0, -spin * 0.35) * 0.7;
    float halo = exp(-abs(r - 1.0) * 8.0) * (0.18 + 0.35 * energy)
               + exp(-max(r - 1.0, 0.0) * 2.2) * 0.08 * (0.5 + energy);
    glow += core + lens * (0.5 + 0.6 * energy) + halo;

    glow *= 0.62 + 0.38 * activity;

    float3 base = tint;
    float3 hot = mix(base, float3(1.0, 0.96, 0.88), 0.75);
    float3 light = base * glow + hot * glow * glow * 0.35;
    light = 1.0 - exp(-light * 1.25);

    if (clearBackground > 0.5) {
        // RuntimeShader expects premultiplied RGB; light already contains its intensity.
        float alpha = clamp(max(light.r, max(light.g, light.b)), 0.0, 1.0);
        return half4(half3(light), half(alpha));
    }
    float3 background = float3(0.006, 0.008, 0.014) + base * 0.035 * exp(-dot(raw, raw) * 1.4);
    return half4(half3(background + light), 1.0);
}
"""
}

// MARK: - Canvas fallback (Android 8 to 12)

private val ORB_BACKGROUND = Color(0xFF020204)
private val WARM_WHITE = Color(1f, 0.96f, 0.88f)

/** Fixed points on the unit sphere for sparks. */
private val SPARK_POINTS: List<FloatArray> = List(28) { index ->
    val golden = 2.399963f
    val y = 1f - (index + 0.5f) / 28f * 2f
    val radius = sqrt(max(0f, 1f - y * y))
    val theta = golden * index
    floatArrayOf(cos(theta) * radius, y, sin(theta) * radius, (index * 0.618f) % 1f)
}

private fun rotateX(p: FloatArray, angle: Float): FloatArray {
    val c = cos(angle)
    val s = sin(angle)
    return floatArrayOf(p[0], c * p[1] - s * p[2], s * p[1] + c * p[2])
}

private fun rotateY(p: FloatArray, angle: Float): FloatArray {
    val c = cos(angle)
    val s = sin(angle)
    return floatArrayOf(c * p[0] + s * p[2], p[1], -s * p[0] + c * p[2])
}

private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0]
)

private fun normalize(a: FloatArray): FloatArray {
    val length = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).takeIf { it > 1e-6f } ?: 1f
    return floatArrayOf(a[0] / length, a[1] / length, a[2] / length)
}

private fun DrawScope.drawFallbackOrb(frame: LiveOrbFrame, tint: Color, clearBackground: Boolean) {
    val scale = size.minDimension / 2f
    val energy = (0.22f * frame.active + 0.9f * frame.level + 0.3f * frame.speaking + 0.25f * frame.thinking).coerceIn(0f, 1.3f)
    val breathe = 1f + 0.015f * sin(frame.time * 1.7f) * (1f - frame.active * 0.5f)
    val radius = 0.56f * breathe * (1f + 0.07f * frame.level) * scale
    val dim = 0.62f + 0.38f * frame.active
    val hot = lerp(tint, WARM_WHITE, 0.75f)
    val blend = BlendMode.Plus

    if (!clearBackground) {
        drawRect(ORB_BACKGROUND)
        drawCircle(
            Brush.radialGradient(listOf(tint.copy(alpha = 0.10f), Color.Transparent), center, scale),
            radius = scale
        )
    }

    // Halo and the glass rim.
    val haloAlpha = ((0.18f + 0.35f * energy) * dim).coerceIn(0f, 1f)
    drawCircle(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.70f to tint.copy(alpha = haloAlpha * 0.25f),
            0.80f to tint.copy(alpha = haloAlpha),
            0.92f to tint.copy(alpha = haloAlpha * 0.2f),
            1f to Color.Transparent,
            center = center,
            radius = radius * 1.25f
        ),
        radius = radius * 1.25f,
        blendMode = blend
    )

    // Great circles and orbits, front halves bright and back halves faint, like the shader.
    val spin = frame.spin
    val ringAlpha = ((0.38f + 0.4f * energy) * dim).coerceIn(0f, 1f)
    val orbitAlpha = ((0.3f + 0.3f * energy) * dim).coerceIn(0f, 1f)
    val stroke = max(1f, scale * 0.012f)
    drawRing(rotateY(rotateX(floatArrayOf(0f, 1f, 0f), 1.15f), spin * 0.9f), radius, tint, ringAlpha, stroke, 0f, 0f)
    drawRing(rotateY(rotateX(floatArrayOf(0f, 0f, 1f), 0.85f + 0.25f * sin(spin * 0.6f)), -spin * 0.55f), radius, tint, ringAlpha, stroke, 5f, spin * 0.2f)
    drawRing(rotateY(rotateX(floatArrayOf(0f, 1f, 0f), 0.32f), -spin * 0.7f + 1f), radius, tint, ringAlpha, stroke * 0.8f, 0f, 0f)
    drawRing(
        normalize(floatArrayOf(cos(spin * 0.45f + 2f), 0.45f * sin(spin * 0.45f + 2f), 0.75f)),
        radius, tint, ringAlpha, stroke * 0.8f, 9f, -spin * 0.3f
    )
    drawRing(rotateY(rotateX(floatArrayOf(0f, 1f, 0f), 1.28f), spin * 0.25f), radius * 1.22f, tint, orbitAlpha, stroke * 0.8f, 3f, spin * 0.1f)
    drawRing(rotateY(rotateX(floatArrayOf(0f, 1f, 0f), -1.05f), -spin * 0.18f + 0.7f), radius * 1.4f, tint, orbitAlpha * 0.7f, stroke * 0.6f, 11f, -spin * 0.15f)

    // Sparks turning with the flow.
    val sparkAlpha = ((0.5f + 0.7f * energy) * dim).coerceIn(0f, 1f)
    for (point in SPARK_POINTS) {
        val turned = rotateY(rotateX(point, 0.45f), frame.flow * 0.4f)
        val front = turned[2] >= 0f
        val twinkle = 0.5f + 0.5f * sin(frame.time * (1.5f + point[3] * 5f) + point[3] * 40f)
        val alpha = sparkAlpha * twinkle * twinkle * if (front) 1f else 0.32f
        if (alpha < 0.02f) continue
        drawCircle(
            hot.copy(alpha = alpha.coerceIn(0f, 1f)),
            radius = max(1f, scale * 0.012f),
            center = Offset(center.x + turned[0] * radius, center.y + turned[1] * radius),
            blendMode = blend
        )
    }

    // The core and its lens rings.
    val pulse = 1f + 0.35f * frame.thinking * sin(frame.time * 7f)
    val coreAlpha = ((0.65f + 0.75f * energy) * pulse * dim).coerceIn(0f, 1f)
    val coreRadius = radius * sqrt(0.015f + 0.025f * energy) * 2.6f
    drawCircle(
        Brush.radialGradient(
            0f to WARM_WHITE.copy(alpha = coreAlpha),
            0.35f to hot.copy(alpha = coreAlpha * 0.8f),
            0.7f to tint.copy(alpha = coreAlpha * 0.35f),
            1f to Color.Transparent,
            center = center,
            radius = coreRadius
        ),
        radius = coreRadius,
        blendMode = blend
    )
    val lensAlpha = ((0.5f + 0.6f * energy) * dim).coerceIn(0f, 1f)
    drawIris(radius * 0.15f * (1f + 0.3f * frame.level), tint, lensAlpha, stroke, 6, frame.spin * 0.5f)
    drawIris(radius * 0.26f * (1f + 0.2f * frame.level), tint, lensAlpha * 0.7f, stroke * 0.7f, 14, -frame.spin * 0.35f)
}

private fun DrawScope.drawRing(
    normal: FloatArray,
    radius: Float,
    color: Color,
    alpha: Float,
    width: Float,
    dashes: Float,
    dashShift: Float
) {
    val n = normalize(normal)
    val u = normalize(if (abs(n[1]) < 0.95f) cross(n, floatArrayOf(0f, 1f, 0f)) else cross(n, floatArrayOf(1f, 0f, 0f)))
    val v = cross(n, u)
    val steps = 72
    var previous: FloatArray? = null
    for (step in 0..steps) {
        val t = step / steps.toFloat() * (2f * Math.PI.toFloat())
        val point = floatArrayOf(
            cos(t) * u[0] + sin(t) * v[0],
            cos(t) * u[1] + sin(t) * v[1],
            cos(t) * u[2] + sin(t) * v[2]
        )
        val last = previous
        previous = point
        if (last == null) continue
        if (dashes > 0f) {
            val dash = ((step / steps.toFloat()) * dashes + dashShift).let { it - kotlin.math.floor(it) }
            if (dash > 0.72f) continue
        }
        val front = (last[2] + point[2]) >= 0f
        drawLine(
            color.copy(alpha = (alpha * if (front) 1f else 0.3f).coerceIn(0f, 1f)),
            Offset(center.x + last[0] * radius, center.y + last[1] * radius),
            Offset(center.x + point[0] * radius, center.y + point[1] * radius),
            strokeWidth = width,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus
        )
    }
}

private fun DrawScope.drawIris(radius: Float, color: Color, alpha: Float, width: Float, dashes: Int, phase: Float) {
    val circumference = (2f * Math.PI.toFloat() * radius).takeIf { it > 1f } ?: return
    val segment = circumference / dashes
    drawCircle(
        color.copy(alpha = alpha.coerceIn(0f, 1f)),
        radius = radius,
        style = Stroke(
            width = width,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(segment * 0.62f, segment * 0.38f), phase * segment * dashes)
        ),
        blendMode = BlendMode.Plus
    )
}
