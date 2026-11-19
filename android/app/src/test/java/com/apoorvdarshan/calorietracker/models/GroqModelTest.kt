package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GroqModelTest {
    @Test
    fun retiredQwenModelResolvesToItsReplacementOnBothRoutes() {
        val retired = "qwen/qwen3.6-27b"
        val replacement = "qwen/qwen3.8-27b"
        assertEquals(replacement, AIProvider.GROQ.supportedModelOrDefault(retired))
        assertEquals(replacement, AIProvider.GROQ.supportedTextModelOrDefault(retired))
        assertFalse(retired in AIProvider.GROQ.textModels)
    }
}
