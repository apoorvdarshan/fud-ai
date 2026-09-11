package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.UserExercise
import com.apoorvdarshan.calorietracker.models.UserExerciseDraft
import com.apoorvdarshan.calorietracker.models.WorkoutPersistedState
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UserExerciseRepositoryTest {
    private class MemoryStore : WorkoutStateStore {
        private val flow = MutableStateFlow(WorkoutPersistedState())
        override val workoutState = flow
        override suspend fun setWorkoutState(state: WorkoutPersistedState) {
            flow.value = state
        }

        override suspend fun clearWorkoutState() {
            flow.value = WorkoutPersistedState()
        }
    }

    @Test
    fun saveUserExercisePersistsTemplate() = runBlocking {
        val store = MemoryStore()
        val repository = WorkoutRepository(store)
        val imageStore = FoodImageStore(RuntimeEnvironment.getApplication())

        val saved = repository.saveUserExercise(
            UserExerciseDraft(
                name = "My Row",
                instructions = "Pull to chest.",
                level = "Beginner",
                category = "Strength",
                primaryMuscles = listOf("Lats")
            ),
            imageStore
        )

        assertTrue(saved != null)
        assertTrue(UserExercise.isUserExercise(saved!!.id))

        val state = store.workoutState.first()
        assertEquals(1, state.userExercises.size)
        assertEquals("My Row", state.userExercises.single().name)
    }
}
