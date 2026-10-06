package com.apoorvdarshan.calorietracker.models

import java.util.Locale

/**
 * In-app UI locales that ship as `values-*` folders. Distinct from [SpeechLanguage]
 * (speech-to-text only). Empty / null tag means follow the device locale.
 */
object AppLanguage {
    /** BCP-47 tags matching `res/xml/locales_config.xml` and `values-*`. */
    val supportedTags: List<String> = listOf(
        "en",
        "ar",
        "az",
        "cs",
        "de",
        "es",
        "fr",
        "hi",
        "it",
        "ja",
        "ko",
        "nl",
        "pl",
        "pt-BR",
        "ro",
        "ru",
        "uk",
        "zh-CN"
    )

    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(locale)
        return name.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(locale) else ch.toString()
        }
    }

    /** Maps a stored / system language tag onto one of [supportedTags], or null for system default. */
    fun matchSupported(languageTag: String?): String? {
        if (languageTag.isNullOrBlank()) return null
        supportedTags.firstOrNull { it.equals(languageTag, ignoreCase = true) }?.let { return it }
        val got = Locale.forLanguageTag(languageTag)
        return supportedTags.firstOrNull { tag ->
            val wanted = Locale.forLanguageTag(tag)
            wanted.language.equals(got.language, ignoreCase = true) &&
                (wanted.country.isEmpty() || wanted.country.equals(got.country, ignoreCase = true))
        }
    }
}
