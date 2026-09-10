package com.apoorvdarshan.calorietracker.services.ai

import android.content.Context
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.AIProvider
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/** Stable categories; provider response text is never a user-facing message. */
enum class AiErrorKind(val messageRes: Int, val defaultMessage: String) {
    NO_KEY(R.string.ai_error_no_key, "No API key configured. Add your key in Settings → AI Provider."),
    IMAGE_CONVERSION(R.string.ai_error_image_conversion, "Failed to process the image. Try another photo."),
    INVALID_RESPONSE(R.string.ai_error_invalid_response, "Could not understand the AI response. Please try again."),
    INVALID_URL(R.string.ai_error_invalid_url, "Invalid API URL. Check your provider settings."),
    OFFLINE(R.string.ai_error_offline, "You appear to be offline. Check your connection and try again."),
    CONNECTION(R.string.ai_error_connection, "Could not connect to the AI provider. Check your connection and provider URL, then try again."),
    TIMEOUT(R.string.ai_error_timeout, "The AI took too long to respond. Try again, or raise the request timeout in Settings → AI Provider."),
    CREDITS(R.string.ai_error_credits, "Your AI account is out of credits. Top up, or switch provider in Settings → AI Provider."),
    DAILY_QUOTA(R.string.ai_error_daily_quota, "Your AI account’s daily quota is used up. Try again tomorrow, or switch provider in Settings → AI Provider."),
    QUOTA(R.string.ai_error_quota, "Your AI account’s usage quota was exceeded. Check your provider’s limits, or switch provider in Settings → AI Provider."),
    MODEL(R.string.ai_error_model, "The selected model or API endpoint is unavailable. Check the model and API URL in Settings → AI Provider."),
    RATE_LIMIT(R.string.ai_error_rate_limit, "Rate limit hit on your API key. Wait a minute, or switch provider in Settings → AI Provider. If you use a free tier, check whether its daily quota is used up."),
    KEY_REJECTED(R.string.ai_error_key_rejected, "Your API key was rejected. Open Settings → AI Provider and re-paste a valid key."),
    OVERLOADED(R.string.ai_error_overloaded, "The AI provider is overloaded right now. We retried a few times — try again in a minute, or switch provider/model in Settings → AI Provider."),
    GENERIC(R.string.ai_error_generic, "The AI request failed. Try again, or switch provider in Settings → AI Provider."),
    LOCAL_UNAVAILABLE(R.string.ai_error_local_unavailable, "The on-device AI runtime is unavailable. Check the downloaded model in Settings → AI Provider."),
    TRUNCATED(R.string.ai_error_truncated, "The AI response was truncated twice. Try a shorter input or another model.");

    companion object {
        fun fromResponse(status: Int, raw: String): AiErrorKind {
            val text = raw.lowercase()
            fun has(vararg markers: String) = markers.any { it in text }
            return when {
                status == 401 || status == 403 || (status == 400 && has("api key not valid", "api_key_invalid", "api key expired", "api_key_expired")) -> KEY_REJECTED
                status == 402 || has("insufficient credits", "insufficient credit", "credit balance is too low", "out of credits", "billing_hard_limit_reached") -> CREDITS
                has("quota", "limit", "resource_exhausted") && has("daily", "per day", "per_day", "perday", "requestsperday") -> DAILY_QUOTA
                has("quota exceeded", "quota_exceeded", "exceeded your current quota", "insufficient_quota") -> QUOTA
                status == 404 -> MODEL
                status == 429 -> RATE_LIMIT
                status == 503 || status == 529 -> OVERLOADED
                else -> GENERIC
            }
        }

        fun fromNetwork(error: Throwable): AiErrorKind {
            val causes = generateSequence(error) { it.cause }.take(8).toList()
            return when {
                causes.any { it is SocketTimeoutException || it is TimeoutException } -> TIMEOUT
                causes.any { it is UnknownHostException } -> OFFLINE
                else -> CONNECTION
            }
        }
    }
}

sealed class AiError(val kind: AiErrorKind) : Exception(kind.defaultMessage) {
    object NoApiKey : AiError(AiErrorKind.NO_KEY)
    object ImageConversionFailed : AiError(AiErrorKind.IMAGE_CONVERSION)
    class Network(cause: Throwable) : AiError(AiErrorKind.fromNetwork(cause)) { init { initCause(cause) } }
    object InvalidResponse : AiError(AiErrorKind.INVALID_RESPONSE)
    class Api(raw: String, kind: AiErrorKind = AiErrorKind.fromResponse(0, raw)) : AiError(kind)
    class Failure(kind: AiErrorKind) : AiError(kind)
    class InvalidUrl(val url: String) : AiError(AiErrorKind.INVALID_URL)
    class BothProvidersFailed(val primary: AIProvider, val fallback: AIProvider, val failure: Throwable) :
        AiError((failure as? AiError)?.kind ?: AiErrorKind.GENERIC)

    fun userMessage(context: Context): String = when (this) {
        is BothProvidersFailed -> context.getString(R.string.ai_error_both_failed,
            context.getString(primary.displayNameRes), context.getString(fallback.displayNameRes),
            (failure as? AiError)?.userMessage(context) ?: context.getString(R.string.ai_error_generic))
        else -> context.getString(kind.messageRes)
    }
}
