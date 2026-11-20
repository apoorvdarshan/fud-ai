package com.apoorvdarshan.calorietracker.services.speech

import android.content.Context
import com.apoorvdarshan.calorietracker.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Recorded-audio transcription failures. The English message is for logs; [userMessage] is what the UI shows. */
sealed class SttApiError(message: String) : Exception(message) {
    object NoApiKey : SttApiError("No STT API key configured.")
    class Network(cause: Throwable) : SttApiError("Network error: ${cause.localizedMessage}")
    class Api(msg: String, val httpCode: Int? = null) : SttApiError(msg)
    object InvalidResponse : SttApiError("Could not understand the transcription response.")
    object Timeout : SttApiError("Transcription timed out.")
    object LocalUnavailable : SttApiError("The on-device Whisper runtime is unavailable.")
    object EmptyTranscript : SttApiError("The recording contained no recognizable speech.")

    fun userMessage(context: Context): String = when (this) {
        NoApiKey -> context.getString(R.string.stt_api_error_no_key)
        is Network -> context.getString(R.string.stt_api_error_network)
        is Api -> httpCode?.let { context.getString(R.string.stt_api_error_http, it) }
            ?: context.getString(R.string.stt_api_error_api)
        InvalidResponse -> context.getString(R.string.stt_api_error_invalid_response)
        Timeout -> context.getString(R.string.stt_api_error_timeout)
        LocalUnavailable -> context.getString(R.string.stt_api_error_local_unavailable)
        EmptyTranscript -> context.getString(R.string.stt_error_no_match)
    }
}

/**
 * OpenAI, Groq, and Mistral share /v1/audio/transcriptions (multipart).
 */
object WhisperClient {
    suspend fun transcribe(
        client: OkHttpClient,
        baseUrl: String,
        apiKey: String,
        model: String,
        audio: File,
        languageCode: String? = null
    ): String = withContext(Dispatchers.IO) {
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", audio.name, audio.asRequestBody(audio.mimeType().toMediaType()))
        if (!languageCode.isNullOrBlank()) {
            bodyBuilder.addFormDataPart("language", languageCode)
        }
        val body = bodyBuilder.build()
        val req = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        runRequest(client, req)
    }
}

/**
 * Gemini 3.5 Transcribe uses the Files API followed by the Interactions API.
 */
object GeminiAudioClient {
    suspend fun transcribe(
        client: OkHttpClient,
        apiKey: String,
        model: String,
        audio: File,
        languageCode: String? = null
    ): String = withContext(Dispatchers.IO) {
        val uploadedFile = uploadAudio(client, apiKey, audio)
        try {
            val body = interactionPayload(
                model = model,
                fileUri = uploadedFile.uri,
                mimeType = audio.mimeType(),
                languageCode = languageCode
            ).toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/interactions")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-goog-api-key", apiKey)
                .post(body)
                .build()

            transcriptFromResponse(runRequestRaw(client, req)) ?: throw SttApiError.InvalidResponse
        } finally {
            deleteFile(client, apiKey, uploadedFile.name)
        }
    }

    internal fun interactionPayload(
        model: String,
        fileUri: String,
        mimeType: String,
        languageCode: String?
    ): String {
        val languageCodes = buildJsonArray {
            if (!languageCode.isNullOrBlank()) add(JsonPrimitive(languageCode))
        }
        return buildJsonObject {
            put("model", model)
            putJsonArray("input") {
                add(buildJsonObject {
                    put("type", "audio")
                    put("uri", fileUri)
                    put("mime_type", mimeType)
                })
            }
            putJsonObject("generation_config") {
                putJsonObject("transcription_config") {
                    put("language_codes", languageCodes)
                    putJsonObject("mode") { put("type", "smart") }
                }
            }
        }.toString()
    }

    internal fun transcriptFromResponse(responseBody: String): String? = runCatching {
        val json = Json.parseToJsonElement(responseBody).jsonObject
        json["output_text"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return@runCatching it }

        json["outputs"]?.jsonArray?.firstNotNullOfOrNull { output ->
            output.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }?.let { return@runCatching it }

        json["steps"]?.jsonArray?.firstNotNullOfOrNull { step ->
            step.jsonObject["content"]?.jsonArray?.firstNotNullOfOrNull { content ->
                content.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

    private data class UploadedFile(val uri: String, val name: String)

    private suspend fun uploadAudio(client: OkHttpClient, apiKey: String, audio: File): UploadedFile {
        val metadata = JSONObject()
            .put("file", JSONObject().put("display_name", audio.name))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val startRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files")
            .addHeader("X-goog-api-key", apiKey)
            .addHeader("X-Goog-Upload-Protocol", "resumable")
            .addHeader("X-Goog-Upload-Command", "start")
            .addHeader("X-Goog-Upload-Header-Content-Length", audio.length().toString())
            .addHeader("X-Goog-Upload-Header-Content-Type", audio.mimeType())
            .post(metadata)
            .build()

        val uploadUrl = try {
            client.newCall(startRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SttApiError.Api("STT HTTP ${response.code}: ${responseBody.take(200)}", response.code)
                }
                response.header("X-Goog-Upload-URL")
                    ?: throw SttApiError.Api("Gemini upload URL was missing from the response.")
            }
        } catch (io: IOException) {
            throw SttApiError.Network(io)
        }

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .addHeader("Content-Length", audio.length().toString())
            .addHeader("X-Goog-Upload-Offset", "0")
            .addHeader("X-Goog-Upload-Command", "upload, finalize")
            .post(audio.asRequestBody(audio.mimeType().toMediaType()))
            .build()
        val file = JSONObject(runRequestRaw(client, uploadRequest)).optJSONObject("file")
            ?: throw SttApiError.InvalidResponse
        val uri = file.optString("uri").takeIf { it.isNotBlank() } ?: throw SttApiError.InvalidResponse
        val name = file.optString("name").takeIf { it.isNotBlank() } ?: throw SttApiError.InvalidResponse
        return UploadedFile(uri, name)
    }

    private fun deleteFile(client: OkHttpClient, apiKey: String, name: String) {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$name")
            .addHeader("X-goog-api-key", apiKey)
            .delete()
            .build()
        try {
            client.newCall(request).execute().close()
        } catch (_: IOException) {
            // Files auto-expire, so cleanup failure must not hide the transcript result.
        }
    }
}

/**
 * Deepgram: raw audio body, Token auth.
 */
object DeepgramClient {
    suspend fun transcribe(
        client: OkHttpClient,
        apiKey: String,
        model: String,
        audio: File,
        languageCode: String? = null
    ): String = withContext(Dispatchers.IO) {
        val languageQuery = if (!languageCode.isNullOrBlank()) "&language=$languageCode" else ""
        val req = Request.Builder()
            .url("https://api.deepgram.com/v1/listen?model=$model&punctuate=true&smart_format=true$languageQuery")
            .addHeader("Authorization", "Token $apiKey")
            .addHeader("Content-Type", audio.mimeType())
            .post(audio.asRequestBody(audio.mimeType().toMediaType()))
            .build()
        val body = runRequestRaw(client, req)
        runCatching {
            JSONObject(body)
                .getJSONObject("results")
                .getJSONArray("channels")
                .getJSONObject(0)
                .getJSONArray("alternatives")
                .getJSONObject(0)
                .getString("transcript")
        }.getOrNull() ?: throw SttApiError.InvalidResponse
    }
}

/**
 * AssemblyAI: 3-step upload -> submit -> poll every 1s up to 60s.
 */
object AssemblyAIClient {
    suspend fun transcribe(
        client: OkHttpClient,
        apiKey: String,
        speechModels: List<String>,
        audio: File,
        languageCode: String? = null
    ): String = withContext(Dispatchers.IO) {
        // 1. Upload
        val uploadReq = Request.Builder()
            .url("https://api.assemblyai.com/v2/upload")
            .addHeader("authorization", apiKey)
            .post(audio.asRequestBody(audio.mimeType().toMediaType()))
            .build()
        val uploadJson = JSONObject(runRequestRaw(client, uploadReq))
        val audioUrl = uploadJson.optString("upload_url").takeIf { it.isNotEmpty() }
            ?: throw SttApiError.InvalidResponse

        // 2. Submit
        val submitPayload = JSONObject()
            .put("audio_url", audioUrl)
            .put("speech_models", JSONArray(speechModels))
        if (!languageCode.isNullOrBlank()) {
            submitPayload.put("language_code", languageCode)
        } else {
            submitPayload.put("language_detection", true)
        }
        val submitBody = submitPayload.toString().toRequestBody("application/json".toMediaType())
        val submitReq = Request.Builder()
            .url("https://api.assemblyai.com/v2/transcript")
            .addHeader("authorization", apiKey)
            .post(submitBody)
            .build()
        val submitJson = JSONObject(runRequestRaw(client, submitReq))
        val transcriptId = submitJson.optString("id").takeIf { it.isNotEmpty() }
            ?: throw SttApiError.InvalidResponse

        // 3. Poll
        repeat(60) {
            delay(1_000)
            val pollReq = Request.Builder()
                .url("https://api.assemblyai.com/v2/transcript/$transcriptId")
                .addHeader("authorization", apiKey)
                .get()
                .build()
            val pollJson = JSONObject(runRequestRaw(client, pollReq))
            when (pollJson.optString("status")) {
                "completed" -> return@withContext pollJson.optString("text").orEmpty()
                "error" -> throw SttApiError.Api(pollJson.optString("error", "AssemblyAI error"))
            }
        }
        throw SttApiError.Timeout
    }
}

// Shared helpers -------------------------------------------------------

private suspend fun runRequest(client: OkHttpClient, req: Request): String {
    val body = runRequestRaw(client, req)
    return runCatching {
        JSONObject(body).optString("text").takeIf { it.isNotEmpty() }
    }.getOrNull() ?: throw SttApiError.InvalidResponse
}

private fun File.mimeType(): String = when (extension.lowercase()) {
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    else -> "audio/mp4"
}

private suspend fun runRequestRaw(client: OkHttpClient, req: Request): String = withContext(Dispatchers.IO) {
    try {
        client.newCall(req).execute().use { resp ->
            val str = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SttApiError.Api("STT HTTP ${resp.code}: ${str.take(200)}", resp.code)
            str
        }
    } catch (io: IOException) {
        throw SttApiError.Network(io)
    }
}
