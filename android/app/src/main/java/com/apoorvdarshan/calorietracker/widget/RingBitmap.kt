package com.apoorvdarshan.calorietracker.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Glance has no Canvas / arc primitives, so the Home-style dashed speedometer
 * gauge and the vertical nutrient bars are rasterized into Bitmaps in the
 * widget update path and drawn via Image(provider = ImageProvider(bitmap)).
 * Colors come from the snapshot's synced theme (Fud Pink fallback), mirroring
 * the iOS widget's SpeedometerGauge / VerticalNutrientBar pair.
 */

const val DEFAULT_THEME_START = 0xFF375F
const val DEFAULT_THEME_END = 0xFF6B8A

private fun opaque(rgb: Int): Int =
    AndroidColor.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

/** 15%-alpha version of the theme color, used for gauge/bar tracks. */
private fun track(rgb: Int): Int =
    AndroidColor.argb(38, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

/**
 * Dashed top-semicircle speedometer. The returned bitmap is diameter wide and
 * 0.58 * diameter tall — the dome plus a little room for the center readout,
 * matching the iOS gauge's cropped frame.
 */
fun speedometerBitmap(
    diameterPx: Int,
    progress: Float,
    strokeWidthPx: Float,
    startRgb: Int,
    endRgb: Int
): Bitmap {
    val heightPx = (diameterPx * 0.58f).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(diameterPx.coerceAtLeast(1), heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val pad = strokeWidthPx / 2f
    // Full circle bounds; the canvas clips everything below the dome.
    val rect = RectF(pad, pad, diameterPx - pad, diameterPx - pad)
    val dash = DashPathEffect(floatArrayOf(strokeWidthPx * 0.28f, strokeWidthPx * 0.42f), 0f)

    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        pathEffect = dash
        color = track(startRgb)
    }
    canvas.drawArc(rect, 180f, 180f, false, trackPaint)

    val sweep = progress.coerceIn(0f, 1f) * 180f
    if (sweep > 0f) {
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            pathEffect = dash
            shader = LinearGradient(
                0f, 0f, diameterPx.toFloat(), 0f,
                opaque(startRgb), opaque(endRgb),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawArc(rect, 180f, sweep, false, fg)
    }
    return bitmap
}

/** Vertical fill tube — dim theme track with a bottom-up gradient fill. */
fun verticalBarBitmap(
    widthPx: Int,
    heightPx: Int,
    progress: Float,
    startRgb: Int,
    endRgb: Int
): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = widthPx / 2f

    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = track(startRgb)
    }
    canvas.drawRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), radius, radius, trackPaint)

    val clamped = progress.coerceIn(0f, 1f)
    if (clamped > 0f) {
        val fillH = (heightPx * clamped).coerceAtLeast(widthPx.toFloat())
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, heightPx.toFloat(), 0f, heightPx - fillH,
                opaque(startRgb), opaque(endRgb),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(RectF(0f, heightPx - fillH, widthPx.toFloat(), heightPx.toFloat()), radius, radius, fg)
    }
    return bitmap
}
