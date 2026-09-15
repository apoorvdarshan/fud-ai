package com.apoorvdarshan.calorietracker.services.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
 *   Body:   {systemInstruction?, contents: [{role?, parts: [...]}], generationConfig?}
 */
object GeminiClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * `generationConfig` for a single-shot food-analysis request. Without it Gemini 3 models
     * default to a high thinking level and free-form text, which on Flash-Lite means long,
     * open-ended generations that keep the analyzing overlay up until the read timeout.
     */
    data class GenerationConfig(
        val maxOutputTokens: Int?,
        val responseMimeType: String?,
        val thinkingLevel: String?
    ) {
        fun toJson(): JsonObject? {
            val json = buildJsonObject {
                maxOutputTokens?.takeIf { it > 0 }?.let { put("maxOutputTokens", it) }
                responseMimeType?.let { put("responseMimeType", it) }
                thinkingLevel?.let { level -> putJsonObject("thinkingConfig") { put("thinkingLevel", level) } }
            }
            return json.takeIf { it.isNotEmpty() }
        }

        companion object {
            fun forFoodAnalysis(model: String, maxOutputTokens: Int, jsonResponse: Boolean): GenerationConfig =
                GenerationConfig(
                    maxOutputTokens = maxOutputTokens,
                    responseMimeType = if (jsonResponse) "application/json" else null,
                    thinkingLevel = thinkingLevel(model)
                )
        }
    }

    private val geminiModelPattern = Regex("""^gemini-(\d+)(?:\.(\d+))?-(.+)$""")

    /**
     * `thinkingConfig.thinkingLevel` for Gemini 3-family models. `minimal` is the fastest level
     * but is only accepted by Flash-Lite and the 3 / 3.5 / 3.6 Flash models; 3.7+ Flash and the
     * Pro models reject it with a 400, so they get `low`. Pre-3 models (thinkingBudget only) and
     * unrecognized ids get null so nothing is sent.
     */
    fun thinkingLevel(model: String): String? {
        val id = model.trim().lowercase().substringAfterLast('/')
        val match = geminiModelPattern.find(id) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        val variant = match.groupValues[3]
        if (major < 3) return null
        return when {
            variant.startsWith("flash-lite") -> "minimal"
            variant.startsWith("flash") && (major > 3 || minor >= 7) -> "low"
            variant.startsWith("flash") -> "minimal"
            else -> "low"
        }
    }

    suspend fun analyze(
        client: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String,
        prompt: String,
        imageBytesList: List<ByteArray>,
        generationConfig: GenerationConfig? = null,
        retryDelays: LongArray = RetryPolicy.defaultDelays
    ): String {
        val url = "$baseUrl/models/$model:generateContent"

        suspend fun request(requestPrompt: String): GeminiTextResponse {
            val bodyStr = withContext(Dispatchers.IO) {
                RetryPolicy.execute(retryDelays) {
                    val body = requestBody(requestPrompt, imageBytesList, generationConfig)
                    client.newCall(
                        Request.Builder()
                            .url(url)
                            .addHeader("Content-Type", "application/json")
                            .addHeader("X-goog-api-key", apiKey)
                            .post(body.toString().toRequestBody(jsonMedia))
                            .build()
                    )
                }
            }
            return parseResponse(bodyStr)
        }

        var response = request(prompt)
        if (response.wasTruncated) {
            response = request(
                compactRetryPrompt(
                    prompt,
                    generationConfig?.maxOutputTokens,
                    jsonResponse = generationConfig?.responseMimeType == "application/json"
                )
            )
            if (response.wasTruncated) {
                throw AiError.Failure(AiErrorKind.TRUNCATED)
            }
        }
        return response.text ?: throw AiError.InvalidResponse
    }

    internal fun requestBody(
        prompt: String,
        imageBytesList: List<ByteArray>,
        generationConfig: GenerationConfig?
    ): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                putJsonArray("parts") {
                    imageBytesList.forEach { bytes ->
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/jpeg")
                                put("data", Base64.getEncoder().encodeToString(bytes))
                            }
                        }
                    }
                    addJsonObject { put("text", prompt) }
                }
            }
        }
        generationConfig?.toJson()?.let { put("generationConfig", it) }
    }

    private fun compactRetryPrompt(prompt: String, maxTokens: Int?, jsonResponse: Boolean): String {
        val budget = maxTokens?.takeIf { it > 0 }?.let { " Keep the complete response under $it tokens." }.orEmpty()
        val shape = if (jsonResponse) {
            "Return only the requested compact JSON object, with no reasoning, explanation, or markdown."
        } else {
            "Return only the requested concise plain-English answer, with no reasoning, explanation, JSON, or markdown."
        }
        return "$prompt\n\nIMPORTANT: The previous response was truncated. $shape$budget"
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

        return parseResponse(bodyStr).text ?: throw AiError.InvalidResponse
    }

    data class GeminiTextResponse(val text: String?, val finishReason: String?) {
        val wasTruncated: Boolean get() = finishReason == "MAX_TOKENS"
    }

    /**
     * Concatenates every text part of the first candidate. Thinking models may return several
     * parts (and `thought: true` summaries) before the answer, so reading only `parts[0]` turned
     * perfectly good responses into InvalidResponse.
     */
    internal fun parseResponse(body: String): GeminiTextResponse {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw AiError.InvalidResponse
        val first = runCatching { json["candidates"]?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull()
            ?: throw AiError.InvalidResponse
        val finishReason = runCatching { first["finishReason"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()?.takeIf { it.isNotEmpty() }
        val parts = runCatching { first["content"]?.jsonObject?.get("parts")?.jsonArray }.getOrNull().orEmpty()
        val combined = parts.mapNotNull { part ->
            val obj = runCatching { part.jsonObject }.getOrNull() ?: return@mapNotNull null
            val isThought = runCatching { obj["thought"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
            if (isThought) return@mapNotNull null
            runCatching { obj["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotEmpty() }
        }.joinToString("").trim()
        if (combined.isEmpty() && finishReason != "MAX_TOKENS") throw AiError.InvalidResponse
        return GeminiTextResponse(text = combined.takeIf { it.isNotEmpty() }, finishReason = finishReason)
    }
}
