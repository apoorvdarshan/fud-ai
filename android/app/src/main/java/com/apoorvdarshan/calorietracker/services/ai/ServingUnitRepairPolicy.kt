package com.apoorvdarshan.calorietracker.services.ai

/**
 * Decides whether a food-analysis result that omitted or mangled `unit_options` is worth a second
 * "repair" round-trip to the model.
 *
 * The repair call runs while the user is still staring at the analyzing overlay and roughly doubles
 * the wait (Flash-Lite frequently drops `unit_options`). It can only ever add non-gram serving
 * units — it never fixes macros — so when the primary response already has usable nutrition the
 * review sheet is shown immediately with gram-based portions and the repair is skipped. Only a
 * result with no macros at all (water, black coffee, zero-calorie drinks) gets the repair, because
 * there a volume unit like ml/cup is the whole point of the entry.
 */
internal object ServingUnitRepairPolicy {
    fun shouldRepair(
        shouldRequestFallback: Boolean,
        servingSizeIsKnown: Boolean,
        calories: Number,
        protein: Double,
        carbs: Double,
        fat: Double
    ): Boolean {
        if (!shouldRequestFallback || !servingSizeIsKnown) return false
        val hasUsableMacros = calories.toDouble() > 0 || protein > 0 || carbs > 0 || fat > 0
        return !hasUsableMacros
    }

    fun shouldRepair(analysis: FoodAnalysis, shouldRequestFallback: Boolean): Boolean =
        shouldRepair(
            shouldRequestFallback = shouldRequestFallback,
            servingSizeIsKnown = analysis.servingSizeIsKnown,
            calories = analysis.calories,
            protein = analysis.protein,
            carbs = analysis.carbs,
            fat = analysis.fat
        )

    fun shouldRepair(analysis: NutritionLabelAnalysis, shouldRequestFallback: Boolean): Boolean =
        shouldRepair(
            shouldRequestFallback = shouldRequestFallback,
            servingSizeIsKnown = analysis.servingSizeGrams != null,
            calories = analysis.caloriesPer100g,
            protein = analysis.proteinPer100g,
            carbs = analysis.carbsPer100g,
            fat = analysis.fatPer100g
        )
}
