package com.apoorvdarshan.calorietracker.services.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.EnergyUnit
import com.apoorvdarshan.calorietracker.models.OpenRouterReasoningEffort
import com.apoorvdarshan.calorietracker.models.UserProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoachNonStreamingTest {
    @Test
    fun customEndpointReceivesExplicitNonStreamingRequest() = runBlocking {
        val prefs = mockk<PreferencesStore>()
        val keys = mockk<KeyStore>()
        every { prefs.energyUnit } returns flowOf(EnergyUnit.KCAL)
        every { prefs.userContext } returns flowOf("")
        every { prefs.separateTextProviderEnabled } returns flowOf(false)
        every { prefs.selectedAIProvider } returns flowOf(AIProvider.CUSTOM_OPENAI)
        every { prefs.selectedAIModel } returns flowOf("test-model")
        every { prefs.customBaseUrl(AIProvider.CUSTOM_OPENAI) } returns flowOf("https://example.invalid/v1")
        every { prefs.maxResponseTokens } returns flowOf(4096)
        every { prefs.aiRequestTimeoutSeconds } returns flowOf(60)
        every { prefs.openRouterReasoningEffort } returns flowOf(OpenRouterReasoningEffort.AUTO)
        every { keys.apiKey(AIProvider.CUSTOM_OPENAI) } returns "unused-test-value"

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { requireNotNull(request.body).writeTo(it) }
            val stream = JSONObject(body.readUtf8()).opt("stream")
            // A router that defaults to SSE returns JSON only when stream is explicitly false.
            val reply = if (stream == false) {
                """{"choices":[{"finish_reason":"stop","message":{"content":"Coach reply"}}]}"""
            } else {
                """data: {"choices":[{"delta":{"content":"Coach reply"}}]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val reply = ChatService(prefs, keys, client).sendMessage(
            history = emptyList(),
            newUserMessage = "How was my day?",
            profile = UserProfile(),
            weights = emptyList(),
            bodyFats = emptyList(),
            foods = emptyList(),
            heightMetric = true,
            weightMetric = true
        )
        assertEquals("Coach reply", reply)
    }
}
