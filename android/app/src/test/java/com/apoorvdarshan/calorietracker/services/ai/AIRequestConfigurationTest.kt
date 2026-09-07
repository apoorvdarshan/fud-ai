package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.services.speech.GeminiAudioClient
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AIRequestConfigurationTest {
    @Test
    fun modelPresetsAndDefaultsMatchIosRegistryOrder() {
        val registries = linkedMapOf(
            AIProvider.GEMINI to listOf(
                "gemini-3.5-flash-lite",
                "gemini-3.8-flash",
                "gemini-3.7-flash",
                "gemini-3.6-flash",
                "gemini-3.5-flash",
                "gemini-3.1-pro-preview"
            ),
            AIProvider.OPENAI to listOf(
                "gpt-5.4-mini",
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.5",
                "gpt-5.4-nano",
                "gpt-4.1",
                "gpt-4.1-mini",
                "gpt-4o-mini"
            ),
            AIProvider.ANTHROPIC to listOf(
                "claude-sonnet-5",
                "claude-opus-5",
                "claude-fable-5",
                "claude-opus-4-8",
                "claude-haiku-4-5"
            ),
            AIProvider.XAI to listOf("grok-4.6", "grok-4.3"),
            AIProvider.OPENROUTER to listOf(
                "openrouter/free",
                "google/gemini-3.5-flash-lite",
                "google/gemini-3.8-flash",
                "google/gemini-3.7-flash",
                "openai/gpt-5.6-luna",
                "qwen/qwen3.8-27b",
                "openai/gpt-5-mini",
                "anthropic/claude-sonnet-5",
                "qwen/qwen3-vl-8b-instruct"
            ),
            AIProvider.TOGETHER_AI to listOf(
                "Qwen/Qwen3.5-9B",
                "moonshotai/Kimi-K3",
                "Qwen/Qwen3.8-2.4T-A95B",
                "google/gemma-4-31B-it",
                "MiniMaxAI/MiniMax-M3"
            ),
            AIProvider.GROQ to listOf("qwen/qwen3.6-27b"),
            AIProvider.HUGGING_FACE to listOf(
                "google/gemma-4-31B-it",
                "Qwen/Qwen3.8-27B",
                "moonshotai/Kimi-K3",
                "google/gemma-3-27b-it",
                "Qwen/Qwen3.5-9B"
            ),
            AIProvider.FIREWORKS to listOf(
                "accounts/fireworks/models/qwen3p7-plus",
                "accounts/fireworks/models/kimi-k3",
                "accounts/fireworks/models/muse-glimmer-30b",
                "accounts/fireworks/models/minimax-m3",
                "accounts/fireworks/models/kimi-k2p6"
            ),
            AIProvider.DEEP_INFRA to listOf(
                "google/gemma-3-27b-it",
                "Qwen/Qwen3.8-27B",
                "MiniMaxAI/MiniMax-M3",
                "google/gemma-4-31B-it",
                "google/gemma-4-26B-A4B-it"
            ),
            AIProvider.MISTRAL to listOf(
                "mistral-small-2603",
                "mistral-medium-3-5",
                "mistral-large-2512",
                "ministral-14b-2512"
            ),
            AIProvider.LOCAL_GEMMA to listOf("gemma-4-E2B-it"),
            AIProvider.OLLAMA to listOf(
                "qwen3-vl",
                "qwen3.8",
                "gemma4",
                "llama3.2-vision",
                "llava",
                "moondream"
            ),
            AIProvider.CUSTOM_OPENAI to emptyList()
        )

        assertEquals(AIProvider.visionProviders, registries.keys.toList())
        registries.forEach { (provider, models) ->
            assertEquals(models, provider.models)
            assertEquals(models.firstOrNull().orEmpty(), provider.defaultModel)
        }
    }

    @Test
    fun textProvidersExposeCurrentTextOnlyModelsOutsideVisionRegistry() {
        assertEquals(AIProvider.values().toList(), AIProvider.textProviders)
        assertEquals(false, AIProvider.visionProviders.contains(AIProvider.DEEPSEEK))
        assertEquals(false, AIProvider.visionProviders.contains(AIProvider.CEREBRAS))
        assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), AIProvider.DEEPSEEK.textModels)
        assertEquals(listOf("gpt-oss-120b", "gemma-4-31b"), AIProvider.CEREBRAS.textModels)
        assertEquals("openai/gpt-oss-20b", AIProvider.GROQ.defaultTextModel)
        assertEquals(true, AIProvider.GROQ.textModels.contains("openai/gpt-oss-120b"))
        assertEquals(true, AIProvider.TOGETHER_AI.textModels.contains("deepseek-ai/DeepSeek-V4-Pro"))
        assertEquals("deepseek-v4-flash", AIProvider.DEEPSEEK.supportedTextModelOrDefault("retired-model"))
    }

    @Test
    fun removedPresetsHaveProviderScopedReplacements() {
        assertEquals(
            "gemini-3.5-flash-lite",
            AIProvider.upgradedLegacyModel(AIProvider.GEMINI, "gemini-3.1-flash-lite")
        )
        assertEquals(
            "google/gemini-3.5-flash-lite",
            AIProvider.upgradedLegacyModel(
                AIProvider.OPENROUTER,
                "google/gemini-3.1-flash-lite"
            )
        )
        assertEquals(
            "Qwen/Qwen3.8-27B",
            AIProvider.upgradedLegacyModel(
                AIProvider.HUGGING_FACE,
                "Qwen/Qwen2.5-VL-72B-Instruct"
            )
        )
        assertEquals(
            "mistral-medium-3-5",
            AIProvider.upgradedLegacyModel(AIProvider.MISTRAL, "mistral-medium-2604")
        )
        assertEquals(
            "claude-sonnet-5",
            AIProvider.upgradedLegacyModel(AIProvider.ANTHROPIC, "claude-sonnet-4-6")
        )
        assertEquals(
            "claude-opus-5",
            AIProvider.upgradedLegacyModel(AIProvider.ANTHROPIC, "claude-opus-4-7")
        )

        assertNull(AIProvider.upgradedLegacyModel(AIProvider.OPENAI, "gpt-5.4-mini"))
        assertNull(AIProvider.upgradedLegacyModel(AIProvider.GEMINI, null))
        assertNull(
            AIProvider.upgradedLegacyModel(AIProvider.OPENROUTER, "gemini-3.1-flash-lite")
        )
    }

    @Test
    fun freeFormProvidersPreserveUserSuppliedModels() {
        val customModel = "company/private-vision-model-v7"

        assertEquals(customModel, AIProvider.CUSTOM_OPENAI.supportedModelOrDefault(customModel))
        assertEquals(customModel, AIProvider.OPENROUTER.supportedModelOrDefault(customModel))
        assertEquals(customModel, AIProvider.HUGGING_FACE.supportedModelOrDefault(customModel))
        assertEquals(
            AIProvider.OPENAI.defaultModel,
            AIProvider.OPENAI.supportedModelOrDefault(customModel)
        )
    }

    @Test
    fun openAi56ModelsUseCompletionTokenLimitParameter() {
        listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna").forEach { model ->
            assertEquals(
                "max_completion_tokens",
                OpenAICompatibleClient.tokenLimitParameter(AIProvider.OPENAI, model)
            )
            assertEquals(
                "max_completion_tokens",
                OpenAICompatibleClient.tokenLimitParameter(
                    AIProvider.CUSTOM_OPENAI,
                    "openai/$model"
                )
            )
        }
        assertEquals(
            "max_tokens",
            OpenAICompatibleClient.tokenLimitParameter(
                AIProvider.OPENROUTER,
                "openai/gpt-5.6-luna"
            )
        )
    }

    @Test
    fun speechProviderDefaultsMatchIosRegistry() {
        val defaults = linkedMapOf(
            SpeechProvider.NATIVE to "",
            SpeechProvider.LOCAL_WHISPER to "ggml-base",
            SpeechProvider.GEMINI to "gemini-3.5-transcribe",
            SpeechProvider.OPENAI to "gpt-transcribe",
            SpeechProvider.GROQ to "whisper-large-v3",
            SpeechProvider.MISTRAL to "voxtral-mini-2602",
            SpeechProvider.DEEPGRAM to "nova-3",
            SpeechProvider.ASSEMBLY_AI to "universal-3-pro"
        )

        assertEquals(SpeechProvider.values().toList(), defaults.keys.toList())
        defaults.forEach { (provider, model) ->
            assertEquals(model, provider.defaultModel)
        }
        assertEquals(
            SpeechProvider.values().filter { it.requiresApiKey },
            SpeechProvider.remoteProviders
        )
    }

    @Test
    fun primaryAiProvidersMapOnlyToTheirFirstPartySpeechProvider() {
        assertEquals(
            SpeechProvider.GEMINI,
            SpeechProvider.matchingPrimaryAIProvider(AIProvider.GEMINI)
        )
        assertEquals(
            SpeechProvider.OPENAI,
            SpeechProvider.matchingPrimaryAIProvider(AIProvider.OPENAI)
        )
        assertEquals(
            SpeechProvider.GROQ,
            SpeechProvider.matchingPrimaryAIProvider(AIProvider.GROQ)
        )
        assertEquals(
            SpeechProvider.MISTRAL,
            SpeechProvider.matchingPrimaryAIProvider(AIProvider.MISTRAL)
        )
        assertNull(SpeechProvider.matchingPrimaryAIProvider(AIProvider.ANTHROPIC))
        assertNull(SpeechProvider.matchingPrimaryAIProvider(AIProvider.OPENROUTER))
        assertEquals(
            SpeechProvider.OPENAI,
            SpeechProvider.migratedV7Selection(AIProvider.OPENAI, SpeechProvider.NATIVE)
        )
        assertEquals(
            SpeechProvider.NATIVE,
            SpeechProvider.migratedV7Selection(AIProvider.ANTHROPIC, SpeechProvider.NATIVE)
        )
        assertEquals(
            SpeechProvider.DEEPGRAM,
            SpeechProvider.migratedV7Selection(AIProvider.GEMINI, SpeechProvider.DEEPGRAM)
        )
    }

    @Test
    fun geminiSpeechInteractionUsesTheDedicatedTranscriptionSchema() {
        val payload = Json.parseToJsonElement(GeminiAudioClient.interactionPayload(
            model = SpeechProvider.GEMINI.defaultModel,
            fileUri = "https://generativelanguage.googleapis.com/v1beta/files/audio",
            mimeType = "audio/m4a",
            languageCode = "hi"
        )).jsonObject

        assertEquals("gemini-3.5-transcribe", payload.getValue("model").jsonPrimitive.content)
        val audioInput = payload.getValue("input").jsonArray.first().jsonObject
        assertEquals("audio", audioInput.getValue("type").jsonPrimitive.content)
        assertEquals("audio/m4a", audioInput.getValue("mime_type").jsonPrimitive.content)
        val transcriptionConfig = payload.getValue("generation_config").jsonObject
            .getValue("transcription_config").jsonObject
        assertEquals("hi", transcriptionConfig.getValue("language_codes").jsonArray.first().jsonPrimitive.content)
        assertEquals("smart", transcriptionConfig.getValue("mode").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun geminiSpeechResponseParserSupportsCurrentInteractionShapes() {
        assertEquals(
            "two eggs and toast",
            GeminiAudioClient.transcriptFromResponse(
                """{"outputs":[{"type":"text","text":"  two eggs and toast  "}]}"""
            )
        )
        assertEquals(
            "Greek yogurt",
            GeminiAudioClient.transcriptFromResponse(
                """{"steps":[{"content":[{"type":"text","text":"Greek yogurt"}]}]}"""
            )
        )
    }

    @Test
    fun timeoutConfigurationClampsToSupportedRange() {
        assertEquals(30, AIProvider.normalizedRequestTimeoutSeconds(1))
        assertEquals(180, AIProvider.normalizedRequestTimeoutSeconds(180))
        assertEquals(600, AIProvider.normalizedRequestTimeoutSeconds(999))
    }

    @Test
    fun configurableTimeoutOnlyChangesLocalAndCustomClients() {
        val base = OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val cloud = FoodAnalysisService.clientForProvider(base, AIProvider.GEMINI, 240)
        val local = FoodAnalysisService.clientForProvider(base, AIProvider.OLLAMA, 240)

        assertSame(base, cloud)
        assertEquals(240_000, local.readTimeoutMillis)
        assertEquals(240_000, local.writeTimeoutMillis)
    }
}
