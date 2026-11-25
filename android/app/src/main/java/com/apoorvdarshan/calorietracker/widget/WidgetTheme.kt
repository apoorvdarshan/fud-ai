package com.apoorvdarshan.calorietracker.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.ContentScale
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.theme.FlashInkDark
import com.apoorvdarshan.calorietracker.ui.theme.FlashInkLight
import com.apoorvdarshan.calorietracker.ui.theme.FlashPink

/** Flash palette exposed to Glance without depending on an in-app composition. */
object WidgetTheme {
    val primaryTextProvider = ColorProvider(day = FlashInkLight, night = FlashInkDark)
    val secondaryTextProvider = ColorProvider(day = Color(0xFF655D65), night = Color(0xFFBDB4BD))

    /** Keep user accents and upgrade the old default cached by earlier builds. */
    fun accentRgb(hex: Int?): Int =
        flashWidgetAccentRgb(hex, FlashPink.toArgb() and 0xFFFFFF)

    fun accentProvider(hex: Int?): GlanceColorProvider {
        val color = Color(0xFF000000.toInt() or accentRgb(hex))
        return ColorProvider(day = color, night = color)
    }
}

internal fun flashWidgetAccentRgb(saved: Int?, flashPink: Int): Int =
    if (saved == null || saved == 0xFF375F) flashPink else saved

internal fun GlanceModifier.flashWidgetPanel(): GlanceModifier =
    background(ImageProvider(R.drawable.widget_fallback_background), contentScale = ContentScale.FillBounds)
        .cornerRadius(22.dp)
