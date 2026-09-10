package com.apoorvdarshan.calorietracker.services.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RetryPolicyErrorTest {
    @Test fun dailyQuotaMetadataSurvivesAllExistingRetries() {
        MockWebServer().use { server ->
            repeat(4) {
                server.enqueue(MockResponse().setResponseCode(429).setBody(
                    """{"error":{"message":"Resource exhausted","details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}}"""
                ))
            }
            val error = failure(server)
            assertEquals(AiErrorKind.DAILY_QUOTA, error.kind)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun nonRetryableFailuresHaveSafeActionableMessages() {
        for ((status, expected) in listOf(402 to AiErrorKind.CREDITS, 404 to AiErrorKind.MODEL, 500 to AiErrorKind.GENERIC)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("private response text"))
                val error = failure(server)
                assertEquals(expected, error.kind)
                assertEquals(1, server.requestCount)
                assertFalse(error.message!!.contains("private response"))
            }
        }
    }

    private fun failure(server: MockWebServer): AiError {
        val client = OkHttpClient()
        val request = Request.Builder().url(server.url("/analysis")).build()
        try {
            runBlocking { RetryPolicy.execute { client.newCall(request) } }
            throw AssertionError("Expected failure")
        } catch (error: AiError) {
            return error
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
