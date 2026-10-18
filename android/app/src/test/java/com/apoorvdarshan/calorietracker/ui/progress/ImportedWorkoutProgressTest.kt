package com.apoorvdarshan.calorietracker.ui.progress

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.apoorvdarshan.calorietracker.models.ImportedHealthWorkout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Progress chart math for Health Connect workouts (port of iOS ImportedHealthWorkoutAggregation). */
class ImportedWorkoutProgressTest {
    @Test
    fun dailyAggregationGroupsSessionsByDiaryDayInsideTheRange() {
        val days = importedWorkoutDays(
            listOf(
                workout("walk", "2026-10-09", calories = 100),
                workout("run", "2026-10-09", calories = 200),
                workout("yoga", "2026-10-10", calories = null),
                workout("outside", "2026-10-12", calories = 500)
            ),
            start = LocalDate.parse("2026-10-08"),
            end = LocalDate.parse("2026-10-10")
        )

        assertEquals(
            listOf(
                ImportedWorkoutDay(LocalDate.parse("2026-10-09"), sessionCount = 2, totalCalories = 300),
                ImportedWorkoutDay(LocalDate.parse("2026-10-10"), sessionCount = 1, totalCalories = 0)
            ),
            days
        )
    }

    @Test
    fun averageIgnoresDaysWhoseAppsRecordedNoCalories() {
        val summary = importedWorkoutSummary(
            listOf(
                ImportedWorkoutDay(LocalDate.parse("2026-10-09"), sessionCount = 2, totalCalories = 300),
                ImportedWorkoutDay(LocalDate.parse("2026-10-10"), sessionCount = 1, totalCalories = 0)
            )
        )

        assertEquals(ImportedWorkoutSummary(sessions = 3, totalCalories = 300, averageCalories = 300), summary)
        assertEquals(ImportedWorkoutSummary(0, 0, 0), importedWorkoutSummary(emptyList()))
    }

    private fun workout(id: String, day: String, calories: Int?) = ImportedHealthWorkout(
        id = id,
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        startedAt = Instant.parse("${day}T06:00:00Z"),
        endedAt = Instant.parse("${day}T06:30:00Z"),
        durationSeconds = 1_800,
        activeCalories = calories,
        sourcePackage = "com.strava",
        diaryDateKey = day
    )
}
