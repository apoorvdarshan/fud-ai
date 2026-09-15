package com.apoorvdarshan.calorietracker.services.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiClientTest {
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private val liteConfig = GeminiClient.GenerationConfig.forFoodAnalysis("gemini-3.5-flash-lite", 1024, jsonResponse = true)

    @Test
    fun minimalThinkingOnlyGoesToModelsThatAcceptIt() {
        listOf("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.6-flash", "gemini-3-flash", "gemini-3-flash-preview")
            .forEach { assertEquals(it, "minimal", GeminiClient.thinkingLevel(it)) }
        listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.1-pro-preview", "gemini-3-pro")
            .forEach { assertEquals(it, "low", GeminiClient.thinkingLevel(it)) }
        assertEquals("minimal", GeminiClient.thinkingLevel("models/gemini-3.5-flash-lite"))
        assertEquals("minimal", GeminiClient.thinkingLevel(" Gemini-3.5-Flash-Lite "))
    }

    @Test
    fun preGemini3AndUnknownModelsSendNoThinkingLevel() {
        listOf("gemini-2.5-flash", "gemini-2.0-flash-lite", "gemma-3-27b-it", "custom-proxy-model", "")
            .forEach { assertNull(it, GeminiClient.thinkingLevel(it)) }
    }

    @Test
    fun foodAnalysisGenerationConfigCapsTokensAndForcesJson() {
        val json = liteConfig.toJson()!!

        assertEquals(1024, json.int("maxOutputTokens"))
        assertEquals("application/json", json.string("responseMimeType"))
        assertEquals("minimal", json.obj("thinkingConfig").string("thinkingLevel"))
    }

    @Test
    fun proseRequestsSkipJsonMimeTypeAndFlashUsesLowThinking() {
        val json = GeminiClient.GenerationConfig.forFoodAnalysis("gemini-3.8-flash", maxOutputTokens = 512, jsonResponse = false).toJson()!!

        assertEquals(512, json.int("maxOutputTokens"))
        assertFalse("responseMimeType" in json)
        assertEquals("low", json.obj("thinkingConfig").string("thinkingLevel"))
    }

    @Test
    fun requestBodyOnlyIncludesGenerationConfigWhenConfigured() {
        val prompt = "Analyze this food"
        val plain = GeminiClient.requestBody(prompt, listOf(byteArrayOf(1, 2, 3)), generationConfig = null)
        assertFalse("generationConfig" in plain)
        val parts = plain.getValue("contents").jsonArray.first().jsonObject.getValue("parts").jsonArray
        assertEquals("image/jpeg", parts[0].jsonObject.obj("inlineData").string("mimeType"))
        assertEquals(prompt, parts[1].jsonObject.string("text"))

        val configured = GeminiClient.requestBody(prompt, emptyList(), liteConfig)
        val generationConfig = configured.obj("generationConfig")
        assertEquals("application/json", generationConfig.string("responseMimeType"))
        assertEquals("minimal", generationConfig.obj("thinkingConfig").string("thinkingLevel"))

        val empty = GeminiClient.GenerationConfig(maxOutputTokens = 0, responseMimeType = null, thinkingLevel = null)
        assertNull(empty.toJson())
        assertFalse("generationConfig" in GeminiClient.requestBody(prompt, emptyList(), empty))
    }

    @Test
    fun parserJoinsEveryTextPartAndSkipsThoughtParts() {
        val response = GeminiClient.parseResponse(
            """
            {"candidates":[{"content":{"parts":[
                {"thought":true,"text":"Let me look at the plate..."},
                {"text":"{\"name\":\"Oatmeal\","},
                {"text":"\"calories\":320}"}
            ],"role":"model"},"finishReason":"STOP"}]}
            """.trimIndent()
        )

        assertEquals("""{"name":"Oatmeal","calories":320}""", response.text)
        assertEquals("STOP", response.finishReason)
        assertFalse(response.wasTruncated)
    }

    @Test
    fun parserReportsTruncationInsteadOfInvalidResponse() {
        val truncated = GeminiClient.parseResponse(
            """{"candidates":[{"content":{"parts":[{"thought":true,"text":"hmm"}],"role":"model"},"finishReason":"MAX_TOKENS"}]}"""
        )
        assertNull(truncated.text)
        assertTrue(truncated.wasTruncated)

        val partial = GeminiClient.parseResponse(
            """{"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Oat"}]},"finishReason":"MAX_TOKENS"}]}"""
        )
        assertEquals("{\"name\":\"Oat", partial.text)
        assertTrue(partial.wasTruncated)
    }

    @Test
    fun parserRejectsEmptyOrMalformedCandidates() {
        listOf(
            "not json",
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            """{"candidates":[]}""",
            """{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}""",
            """{"candidates":[{"content":{"parts":[{"thought":true,"text":"only thoughts"}]},"finishReason":"STOP"}]}"""
        ).forEach { body ->
            try {
                GeminiClient.parseResponse(body)
                throw AssertionError("Expected InvalidResponse for $body")
            } catch (error: AiError) {
                assertEquals(AiErrorKind.INVALID_RESPONSE, error.kind)
            }
        }
    }

    @Test
    fun analyzeSendsGenerationConfigAndReadsMultiPartAnswer() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"candidates":[{"content":{"parts":[{"thought":true,"text":"..."},{"text":"{\"name\":"},{"text":"\"Dal\"}"}]},"finishReason":"STOP"}]}"""
                )
            )
            withClient { client ->
                val text = runBlocking {
                    GeminiClient.analyze(
                        client = client,
                        baseUrl = server.url("/v1beta").toString().trimEnd('/'),
                        model = "gemini-3.5-flash-lite",
                        apiKey = "test-key",
                        prompt = "Analyze",
                        imageBytesList = emptyList(),
                        generationConfig = liteConfig,
                        retryDelays = RetryPolicy.interactiveDelays
                    )
                }
                assertEquals("""{"name":"Dal"}""", text)

                val request = server.takeRequest()
                assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", request.path)
                assertEquals("test-key", request.getHeader("X-goog-api-key"))
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val generationConfig = body.obj("generationConfig")
                assertEquals(1024, generationConfig.int("maxOutputTokens"))
                assertEquals("application/json", generationConfig.string("responseMimeType"))
                assertEquals("minimal", generationConfig.obj("thinkingConfig").string("thinkingLevel"))
            }
        }
    }

    @Test
    fun analyzeRetriesOnceCompactlyWhenTruncated() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Oat"}]},"finishReason":"MAX_TOKENS"}]}"""))
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Oatmeal\"}"}]},"finishReason":"STOP"}]}"""))
            withClient { client ->
                val text = runBlocking {
                    GeminiClient.analyze(
                        client, server.url("/v1beta").toString().trimEnd('/'), "gemini-3.5-flash-lite", "key", "Analyze", emptyList(),
                        generationConfig = GeminiClient.GenerationConfig.forFoodAnalysis("gemini-3.5-flash-lite", 256, jsonResponse = true)
                    )
                }
                assertEquals("""{"name":"Oatmeal"}""", text)
                assertEquals(2, server.requestCount)
                server.takeRequest()
                val retryPrompt = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                    .getValue("contents").jsonArray.first().jsonObject
                    .getValue("parts").jsonArray.first().jsonObject.string("text")
                assertTrue(retryPrompt.contains("truncated"))
                assertTrue(retryPrompt.contains("256 tokens"))
            }
        }
    }

    @Test
    fun interactiveRetryProfileStopsAfterOneRetry() {
        MockWebServer().use { server ->
            repeat(3) {
                server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"The model is overloaded"}}"""))
            }
            withClient { client ->
                val error = try {
                    runBlocking {
                        GeminiClient.analyze(
                            client, server.url("/v1beta").toString().trimEnd('/'), "gemini-3.5-flash-lite", "key", "Analyze", emptyList(),
                            retryDelays = RetryPolicy.interactiveDelays
                        )
                    }
                    throw AssertionError("Expected failure")
                } catch (error: AiError) {
                    error
                }
                assertEquals(AiErrorKind.OVERLOADED, error.kind)
                assertEquals(2, server.requestCount)
            }
        }
    }

    private fun withClient(block: (OkHttpClient) -> Unit) {
        val client = OkHttpClient()
        try {
            block(client)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
