package com.apoorvdarshan.calorietracker.services.speech

import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysisService
import com.apoorvdarshan.calorietracker.services.ondevice.LocalWhisperRuntime
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File

/**
 * Routes a single-shot recording to the selected local or remote STT provider.
 * Native on-device STT is handled separately via [NativeSpeechRecognizer] since it
 * streams partial results rather than taking a file upload.
 */
class SpeechService(
    private val prefs: PreferencesStore,
    private val keyStore: KeyStore,
    private val okHttp: OkHttpClient = FoodAnalysisService.defaultClient,
    private val localWhisper: LocalWhisperRuntime? = null
) {

    /** Returns the transcript text. Throws [SttApiError] on any failure. */
    suspend fun transcribeRecordedAudio(audio: File): String {
        val provider = prefs.selectedSpeechProvider.first()
        return try {
            try {
                transcribe(audio, provider)
            } catch (primaryError: Throwable) {
                if (primaryError is kotlinx.coroutines.CancellationException) throw primaryError
                if (!prefs.speechFallbackEnabled.first()) throw primaryError
                val fallback = prefs.selectedSpeechFallbackProvider.first()
                if (fallback == provider || !isUsableRecordedProvider(fallback)) throw primaryError
                transcribe(audio, fallback)
            }
        } finally {
            runCatching { audio.delete() }
        }
    }

    private suspend fun transcribe(audio: File, provider: SpeechProvider): String {
        val languageCode = prefs.selectedSpeechLanguage(provider).first().remoteLanguageCode()
        val apiKey = keyStore.speechApiKey(provider)

        if (provider.requiresApiKey && apiKey.isNullOrEmpty()) throw SttApiError.NoApiKey

        return when (provider) {
            SpeechProvider.LOCAL_WHISPER -> localWhisper?.transcribe(audio, languageCode)
                ?: error("The on-device Whisper runtime is unavailable.")
            SpeechProvider.GEMINI -> GeminiAudioClient.transcribe(
                client = okHttp,
                apiKey = apiKey!!,
                model = provider.defaultModel,
                audio = audio,
                languageCode = languageCode
            )
            SpeechProvider.OPENAI -> WhisperClient.transcribe(
                client = okHttp,
                baseUrl = "https://api.openai.com/v1",
                apiKey = apiKey!!,
                model = provider.defaultModel,
                audio = audio,
                languageCode = languageCode
            )
            SpeechProvider.GROQ -> WhisperClient.transcribe(
                client = okHttp,
                baseUrl = "https://api.groq.com/openai/v1",
                apiKey = apiKey!!,
                model = provider.defaultModel,
                audio = audio,
                languageCode = languageCode
            )
            SpeechProvider.MISTRAL -> WhisperClient.transcribe(
                client = okHttp,
                baseUrl = "https://api.mistral.ai/v1",
                apiKey = apiKey!!,
                model = provider.defaultModel,
                audio = audio,
                languageCode = languageCode
            )
            SpeechProvider.DEEPGRAM -> DeepgramClient.transcribe(
                client = okHttp,
                apiKey = apiKey!!,
                model = provider.defaultModel,
                audio = audio,
                languageCode = languageCode
            )
            SpeechProvider.ASSEMBLY_AI -> AssemblyAIClient.transcribe(
                client = okHttp,
                apiKey = apiKey!!,
                speechModels = listOf(provider.defaultModel, "universal-2"),
                audio = audio,
                languageCode = languageCode
            )
            SpeechProvider.NATIVE ->
                error("NATIVE speech should use NativeSpeechRecognizer, not a recorded audio file.")
        }
    }

    private fun isUsableRecordedProvider(provider: SpeechProvider): Boolean = when (provider) {
        SpeechProvider.NATIVE -> false
        SpeechProvider.LOCAL_WHISPER -> localWhisper?.isReady() == true
        else -> !keyStore.speechApiKey(provider).isNullOrEmpty()
    }
}
