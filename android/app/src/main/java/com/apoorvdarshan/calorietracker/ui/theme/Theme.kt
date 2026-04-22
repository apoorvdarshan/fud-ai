package com.apoorvdarshan.calorietracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = AppColors.Calorie,
    onPrimary = AppColors.OnDark,
    secondary = AppColors.Calorie,
    onSecondary = AppColors.OnDark,
    tertiary = AppColors.Calorie,
    onTertiary = AppColors.OnDark,
    background = AppColors.AppBackgroundLight,
    onBackground = AppColors.OnLight,
    surface = AppColors.AppCardLight,
    onSurface = AppColors.OnLight,
    surfaceVariant = AppColors.AppCardLight,
    onSurfaceVariant = AppColors.MutedLight,
    outline = AppColors.DividerLight
)

private val DarkColors = darkColorScheme(
    primary = AppColors.Calorie,
    onPrimary = AppColors.OnDark,
    secondary = AppColors.Calorie,
    onSecondary = AppColors.OnDark,
    tertiary = AppColors.Calorie,
    onTertiary = AppColors.OnDark,
    background = AppColors.AppBackgroundDark,
    onBackground = AppColors.OnDark,
    surface = AppColors.AppCardDark,
    onSurface = AppColors.OnDark,
    surfaceVariant = AppColors.AppCardDark,
    onSurfaceVariant = AppColors.MutedDark,
    outline = AppColors.DividerDark
)

@Composable
fun FudAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
