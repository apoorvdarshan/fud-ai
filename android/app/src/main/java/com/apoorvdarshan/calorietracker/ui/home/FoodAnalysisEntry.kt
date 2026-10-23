package com.apoorvdarshan.calorietracker.ui.home

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Turns an AI [FoodAnalysis] into the [FoodEntry] that gets logged. Shared by the in-app save
 * ([HomeViewModel.saveAnalysis]) and the one-tap voice widget so both log identical entries.
 */
internal fun FoodAnalysis.toFoodEntry(
    id: UUID = UUID.randomUUID(),
    name: String? = null,
    scale: Double = 1.0,
    timestamp: Instant = Instant.now(),
    source: FoodSource,
    mealType: MealType,
    imageFilenames: List<String> = emptyList(),
    servingGrams: Double? = null,
    servingSizeIsKnown: Boolean = true,
    selectedServingUnit: String? = null,
    selectedServingQuantity: Double? = null
): FoodEntry {
    val analysis = this
    fun s(v: Int) = (v * scale).roundToInt()
    fun macro(v: Double) = v * scale
    fun s(v: Double?) = v?.let { it * scale }
    return FoodEntry(
        id = id,
        name = name?.takeIf { it.isNotBlank() } ?: analysis.name,
        calories = s(analysis.calories),
        protein = macro(analysis.protein),
        carbs = macro(analysis.carbs),
        fat = macro(analysis.fat),
        timestamp = timestamp,
        imageFilename = imageFilenames.firstOrNull(),
        additionalImageFilenames = imageFilenames.drop(1),
        emoji = analysis.emoji,
        source = source,
        mealType = mealType,
        sugar = s(analysis.sugar),
        addedSugar = s(analysis.addedSugar),
        fiber = s(analysis.fiber),
        saturatedFat = s(analysis.saturatedFat),
        monounsaturatedFat = s(analysis.monounsaturatedFat),
        polyunsaturatedFat = s(analysis.polyunsaturatedFat),
        cholesterol = s(analysis.cholesterol),
        caffeine = s(analysis.caffeine),
        supplementalNutrients = analysis.supplementalNutrients.mapValues { (_, value) -> s(value) ?: 0.0 },
        sodium = s(analysis.sodium),
        potassium = s(analysis.potassium),
        transFat = s(analysis.transFat),
        calcium = s(analysis.calcium),
        iron = s(analysis.iron),
        magnesium = s(analysis.magnesium),
        zinc = s(analysis.zinc),
        vitaminA = s(analysis.vitaminA),
        vitaminC = s(analysis.vitaminC),
        vitaminD = s(analysis.vitaminD),
        vitaminB12 = s(analysis.vitaminB12),
        vitaminE = s(analysis.vitaminE),
        vitaminK = s(analysis.vitaminK),
        folate = s(analysis.folate),
        omega3 = s(analysis.omega3),
        servingSizeGrams = if (servingSizeIsKnown) {
            servingGrams ?: analysis.servingSizeGrams
        } else {
            null
        },
        servingUnitOptions = if (servingSizeIsKnown) analysis.servingUnitOptions else emptyList(),
        selectedServingUnit = if (servingSizeIsKnown) {
            if (analysis.servingUnitOptions.isEmpty()) null else selectedServingUnit
        } else {
            "serving"
        },
        selectedServingQuantity = if (servingSizeIsKnown) {
            if (analysis.servingUnitOptions.isEmpty()) null else selectedServingQuantity
        } else {
            selectedServingQuantity
        },
        customNote = analysis.customNote,
        progressiveMeal = analysis.progressiveMeal,
        ingredients = analysis.ingredients.map { it.scaled(scale) },
        productMetadata = analysis.productMetadata
    )
}
