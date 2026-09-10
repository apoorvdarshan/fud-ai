package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.apoorvdarshan.calorietracker.FudAIApp
import com.apoorvdarshan.calorietracker.models.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Fake inference, real UI: no provider calls and no changes to the user's diary. */
class WorkoutTextSheetTest {
    @get:Rule val compose = createComposeRule()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FudAIApp).container

    @Test fun descriptionPreviewEditAndExplicitAdd() {
        var saved: WorkoutTextDraft? = null
        var submitted = ""
        val day = LocalDate.now()
        compose.setContent {
            MaterialTheme {
                WorkoutTextSheet(container, emptyList(), day, WorkoutWeightUnit.KG, 70.0, WorkoutRpeScale.STRENGTH,
                    onAdded = {}, onDismiss = {}, analyzeWorkout = {
                        submitted = it
                        WorkoutTextDraft(day.toString(), listOf(WorkoutTextExercise(exerciseId = null, name = "Soccer", minutes = "180")))
                    }, saveWorkout = { saved = it })
            }
        }
        compose.onNodeWithText("Workout description").performTextInput("3 hours of soccer")
        compose.onNodeWithText("Analyze").performScrollTo().performClick()
        compose.waitUntil { submitted.isNotEmpty() }
        compose.onNodeWithText("Review workout").assertExists()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Minutes").performScrollTo().performTextReplacement("20")
        compose.onNodeWithText("Add to diary").performScrollTo().performClick()
        compose.waitUntil { saved != null }
        assertEquals("20", saved!!.exercises.single().minutes)
        assertEquals("3 hours of soccer", submitted)
    }

    @Test fun voiceTranscriptStartsAnalysisWithoutSaving() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                WorkoutTextSheet(container, emptyList(), LocalDate.now(), WorkoutWeightUnit.KG, 70.0, WorkoutRpeScale.STRENGTH,
                    onAdded = {}, onDismiss = {}, initialDescription = "20 minutes soccer", analyzeOnOpen = true,
                    analyzeWorkout = { calls++; assertEquals("20 minutes soccer", it)
                        WorkoutTextDraft(LocalDate.now().toString(), listOf(WorkoutTextExercise(exerciseId = null, name = "Soccer", minutes = "20")))
                    }, saveWorkout = { error("Must wait for review") })
            }
        }
        compose.waitUntil { calls == 1 }
        compose.onNodeWithText("Review workout").assertExists()
        compose.onNodeWithText("Voice").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun clarificationKeepsDescriptionAndDoesNotSave() {
        compose.setContent {
            MaterialTheme {
                WorkoutTextSheet(container, emptyList(), LocalDate.now(), WorkoutWeightUnit.KG, 70.0, WorkoutRpeScale.STRENGTH,
                    onAdded = {}, onDismiss = {}, analyzeWorkout = { error("How many minutes?") },
                    saveWorkout = { error("Must not save") })
            }
        }
        compose.onNodeWithText("Workout description").performTextInput("soccer")
        compose.onNodeWithText("Analyze").performScrollTo().performClick()
        compose.onNodeWithText("How many minutes?").performScrollTo().assertExists()
        compose.onNodeWithText("soccer").assertExists()
        compose.onNodeWithText("Add to diary").assertDoesNotExist()
    }
}
