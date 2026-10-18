package com.apoorvdarshan.calorietracker.data

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.apoorvdarshan.calorietracker.models.ImportedHealthWorkout
import com.apoorvdarshan.calorietracker.models.importedWorkoutsOn
import com.apoorvdarshan.calorietracker.services.health.HealthExerciseSession
import com.apoorvdarshan.calorietracker.services.health.SessionCalorieLookup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Health Connect workout import (#479), Android port of iOS ImportedHealthWorkoutStore. */
class ImportedHealthWorkoutRepositoryTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun sessionMapsToReadOnlyWorkoutOnItsLocalDiaryDay() {
        // 23:00 UTC on Oct 11 is already Oct 12 in India.
        val session = session("run", start = "2026-10-11T23:00:00Z", minutes = 47, laps = 7_520.0)

        val workout = session.toImportedHealthWorkout(zone, "Garmin Connect", 512, 99L)!!

        assertEquals("run", workout.id)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, workout.exerciseType)
        assertEquals("2026-10-12", workout.diaryDateKey)
        assertEquals(47 * 60, workout.durationSeconds)
        assertEquals(47, workout.durationMinutes)
        assertEquals(512, workout.activeCalories)
        assertEquals(7_520.0, workout.distanceMeters!!, 0.0)
        assertEquals("Garmin Connect · Garmin Forerunner 265", workout.sourceSummary)
        assertEquals(99L, workout.caloriesCheckedForModifiedMs)
    }

    @Test
    fun unresolvedAppNameFallsBackToThePackageAndZeroCaloriesReadAsUnknown() {
        val workout = session("walk", device = null).toImportedHealthWorkout(zone, "  ", 0, null)!!

        assertEquals("com.strava", workout.sourceSummary)
        assertNull(workout.activeCalories)
    }

    @Test
    fun deniedExercisePermissionClearsImportsWithoutReadingHealthConnect() = runBlocking {
        val store = FakeImportedStore(listOf(stored("old")))
        val source = FakeSource(granted = false)

        val result = repository(store, source).synchronize()

        assertEquals(HealthWorkoutImportResult.PERMISSION_DENIED, result)
        assertTrue(store.items.value.isEmpty())
        assertEquals(0, source.sessionReads)
        assertEquals(0, source.calorieLookups)
    }

    @Test
    fun revokedAccessRemovesImportsOutsideTheSync() = runBlocking {
        // Every Fud AI permission revoked, or Exercise revoked while Fud AI's Health Connect
        // toggle is off: the foreground sync returns before synchronize(), so removal runs on
        // its own and must not need any other permission.
        val store = FakeImportedStore(listOf(stored("garmin-run"), stored("strava-ride")))
        val source = FakeSource(granted = false)

        assertTrue(repository(store, source).removeIfAccessRevoked())

        assertTrue(store.items.value.isEmpty())
        assertEquals(0, source.sessionReads)
        assertEquals(0, source.calorieLookups)
    }

    @Test
    fun removalKeepsImportsWhenAccessRemainsOrTheProbeFails() = runBlocking {
        val kept = listOf(stored("garmin-run"))

        // Still granted (e.g. only Fud AI's toggle is off): kept, and nothing is imported.
        val granted = FakeImportedStore(kept)
        val grantedSource = FakeSource(sessions = listOf(session("new")))
        assertFalse(repository(granted, grantedSource).removeIfAccessRevoked())
        assertEquals(kept, granted.items.value)
        assertEquals(0, grantedSource.sessionReads)

        // A failed probe or unavailable Health Connect is not a revocation.
        val probeFailed = FakeImportedStore(kept)
        assertFalse(repository(probeFailed, FakeSource(granted = null)).removeIfAccessRevoked())
        assertEquals(kept, probeFailed.items.value)
    }

    @Test
    fun removalDoesNotProbeWhenNothingWasImported() = runBlocking {
        val source = FakeSource(granted = false)

        assertFalse(repository(FakeImportedStore(), source).removeIfAccessRevoked())

        assertEquals(0, source.permissionProbes)
    }

    @Test
    fun failedPermissionProbeOrReadKeepsStoredImports() = runBlocking {
        val kept = listOf(stored("old"))
        val probeFailed = FakeImportedStore(kept)
        assertEquals(
            HealthWorkoutImportResult.SKIPPED,
            repository(probeFailed, FakeSource(granted = null)).synchronize()
        )
        assertEquals(kept, probeFailed.items.value)

        val readFailed = FakeImportedStore(kept)
        assertEquals(
            HealthWorkoutImportResult.SKIPPED,
            repository(readFailed, FakeSource(sessions = null)).synchronize()
        )
        assertEquals(kept, readFailed.items.value)
    }

    @Test
    fun syncImportsSessionsWithCaloriesAndAppNames() = runBlocking {
        val store = FakeImportedStore()
        val source = FakeSource(
            sessions = listOf(session("ride", start = "2026-10-10T05:00:00Z")),
            calories = mapOf("ride" to SessionCalorieLookup.Resolved(640))
        )

        assertEquals(HealthWorkoutImportResult.SYNCED, repository(store, source).synchronize())

        val workout = store.items.value.single()
        assertEquals(640, workout.activeCalories)
        assertEquals("Strava", workout.sourceName)
        assertEquals(session("ride", start = "2026-10-10T05:00:00Z").lastModified.toEpochMilli(), workout.caloriesCheckedForModifiedMs)
    }

    @Test
    fun unchangedOlderSessionsReuseStoredCaloriesWhileRecentOnesAreRechecked() = runBlocking {
        val older = session("older", start = "2026-10-10T05:00:00Z")
        val recent = session("recent", start = "2026-10-17T18:00:00Z")
        val store = FakeImportedStore()
        val source = FakeSource(
            sessions = listOf(older, recent),
            calories = mapOf(
                "older" to SessionCalorieLookup.Resolved(300),
                "recent" to SessionCalorieLookup.Resolved(null)
            )
        )
        val repository = repository(store, source)
        repository.synchronize()
        assertEquals(2, source.calorieLookups)

        // The watch app finishes writing the recent workout's calories after the session.
        source.calories = mapOf("recent" to SessionCalorieLookup.Resolved(410))
        repository.synchronize()

        assertEquals(3, source.calorieLookups)
        assertEquals(300, store.items.value.first { it.id == "older" }.activeCalories)
        assertEquals(410, store.items.value.first { it.id == "recent" }.activeCalories)
    }

    @Test
    fun editedSessionIsLookedUpAgain() = runBlocking {
        val original = session("swim", start = "2026-10-01T05:00:00Z")
        val store = FakeImportedStore()
        val source = FakeSource(sessions = listOf(original), calories = mapOf("swim" to SessionCalorieLookup.Resolved(200)))
        val repository = repository(store, source)
        repository.synchronize()

        source.sessions = listOf(original.copy(lastModified = original.lastModified.plusSeconds(60)))
        source.calories = mapOf("swim" to SessionCalorieLookup.Resolved(260))
        repository.synchronize()

        assertEquals(2, source.calorieLookups)
        assertEquals(260, store.items.value.single().activeCalories)
    }

    @Test
    fun failedOrUnpermittedCalorieLookupKeepsTheLastValueAndRetries() = runBlocking {
        val swim = session("swim", start = "2026-10-01T05:00:00Z")
        val store = FakeImportedStore(listOf(stored("swim", calories = 180, checkedFor = 1L)))
        val source = FakeSource(sessions = listOf(swim), calories = mapOf("swim" to SessionCalorieLookup.Failed))
        val repository = repository(store, source)

        repository.synchronize()
        assertEquals(180, store.items.value.single().activeCalories)
        assertNull(store.items.value.single().caloriesCheckedForModifiedMs)

        // Without Active Calories read access no aggregate is attempted; the session waits for it.
        source.activeCaloriesReadable = false
        repository.synchronize()
        assertEquals(1, source.calorieLookups)
        assertNull(store.items.value.single().caloriesCheckedForModifiedMs)

        source.activeCaloriesReadable = true
        source.calories = mapOf("swim" to SessionCalorieLookup.Resolved(195))
        repository.synchronize()
        assertEquals(195, store.items.value.single().activeCalories)
    }

    @Test
    fun sessionsDeletedInTheirAppDisappearWhileOlderHistoryStays() = runBlocking {
        val queryStart = now.minus(Duration.ofDays(ImportedHealthWorkoutRepository.LOOKBACK_DAYS))
        val beforeWindow = stored("archived", startedAt = queryStart.minus(Duration.ofDays(3)))
        val deleted = stored("deleted", startedAt = Instant.parse("2026-10-05T05:00:00Z"))
        val store = FakeImportedStore(listOf(beforeWindow, deleted))
        val source = FakeSource(sessions = listOf(session("kept", start = "2026-10-06T05:00:00Z")))

        repository(store, source).synchronize()

        assertEquals(listOf("kept", "archived"), store.items.value.map { it.id })
        assertEquals(queryStart, source.lastQuery?.first)
    }

    @Test
    fun diaryShowsOnlyTheSelectedDaysSessionsEarliestFirst() {
        val late = stored("late", startedAt = Instant.parse("2026-10-12T12:00:00Z"), day = "2026-10-12")
        val early = stored("early", startedAt = Instant.parse("2026-10-12T02:00:00Z"), day = "2026-10-12")
        val other = stored("other", startedAt = Instant.parse("2026-10-11T02:00:00Z"), day = "2026-10-11")

        assertEquals(
            listOf("early", "late"),
            importedWorkoutsOn(listOf(late, other, early), LocalDate.parse("2026-10-12")).map { it.id }
        )
    }

    @Test
    fun newestFirstOrderIsPublished() = runBlocking {
        val store = FakeImportedStore(
            listOf(
                stored("a", startedAt = Instant.parse("2026-10-01T05:00:00Z")),
                stored("b", startedAt = Instant.parse("2026-10-03T05:00:00Z"))
            )
        )

        assertEquals(listOf("b", "a"), repository(store, FakeSource()).workouts.first().map { it.id })
    }

    private fun repository(store: FakeImportedStore, source: FakeSource) =
        ImportedHealthWorkoutRepository(store, source, now = { now }, zone = { zone })

    private fun session(
        id: String,
        start: String = "2026-10-12T06:30:00Z",
        minutes: Long = 45,
        laps: Double? = null,
        device: String? = "Garmin Forerunner 265"
    ): HealthExerciseSession {
        val startTime = Instant.parse(start)
        return HealthExerciseSession(
            recordId = id,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            startTime = startTime,
            endTime = startTime.plus(Duration.ofMinutes(minutes)),
            originPackage = "com.strava",
            lastModified = startTime.plus(Duration.ofMinutes(minutes + 5)),
            deviceName = device,
            lapDistanceMeters = laps
        )
    }

    private fun stored(
        id: String,
        startedAt: Instant = Instant.parse("2026-10-12T06:30:00Z"),
        day: String = "2026-10-12",
        calories: Int? = 250,
        checkedFor: Long? = null
    ) = ImportedHealthWorkout(
        id = id,
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        startedAt = startedAt,
        endedAt = startedAt.plus(Duration.ofMinutes(30)),
        durationSeconds = 1_800,
        activeCalories = calories,
        sourcePackage = "com.strava",
        sourceName = "Strava",
        diaryDateKey = day,
        caloriesCheckedForModifiedMs = checkedFor
    )

    private class FakeImportedStore(initial: List<ImportedHealthWorkout> = emptyList()) : ImportedHealthWorkoutStore {
        val items = MutableStateFlow(initial)
        override val importedHealthWorkouts: Flow<List<ImportedHealthWorkout>> = items
        override suspend fun updateImportedHealthWorkouts(
            transform: (List<ImportedHealthWorkout>) -> List<ImportedHealthWorkout>
        ) {
            items.value = transform(items.value)
        }
    }

    private class FakeSource(
        var granted: Boolean? = true,
        var sessions: List<HealthExerciseSession>? = emptyList(),
        var calories: Map<String, SessionCalorieLookup> = emptyMap(),
        var activeCaloriesReadable: Boolean = true
    ) : ExerciseSessionSource {
        var permissionProbes = 0
        var sessionReads = 0
        var calorieLookups = 0
        var lastQuery: Pair<Instant, Instant>? = null

        override suspend fun exerciseReadGranted(): Boolean? {
            permissionProbes++
            return granted
        }
        override suspend fun activeCaloriesReadable(): Boolean = activeCaloriesReadable
        override suspend fun readSessions(from: Instant, to: Instant): List<HealthExerciseSession>? {
            sessionReads++
            lastQuery = from to to
            return sessions
        }

        override suspend fun activeCalories(session: HealthExerciseSession): SessionCalorieLookup {
            calorieLookups++
            return calories[session.recordId] ?: SessionCalorieLookup.Resolved(null)
        }

        override fun appLabel(packageName: String): String? = if (packageName == "com.strava") "Strava" else null
    }
}
