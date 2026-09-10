package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.data.ExerciseItem
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import java.time.LocalDate

class WorkoutTextDraftTest {
    private val bench = ExerciseItem("Bench", "Bench press", "", emptyList(), "", "", "strength", "", emptyList(), emptyList(), emptyList())
    private val library = listOf(bench)
    private val today = LocalDate.of(2026, 9, 10)
    private val json = """{"date":"2026-09-09","exercises":[{"exercise_id":"Bench","name":"invented display name","minutes":null,"unit":"lbs","sets":[{"weight":40.5,"reps":10},{"weight":null,"reps":8}]},{"exercise_id":null,"name":"Soccer","minutes":180,"unit":"kg","sets":[]}]}"""

    @Test fun clarificationCarriesChoicesAndNeverCreatesADraft() {
        val error = runCatching { WorkoutTextDraft.parse(
            """{"question":"Which equipment?","options":["Barbell","Dumbbells","Machine","Barbell"],"exercises":[]}""", library, today)
        }.exceptionOrNull() as WorkoutClarification
        assertEquals(listOf("Barbell", "Dumbbells", "Machine"), error.options)
        assertEquals("Which equipment?", error.message)
    }

    @Test fun conversationPreservesOriginalAndAllRepliesAcrossRetries() {
        val original = "Bench press, 2 sets of 10"
        val first = WorkoutConversation(original).answering("Equipment?", "Dumbbells")
        val second = first.answering("Weight?", "20 kg")
        assertEquals(original, second.original)
        assertEquals(listOf("Dumbbells", "20 kg"), second.turns.map { it.answer })
        val context = kotlinx.serialization.json.Json.parseToJsonElement(second.requestDescription()).jsonObject
        assertEquals(original, context.getValue("original_workout").jsonPrimitive.content)
        assertEquals(2, context.getValue("follow_ups").jsonArray.size)
        assertEquals(original, WorkoutConversation(original).requestDescription())
        assertTrue(runCatching { first.answering("Weight?", " ") }.isFailure)
        assertTrue(runCatching { first.answering("Weight?", "x".repeat(501)) }.isFailure)
    }

    @Test fun searchUsesAIQueriesAndEquipmentMetadata() {
        val calf = bench.copy(id = "Standing_Calf_Raises", name = "Standing Calf Raises", equipment = "machine")
        val queries = WorkoutTextDraft.searchQueries("""{"queries":["calf raise machine"]}""", "calf raise machien")
        val prompt = WorkoutTextDraft.prompt("calf raise machien 3set 20 reps rpe 6 both", today, WorkoutWeightUnit.KG, listOf(calf), queries)
        assertTrue(prompt.contains("Standing_Calf_Raises | Standing Calf Raises | equipment: machine"))
        assertEquals(listOf("original"), WorkoutTextDraft.searchQueries("bad JSON", "original"))
    }

    @Test fun preservesRpeAndRejectsInvalidRpe() {
        val response = json.replace("\"reps\":10", "\"reps\":10,\"rpe\":6")
        val set = WorkoutTextDraft.parse(response, library, today).planned(library, today).first().sets.first()
        assertEquals("6.0", set.rpe)
        assertEquals(WorkoutRpeScale.STRENGTH, set.rpeScale)
        assertTrue(runCatching { WorkoutTextDraft.parse(response.replace("\"rpe\":6", "\"rpe\":11"), library, today) }.isFailure)
    }

    @Test fun unresolvedStrengthAsksForVariationInsteadOfDuration() {
        val draft = WorkoutTextDraft(today.toString(), listOf(WorkoutTextExercise(exerciseId = null,
            name = "Calf raise machine", sets = listOf(WorkoutTextSet(reps = "20", rpe = "6")))))
        val error = runCatching { draft.planned(library, today) }.exceptionOrNull() as WorkoutClarification
        assertEquals("Which variation of Calf raise machine did you do?", error.message)
        assertEquals(listOf("Barbell", "Dumbbells", "Machine"), error.options)
        assertFalse(error.message!!.contains("duration"))
    }

    @Test fun unresolvedStrengthDraftBecomesClarificationNotARedError() {
        val json = """{"date":"2026-09-09","exercises":[{"exercise_id":null,"name":"Bench press","minutes":null,"unit":"kg","sets":[{"weight":null,"reps":10},{"weight":null,"reps":10}]}]}"""
        val error = runCatching { WorkoutTextDraft.parse(json, library, today) }.exceptionOrNull() as WorkoutClarification
        assertTrue(error.message!!.contains("Bench press"))
        assertEquals(listOf("Barbell", "Dumbbells", "Machine"), error.options)
    }

    @Test fun candidateCatalogPrioritizesNamedExercisesAndBoundsContext() {
        val many = (1..100).map { bench.copy(id = "Squat_$it", name = "Squat $it") } + bench
        val candidates = WorkoutTextDraft.candidates("bench press 3 sets of 10", many)
        assertEquals(bench, candidates.first())
        assertEquals(60, candidates.size)
    }

    @Test fun timedEffortIsPreservedAndInvalidEffortRejected() {
        val draft = WorkoutTextDraft.parse(json, library, today)
        val vigorous = draft.copy(exercises = listOf(draft.exercises.last().copy(intensity = "vigorous")))
        assertEquals(WorkoutIntensity.VIGOROUS, vigorous.planned(library, today).single().timer!!.intensity)
        assertTrue(runCatching { draft.copy(exercises = listOf(draft.exercises.last().copy(intensity = "unknown"))).planned(library, today) }.isFailure)
    }

    @Test fun parsesMixedWorkoutPreservingDateUnitsAndSavedDuration() {
        val draft = WorkoutTextDraft.parse("```json\n$json\n```", library, today)
        val planned = draft.planned(library, today)
        assertEquals("2026-09-09", draft.date)
        assertEquals("Bench press", planned[0].name)
        assertEquals("40.5", planned[0].sets[0].weight)
        assertEquals(WorkoutWeightUnit.LBS, planned[0].sets[0].weightUnit)
        assertEquals("", planned[0].sets[1].weight)
        assertEquals(10800.0, planned[1].timer!!.savedSeconds, 0.01)
        assertFalse(planned[1].timer!!.isRunning)
        assertTrue(planned[1].isCardio)
        assertEquals(planned.map { it.id }, draft.planned(library, today).map { it.id })
    }

    @Test fun rejectsUnknownIdsInvalidDatesAndImpossibleValues() {
        listOf(
            json.replace("\"Bench\"", "\"fake\""), json.replace("2026-09-09", "2026-09-11"),
            json.replace("2026-09-09", "2026-02-30"), json.replace("180", "-20"),
            json.replace("180", "1441"), json.replace("40.5", "-1"),
            json.replace("\"reps\":10", "\"reps\":0"), json.replace("\"lbs\"", "\"stone\""),
            json.replace("180", "null"), "{}", "not json"
        ).forEach { value ->
            assertTrue(value, runCatching { WorkoutTextDraft.parse(value, library, today) }.isFailure)
        }
    }

    @Test fun clarificationDoesNotBecomeAWorkout() {
        val result = runCatching { WorkoutTextDraft.parse("""{"question":"How many minutes?","exercises":[]}""", library, today) }
        assertEquals("How many minutes?", result.exceptionOrNull()?.message)
    }

    @Test fun editedDraftIsValidatedAgainBeforeSaving() {
        val draft = WorkoutTextDraft.parse(json, library, today)
        assertTrue(runCatching { draft.copy(exercises = emptyList()).planned(library, today) }.isFailure)
        val invalid = draft.exercises.first().copy(sets = listOf(WorkoutTextSet(reps = "NaN")))
        assertTrue(runCatching { draft.copy(exercises = listOf(invalid)).planned(library, today) }.isFailure)
    }
}
