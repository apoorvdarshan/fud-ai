package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

/**
 * Workouts theme bridge — the exercise library is ported from Delts
 * (github.com/apoorvdarshan/delts), whose screens read a small resolved palette
 * (`LocalDeltsColors.current`). This file re-implements that exact field surface
 * on top of Fud AI's theme (AppColors + the user-selectable accent), so the ported
 * screens render with Fud AI's default look while keeping their code unchanged.
 */
data class WorkoutsColors(
    val background: Color,
    val charcoal: Color,
    val card: Color,
    val panel: Color,
    val hairline: Color,
    val accent: Color,
    val secondaryAccent: Color,
    val onAccent: Color,
    val mutedText: Color,
    val isDark: Boolean
)

@Composable
fun workoutsColors(): WorkoutsColors {
    // Same dark-detection trick as FudAIBottomNavBar: the resolved background
    // luminance tracks the user's appearance override, not just the system.
    // Read the selected scheme so Dark gray is not painted back over with the
    // fixed near-black palette. Non-gray dark still resolves to those colors.
    val scheme = MaterialTheme.colorScheme
    val bg = scheme.background
    val isDark = (bg.red + bg.green + bg.blue) / 3f < 0.5f

    return WorkoutsColors(
        background = bg,
        charcoal = if (isDark) AppColors.OnDark else AppColors.OnLight,
        card = scheme.surface,
        panel = if (isDark) Color(0xFF2A2A2E) else Color(0xFFEFE7DF),
        hairline = scheme.outline,
        accent = AppColors.Calorie,
        secondaryAccent = AppColors.CalorieEnd,
        onAccent = Color.White,
        mutedText = if (isDark) AppColors.MutedDark else AppColors.MutedLight,
        isDark = isDark
    )
}
