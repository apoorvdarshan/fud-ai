package com.apoorvdarshan.calorietracker.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider as GlanceColorProvider

/** Brand palette exposed to Glance. Keep in sync with ui/theme/Color.kt. */
object WidgetTheme {
    val calorieProvider = ColorProvider(day = Color(0xFFFF375F), night = Color(0xFFFF375F))
    val backgroundProvider = ColorProvider(day = Color(0xFFFFF8F2), night = Color(0xFF0C0C0C))
    val primaryTextProvider = ColorProvider(day = Color(0xFF1C1C1E), night = Color(0xFFF2F2F7))
    val secondaryTextProvider = ColorProvider(day = Color(0xFF8E8E93), night = Color(0xFF8E8E93))

    /** Raw RGB hex from the snapshot, Fud Pink when the field is absent. */
    fun themeStart(hex: Int?): Int = hex ?: DEFAULT_THEME_START
    fun themeEnd(hex: Int?): Int = hex ?: DEFAULT_THEME_END

    /** Text color provider for the user's theme color (same in light/dark). */
    fun themeTextProvider(hex: Int?): GlanceColorProvider {
        val color = Color(0xFF000000L or themeStart(hex).toLong())
        return ColorProvider(day = color, night = color)
    }
}
