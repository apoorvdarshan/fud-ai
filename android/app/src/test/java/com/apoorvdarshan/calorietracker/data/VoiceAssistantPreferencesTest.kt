package com.apoorvdarshan.calorietracker.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.apoorvdarshan.calorietracker.models.AppLanguage
import com.apoorvdarshan.calorietracker.services.voice.VoiceProviderChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAssistantPreferencesTest {
    private val language = stringPreferencesKey("voiceAssistantLanguage")
    private val autoCapture = booleanPreferencesKey("voiceAssistantAutoCapture")

    @Test
    fun cleanupRetiresOnlyOfflineVoiceChoicesAndItsFallback() {
        val provider = stringPreferencesKey("voiceAssistantProvider")
        val fallback = booleanPreferencesKey("localVoiceCloudFallback")
        for (old in listOf("lfm3_offline", "lfm3_connected", "qwen")) {
            val saved = mutablePreferencesOf(provider to old, fallback to true, language to "hi")
            PreferencesStore.clearRetiredOfflineVoicePreferences(saved)
            assertNull(saved[fallback])
            val restored = PreferencesStore.readVoiceAssistantPreferences(saved)
            assertEquals(if (old == "qwen") VoiceProviderChoice.QWEN else VoiceProviderChoice.AUTOMATIC, restored.choice)
            assertEquals(if (old == "qwen") old else null, saved[provider])
            assertEquals("hi", restored.language)
        }
    }

    @Test
    fun nothingSavedRepliesInTheAppLanguageWithAutoCaptureOn() {
        val prefs = PreferencesStore.readVoiceAssistantPreferences(emptyPreferences())
        assertEquals(VoiceAssistantPreferences.LANGUAGE_APP, prefs.language)
        assertTrue(prefs.autoCapture)
        assertEquals(VoiceAssistantPreferences(), prefs)
    }

    @Test
    fun geminiInterruptionsAreOffByDefaultAndCanBeEnabled() {
        assertFalse(PreferencesStore.readVoiceAssistantPreferences(emptyPreferences()).geminiBargeIn)
        assertTrue(PreferencesStore.readVoiceAssistantPreferences(
            preferencesOf(booleanPreferencesKey("voiceAssistantGeminiBargeIn") to true)
        ).geminiBargeIn)
    }

    @Test
    fun savedChoicesAreKept() {
        val automatic = PreferencesStore.readVoiceAssistantPreferences(
            preferencesOf(language to VoiceAssistantPreferences.LANGUAGE_AUTOMATIC, autoCapture to false)
        )
        assertEquals(VoiceAssistantPreferences.LANGUAGE_AUTOMATIC, automatic.language)
        assertFalse(automatic.autoCapture)
        assertEquals("es", PreferencesStore.readVoiceAssistantPreferences(preferencesOf(language to "es")).language)
    }

    @Test
    fun appLanguageIsTheLanguageTheAppShows() {
        // Finnish has no translation, so the app shows English.
        assertEquals("en", AppLanguage.shownTag(null, listOf("fi-FI")))
        assertEquals("de", AppLanguage.shownTag(null, listOf("fi-FI", "de-DE")))
        assertEquals("tr", AppLanguage.shownTag(null, listOf("tr-TR")))
        assertEquals("pt-BR", AppLanguage.shownTag(null, listOf("pt-BR")))
        assertEquals("zh-CN", AppLanguage.shownTag(null, listOf("zh-CN")))
        assertEquals("ja", AppLanguage.shownTag("ja", listOf("fi-FI")))
        assertEquals("en", AppLanguage.shownTag(null, emptyList()))
    }
}
