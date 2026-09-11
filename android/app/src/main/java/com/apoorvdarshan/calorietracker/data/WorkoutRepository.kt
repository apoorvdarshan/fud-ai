package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.CompletedExercise
import com.apoorvdarshan.calorietracker.models.CompletedSet
import com.apoorvdarshan.calorietracker.models.ExerciseLiftDay
import com.apoorvdarshan.calorietracker.models.ExerciseLiftHistory
import com.apoorvdarshan.calorietracker.models.ExerciseLiftSet
import com.apoorvdarshan.calorietracker.models.ExerciseTimer
import com.apoorvdarshan.calorietracker.models.ExerciseTimerAction
import com.apoorvdarshan.calorietracker.models.PlannedExercise
import com.apoorvdarshan.calorietracker.models.PlannedSet
import com.apoorvdarshan.calorietracker.models.WorkoutBurnEstimate
import com.apoorvdarshan.calorietracker.models.WorkoutBurnEstimator
import com.apoorvdarshan.calorietracker.models.WorkoutDate
import com.apoorvdarshan.calorietracker.models.WorkoutDayPlan
import com.apoorvdarshan.calorietracker.models.WorkoutPersistedState
import com.apoorvdarshan.calorietracker.models.WorkoutPreferences
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.models.WorkoutTabMode
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import com.apoorvdarshan.calorietracker.models.WorkoutIntensity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Small persistence seam so repository behavior can be unit-tested without Android DataStore. */
interface WorkoutStateStore {
    val workoutState: Flow<WorkoutPersistedState>
    suspend fun setWorkoutState(state: WorkoutPersistedState)
    suspend fun clearWorkoutState()
}

/**
 * Health Connect boundary. The manager can implement this directly or FudAIApp can provide
 * a tiny adapter. A null read result means the health query was unavailable; an empty list is
 * a successful query containing no app-owned burns.
 */
interface WorkoutHealthSync {
    suspend fun upsertBurn(session: WorkoutSession): Boolean
    suspend fun deleteBurn(sessionId: UUID, diaryDateKey: String): Boolean
    suspend fun readOwnedBurns(): List<WorkoutSession>? = null
}

/** Persistent, local-first workout diary matching the final iOS StrengthWorkoutStore behavior. */
class WorkoutRepository(
    private val store: WorkoutStateStore,
    private val health: WorkoutHealthSync? = null
) {
    private val stateMutex = Mutex()
    private val healthMutex = Mutex()

    val state: Flow<WorkoutPersistedState> = store.workoutState
        .map { it.sanitized() }
        .distinctUntilChanged()

    val mode: Flow<WorkoutTabMode> = state.map { it.mode }.distinctUntilChanged()
    val preferences: Flow<WorkoutPreferences> = state.map { it.preferences }.distinctUntilChanged()
    val dayPlans: Flow<Map<String, WorkoutDayPlan>> = state.map { it.dayPlans }.distinctUntilChanged()
    val completedSessions: Flow<List<WorkoutSession>> = state
        .map { it.completedSessions.sortedWith(sessionDescendingComparator) }
        .distinctUntilChanged()
    val burnSessions: Flow<List<WorkoutSession>> = completedSessions
        .map { sessions -> sessions.filter { it.caloriesBurned != null } }
        .distinctUntilChanged()
    val savedExerciseIds: Flow<Set<String>> = state.map { it.savedExerciseIds }.distinctUntilChanged()

    fun plan(date: LocalDate): Flow<WorkoutDayPlan> = plan(WorkoutDate.key(date))

    fun plan(dateKey: String): Flow<WorkoutDayPlan> {
        val key = WorkoutDate.requireKey(dateKey)
        return state.map { it.dayPlans[key] ?: WorkoutDayPlan(key) }.distinctUntilChanged()
    }

    fun exercises(date: LocalDate): Flow<List<PlannedExercise>> = plan(date).map { it.exercises }
    fun exercises(dateKey: String): Flow<List<PlannedExercise>> = plan(dateKey).map { it.exercises }

    fun caloriesBurned(date: LocalDate): Flow<Int?> = caloriesBurned(WorkoutDate.key(date))

    fun caloriesBurned(dateKey: String): Flow<Int?> {
        val key = WorkoutDate.requireKey(dateKey)
        return state.map { current -> preferredBurn(current, key)?.caloriesBurned }.distinctUntilChanged()
    }

    suspend fun snapshot(): WorkoutPersistedState = store.workoutState.first().sanitized()

    suspend fun planNow(date: LocalDate): WorkoutDayPlan = planNow(WorkoutDate.key(date))

    suspend fun planNow(dateKey: String): WorkoutDayPlan {
        val key = WorkoutDate.requireKey(dateKey)
        return snapshot().dayPlans[key] ?: WorkoutDayPlan(key)
    }

    suspend fun setMode(mode: WorkoutTabMode) {
        updateState { it.copy(mode = mode) }
    }

    /** Append a reviewed batch atomically; stable draft IDs make retries idempotent. */
    suspend fun addTextWorkout(draft: com.apoorvdarshan.calorietracker.models.WorkoutTextDraft, library: List<ExerciseItem>) {
        val additions = draft.planned(library)
        val custom = additions.filter { it.itemId.startsWith("custom_activity_") }
            .map { it.copy(sets = emptyList(), timer = null) }
        updatePlan(draft.date, custom) { plan ->
            val existing = plan.exercises.map { it.id }.toSet()
            plan.copy(exercises = plan.exercises + additions.filterNot { it.id in existing })
        }
    }

    suspend fun toggleExercise(item: ExerciseItem, date: LocalDate) =
        toggleExercise(item, WorkoutDate.key(date))

    suspend fun toggleExercise(item: ExerciseItem, dateKey: String) {
        val key = WorkoutDate.requireKey(dateKey)
        updatePlan(key) { plan ->
            val exercises = plan.exercises.toMutableList()
            val index = exercises.indexOfFirst { it.itemId == item.id }
            if (index >= 0) exercises.removeAt(index) else exercises.add(PlannedExercise.from(item))
            plan.copy(exercises = exercises)
        }
    }

    /** Appends a completed timed cardio entry for quick logging from Home. */
    suspend fun logQuickCardio(
        item: ExerciseItem,
        minutes: Int,
        date: LocalDate,
        intensity: WorkoutIntensity = WorkoutIntensity.MODERATE
    ) {
        if (minutes <= 0) return
        val seconds = minutes * 60.0
        val exercise = PlannedExercise.from(item).copy(
            sets = emptyList(),
            timer = ExerciseTimer(
                accumulatedSeconds = seconds,
                savedDurationSeconds = seconds,
                intensity = intensity
            )
        )
        updatePlan(WorkoutDate.key(date)) { plan ->
            plan.copy(exercises = plan.exercises + exercise)
        }
    }

    suspend fun removeExercise(exerciseId: UUID, date: LocalDate) =
        removeExercise(exerciseId, WorkoutDate.key(date))

    suspend fun removeExercise(exerciseId: UUID, dateKey: String) {
        val key = WorkoutDate.requireKey(dateKey)
        updatePlan(key) { plan ->
            plan.copy(exercises = plan.exercises.filterNot { it.id == exerciseId })
        }
    }

    suspend fun setSetCount(count: Int, exerciseId: UUID, date: LocalDate) =
        setSetCount(count, exerciseId, WorkoutDate.key(date))

    suspend fun setSetCount(count: Int, exerciseId: UUID, dateKey: String) {
        val target = count.coerceIn(1, 12)
        updateExercise(WorkoutDate.requireKey(dateKey), exerciseId) { exercise ->
            val sets = when {
                target > exercise.sets.size -> {
                    val template = exercise.sets.lastOrNull() ?: PlannedSet()
                    exercise.sets + List(target - exercise.sets.size) { template.copyingFromPrevious() }
                }
                target < exercise.sets.size -> exercise.sets.take(target)
                else -> exercise.sets
            }
            exercise.copy(sets = sets)
        }
    }

    suspend fun updateSet(
        exerciseId: UUID,
        setId: UUID,
        date: LocalDate,
        weight: String? = null,
        weightUnit: WorkoutWeightUnit? = null,
        reps: String? = null,
        rpe: String? = null
    ) = updateSet(exerciseId, setId, WorkoutDate.key(date), weight, weightUnit, reps, rpe)

    suspend fun updateSet(
        exerciseId: UUID,
        setId: UUID,
        dateKey: String,
        weight: String? = null,
        weightUnit: WorkoutWeightUnit? = null,
        reps: String? = null,
        rpe: String? = null
    ) {
        val key = WorkoutDate.requireKey(dateKey)
        updatePlanWithPreferences(key) { plan, preferences ->
            val exerciseIndex = plan.exercises.indexOfFirst { it.id == exerciseId }
            if (exerciseIndex < 0) return@updatePlanWithPreferences plan
            val exercise = plan.exercises[exerciseIndex]
            val setIndex = exercise.sets.indexOfFirst { it.id == setId }
            if (setIndex < 0) return@updatePlanWithPreferences plan

            var changedSet = exercise.sets[setIndex]
            if (weight != null) {
                changedSet = changedSet.copy(
                    weight = com.apoorvdarshan.calorietracker.models.WorkoutSetInput.weight(weight),
                    weightUnit = weightUnit ?: changedSet.weightUnit
                )
            }
            if (reps != null) {
                changedSet = changedSet.copy(reps = com.apoorvdarshan.calorietracker.models.WorkoutSetInput.reps(reps))
            }
            if (rpe != null) {
                val scale = changedSet.rpeScale ?: preferences.rpeScale
                changedSet = changedSet.copy(
                    rpe = scale.sanitize(rpe, changedSet.rpe),
                    rpeScale = scale
                )
            }

            val changedSets = exercise.sets.toMutableList().also { it[setIndex] = changedSet }
            val changedExercises = plan.exercises.toMutableList().also {
                it[exerciseIndex] = exercise.copy(sets = changedSets)
            }
            plan.copy(exercises = changedExercises)
        }
    }

    suspend fun toggleSaved(itemId: String) {
        updateState { current ->
            val saved = current.savedExerciseIds.toMutableSet()
            if (!saved.add(itemId)) saved.remove(itemId)
            current.copy(savedExerciseIds = saved)
        }
    }

    suspend fun updateTimer(
        exerciseId: UUID,
        date: LocalDate,
        action: ExerciseTimerAction,
        at: Instant = Instant.now()
    ) {
        updateExercise(WorkoutDate.key(date), exerciseId) { exercise ->
            val timer = exercise.timer ?: ExerciseTimer()
            val updated = when (action) {
                ExerciseTimerAction.START, ExerciseTimerAction.RESUME -> timer.start(at)
                ExerciseTimerAction.PAUSE -> timer.pause(at)
                ExerciseTimerAction.STOP -> timer.stop(at)
                ExerciseTimerAction.RESTART -> timer.restart(at)
                ExerciseTimerAction.DISCARD -> null
            }
            exercise.copy(timer = updated)
        }
    }

    suspend fun setTimerIntensity(exerciseId: UUID, date: LocalDate, intensity: WorkoutIntensity) {
        updateExercise(WorkoutDate.key(date), exerciseId) { exercise ->
            exercise.copy(timer = (exercise.timer ?: ExerciseTimer()).copy(intensity = intensity))
        }
    }

    suspend fun copyPlan(from: LocalDate, to: LocalDate, includeSetDetails: Boolean = false) =
        copyPlan(WorkoutDate.key(from), WorkoutDate.key(to), includeSetDetails)

    suspend fun copyPlan(fromDateKey: String, toDateKey: String, includeSetDetails: Boolean = false) {
        val sourceKey = WorkoutDate.requireKey(fromDateKey)
        val targetKey = WorkoutDate.requireKey(toDateKey)
        stateMutex.withLock {
            val current = store.workoutState.first().sanitized()
            val source = current.dayPlans[sourceKey]?.exercises.orEmpty()
            if (source.isEmpty()) return@withLock
            val target = current.dayPlans[targetKey] ?: WorkoutDayPlan(targetKey)
            val existing = target.exercises.mapTo(mutableSetOf()) { it.itemId }
            val copied = source.filterNot { it.itemId in existing }.map { it.copiedForNewDay(includeSetDetails) }
            if (copied.isEmpty()) return@withLock
            store.setWorkoutState(
                current.copy(dayPlans = current.dayPlans + (targetKey to target.copy(exercises = target.exercises + copied)))
            )
        }
    }

    suspend fun previousPlanDates(before: LocalDate): List<LocalDate> =
        previousPlanDateKeys(WorkoutDate.key(before)).mapNotNull(WorkoutDate::parse)

    suspend fun previousPlanDateKeys(beforeDateKey: String): List<String> {
        val before = WorkoutDate.requireKey(beforeDateKey)
        return snapshot().dayPlans.values
            .filter { it.exercises.isNotEmpty() && it.dateKey < before }
            .map { it.dateKey }
            .sortedDescending()
    }

    suspend fun exerciseLiftHistory(
        itemId: String,
        name: String,
        before: LocalDate,
        limit: Int = 90
    ): List<ExerciseLiftDay> = exerciseLiftHistory(itemId, name, WorkoutDate.key(before), limit)

    suspend fun exerciseLiftHistory(
        itemId: String,
        name: String,
        beforeDateKey: String,
        limit: Int = 90
    ): List<ExerciseLiftDay> =
        ExerciseLiftHistory.history(snapshot(), itemId, name, beforeDateKey, limit)

    suspend fun lastExerciseLiftSummary(
        itemId: String,
        name: String,
        before: LocalDate,
        displayUnit: WorkoutWeightUnit
    ): String? = ExerciseLiftHistory.lastSummary(
        snapshot(),
        itemId,
        name,
        WorkoutDate.key(before),
        displayUnit
    )

    suspend fun updatePreferences(transform: (WorkoutPreferences) -> WorkoutPreferences) {
        updateState { current ->
            current.copy(preferences = transform(current.preferences).sanitized())
        }
    }

    fun estimateBurn(
        exercises: List<PlannedExercise>,
        bodyWeightKg: Double,
        weightUnit: WorkoutWeightUnit,
        rpeScale: com.apoorvdarshan.calorietracker.models.WorkoutRpeScale
    ): WorkoutBurnEstimate? = WorkoutBurnEstimator.estimate(
        exercises = exercises,
        bodyWeightKg = bodyWeightKg,
        defaultWeightUnit = weightUnit,
        defaultRpeScale = rpeScale
    )

    suspend fun calculateBurn(
        date: LocalDate,
        bodyWeightKg: Double,
        weightUnit: WorkoutWeightUnit,
        calculatedAt: Instant = Instant.now()
    ): WorkoutSession? = calculateBurn(
        dateKey = WorkoutDate.key(date),
        bodyWeightKg = bodyWeightKg,
        weightUnit = weightUnit,
        calculatedAt = calculatedAt
    )

    suspend fun calculateBurn(
        dateKey: String,
        bodyWeightKg: Double,
        weightUnit: WorkoutWeightUnit,
        calculatedAt: Instant = Instant.now()
    ): WorkoutSession? = saveCalculatedWorkout(dateKey, null, bodyWeightKg, weightUnit, calculatedAt)

    suspend fun upsertCalculatedWorkout(
        date: LocalDate,
        caloriesBurned: Int,
        weightUnit: WorkoutWeightUnit,
        calculatedAt: Instant = Instant.now()
    ): WorkoutSession? = upsertCalculatedWorkout(
        WorkoutDate.key(date),
        caloriesBurned,
        weightUnit,
        calculatedAt
    )

    /** Stores one stable, recalculable burn snapshot for the calendar day. */
    suspend fun upsertCalculatedWorkout(
        dateKey: String,
        caloriesBurned: Int,
        weightUnit: WorkoutWeightUnit,
        calculatedAt: Instant = Instant.now()
    ): WorkoutSession? = saveCalculatedWorkout(dateKey, caloriesBurned, null, weightUnit, calculatedAt)

    private suspend fun saveCalculatedWorkout(
        dateKey: String,
        suppliedCalories: Int?,
        bodyWeightKg: Double?,
        weightUnit: WorkoutWeightUnit,
        calculatedAt: Instant
    ): WorkoutSession? {
        val key = WorkoutDate.requireKey(dateKey)
        var savedSession: WorkoutSession? = null
        var duplicateIds: List<Pair<UUID, String>> = emptyList()

        stateMutex.withLock {
            val current = store.workoutState.first().sanitized()
            val planned = current.dayPlans[key]?.exercises.orEmpty()
            val logs = completedLogs(planned, weightUnit, current.preferences)
            if (planned.none { it.hasCalculableWork }) return@withLock
            // Derive burn and completed logs from the same plan while edits are excluded.
            val caloriesBurned = suppliedCalories ?: WorkoutBurnEstimator.estimate(
                exercises = planned,
                bodyWeightKg = bodyWeightKg ?: 70.0,
                defaultWeightUnit = weightUnit,
                defaultRpeScale = current.preferences.rpeScale
            )?.calories ?: return@withLock

            val existingBurns = current.completedSessions
                .filter { it.diaryDateKey == key && it.caloriesBurned != null }
                .sortedWith(sessionDescendingComparator)
            val existing = existingBurns.firstOrNull()
            val session = WorkoutSession(
                id = existing?.id ?: UUID.randomUUID(),
                diaryDateKey = key,
                startedAt = calculatedAt,
                completedAt = calculatedAt,
                // Exercise timers may overlap; the daily snapshot has no session interval.
                durationSeconds = 0,
                exercises = logs,
                caloriesBurned = caloriesBurned.coerceIn(1, 5_000),
                healthSyncVersion = (existing?.healthSyncVersion ?: 0) + 1
            )
            duplicateIds = existingBurns.drop(1)
                .filter { it.id != session.id }
                .map { it.id to it.diaryDateKey }

            val tombstones = current.healthDeletionTombstones.toMutableMap().apply {
                remove(session.id.toString())
                duplicateIds.forEach { (id, duplicateDateKey) -> put(id.toString(), duplicateDateKey) }
            }
            val pendingDeletes = current.pendingHealthDeleteIds.toMutableSet().apply {
                remove(session.id.toString())
                duplicateIds.forEach { add(it.first.toString()) }
            }
            val pending = current.pendingHealthUpsertIds.toMutableSet().apply {
                duplicateIds.forEach { remove(it.first.toString()) }
                add(session.id.toString())
            }
            val sessions = current.completedSessions
                .filterNot { it.diaryDateKey == key && it.caloriesBurned != null } + session
            store.setWorkoutState(
                current.copy(
                    completedSessions = sessions,
                    healthDeletionTombstones = tombstones,
                    pendingHealthDeleteIds = pendingDeletes,
                    pendingHealthUpsertIds = pending
                )
            )
            savedSession = session
        }

        duplicateIds.forEach { (id, duplicateDateKey) -> performHealthDelete(id, duplicateDateKey) }
        savedSession?.let { performHealthUpsert(it.id) }
        return savedSession
    }

    suspend fun deleteSession(sessionId: UUID) {
        var deletedBurn: Pair<UUID, String>? = null
        stateMutex.withLock {
            val current = store.workoutState.first().sanitized()
            val session = current.completedSessions.firstOrNull { it.id == sessionId } ?: return@withLock
            val tombstones = current.healthDeletionTombstones.toMutableMap()
            val pendingDeletes = current.pendingHealthDeleteIds.toMutableSet()
            val pending = current.pendingHealthUpsertIds.toMutableSet()
            if (session.caloriesBurned != null) {
                tombstones[session.id.toString()] = session.diaryDateKey
                pendingDeletes.add(session.id.toString())
                pending.remove(session.id.toString())
                deletedBurn = session.id to session.diaryDateKey
            }
            store.setWorkoutState(
                current.copy(
                    completedSessions = current.completedSessions.filterNot { it.id == sessionId },
                    healthDeletionTombstones = tombstones,
                    pendingHealthDeleteIds = pendingDeletes,
                    pendingHealthUpsertIds = pending
                )
            )
        }
        deletedBurn?.let { performHealthDelete(it.first, it.second) }
    }

    /** Health imports never trigger a write callback, preventing an echo loop. */
    suspend fun importWorkoutBurnSessions(imported: List<WorkoutSession>) {
        if (imported.isEmpty()) return
        updateState { current ->
            val sessions = current.completedSessions.toMutableList()
            var changed = false
            for (incoming in imported) {
                if (incoming.caloriesBurned == null) continue
                if (incoming.id.toString() in current.healthDeletionTombstones) continue

                val idIndex = sessions.indexOfFirst { it.id == incoming.id }
                if (idIndex >= 0) {
                    val local = sessions[idIndex]
                    if ((incoming.healthSyncVersion ?: 0) > (local.healthSyncVersion ?: 0)) {
                        sessions[idIndex] = mergeBurnSession(local, incoming)
                        changed = true
                    }
                    continue
                }

                val sameDayIndex = sessions.indexOfFirst {
                    it.diaryDateKey == incoming.diaryDateKey && it.caloriesBurned != null
                }
                if (sameDayIndex >= 0) {
                    val local = sessions[sameDayIndex]
                    if ((incoming.healthSyncVersion ?: 0) > (local.healthSyncVersion ?: 0)) {
                        sessions[sameDayIndex] = mergeBurnSession(local, incoming)
                        changed = true
                    }
                } else {
                    sessions.add(incoming)
                    changed = true
                }
            }
            if (!changed) current else current.copy(completedSessions = sessions)
        }
    }

    /**
     * Bidirectional reconciliation hook for the Health Connect coordinator. Local detail wins an
     * id conflict; a higher version wins when the ids match. Deletes are retried before reads.
     */
    suspend fun synchronizeWithHealth() {
        val adapter = health ?: return
        // The read, tombstone checks, remote deletes, imports, and exports form one
        // serialized operation. Otherwise a delete could clear its tombstone while
        // an older in-flight read still holds the soon-to-be-deleted record.
        healthMutex.withLock {
            retryPendingHealthOperationsLocked(adapter)
            val remote = adapter.readOwnedBurns()
            if (remote == null) {
                markAllLocalBurnsPending()
                retryPendingHealthOperationsLocked(adapter)
                return@withLock
            }

            val current = snapshot()
            val tombstones = current.healthDeletionTombstones.keys
            val remoteIds = remote.mapTo(mutableSetOf()) { it.id.toString() }
            val remoteByDate = mutableMapOf<String, WorkoutSession>()
            val remoteDuplicates = mutableListOf<WorkoutSession>()
            for ((dateKey, sessions) in remote.filterNot { it.id.toString() in tombstones }.groupBy { it.diaryDateKey }) {
                val preferred = sessions.maxWithOrNull(preferredSessionComparator) ?: continue
                remoteByDate[dateKey] = preferred
                remoteDuplicates += sessions.filter { it.id != preferred.id }
            }
            // A successful delete can remain visible briefly. Retain its tombstone until an
            // authoritative owned-record read no longer contains that exact UUID.
            confirmHealthDeletionsMissingFrom(remoteIds)
            remoteDuplicates.forEach { record ->
                addHealthTombstone(record.id, record.diaryDateKey)
                performHealthDeleteLocked(adapter, record.id, record.diaryDateKey)
            }

            val localByDate = preferredBurnsByDate(snapshot().completedSessions)
            val imports = mutableListOf<WorkoutSession>()
            for (dateKey in localByDate.keys + remoteByDate.keys) {
                val local = localByDate[dateKey]
                val healthRecord = remoteByDate[dateKey]
                when {
                    local != null && healthRecord == null -> markHealthUpsertPending(local.id)
                    local == null && healthRecord != null -> imports += healthRecord
                    local != null && healthRecord != null && local.id != healthRecord.id -> {
                        addHealthTombstone(healthRecord.id, healthRecord.diaryDateKey)
                        performHealthDeleteLocked(adapter, healthRecord.id, healthRecord.diaryDateKey)
                        markHealthUpsertPending(local.id)
                    }
                    local != null && healthRecord != null -> {
                        val localVersion = local.healthSyncVersion ?: 0
                        val remoteVersion = healthRecord.healthSyncVersion ?: 0
                        if (remoteVersion > localVersion) {
                            imports += mergeBurnSession(local, healthRecord)
                        } else if (
                            localVersion > remoteVersion ||
                            local.caloriesBurned != healthRecord.caloriesBurned ||
                            local.diaryDateKey != healthRecord.diaryDateKey
                        ) {
                            markHealthUpsertPending(local.id)
                        }
                    }
                }
            }
            importWorkoutBurnSessions(imports)
            retryPendingHealthOperationsLocked(adapter)
        }
    }

    suspend fun retryPendingHealthOperations() {
        val adapter = health ?: return
        healthMutex.withLock { retryPendingHealthOperationsLocked(adapter) }
    }

    private suspend fun retryPendingHealthOperationsLocked(adapter: WorkoutHealthSync) {
        val before = snapshot()
        val pendingDeletes = before.pendingHealthDeleteIds.mapNotNull { id ->
            val dateKey = before.healthDeletionTombstones[id] ?: return@mapNotNull null
            runCatching { UUID.fromString(id) }.getOrNull()?.let { it to dateKey }
        }
        pendingDeletes.forEach { (id, dateKey) -> performHealthDeleteLocked(adapter, id, dateKey) }

        val pendingUpserts = snapshot().pendingHealthUpsertIds.mapNotNull {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        pendingUpserts.forEach { performHealthUpsertLocked(adapter, it) }
    }

    /** Local-only wipe, matching iOS Delete Everything semantics. */
    suspend fun clear() {
        stateMutex.withLock { store.clearWorkoutState() }
    }

    private suspend fun updatePlan(
        dateKey: String,
        customActivities: List<PlannedExercise> = emptyList(),
        transform: (WorkoutDayPlan) -> WorkoutDayPlan
    ) = updatePlanWithPreferences(dateKey, customActivities) { plan, _ -> transform(plan) }

    private suspend fun updatePlanWithPreferences(
        dateKey: String,
        customActivities: List<PlannedExercise> = emptyList(),
        transform: (WorkoutDayPlan, WorkoutPreferences) -> WorkoutDayPlan
    ) {
        var invalidatedBurns: List<WorkoutSession> = emptyList()
        stateMutex.withLock {
            val current = store.workoutState.first().sanitized()
            val previous = current.dayPlans[dateKey] ?: WorkoutDayPlan(dateKey)
            val changed = transform(previous, current.preferences)
            val plans = current.dayPlans.toMutableMap()
            if (changed.exercises.isEmpty()) plans.remove(dateKey) else plans[dateKey] = changed
            // A saved timer change invalidates the daily estimate; unsaved ticks/pauses do not.
            // Clear the whole snapshot because mixed strength/timer calories cannot be subtracted safely.
            if (savedTimerInputs(previous, current.preferences.rpeScale) != savedTimerInputs(changed, current.preferences.rpeScale)) {
                invalidatedBurns = current.completedSessions.filter {
                    it.diaryDateKey == dateKey && it.caloriesBurned != null
                }
            }
            val invalidatedIds = invalidatedBurns.mapTo(mutableSetOf()) { it.id.toString() }
            val next = current.copy(
                customActivities = (current.customActivities + customActivities).distinctBy { it.itemId },
                dayPlans = plans,
                completedSessions = current.completedSessions.filterNot { it.id.toString() in invalidatedIds },
                healthDeletionTombstones = current.healthDeletionTombstones +
                    invalidatedBurns.associate { it.id.toString() to it.diaryDateKey },
                pendingHealthDeleteIds = current.pendingHealthDeleteIds + invalidatedIds,
                pendingHealthUpsertIds = current.pendingHealthUpsertIds - invalidatedIds
            )
            if (next != current) store.setWorkoutState(next)
        }
        invalidatedBurns.forEach { performHealthDelete(it.id, it.diaryDateKey) }
    }

    private fun savedTimerInputs(plan: WorkoutDayPlan, rpeScale: com.apoorvdarshan.calorietracker.models.WorkoutRpeScale): Map<UUID, Pair<Double, WorkoutIntensity>> =
        plan.exercises.mapNotNull { exercise ->
            val timer = exercise.timer ?: return@mapNotNull null
            val seconds = timer.savedSeconds.takeIf { it > 0.0 } ?: return@mapNotNull null
            exercise.id to (seconds to WorkoutBurnEstimator.timerIntensity(exercise, rpeScale))
        }.toMap()

    private suspend fun updateExercise(
        dateKey: String,
        exerciseId: UUID,
        transform: (PlannedExercise) -> PlannedExercise
    ) {
        updatePlan(dateKey) { plan ->
            val index = plan.exercises.indexOfFirst { it.id == exerciseId }
            if (index < 0) return@updatePlan plan
            plan.copy(exercises = plan.exercises.toMutableList().also {
                it[index] = transform(it[index])
            })
        }
    }

    private suspend fun updateState(transform: (WorkoutPersistedState) -> WorkoutPersistedState) {
        stateMutex.withLock {
            val current = store.workoutState.first().sanitized()
            val next = transform(current).sanitized()
            if (next != current) store.setWorkoutState(next)
        }
    }

    private suspend fun performHealthUpsert(sessionId: UUID) {
        val adapter = health ?: return
        healthMutex.withLock { performHealthUpsertLocked(adapter, sessionId) }
    }

    private suspend fun performHealthUpsertLocked(adapter: WorkoutHealthSync, sessionId: UUID) {
        val before = snapshot()
        if (sessionId.toString() in before.healthDeletionTombstones) return
        if (sessionId.toString() !in before.pendingHealthUpsertIds) return
        val latest = before.completedSessions.firstOrNull {
            it.id == sessionId && it.caloriesBurned != null
        } ?: return
        if (!adapter.upsertBurn(latest)) return
        updateState { current ->
            val stillCurrent = current.completedSessions.firstOrNull { it.id == sessionId }
            if (stillCurrent?.healthSyncVersion != latest.healthSyncVersion ||
                stillCurrent?.caloriesBurned != latest.caloriesBurned
            ) {
                current
            } else {
                current.copy(pendingHealthUpsertIds = current.pendingHealthUpsertIds - sessionId.toString())
            }
        }
    }

    private suspend fun performHealthDelete(sessionId: UUID, diaryDateKey: String) {
        val adapter = health ?: return
        healthMutex.withLock { performHealthDeleteLocked(adapter, sessionId, diaryDateKey) }
    }

    private suspend fun performHealthDeleteLocked(
        adapter: WorkoutHealthSync,
        sessionId: UUID,
        diaryDateKey: String
    ) {
        val before = snapshot()
        if (before.healthDeletionTombstones[sessionId.toString()] != diaryDateKey) return
        if (sessionId.toString() !in before.pendingHealthDeleteIds) return
        if (!adapter.deleteBurn(sessionId, diaryDateKey)) return
        updateState { current ->
            if (current.healthDeletionTombstones[sessionId.toString()] != diaryDateKey) {
                current
            } else {
                current.copy(
                    pendingHealthDeleteIds = current.pendingHealthDeleteIds - sessionId.toString()
                )
            }
        }
    }

    private suspend fun addHealthTombstone(sessionId: UUID, diaryDateKey: String) {
        updateState { current ->
            current.copy(
                healthDeletionTombstones = current.healthDeletionTombstones +
                    (sessionId.toString() to diaryDateKey),
                pendingHealthDeleteIds = current.pendingHealthDeleteIds + sessionId.toString(),
                pendingHealthUpsertIds = current.pendingHealthUpsertIds - sessionId.toString()
            )
        }
    }

    private suspend fun confirmHealthDeletionsMissingFrom(remoteIds: Set<String>) {
        updateState { current ->
            val confirmedIds = current.healthDeletionTombstones.keys - remoteIds
            if (confirmedIds.isEmpty()) current else current.copy(
                healthDeletionTombstones = current.healthDeletionTombstones - confirmedIds,
                pendingHealthDeleteIds = current.pendingHealthDeleteIds - confirmedIds
            )
        }
    }

    private suspend fun markHealthUpsertPending(sessionId: UUID) {
        updateState { current ->
            if (sessionId.toString() in current.healthDeletionTombstones) current else current.copy(
                pendingHealthUpsertIds = current.pendingHealthUpsertIds + sessionId.toString()
            )
        }
    }

    private suspend fun markAllLocalBurnsPending() {
        updateState { current ->
            val ids = current.completedSessions
                .filter { it.caloriesBurned != null && it.id.toString() !in current.healthDeletionTombstones }
                .mapTo(mutableSetOf()) { it.id.toString() }
            current.copy(pendingHealthUpsertIds = current.pendingHealthUpsertIds + ids)
        }
    }

    private fun completedLogs(
        planned: List<PlannedExercise>,
        weightUnit: WorkoutWeightUnit,
        preferences: WorkoutPreferences
    ): List<CompletedExercise> = planned.map { exercise ->
        CompletedExercise(
            itemId = exercise.itemId,
            name = exercise.name,
            targetMuscles = exercise.primaryMuscles,
            equipment = exercise.equipment,
            durationSeconds = exercise.timer?.savedSeconds?.takeIf { it > 0.0 },
            intensity = exercise.timer?.takeIf { it.savedSeconds > 0.0 }?.let { WorkoutBurnEstimator.timerIntensity(exercise, preferences.rpeScale) },
            sets = exercise.sets.mapIndexed { index, set ->
                CompletedSet(
                    setNumber = index + 1,
                    weight = set.weight.trim(),
                    weightUnit = set.weightUnit ?: weightUnit,
                    reps = set.reps.trim(),
                    rpe = set.rpe.trim(),
                    rpeScale = set.rpeScale ?: preferences.rpeScale
                )
            }
        )
    }

    private fun mergeBurnSession(local: WorkoutSession, imported: WorkoutSession): WorkoutSession =
        imported.copy(
            exercises = imported.exercises.ifEmpty { local.exercises },
            durationSeconds = if (imported.exercises.isEmpty()) local.durationSeconds else imported.durationSeconds
        )

    private fun preferredBurn(state: WorkoutPersistedState, dateKey: String): WorkoutSession? =
        state.completedSessions
            .filter { it.diaryDateKey == dateKey && it.caloriesBurned != null }
            .maxWithOrNull(preferredSessionComparator)

    private fun preferredBurnsByDate(sessions: List<WorkoutSession>): Map<String, WorkoutSession> =
        sessions.filter { it.caloriesBurned != null }
            .groupBy { it.diaryDateKey }
            .mapValues { (_, values) -> values.maxWith(preferredSessionComparator) }

    companion object {
        private val sessionDescendingComparator =
            compareByDescending<WorkoutSession> { it.diaryDateKey }
                .thenByDescending { it.completedAt }

        private val preferredSessionComparator = Comparator<WorkoutSession> { left, right ->
            val versionComparison = (left.healthSyncVersion ?: 0).compareTo(right.healthSyncVersion ?: 0)
            if (versionComparison != 0) versionComparison else left.completedAt.compareTo(right.completedAt)
        }
    }
}
