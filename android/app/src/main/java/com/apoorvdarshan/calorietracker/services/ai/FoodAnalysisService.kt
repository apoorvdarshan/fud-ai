package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.AIProvider
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

    suspend fun analyzeText(description: String): FoodAnalysis {
        val prompt = """
            Estimate the nutritional content for: $description
            Parse any quantities, brands, and multiple items from the text. If a brand is mentioned, use that brand's known nutritional data. If multiple items are described, sum up the total nutrition.
            Respond ONLY with JSON:
            {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"emoji":"<single specific food emoji>","sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0}
            Calories/protein/carbs/fat are integers. serving_size_grams is the estimated total weight in grams. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
            For "emoji" pick the single most specific food emoji that depicts this dish — e.g. 🥚 for eggs, 🍕 for pizza, 🍎 for an apple, 🥗 for a salad, 🍔 for a burger, 🍜 for ramen, 🍰 for cake, 🥑 for avocado, ☕ for coffee, 🍣 for sushi. Only fall back to 🍽️ when the food truly cannot be represented by any specific emoji. Use null for any nutrient you cannot estimate.
        """.trimIndent()
        return FoodJsonParser.parseFood(callAi(prompt, null))
    }

    suspend fun analyzeAuto(imageBytes: ByteArray): FoodAnalysis {
        val prompt = """
            Analyze this image. It could be either a photo of food OR a nutrition facts label.

            If it's a food photo: identify the food and estimate nutritional content for the serving shown.
            If it's a nutrition label: read the values and calculate for one serving size as listed on the label.

            Respond ONLY with JSON:
            {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0}
            Calories/protein/carbs/fat are integers. serving_size_grams is the estimated weight in grams of the serving. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
            Use null for any nutrient you cannot estimate.
        """.trimIndent()
        return FoodJsonParser.parseFood(callAi(prompt, imageBytes))
    }

    suspend fun analyzeFood(imageBytes: ByteArray, description: String? = null): FoodAnalysis {
        var prompt = """
            Analyze this food image. Identify the food and estimate its nutritional content for the serving visible.
            Respond ONLY with JSON:
            {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0}
            Use null for any nutrient you cannot estimate.
        """.trimIndent()
        if (!description.isNullOrBlank()) {
            prompt += "\n\nAdditional context from the user: $description"
        }
        return FoodJsonParser.parseFood(callAi(prompt, imageBytes))
    }

    suspend fun analyzeNutritionLabel(imageBytes: ByteArray, servingGrams: Double): FoodAnalysis {
        val prompt = """
            Read this nutrition facts label and extract per-100g values. If the label only shows per-serving, normalize using the serving size listed on the label.
            Respond ONLY with JSON:
            {"name":"...","calories_per_100g":0.0,"protein_per_100g":0.0,"carbs_per_100g":0.0,"fat_per_100g":0.0,"serving_size_grams":0.0,"sugar_per_100g":0.0,"added_sugar_per_100g":0.0,"fiber_per_100g":0.0,"saturated_fat_per_100g":0.0,"monounsaturated_fat_per_100g":0.0,"polyunsaturated_fat_per_100g":0.0,"cholesterol_per_100g":0.0,"sodium_per_100g":0.0,"potassium_per_100g":0.0}
            Use null for any field the label does not list.
        """.trimIndent()
        return FoodJsonParser.parseLabel(callAi(prompt, imageBytes)).scaled(servingGrams)
    }

    // -- Internal dispatch ------------------------------------------------

    private suspend fun callAi(prompt: String, imageBytes: ByteArray?): String {
        val context = prefs.userContext.first()
        val finalPrompt = if (context.isNotBlank()) "User context (apply to every analysis): $context\n\n$prompt" else prompt

        val primary = prefs.selectedAIProvider.first()
        val primaryModel = prefs.selectedAIModel.first() ?: primary.defaultModel
        val primaryBaseUrl = prefs.customBaseUrl(primary).first()?.takeIf { it.isNotEmpty() } ?: primary.baseUrl
        val primaryKey = keyStore.apiKey(primary)
        if (primary.requiresApiKey && primaryKey.isNullOrEmpty()) throw AiError.NoApiKey

        return try {
            dispatch(primary, primaryModel, primaryBaseUrl, primaryKey, finalPrompt, imageBytes)
        } catch (primaryError: Throwable) {
            val fallback = currentFallbackConfig(primary, primaryModel) ?: throw primaryError
            dispatch(fallback.provider, fallback.model, fallback.baseUrl, fallback.apiKey, finalPrompt, imageBytes)
        }
    }

    private suspend fun dispatch(
        provider: AIProvider,
        model: String,
        baseUrl: String,
        apiKey: String?,
        prompt: String,
        imageBytes: ByteArray?
    ): String {
        if (baseUrl.isEmpty()) throw AiError.InvalidUrl(baseUrl)
        if (provider.requiresApiKey && apiKey.isNullOrEmpty()) throw AiError.NoApiKey
        return when (provider.apiFormat) {
            AIProvider.ApiFormat.GEMINI ->
                GeminiClient.analyze(okHttp, baseUrl, model, apiKey!!, prompt, imageBytes)
            AIProvider.ApiFormat.ANTHROPIC ->
                AnthropicClient.analyze(okHttp, baseUrl, model, apiKey!!, prompt, imageBytes)
            AIProvider.ApiFormat.OPENAI_COMPATIBLE ->
                OpenAICompatibleClient.analyze(okHttp, baseUrl, model, apiKey, prompt, imageBytes, provider)
        }
    }

    private suspend fun currentFallbackConfig(
        primary: AIProvider,
        primaryModel: String
    ): FallbackConfig? {
        if (!prefs.fallbackEnabled.first()) return null
        val provider = prefs.selectedFallbackProvider.first()
        val model = prefs.selectedFallbackModel.first() ?: provider.defaultModel
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
