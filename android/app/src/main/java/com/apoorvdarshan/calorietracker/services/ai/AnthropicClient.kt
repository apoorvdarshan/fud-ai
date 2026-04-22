package com.apoorvdarshan.calorietracker.services.ai

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
        imageBytes: ByteArray?
    ): String {
        val url = "$baseUrl/messages"

        val content = JSONArray().apply {
            imageBytes?.let {
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
            put(JSONObject().put("type", "text").put("text", prompt))
        }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
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
        return parseText(bodyStr)
    }

    suspend fun chat(
        client: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        history: List<Pair<String, String>>, // (role: "user"|"assistant", content)
        userMessage: String
    ): String {
        val url = "$baseUrl/messages"

        val messages = JSONArray()
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
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
        return parseText(bodyStr)
    }

    private fun parseText(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw AiError.InvalidResponse
        val contentArr = json.optJSONArray("content") ?: throw AiError.InvalidResponse
        val text = contentArr.optJSONObject(0)?.optString("text").orEmpty()
        if (text.isEmpty()) throw AiError.InvalidResponse
        return text
    }
}
