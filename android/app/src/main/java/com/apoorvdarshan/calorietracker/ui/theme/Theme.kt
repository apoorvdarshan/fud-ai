package com.apoorvdarshan.calorietracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private fun lightColors(themeColor: AppThemeColor) = lightColorScheme(
    primary = themeColor.start,
    onPrimary = contrastingActionInk(themeColor.start),
    secondary = themeColor.start,
    onSecondary = contrastingActionInk(themeColor.start),
    tertiary = themeColor.start,
    onTertiary = contrastingActionInk(themeColor.start),
    background = AppColors.AppBackgroundLight,
    onBackground = FlashInkLight,
    surface = AppColors.AppCardLight,
    onSurface = FlashInkLight,
    surfaceVariant = AppColors.AppCardLight,
    onSurfaceVariant = Color(0xFF534C54),
    outline = AppColors.DividerLight
)

private fun darkColors(themeColor: AppThemeColor, gray: Boolean) = darkColorScheme(
    primary = themeColor.start,
    onPrimary = contrastingActionInk(themeColor.start),
    secondary = themeColor.start,
    onSecondary = contrastingActionInk(themeColor.start),
    tertiary = themeColor.start,
    onTertiary = contrastingActionInk(themeColor.start),
    background = when {
        gray -> Color(0xFF202124)
        else -> AppColors.AppBackgroundDark
    },
    onBackground = FlashInkDark,
    surface = when {
        gray -> Color(0xFF303136)
        else -> AppColors.AppCardDark
    },
    onSurface = FlashInkDark,
    surfaceVariant = when {
        gray -> Color(0xFF303136)
        else -> AppColors.AppCardDark
    },
    onSurfaceVariant = Color(0xFFC5BDC6),
    outline = if (gray) Color(0xFFAAAAB1) else FlashInkDark
)

@Composable
fun FudAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColor: AppThemeColor = AppThemeColor.FUD_PINK,
    grayDarkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    AppColors.setThemeColor(themeColor)
    val colorScheme = if (darkTheme) darkColors(themeColor, grayDarkTheme) else lightColors(themeColor)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
