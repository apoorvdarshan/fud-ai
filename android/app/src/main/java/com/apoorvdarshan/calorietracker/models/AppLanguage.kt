package com.apoorvdarshan.calorietracker.models

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
        "tr",
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

    /** Languages the app's strings ship in; every one has a picker entry. */
    val translatedTags: List<String> = supportedTags

    /** Maps a stored / system language tag onto one of [supportedTags], or null for system default. */
    fun matchSupported(languageTag: String?): String? = match(languageTag, supportedTags)

    /**
     * The language the app's strings appear in: the in-app choice, else the first device
     * language the app is translated into, else English (the default resources). A device
     * set to Finnish sees English, so this returns "en", not "fi".
     */
    fun shownTag(appChoice: String?, deviceTags: List<String>): String =
        match(appChoice, translatedTags)
            ?: deviceTags.firstNotNullOfOrNull { match(it, translatedTags) }
            ?: "en"

    /** [shownTag] for the running app: the in-app choice and the device's language list. */
    fun shownTag(): String {
        val device = LocaleListCompat.getAdjustedDefault()
        return shownTag(
            AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag(),
            (0 until device.size()).mapNotNull { device[it]?.toLanguageTag() }
        )
    }

    /** English name of a language tag (`pt-BR` → "Portuguese (Brazil)"), for AI instructions. */
    fun englishName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifBlank { tag }

    private fun match(languageTag: String?, tags: List<String>): String? {
        if (languageTag.isNullOrBlank()) return null
        tags.firstOrNull { it.equals(languageTag, ignoreCase = true) }?.let { return it }
        val got = Locale.forLanguageTag(languageTag)
        return tags.firstOrNull { tag ->
            val wanted = Locale.forLanguageTag(tag)
            wanted.language.equals(got.language, ignoreCase = true) &&
                (wanted.country.isEmpty() || wanted.country.equals(got.country, ignoreCase = true))
        }
    }
}
