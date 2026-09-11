package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.data.ExerciseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkoutModelsTest {
    @Test
    fun preferencesDefaultToFullBodyAndMigrateLegacyCustom() {
        val defaults = WorkoutPreferences()
        val migrated = WorkoutPreferences(
            split = WorkoutSplit.CUSTOM,
            customSplit = "Chest + back / Legs",
            frequencyDays = 9,
            strength = WorkoutStrengthNumbers(squatKg = -5.0)
        ).sanitized()

        assertEquals(WorkoutSplit.FULL_BODY, defaults.split)
        assertEquals(WorkoutRpeScale.STRENGTH, defaults.rpeScale)
        assertEquals(WorkoutSplit.FULL_BODY, migrated.split)
        assertEquals("", migrated.customSplit)
        assertEquals(7, migrated.frequencyDays)
        assertNull(migrated.strength.squatKg)
        assertTrue(WorkoutSplit.CUSTOM !in WorkoutSplit.SelectableValues)
    }

    @Test
    fun rpeSanitizationPreservesTypingAndEnforcesEachScale() {
        assertEquals("7.", WorkoutRpeScale.STRENGTH.sanitize("7."))
        assertEquals("8.6", WorkoutRpeScale.CR10.sanitize("8,67"))
        assertEquals("18", WorkoutRpeScale.BORG.sanitize("18"))
        assertEquals("20", WorkoutRpeScale.BORG.sanitize("99", previousValue = "18"))
        assertEquals("20", WorkoutRpeScale.BORG.sanitize("5", previousValue = "20"))
    }

    @Test
    fun plannedWeightDisplayConvertsWithoutMutatingStoredValue() {
        val set = PlannedSet(weight = "100", weightUnit = WorkoutWeightUnit.KG)

        assertEquals("220.46", set.displayWeight(WorkoutWeightUnit.LBS))
        assertEquals("100", set.weight)
        assertEquals(WorkoutWeightUnit.KG, set.weightUnit)
    }

    @Test
    fun estimatorRequiresPositiveRepsAndRespondsToEffortAndLoad() {
        val blank = exercise(PlannedSet())
        assertNull(
            WorkoutBurnEstimator.estimate(
                exercises = listOf(blank),
                bodyWeightKg = 75.0,
                defaultWeightUnit = WorkoutWeightUnit.KG,
                defaultRpeScale = WorkoutRpeScale.STRENGTH
            )
        )

        val easier = WorkoutBurnEstimator.estimate(
            exercises = listOf(exercise(PlannedSet(weight = "8", weightUnit = WorkoutWeightUnit.KG, reps = "12", rpe = "3"))),
            bodyWeightKg = 75.0,
            defaultWeightUnit = WorkoutWeightUnit.KG,
            defaultRpeScale = WorkoutRpeScale.STRENGTH
        )!!
        val harder = WorkoutBurnEstimator.estimate(
            exercises = listOf(exercise(PlannedSet(weight = "40", weightUnit = WorkoutWeightUnit.KG, reps = "12", rpe = "10"))),
            bodyWeightKg = 75.0,
            defaultWeightUnit = WorkoutWeightUnit.KG,
            defaultRpeScale = WorkoutRpeScale.STRENGTH
        )!!

        assertEquals(1, easier.performedSetCount)
        assertEquals(12, easier.repCount)
        assertTrue(harder.calories > easier.calories)
        assertTrue(harder.calories in 1..5_000)
    }

    @Test
    fun splitGroupsMatchPrimaryAndSecondaryCatalogMuscles() {
        val groups = WorkoutSplitGroup.selectionGroups(
            split = WorkoutSplit.PUSH_PULL_LEGS,
            availablePrimaryMuscles = listOf("Chest", "Lats", "Quadriceps", "Abdominals"),
            availableSecondaryMuscles = listOf("Shoulders", "Biceps", "Hamstrings")
        )

        assertEquals(listOf("Push", "Pull", "Legs", "Core"), groups.map { it.title })
        assertEquals(setOf("Chest", "Shoulders"), groups.first { it.title == "Push" }.muscles)
        assertEquals(setOf("Biceps", "Lats"), groups.first { it.title == "Pull" }.muscles)
    }

    @Test
    fun timerPersistsRunningAnchorAndExcludesPausedTime() {
        val start = Instant.parse("2026-09-08T10:00:00Z")
        val running = ExerciseTimer().start(start)
        val restored = Json.decodeFromString<ExerciseTimer>(Json.encodeToString(running))
        assertEquals(600.0, restored.elapsedSeconds(start.plusSeconds(600)), 0.001)
        val paused = restored.pause(start.plusSeconds(90))
        assertEquals(90.0, paused.elapsedSeconds(start.plusSeconds(900)), 0.001)
        val resumed = paused.start(start.plusSeconds(900))
        val saved = resumed.stop(start.plusSeconds(960))
        assertEquals(150.0, saved.savedSeconds, 0.001)
        assertTrue(saved.isSaved)
        assertEquals(150.0, saved.elapsedSeconds(start.plusSeconds(2_000)), 0.001)
        val extended = saved.start(start.plusSeconds(2_000)).stop(start.plusSeconds(2_010))
        assertEquals(160.0, extended.savedSeconds, 0.001)
        val restarted = saved.restart(start.plusSeconds(2_000))
        assertEquals(0.0, restarted.savedSeconds, 0.001)
        assertTrue(restarted.isRunning)
        assertEquals(10.0, restarted.elapsedSeconds(start.plusSeconds(2_010)), 0.001)
        assertEquals(0.0, running.elapsedSeconds(start.minusSeconds(60)), 0.001)
        assertTrue(!running.stop(start).isSaved)
        assertTrue(!ExerciseTimer(savedDurationSeconds = Double.POSITIVE_INFINITY).isSaved)
        assertEquals(0.0, saved.copy(runningSince = start).savedSeconds, 0.001)
    }

    @Test
    fun oldPlansDecodeAndCopiedPlansClearOnlyTheirTimer() {
        val untimed = exercise(PlannedSet(reps = "10"))
        val oldJson = Json.encodeToString(untimed)
        assertTrue(!oldJson.contains("timer"))
        assertNull(Json.decodeFromString<PlannedExercise>(oldJson).timer)
        val timed = untimed.copy(timer = ExerciseTimer(accumulatedSeconds = 120.0, savedDurationSeconds = 120.0))
        val restored = Json.decodeFromString<PlannedExercise>(Json.encodeToString(timed))
        assertEquals(120.0, restored.timer!!.savedSeconds, 0.001)
        assertNull(restored.copiedForNewDay().timer)
        assertEquals(120.0, timed.timer!!.savedSeconds, 0.001)
    }

    @Test
    fun savedCardioTimerCalculatesWithoutRepsAndDoesNotCountRepsTwice() {
        val cardio = exercise(PlannedSet()).copy(
            itemId = "Running_Treadmill",
            category = "cardio",
            timer = ExerciseTimer(accumulatedSeconds = 1_800.0, savedDurationSeconds = 1_800.0)
        )
        val moderate = estimate(listOf(cardio))!!
        assertEquals(312, moderate.calories)
        assertEquals(0, moderate.performedSetCount)
        val withReps = estimate(listOf(cardio.copy(sets = listOf(PlannedSet(reps = "10")))))!!
        assertEquals(moderate.calories, withReps.calories)
        assertEquals(10, withReps.repCount)
        assertEquals(1, withReps.performedSetCount)
        val vigorous = estimate(listOf(cardio.copy(timer = cardio.timer!!.copy(intensity = WorkoutIntensity.VIGOROUS))))!!
        assertTrue(vigorous.calories > moderate.calories)
        val running = cardio.copy(timer = ExerciseTimer().start(Instant.now()))
        val paused = cardio.copy(timer = ExerciseTimer(accumulatedSeconds = 1_800.0))
        assertNull(estimate(listOf(running)))
        assertNull(estimate(listOf(paused)))
        assertTrue(cardio.hasCalculableWork)
        assertTrue(!running.hasCalculableWork)
    }

    @Test
    fun mixedTimedCardioAndUntimedStrengthRetainLegacyStrengthEstimate() {
        val strength = exercise(PlannedSet(reps = "10", weight = "20", rpe = "7"))
        val cardio = exercise(PlannedSet()).copy(
            itemId = "Walking_Treadmill", category = "cardio",
            timer = ExerciseTimer(accumulatedSeconds = 600.0, savedDurationSeconds = 600.0)
        )
        val strengthOnly = estimate(listOf(strength))!!
        val cardioOnly = estimate(listOf(cardio))!!
        val mixed = estimate(listOf(cardio, strength))!!
        assertEquals((strengthOnly.calories + cardioOnly.calories).toDouble(), mixed.calories.toDouble(), 1.0)
        assertEquals(1, mixed.performedSetCount)
        assertEquals(10, mixed.repCount)
        val confusingName = strength.copy(name = "Bicycling", timer = cardio.timer)
        val plainStrength = strength.copy(timer = cardio.timer)
        assertEquals(estimate(listOf(plainStrength))!!.calories, estimate(listOf(confusingName))!!.calories)
        val legacyCardio = cardio.copy(timer = null, sets = listOf(PlannedSet(reps = "100")))
        assertNull(estimate(listOf(legacyCardio)))
        assertTrue(!legacyCardio.hasCalculableWork)
        assertEquals(strengthOnly.calories, estimate(listOf(legacyCardio, strength))!!.calories)
        val stretching = cardio.copy(
            category = "stretching",
            timer = cardio.timer!!.copy(intensity = WorkoutIntensity.VIGOROUS)
        )
        assertEquals(28, estimate(listOf(stretching))!!.calories)
    }

    @Test
    fun liftHistoryMatchingUsesCatalogIdExclusivelyWhenProvided() {
        assertTrue(
            ExerciseLiftHistory.matches("bench-press", "Bench Press", "bench-press", "Bench Press")
        )
        assertFalse(
            ExerciseLiftHistory.matches("bench-press", "Bench Press", "custom-bench", "Bench Press")
        )
        assertTrue(
            ExerciseLiftHistory.matches("", "Bench Press", "custom-bench", "Bench Press")
        )
    }

    @Test
    fun timedCaloriesUseRpeAcrossScalesAndIgnoreBlankSets() {
        fun timed(rpe: String, scale: WorkoutRpeScale) = exercise(PlannedSet(rpe = rpe, rpeScale = scale)).copy(
            timer = ExerciseTimer(accumulatedSeconds = 600.0, savedDurationSeconds = 600.0)
        )
        val light = timed("3", WorkoutRpeScale.STRENGTH)
        val hard = timed("9", WorkoutRpeScale.STRENGTH)
        assertTrue(estimate(listOf(hard))!!.calories > estimate(listOf(light))!!.calories)
        assertEquals(estimate(listOf(hard)), estimate(listOf(timed("9", WorkoutRpeScale.CR10))))
        assertEquals(estimate(listOf(hard)), estimate(listOf(timed("19", WorkoutRpeScale.BORG))))
        assertEquals(estimate(listOf(hard)), estimate(listOf(hard.copy(sets = hard.sets + PlannedSet()))))
        assertEquals(WorkoutIntensity.MODERATE, WorkoutBurnEstimator.timerIntensity(timed("", WorkoutRpeScale.STRENGTH), WorkoutRpeScale.STRENGTH))
    }

    private fun estimate(exercises: List<PlannedExercise>): WorkoutBurnEstimate? = WorkoutBurnEstimator.estimate(
        exercises, 70.0, WorkoutWeightUnit.KG, WorkoutRpeScale.STRENGTH
    )

    private fun exercise(set: PlannedSet): PlannedExercise = PlannedExercise.from(
        ExerciseItem(
            id = "curl",
            name = "Dumbbell Curl",
            level = "Intermediate",
            imagePaths = emptyList(),
            force = "Pull",
            mechanic = "Isolation",
            category = "Strength",
            equipment = "Dumbbell",
            primaryMuscles = listOf("Biceps"),
            secondaryMuscles = listOf("Forearms"),
            instructions = listOf("Control the repetition.")
        )
    ).copy(sets = listOf(set))
}
