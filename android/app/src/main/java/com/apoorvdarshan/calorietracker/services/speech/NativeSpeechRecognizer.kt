package com.apoorvdarshan.calorietracker.services.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.apoorvdarshan.calorietracker.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed class SttEvent {
    data class Partial(val text: String) : SttEvent()
    data class Final(val text: String) : SttEvent()
    data class Error(val code: Int, val message: String) : SttEvent()
    object Ready : SttEvent()
    object EndOfSpeech : SttEvent()
}

/**
 * Wraps Android's [SpeechRecognizer] as a cold Flow. Emits live partials while
 * the user speaks, then a Final event on completion. Port of iOS native-iOS STT
 * one-tap flow.
 */
class NativeSpeechRecognizer(private val context: Context) {

    companion object {
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        fun isRecoverableSessionError(code: Int): Boolean =
            code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    code == SpeechRecognizer.ERROR_NO_MATCH ||
                    code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    code == ERROR_SERVER_DISCONNECTED

        fun isLanguageSupportError(code: Int): Boolean =
            code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun listen(locale: String? = null, preferOffline: Boolean = true): Flow<SttEvent> = callbackFlow {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(SttEvent.Ready) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { trySend(SttEvent.EndOfSpeech) }
            override fun onError(error: Int) {
                trySend(SttEvent.Error(error, describeError(error)))
                close()
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                trySend(SttEvent.Final(list?.firstOrNull().orEmpty()))
                close()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                trySend(SttEvent.Partial(list?.firstOrNull().orEmpty()))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        recognizer.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (!locale.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)

        awaitClose {
            runCatching { recognizer.stopListening() }
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> context.getString(R.string.stt_error_audio)
        SpeechRecognizer.ERROR_CLIENT -> context.getString(R.string.stt_error_client)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.stt_error_permission)
        SpeechRecognizer.ERROR_NETWORK -> context.getString(R.string.stt_error_network)
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.stt_error_network_timeout)
        SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.stt_error_no_match)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(R.string.stt_error_busy)
        SpeechRecognizer.ERROR_SERVER -> context.getString(R.string.stt_error_server)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.stt_error_speech_timeout)
        ERROR_SERVER_DISCONNECTED -> context.getString(R.string.stt_error_server_disconnected)
        ERROR_LANGUAGE_NOT_SUPPORTED -> context.getString(R.string.stt_error_language_not_supported)
        ERROR_LANGUAGE_UNAVAILABLE -> context.getString(R.string.stt_error_language_unavailable)
        else -> context.getString(R.string.stt_error_generic_format, code)
    }
}
