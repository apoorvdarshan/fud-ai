package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun supportedTagsMatchShippedLocales() {
        assertEquals(
            listOf(
                "en", "ar", "az", "cs", "de", "es", "fr", "hi", "it",
                "ja", "ko", "nl", "pl", "pt-BR", "ro", "ru", "uk", "zh-CN"
            ),
            AppLanguage.supportedTags
        )
    }

    @Test
    fun displayNameUsesNativeLocale() {
        assertEquals("Deutsch", AppLanguage.displayName("de"))
        assertEquals("日本語", AppLanguage.displayName("ja"))
        assertTrue(AppLanguage.displayName("pt-BR").contains("Portugu", ignoreCase = true))
    }

    @Test
    fun matchSupportedAcceptsExactAndLanguageOnlyTags() {
        assertNull(AppLanguage.matchSupported(null))
        assertNull(AppLanguage.matchSupported(""))
        assertEquals("de", AppLanguage.matchSupported("de"))
        assertEquals("pt-BR", AppLanguage.matchSupported("pt-BR"))
        assertEquals("zh-CN", AppLanguage.matchSupported("zh-CN"))
        assertEquals("en", AppLanguage.matchSupported("en-US"))
    }
}
