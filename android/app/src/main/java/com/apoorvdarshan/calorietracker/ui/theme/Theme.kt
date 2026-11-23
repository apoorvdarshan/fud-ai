package com.apoorvdarshan.calorietracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val square = RoundedCornerShape(0.dp)
private val flashShapes = Shapes(square, square, square, square, square)

private fun lightColors(themeColor: AppThemeColor) = lightColorScheme(
    primary = themeColor.start,
    onPrimary = contrastingActionInk(themeColor.start),
    secondary = themeColor.start,
    onSecondary = contrastingActionInk(themeColor.start),
    tertiary = themeColor.start,
    onTertiary = contrastingActionInk(themeColor.start),
    background = if (themeColor == AppThemeColor.FUD_PINK) PinkNeoPaperLight else AppColors.AppBackgroundLight,
    onBackground = FlashInkLight,
    surface = if (themeColor == AppThemeColor.FUD_PINK) PinkNeoPanelLight else AppColors.AppCardLight,
    onSurface = FlashInkLight,
    surfaceVariant = if (themeColor == AppThemeColor.FUD_PINK) PinkNeoPanelLight else AppColors.AppCardLight,
    onSurfaceVariant = Color(0xFF534C54),
    outline = FlashInkLight
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
        themeColor == AppThemeColor.FUD_PINK -> PinkNeoPaperDark
        else -> AppColors.AppBackgroundDark
    },
    onBackground = FlashInkDark,
    surface = when {
        gray -> Color(0xFF303136)
        themeColor == AppThemeColor.FUD_PINK -> PinkNeoPanelDark
        else -> AppColors.AppCardDark
    },
    onSurface = FlashInkDark,
    surfaceVariant = when {
        gray -> Color(0xFF303136)
        themeColor == AppThemeColor.FUD_PINK -> PinkNeoPanelDark
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
        shapes = flashShapes,
        content = content
    )
}
