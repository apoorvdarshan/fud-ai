package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.CompletedExercise
import com.apoorvdarshan.calorietracker.models.CompletedSet
import com.apoorvdarshan.calorietracker.models.ExerciseTimerAction
import com.apoorvdarshan.calorietracker.models.WorkoutIntensity
import com.apoorvdarshan.calorietracker.models.PlannedSet
import com.apoorvdarshan.calorietracker.models.WorkoutDayPlan
import com.apoorvdarshan.calorietracker.models.WorkoutPersistedState
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.models.WorkoutTabMode
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class WorkoutRepositoryTest {
    @Test
    fun customActivityRemainsAvailableAfterItsSourceEntryIsDeleted() = runBlocking {
        val store = FakeWorkoutStateStore()
        val repository = WorkoutRepository(store)
        val day = LocalDate.of(2026, 9, 9)
        val draft = com.apoorvdarshan.calorietracker.models.WorkoutTextDraft(day.toString(), listOf(
            com.apoorvdarshan.calorietracker.models.WorkoutTextExercise(exerciseId = null, name = "Soccer", minutes = "20")
        ))
        repository.addTextWorkout(draft, emptyList())
        repository.addTextWorkout(draft, emptyList())
        val entry = repository.planNow(day).exercises.single()
        repository.toggleSaved(entry.itemId)
        repository.removeExercise(entry.id, day)
        val state = repository.snapshot()
        assertEquals(1, state.customActivities.size)
        assertEquals("Soccer", state.customActivities.single().asExerciseItem().name)
        assertNull(state.customActivities.single().timer)
        assertTrue(entry.itemId in state.savedExerciseIds)
    }

    @Test
    fun reviewedTextWorkoutAppendsAtomicallyAndRetriesDoNotDuplicate() = runBlocking {
        val store = FakeWorkoutStateStore()
        val repository = WorkoutRepository(store)
        val day = LocalDate.of(2026, 9, 9)
        val item = exerciseItem()
        repository.toggleExercise(item, day)
        val original = repository.planNow(day).exercises.single()
        val draft = com.apoorvdarshan.calorietracker.models.WorkoutTextDraft(day.toString(), listOf(
            com.apoorvdarshan.calorietracker.models.WorkoutTextExercise(exerciseId = item.id, name = item.name,
                unit = "kg", sets = listOf(com.apoorvdarshan.calorietracker.models.WorkoutTextSet(weight = "40", reps = "10")))
        ))
        repository.addTextWorkout(draft, listOf(item))
        repository.addTextWorkout(draft, listOf(item))
        assertEquals(2, repository.planNow(day).exercises.size)
        assertEquals(original, repository.planNow(day).exercises.first())
        val invalid = draft.copy(exercises = listOf(draft.exercises.single().copy(minutes = "-1")))
        assertTrue(runCatching { repository.addTextWorkout(invalid, listOf(item)) }.isFailure)
        assertEquals(2, repository.planNow(day).exercises.size)
    }

    @Test
    fun planEditingSanitizesInputsAddsBlankSetsAndCopiesWithoutDuplicates() = runBlocking {
        val store = FakeWorkoutStateStore()
        val repository = WorkoutRepository(store)
        val source = LocalDate.of(2026, 7, 18)
        val target = LocalDate.of(2026, 7, 19)
        val item = exerciseItem()

        repository.toggleExercise(item, source)
        val sourceExercise = repository.planNow(source).exercises.single()
        val firstSet = sourceExercise.sets.single()
        repository.updateSet(
            exerciseId = sourceExercise.id,
            setId = firstSet.id,
            date = source,
            weight = "82,5 kg",
            weightUnit = WorkoutWeightUnit.KG,
            reps = "8 reps",
            rpe = "7.5"
        )
        repository.setSetCount(99, sourceExercise.id, source)

        val edited = repository.planNow(source).exercises.single()
        assertEquals(12, edited.sets.size)
        assertEquals("82.5", edited.sets.first().weight)
        assertEquals("8", edited.sets.first().reps)
        assertEquals("7.5", edited.sets.first().rpe)
        assertTrue(edited.sets.drop(1).all {
            it.weight.isEmpty() && it.weightUnit == null && it.reps.isEmpty() &&
                it.rpe.isEmpty() && it.rpeScale == null
        })

        repository.toggleExercise(item, target)
        repository.copyPlan(source, target)
        repository.copyPlan(source, target)
        val copied = repository.planNow(target).exercises

        assertEquals(1, copied.size)
        assertNotEquals(edited.id, copied.single().id)
        assertEquals(1, copied.single().sets.size)
        assertEquals("", copied.single().sets.single().weight)
        assertEquals("", copied.single().sets.single().reps)
        assertEquals("", copied.single().sets.single().rpe)
    }

    @Test
    fun copyPlanCanCarrySetDetailsWhenRequested() = runBlocking {
        val store = FakeWorkoutStateStore()
        val repository = WorkoutRepository(store)
        val source = LocalDate.of(2026, 7, 18)
        val target = LocalDate.of(2026, 7, 19)
        val item = exerciseItem()
        repository.toggleExercise(item, source)
        val exercise = repository.planNow(source).exercises.single()
        val set = exercise.sets.single()
        repository.updateSet(exercise.id, set.id, source, "40", WorkoutWeightUnit.KG, "10", "8")
        repository.setSetCount(2, exercise.id, source)

        repository.copyPlan(source, target, includeSetDetails = true)

        val copied = repository.planNow(target).exercises.single()
        assertEquals(2, copied.sets.size)
        assertEquals("40", copied.sets.first().weight)
        assertEquals("10", copied.sets.first().reps)
        assertEquals("8", copied.sets.first().rpe)
        assertNotEquals(exercise.id, copied.id)
        assertNotEquals(set.id, copied.sets.first().id)
    }

    @Test
    fun calculatedBurnUpsertsOneStableDailyVersionedSnapshot() = runBlocking {
        val store = FakeWorkoutStateStore()
        val repository = WorkoutRepository(store)
        val date = LocalDate.of(2026, 7, 19)
        val item = exerciseItem()
        repository.toggleExercise(item, date)
        val exercise = repository.planNow(date).exercises.single()
        repository.updateSet(
            exerciseId = exercise.id,
            setId = exercise.sets.single().id,
            date = date,
            weight = "100",
            weightUnit = WorkoutWeightUnit.KG,
            reps = "8",
            rpe = "8"
        )

        val first = repository.upsertCalculatedWorkout(date, 180, WorkoutWeightUnit.KG)!!
        val second = repository.upsertCalculatedWorkout(date, 225, WorkoutWeightUnit.KG)!!
        val current = repository.snapshot()

        assertEquals(first.id, second.id)
        assertEquals(1, first.healthSyncVersion)
        assertEquals(2, second.healthSyncVersion)
        assertEquals(225, second.caloriesBurned)
        assertEquals(1, current.completedSessions.count { it.caloriesBurned != null })
        assertEquals(1, current.dayPlans.getValue(date.toString()).exercises.size)
    }

    @Test
    fun failedHealthDeleteLeavesTombstoneAndBlocksRestore() = runBlocking {
        val session = burnSession()
        val store = FakeWorkoutStateStore(
            WorkoutPersistedState(completedSessions = listOf(session))
        )
        val health = FakeWorkoutHealthSync(deleteSucceeds = false)
        val repository = WorkoutRepository(store, health)

        repository.deleteSession(session.id)
        repository.importWorkoutBurnSessions(listOf(session.copy(healthSyncVersion = 2)))
        val current = repository.snapshot()

        assertTrue(current.completedSessions.isEmpty())
        assertEquals(session.diaryDateKey, current.healthDeletionTombstones[session.id.toString()])
        assertEquals(listOf(session.id to session.diaryDateKey), health.deleted)
    }

    @Test
    fun reconcileCannotResurrectARecordDeletedDuringStaleHealthRead() = runBlocking {
        val session = burnSession()
        val store = FakeWorkoutStateStore(
            WorkoutPersistedState(completedSessions = listOf(session))
        )
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val health = FakeWorkoutHealthSync(
            owned = listOf(session),
            readStarted = readStarted,
            releaseRead = releaseRead
        )
        val repository = WorkoutRepository(store, health)

        val reconcile = async(Dispatchers.Default) { repository.synchronizeWithHealth() }
        readStarted.await()
        val deletion = async(Dispatchers.Default) { repository.deleteSession(session.id) }
        while (repository.snapshot().healthDeletionTombstones.isEmpty()) {
            kotlinx.coroutines.yield()
        }
        releaseRead.complete(Unit)
        reconcile.await()
        deletion.await()

        val current = repository.snapshot()
        assertTrue(current.completedSessions.isEmpty())
        assertFalse(session.id.toString() in current.pendingHealthUpsertIds)
        assertEquals(listOf(session.id to session.diaryDateKey), health.deleted)
    }

    @Test
    fun newUsersDefaultToDiaryAndLaterLaunchesRestoreTheLastWorkoutView() = runBlocking {
        val store = FakeWorkoutStateStore()
        val firstLaunch = WorkoutRepository(store)

        assertEquals(WorkoutTabMode.LOG, firstLaunch.snapshot().mode)

        firstLaunch.setMode(WorkoutTabMode.LIBRARY)
        val secondLaunch = WorkoutRepository(store)
        assertEquals(WorkoutTabMode.LIBRARY, secondLaunch.snapshot().mode)

        secondLaunch.setMode(WorkoutTabMode.LOG)
        val thirdLaunch = WorkoutRepository(store)
        assertEquals(WorkoutTabMode.LOG, thirdLaunch.snapshot().mode)
    }

    @Test
    fun modeAndSavedIdsPersistInTheSameState() = runBlocking {
        val repository = WorkoutRepository(FakeWorkoutStateStore())
        repository.setMode(WorkoutTabMode.LIBRARY)
        repository.toggleSaved("bench")

        val current = repository.snapshot()
        assertEquals(WorkoutTabMode.LIBRARY, current.mode)
        assertEquals(setOf("bench"), current.savedExerciseIds)
        assertNull(current.completedSessions.firstOrNull())
    }

    @Test
    fun exerciseTimersRemainIndependentAcrossExerciseDayAndRepositoryRestart() = runBlocking {
        val store = FakeWorkoutStateStore()
        var repository = WorkoutRepository(store)
        val date = LocalDate.of(2026, 9, 8)
        val previous = date.minusDays(1)
        val item = exerciseItem()
        repository.toggleExercise(item, date)
        repository.toggleExercise(item.copy(id = "run", name = "Running", category = "cardio"), date)
        repository.toggleExercise(item, previous)
        val exercises = repository.planNow(date).exercises
        val startedAt = Instant.parse("2026-09-08T10:00:00Z")
        repository.updateTimer(exercises[0].id, date, ExerciseTimerAction.START, startedAt)
        repository.updateTimer(exercises[1].id, date, ExerciseTimerAction.START, startedAt.plusSeconds(30))
        repository = WorkoutRepository(store)
        repository.updateTimer(exercises[0].id, date, ExerciseTimerAction.PAUSE, startedAt.plusSeconds(60))
        assertEquals(60.0, repository.planNow(date).exercises[0].timer!!.elapsedSeconds(startedAt.plusSeconds(300)), 0.001)
        assertEquals(270.0, repository.planNow(date).exercises[1].timer!!.elapsedSeconds(startedAt.plusSeconds(300)), 0.001)
        assertNull(repository.planNow(previous).exercises.single().timer)
        repository.updateTimer(exercises[0].id, date, ExerciseTimerAction.RESUME, startedAt.plusSeconds(300))
        repository.updateTimer(exercises[0].id, date, ExerciseTimerAction.STOP, startedAt.plusSeconds(360))
        repository.copyPlan(date, date.plusDays(1))
        assertTrue(repository.planNow(date.plusDays(1)).exercises.all { it.timer == null })
        assertEquals(120.0, repository.planNow(date).exercises[0].timer!!.savedSeconds, 0.001)
        repository.updateTimer(exercises[0].id, date, ExerciseTimerAction.DISCARD)
        assertNull(repository.planNow(date).exercises[0].timer)
        assertTrue(repository.planNow(date).exercises[1].timer!!.isRunning)
        assertEquals(2, repository.planNow(date).exercises.size)
    }

    @Test
    fun logQuickCardioAppendsSavedTimedEntry() = runBlocking {
        val repository = WorkoutRepository(FakeWorkoutStateStore())
        val date = LocalDate.of(2026, 9, 8)
        repository.logQuickCardio(
            exerciseItem().copy(id = "Running_Outdoor", name = "Running", category = "cardio"),
            minutes = 45,
            date = date
        )
        val exercise = repository.planNow(date).exercises.single()
        assertEquals("Running_Outdoor", exercise.itemId)
        assertEquals(2_700.0, exercise.timer!!.savedSeconds, 0.001)
        assertTrue(exercise.sets.isEmpty())
    }

    @Test
    fun savedTimerBurnUpsertsOneDailySnapshotWithDurationAndSurvivesHealthRestore() = runBlocking {
        val store = FakeWorkoutStateStore()
        val repository = WorkoutRepository(store)
        val date = LocalDate.of(2026, 9, 8)
        val startedAt = Instant.parse("2026-09-08T10:00:00Z")
        repository.toggleExercise(exerciseItem().copy(id = "Running_Treadmill", category = "cardio"), date)
        val exercise = repository.planNow(date).exercises.single()
        repository.updateTimer(exercise.id, date, ExerciseTimerAction.START, startedAt)
        assertNull(repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG))
        repository.updateTimer(exercise.id, date, ExerciseTimerAction.STOP, startedAt.plusSeconds(1_800))
        val first = repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG, startedAt.plusSeconds(1_800))!!
        val repeated = repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG, startedAt.plusSeconds(1_801))!!
        assertEquals(first.id, repeated.id)
        repository.setTimerIntensity(exercise.id, date, WorkoutIntensity.VIGOROUS)
        assertTrue(repository.snapshot().completedSessions.isEmpty())
        val second = repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG, startedAt.plusSeconds(1_801))!!
        assertNotEquals(first.id, second.id)
        assertEquals(1, repository.snapshot().completedSessions.size)
        assertEquals(0, second.durationSeconds)
        assertEquals(1_800.0, second.exercises.single().durationSeconds!!, 0.001)
        assertEquals(WorkoutIntensity.VIGOROUS, second.exercises.single().intensity)
        assertTrue(second.caloriesBurned!! > first.caloriesBurned!!)
        repository.importWorkoutBurnSessions(listOf(second.copy(exercises = emptyList(), durationSeconds = 0, healthSyncVersion = 3)))
        val restored = repository.snapshot().completedSessions.single()
        assertEquals(0, restored.durationSeconds)
        assertEquals(1_800.0, restored.exercises.single().durationSeconds!!, 0.001)
    }

    @Test
    fun discardingSavedTimerInvalidatesOnlyThatDaysBurnAndQueuesHealthDeletion() = runBlocking {
        val date = LocalDate.of(2026, 9, 8)
        val previousBurn = burnSession()
        val legacySession = burnSession().copy(diaryDateKey = date.toString(), caloriesBurned = null)
        val store = FakeWorkoutStateStore(WorkoutPersistedState(completedSessions = listOf(previousBurn, legacySession)))
        val health = FakeWorkoutHealthSync(deleteSucceeds = false)
        val repository = WorkoutRepository(store, health)
        repository.toggleExercise(exerciseItem().copy(id = "Running_Treadmill", category = "cardio"), date)
        val exercise = repository.planNow(date).exercises.single()
        val start = Instant.parse("2026-09-08T10:00:00Z")
        repository.updateTimer(exercise.id, date, ExerciseTimerAction.START, start)
        repository.updateTimer(exercise.id, date, ExerciseTimerAction.STOP, start.plusSeconds(600))
        val calculated = repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG)!!
        repository.updateTimer(exercise.id, date, ExerciseTimerAction.DISCARD)
        val state = repository.snapshot()
        assertEquals(setOf(previousBurn.id, legacySession.id), state.completedSessions.map { it.id }.toSet())
        assertEquals(date.toString(), state.healthDeletionTombstones[calculated.id.toString()])
        assertTrue(calculated.id.toString() in state.pendingHealthDeleteIds)
        assertTrue(calculated.id.toString() !in state.pendingHealthUpsertIds)
        assertEquals(listOf(calculated.id to date.toString()), health.deleted)
        assertNull(repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG))
    }

    @Test
    fun unsavedTimerTransitionsPreserveStrengthBurnAndSavedTimerRemovalInvalidatesIt() = runBlocking {
        val repository = WorkoutRepository(FakeWorkoutStateStore())
        val date = LocalDate.of(2026, 9, 8)
        repository.toggleExercise(exerciseItem(), date)
        val strength = repository.planNow(date).exercises.single()
        repository.updateSet(strength.id, strength.sets.single().id, date, reps = "10")
        val strengthBurn = repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG)!!
        repository.toggleExercise(exerciseItem().copy(id = "Running_Treadmill", category = "cardio"), date)
        val cardio = repository.planNow(date).exercises.last()
        val start = Instant.parse("2026-09-08T10:00:00Z")
        repository.updateTimer(cardio.id, date, ExerciseTimerAction.START, start)
        repository.updateTimer(cardio.id, date, ExerciseTimerAction.PAUSE, start.plusSeconds(30))
        repository.setTimerIntensity(cardio.id, date, WorkoutIntensity.VIGOROUS)
        repository.updateTimer(cardio.id, date, ExerciseTimerAction.RESUME, start.plusSeconds(60))
        assertEquals(strengthBurn.id, repository.snapshot().completedSessions.single().id)
        repository.updateTimer(cardio.id, date, ExerciseTimerAction.STOP, start.plusSeconds(90))
        assertTrue(repository.snapshot().completedSessions.isEmpty())
        val mixedBurn = repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG)!!
        repository.removeExercise(cardio.id, date)
        assertTrue(repository.snapshot().completedSessions.isEmpty())
        assertTrue(mixedBurn.id.toString() in repository.snapshot().pendingHealthDeleteIds)
        assertEquals(strengthBurn.caloriesBurned, repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG)!!.caloriesBurned)
    }

    @Test
    fun editingTimedRpeInvalidatesBurnAndLogsDerivedEffort() = runBlocking {
        val repository = WorkoutRepository(FakeWorkoutStateStore())
        val date = LocalDate.of(2026, 9, 8)
        val start = Instant.parse("2026-09-08T10:00:00Z")
        repository.toggleExercise(exerciseItem(), date)
        val exercise = repository.planNow(date).exercises.single()
        repository.updateTimer(exercise.id, date, ExerciseTimerAction.START, start)
        repository.updateTimer(exercise.id, date, ExerciseTimerAction.STOP, start.plusSeconds(600))
        repository.updateSet(exercise.id, exercise.sets[0].id, date, rpe = "3")
        val light = repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG)!!
        repository.updateSet(exercise.id, exercise.sets[0].id, date, rpe = "9")
        assertTrue(repository.snapshot().completedSessions.isEmpty())
        val hard = repository.calculateBurn(date, 70.0, WorkoutWeightUnit.KG)!!
        assertTrue(hard.caloriesBurned!! > light.caloriesBurned!!)
        assertEquals(WorkoutIntensity.VIGOROUS, hard.exercises.single().intensity)
        assertEquals(600.0, hard.exercises.single().durationSeconds!!, 0.001)
    }

    private fun exerciseItem() = ExerciseItem(
        id = "bench",
        name = "Bench Press",
        level = "Intermediate",
        imagePaths = emptyList(),
        force = "Push",
        mechanic = "Compound",
        category = "Strength",
        equipment = "Barbell",
        primaryMuscles = listOf("Chest"),
        secondaryMuscles = listOf("Triceps"),
        instructions = listOf("Control the repetition.")
    )

    private fun burnSession(): WorkoutSession {
        val instant = Instant.parse("2026-07-19T12:00:00Z")
        return WorkoutSession(
            diaryDateKey = "2026-07-19",
            startedAt = instant,
            completedAt = instant,
            exercises = listOf(
                CompletedExercise(
                    itemId = "bench",
                    name = "Bench Press",
                    targetMuscles = listOf("Chest"),
                    equipment = "Barbell",
                    sets = listOf(
                        CompletedSet(
                            setNumber = 1,
                            weight = "100",
                            weightUnit = WorkoutWeightUnit.KG,
                            reps = "5",
                            rpe = "8"
                        )
                    )
                )
            ),
            caloriesBurned = 200,
            healthSyncVersion = 1
        )
    }
}

private class FakeWorkoutStateStore(initial: WorkoutPersistedState = WorkoutPersistedState()) : WorkoutStateStore {
    private val mutable = MutableStateFlow(initial)
    override val workoutState: Flow<WorkoutPersistedState> = mutable

    override suspend fun setWorkoutState(state: WorkoutPersistedState) {
        mutable.value = state
    }

    override suspend fun clearWorkoutState() {
        mutable.value = WorkoutPersistedState()
    }
}

private class FakeWorkoutHealthSync(
    private val deleteSucceeds: Boolean = true,
    private val owned: List<WorkoutSession>? = emptyList(),
    private val readStarted: CompletableDeferred<Unit>? = null,
    private val releaseRead: CompletableDeferred<Unit>? = null
) : WorkoutHealthSync {
    val deleted = mutableListOf<Pair<UUID, String>>()

    override suspend fun upsertBurn(session: WorkoutSession): Boolean = true

    override suspend fun deleteBurn(sessionId: UUID, diaryDateKey: String): Boolean {
        deleted += sessionId to diaryDateKey
        return deleteSucceeds
    }

    override suspend fun readOwnedBurns(): List<WorkoutSession>? {
        readStarted?.complete(Unit)
        releaseRead?.await()
        return owned
    }
}
