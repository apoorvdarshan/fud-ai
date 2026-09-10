package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.models.SupplementalNutrient
import com.apoorvdarshan.calorietracker.models.UserProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt

/** Result of AI food-photo / text analysis. */
@Serializable
data class FoodAnalysis(
    val name: String,
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val servingSizeGrams: Double,
    val emoji: String? = null,
    val sugar: Double? = null,
    val addedSugar: Double? = null,
    val fiber: Double? = null,
    val saturatedFat: Double? = null,
    val monounsaturatedFat: Double? = null,
    val polyunsaturatedFat: Double? = null,
    val cholesterol: Double? = null,
    val caffeine: Double? = null,
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
    val servingUnitOptions: List<ServingUnitOption> = emptyList(),
    val selectedServingUnit: String? = null,
    val selectedServingQuantity: Double? = null,
    /** False for restored/legacy totals whose original food mass is unavailable. */
    val servingSizeIsKnown: Boolean = true,
    val customNote: String? = null,
    val progressiveMeal: Boolean = false,
    val ingredients: List<MealIngredient> = emptyList(),
    val productMetadata: FoodProductMetadata? = null
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
    val caffeinePer100g: Double? = null,
    val supplementalNutrientsPer100g: Map<String, Double> = emptyMap(),
    val sodiumPer100g: Double? = null,
    val potassiumPer100g: Double? = null,
    val transFatPer100g: Double? = null,
    val calciumPer100g: Double? = null,
    val ironPer100g: Double? = null,
    val magnesiumPer100g: Double? = null,
    val zincPer100g: Double? = null,
    val vitaminAPer100g: Double? = null,
    val vitaminCPer100g: Double? = null,
    val vitaminDPer100g: Double? = null,
    val vitaminB12Per100g: Double? = null,
    val vitaminEPer100g: Double? = null,
    val vitaminKPer100g: Double? = null,
    val folatePer100g: Double? = null,
    val omega3Per100g: Double? = null,
    val servingUnitOptions: List<ServingUnitOption> = emptyList()
) {
    fun scaled(toGrams: Double): FoodAnalysis {
        val scale = toGrams / 100.0
        fun s(v: Double?) = v?.let { round(it * scale * 10) / 10 }
        val selectedOption = servingUnitOptions.firstOrNull()
        return FoodAnalysis(
            name = name,
            calories = (caloriesPer100g * scale).toInt(),
            protein = proteinPer100g * scale,
            carbs = carbsPer100g * scale,
            fat = fatPer100g * scale,
            servingSizeGrams = toGrams,
            sugar = s(sugarPer100g),
            addedSugar = s(addedSugarPer100g),
            fiber = s(fiberPer100g),
            saturatedFat = s(saturatedFatPer100g),
            monounsaturatedFat = s(monounsaturatedFatPer100g),
            polyunsaturatedFat = s(polyunsaturatedFatPer100g),
            cholesterol = s(cholesterolPer100g),
            caffeine = s(caffeinePer100g),
            supplementalNutrients = supplementalNutrientsPer100g.mapValues { (_, value) ->
                round(value * scale * 10) / 10
            },
            sodium = s(sodiumPer100g),
            potassium = s(potassiumPer100g),
            transFat = s(transFatPer100g),
            calcium = s(calciumPer100g),
            iron = s(ironPer100g),
            magnesium = s(magnesiumPer100g),
            zinc = s(zincPer100g),
            vitaminA = s(vitaminAPer100g),
            vitaminC = s(vitaminCPer100g),
            vitaminD = s(vitaminDPer100g),
            vitaminB12 = s(vitaminB12Per100g),
            vitaminE = s(vitaminEPer100g),
            vitaminK = s(vitaminKPer100g),
            folate = s(folatePer100g),
            omega3 = s(omega3Per100g),
            servingUnitOptions = servingUnitOptions,
            selectedServingUnit = selectedOption?.unit,
            selectedServingQuantity = selectedOption?.quantityFor(toGrams)
        )
    }
}

data class HealthEnergyGoalSuggestion(
    val calories: Int,
    val reason: String? = null
)

/** AI-computed daily targets returned by FoodAnalysisService.calculateGoals. */
data class GoalCalculation(
    val calories: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int,
    val reason: String? = null
)

internal data class ParsedFoodResponse(
    val analysis: FoodAnalysis,
    val shouldRequestServingUnitFallback: Boolean
)

internal data class ParsedNutritionLabelResponse(
    val analysis: NutritionLabelAnalysis,
    val shouldRequestServingUnitFallback: Boolean
)

internal data class ServingUnitOptionsParseResult(
    val options: List<ServingUnitOption>,
    val shouldRequestFallback: Boolean
)

internal object FoodJsonParser {

    fun extractJson(text: String): String {
        var cleaned = text.trim()

        val openFence = cleaned.indexOf("```json", ignoreCase = true)
            .takeIf { it >= 0 }
            ?: cleaned.indexOf("```").takeIf { it >= 0 }
        if (openFence != null) {
            val after = if (cleaned.regionMatches(openFence, "```json", 0, 7, ignoreCase = true)) openFence + 7 else openFence + 3
            cleaned = cleaned.substring(after)
            val closeFence = cleaned.lastIndexOf("```")
            if (closeFence >= 0) cleaned = cleaned.substring(0, closeFence)
        }
        cleaned = cleaned.trim()

        val firstBrace = cleaned.indexOf('{')
        if (firstBrace < 0) return cleaned
        var depth = 0
        var inString = false
        var escape = false
        var endIndex = -1
        for (i in firstBrace until cleaned.length) {
            val ch = cleaned[i]
            if (escape) { escape = false; continue }
            if (ch == '\\') { escape = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (inString) continue
            if (ch == '{') depth++
            else if (ch == '}') {
                depth--
                if (depth == 0) { endIndex = i + 1; break }
            }
        }
        return if (endIndex > firstBrace) cleaned.substring(firstBrace, endIndex) else cleaned
    }

    fun parseFood(text: String): FoodAnalysis = parseFoodResponse(text).analysis

    fun parseFoodResponse(text: String): ParsedFoodResponse {
        val extractedJson = extractJson(text)
        val json = runCatching { JSONObject(extractedJson) }.getOrNull()
            ?: throw AiError.InvalidResponse
        val name = json.optString("name").takeIf { it.isNotEmpty() } ?: throw AiError.InvalidResponse
        val responseServingSizeGrams = optDouble(json, "serving_size_grams")
        val servingSizeGrams = responseServingSizeGrams ?: 1.0
        val unitOptionsResult = parseServingUnitOptionsResult(extractedJson, responseServingSizeGrams)
        val unitOptions = unitOptionsResult.options
        val selectedOption = unitOptions.firstOrNull()
        fun optDouble(key: String): Double? =
            optDouble(json, key)
        val analysis = FoodAnalysis(
            name = name,
            calories = json.optInt("calories"),
            protein = optDouble("protein") ?: 0.0,
            carbs = optDouble("carbs") ?: 0.0,
            fat = optDouble("fat") ?: 0.0,
            servingSizeGrams = servingSizeGrams,
            emoji = json.optString("emoji").takeIf { it.isNotEmpty() },
            sugar = optDouble("sugar"),
            addedSugar = optDouble("added_sugar"),
            fiber = optDouble("fiber"),
            saturatedFat = optDouble("saturated_fat"),
            monounsaturatedFat = optDouble("monounsaturated_fat"),
            polyunsaturatedFat = optDouble("polyunsaturated_fat"),
            cholesterol = optDouble("cholesterol"),
            caffeine = optDouble("caffeine"),
            supplementalNutrients = SupplementalNutrient.values().mapNotNull { nutrient ->
                optDouble(nutrient.apiKey)?.let { nutrient.storageKey to it }
            }.toMap(),
            sodium = optDouble("sodium"),
            potassium = optDouble("potassium"),
            transFat = optDouble("trans_fat"),
            calcium = optDouble("calcium"),
            iron = optDouble("iron"),
            magnesium = optDouble("magnesium"),
            zinc = optDouble("zinc"),
            vitaminA = optDouble("vitamin_a"),
            vitaminC = optDouble("vitamin_c"),
            vitaminD = optDouble("vitamin_d"),
            vitaminB12 = optDouble("vitamin_b12"),
            vitaminE = optDouble("vitamin_e"),
            vitaminK = optDouble("vitamin_k"),
            folate = optDouble("folate"),
            omega3 = optDouble("omega_3"),
            ingredients = parseIngredients(json),
            servingUnitOptions = unitOptions,
            selectedServingUnit = if (responseServingSizeGrams == null) "serving" else selectedOption?.unit,
            selectedServingQuantity = if (responseServingSizeGrams == null) 1.0 else selectedOption?.quantityFor(servingSizeGrams),
            servingSizeIsKnown = responseServingSizeGrams != null
        )
        return ParsedFoodResponse(
            analysis = analysis,
            shouldRequestServingUnitFallback = unitOptionsResult.shouldRequestFallback
        )
    }

    private fun parseIngredients(json: JSONObject): List<MealIngredient> {
        val array = json.optJSONArray("ingredients") ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), 20)) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val grams = optDouble(item, "grams") ?: continue
                val calories = optDouble(item, "calories") ?: continue
                val protein = optDouble(item, "protein") ?: continue
                val carbs = optDouble(item, "carbs") ?: continue
                val fat = optDouble(item, "fat") ?: continue
                if (name.isEmpty() || grams <= 0 || listOf(calories, protein, carbs, fat).any { it < 0 }) continue
                add(
                    MealIngredient(
                        name = name,
                        grams = grams,
                        calories = calories.roundToInt(),
                        protein = protein,
                        carbs = carbs,
                        fat = fat
                    )
                )
            }
        }
    }

    fun parseLabel(text: String): NutritionLabelAnalysis = parseLabelResponse(text).analysis

    fun parseLabelResponse(text: String): ParsedNutritionLabelResponse {
        val extractedJson = extractJson(text)
        val json = runCatching { JSONObject(extractedJson) }.getOrNull()
            ?: throw AiError.InvalidResponse
        val name = json.optString("name").takeIf { it.isNotEmpty() } ?: throw AiError.InvalidResponse
        fun optDouble(key: String): Double? =
            optDouble(json, key)
        val servingSizeGrams = optDouble("serving_size_grams")
        val unitOptionsResult = parseServingUnitOptionsResult(extractedJson, servingSizeGrams)
        val analysis = NutritionLabelAnalysis(
            name = name,
            caloriesPer100g = optDouble("calories_per_100g") ?: throw AiError.InvalidResponse,
            proteinPer100g = optDouble("protein_per_100g") ?: throw AiError.InvalidResponse,
            carbsPer100g = optDouble("carbs_per_100g") ?: throw AiError.InvalidResponse,
            fatPer100g = optDouble("fat_per_100g") ?: throw AiError.InvalidResponse,
            servingSizeGrams = servingSizeGrams,
            sugarPer100g = optDouble("sugar_per_100g"),
            addedSugarPer100g = optDouble("added_sugar_per_100g"),
            fiberPer100g = optDouble("fiber_per_100g"),
            saturatedFatPer100g = optDouble("saturated_fat_per_100g"),
            monounsaturatedFatPer100g = optDouble("monounsaturated_fat_per_100g"),
            polyunsaturatedFatPer100g = optDouble("polyunsaturated_fat_per_100g"),
            cholesterolPer100g = optDouble("cholesterol_per_100g"),
            caffeinePer100g = optDouble("caffeine_per_100g"),
            supplementalNutrientsPer100g = SupplementalNutrient.values().mapNotNull { nutrient ->
                optDouble("${nutrient.apiKey}_per_100g")?.let { nutrient.storageKey to it }
            }.toMap(),
            sodiumPer100g = optDouble("sodium_per_100g"),
            potassiumPer100g = optDouble("potassium_per_100g"),
            transFatPer100g = optDouble("trans_fat_per_100g"),
            calciumPer100g = optDouble("calcium_per_100g"),
            ironPer100g = optDouble("iron_per_100g"),
            magnesiumPer100g = optDouble("magnesium_per_100g"),
            zincPer100g = optDouble("zinc_per_100g"),
            vitaminAPer100g = optDouble("vitamin_a_per_100g"),
            vitaminCPer100g = optDouble("vitamin_c_per_100g"),
            vitaminDPer100g = optDouble("vitamin_d_per_100g"),
            vitaminB12Per100g = optDouble("vitamin_b12_per_100g"),
            vitaminEPer100g = optDouble("vitamin_e_per_100g"),
            vitaminKPer100g = optDouble("vitamin_k_per_100g"),
            folatePer100g = optDouble("folate_per_100g"),
            omega3Per100g = optDouble("omega_3_per_100g"),
            servingUnitOptions = unitOptionsResult.options
        )
        return ParsedNutritionLabelResponse(
            analysis = analysis,
            shouldRequestServingUnitFallback = unitOptionsResult.shouldRequestFallback
        )
    }

    fun parseServingUnitOptions(text: String, servingSizeGrams: Double?): List<ServingUnitOption> {
        return parseServingUnitOptionsResult(text, servingSizeGrams).options
    }

    fun parseServingUnitOptionsResult(
        text: String,
        servingSizeGrams: Double?
    ): ServingUnitOptionsParseResult {
        val json = runCatching {
            Json.parseToJsonElement(extractJson(text)) as? JsonObject
        }.getOrNull()
            ?: throw AiError.InvalidResponse

        val rawOptions = when {
            "unit_options" in json -> json["unit_options"]
            "serving_unit_options" in json -> json["serving_unit_options"]
            else -> null
        } ?: return ServingUnitOptionsParseResult(
            options = emptyList(),
            shouldRequestFallback = true
        )
        val optionsArray = rawOptions as? JsonArray
            ?: return ServingUnitOptionsParseResult(
                options = emptyList(),
                shouldRequestFallback = true
            )

        val seen = mutableSetOf<String>()
        val options = mutableListOf<ServingUnitOption>()
        for (element in optionsArray) {
            val option = parseServingUnitOption(element, servingSizeGrams)
                ?: return ServingUnitOptionsParseResult(
                    options = emptyList(),
                    shouldRequestFallback = true
                )
            if (seen.add(option.id)) options.add(option)
        }
        return ServingUnitOptionsParseResult(
            options = options.take(4),
            shouldRequestFallback = false
        )
    }

    fun parseOptionalNutrientGoals(text: String): OptionalNutrientGoals {
        val json = runCatching { JSONObject(extractJson(text)) }.getOrNull()
            ?: throw AiError.InvalidResponse
        fun optInt(vararg keys: String, fallback: Int): Int =
            keys.firstNotNullOfOrNull { key ->
                if (!json.has(key) || json.isNull(key)) null
                else when (val value = json.opt(key)) {
                    is Number -> value.toDouble().roundToInt()
                    is String -> value.toDoubleOrNull()?.roundToInt()
                    else -> null
                }
            }?.coerceAtLeast(0) ?: fallback
        return OptionalNutrientGoals(
            sugar = optInt("sugar", "sugar_g", fallback = OptionalNutrientGoals.Default.sugar),
            addedSugar = optInt("added_sugar", "addedSugar", "added_sugar_g", fallback = OptionalNutrientGoals.Default.addedSugar),
            fiber = optInt("fiber", "fiber_g", fallback = OptionalNutrientGoals.Default.fiber),
            saturatedFat = optInt("saturated_fat", "saturatedFat", "saturated_fat_g", fallback = OptionalNutrientGoals.Default.saturatedFat),
            cholesterol = optInt("cholesterol", "cholesterol_mg", fallback = OptionalNutrientGoals.Default.cholesterol),
            caffeine = optInt("caffeine", "caffeine_mg", fallback = OptionalNutrientGoals.Default.caffeine),
            sodium = optInt("sodium", "sodium_mg", fallback = OptionalNutrientGoals.Default.sodium),
            potassium = optInt("potassium", "potassium_mg", fallback = OptionalNutrientGoals.Default.potassium),
            transFat = optInt("trans_fat", "transFat", "trans_fat_g", fallback = OptionalNutrientGoals.Default.transFat),
            calcium = optInt("calcium", "calcium_mg", fallback = OptionalNutrientGoals.Default.calcium),
            iron = optInt("iron", "iron_mg", fallback = OptionalNutrientGoals.Default.iron),
            magnesium = optInt("magnesium", "magnesium_mg", fallback = OptionalNutrientGoals.Default.magnesium),
            zinc = optInt("zinc", "zinc_mg", fallback = OptionalNutrientGoals.Default.zinc),
            vitaminA = optInt("vitamin_a", "vitaminA", "vitamin_a_mcg", fallback = OptionalNutrientGoals.Default.vitaminA),
            vitaminC = optInt("vitamin_c", "vitaminC", "vitamin_c_mg", fallback = OptionalNutrientGoals.Default.vitaminC),
            vitaminD = optInt("vitamin_d", "vitaminD", "vitamin_d_mcg", fallback = OptionalNutrientGoals.Default.vitaminD),
            vitaminB12 = optInt("vitamin_b12", "vitaminB12", "vitamin_b12_mcg", fallback = OptionalNutrientGoals.Default.vitaminB12),
            vitaminE = optInt("vitamin_e", "vitaminE", "vitamin_e_mg", fallback = OptionalNutrientGoals.Default.vitaminE),
            vitaminK = optInt("vitamin_k", "vitaminK", "vitamin_k_mcg", fallback = OptionalNutrientGoals.Default.vitaminK),
            folate = optInt("folate", "folate_mcg", fallback = OptionalNutrientGoals.Default.folate),
            omega3 = optInt("omega_3", "omega3", "omega_3_g", fallback = OptionalNutrientGoals.Default.omega3),
            supplementalNutrients = SupplementalNutrient.values().associate { nutrient ->
                nutrient.storageKey to optInt(nutrient.apiKey, "${nutrient.apiKey}_g", fallback = 0)
            }
        )
    }

    fun parseHealthEnergyGoalSuggestion(text: String): HealthEnergyGoalSuggestion {
        val json = runCatching { JSONObject(extractJson(text)) }.getOrNull()
            ?: throw AiError.InvalidResponse
        val calories = when (val value = json.opt("calories")) {
            is Number -> value.toDouble().roundToInt()
            is String -> value.toDoubleOrNull()?.roundToInt()
            else -> null
        } ?: throw AiError.InvalidResponse
        return HealthEnergyGoalSuggestion(
            calories = calories.coerceIn(800, 6000),
            reason = json.optString("reason").takeIf { it.isNotBlank() }
        )
    }

    fun parseAllergensFromLabReport(text: String): List<String> {
        val json = runCatching { JSONObject(extractJson(text)) }.getOrNull()
            ?: throw AiError.InvalidResponse
        val arr = json.optJSONArray("allergens") ?: throw AiError.InvalidResponse
        val seen = linkedSetOf<String>()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val raw = when (val value = arr.opt(i)) {
                    is String -> value.trim()
                    is Number -> value.toString().trim()
                    else -> continue
                }
                if (raw.isEmpty() || !seen.add(raw.lowercase())) continue
                add(raw)
            }
        }
    }

    fun parseGoalCalculation(text: String, profile: UserProfile? = null): GoalCalculation {
        val json = runCatching { Json.parseToJsonElement(extractJson(text)) as? JsonObject }
            .getOrNull() ?: throw AiError.InvalidResponse
        fun numberOf(key: String): Double? = (json[key] as? JsonPrimitive)
            ?.content
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }
        val rawCalories = numberOf("calories") ?: throw AiError.InvalidResponse
        val rawProtein = numberOf("protein") ?: throw AiError.InvalidResponse
        val rawCarbs = numberOf("carbs") ?: throw AiError.InvalidResponse
        val rawFat = numberOf("fat") ?: throw AiError.InvalidResponse
        // Bad provider output must leave the user's existing targets untouched. Do not silently
        // turn negative, zero-macro, extreme, or internally incoherent JSON into a plausible plan.
        if (rawCalories !in 800.0..6_000.0 || rawProtein !in 10.0..500.0 ||
            rawCarbs !in 0.0..1_500.0 || rawFat !in 10.0..400.0
        ) throw AiError.InvalidResponse
        val reportedMacroCalories = rawProtein * 4 + rawCarbs * 4 + rawFat * 9
        val allowedMismatch = maxOf(100.0, rawCalories * 0.15)
        if (kotlin.math.abs(reportedMacroCalories - rawCalories) > allowedMismatch) {
            throw AiError.InvalidResponse
        }

        val calories = rawCalories.roundToInt()
        var protein = minOf(rawProtein.roundToInt(), calories / 4)
        // Derive the stored carb value from the validated calories, protein and fat so the saved
        // plan satisfies 4P + 4C + 9F exactly after integer rounding.
        val requestedFat = rawFat.roundToInt()

        val requiredFatResidue = Math.floorMod(calories, 4)
        var fatCapacity = (calories - protein * 4) / 9
        while (fatCapacity < requiredFatResidue && protein > 0) {
            protein -= 1
            fatCapacity = (calories - protein * 4) / 9
        }
        fatCapacity = minOf(fatCapacity, 400)
        val targetFat = requestedFat.coerceAtMost(fatCapacity)
        val lowerFat = targetFat - Math.floorMod(targetFat - requiredFatResidue, 4)
        val upperFat = lowerFat + 4
        val fat = listOf(lowerFat, upperFat)
            .filter { it in 0..fatCapacity }
            .minByOrNull { kotlin.math.abs(it - targetFat) }
            ?: requiredFatResidue.coerceAtMost(fatCapacity)
        val carbs = (calories - protein * 4 - fat * 9) / 4
        if (protein < 10 || fat < 10 || carbs < 0) throw AiError.InvalidResponse
        if (profile != null) {
            val proteinReference = maxOf(10, profile.proteinGoal)
            val fatReference = maxOf(10, profile.fatGoal)
            val proteinRange = (proteinReference * 0.70).roundToInt()..(proteinReference * 1.30).roundToInt()
            val fatRange = (fatReference * 0.50).roundToInt()..(fatReference * 1.50).roundToInt()
            if (protein !in proteinRange || fat !in fatRange) throw AiError.InvalidResponse
            val calorieReference = profile.dailyCalories.coerceIn(800, 6_000)
            val calorieRange = maxOf(800, (calorieReference * 0.50).roundToInt())..
                minOf(6_000, (calorieReference * 2.00).roundToInt())
            if (calories !in calorieRange) throw AiError.InvalidResponse
        }
        return GoalCalculation(
            calories = calories,
            protein = protein,
            carbs = carbs.coerceIn(0, 1500),
            fat = fat,
            reason = (json["reason"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotBlank() }
        )
    }

    private fun parseServingUnitOption(
        element: JsonElement,
        servingSizeGrams: Double?
    ): ServingUnitOption? {
        val raw = element as? JsonObject ?: return null
        val unitValue = raw["unit"] as? JsonPrimitive ?: return null
        val unit = unitValue.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val quantity = positiveJsonNumber(raw["quantity"]) ?: return null
        val gramsPerUnit = positiveJsonNumber(
            raw["grams_per_unit"] ?: raw["gramsPerUnit"]
        ) ?: return null
        val option = ServingUnitOption(
            unit = unit,
            gramsPerUnit = gramsPerUnit,
            quantity = quantity
        )
        if (!option.isValid || option.isGramUnit) return null
        if (!approximatelyRepresentsServing(quantity, gramsPerUnit, servingSizeGrams)) return null
        return option
    }

    private fun positiveJsonNumber(value: JsonElement?): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toDoubleOrNull()
            ?.takeIf { it > 0 && !it.isNaN() && !it.isInfinite() }
    }

    private fun approximatelyRepresentsServing(
        quantity: Double,
        gramsPerUnit: Double,
        servingSizeGrams: Double?
    ): Boolean {
        val servingGrams = servingSizeGrams?.takeIf { it > 0 } ?: return true
        val representedGrams = quantity * gramsPerUnit
        val toleranceGrams = max(5.0, servingGrams * 0.15)
        return abs(representedGrams - servingGrams) <= toleranceGrams
    }

    private fun optDouble(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val value = json.opt(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }?.takeUnless { it.isNaN() || it.isInfinite() }
    }
}
