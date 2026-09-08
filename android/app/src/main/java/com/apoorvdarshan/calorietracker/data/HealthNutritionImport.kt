package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.services.health.ExternalNutrition
import java.util.UUID
import kotlin.math.roundToInt

internal fun healthImportKey(record: ExternalNutrition): String =
    "health-nutrition:${record.originPackage}:${record.recordId}"

/** Stable identities, never value-based deduplication: identical meals can be legitimate. */
internal fun nutritionImportCandidates(
    records: List<ExternalNutrition>,
    ownPackage: String,
    importedKeys: Set<String>
): List<ExternalNutrition> = records.filter {
    it.originPackage.isNotBlank() && it.originPackage != ownPackage &&
        it.originPackage != "com.apoorvdarshan.calorietracker" &&
        it.originPackage != "com.apoorvdarshan.calorietracker.debug" &&
        it.originPackage != "com.apoorvdarshan.calorietracker.debug2" &&
        it.recordId.isNotBlank() && healthImportKey(it) !in importedKeys
}.distinctBy(::healthImportKey)

internal fun importedNutritionEntry(record: ExternalNutrition, fallbackName: String): FoodEntry {
    return FoodEntry(
        id = UUID.nameUUIDFromBytes(healthImportKey(record).toByteArray(Charsets.UTF_8)),
        name = record.name?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName,
        healthConnectOrigin = record.originPackage,
        healthConnectRecordId = record.recordId,
        calories = (record.calories ?: 0.0).roundToInt(),
        protein = record.protein ?: 0.0,
        carbs = record.carbs ?: 0.0,
        fat = record.fat ?: 0.0,
        timestamp = record.time,
        source = FoodSource.MANUAL,
        mealType = record.mealType,
        sugar = record.sugar,
        fiber = record.fiber,
        saturatedFat = record.saturatedFat,
        monounsaturatedFat = record.monounsaturatedFat,
        polyunsaturatedFat = record.polyunsaturatedFat,
        cholesterol = record.cholesterol,
        caffeine = record.caffeine,
        sodium = record.sodium,
        potassium = record.potassium,
        transFat = record.transFat,
        calcium = record.calcium,
        iron = record.iron,
        magnesium = record.magnesium,
        zinc = record.zinc,
        vitaminA = record.vitaminA,
        vitaminC = record.vitaminC,
        vitaminD = record.vitaminD,
        vitaminB12 = record.vitaminB12,
        vitaminE = record.vitaminE,
        vitaminK = record.vitaminK,
        folate = record.folate,
        // Health Connect NutritionRecord exposes nutrient totals but
        // no food-mass/custom-metadata field. Preserve that truth as
        // one logged serving instead of fabricating 100 grams.
        selectedServingUnit = "serving",
        selectedServingQuantity = 1.0
    )
}

internal data class NutritionImportMerge(val entries: List<FoodEntry>, val ledger: Set<String>, val added: Int)

internal fun mergeNutritionImport(
    current: List<FoodEntry>, ledger: Set<String>, records: List<ExternalNutrition>,
    ownPackage: String, fallbackName: String
): NutritionImportMerge {
    val candidates = nutritionImportCandidates(records, ownPackage, ledger)
    val existing = current.map { it.id }.toSet()
    val additions = candidates.map { importedNutritionEntry(it, fallbackName) }.filter { it.id !in existing }
    return NutritionImportMerge(
        (current + additions).sortedBy { it.timestamp },
        ledger + candidates.map(::healthImportKey), additions.size
    )
}
