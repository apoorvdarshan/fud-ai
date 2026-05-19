package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import com.apoorvdarshan.calorietracker.models.UserProfile
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * Single-shot food / text / nutrition-label analysis. Port of iOS GeminiService.
 * Routes the call to the right per-format client based on the user's selected provider.
 */
class FoodAnalysisService(
    private val prefs: PreferencesStore,
    private val keyStore: KeyStore,
    private val okHttp: OkHttpClient = defaultClient
) {

    suspend fun estimateOptionalNutrientGoals(profile: UserProfile?): OptionalNutrientGoals {
        val profileContext = profile?.let {
            """
                Profile:
                - age: ${it.age}
                - gender: ${it.gender.name.lowercase()}
                - height_cm: ${String.format(java.util.Locale.US, "%.1f", it.heightCm)}
                - weight_kg: ${String.format(java.util.Locale.US, "%.1f", it.weightKg)}
                - activity_level: ${it.activityLevel.name.lowercase()}
                - weight_goal: ${it.goal.name.lowercase()}
                - daily_calories: ${it.effectiveCalories}
                - daily_protein_g: ${it.effectiveProtein}
                - daily_carbs_g: ${it.effectiveCarbs}
                - daily_fat_g: ${it.effectiveFat}
            """.trimIndent()
        } ?: "No user profile is available. Use conservative general adult defaults."
        val prompt = """
            Estimate practical daily goals for nutrients outside the app's calorie/protein/carbs/fat calculator.

            $profileContext

            Return ONLY JSON in this exact shape:
            {"sugar":50,"added_sugar":25,"fiber":30,"saturated_fat":20,"cholesterol":300,"sodium":2300,"potassium":3500}

            Rules:
            - Do not return calories, protein, carbs, or fat.
            - Keep this independent from macro calculation; only estimate the seven listed optional nutrient goals.
            - sugar, added_sugar, fiber, and saturated_fat are grams per day.
            - cholesterol, sodium, and potassium are milligrams per day.
            - Use realistic non-medical nutrition targets for an average adult adjusted by profile and calorie target.
            - Keep added_sugar and saturated_fat near or below 10% of calories when possible.
            - Fiber should generally scale around 14g per 1000 kcal, with a practical adult range.
            - Sodium should usually stay near general adult guidance unless the profile strongly suggests otherwise.
            - Potassium should use a practical daily target, not food-log intake.
            - Use integers only.
        """.trimIndent()
        return FoodJsonParser.parseOptionalNutrientGoals(callAi(prompt, imageBytes = null))
    }

    suspend fun analyzeText(description: String): FoodAnalysis {
        val prompt = """
            Estimate the nutritional content for: $description
            Parse any quantities, brands, and multiple items from the text. If a brand is mentioned, use that brand's known nutritional data. If multiple items are described, sum up the total nutrition.
            Respond ONLY with JSON:
            {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"emoji":"<single specific food emoji>","sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0,"unit_options":[]}
            Calories/protein/carbs/fat are integers. serving_size_grams is the estimated total weight in grams. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
            The [] in unit_options above is only a JSON shape placeholder; replace it with options when a non-gram unit is obvious.
            unit_options is required when the text names an obvious non-gram serving unit, and optional otherwise. Use slice/piece for pizza, cake, bread, cookies, fruit pieces, etc.; use ml/cup/fl oz for drinks, milk, soup, smoothies, sauces, etc.; use tbsp/tsp for spooned foods; use can/packet when packaged. Its quantity must describe the whole analyzed amount, not always 1. Do not copy any sample number; use the quantity stated or clearly implied by the meal. Use [] only when no non-gram unit is apparent. Do not include g/grams in unit_options.
            For "emoji" pick the single most specific food emoji that depicts this dish — e.g. 🥚 for eggs, 🍕 for pizza, 🍎 for an apple, 🥗 for a salad, 🍔 for a burger, 🍜 for ramen, 🍰 for cake, 🥑 for avocado, ☕ for coffee, 🍣 for sushi. Only fall back to 🍽️ when the food truly cannot be represented by any specific emoji. Use null for any nutrient you cannot estimate.
        """.trimIndent()
        val analysis = FoodJsonParser.parseFood(callAi(prompt, null))
        return addingFallbackServingUnits(analysis, imageBytes = null, description = description)
    }

    suspend fun analyzeAuto(imageBytes: ByteArray): FoodAnalysis {
        val prompt = """
            Analyze this image. It could be either a photo of food OR a nutrition facts label.

            If it's a food photo: identify the food and estimate nutritional content for the serving shown.
            If it's a nutrition label: read the values and calculate for one serving size as listed on the label.

            Respond ONLY with JSON:
            {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0,"unit_options":[]}
            Calories/protein/carbs/fat are integers. serving_size_grams is the estimated weight in grams of the serving. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
            The [] in unit_options above is only a JSON shape placeholder; replace it with options when a non-gram unit is obvious.
            unit_options is required for obvious non-gram units visible in the image or label. Use slice/piece for pizza, cake, bread, cookies, fruit pieces, etc.; use ml/cup/fl oz for drinks, milk, soup, smoothies, sauces, etc.; use tbsp/tsp for spooned foods; use can/packet when packaged. Its quantity must describe the whole analyzed amount, not always 1. For a whole or mostly-whole divisible food like cake, pie, or pizza, count the visible pieces/slices and derive grams_per_unit from serving_size_grams / quantity. If N slices are visible, return quantity N. Use quantity 1 only when a single piece/slice is actually the analyzed portion. Use [] only when no non-gram unit is apparent. Do not include g/grams in unit_options.
            Use null for any nutrient you cannot estimate.
        """.trimIndent()
        val analysis = FoodJsonParser.parseFood(callAi(prompt, imageBytes))
        return addingFallbackServingUnits(analysis, imageBytes = imageBytes, description = null)
    }

    suspend fun analyzeFood(imageBytes: ByteArray, description: String? = null): FoodAnalysis {
        var prompt = """
            Analyze this food image. Identify the food and estimate its nutritional content.
            Respond ONLY with JSON:
            {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0,"unit_options":[]}
            Calories/protein/carbs/fat are integers. serving_size_grams is the estimated weight in grams of the serving shown. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
            The [] in unit_options above is only a JSON shape placeholder; replace it with options when a non-gram unit is obvious.
            unit_options is required for obvious non-gram units visible in the food. Use slice/piece for pizza, cake, bread, cookies, fruit pieces, etc.; use ml/cup/fl oz for drinks, milk, soup, smoothies, sauces, etc.; use tbsp/tsp for spooned foods; use can/packet when packaged. Its quantity must describe the whole analyzed amount, not always 1. For a whole or mostly-whole divisible food like cake, pie, or pizza, count the visible pieces/slices and derive grams_per_unit from serving_size_grams / quantity. If N slices are visible, return quantity N. Use quantity 1 only when a single piece/slice is actually the analyzed portion. Use [] only when no non-gram unit is apparent. Do not include g/grams in unit_options.
            Give your best estimate for the visible food amount shown in the image. For whole/mostly-whole cakes, pizzas, pies, loaves, or similar foods, estimate the total visible item/remaining item weight rather than defaulting to one slice. Use null for any nutrient you cannot estimate.
        """.trimIndent()
        if (!description.isNullOrBlank()) {
            prompt += "\n\nAdditional context from the user about this meal: $description\nUse this context to improve accuracy of identification, portion size, and nutrition estimates."
        }
        val analysis = FoodJsonParser.parseFood(callAi(prompt, imageBytes))
        return addingFallbackServingUnits(analysis, imageBytes = imageBytes, description = description)
    }

    suspend fun analyzeFood(imageBytesList: List<ByteArray>): FoodAnalysis {
        val prompt = """
            Analyze these food images together. They are different angles or supporting photos of the same meal.
            Use all images to identify the food and estimate the total nutritional content for the serving shown.
            Respond ONLY with JSON:
            {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0,"unit_options":[]}
            Calories/protein/carbs/fat are integers. serving_size_grams is the estimated weight in grams of the serving shown. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
            The [] in unit_options above is only a JSON shape placeholder; replace it with options when a non-gram unit is obvious.
            unit_options is required for obvious non-gram units visible in the food. Use slice/piece for pizza, cake, bread, cookies, fruit pieces, etc.; use ml/cup/fl oz for drinks, milk, soup, smoothies, sauces, etc.; use tbsp/tsp for spooned foods; use can/packet when packaged. Its quantity must describe the whole analyzed amount, not always 1. Use [] only when no non-gram unit is apparent. Do not include g/grams in unit_options.
            Do not double-count the meal across images. Treat the photos as multiple views of the same item unless there are clearly separate foods.
            Use null for any nutrient you cannot estimate.
        """.trimIndent()
        val images = imageBytesList.filter { it.isNotEmpty() }
        if (images.isEmpty()) throw AiError.InvalidResponse
        val analysis = FoodJsonParser.parseFood(callAi(prompt, images))
        return addingFallbackServingUnits(analysis, imageBytes = images.first(), description = null)
    }

    suspend fun analyzeNutritionLabel(imageBytes: ByteArray, servingGrams: Double): FoodAnalysis {
        val prompt = """
            Read this nutrition facts label and extract per-100g values. If the label only shows per-serving, normalize using the serving size listed on the label.
            Respond ONLY with JSON:
            {"name":"...","calories_per_100g":0.0,"protein_per_100g":0.0,"carbs_per_100g":0.0,"fat_per_100g":0.0,"serving_size_grams":0.0,"sugar_per_100g":0.0,"added_sugar_per_100g":0.0,"fiber_per_100g":0.0,"saturated_fat_per_100g":0.0,"monounsaturated_fat_per_100g":0.0,"polyunsaturated_fat_per_100g":0.0,"cholesterol_per_100g":0.0,"sodium_per_100g":0.0,"potassium_per_100g":0.0,"unit_options":[]}
            The [] in unit_options above is only a JSON shape placeholder; replace it with options when a non-gram unit is visible.
            All values should be numbers. If serving size or any nutrient is not available, use null. unit_options is required when a non-gram label serving unit is visible, such as slice, piece, tbsp, cup, ml, fl oz, can, or packet. Do not copy any sample number; use the quantity shown on the label. Use [] only when no non-gram unit is visible. Do not include g/grams in unit_options.
        """.trimIndent()
        val analysis = FoodJsonParser.parseLabel(callAi(prompt, imageBytes))
        return addingFallbackServingUnits(analysis, imageBytes).scaled(servingGrams)
    }

    // -- Internal dispatch ------------------------------------------------

    private suspend fun callAi(prompt: String, imageBytes: ByteArray?): String {
        return callAi(prompt, imageBytes?.let { listOf(it) }.orEmpty())
    }

    private suspend fun callAi(prompt: String, imageBytesList: List<ByteArray>): String {
        val context = prefs.userContext.first()
        val finalPrompt = if (context.isNotBlank()) "User context (apply to every analysis): $context\n\n$prompt" else prompt

        val primary = prefs.selectedAIProvider.first()
        val primaryModel = primary.supportedModelOrDefault(prefs.selectedAIModel.first())
        val primaryBaseUrl = prefs.customBaseUrl(primary).first()?.takeIf { it.isNotEmpty() } ?: primary.baseUrl
        val primaryKey = keyStore.apiKey(primary)
        if (primary.requiresApiKey && primaryKey.isNullOrEmpty()) throw AiError.NoApiKey

        return try {
            dispatch(primary, primaryModel, primaryBaseUrl, primaryKey, finalPrompt, imageBytesList)
        } catch (primaryError: Throwable) {
            val fallback = currentFallbackConfig(primary, primaryModel) ?: throw primaryError
            dispatch(fallback.provider, fallback.model, fallback.baseUrl, fallback.apiKey, finalPrompt, imageBytesList)
        }
    }

    private suspend fun addingFallbackServingUnits(
        analysis: FoodAnalysis,
        imageBytes: ByteArray?,
        description: String?
    ): FoodAnalysis {
        if (analysis.servingUnitOptions.isNotEmpty()) return analysis
        val options = runCatching {
            inferServingUnitOptions(
                name = analysis.name,
                servingSizeGrams = analysis.servingSizeGrams,
                imageBytes = imageBytes,
                description = description
            )
        }.getOrDefault(emptyList())
        if (options.isEmpty()) return analysis
        val selected = options.first()
        return analysis.copy(
            servingUnitOptions = options,
            selectedServingUnit = selected.unit,
            selectedServingQuantity = selected.quantityFor(analysis.servingSizeGrams)
        )
    }

    private suspend fun addingFallbackServingUnits(
        analysis: NutritionLabelAnalysis,
        imageBytes: ByteArray
    ): NutritionLabelAnalysis {
        if (analysis.servingUnitOptions.isNotEmpty()) return analysis
        val servingSizeGrams = analysis.servingSizeGrams ?: return analysis
        val options = runCatching {
            inferServingUnitOptions(
                name = analysis.name,
                servingSizeGrams = servingSizeGrams,
                imageBytes = imageBytes,
                description = null
            )
        }.getOrDefault(emptyList())
        if (options.isEmpty()) return analysis
        return analysis.copy(servingUnitOptions = options)
    }

    private suspend fun inferServingUnitOptions(
        name: String,
        servingSizeGrams: Double,
        imageBytes: ByteArray?,
        description: String?
    ): List<com.apoorvdarshan.calorietracker.models.ServingUnitOption> {
        val context = description?.trim()?.takeIf { it.isNotEmpty() }
        val contextLine = context?.let { "\nUser context: $it" }.orEmpty()
        val prompt = """
            The previous food analysis returned grams only. Infer non-gram serving unit options for the same food and amount.

            Food: $name
            Total grams for the analyzed amount: ${String.format(java.util.Locale.US, "%.1f", servingSizeGrams)}$contextLine

            Return ONLY JSON:
            {"unit_options":[{"unit":"slice","quantity":8.0,"grams_per_unit":45.0}]}

            Rules:
            - Replace the sample numbers with the actual best estimate. Do not copy 8 or 45 unless they fit the food.
            - If the image shows countable portions, count visible pieces/slices. For pizza, cake, pie, bread, cookies, fruit pieces, nuggets, or sweets, use slice or piece.
            - For liquids or pourable foods like milk, juice, soup, smoothies, dal, sauces, or yogurt, use ml when the volume is clearer than a count.
            - For spooned foods like peanut butter, honey, oil, chutney, or ghee, use tbsp or tsp.
            - For packaged foods/drinks, use can, packet, bar, scoop, or bowl only when that unit is visible or strongly implied.
            - grams_per_unit is grams for one unit. For countable units, use total grams / visible quantity. For ml, use grams per ml.
            - Return [] only if no non-gram unit is apparent.

            Good outputs:
            {"unit_options":[{"unit":"slice","quantity":8.0,"grams_per_unit":45.0}]}
            {"unit_options":[{"unit":"ml","quantity":250.0,"grams_per_unit":1.03},{"unit":"cup","quantity":1.0,"grams_per_unit":250.0}]}
            {"unit_options":[{"unit":"tbsp","quantity":2.0,"grams_per_unit":16.0}]}
            {"unit_options":[{"unit":"can","quantity":1.0,"grams_per_unit":330.0}]}
            {"unit_options":[{"unit":"piece","quantity":5.0,"grams_per_unit":18.0}]}
        """.trimIndent()
        return FoodJsonParser.parseServingUnitOptions(callAi(prompt, imageBytes), servingSizeGrams)
    }

    private suspend fun dispatch(
        provider: AIProvider,
        model: String,
        baseUrl: String,
        apiKey: String?,
        prompt: String,
        imageBytesList: List<ByteArray>
    ): String {
        if (baseUrl.isEmpty()) throw AiError.InvalidUrl(baseUrl)
        if (provider.requiresApiKey && apiKey.isNullOrEmpty()) throw AiError.NoApiKey
        return when (provider.apiFormat) {
            AIProvider.ApiFormat.GEMINI ->
                GeminiClient.analyze(okHttp, baseUrl, model, apiKey!!, prompt, imageBytesList)
            AIProvider.ApiFormat.ANTHROPIC ->
                AnthropicClient.analyze(okHttp, baseUrl, model, apiKey!!, prompt, imageBytesList)
            AIProvider.ApiFormat.OPENAI_COMPATIBLE ->
                OpenAICompatibleClient.analyze(okHttp, baseUrl, model, apiKey, prompt, imageBytesList, provider)
        }
    }

    private suspend fun currentFallbackConfig(
        primary: AIProvider,
        primaryModel: String
    ): FallbackConfig? {
        if (!prefs.fallbackEnabled.first()) return null
        val provider = prefs.selectedFallbackProvider.first()
        val model = provider.supportedModelOrDefault(prefs.selectedFallbackModel.first())
        // Fallback identical to primary would be a pointless retry of the same call.
        if (provider == primary && model == primaryModel) return null
        val key = keyStore.apiKey(provider)
        if (provider.requiresApiKey && key.isNullOrEmpty()) return null
        val baseUrl = prefs.customBaseUrl(provider).first()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl
        if (baseUrl.isEmpty()) return null
        return FallbackConfig(provider, model, baseUrl, key)
    }

    private data class FallbackConfig(
        val provider: AIProvider,
        val model: String,
        val baseUrl: String,
        val apiKey: String?
    )

    companion object {
        internal val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }
}
