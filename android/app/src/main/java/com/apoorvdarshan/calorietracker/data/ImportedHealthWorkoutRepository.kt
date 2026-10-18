package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.ImportedHealthWorkout
import com.apoorvdarshan.calorietracker.services.health.HealthExerciseSession
import com.apoorvdarshan.calorietracker.services.health.SessionCalorieLookup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Health Connect side of the workout import: HealthConnectManager in the app, a fake in tests. */
interface ExerciseSessionSource {
    /** Whether exercise sessions may be read; null when the permission probe failed. */
    suspend fun exerciseReadGranted(): Boolean?

    /** Whether Active Calories Burned may be read for session calories. */
    suspend fun activeCaloriesReadable(): Boolean

    /** Other apps' sessions in [from, to), Fud AI's own left out; null when a read failed. */
    suspend fun readSessions(from: Instant, to: Instant): List<HealthExerciseSession>?

    suspend fun activeCalories(session: HealthExerciseSession): SessionCalorieLookup

    /** The recording app's name on this phone, if Android can resolve it. */
    fun appLabel(packageName: String): String?
}

/** Persistence for imported sessions (DataStore in the app). */
interface ImportedHealthWorkoutStore {
    val importedHealthWorkouts: Flow<List<ImportedHealthWorkout>>
    suspend fun updateImportedHealthWorkouts(
        transform: (List<ImportedHealthWorkout>) -> List<ImportedHealthWorkout>
    )
}

/** What one [ImportedHealthWorkoutRepository.synchronize] call did. */
enum class HealthWorkoutImportResult {
    /** The permission probe or session read failed; stored sessions were left untouched. */
    SKIPPED,
    /** Exercise access is not granted; any imported copies were removed. */
    PERMISSION_DENIED,
    SYNCED
}

/**
 * Read-only copies of exercise sessions other apps (Garmin Connect, Coros, Strava, Samsung
 * Health, Fitbit, …) shared with Health Connect, shown in the Workouts diary and on Progress.
 * Port of iOS ImportedHealthWorkoutStore + HealthKitManager's workout import: rows are keyed
 * by Health Connect record id, refreshed over a rolling [LOOKBACK_DAYS] window, never written
 * back, and never fed into Fud AI's calculated burns or Energy Burn goals.
 */
class ImportedHealthWorkoutRepository(
    private val store: ImportedHealthWorkoutStore,
    private val source: ExerciseSessionSource,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    private val mutex = Mutex()

    /** Newest first. */
    val workouts: Flow<List<ImportedHealthWorkout>> = store.importedHealthWorkouts.map { list ->
        list.sortedWith(newestFirst)
    }

    /**
     * Removes the imported copies once a successful permission probe shows Exercise access is
     * gone, and never imports. It needs no other Health Connect permission and ignores Fud AI's
     * Health Connect toggle, so the foreground sync runs it ahead of those guards: revoking every
     * Fud AI permission, or Exercise while the toggle is off, skips [synchronize] but must still
     * remove them. A failed probe keeps them; with nothing imported it does not probe at all.
     * Returns whether copies were removed.
     */
    suspend fun removeIfAccessRevoked(): Boolean = mutex.withLock {
        if (store.importedHealthWorkouts.first().isEmpty()) return@withLock false
        if (source.exerciseReadGranted() != false) return@withLock false
        clearImports()
        true
    }

    suspend fun synchronize(): HealthWorkoutImportResult = mutex.withLock {
        val granted = source.exerciseReadGranted() ?: return@withLock HealthWorkoutImportResult.SKIPPED
        if (!granted) {
            // Access was declined or revoked: drop the copies made while it was granted.
            clearImports()
            return@withLock HealthWorkoutImportResult.PERMISSION_DENIED
        }

        val end = now()
        val queryStart = end.minus(Duration.ofDays(LOOKBACK_DAYS))
        val sessions = source.readSessions(queryStart, end) ?: return@withLock HealthWorkoutImportResult.SKIPPED
        val previous = store.importedHealthWorkouts.first().associateBy { it.id }
        val caloriesReadable = source.activeCaloriesReadable()
        val labels = mutableMapOf<String, String?>()
        val zoneId = zone()
        var lookups = 0

        // Newest first, so the lookup budget goes to the sessions people look at.
        val fetched = sessions.sortedByDescending { it.startTime }.mapNotNull { session ->
            val stored = previous[session.recordId]
            val modifiedMs = session.lastModified.toEpochMilli()
            val calories: Int?
            val checkedFor: Long?
            when {
                !needsCalorieLookup(stored, session, end) -> {
                    calories = stored?.activeCalories
                    checkedFor = stored?.caloriesCheckedForModifiedMs
                }
                !caloriesReadable || lookups >= MAX_CALORIE_LOOKUPS_PER_SYNC -> {
                    calories = stored?.activeCalories
                    checkedFor = null
                }
                else -> {
                    lookups++
                    when (val lookup = source.activeCalories(session)) {
                        is SessionCalorieLookup.Resolved -> {
                            calories = lookup.activeCalories
                            checkedFor = modifiedMs
                        }
                        SessionCalorieLookup.Failed -> {
                            calories = stored?.activeCalories
                            checkedFor = null
                        }
                    }
                }
            }
            val origin = session.originPackage
            val label = if (origin in labels) labels[origin] else source.appLabel(origin).also { labels[origin] = it }
            session.toImportedHealthWorkout(zoneId, label ?: stored?.sourceName, calories, checkedFor)
        }

        store.updateImportedHealthWorkouts { current -> reconcileImportedWorkouts(current, fetched, queryStart) }
        HealthWorkoutImportResult.SYNCED
    }

    /** Caller holds [mutex]. */
    private suspend fun clearImports() {
        if (store.importedHealthWorkouts.first().isNotEmpty()) {
            store.updateImportedHealthWorkouts { emptyList() }
        }
    }

    companion object {
        /** Matches iOS's Apple Health workout import window. */
        const val LOOKBACK_DAYS = 90L

        /** Sessions this recent are re-checked every sync: apps often finish writing a
         *  workout's calories after the session itself. */
        val CALORIE_RECHECK_WINDOW: Duration = Duration.ofDays(2)

        /** Caps Health Connect aggregate calls per sync; the rest are looked up next time. */
        const val MAX_CALORIE_LOOKUPS_PER_SYNC = 60

        private val newestFirst = compareByDescending<ImportedHealthWorkout> { it.startedAt }.thenBy { it.id }
    }
}

/** Whether [session]'s calories must be (re)read rather than reusing [stored]'s. */
internal fun needsCalorieLookup(stored: ImportedHealthWorkout?, session: HealthExerciseSession, now: Instant): Boolean =
    stored?.caloriesCheckedForModifiedMs != session.lastModified.toEpochMilli() ||
        session.endTime.isAfter(now.minus(ImportedHealthWorkoutRepository.CALORIE_RECHECK_WINDOW))

/** Maps a Health Connect session to the stored model; null for a session without a positive duration. */
internal fun HealthExerciseSession.toImportedHealthWorkout(
    zone: ZoneId,
    sourceName: String?,
    activeCalories: Int?,
    caloriesCheckedForModifiedMs: Long?
): ImportedHealthWorkout? {
    val seconds = Duration.between(startTime, endTime).seconds
    if (seconds <= 0) return null
    return ImportedHealthWorkout(
        id = recordId,
        exerciseType = exerciseType,
        startedAt = startTime,
        endedAt = endTime,
        durationSeconds = seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        activeCalories = activeCalories?.takeIf { it > 0 },
        distanceMeters = lapDistanceMeters?.takeIf { it.isFinite() && it > 0.0 },
        sourcePackage = originPackage,
        sourceName = sourceName?.trim()?.takeIf { it.isNotEmpty() },
        deviceName = deviceName,
        diaryDateKey = startTime.atZone(zone).toLocalDate().toString(),
        lastModifiedEpochMs = lastModified.toEpochMilli(),
        caloriesCheckedForModifiedMs = caloriesCheckedForModifiedMs
    )
}

/**
 * Upserts [fetched] and drops stored sessions in the queried window that Health Connect no
 * longer returns (deleted in the source app). Older sessions outside the window are kept,
 * matching iOS ImportedHealthWorkoutStore.synchronize.
 */
internal fun reconcileImportedWorkouts(
    current: List<ImportedHealthWorkout>,
    fetched: List<ImportedHealthWorkout>,
    queryStart: Instant
): List<ImportedHealthWorkout> {
    val fetchedIds = fetched.mapTo(HashSet()) { it.id }
    val olderKept = current.filter { it.startedAt.isBefore(queryStart) && it.id !in fetchedIds }
    return (olderKept + fetched.distinctBy { it.id }).sortedByDescending { it.startedAt }
}
