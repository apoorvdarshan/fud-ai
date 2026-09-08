package com.apoorvdarshan.calorietracker.models

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class MealIngredient(
    val name: String,
    val grams: Double,
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val imageFilename: String? = null,
    val additionalImageFilenames: List<String> = emptyList(),
    val emoji: String? = null
) {
    val allImageFilenames: List<String>
        get() = (listOfNotNull(imageFilename) + additionalImageFilenames).distinct()

    fun scaled(factor: Double): MealIngredient = copy(
        grams = grams * factor,
        calories = (calories * factor).roundToInt(),
        protein = protein * factor,
        carbs = carbs * factor,
        fat = fat * factor
    )
}

data class MealIngredientTotals(
    val grams: Double,
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double
)

fun List<MealIngredient>.totals(): MealIngredientTotals = MealIngredientTotals(
    grams = sumOf { it.grams },
    calories = sumOf { it.calories },
    protein = sumOf { it.protein },
    carbs = sumOf { it.carbs },
    fat = sumOf { it.fat }
)
