package com.apoorvdarshan.calorietracker

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Resolves string resources outside AppCompat activities — the application context,
 * services, widgets and broadcast receivers — in the language chosen in Settings.
 *
 * [AppCompatDelegate.setApplicationLocales] applies to AppCompat activities only on
 * Android 12 and earlier, so the raw application context keeps the device language
 * there. On Android 13+ the system applies the per-app locale to every context and
 * the base context is returned unchanged.
 */
object AppLocalization {
    fun context(base: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return base
        val tag = locales.toLanguageTags().substringBefore(',').ifBlank { return base }
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(configuration)
    }
}
