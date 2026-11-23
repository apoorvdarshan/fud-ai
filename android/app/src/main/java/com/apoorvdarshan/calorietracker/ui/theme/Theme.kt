package com.apoorvdarshan.calorietracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private fun lightColors(themeColor: AppThemeColor) = lightColorScheme(
    primary = themeColor.start,
    onPrimary = AppColors.OnDark,
    secondary = themeColor.start,
    onSecondary = AppColors.OnDark,
    tertiary = themeColor.start,
    onTertiary = AppColors.OnDark,
    background = if (themeColor == AppThemeColor.FUD_PINK) PinkNeoPaperLight else AppColors.AppBackgroundLight,
    onBackground = AppColors.OnLight,
    surface = if (themeColor == AppThemeColor.FUD_PINK) PinkNeoPanelLight else AppColors.AppCardLight,
    onSurface = AppColors.OnLight,
    surfaceVariant = if (themeColor == AppThemeColor.FUD_PINK) PinkNeoPanelLight else AppColors.AppCardLight,
    onSurfaceVariant = AppColors.MutedLight,
    outline = AppColors.DividerLight
)

private fun darkColors(themeColor: AppThemeColor, gray: Boolean) = darkColorScheme(
    primary = themeColor.start,
    onPrimary = AppColors.OnDark,
    secondary = themeColor.start,
    onSecondary = AppColors.OnDark,
    tertiary = themeColor.start,
    onTertiary = AppColors.OnDark,
    background = when {
        gray -> Color(0xFF202124)
        themeColor == AppThemeColor.FUD_PINK -> PinkNeoPaperDark
        else -> AppColors.AppBackgroundDark
    },
    onBackground = AppColors.OnDark,
    surface = when {
        gray -> Color(0xFF303136)
        themeColor == AppThemeColor.FUD_PINK -> PinkNeoPanelDark
        else -> AppColors.AppCardDark
    },
    onSurface = AppColors.OnDark,
    surfaceVariant = when {
        gray -> Color(0xFF303136)
        themeColor == AppThemeColor.FUD_PINK -> PinkNeoPanelDark
        else -> AppColors.AppCardDark
    },
    onSurfaceVariant = AppColors.MutedDark,
    outline = if (gray) Color(0xFF777980) else AppColors.DividerDark
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
