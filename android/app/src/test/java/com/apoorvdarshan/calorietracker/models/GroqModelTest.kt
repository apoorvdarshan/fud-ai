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

    @Test
    fun groqAcceptsAtMostThreeImagesPerRequest() {
        assertEquals(3, AIProvider.requestImageLimit(AIProvider.GROQ, "qwen/qwen3.8-27b"))
        assertEquals(null, AIProvider.requestImageLimit(AIProvider.GEMINI, "gemini-3.8-flash"))
        assertEquals(null, AIProvider.requestImageLimit(AIProvider.OPENROUTER, "qwen/qwen3.8-27b"))
    }

    @Test
    fun mealPhotoLimitIncludesTheImageFallback() {
        assertEquals(10, AIProvider.mealPhotoLimit(null, null))
        assertEquals(3, AIProvider.mealPhotoLimit(null, 3))
        assertEquals(3, AIProvider.mealPhotoLimit(3, null))
        assertEquals(3, AIProvider.mealPhotoLimit(3, 5))
    }
