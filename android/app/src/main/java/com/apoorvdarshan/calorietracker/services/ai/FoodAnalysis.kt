package com.apoorvdarshan.calorietracker.services.ai

import org.json.JSONObject
import kotlin.math.round

/** Result of AI food-photo / text analysis. */
data class FoodAnalysis(
    val name: String,
    val calories: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int,
    val servingSizeGrams: Double,
    val emoji: String? = null,
    val sugar: Double? = null,
    val addedSugar: Double? = null,
    val fiber: Double? = null,
    val saturatedFat: Double? = null,
    val monounsaturatedFat: Double? = null,
    val polyunsaturatedFat: Double? = null,
    val cholesterol: Double? = null,
    val sodium: Double? = null,
    val potassium: Double? = null
)

/** Per-100g nutrition-label reading. Scaled to a real serving via [scaled]. */
data class NutritionLabelAnalysis(
    val name: String,
    val caloriesPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    val servingSizeGrams: Double? = null,
    val sugarPer100g: Double? = null,
    val addedSugarPer100g: Double? = null,
    val fiberPer100g: Double? = null,
    val saturatedFatPer100g: Double? = null,
    val monounsaturatedFatPer100g: Double? = null,
    val polyunsaturatedFatPer100g: Double? = null,
    val cholesterolPer100g: Double? = null,
    val sodiumPer100g: Double? = null,
    val potassiumPer100g: Double? = null
) {
    fun scaled(toGrams: Double): FoodAnalysis {
        val scale = toGrams / 100.0
        fun s(v: Double?) = v?.let { round(it * scale * 10) / 10 }
        return FoodAnalysis(
            name = name,
            calories = (caloriesPer100g * scale).toInt(),
            protein = (proteinPer100g * scale).toInt(),
            carbs = (carbsPer100g * scale).toInt(),
            fat = (fatPer100g * scale).toInt(),
            servingSizeGrams = toGrams,
            sugar = s(sugarPer100g),
            addedSugar = s(addedSugarPer100g),
            fiber = s(fiberPer100g),
            saturatedFat = s(saturatedFatPer100g),
            monounsaturatedFat = s(monounsaturatedFatPer100g),
            polyunsaturatedFat = s(polyunsaturatedFatPer100g),
            cholesterol = s(cholesterolPer100g),
            sodium = s(sodiumPer100g),
            potassium = s(potassiumPer100g)
        )
    }
}

internal object FoodJsonParser {

    fun extractJson(text: String): String {
        var cleaned = text.trim()
        if (cleaned.startsWith("```json")) cleaned = cleaned.drop(7)
        else if (cleaned.startsWith("```")) cleaned = cleaned.drop(3)
        if (cleaned.endsWith("```")) cleaned = cleaned.dropLast(3)
        return cleaned.trim()
    }

    fun parseFood(text: String): FoodAnalysis {
        val json = runCatching { JSONObject(extractJson(text)) }.getOrNull()
            ?: throw AiError.InvalidResponse
        val name = json.optString("name").takeIf { it.isNotEmpty() } ?: throw AiError.InvalidResponse
        fun optDouble(key: String): Double? =
            if (json.has(key) && !json.isNull(key)) json.optDouble(key) else null
        return FoodAnalysis(
            name = name,
            calories = json.optInt("calories"),
            protein = json.optInt("protein"),
            carbs = json.optInt("carbs"),
            fat = json.optInt("fat"),
            servingSizeGrams = optDouble("serving_size_grams") ?: 100.0,
            emoji = json.optString("emoji").takeIf { it.isNotEmpty() },
            sugar = optDouble("sugar"),
            addedSugar = optDouble("added_sugar"),
            fiber = optDouble("fiber"),
            saturatedFat = optDouble("saturated_fat"),
            monounsaturatedFat = optDouble("monounsaturated_fat"),
            polyunsaturatedFat = optDouble("polyunsaturated_fat"),
            cholesterol = optDouble("cholesterol"),
            sodium = optDouble("sodium"),
            potassium = optDouble("potassium")
        )
    }

    fun parseLabel(text: String): NutritionLabelAnalysis {
        val json = runCatching { JSONObject(extractJson(text)) }.getOrNull()
            ?: throw AiError.InvalidResponse
        val name = json.optString("name").takeIf { it.isNotEmpty() } ?: throw AiError.InvalidResponse
        fun optDouble(key: String): Double? =
            if (json.has(key) && !json.isNull(key)) json.optDouble(key) else null
        return NutritionLabelAnalysis(
            name = name,
            caloriesPer100g = optDouble("calories_per_100g") ?: throw AiError.InvalidResponse,
            proteinPer100g = optDouble("protein_per_100g") ?: throw AiError.InvalidResponse,
            carbsPer100g = optDouble("carbs_per_100g") ?: throw AiError.InvalidResponse,
            fatPer100g = optDouble("fat_per_100g") ?: throw AiError.InvalidResponse,
            servingSizeGrams = optDouble("serving_size_grams"),
            sugarPer100g = optDouble("sugar_per_100g"),
            addedSugarPer100g = optDouble("added_sugar_per_100g"),
            fiberPer100g = optDouble("fiber_per_100g"),
            saturatedFatPer100g = optDouble("saturated_fat_per_100g"),
            monounsaturatedFatPer100g = optDouble("monounsaturated_fat_per_100g"),
            polyunsaturatedFatPer100g = optDouble("polyunsaturated_fat_per_100g"),
            cholesterolPer100g = optDouble("cholesterol_per_100g"),
            sodiumPer100g = optDouble("sodium_per_100g"),
            potassiumPer100g = optDouble("potassium_per_100g")
        )
    }
}
