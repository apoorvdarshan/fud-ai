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
        OLLAMA -> "http://localhost:11434/v1"
        CUSTOM_OPENAI -> ""
    }

    /** Only models that are currently in service AND accept image input + return structured text. */
    val models: List<String> get() = when (this) {
        GEMINI -> listOf(
            "gemini-3.1-flash-lite-preview",
            "gemini-3.1-pro-preview",
            "gemini-3-flash-preview",
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash"
        )
        OPENAI -> listOf(
            "gpt-5",
            "gpt-5-mini",
            "gpt-5-nano",
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4.1",
            "gpt-4.1-mini"
        )
        ANTHROPIC -> listOf(
            "claude-sonnet-4-6",
            "claude-opus-4-7",
            "claude-haiku-4-5",
            "claude-opus-4-5",
            "claude-sonnet-4-5-20250929",
            "claude-opus-4-1-20250805"
        )
        XAI -> listOf(
            "grok-4",
            "grok-2-vision-latest"
        )
        OPENROUTER -> listOf(
            "openrouter/free",
            "google/gemini-2.5-flash",
            "openai/gpt-4o",
            "anthropic/claude-sonnet-4",
            "meta-llama/llama-4-maverick"
        )
        TOGETHER_AI -> listOf(
            "meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8",
            "meta-llama/Llama-3.2-90B-Vision-Instruct-Turbo",
            "Qwen/Qwen2.5-VL-72B-Instruct"
        )
        GROQ -> listOf(
            "meta-llama/llama-4-scout-17b-16e-instruct"
        )
        HUGGING_FACE -> listOf(
            "google/gemma-3-27b-it",
            "Qwen/Qwen2.5-VL-72B-Instruct",
            "meta-llama/Llama-3.2-90B-Vision-Instruct"
        )
        FIREWORKS -> listOf(
            "accounts/fireworks/models/qwen2-vl-72b-instruct",
            "accounts/fireworks/models/llama-v3p2-90b-vision-instruct",
            "accounts/fireworks/models/phi-3-vision-128k-instruct"
        )
        DEEP_INFRA -> listOf(
            "google/gemma-3-27b-it",
            "meta-llama/Llama-3.2-90B-Vision-Instruct",
            "Qwen/Qwen2.5-VL-72B-Instruct"
        )
        MISTRAL -> listOf(
            "pixtral-large-latest",
            "pixtral-12b-latest"
        )
        OLLAMA -> listOf(
            "llama3.2-vision",
            "llava",
            "moondream"
        )
        CUSTOM_OPENAI -> emptyList()
    }

    val defaultModel: String get() = models.firstOrNull() ?: ""

    val requiresApiKey: Boolean get() = this != OLLAMA
    val requiresCustomEndpoint: Boolean get() = this == CUSTOM_OPENAI
    val requiresCustomModelName: Boolean get() = this == CUSTOM_OPENAI
    val supportsCustomModelName: Boolean
        get() = this == OPENROUTER || this == HUGGING_FACE || this == CUSTOM_OPENAI

    val apiFormat: ApiFormat get() = when (this) {
        GEMINI -> ApiFormat.GEMINI
        ANTHROPIC -> ApiFormat.ANTHROPIC
        OPENAI, XAI, OPENROUTER, TOGETHER_AI, GROQ, HUGGING_FACE,
        FIREWORKS, DEEP_INFRA, MISTRAL, OLLAMA, CUSTOM_OPENAI -> ApiFormat.OPENAI_COMPATIBLE
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
        OLLAMA -> R.string.ai_key_placeholder_ollama
        CUSTOM_OPENAI -> R.string.ai_key_placeholder_custom
    }

    enum class ApiFormat { GEMINI, OPENAI_COMPATIBLE, ANTHROPIC }
}
