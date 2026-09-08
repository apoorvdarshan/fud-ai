package com.apoorvdarshan.calorietracker.models

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class FoodEntry(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant = Instant.now(),
    /** Filename (not path) under filesDir/fudai-food-images/ where the JPEG lives. */
    val imageFilename: String? = null,
    /** Ordered photos after the primary image. Kept separate so galleries and AI
     * reprocessing can use the original files instead of a stitched composite. */
    val additionalImageFilenames: List<String> = emptyList(),
    val emoji: String? = null,
    val source: FoodSource,
    val mealType: MealType = MealType.OTHER,
    val sugar: Double? = null,
    val addedSugar: Double? = null,
    val fiber: Double? = null,
    val saturatedFat: Double? = null,
    val monounsaturatedFat: Double? = null,
    val polyunsaturatedFat: Double? = null,
    val cholesterol: Double? = null,
    val caffeine: Double? = null,
    /** Optional sports-nutrition compounds, stored in grams by SupplementalNutrient.storageKey. */
    val supplementalNutrients: Map<String, Double> = emptyMap(),
    val sodium: Double? = null,
    val potassium: Double? = null,
    val transFat: Double? = null,
    val calcium: Double? = null,
    val iron: Double? = null,
    val magnesium: Double? = null,
    val zinc: Double? = null,
    val vitaminA: Double? = null,
    val vitaminC: Double? = null,
    val vitaminD: Double? = null,
    val vitaminB12: Double? = null,
    val vitaminE: Double? = null,
    val vitaminK: Double? = null,
    val folate: Double? = null,
    val omega3: Double? = null,
    val servingSizeGrams: Double? = null,
    val servingUnitOptions: List<ServingUnitOption> = emptyList(),
    val selectedServingUnit: String? = null,
    val selectedServingQuantity: Double? = null,
    val customNote: String? = null,
    val progressiveMeal: Boolean = false,
    val ingredients: List<MealIngredient> = emptyList(),
    /** Origin of a Health Connect import; never export these entries back to Health Connect. */
    val healthConnectOrigin: String? = null,
    val healthConnectRecordId: String? = null,
    val productMetadata: FoodProductMetadata? = null
) {
    /** Unique key for favorite deduplication (name + calorie combo). */
    val favoriteKey: String get() = "${name.lowercase()}|$calories"

    /** True only when the original food mass is actually known. */
    val hasKnownServingSize: Boolean
        get() = servingSizeGrams?.let { it.isFinite() && it > 0.0 } == true

    /**
     * Working scale for edit/re-log UI. When mass is unknown this is a serving
     * count, allowing 1 serving to remain 1x without fabricating 100 grams.
     */
    val reviewServingReference: Double
        get() = servingSizeGrams?.takeIf { it.isFinite() && it > 0.0 }
            ?: selectedServingQuantity?.takeIf { it.isFinite() && it > 0.0 }
            ?: 1.0

    val reviewServingUnitOptions: List<ServingUnitOption>
        get() = if (hasKnownServingSize) servingUnitOptions
        else listOf(ServingUnitOption.loggedServing(reviewServingReference))

    val reviewSelectedServingUnit: String?
        get() = if (hasKnownServingSize) selectedServingUnit else "serving"

    val reviewSelectedServingQuantity: Double?
        get() = if (hasKnownServingSize) selectedServingQuantity else reviewServingReference

    /** New entry for the given log date (new id), copying nutrition and media from this entry. */
    fun duplicatedForLogging(
        logDate: Instant,
        mealType: MealType = MealType.currentMeal
    ): FoodEntry = FoodEntry(
        id = UUID.randomUUID(),
        name = name,
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        timestamp = logDate,
        imageFilename = imageFilename,
        additionalImageFilenames = additionalImageFilenames,
        emoji = emoji,
        source = source,
        mealType = mealType,
        sugar = sugar,
        addedSugar = addedSugar,
        fiber = fiber,
        saturatedFat = saturatedFat,
        monounsaturatedFat = monounsaturatedFat,
        polyunsaturatedFat = polyunsaturatedFat,
        cholesterol = cholesterol,
        caffeine = caffeine,
        supplementalNutrients = supplementalNutrients,
        sodium = sodium,
        potassium = potassium,
        transFat = transFat,
        calcium = calcium,
        iron = iron,
        magnesium = magnesium,
        zinc = zinc,
        vitaminA = vitaminA,
        vitaminC = vitaminC,
        vitaminD = vitaminD,
        vitaminB12 = vitaminB12,
        vitaminE = vitaminE,
        vitaminK = vitaminK,
        folate = folate,
        omega3 = omega3,
        servingSizeGrams = servingSizeGrams,
        servingUnitOptions = servingUnitOptions,
        selectedServingUnit = selectedServingUnit,
        selectedServingQuantity = selectedServingQuantity,
        customNote = customNote,
        progressiveMeal = progressiveMeal,
        ingredients = ingredients,
        productMetadata = productMetadata
    )

    val allImageFilenames: List<String>
        get() = (listOfNotNull(imageFilename) + additionalImageFilenames + ingredients.flatMap { it.allImageFilenames }).distinct()
}
