package com.apoorvdarshan.calorietracker.services.health

import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class HealthExerciseSessionsTest {
    private val start = Instant.parse("2026-10-12T06:30:00Z")
    private val end = Instant.parse("2026-10-12T07:15:00Z")
    private val modified = Instant.parse("2026-10-12T07:20:00Z")

    @Test
    fun exerciseSessionRecordMapsTypeTimesSourceDeviceAndLapDistance() {
        val record = session(
            origin = "com.garmin.android.apps.connectmobile",
            device = Device(type = Device.TYPE_WATCH, manufacturer = "Garmin", model = "Forerunner 265"),
            laps = listOf(
                ExerciseLap(start, start.plusSeconds(1_200), Length.meters(4_000.0)),
                ExerciseLap(start.plusSeconds(1_200), end, Length.meters(3_500.0))
            )
        )

        assertEquals(
            HealthExerciseSession(
                recordId = "hc-record-1",
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                startTime = start,
                endTime = end,
                originPackage = "com.garmin.android.apps.connectmobile",
                lastModified = modified,
                deviceName = "Garmin Forerunner 265",
                lapDistanceMeters = 7_500.0
            ),
            record.toExternalHealthExerciseSession(ownPackage = "com.apoorvdarshan.calorietracker")
        )
    }

    @Test
    fun sessionWithoutLapsOrDeviceHasNoDistanceOrDevice() {
        val mapped = session(origin = "com.strava").toExternalHealthExerciseSession()!!

        assertNull(mapped.lapDistanceMeters)
        assertNull(mapped.deviceName)
    }

    @Test
    fun sessionsWrittenByAnyFudAiBuildAreNeverImported() {
        for (own in listOf(
            "com.apoorvdarshan.calorietracker",
            "com.apoorvdarshan.calorietracker.debug",
            "com.apoorvdarshan.calorietracker.debug2"
        )) {
            assertNull(own, session(origin = own).toExternalHealthExerciseSession(ownPackage = own))
            assertNull(
                own,
                session(origin = own).toExternalHealthExerciseSession(ownPackage = "com.apoorvdarshan.calorietracker")
            )
        }
        // A similarly named but unrelated package is still another app.
        assertEquals(
            "com.apoorvdarshan.calorietrackerfan",
            session(origin = "com.apoorvdarshan.calorietrackerfan").toExternalHealthExerciseSession()?.originPackage
        )
    }

    @Test
    fun malformedSessionsAreSkipped() {
        assertNull(session(origin = "com.strava", id = "").toExternalHealthExerciseSession())
        assertNull(session(origin = "").toExternalHealthExerciseSession())
    }

    @Test
    fun deviceNameDoesNotRepeatTheManufacturer() {
        assertEquals("Garmin Forerunner 265", healthDeviceName("Garmin", "Forerunner 265"))
        assertEquals("samsung Galaxy Watch7", healthDeviceName("samsung", "Galaxy Watch7"))
        assertEquals("COROS PACE 3", healthDeviceName("COROS", "COROS PACE 3"))
        assertEquals("Fitbit", healthDeviceName(" Fitbit ", "  "))
        assertNull(healthDeviceName(null, null))
    }

    @Test
    fun sessionCaloriesPreferTheRecordingAppsOwnActiveCalories() {
        // The watch app wrote 512 kcal for the run; the window total also holds a phone
        // estimate and a Fud AI calculated burn, which must not be added on top.
        assertEquals(512, attributedSessionActiveCalories(sameOriginKcal = 511.6, allWindowKcal = 900.0, ownWindowKcal = 300.0))
    }

    @Test
    fun sessionCaloriesFallBackToOtherAppsWithoutFudAiOwnBurn() {
        // Strava wrote no calories: use the window's active energy minus Fud AI's calculated burn.
        assertEquals(420, attributedSessionActiveCalories(sameOriginKcal = 0.0, allWindowKcal = 720.0, ownWindowKcal = 300.0))
        assertEquals(420, attributedSessionActiveCalories(sameOriginKcal = null, allWindowKcal = 420.0, ownWindowKcal = 0.0))
        // Only Fud AI's own estimate sits in the window: nothing is attributed to the session.
        assertNull(attributedSessionActiveCalories(sameOriginKcal = 0.0, allWindowKcal = 300.0, ownWindowKcal = 300.0))
        assertNull(attributedSessionActiveCalories(sameOriginKcal = 0.0, allWindowKcal = 100.0, ownWindowKcal = 300.0))
        assertNull(attributedSessionActiveCalories(sameOriginKcal = null, allWindowKcal = null, ownWindowKcal = null))
        assertNull(attributedSessionActiveCalories(sameOriginKcal = Double.NaN, allWindowKcal = Double.NaN, ownWindowKcal = 0.0))
    }

    private fun session(
        origin: String,
        id: String = "hc-record-1",
        device: Device? = null,
        laps: List<ExerciseLap> = emptyList()
    ) = ExerciseSessionRecord(
        startTime = start,
        startZoneOffset = null,
        endTime = end,
        endZoneOffset = null,
        metadata = readMetadata(id = id, origin = origin, device = device),
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        title = "Morning Run",
        notes = null,
        segments = emptyList(),
        laps = laps
    )

    /**
     * Metadata as Health Connect returns it on a read: a record id, the writing app's origin,
     * and a modification time. Only the platform fills these, so the library keeps that
     * constructor internal to Kotlin callers; the test reaches it the way the SDK does.
     */
    private fun readMetadata(id: String, origin: String, device: Device?): Metadata =
        Metadata::class.java.getConstructor(
            Int::class.javaPrimitiveType,
            String::class.java,
            DataOrigin::class.java,
            Instant::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Device::class.java
        ).newInstance(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, id, DataOrigin(origin), modified, null, 0L, device)
}
