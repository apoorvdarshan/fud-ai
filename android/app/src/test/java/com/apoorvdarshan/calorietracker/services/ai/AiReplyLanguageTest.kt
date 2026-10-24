package com.apoorvdarshan.calorietracker.services.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReplyLanguageTest {
    @Test
    fun englishAddsNothing() {
        assertNull(AiReplyLanguage.instruction("en"))
        assertNull(AiReplyLanguage.instruction("en-US"))
        assertEquals("Analyze this image.", AiReplyLanguage.prompt("Analyze this image.", AiReplyLanguage.instruction("en")))
        assertEquals("English", AiReplyLanguage.plainLanguageName("en"))
    }

    @Test
    fun czechNamesTheLanguageAndKeepsIdentifiersEnglish() {
        val instruction = AiReplyLanguage.instruction("cs")!!
        assertTrue(instruction, instruction.contains("in Czech"))
        assertTrue(instruction, instruction.contains("exercise_id"))
        assertTrue(instruction, instruction.contains("intensity"))
        assertTrue(instruction, instruction.contains("in English"))
        assertEquals("$instruction\n\nAnalyze this image.", AiReplyLanguage.prompt("Analyze this image.", instruction))
        assertEquals("Czech", AiReplyLanguage.plainLanguageName("cs"))
    }

    @Test
    fun regionalTagsUseTheFullLanguageName() {
        assertTrue(AiReplyLanguage.instruction("pt-BR")!!.contains("Portuguese (Brazil)"))
        assertEquals("Chinese (China)", AiReplyLanguage.plainLanguageName("zh-CN"))
    }
}
