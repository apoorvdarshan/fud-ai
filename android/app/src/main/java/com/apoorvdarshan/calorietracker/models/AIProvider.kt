package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelId

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
    @SerialName("Alibaba Qwen") QWEN,
    @SerialName("Cerebras") CEREBRAS,
    @SerialName("Gemma 4 E2B (On-Device)") LOCAL_GEMMA,
    @SerialName("Gemma 4 E4B (On-Device)") LOCAL_GEMMA_E4B,
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
        QWEN -> R.string.ai_provider_qwen
        CEREBRAS -> R.string.ai_provider_cerebras
        LOCAL_GEMMA -> R.string.ai_provider_local_gemma
        LOCAL_GEMMA_E4B -> R.string.ai_provider_local_gemma_e4b
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
        // Model Studio's public international (Singapore) endpoint.
        QWEN -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        CEREBRAS -> "https://api.cerebras.ai/v1"
        LOCAL_GEMMA, LOCAL_GEMMA_E4B -> ""
        OLLAMA -> "http://localhost:11434/v1"
        CUSTOM_OPENAI -> ""
    }

    /**
     * Only models that are currently in service AND accept image input + return structured text.
     * Public endpoint presets, not an inventory of dedicated deployments. Mirrors iOS AIProvider.swift.
     */
    val models: List<String> get() = when (this) {
        GEMINI -> listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-4-hydrogen",
            "gemini-4-neon",
            "gemini-4-argon",
            "gemini-4-carbon",
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-3.1-pro-preview"
        )
        OPENAI -> listOf(
            "gpt-5.4-mini",
            // GPT-6 presets verified 2026-10-17; Coach uses reasoning_effort=none.
            "gpt-6-luna",
            "gpt-6-sol",
            "gpt-6.1-terra",
            "gpt-6.2-sol",
            "gpt-6.2-astra",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5"
        )
        ANTHROPIC -> listOf(
            "claude-sonnet-5",
            "claude-sonnet-5-5",
            "claude-haiku-5-5",
            "claude-opus-5-5",
            "claude-opus-5",
            "claude-fable-5",
            "claude-opus-4-8",
            "claude-haiku-4-5"
        )
        XAI -> listOf(
            "grok-4.6",
            "grok-4.3",
            "grok-4.7"
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
            "qwen/qwen3-vl-8b-instruct",
            "deepseek/deepseek-v4.1-flash"
        )
        TOGETHER_AI -> listOf(
            "Qwen/Qwen3.5-9B",
            "moonshotai/Kimi-K3",
            "moonshotai/Kimi-K3.1-Pro"
        )
        GROQ -> listOf(
            "qwen/qwen3.8-27b"
        )
        HUGGING_FACE -> listOf(
            "google/gemma-4-31B-it",
            "Qwen/Qwen3.8-27B",
            "moonshotai/Kimi-K3",
            "google/gemma-3-27b-it",
            "Qwen/Qwen3.5-9B"
        )
        FIREWORKS -> listOf(
            "accounts/fireworks/models/kimi-k3",
            "accounts/fireworks/models/minimax-m3p1-flash",
            "accounts/fireworks/models/kimi-k3p1-pro"
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
            "mistral-large-4-0",
            "ministral-14b-2512",
            "ministral-8b-2512"
        )
        QWEN -> listOf(
            "qwen3.7-plus",
            "qwen3.8-flash",
            "qwen3.8-max",
            "qwen3-vl-plus",
            "qwen4-max"
        )
        LOCAL_GEMMA -> listOf("gemma-4-E2B-it")
        LOCAL_GEMMA_E4B -> listOf("gemma-4-E4B-it")
        OLLAMA -> listOf(
            "qwen3-vl",
            "qwen3.8",
            "gemma4",
            "gemma4:e2b",
            "qwen3-vl:2b",
            "llama3.2-vision",
            "llava",
            "moondream"
        )
        DEEPSEEK -> listOf("deepseek-flash")
        CEREBRAS -> listOf("qwen-3.8-27b")
        CUSTOM_OPENAI -> emptyList()
    }

    val defaultModel: String get() = models.firstOrNull() ?: ""

    val textModels: List<String> get() = when (this) {
        GROQ -> listOf(
            "openai/gpt-oss-20b",
            "openai/gpt-oss-120b",
            "qwen/qwen3.8-27b"
        )
        TOGETHER_AI -> listOf(
            "Qwen/Qwen3.5-9B",
            "Qwen/Qwen3.7-Max",
            "Qwen/Qwen3.6-Plus",
            "openai/gpt-oss-120b",
            "MiniMaxAI/MiniMax-M3.1-Flash",
            "deepseek-ai/DeepSeek-V4-Pro-0813"
        )
        FIREWORKS -> models + "accounts/fireworks/models/minimax-m3"
        DEEPSEEK -> listOf("deepseek-flash", "deepseek-v4-pro")
        QWEN -> listOf("qwen3.8-flash", "qwen3.7-plus", "qwen3.8-max", "qwen4-max")
        OPENAI -> models // Coach routes 6.1/6.2 through the Responses API.
        CEREBRAS -> listOf("gpt-oss-120b", "qwen-3.8-27b")
        OLLAMA -> (listOf("qwen3.8", "gemma4", "llama3.2", "qwen3", "mistral-small3.2") + models).distinct()
        CUSTOM_OPENAI -> emptyList()
        else -> models
    }

    val defaultTextModel: String get() = textModels.firstOrNull() ?: defaultModel
    fun needsResponsesForTools(model: String): Boolean =
        this == OPENAI && (model.startsWith("gpt-6.1-") || model.startsWith("gpt-6.2-"))
    val supportsVision: Boolean get() = models.isNotEmpty() || requiresCustomModelName

    fun supportedModelOrDefault(model: String?): String {
        val normalized = upgradedLegacyModel(this, model) ?: model?.let {
            if (supportsCustomModelName) it else normalizeModelId(it)
        }
        return when {
            normalized.isNullOrBlank() -> defaultModel
            supportsCustomModelName -> normalized
            models.contains(normalized) -> normalized
            else -> defaultModel
        }
    }

    fun supportedTextModelOrDefault(model: String?): String {
        val normalized = upgradedLegacyModel(this, model, text = true) ?: model?.let {
            if (supportsCustomModelName) it else normalizeModelId(it)
        }
        return when {
            normalized.isNullOrBlank() -> defaultTextModel
            supportsCustomModelName -> normalized
            textModels.contains(normalized) -> normalized
            else -> defaultTextModel
        }
    }

    val localModelId: LocalModelId?
        get() = when (this) {
            LOCAL_GEMMA -> LocalModelId.GEMMA_4_E2B
            LOCAL_GEMMA_E4B -> LocalModelId.GEMMA_4_E4B
            else -> null
        }

    val requiresApiKey: Boolean get() = this != OLLAMA && localModelId == null
    val requiresCustomEndpoint: Boolean get() = this == CUSTOM_OPENAI
    val requiresCustomModelName: Boolean get() = this == CUSTOM_OPENAI
    val usesConfigurableRequestTimeout: Boolean get() = this == OLLAMA || this == CUSTOM_OPENAI
    val supportsCustomModelName: Boolean
        get() = this == OPENROUTER || this == HUGGING_FACE || this == CUSTOM_OPENAI

    val apiFormat: ApiFormat get() = when (this) {
        GEMINI -> ApiFormat.GEMINI
        ANTHROPIC -> ApiFormat.ANTHROPIC
        LOCAL_GEMMA, LOCAL_GEMMA_E4B -> ApiFormat.LOCAL
        OPENAI, XAI, OPENROUTER, TOGETHER_AI, GROQ, HUGGING_FACE,
        FIREWORKS, DEEP_INFRA, MISTRAL, DEEPSEEK, QWEN, CEREBRAS, OLLAMA, CUSTOM_OPENAI -> ApiFormat.OPENAI_COMPATIBLE
    }

    /** Short name used in “Paste X API key” hints (not the full picker label). */
    val apiKeyBrandName: String get() = when (this) {
        GEMINI -> "Gemini"
        OPENAI -> "OpenAI"
        ANTHROPIC -> "Claude"
        XAI -> "Grok"
        OPENROUTER -> "OpenRouter"
        TOGETHER_AI -> "Together AI"
        GROQ -> "Groq"
        HUGGING_FACE -> "Hugging Face"
        FIREWORKS -> "Fireworks"
        DEEP_INFRA -> "DeepInfra"
        MISTRAL -> "Mistral"
        DEEPSEEK -> "DeepSeek"
        QWEN -> "Qwen"
        CEREBRAS -> "Cerebras"
        CUSTOM_OPENAI -> "API"
        LOCAL_GEMMA, LOCAL_GEMMA_E4B, OLLAMA -> "API"
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
        QWEN -> R.string.ai_key_placeholder_qwen
        CEREBRAS -> R.string.ai_key_placeholder_cerebras
        LOCAL_GEMMA, LOCAL_GEMMA_E4B -> R.string.ai_key_placeholder_local
        OLLAMA -> R.string.ai_key_placeholder_ollama
        CUSTOM_OPENAI -> R.string.ai_key_placeholder_custom
    }

    enum class ApiFormat { GEMINI, OPENAI_COMPATIBLE, ANTHROPIC, LOCAL }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 180

        val visionProviders: List<AIProvider> get() = values().filter { it.supportsVision }
        val remoteVisionProviders: List<AIProvider>
            get() = visionProviders.filter { it.localModelId == null }
        val textProviders: List<AIProvider>
            get() = values().filter { it.textModels.isNotEmpty() || it.requiresCustomModelName }
        val remoteTextProviders: List<AIProvider>
            get() = textProviders.filter { it.localModelId == null }

        fun normalizedRequestTimeoutSeconds(value: Int): Int = value.coerceIn(30, 600)

        /** The app's normal multi-photo meal limit. */
        const val DEFAULT_MEAL_PHOTO_LIMIT = 10

        /** Groq's Qwen vision models accept at most three images per request (#712). */
        const val GROQ_REQUEST_IMAGE_LIMIT = 3

        /**
         * Photos one request to this provider/model accepts. Null means the provider
         * takes the app's full ten-photo selection.
         */
        fun requestImageLimit(provider: AIProvider, model: String?): Int? = when {
            provider == GROQ -> GROQ_REQUEST_IMAGE_LIMIT
            // Don't assume the user's key is on Pay as You Go.
            provider == CEREBRAS && model == "qwen-3.8-27b" -> 2
            else -> null
        }

        /** The smallest photo cap of the primary provider and the image fallback. */
        fun mealPhotoLimit(primaryLimit: Int?, fallbackLimit: Int?): Int =
            listOfNotNull(primaryLimit, fallbackLimit).minOrNull() ?: DEFAULT_MEAL_PHOTO_LIMIT

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
                "gemini-2.5-pro" -> "gemini-3.8-flash"
                "gemini-2.5-flash", "gemini-3.1-flash-lite" ->
                    "gemini-3.5-flash-lite"
                else -> null
            }

        /** Provider-scoped replacements for presets removed from the current registry. */
        fun upgradedLegacyModel(provider: AIProvider, model: String?, text: Boolean = false): String? {
            val normalized = model?.let(::normalizeModelId) ?: return null
            return when (provider) {
                GEMINI -> when (normalized) {
                    "gemini-3.7-flash" -> "gemini-3.8-flash"
                    "gemini-3.5-flash" -> "gemini-3.6-flash"
                    else -> upgradedLegacyGeminiModel(normalized)
                }
                OPENAI -> when (normalized) {
                    "gpt-4.1", "gpt-4.1-mini", "gpt-4o-mini", "gpt-5.4-nano" -> "gpt-6-luna"
                    else -> null
                }
                MISTRAL -> when (normalized) {
                    "mistral-medium-2604", "mistral-medium-3-5", "mistral-large-2512" -> "mistral-large-4-0"
                    else -> null
                }
                ANTHROPIC -> when (normalized) {
                    "claude-sonnet-4-6" -> "claude-sonnet-5"
                    "claude-opus-4-7" -> "claude-opus-5"
                    else -> null
                }
                GROQ -> when (normalized) {
                    "qwen/qwen3.6-27b" -> "qwen/qwen3.8-27b"
                    "llama-3.1-8b-instant" -> if (text) "openai/gpt-oss-20b" else null
                    "llama-3.3-70b-versatile" -> if (text) "openai/gpt-oss-120b" else null
                    else -> null
                }
                TOGETHER_AI -> when (normalized) {
                    "MiniMaxAI/MiniMax-M2.7", "Qwen/Qwen3.5-397B-A17B", "zai-org/GLM-5", "zai-org/GLM-5.1" -> "Qwen/Qwen3.5-9B"
                    "moonshotai/Kimi-K2.6" -> if (text) "Qwen/Qwen3.7-Max" else "moonshotai/Kimi-K3"
                    "openai/gpt-oss-20b" -> if (text) "openai/gpt-oss-120b" else null
                    "deepseek-ai/DeepSeek-V4-Pro" -> if (text) "deepseek-ai/DeepSeek-V4-Pro-0813" else null
                    "Qwen/Qwen3.8-2.4T-A95B", "google/gemma-4-31B-it", "MiniMaxAI/MiniMax-M3" -> "Qwen/Qwen3.5-9B"
                    else -> null
                }
                FIREWORKS -> when (normalized) {
                    "accounts/fireworks/models/qwen3p7-plus", "accounts/fireworks/models/muse-glimmer-30b",
                    "accounts/fireworks/models/kimi-k2p6" -> "accounts/fireworks/models/kimi-k3"
                    "accounts/fireworks/models/minimax-m3" -> if (text) null else "accounts/fireworks/models/minimax-m3p1-flash"
                    else -> null
                }
                DEEPSEEK -> when (normalized) {
                    "deepseek-v4-flash", "deepseek-v4-flash-vision-exp" -> "deepseek-flash"
                    else -> null
                }
                CEREBRAS -> "qwen-3.8-27b".takeIf { normalized == "gemma-4-31b" }
                else -> null
            }
        }
    }
}
