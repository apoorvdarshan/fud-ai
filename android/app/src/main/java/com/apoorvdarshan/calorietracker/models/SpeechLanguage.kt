package com.apoorvdarshan.calorietracker.models

import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class SpeechLanguage(
    @get:StringRes val displayNameRes: Int,
    val languageCode: String?
) {
    PROVIDER_AUTO(R.string.speech_language_provider_auto, null),
    DEVICE(R.string.speech_language_device, null),
    ENGLISH(R.string.speech_language_english, "en"),
    GERMAN(R.string.speech_language_german, "de"),
    SPANISH(R.string.speech_language_spanish, "es"),
    FRENCH(R.string.speech_language_french, "fr"),
    ITALIAN(R.string.speech_language_italian, "it"),
    PORTUGUESE(R.string.speech_language_portuguese, "pt"),
    DUTCH(R.string.speech_language_dutch, "nl"),
    HINDI(R.string.speech_language_hindi, "hi"),
    JAPANESE(R.string.speech_language_japanese, "ja"),
    CHINESE(R.string.speech_language_chinese, "zh"),
    KOREAN(R.string.speech_language_korean, "ko"),
    CZECH(R.string.speech_language_czech, "cs");

    fun remoteLanguageCode(): String? = when (this) {
        PROVIDER_AUTO -> null
        DEVICE -> Locale.getDefault().language.takeIf { it.isNotBlank() }
        else -> languageCode
    }

    fun nativeLocaleTag(): String = when (this) {
        PROVIDER_AUTO, DEVICE -> Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() }
            ?: Locale.getDefault().language.ifBlank { "en" }
        else -> languageCode ?: "en"
    }

    companion object {
        fun defaultFor(provider: SpeechProvider): SpeechLanguage = when (provider) {
            SpeechProvider.NATIVE -> DEVICE
            SpeechProvider.DEEPGRAM -> DEVICE
            SpeechProvider.LOCAL_WHISPER,
            SpeechProvider.GEMINI,
            SpeechProvider.OPENAI,
            SpeechProvider.GROQ,
            SpeechProvider.MISTRAL,
            SpeechProvider.ASSEMBLY_AI -> PROVIDER_AUTO
        }

        fun optionsFor(provider: SpeechProvider): List<SpeechLanguage> {
            val explicitLanguages = listOf(
                ENGLISH,
                GERMAN,
                SPANISH,
                FRENCH,
                ITALIAN,
                PORTUGUESE,
                DUTCH,
                HINDI,
                JAPANESE,
                CHINESE,
                KOREAN,
                CZECH
            )
            return when (provider) {
                SpeechProvider.NATIVE -> listOf(DEVICE) + explicitLanguages
                else -> listOf(PROVIDER_AUTO, DEVICE) + explicitLanguages
            }
        }
    }
}
