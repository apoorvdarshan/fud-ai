package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette

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
    // Resolve the current appearance, including the Dark gray override.
    val scheme = MaterialTheme.colorScheme
    val neo = pinkNeoPalette()
    val bg = scheme.background
    val isDark = (bg.red + bg.green + bg.blue) / 3f < 0.5f

    return WorkoutsColors(
        background = bg,
        charcoal = neo.ink,
        card = scheme.surface,
        panel = neo.wash,
        hairline = neo.rule,
        accent = neo.action,
        secondaryAccent = neo.action,
        onAccent = neo.onAction,
        mutedText = neo.ink.copy(alpha = 0.66f),
        isDark = isDark
    )
}
