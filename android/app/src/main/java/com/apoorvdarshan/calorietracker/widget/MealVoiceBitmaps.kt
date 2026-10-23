package com.apoorvdarshan.calorietracker.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.BlurMaskFilter
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient

/** Phase colors for the voice-meal widget; the red edge glow is what says "recording". */
internal const val MEAL_VOICE_RED = 0xFF3B30
private const val MEAL_VOICE_RED_END = 0xFF2D55
private const val MEAL_VOICE_GREEN = 0x34C759
private const val MEAL_VOICE_GREEN_END = 0x248A3D

private fun opaque(rgb: Int): Int =
    AndroidColor.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

private fun withAlpha(rgb: Int, alpha: Int): Int =
    AndroidColor.argb(alpha, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

/**
 * The widget's round mic button, rasterized (Glance has no Canvas). With [progress] it is wrapped
 * in a calorie ring; with null it is just the button. While [recording] the button turns red;
 * while [processing] the mic becomes three dots.
 */
fun mealVoiceBadgeBitmap(
    sizePx: Int,
    progress: Float?,
    recording: Boolean,
    processing: Boolean,
    startRgb: Int,
    endRgb: Int,
    addedText: String? = null
): Bitmap {
    val s = sizePx.coerceAtLeast(2)
    val sf = s.toFloat()
    val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = sf / 2f

    val ringMode = progress != null
    // Larger disc while it shows "+N" so even 4-digit values ("+1287") stay readable.
    val discR = if (ringMode) (if (addedText != null) sf * 0.335f else sf * 0.28f) else sf * 0.38f
    val ringR = sf * 0.385f
    val ringW = sf * 0.07f

    if (progress != null) {
        val rect = RectF(c - ringR, c - ringR, c + ringR, c + ringR)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringW
            color = withAlpha(startRgb, 46)
        }
        canvas.drawArc(rect, 0f, 360f, false, track)
        val sweep = progress.coerceIn(0f, 1f) * 360f
        if (sweep > 0f) {
            val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = ringW
                strokeCap = Paint.Cap.ROUND
                shader = SweepGradient(c, c, opaque(startRgb), opaque(endRgb)).apply {
                    setLocalMatrix(Matrix().apply { postRotate(-90f, c, c) })
                }
            }
            canvas.drawArc(rect, -90f, sweep, false, fg)
        }
    }

    val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val (a, b) = when {
            addedText != null -> opaque(MEAL_VOICE_GREEN) to opaque(MEAL_VOICE_GREEN_END)
            recording -> opaque(MEAL_VOICE_RED) to opaque(MEAL_VOICE_RED_END)
            else -> opaque(startRgb) to opaque(endRgb)
        }
        shader = LinearGradient(c - discR, c - discR, c + discR, c + discR, a, b, Shader.TileMode.CLAMP)
        if (processing) alpha = 150
    }
    canvas.drawCircle(c, c, discR, disc)

    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }
    if (addedText != null) {
        // Calories just logged, e.g. "+451", fitted inside the disc.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = discR * 0.9f
            val maxWidth = discR * 1.7f
            if (measureText(addedText) > maxWidth) textSize *= maxWidth / measureText(addedText)
        }
        canvas.drawText(addedText, c, c - (text.ascent() + text.descent()) / 2f, text)
        return bitmap
    }
    if (processing) {
        val dot = discR * 0.13f
        val gap = discR * 0.42f
        for (i in -1..1) canvas.drawCircle(c + i * gap, c, dot, white)
        return bitmap
    }

    // Microphone glyph, sized from the disc so it scales with the widget.
    val m = discR
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = m * 0.09f
        strokeCap = Paint.Cap.ROUND
    }
    val dy = -m * 0.04f
    canvas.drawRoundRect(
        RectF(c - 0.20f * m, c - 0.55f * m + dy, c + 0.20f * m, c + 0.12f * m + dy),
        0.20f * m, 0.20f * m, white
    )
    canvas.drawArc(
        RectF(c - 0.40f * m, c - 0.38f * m + dy, c + 0.40f * m, c + 0.42f * m + dy),
        0f, 180f, false, stroke
    )
    canvas.drawLine(c, c + 0.42f * m + dy, c, c + 0.62f * m + dy, stroke)
    canvas.drawLine(c - 0.20f * m, c + 0.62f * m + dy, c + 0.20f * m, c + 0.62f * m + dy, stroke)
    return bitmap
}

/**
 * Soft red glow hugging the inside of the widget's rounded outline, drawn as a transparent overlay
 * the size of the whole widget.
 */
fun edgeGlowBitmap(widthPx: Int, heightPx: Int, cornerPx: Float, glowPx: Float): Bitmap {
    val w = widthPx.coerceAtLeast(2)
    val h = heightPx.coerceAtLeast(2)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val shape = RectF(0f, 0f, w.toFloat(), h.toFloat())
    canvas.clipPath(Path().apply { addRoundRect(shape, cornerPx, cornerPx, Path.Direction.CW) })
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = glowPx
        color = withAlpha(MEAL_VOICE_RED, 235)
        maskFilter = BlurMaskFilter(glowPx, BlurMaskFilter.Blur.NORMAL)
    }
    // Two passes: a wide soft halo plus a tighter, brighter rim.
    canvas.drawRoundRect(shape, cornerPx, cornerPx, paint)
    paint.strokeWidth = glowPx * 0.5f
    paint.maskFilter = BlurMaskFilter(glowPx * 0.5f, BlurMaskFilter.Blur.NORMAL)
    canvas.drawRoundRect(shape, cornerPx, cornerPx, paint)
    return bitmap
}
