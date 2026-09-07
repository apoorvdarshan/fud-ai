package com.apoorvdarshan.calorietracker.models

import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AIProvider {
    @SerialName("Google Gemini") GEMINI,
    @SerialName("OpenAI") OPENAI,
    @SerialName("Anthropic Claude") ANTHROPIC,
    @SerialName("xAI Grok") XAI,
    @SerialName("OpenRouter") OPENROUTER,
    @SerialName("Together AI") TOGETHER_AI,
    @SerialName("Groq") GROQ,
    @SerialName("Hugging Face") HUGGING_FACE,
    @SerialName("Fireworks AI") FIREWORKS,
    @SerialName("DeepInfra") DEEP_INFRA,
    @SerialName("Mistral") MISTRAL,
    @SerialName("DeepSeek") DEEPSEEK,
    @SerialName("Cerebras") CEREBRAS,
    @SerialName("Gemma 4 E2B (On-Device)") LOCAL_GEMMA,
    @SerialName("Ollama (Local)") OLLAMA,
    @SerialName("Custom (OpenAI-compatible)") CUSTOM_OPENAI;

    @get:StringRes
    val displayNameRes: Int get() = when (this) {
        GEMINI -> R.string.ai_provider_gemini
        OPENAI -> R.string.ai_provider_openai
        ANTHROPIC -> R.string.ai_provider_anthropic
        XAI -> R.string.ai_provider_xai
        OPENROUTER -> R.string.ai_provider_openrouter
        TOGETHER_AI -> R.string.ai_provider_together
        GROQ -> R.string.ai_provider_groq
        HUGGING_FACE -> R.string.ai_provider_huggingface
        FIREWORKS -> R.string.ai_provider_fireworks
        DEEP_INFRA -> R.string.ai_provider_deepinfra
        MISTRAL -> R.string.ai_provider_mistral
        DEEPSEEK -> R.string.ai_provider_deepseek
        CEREBRAS -> R.string.ai_provider_cerebras
        LOCAL_GEMMA -> R.string.ai_provider_local_gemma
        OLLAMA -> R.string.ai_provider_ollama
        CUSTOM_OPENAI -> R.string.ai_provider_custom
    }

    val baseUrl: String get() = when (this) {
        GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        OPENAI -> "https://api.openai.com/v1"
        ANTHROPIC -> "https://api.anthropic.com/v1"
        XAI -> "https://api.x.ai/v1"
        OPENROUTER -> "https://openrouter.ai/api/v1"
        TOGETHER_AI -> "https://api.together.xyz/v1"
        GROQ -> "https://api.groq.com/openai/v1"
        HUGGING_FACE -> "https://router.huggingface.co/v1"
        FIREWORKS -> "https://api.fireworks.ai/inference/v1"
        DEEP_INFRA -> "https://api.deepinfra.com/v1/openai"
        MISTRAL -> "https://api.mistral.ai/v1"
        DEEPSEEK -> "https://api.deepseek.com"
        CEREBRAS -> "https://api.cerebras.ai/v1"
        LOCAL_GEMMA -> ""
        OLLAMA -> "http://localhost:11434/v1"
        CUSTOM_OPENAI -> ""
    }

    /**
     * Only models that are currently in service AND accept image input + return structured text.
     * Lineups verified against provider docs on 2026-09-07. Mirrors iOS AIProvider.swift.
     */
    val models: List<String> get() = when (this) {
        GEMINI -> listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.1-pro-preview"
        )
        OPENAI -> listOf(
            "gpt-5.4-mini",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5",
            "gpt-5.4-nano",
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4o-mini"
        )
        ANTHROPIC -> listOf(
            "claude-sonnet-5",
            "claude-opus-5",
            "claude-fable-5",
            "claude-opus-4-8",
            "claude-haiku-4-5"
        )
        XAI -> listOf(
            "grok-4.6",
            "grok-4.3"
        )
        OPENROUTER -> listOf(
            "openrouter/free",
            "google/gemini-3.5-flash-lite",
            "google/gemini-3.8-flash",
            "google/gemini-3.7-flash",
            "openai/gpt-5.6-luna",
            "qwen/qwen3.8-27b",
            "openai/gpt-5-mini",
            "anthropic/claude-sonnet-5",
            "qwen/qwen3-vl-8b-instruct"
        )
        TOGETHER_AI -> listOf(
            "Qwen/Qwen3.5-9B",
            "moonshotai/Kimi-K3",
            "Qwen/Qwen3.8-2.4T-A95B",
            "google/gemma-4-31B-it",
            "MiniMaxAI/MiniMax-M3"
        )
        GROQ -> listOf(
            "qwen/qwen3.6-27b"
        )
        HUGGING_FACE -> listOf(
            "google/gemma-4-31B-it",
            "Qwen/Qwen3.8-27B",
            "moonshotai/Kimi-K3",
            "google/gemma-3-27b-it",
            "Qwen/Qwen3.5-9B"
        )
        FIREWORKS -> listOf(
            "accounts/fireworks/models/qwen3p7-plus",
            "accounts/fireworks/models/kimi-k3",
            "accounts/fireworks/models/muse-glimmer-30b",
            "accounts/fireworks/models/minimax-m3",
            "accounts/fireworks/models/kimi-k2p6"
        )
        DEEP_INFRA -> listOf(
            "google/gemma-3-27b-it",
            "Qwen/Qwen3.8-27B",
            "MiniMaxAI/MiniMax-M3",
            "google/gemma-4-31B-it",
            "google/gemma-4-26B-A4B-it"
        )
        MISTRAL -> listOf(
            "mistral-small-2603",
            "mistral-medium-3-5",
            "mistral-large-2512",
            "ministral-14b-2512"
        )
        LOCAL_GEMMA -> listOf("gemma-4-E2B-it")
        OLLAMA -> listOf(
            "qwen3-vl",
            "qwen3.8",
            "gemma4",
            "llama3.2-vision",
            "llava",
            "moondream"
        )
        DEEPSEEK, CEREBRAS -> emptyList()
        CUSTOM_OPENAI -> emptyList()
    }

    val defaultModel: String get() = models.firstOrNull() ?: ""

    val textModels: List<String> get() = when (this) {
        GROQ -> listOf(
            "openai/gpt-oss-20b",
            "openai/gpt-oss-120b",
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "qwen/qwen3.6-27b",
            "qwen/qwen3.8-27b"
        )
        TOGETHER_AI -> listOf(
            "MiniMaxAI/MiniMax-M2.7",
            "Qwen/Qwen3.7-Max",
            "Qwen/Qwen3.5-397B-A17B",
            "Qwen/Qwen3.6-Plus",
            "Qwen/Qwen3.5-9B",
            "moonshotai/Kimi-K2.6",
            "zai-org/GLM-5.1",
            "zai-org/GLM-5",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "deepseek-ai/DeepSeek-V4-Pro"
        )
        DEEPSEEK -> listOf("deepseek-v4-flash", "deepseek-v4-pro")
        CEREBRAS -> listOf("gpt-oss-120b", "gemma-4-31b")
        OLLAMA -> listOf("qwen3.8", "gemma4", "llama3.2", "qwen3", "mistral-small3.2") + models
        CUSTOM_OPENAI -> emptyList()
        else -> models
    }

    val defaultTextModel: String get() = textModels.firstOrNull() ?: defaultModel
    val supportsVision: Boolean get() = this != DEEPSEEK && this != CEREBRAS

    fun supportedModelOrDefault(model: String?): String {
        val normalized = model?.let(::normalizeModelId)
        return when {
            normalized.isNullOrBlank() -> defaultModel
            supportsCustomModelName -> normalized
            models.contains(normalized) -> normalized
            else -> defaultModel
        }
    }

    fun supportedTextModelOrDefault(model: String?): String {
        val normalized = model?.let(::normalizeModelId)
        return when {
            normalized.isNullOrBlank() -> defaultTextModel
            supportsCustomModelName -> normalized
            textModels.contains(normalized) -> normalized
            else -> defaultTextModel
        }
    }

    val requiresApiKey: Boolean get() = this != OLLAMA && this != LOCAL_GEMMA
    val requiresCustomEndpoint: Boolean get() = this == CUSTOM_OPENAI
    val requiresCustomModelName: Boolean get() = this == CUSTOM_OPENAI
    val usesConfigurableRequestTimeout: Boolean get() = this == OLLAMA || this == CUSTOM_OPENAI
    val supportsCustomModelName: Boolean
        get() = this == OPENROUTER || this == HUGGING_FACE || this == CUSTOM_OPENAI

    val apiFormat: ApiFormat get() = when (this) {
        GEMINI -> ApiFormat.GEMINI
        ANTHROPIC -> ApiFormat.ANTHROPIC
        LOCAL_GEMMA -> ApiFormat.LOCAL
        OPENAI, XAI, OPENROUTER, TOGETHER_AI, GROQ, HUGGING_FACE,
        FIREWORKS, DEEP_INFRA, MISTRAL, DEEPSEEK, CEREBRAS, OLLAMA, CUSTOM_OPENAI -> ApiFormat.OPENAI_COMPATIBLE
    }

    @get:StringRes
    val apiKeyPlaceholderRes: Int get() = when (this) {
        GEMINI -> R.string.ai_key_placeholder_gemini
        OPENAI -> R.string.ai_key_placeholder_openai
        ANTHROPIC -> R.string.ai_key_placeholder_anthropic
        XAI -> R.string.ai_key_placeholder_xai
        OPENROUTER -> R.string.ai_key_placeholder_openrouter
        TOGETHER_AI -> R.string.ai_key_placeholder_together
        GROQ -> R.string.ai_key_placeholder_groq
        HUGGING_FACE -> R.string.ai_key_placeholder_huggingface
        FIREWORKS -> R.string.ai_key_placeholder_fireworks
        DEEP_INFRA -> R.string.ai_key_placeholder_deepinfra
        MISTRAL -> R.string.ai_key_placeholder_mistral
        DEEPSEEK -> R.string.ai_key_placeholder_deepseek
        CEREBRAS -> R.string.ai_key_placeholder_cerebras
        LOCAL_GEMMA -> R.string.ai_key_placeholder_local
        OLLAMA -> R.string.ai_key_placeholder_ollama
        CUSTOM_OPENAI -> R.string.ai_key_placeholder_custom
    }

    enum class ApiFormat { GEMINI, OPENAI_COMPATIBLE, ANTHROPIC, LOCAL }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 180

        val visionProviders: List<AIProvider> get() = values().filter { it.supportsVision }
        val remoteVisionProviders: List<AIProvider>
            get() = visionProviders.filter { it != LOCAL_GEMMA }
        val textProviders: List<AIProvider>
            get() = values().filter { it.textModels.isNotEmpty() || it.requiresCustomModelName }
        val remoteTextProviders: List<AIProvider>
            get() = textProviders.filter { it != LOCAL_GEMMA }

        fun normalizedRequestTimeoutSeconds(value: Int): Int = value.coerceIn(30, 600)

        fun normalizeModelId(model: String): String =
            when (model.trim()) {
                "gemini-3.1-flash-lite-preview" -> "gemini-3.1-flash-lite"
                else -> model
            }

        /**
         * One-time upgrade path for older Gemini presets. Kept separate from
         * normalization so users may still manually select supported older models.
         */
        fun upgradedLegacyGeminiModel(model: String?): String? =
            when (model?.let(::normalizeModelId)) {
                "gemini-2.5-flash", "gemini-2.5-pro", "gemini-3.1-flash-lite" ->
                    "gemini-3.5-flash-lite"
                else -> null
            }

        /** Provider-scoped replacements for presets removed from the current registry. */
        fun upgradedLegacyModel(provider: AIProvider, model: String?): String? {
            val normalized = model?.let(::normalizeModelId) ?: return null
            return when {
                provider == GEMINI && normalized == "gemini-3.1-flash-lite" ->
                    "gemini-3.5-flash-lite"
                provider == OPENROUTER && normalized == "google/gemini-3.1-flash-lite" ->
                    "google/gemini-3.5-flash-lite"
                provider == HUGGING_FACE && normalized == "Qwen/Qwen2.5-VL-72B-Instruct" ->
                    "Qwen/Qwen3.8-27B"
                provider == MISTRAL && normalized == "mistral-medium-2604" ->
                    "mistral-medium-3-5"
                provider == ANTHROPIC && normalized == "claude-sonnet-4-6" ->
                    "claude-sonnet-5"
                provider == ANTHROPIC && normalized == "claude-opus-4-7" ->
                    "claude-opus-5"
                else -> null
            }
        }
    }
}
