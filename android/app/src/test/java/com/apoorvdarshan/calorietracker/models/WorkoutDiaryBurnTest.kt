package com.apoorvdarshan.calorietracker.models

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutDiaryBurnTest {
    @Test
    fun totalsIncludeKnownImportedCaloriesAndKeepUnknownSeparate() {
        val imports = listOf(workout("a", 250), workout("b", null), workout("c", 100))

        assertEquals(350, workoutDiaryBurnCalories(null, imports))
        assertEquals(550, workoutDiaryBurnCalories(200, imports))
        assertEquals(200, workoutDiaryBurnCalories(200, emptyList()))
        assertNull(workoutDiaryBurnCalories(null, listOf(workout("d", null))))
    }

    private fun workout(id: String, calories: Int?) = ImportedHealthWorkout(
        id = id,
        exerciseType = 0,
        startedAt = Instant.EPOCH,
        endedAt = Instant.EPOCH.plusSeconds(60),
        durationSeconds = 60,
        activeCalories = calories,
        sourcePackage = "example.test",
        diaryDateKey = "1970-01-01"
    )
}
