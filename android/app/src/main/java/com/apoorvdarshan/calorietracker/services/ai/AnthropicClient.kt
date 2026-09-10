package com.apoorvdarshan.calorietracker.services.ai

import kotlinx.serialization.json.Json
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

/**
 * Anthropic Messages format:
 *   POST <base>/messages
 *   Headers: x-api-key: <apiKey>, anthropic-version: 2023-06-01
 *   Body:    {model, max_tokens, system?, messages: [{role, content: [...]}]}
 */
object AnthropicClient {

    private const val API_VERSION = "2023-06-01"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun analyze(
        client: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String,
        prompt: String,
        imageBytesList: List<ByteArray>,
        maxTokens: Int
    ): String {
        val url = "$baseUrl/messages"

        suspend fun request(requestPrompt: String): AnthropicTextResponse {
            val content = JSONArray().apply {
                imageBytesList.forEach {
                    put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", "image/jpeg")
                                    .put("data", Base64.getEncoder().encodeToString(it))
                            )
                    )
                }
                put(JSONObject().put("type", "text").put("text", requestPrompt))
            }

            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

            val bodyStr = RetryPolicy.execute {
                client.newCall(
                    Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", API_VERSION)
                        .post(body.toString().toRequestBody(jsonMedia))
                        .build()
                )
            }
            return AnthropicResponseParser.parse(bodyStr)
        }

        var response = request(prompt)
        if (response.wasTruncated) {
            response = request(compactRetryPrompt(prompt, maxTokens))
            if (response.wasTruncated) {
                throw AiError.Failure(AiErrorKind.TRUNCATED)
            }
        }
        return response.text ?: throw AiError.InvalidResponse
    }

    suspend fun chat(
        client: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        history: List<Pair<String, String>>, // (role: "user"|"assistant", content)
        userMessage: String,
        maxTokens: Int
    ): String {
        val url = "$baseUrl/messages"

        val messages = JSONArray()
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", systemPrompt)
            .put("messages", messages)

        val bodyStr = RetryPolicy.execute {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", API_VERSION)
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build()
            )
        }
        return AnthropicResponseParser.parse(bodyStr).text ?: throw AiError.InvalidResponse
    }

    private fun compactRetryPrompt(prompt: String, maxTokens: Int): String =
        "$prompt\n\nIMPORTANT: The previous response was truncated. Return only the requested compact JSON object, with no reasoning, explanation, or markdown. Keep the complete response under $maxTokens tokens."
}

internal data class AnthropicTextResponse(
    val text: String?,
    val stopReason: String?
) {
    val wasTruncated: Boolean get() = stopReason == "max_tokens"
}

internal object AnthropicResponseParser {
    fun parse(body: String): AnthropicTextResponse {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw AiError.InvalidResponse
        val content = runCatching { json["content"]?.jsonArray }.getOrNull()
            ?: throw AiError.InvalidResponse
        val text = content.mapNotNull { element ->
            val block = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            if (block["type"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
            block["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.joinToString("\n").takeIf { it.isNotEmpty() }
        return AnthropicTextResponse(
            text = text,
            stopReason = json["stop_reason"]?.jsonPrimitive?.contentOrNull
        )
    }
}
