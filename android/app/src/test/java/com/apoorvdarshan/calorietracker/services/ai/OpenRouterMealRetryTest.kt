package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.OpenRouterReasoningEffort
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterMealRetryTest {
    @Test fun knownTextOnlyModelRejectsPhotosBeforeMakingARequest() = runBlocking {
        MockWebServer().use { server ->
            val model = "nvidia/nemotron-3-ultra-550b-a55b:free"
            val result = runCatching {
                OpenAICompatibleClient.analyze(
                    OkHttpClient(), server.url("/api/v1").toString().trimEnd('/'), model, null,
                    "Estimate this meal", listOf(byteArrayOf(1)), AIProvider.OPENROUTER, 1024
                )
            }
            assertEquals(AiErrorKind.MODEL_TEXT_ONLY, (result.exceptionOrNull() as? AiError)?.kind)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun truncatedFreeRouteRetriesAtTheSameBudgetWithoutReasoning() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":""}}]}"""))
            val result = """{"name":"Oats","calories":100,"protein":3,"carbs":18,"fat":2}"""
            val response = JSONObject().put("choices", org.json.JSONArray().put(
                JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", result))
            ))
            server.enqueue(MockResponse().setBody(response.toString()))
            val actual = OpenAICompatibleClient.analyze(
                OkHttpClient(), server.url("/api/v1").toString().trimEnd('/'), "openrouter/free", null,
                "Estimate oatmeal; return JSON", emptyList(), AIProvider.OPENROUTER, 1024
            )
            assertEquals(result, actual)
            for (attempt in 1..2) {
                val request = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals("openrouter/free", request.getString("model"))
                assertEquals(1024, request.getInt("max_tokens"))
                assertEquals("none", request.getJSONObject("reasoning").getString("effort"))
                if (attempt == 2) assertTrue(request.toString().contains("complete answer"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun explicitEffortAndUnknownModelsRetainTheirExistingFirstRequestBehavior() {
        val model = "nvidia/nemotron-3-ultra-550b-a55b:free"
        assertEquals("high", OpenRouterReasoningEffort.HIGH.requestOptions(false, true, model)?.get("effort"))
        assertEquals("none", OpenRouterReasoningEffort.HIGH.requestOptions(true, true, model)?.get("effort"))
        assertEquals(mapOf("exclude" to true), OpenRouterReasoningEffort.AUTO.requestOptions(false, true, "example/mandatory"))
    }
}
