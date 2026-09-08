package com.apoorvdarshan.calorietracker.models

/** Unrounded nutrition baseline used while typing a new ingredient weight. */
data class IngredientPortion(
    val grams: Double,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double
) {
    fun resized(newGrams: Double): IngredientPortion? {
        if (!grams.isFinite() || grams <= 0 || !newGrams.isFinite() || newGrams <= 0) return null
        val factor = newGrams / grams
        val result = IngredientPortion(newGrams, calories * factor, protein * factor, carbs * factor, fat * factor)
        if (listOf(result.calories, result.protein, result.carbs, result.fat).any { !it.isFinite() || it < 0 || it >= Int.MAX_VALUE.toDouble() }) return null
        return result
    }
}
