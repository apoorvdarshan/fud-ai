package com.apoorvdarshan.calorietracker.services.hosted

import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.ai.AiErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HostedAiClientTest {
    private val server = MockWebServer()
    private val userId = "\$RCAnonymousID:0123456789abcdef0123456789abcdef"
    private lateinit var quota: HostedQuotaStore
    private lateinit var client: HostedAiClient

    private class MemoryPersistence : QuotaSnapshotPersistence {
        var value: String? = null
        override fun load(): String? = value
        override fun save(value: String) { this.value = value }
    }

    @Before
    fun setUp() {
        server.start()
        quota = HostedQuotaStore(MemoryPersistence(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
            client.fetchQuota(it)
        }
        client = HostedAiClient(
            userId = { userId },
            quota = quota,
            client = OkHttpClient(),
            baseUrl = server.url("/api/hosted-ai/v1").toString().trimEnd('/'),
            retryDelayMillis = 0
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun quotaHeaders(response: MockResponse, used: Int = 1, credits: Int = 50) = response
        .addHeader("X-Fud-Quota-Plan", "plus")
        .addHeader("X-Fud-Quota-Day", "2026-10-08")
        .addHeader("X-Fud-Quota-Daily-Used", used.toString())
        .addHeader("X-Fud-Quota-Daily-Limit", "30")
        .addHeader("X-Fud-Quota-Credits", credits.toString())

    @Test
    fun generateSendsTheRevenueCatIdAndAppliesQuotaHeaders() = runBlocking {
        server.enqueue(quotaHeaders(MockResponse().setResponseCode(200).setBody("""{"text":"{\"name\":\"Toast\"}"}""")))
        val text = client.generate("Analyze", listOf(byteArrayOf(1, 2, 3)), "User context: vegan", jsonResponse = true)
        assertEquals("""{"name":"Toast"}""", text)
        val request = server.takeRequest()
        assertEquals("/api/hosted-ai/v1/generate", request.path)
        assertEquals(userId, request.getHeader("X-Fud-User-Id"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Analyze", body["prompt"]!!.jsonPrimitive.content)
        assertEquals("AQID", body["images"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("User context: vegan", body["systemInstruction"]!!.jsonPrimitive.content)
        assertEquals("application/json", body["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals(1, quota.snapshot.value?.dailyUsed)
    }

    @Test
    fun quotaExceededMapsToThePaywallKindAndAppliesTheBodyQuota() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(402)
                .setBody("""{"error":"quota_exceeded","quota":{"plan":"plus","day":"2026-10-08","dailyUsed":30,"dailyLimit":30,"creditBank":0}}""")
        )
        val error = expectHosted { client.generate("hi", emptyList(), null, jsonResponse = false) }
        assertEquals(AiErrorKind.HOSTED_QUOTA_EXCEEDED, error.kind)
        assertTrue(error.kind.isHostedPaywallTrigger)
        assertEquals(30, quota.snapshot.value?.dailyUsed)
    }

    @Test
    fun subscriptionRequiredIsDistinctFromOtherForbiddenErrors() {
        assertEquals(AiErrorKind.HOSTED_SUBSCRIPTION_REQUIRED, HostedAiClient.errorFor(403, "subscription_required").kind)
        assertEquals(AiErrorKind.HOSTED_FAILED, HostedAiClient.errorFor(403, "other").kind)
        assertEquals(AiErrorKind.HOSTED_UNAUTHORIZED, HostedAiClient.errorFor(401, "invalid_user_id").kind)
        assertEquals(AiErrorKind.HOSTED_RATE_LIMITED, HostedAiClient.errorFor(429, "rate_limited").kind)
        assertEquals(AiErrorKind.HOSTED_AUDIO_TOO_LONG, HostedAiClient.errorFor(400, "audio_too_long").kind)
        assertEquals(AiErrorKind.HOSTED_TOO_LARGE, HostedAiClient.errorFor(413, "body_too_large").kind)
        assertEquals(AiErrorKind.HOSTED_TOO_MANY_IMAGES, HostedAiClient.errorFor(400, "too_many_images").kind)
        assertEquals(AiErrorKind.HOSTED_UNAVAILABLE, HostedAiClient.errorFor(504, "upstream_timeout").kind)
        assertEquals(AiErrorKind.HOSTED_REJECTED, HostedAiClient.errorFor(400, "invalid_request_body").kind)
        assertFalse(AiErrorKind.HOSTED_RATE_LIMITED.isHostedPaywallTrigger)
    }

    @Test
    fun unchargedUpstreamOutagesAreRetriedOnce() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"upstream_unavailable"}"""))
        server.enqueue(quotaHeaders(MockResponse().setResponseCode(200).setBody("""{"text":"ok"}""")))
        assertEquals("ok", client.generate("hi", emptyList(), null, jsonResponse = false))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun timeoutsAreNotRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(504).setBody("""{"error":"upstream_timeout"}"""))
        val error = expectHosted { client.generate("hi", emptyList(), null, jsonResponse = false) }
        assertEquals(AiErrorKind.HOSTED_UNAVAILABLE, error.kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun moreThanThreeImagesFailBeforeAnyNetworkCall() = runBlocking {
        val error = expectHosted { client.generate("hi", List(4) { byteArrayOf(1) }, null, jsonResponse = true) }
        assertEquals(AiErrorKind.HOSTED_TOO_MANY_IMAGES, error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun withoutBillingThereIsNoIdentityToSend() = runBlocking {
        val offline = HostedAiClient(userId = { null }, quota = quota, client = OkHttpClient(), baseUrl = server.url("/v1").toString())
        val error = expectHosted { offline.generate("hi", emptyList(), null, jsonResponse = false) }
        assertEquals(AiErrorKind.HOSTED_SUBSCRIPTION_REQUIRED, error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun geminiWrapsTheRequestBodyAndQuotaRefreshUsesTheRefreshFlag() = runBlocking {
        server.enqueue(quotaHeaders(MockResponse().setResponseCode(200).setBody("""{"candidates":[]}""")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"quota":{"plan":"pro","day":"2026-10-08","dailyUsed":2,"dailyLimit":60,"creditBank":150}}"""))
        assertEquals("""{"candidates":[]}""", client.gemini("""{"contents":"x"}"""))
        val gemini = server.takeRequest()
        val sent = Json.parseToJsonElement(gemini.body.readUtf8()).jsonObject
        assertEquals("x", sent["requestBody"]!!.jsonObject["contents"]!!.jsonPrimitive.content)
        val refreshed = quota.refresh(force = true).getOrThrow()
        assertEquals(150, refreshed.creditBank)
        assertEquals("/api/hosted-ai/v1/quota?refresh=1", server.takeRequest().path)
    }

    private suspend fun expectHosted(block: suspend () -> Unit): AiError {
        try {
            block()
        } catch (e: AiError) {
            return e
        }
        fail("expected AiError")
        error("unreachable")
    }
}
