package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.AIProvider
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class AiErrorTest {
    @Test fun statusAndProviderMarkersHaveActionableKinds() {
        for (marker in listOf("API key not valid", "API_KEY_INVALID", "API key expired", "API_KEY_EXPIRED")) {
            assertEquals(AiErrorKind.KEY_REJECTED, AiErrorKind.fromResponse(400, marker))
        }
        assertEquals(AiErrorKind.KEY_REJECTED, AiErrorKind.fromResponse(401, ""))
        assertEquals(AiErrorKind.KEY_REJECTED, AiErrorKind.fromResponse(403, ""))
        assertEquals(AiErrorKind.CREDITS, AiErrorKind.fromResponse(402, ""))
        assertEquals(AiErrorKind.CREDITS, AiErrorKind.fromResponse(400, "Credit balance is too low"))
        assertEquals(AiErrorKind.CREDITS, AiErrorKind.fromResponse(400, "INSUFFICIENT CREDITS"))
        assertEquals(AiErrorKind.MODEL, AiErrorKind.fromResponse(404, "unknown endpoint"))
        assertEquals(AiErrorKind.RATE_LIMIT, AiErrorKind.fromResponse(429, "too many requests"))
        for (status in listOf(503, 529)) assertEquals(AiErrorKind.OVERLOADED, AiErrorKind.fromResponse(status, ""))
    }

    @Test fun quotasAreNotAutomaticallyBillingFailures() {
        assertEquals(AiErrorKind.DAILY_QUOTA, AiErrorKind.fromResponse(429, "daily quota exceeded"))
        assertEquals(AiErrorKind.DAILY_QUOTA, AiErrorKind.fromResponse(429,
            """{"error":{"message":"Resource exhausted","details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}}"""))
        assertEquals(AiErrorKind.QUOTA, AiErrorKind.fromResponse(429, "quota exceeded"))
        assertEquals(AiErrorKind.QUOTA, AiErrorKind.fromResponse(429, "insufficient_quota"))
    }

    @Test fun networkFailuresDistinguishTimeoutOfflineAndConnection() {
        assertEquals(AiErrorKind.TIMEOUT, AiError.Network(SocketTimeoutException("private host")).kind)
        assertEquals(AiErrorKind.OFFLINE, AiError.Network(UnknownHostException("private host")).kind)
        assertEquals(AiErrorKind.CONNECTION, AiError.Network(ConnectException("connection refused")).kind)
        assertEquals(AiErrorKind.TIMEOUT, AiError.Network(IOException(SocketTimeoutException())).kind)
        assertFalse(AiError.Network(IOException("private transport text")).message!!.contains("private transport"))
    }

    @Test fun unknownProviderBodiesNeverBecomeUserMessages() {
        for (status in listOf(400, 418, 500)) {
            assertEquals(AiErrorKind.GENERIC, AiErrorKind.fromResponse(status, "sensitive raw provider text"))
        }
        assertFalse(AiError.Api("sensitive raw provider text").message!!.contains("sensitive"))
    }

    @Test fun combinedFailureRetainsProviderIdentityAndActionableKind() {
        val failure = AiError.BothProvidersFailed(AIProvider.GEMINI, AIProvider.OPENROUTER, AiError.Failure(AiErrorKind.CREDITS))
        assertEquals(AIProvider.GEMINI, failure.primary)
        assertEquals(AIProvider.OPENROUTER, failure.fallback)
        assertEquals(AiErrorKind.CREDITS, failure.kind)
    }
}
