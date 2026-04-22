package com.apoorvdarshan.calorietracker.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object AppColors {
    val CalorieStart = Color(0xFFFF375F)
    val CalorieEnd = Color(0xFFFF6B8A)

    val Calorie = CalorieStart
    val Protein = CalorieStart
    val Carbs = CalorieStart
    val Fat = CalorieStart

    val CalorieGradient = Brush.linearGradient(listOf(CalorieStart, CalorieEnd))

    val AppBackgroundLight = Color(0xFFFFF8F2)
    val AppBackgroundDark = Color(0xFF0C0C0C)

    val AppCardLight = Color(0xFFFFFFFF)
    val AppCardDark = Color(0xFF1C1C1E)

    val OnLight = Color(0xFF1C1C1E)
    val OnDark = Color(0xFFF2F2F7)

    val MutedLight = Color(0xFF8E8E93)
    val MutedDark = Color(0xFF8E8E93)

    val DividerLight = Color(0xFFE5E5EA)
    val DividerDark = Color(0xFF2C2C2E)
}
