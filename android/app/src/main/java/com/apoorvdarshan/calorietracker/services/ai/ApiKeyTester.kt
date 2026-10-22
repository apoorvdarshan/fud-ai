package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.AIProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks a stored API key with the provider it belongs to.
 *
 * The check sends one minimal, cheap request (a single word prompt with the smallest
 * possible token budget) to the provider, so the user learns whether the key, the model
 * and the endpoint actually work together. It costs one request on the provider's
 * account, which the UI states before the user triggers it.
 *
 * The result distinguishes the failure kinds the user can act on: a rejected key, an
 * unsupported model or endpoint, a rate limit, a missing quota, and connectivity
 * problems. Nothing about the key itself is logged.
 */
class ApiKeyTester(
    private val okHttp: okhttp3.OkHttpClient = FoodAnalysisService.defaultClient,
) {

    /**
     * Tests [apiKey] against [provider] and [model].
     *
     * @return the failure kind, or null when the provider accepted the request.
     */
    suspend fun test(
        provider: AIProvider,
        model: String,
        apiKey: String?,
        baseUrl: String
    ): AiErrorKind? = withContext(Dispatchers.IO) {
        if (provider.requiresApiKey && apiKey.isNullOrBlank()) return@withContext AiErrorKind.NO_KEY
        if (baseUrl.isBlank()) return@withContext AiErrorKind.INVALID_URL
        try {
            val response = when (provider.apiFormat) {
                AIProvider.ApiFormat.GEMINI ->
                    GeminiClient.analyze(
                        okHttp,
                        baseUrl,
                        model,
                        apiKey.orEmpty(),
                        TEST_PROMPT,
                        emptyList(),
                        generationConfig = GeminiClient.GenerationConfig(
                            maxOutputTokens = 8,
                            responseMimeType = null,
                            thinkingLevel = "minimal"
                        ),
                        retryDelays = longArrayOf()
                    )

                AIProvider.ApiFormat.ANTHROPIC ->
                    AnthropicClient.analyze(
                        okHttp,
                        baseUrl,
                        model,
                        apiKey.orEmpty(),
                        TEST_PROMPT,
                        emptyList(),
                        maxTokens = 8
                    )

                AIProvider.ApiFormat.OPENAI_COMPATIBLE ->
                    OpenAICompatibleClient.analyze(
                        okHttp,
                        baseUrl,
                        model,
                        apiKey,
                        TEST_PROMPT,
                        emptyList(),
                        provider,
                        maxTokens = 8
                    )

                AIProvider.ApiFormat.LOCAL -> throw AiError.Failure(AiErrorKind.LOCAL_UNAVAILABLE)
            }
            // A provider that answers with an empty body did not really accept the key.
            if (response.isBlank()) AiErrorKind.INVALID_RESPONSE else null
        } catch (e: AiError) {
            e.kind
        } catch (e: Throwable) {
            AiErrorKind.fromNetwork(e)
        }
    }

    private companion object {
        /** The cheapest possible request: one word, no image, no JSON. */
        const val TEST_PROMPT = "ping"
    }
}
