package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.billing.HostedAIConstants
import com.apoorvdarshan.calorietracker.billing.HostedPlan
import com.apoorvdarshan.calorietracker.billing.RevenueCatManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HostedAIService(
    private val client: OkHttpClient = defaultClient,
    private val revenueCat: RevenueCatManager
) {
    suspend fun generate(
        prompt: String,
        imageBytesList: List<ByteArray>,
        systemInstruction: String?
    ): String = withContext(Dispatchers.IO) {
        val images = buildJsonObject {
            putJsonArray("images") {
                imageBytesList.take(HostedAIConstants.MAX_HOSTED_IMAGES).forEach { bytes ->
                    add(kotlinx.serialization.json.JsonPrimitive(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
                }
            }
            put("prompt", prompt)
            systemInstruction?.takeIf { it.isNotBlank() }?.let { put("systemInstruction", it) }
        }
        val data = post("generate", images.toString())
        JSONObject(data).getString("text")
    }

    suspend fun geminiGenerate(requestBody: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val wrapper = JSONObject().put("requestBody", requestBody)
        JSONObject(post("gemini", wrapper.toString()))
    }

    suspend fun transcribe(audioBytes: ByteArray, mimeType: String, language: String?): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("audio", android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP))
                put("mimeType", mimeType)
                language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
            }
            val data = post("transcribe", body.toString())
            JSONObject(data).getString("text")
        }

    private suspend fun post(path: String, jsonBody: String): String {
        val plan = revenueCat.activePlan.value
        val userId = revenueCat.appUserId()
        val request = Request.Builder()
            .url("${HostedAIConstants.HOSTED_AI_BASE_URL}/$path")
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer ${HostedAIConstants.HOSTED_AI_APP_SECRET}")
            .header("X-Fud-User-Id", userId)
            .header("X-Fud-Plan", plan.id)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("error") }.getOrNull()
                throw AiError.Api(detail ?: "Hosted AI failed (${response.code})")
            }
            return body
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
