package com.apoorvdarshan.calorietracker.services.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Gemini format:
 *   POST <base>/models/<model>:generateContent
 *   Header: X-goog-api-key: <apiKey>
 *   Body:   {systemInstruction?, contents: [{role?, parts: [...]}]}
 */
object GeminiClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun analyze(
        client: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String,
        prompt: String,
        imageBytes: ByteArray?
    ): String {
        val url = "$baseUrl/models/$model:generateContent"

        val parts = JSONArray().apply {
            imageBytes?.let {
                put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", Base64.getEncoder().encodeToString(it))
                    )
                )
            }
            put(JSONObject().put("text", prompt))
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        }

        val requestBody = body.toString().toRequestBody(jsonMedia)
        val bodyStr = RetryPolicy.execute {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-goog-api-key", apiKey)
                    .post(requestBody)
                    .build()
            )
        }

        return parseText(bodyStr)
    }

    /**
     * Multi-turn variant for the coach chat. Uses systemInstruction + contents[{role: user|model, parts: [{text}]}].
     */
    suspend fun chat(
        client: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        history: List<Pair<String, String>>, // (role, content) role in {"user","model"}
        userMessage: String
    ): String {
        val url = "$baseUrl/models/$model:generateContent"

        val contents = JSONArray()
        for ((role, content) in history) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", content)))
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
        )

        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            put("contents", contents)
        }

        val requestBody = body.toString().toRequestBody(jsonMedia)
        val bodyStr = RetryPolicy.execute {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-goog-api-key", apiKey)
                    .post(requestBody)
                    .build()
            )
        }

        return parseText(bodyStr)
    }

    private fun parseText(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw AiError.InvalidResponse
        val candidates = json.optJSONArray("candidates") ?: throw AiError.InvalidResponse
        val first = candidates.optJSONObject(0) ?: throw AiError.InvalidResponse
        val content = first.optJSONObject("content") ?: throw AiError.InvalidResponse
        val parts = content.optJSONArray("parts") ?: throw AiError.InvalidResponse
        val text = parts.optJSONObject(0)?.optString("text").orEmpty()
        if (text.isEmpty()) throw AiError.InvalidResponse
        return text
    }
}
