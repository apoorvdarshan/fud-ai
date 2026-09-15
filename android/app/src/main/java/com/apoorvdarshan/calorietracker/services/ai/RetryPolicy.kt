package com.apoorvdarshan.calorietracker.services.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Retries 503/429/529 with exponential backoff (same as iOS).
 * On final failure, throws [AiError.Api] with a user-friendly message.
 * The caller supplies a factory that builds a fresh [Call] per attempt
 * because OkHttp [Call] instances can only be executed once.
 */
object RetryPolicy {
    /** Default 1s/2s/4s backoff: up to four attempts. Used by the coach and background jobs. */
    val defaultDelays = longArrayOf(1_000, 2_000, 4_000)

    /**
     * Interactive food logging keeps the user staring at the analyzing overlay, so it only gets
     * one quick retry before surfacing the overload/rate-limit error and letting them decide.
     */
    val interactiveDelays = longArrayOf(1_500)

    suspend fun execute(delays: LongArray = defaultDelays, callFactory: () -> Call): String {
        var lastKind = AiErrorKind.GENERIC
        for (attempt in 0..delays.size) {
            val response = try {
                callFactory().await()
            } catch (io: IOException) {
                throw AiError.Network(io)
            }

            val bodyStr = response.use { it.body?.string().orEmpty() }
            val code = response.code

            if (response.isSuccessful) return bodyStr

            val raw = parseErrorMessage(bodyStr)?.takeIf { it.isNotEmpty() } ?: "HTTP $code"
            lastKind = AiErrorKind.fromResponse(code, raw + "\n" + bodyStr)

            val retryable = code == 503 || code == 529 || code == 429
            if (retryable && attempt < delays.size) {
                delay(delays[attempt])
                continue
            }
            throw AiError.Failure(lastKind)
        }
        throw AiError.Failure(lastKind)
    }

    private fun parseErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val json = JSONObject(body)
            when (val errorNode = json.opt("error")) {
                is JSONObject -> errorNode.optString("message").takeIf { it.isNotEmpty() }
                is String -> errorNode.takeIf { it.isNotEmpty() }
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * Cooperative cancellation: cancelling the coroutine (e.g. the analyzing overlay's Cancel button)
 * also cancels the underlying OkHttp call instead of letting it run to completion in the background.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation { cancel() }
}
