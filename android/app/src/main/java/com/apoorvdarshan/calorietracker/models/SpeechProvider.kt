package com.apoorvdarshan.calorietracker.models

import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SpeechProvider {
    @SerialName("Native (On-Device)") NATIVE,
    @SerialName("Whisper Base (On-Device)") LOCAL_WHISPER,
    @SerialName("Parakeet v3 (On-Device)") LOCAL_PARAKEET,
    @SerialName("Qwen3-ASR 0.6B (On-Device)") LOCAL_QWEN3_ASR,
    @SerialName("Gemini Audio") GEMINI,
    @SerialName("OpenAI Whisper") OPENAI,
    @SerialName("Groq (Whisper)") GROQ,
    @SerialName("Mistral Voxtral") MISTRAL,
    @SerialName("Deepgram") DEEPGRAM,
    @SerialName("AssemblyAI") ASSEMBLY_AI;

    @get:StringRes
    val displayNameRes: Int get() = when (this) {
        NATIVE -> R.string.speech_provider_native
        LOCAL_WHISPER -> R.string.speech_provider_local_whisper
        LOCAL_PARAKEET -> R.string.speech_provider_local_parakeet
        LOCAL_QWEN3_ASR -> R.string.speech_provider_local_qwen3_asr
        GEMINI -> R.string.speech_provider_gemini
        OPENAI -> R.string.speech_provider_openai
        GROQ -> R.string.speech_provider_groq
        MISTRAL -> R.string.speech_provider_mistral
        DEEPGRAM -> R.string.speech_provider_deepgram
        ASSEMBLY_AI -> R.string.speech_provider_assemblyai
    }

    val localModelId: LocalModelId?
        get() = when (this) {
            LOCAL_WHISPER -> LocalModelId.WHISPER_BASE
            LOCAL_PARAKEET -> LocalModelId.PARAKEET_V3
            LOCAL_QWEN3_ASR -> LocalModelId.QWEN3_ASR
            else -> null
        }

    val detectsLanguageAutomatically: Boolean get() = this == LOCAL_PARAKEET || this == LOCAL_QWEN3_ASR
    val requiresApiKey: Boolean get() = this != NATIVE && localModelId == null

    val matchingAIProvider: AIProvider? get() = when (this) {
        GEMINI -> AIProvider.GEMINI
        OPENAI -> AIProvider.OPENAI
        GROQ -> AIProvider.GROQ
        MISTRAL -> AIProvider.MISTRAL
        NATIVE, LOCAL_WHISPER, LOCAL_PARAKEET, LOCAL_QWEN3_ASR, DEEPGRAM, ASSEMBLY_AI -> null
    }

    @get:StringRes
    val apiKeyPlaceholderRes: Int get() = when (this) {
        NATIVE -> R.string.speech_key_placeholder_native
        LOCAL_WHISPER, LOCAL_PARAKEET, LOCAL_QWEN3_ASR -> R.string.speech_key_placeholder_local
        GEMINI -> R.string.speech_key_placeholder_gemini
        OPENAI -> R.string.speech_key_placeholder_openai
        GROQ -> R.string.speech_key_placeholder_groq
        MISTRAL -> R.string.speech_key_placeholder_mistral
        DEEPGRAM -> R.string.speech_key_placeholder_deepgram
        ASSEMBLY_AI -> R.string.speech_key_placeholder_assemblyai
    }

    val defaultModel: String get() = when (this) {
        NATIVE -> ""
        LOCAL_WHISPER -> "ggml-base"
        LOCAL_PARAKEET -> "parakeet-tdt-0.6b-v3"
        LOCAL_QWEN3_ASR -> "qwen3-asr-0.6b"
        GEMINI -> "gemini-3.5-transcribe"
        OPENAI -> "gpt-transcribe"
        GROQ -> "whisper-large-v3"
        MISTRAL -> "voxtral-mini-2602"
        DEEPGRAM -> "nova-3"
        ASSEMBLY_AI -> "universal-3-pro"
    }

    @get:StringRes
    val descriptionRes: Int get() = when (this) {
        NATIVE -> R.string.speech_description_native
        LOCAL_WHISPER -> R.string.speech_description_local_whisper
        LOCAL_PARAKEET -> R.string.speech_description_local_parakeet
        LOCAL_QWEN3_ASR -> R.string.speech_description_local_qwen3_asr
        GEMINI -> R.string.speech_description_gemini
        OPENAI -> R.string.speech_description_openai
        GROQ -> R.string.speech_description_groq
        MISTRAL -> R.string.speech_description_mistral
        DEEPGRAM -> R.string.speech_description_deepgram
        ASSEMBLY_AI -> R.string.speech_description_assemblyai
    }

    companion object {
        val remoteProviders: List<SpeechProvider>
            get() = values().filter { it.requiresApiKey }

        val recordedFallbackProviders: List<SpeechProvider>
            get() = values().filter { it != NATIVE }

        /** First-party STT route for a supported Primary AI provider. */
        fun matchingPrimaryAIProvider(provider: AIProvider): SpeechProvider? = when (provider) {
            AIProvider.GEMINI -> GEMINI
            AIProvider.OPENAI -> OPENAI
            AIProvider.GROQ -> GROQ
            AIProvider.MISTRAL -> MISTRAL
            else -> null
        }

        fun migratedV7Selection(
            primaryAIProvider: AIProvider,
            currentSpeechProvider: SpeechProvider
        ): SpeechProvider = if (currentSpeechProvider == NATIVE) {
            matchingPrimaryAIProvider(primaryAIProvider) ?: NATIVE
        } else {
            currentSpeechProvider
        }
    }
}
