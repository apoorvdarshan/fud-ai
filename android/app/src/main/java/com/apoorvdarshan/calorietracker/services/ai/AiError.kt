package com.apoorvdarshan.calorietracker.services.ai

sealed class AiError(message: String) : Exception(message) {
    object NoApiKey : AiError("No API key configured. Add your key in Settings → AI Provider.")
    object ImageConversionFailed : AiError("Failed to process the image.")
    class Network(cause: Throwable) : AiError("Network error: ${cause.localizedMessage}")
    object InvalidResponse : AiError("Could not understand the AI response. Please try again.")
    class Api(raw: String) : AiError(raw)
    class InvalidUrl(val url: String) : AiError("Invalid API URL. Check your provider settings.")
}

internal fun friendlyMessage(status: Int, raw: String): String = when (status) {
    503, 529 -> "The AI provider is overloaded right now. We retried a few times — please try again in a minute, or switch to a different provider/model in Settings → AI Provider."
    429 -> "Rate limit hit on your API key. Wait a minute, or switch to another provider in Settings → AI Provider."
    401, 403 -> "Your API key was rejected. Open Settings → AI Provider and re-paste a valid key."
    else -> raw
}
