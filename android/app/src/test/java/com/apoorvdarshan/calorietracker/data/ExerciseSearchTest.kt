package com.apoorvdarshan.calorietracker.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSearchTest {
    private val tricepsPushdown = ExerciseItem(
        id = "Triceps_Pushdown",
        name = "Triceps Pushdown",
        level = "Beginner",
        imagePaths = emptyList(),
        force = "Push",
        mechanic = "Isolation",
        category = "Strength",
        equipment = "Cable",
        primaryMuscles = listOf("Triceps"),
        secondaryMuscles = emptyList(),
        instructions = listOf("Attach a bar to a high pulley.")
    )

    @Test
    fun contiguousQueryMatches() {
        assertTrue(
            ExerciseSearch.matches(tricepsPushdown.searchableText, "triceps pushdown", tricepsPushdown.id)
        )
    }

    @Test
    fun tokenizedNonContiguousQueryMatches() {
        assertTrue(
            ExerciseSearch.matches(tricepsPushdown.searchableText, "triceps cable pushdown", tricepsPushdown.id)
        )
    }

    @Test
    fun aliasQueryMatches() {
        assertTrue(
            ExerciseSearch.matches(tricepsPushdown.searchableText, "cable pushdown", tricepsPushdown.id)
        )
    }

    @Test
    fun missingTokenFails() {
        assertFalse(
            ExerciseSearch.matches(tricepsPushdown.searchableText, "triceps rope", tricepsPushdown.id)
        )
    }
}
