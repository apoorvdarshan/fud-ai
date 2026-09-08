package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WorkoutRpeEditingTest {
    @Test fun existingSetsKeepTheirScaleAfterPreferenceChanges() = runBlocking {
        for ((scale, input) in listOf(WorkoutRpeScale.STRENGTH to "7.5", WorkoutRpeScale.CR10 to "0.5", WorkoutRpeScale.BORG to "15")) {
            val store = object : WorkoutStateStore {
                override val workoutState = MutableStateFlow(WorkoutPersistedState())
                override suspend fun setWorkoutState(state: WorkoutPersistedState) { workoutState.value = state }
                override suspend fun clearWorkoutState() { workoutState.value = WorkoutPersistedState() }
            }
            val repository = WorkoutRepository(store)
            val date = LocalDate.of(2026, 9, 8)
            repository.updatePreferences { it.copy(rpeScale = scale) }
            repository.toggleExercise(ExerciseItem(
                id = "bench", name = "Bench Press", level = "Intermediate", imagePaths = emptyList(),
                force = "Push", mechanic = "Compound", category = "Strength", equipment = "Barbell",
                primaryMuscles = listOf("Chest"), secondaryMuscles = emptyList(), instructions = emptyList()
            ), date)
            val exercise = repository.snapshot().dayPlans.getValue(date.toString()).exercises.single()
            val set = exercise.sets.single()
            repository.updateSet(exercise.id, set.id, date, rpe = input)
            repository.updatePreferences { it.copy(rpeScale = if (scale == WorkoutRpeScale.BORG) WorkoutRpeScale.STRENGTH else WorkoutRpeScale.BORG) }
            repository.updateSet(exercise.id, set.id, date, rpe = input)
            val edited = repository.snapshot().dayPlans.getValue(date.toString()).exercises.single().sets.single()
            assertEquals(input, edited.rpe)
            assertEquals(scale, edited.rpeScale)
            repository.updateSet(exercise.id, set.id, date, rpe = "")
            val blank = repository.snapshot().dayPlans.getValue(date.toString()).exercises.single().sets.single()
            assertEquals("", blank.rpe)
            assertEquals(scale, blank.rpeScale)
        }
    }
}
