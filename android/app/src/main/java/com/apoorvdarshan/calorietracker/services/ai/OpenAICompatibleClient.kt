package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.OpenRouterReasoningEffort
import com.apoorvdarshan.calorietracker.models.AIProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.Locale

/**
 * OpenAI-compatible format — used by OpenAI, xAI Grok, OpenRouter, Together AI,
 * Groq, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama, and the
 * Custom (OpenAI-compatible) provider.
 *
 *   POST <base>/chat/completions
 *   Header: Authorization: Bearer <apiKey>
 *   Body:   {model, messages: [{role, content: [{type, ...}]}], max_tokens/max_completion_tokens}
 */
object OpenAICompatibleClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun analyze(
        client: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String?,
        prompt: String,
        imageBytesList: List<ByteArray>,
        provider: AIProvider,
        maxTokens: Int,
        reasoningEffort: OpenRouterReasoningEffort = OpenRouterReasoningEffort.AUTO
    ): String {
        val url = "$baseUrl/chat/completions"

        suspend fun request(requestPrompt: String, compactRetry: Boolean): OpenAITextResponse {
            val content = JSONArray().apply {
                imageBytesList.forEach {
                    put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(it)}")
                            )
                    )
                }
                put(JSONObject().put("type", "text").put("text", requestPrompt))
            }

            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                .put(tokenLimitParameter(provider, model), maxTokens)
            if (provider == AIProvider.OPENROUTER) {
                body.put(
                    "reasoning",
                    JSONObject(reasoningEffort.requestOptions(compactRetry, exclude = true).orEmpty())
                )
            }

            val builder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMedia))
            if (!apiKey.isNullOrEmpty()) builder.addHeader("Authorization", "Bearer $apiKey")
            if (provider == AIProvider.OPENROUTER) {
                builder.addHeader("HTTP-Referer", "https://github.com/apoorvdarshan/fud-ai")
                builder.addHeader("X-Title", "Fud AI")
            }

            val bodyStr = RetryPolicy.execute { client.newCall(builder.build()) }
            return OpenAIResponseParser.parse(bodyStr)
        }

        var response = request(prompt, compactRetry = false)
        if (response.needsCompactRetry) {
            response = request(compactRetryPrompt(prompt, maxTokens), compactRetry = true)
            if (response.wasTruncated) {
                throw AiError.Api("The AI response was truncated twice. Try a shorter description or another model.")
            }
        }
        return response.text ?: throw AiError.InvalidResponse
    }

    private fun compactRetryPrompt(prompt: String, maxTokens: Int): String =
        "$prompt\n\nIMPORTANT: The previous response did not contain a complete answer. Return only the requested compact JSON object, with no reasoning, explanation, or markdown. Keep the complete response under $maxTokens tokens."

    suspend fun chat(
        client: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        history: List<Pair<String, String>>, // (role: "user"|"assistant", content)
        userMessage: String,
        provider: AIProvider,
        maxTokens: Int
    ): String {
        val url = "$baseUrl/chat/completions"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put(tokenLimitParameter(provider, model), maxTokens)

        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
        if (!apiKey.isNullOrEmpty()) builder.addHeader("Authorization", "Bearer $apiKey")
        if (provider == AIProvider.OPENROUTER) {
            builder.addHeader("HTTP-Referer", "https://github.com/apoorvdarshan/fud-ai")
            builder.addHeader("X-Title", "Fud AI")
        }

        val response = OpenAIResponseParser.parse(RetryPolicy.execute { client.newCall(builder.build()) })
        if (response.wasTruncated) {
            throw AiError.Api("The AI response was truncated. Try a shorter question or a different model.")
        }
        return response.text ?: throw AiError.InvalidResponse
    }

    fun tokenLimitParameter(provider: AIProvider, model: String): String {
        return if (
            provider == AIProvider.OPENAI ||
            (provider == AIProvider.CUSTOM_OPENAI && usesOpenAICompletionTokenLimit(model))
        ) {
            "max_completion_tokens"
        } else {
            "max_tokens"
        }
    }

    private fun usesOpenAICompletionTokenLimit(model: String): Boolean {
        val normalized = model
            .trim()
            .lowercase(Locale.US)
            .substringAfterLast("/")

        return normalized.startsWith("gpt-5") ||
            normalized.startsWith("o1") ||
            normalized.startsWith("o3") ||
            normalized.startsWith("o4")
    }
}

internal data class OpenAITextResponse(
    val text: String?,
    val finishReason: String?,
    val hasReasoning: Boolean
) {
    val wasTruncated: Boolean get() = finishReason == "length"
    val needsCompactRetry: Boolean get() = wasTruncated || (text == null && hasReasoning)
}

internal object OpenAIResponseParser {
    fun parse(body: String): OpenAITextResponse {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw AiError.InvalidResponse
        val errorMessage = runCatching {
            json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val choice = runCatching { json["choices"]?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull()
        val finishReason = runCatching { choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull }
            .getOrNull()?.takeIf { it.isNotBlank() }
        if (finishReason == "error" || (choice == null && errorMessage != null)) {
            throw AiError.Api(errorMessage ?: "The AI provider returned an error.")
        }
        val message = runCatching { choice?.get("message")?.jsonObject }.getOrNull()
            ?: throw AiError.InvalidResponse
        val text = when (val content = message["content"]) {
            is JsonPrimitive -> content.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            is JsonArray -> content.mapNotNull { element ->
                runCatching { element.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            }.joinToString("\n").takeIf { it.isNotEmpty() }
            else -> null
        }
        fun nonEmptyString(key: String): Boolean = runCatching {
            message[key]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        }.getOrDefault(false)
        val hasReasoning = nonEmptyString("reasoning") ||
            nonEmptyString("reasoning_content") ||
            runCatching { message["reasoning_details"]?.jsonArray?.isNotEmpty() == true }.getOrDefault(false)
        return OpenAITextResponse(text, finishReason, hasReasoning)
    }
}
