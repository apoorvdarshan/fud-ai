package com.apoorvdarshan.calorietracker.services.ai

import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Retries 503/429/529 with 1s/2s/4s exponential backoff (same as iOS).
 * On final failure, throws [AiError.Api] with a user-friendly message.
 * The caller supplies a factory that builds a fresh [Call] per attempt
 * because OkHttp [Call] instances can only be executed once.
 */
object RetryPolicy {
    private val delays = longArrayOf(1_000, 2_000, 4_000)

    suspend fun execute(callFactory: () -> Call): String {
        var lastMessage = "Request failed"
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
            lastMessage = friendlyMessage(code, raw)

            val retryable = code == 503 || code == 529 || code == 429
            if (retryable && attempt < delays.size) {
                delay(delays[attempt])
                continue
            }
            throw AiError.Api(lastMessage)
        }
        throw AiError.Api(lastMessage)
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

private suspend fun Call.await(): Response = suspendCoroutine { cont ->
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
}
