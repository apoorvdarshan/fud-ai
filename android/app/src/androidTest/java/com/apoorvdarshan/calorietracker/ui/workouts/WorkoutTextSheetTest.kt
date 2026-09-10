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
import kotlinx.serialization.json.*

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

    @Test fun choiceAndTypedReplyPreserveOriginalAndContinueToPreview() {
        val bench = com.apoorvdarshan.calorietracker.data.ExerciseItem("Bench", "Bench press", "", emptyList(), "", "", "strength", "barbell", emptyList(), emptyList(), emptyList())
        val requests = mutableListOf<String>()
        val original = "Bench press, 2 sets of 10"
        compose.setContent {
            MaterialTheme {
                WorkoutTextSheet(container, listOf(bench), LocalDate.now(), WorkoutWeightUnit.KG, 70.0, WorkoutRpeScale.STRENGTH,
                    onAdded = {}, onDismiss = {}, initialDescription = original, analyzeOnOpen = true,
                    analyzeWorkout = {
                        requests.add(it)
                        when (requests.size) {
                            1 -> throw WorkoutClarification("Which equipment?", listOf("Barbell", "Dumbbells", "Machine"))
                            2 -> throw WorkoutClarification("What weight?")
                            else -> WorkoutTextDraft(LocalDate.now().toString(), listOf(WorkoutTextExercise(exerciseId = "Bench", name = "Bench press", sets = List(2) { WorkoutTextSet(weight = "40", reps = "10") })))
                        }
                    }, saveWorkout = { error("Must wait for Add") })
            }
        }
        compose.waitUntil(5000) { requests.size == 1 }
        compose.onNodeWithText(original).assertExists()
        compose.onNodeWithText("Barbell").performScrollTo().performClick()
        compose.waitUntil(5000) { requests.size == 2 }
        compose.onNodeWithText("Your answer").performScrollTo().performTextInput("40 kg")
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.waitUntil(5000) { requests.size == 3 }
        compose.onNodeWithText("Review workout").assertExists()
        val context = kotlinx.serialization.json.Json.parseToJsonElement(requests.last()).jsonObject
        assertEquals(original, context.getValue("original_workout").jsonPrimitive.content)
        val replies = context.getValue("follow_ups").jsonArray.map { it.jsonObject.getValue("answer").jsonPrimitive.content }
        assertEquals(listOf("Barbell", "40 kg"), replies)
    }

    @Test fun networkRetryKeepsTheAnswerWithoutDuplicatingIt() {
        val requests = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                WorkoutTextSheet(container, emptyList(), LocalDate.now(), WorkoutWeightUnit.KG, 70.0, WorkoutRpeScale.STRENGTH,
                    onAdded = {}, onDismiss = {}, initialDescription = "Bench press, 2 sets of 10", analyzeOnOpen = true,
                    analyzeWorkout = {
                        requests.add(it)
                        if (requests.size == 1) throw WorkoutClarification("Which equipment?", listOf("Barbell"))
                        error("Network unavailable")
                    }, saveWorkout = { error("Must not save") })
            }
        }
        compose.onNodeWithText("Barbell").performScrollTo().performClick()
        compose.waitUntil(5000) { requests.size == 2 }
        compose.onNodeWithText("Network unavailable").performScrollTo().assertExists()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.waitUntil(5000) { requests.size == 3 }
        assertEquals(requests[1], requests[2])
    }

    @Test fun startOverClearsQuestionAndOriginalWithoutSaving() {
        compose.setContent {
            MaterialTheme {
                WorkoutTextSheet(container, emptyList(), LocalDate.now(), WorkoutWeightUnit.KG, 70.0, WorkoutRpeScale.STRENGTH,
                    onAdded = {}, onDismiss = {}, initialDescription = "Bench press, 2 sets of 10", analyzeOnOpen = true,
                    analyzeWorkout = { throw WorkoutClarification("Which equipment?", listOf("Barbell")) },
                    saveWorkout = { error("Must not save") })
            }
        }
        compose.onNodeWithText("Which equipment?").assertExists()
        compose.onNodeWithText("Start over").performScrollTo().performClick()
        compose.onNodeWithText("Which equipment?").assertDoesNotExist()
        compose.onNodeWithText("Bench press, 2 sets of 10").assertDoesNotExist()
        compose.onNodeWithText("Analyze").assertIsNotEnabled()
    }

    @Test fun missingRepsShowsAnswerFieldNotOnlyRetry() {
        compose.setContent {
            MaterialTheme {
                WorkoutTextSheet(container, emptyList(), LocalDate.now(), WorkoutWeightUnit.KG, 70.0, WorkoutRpeScale.STRENGTH,
                    onAdded = {}, onDismiss = {}, initialDescription = "Add Pullup", analyzeOnOpen = true,
                    analyzeWorkout = { throw WorkoutClarification("How many sets and reps of Wide-Grip Rear Pull-Up did you do?", listOf("3x10", "3x8", "3x12")) },
                    saveWorkout = { error("Must not save") })
            }
        }
        compose.onNodeWithText("Add Pullup").assertExists()
        compose.onNodeWithText("How many sets and reps of Wide-Grip Rear Pull-Up did you do?").assertExists()
        compose.onNodeWithText("Your answer").assertExists()
        compose.onNodeWithText("3x10").assertExists()
        compose.onNodeWithText("Enter 1–999 reps for each set.").assertDoesNotExist()
        compose.onNodeWithText("Add to diary").assertDoesNotExist()
    }

    @Test fun clarificationKeepsDescriptionAndDoesNotSave() {
        compose.setContent {
            MaterialTheme {
                WorkoutTextSheet(container, emptyList(), LocalDate.now(), WorkoutWeightUnit.KG, 70.0, WorkoutRpeScale.STRENGTH,
                    onAdded = {}, onDismiss = {}, analyzeWorkout = { throw WorkoutClarification("How many minutes?") },
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
