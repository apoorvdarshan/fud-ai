package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.AIProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * OpenAI-compatible format — used by OpenAI, xAI Grok, OpenRouter, Together AI,
 * Groq, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama, and the
 * Custom (OpenAI-compatible) provider.
 *
 *   POST <base>/chat/completions
 *   Header: Authorization: Bearer <apiKey>
 *   Body:   {model, messages: [{role, content: [{type, ...}]}], max_tokens}
 */
object OpenAICompatibleClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun analyze(
        client: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String?,
        prompt: String,
        imageBytes: ByteArray?,
        provider: AIProvider
    ): String {
        val url = "$baseUrl/chat/completions"

        val content = JSONArray().apply {
            imageBytes?.let {
                put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(it)}")
                        )
                )
            }
            put(JSONObject().put("type", "text").put("text", prompt))
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", 1024)

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
        return parseText(bodyStr)
    }

    suspend fun chat(
        client: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        history: List<Pair<String, String>>, // (role: "user"|"assistant", content)
        userMessage: String,
        provider: AIProvider
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
            .put("max_tokens", 1024)

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
        return parseText(bodyStr)
    }

    private fun parseText(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw AiError.InvalidResponse
        val choices = json.optJSONArray("choices") ?: throw AiError.InvalidResponse
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: throw AiError.InvalidResponse
        val text = message.optString("content").orEmpty()
        if (text.isEmpty()) throw AiError.InvalidResponse
        return text
    }
}
