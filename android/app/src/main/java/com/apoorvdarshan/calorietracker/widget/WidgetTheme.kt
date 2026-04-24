package com.apoorvdarshan.calorietracker.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider

/** Brand palette exposed to Glance. Keep in sync with ui/theme/Color.kt. */
object WidgetTheme {
    val calorieProvider = ColorProvider(day = Color(0xFFFF375F), night = Color(0xFFFF375F))
    val backgroundProvider = ColorProvider(day = Color(0xFFFFF8F2), night = Color(0xFF0C0C0C))
    val primaryTextProvider = ColorProvider(day = Color(0xFF1C1C1E), night = Color(0xFFF2F2F7))
    val secondaryTextProvider = ColorProvider(day = Color(0xFF8E8E93), night = Color(0xFF8E8E93))
}
