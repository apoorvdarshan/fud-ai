package com.apoorvdarshan.calorietracker.services.voice

import com.apoorvdarshan.calorietracker.models.AIProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceProviderResolverTest {
    private fun resolve(
        choice: VoiceProviderChoice,
        selected: AIProvider = AIProvider.GEMINI,
        keys: Set<LiveProvider> = emptySet(),
        entitled: Boolean = false
    ) = VoiceProviderResolver.resolve(choice, selected, { it in keys }, entitled)

    @Test
    fun automaticPrefersTheSelectedOwnKeyProvider() {
        assertEquals(
            VoiceAccess.Ready(LiveProvider.OPENAI),
            resolve(VoiceProviderChoice.AUTOMATIC, AIProvider.OPENAI, setOf(LiveProvider.OPENAI, LiveProvider.GEMINI), entitled = true)
        )
        assertEquals(
            VoiceAccess.Ready(LiveProvider.XAI),
            resolve(VoiceProviderChoice.AUTOMATIC, AIProvider.XAI, setOf(LiveProvider.XAI))
        )
    }

    @Test
    fun automaticFallsBackToHostingWhenSubscribed() {
        assertEquals(
            VoiceAccess.Ready(LiveProvider.HOSTED),
            resolve(VoiceProviderChoice.AUTOMATIC, AIProvider.GEMINI, keys = emptySet(), entitled = true)
        )
        // A selected provider without live voice (Anthropic) also falls back to hosting.
        assertEquals(
            VoiceAccess.Ready(LiveProvider.HOSTED),
            resolve(VoiceProviderChoice.AUTOMATIC, AIProvider.ANTHROPIC, setOf(LiveProvider.GEMINI), entitled = true)
        )
    }

    @Test
    fun automaticDoesNotSelectAnUnrelatedSavedKey() {
        assertEquals(
            VoiceAccess.NeedsSetup,
            resolve(VoiceProviderChoice.AUTOMATIC, AIProvider.ANTHROPIC, setOf(LiveProvider.GEMINI, LiveProvider.XAI))
        )
        assertEquals(VoiceAccess.NeedsSetup, resolve(VoiceProviderChoice.AUTOMATIC, AIProvider.ANTHROPIC))
        assertEquals(VoiceAccess.NeedsSetup, resolve(VoiceProviderChoice.AUTOMATIC, AIProvider.GEMINI))
    }

    @Test
    fun explicitChoicesNeverSwitchProvider() {
        assertEquals(
            VoiceAccess.MissingKey(LiveProvider.OPENAI),
            resolve(VoiceProviderChoice.OPENAI, AIProvider.OPENAI, setOf(LiveProvider.GEMINI), entitled = true)
        )
        assertEquals(VoiceAccess.Ready(LiveProvider.GEMINI), resolve(VoiceProviderChoice.GEMINI, keys = setOf(LiveProvider.GEMINI)))
        assertEquals(VoiceAccess.NeedsSubscription, resolve(VoiceProviderChoice.HOSTED, keys = setOf(LiveProvider.GEMINI)))
        assertEquals(VoiceAccess.Ready(LiveProvider.HOSTED), resolve(VoiceProviderChoice.HOSTED, entitled = true))
    }

    @Test
    fun startupWithAnOwnKeyDoesNotWaitForBilling() = runTest {
        val explicit = VoiceProviderResolver.resolveStartup(
            VoiceProviderChoice.XAI,
            selectedAiProvider = { error("Explicit choice must not look up the selected AI provider") },
            loadKey = { provider -> assertEquals(LiveProvider.XAI, provider); " demo-key " },
            hostedEntitled = { error("Explicit own-key choice must not wait for billing") }
        )
        assertEquals(VoiceAccess.Ready(LiveProvider.XAI), explicit.access)
        assertEquals("demo-key", explicit.key)

        val automatic = VoiceProviderResolver.resolveStartup(
            VoiceProviderChoice.AUTOMATIC,
            selectedAiProvider = { AIProvider.GEMINI },
            loadKey = { provider -> assertEquals(LiveProvider.GEMINI, provider); "demo-key" },
            hostedEntitled = { error("Automatic with the selected provider's key must not wait for billing") }
        )
        assertEquals(VoiceAccess.Ready(LiveProvider.GEMINI), automatic.access)
    }

    @Test
    fun startupUsesHostedOnlyWhenTheChoiceAllowsIt() = runTest {
        val hosted = VoiceProviderResolver.resolveStartup(
            VoiceProviderChoice.AUTOMATIC,
            selectedAiProvider = { AIProvider.GEMINI },
            loadKey = { " " },
            hostedEntitled = { true }
        )
        assertEquals(VoiceAccess.Ready(LiveProvider.HOSTED), hosted.access)
        assertNull(hosted.key)

        val missing = VoiceProviderResolver.resolveStartup(
            VoiceProviderChoice.GEMINI,
            selectedAiProvider = { error("Explicit choice must not look up the selected AI provider") },
            loadKey = { null },
            hostedEntitled = { error("Missing an explicitly selected key must not check billing") }
        )
        assertEquals(VoiceAccess.MissingKey(LiveProvider.GEMINI), missing.access)
    }

    @Test
    fun providersCarryTheContractModelsVoicesAndCameraPolicy() {
        assertEquals("gemini-3.8-live", LiveProvider.GEMINI.defaultModel)
        assertEquals(listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview"), LiveProvider.GEMINI.models)
        assertEquals(listOf("gpt-realtime-2.1-mini", "gpt-realtime-2.1"), LiveProvider.OPENAI.models)
        assertEquals(listOf("grok-voice-latest", "grok-voice-3.0"), LiveProvider.XAI.models)
        assertEquals("Kore", LiveProvider.GEMINI.defaultVoice)
        assertEquals("Kore", LiveProvider.HOSTED.defaultVoice)
        assertEquals("marin", LiveProvider.OPENAI.defaultVoice)
        assertEquals("ara", LiveProvider.XAI.defaultVoice)
        assertEquals(12, LiveProvider.GEMINI.voices.size)
        assertEquals(10, LiveProvider.OPENAI.voices.size)
        assertEquals(5, LiveProvider.XAI.voices.size)
        assertEquals(CameraFrames.CONTINUOUS, LiveProvider.HOSTED.cameraFrames)
        assertEquals(CameraFrames.CONTINUOUS, LiveProvider.GEMINI.cameraFrames)
        assertEquals(CameraFrames.PER_TURN, LiveProvider.OPENAI.cameraFrames)
        assertEquals(CameraFrames.NONE, LiveProvider.XAI.cameraFrames)
        assertEquals(listOf("qwen3.8-omni-flash-realtime"), LiveProvider.QWEN.models)
        assertEquals("Tina", LiveProvider.QWEN.defaultVoice)
        assertEquals(10, LiveProvider.QWEN.voices.size)
        assertEquals(CameraFrames.CONTINUOUS, LiveProvider.QWEN.cameraFrames)
        assertEquals(AIProvider.QWEN, LiveProvider.QWEN.aiProvider)
        assertEquals(LiveProvider.QWEN, LiveProvider.forAiProvider(AIProvider.QWEN))
        assertEquals(VoiceProviderChoice.QWEN, VoiceProviderChoice.fromStorage("qwen"))
        assertEquals(listOf("gpt-live-1"), LiveProvider.GPT_LIVE.models)
        assertEquals("quartz", LiveProvider.GPT_LIVE.defaultVoice)
        assertEquals(CameraFrames.NONE, LiveProvider.GPT_LIVE.cameraFrames)
        assertEquals(AIProvider.OPENAI, LiveProvider.GPT_LIVE.aiProvider)
        // An OpenAI key keeps Automatic on the realtime models.
        assertEquals(LiveProvider.OPENAI, LiveProvider.forAiProvider(AIProvider.OPENAI))
        assertEquals(VoiceProviderChoice.GPT_LIVE, VoiceProviderChoice.fromStorage("gpt_live"))
        assertEquals(
            VoiceAccess.Ready(LiveProvider.QWEN),
            VoiceProviderResolver.resolve(VoiceProviderChoice.QWEN, AIProvider.GEMINI, { it == LiveProvider.QWEN }, false)
        )
        assertEquals(
            VoiceAccess.MissingKey(LiveProvider.QWEN),
            VoiceProviderResolver.resolve(VoiceProviderChoice.QWEN, AIProvider.GEMINI, { it == LiveProvider.GEMINI }, true)
        )
    }

    @Test
    fun storedChoicesModelsAndVoicesFallBackToDefaults() {
        assertEquals(VoiceProviderChoice.AUTOMATIC, VoiceProviderChoice.fromStorage(null))
        assertEquals(VoiceProviderChoice.XAI, VoiceProviderChoice.fromStorage("xai"))
        assertEquals("gpt-realtime-2.1", LiveProvider.OPENAI.supportedModelOrDefault("gpt-realtime-2.1"))
        assertEquals("gpt-realtime-2.1-mini", LiveProvider.OPENAI.supportedModelOrDefault("gpt-4o-realtime"))
        assertEquals("Puck", LiveProvider.GEMINI.supportedVoiceOrDefault("puck"))
        assertEquals("ara", LiveProvider.XAI.supportedVoiceOrDefault("Kore"))
        assertEquals("Marin", LiveProvider.voiceDisplayName("marin"))
        assertEquals(LiveProvider.OPENAI, LiveProvider.forAiProvider(AIProvider.OPENAI))
        assertEquals(null, LiveProvider.forAiProvider(AIProvider.ANTHROPIC))
    }
}
