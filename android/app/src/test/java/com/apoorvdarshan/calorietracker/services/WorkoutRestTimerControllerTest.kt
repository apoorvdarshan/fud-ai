package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.models.WorkoutRestTimer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutRestTimerControllerTest {
    private val base = Instant.parse("2026-10-18T10:00:00Z")
    private val exerciseId = UUID.randomUUID()
    private val setId = UUID.randomUUID()

    private class FakeAlarms : WorkoutRestAlarms {
        val scheduled = mutableListOf<Instant>()
        var cancels = 0
        override fun schedule(timer: WorkoutRestTimer) {
            scheduled += timer.endsAt
        }

        override fun cancel() {
            cancels += 1
        }
    }

    private class FakeStore(var saved: WorkoutRestTimer? = null) : WorkoutRestTimerStore {
        override fun load(): WorkoutRestTimer? = saved
        override fun save(timer: WorkoutRestTimer?) {
            saved = timer
        }
    }

    /** Moves the phone's wall clock without moving the scheduler's (uptime) clock. */
    private var clockShiftMillis = 0L

    private fun TestScope.controller(
        alarms: FakeAlarms, store: FakeStore? = null, hapticsEnabled: Boolean = true
    ) = WorkoutRestTimerController(
        scope = backgroundScope,
        alarms = alarms,
        store = store,
        clock = { base.plusMillis(testScheduler.currentTime + clockShiftMillis) },
        canPlayForegroundHaptic = { hapticsEnabled }
    )

    private fun TestScope.collectCompletions(rest: WorkoutRestTimerController): MutableList<Unit> {
        val completions = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rest.completions.toList(completions) }
        return completions
    }

    @Test
    fun turningOffHapticsLeavesTheForegroundRestNotificationArmed() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms, hapticsEnabled = false)
        val completions = collectCompletions(rest)
        rest.setAppInForeground(true)
        rest.start(exerciseId, setId, "Bench Press", 10)
        val end = base.plusSeconds(10)

        advanceTimeBy(10_000)
        runCurrent()

        assertNull(rest.timer.value)
        assertEquals(0, alarms.cancels)
        assertTrue(completions.isEmpty())
        assertTrue(rest.shouldNotify(end.toEpochMilli()))
    }

    @Test
    fun startingArmsTheBackgroundAlertAndSkippingCancelsIt() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)

        rest.start(exerciseId, setId, "Bench Press", 90)
        assertEquals(base.plusSeconds(90), rest.timer.value?.endsAt)
        assertEquals(listOf(base.plusSeconds(90)), alarms.scheduled)

        rest.skip()
        assertNull(rest.timer.value)
        assertEquals(1, alarms.cancels)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, alarms.scheduled.size)
    }

    @Test
    fun anOffRestTimerStartsNothing() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        rest.start(exerciseId, setId, "Bench Press", 0)
        assertNull(rest.timer.value)
        assertTrue(alarms.scheduled.isEmpty())
    }

    @Test
    fun addingTimeReschedulesTheAlertAndDelaysTheEnd() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        rest.setAppInForeground(true)
        rest.start(exerciseId, setId, "Bench Press", 30)

        advanceTimeBy(20_000)
        rest.extend()
        assertEquals(listOf(base.plusSeconds(30), base.plusSeconds(45)), alarms.scheduled)

        advanceTimeBy(15_000)
        runCurrent()
        // The original end has passed, but the extended rest is still running.
        assertEquals(base.plusSeconds(45), rest.timer.value?.endsAt)
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(rest.timer.value)
    }

    @Test
    fun aRestEndingInTheForegroundAlertsInAppInsteadOfByNotification() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        val completions = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rest.completions.toList(completions) }
        rest.setAppInForeground(true)

        rest.start(exerciseId, setId, "Bench Press", 60)
        advanceTimeBy(60_001)
        runCurrent()

        assertNull(rest.timer.value)
        assertEquals(1, completions.size)
        assertEquals(1, alarms.cancels)
    }

    @Test
    fun aRestEndingInTheBackgroundLeavesTheNotificationToTheAlarm() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        val completions = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rest.completions.toList(completions) }
        rest.setAppInForeground(false)

        rest.start(exerciseId, setId, "Bench Press", 60)
        advanceTimeBy(60_001)
        runCurrent()

        assertNull(rest.timer.value)
        assertTrue(completions.isEmpty())
        assertEquals(0, alarms.cancels)
    }

    @Test
    fun restartingForAnotherSetReplacesTheCountdown() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        val nextSet = UUID.randomUUID()
        rest.start(exerciseId, setId, "Bench Press", 90)
        advanceTimeBy(40_000)
        rest.start(exerciseId, nextSet, "Bench Press", 90)

        assertEquals(nextSet, rest.timer.value?.setId)
        assertEquals(base.plusSeconds(130), rest.timer.value?.endsAt)
        advanceTimeBy(60_000)
        runCurrent()
        // The first set's end no longer clears the second set's rest.
        assertEquals(nextSet, rest.timer.value?.setId)
    }

    @Test
    fun theRunningRestIsSavedAndClearedWhenItEnds() = runTest {
        val store = FakeStore()
        val rest = controller(FakeAlarms(), store)

        rest.start(exerciseId, setId, "Bench Press", 60)
        assertEquals(rest.timer.value, store.saved)
        rest.extend()
        assertEquals(base.plusSeconds(75), store.saved?.endsAt)
        rest.skip()
        assertNull(store.saved)

        rest.start(exerciseId, setId, "Bench Press", 60)
        advanceTimeBy(60_001)
        runCurrent()
        assertNull(store.saved)
    }

    @Test
    fun aRestSavedBeforeTheProcessRestartedKeepsCountingDownAndAlertsInApp() = runTest {
        val saved = requireNotNull(WorkoutRestTimer.start(exerciseId, setId, "Bench Press", 90, base.minusSeconds(30)))
        val alarms = FakeAlarms()
        val rest = controller(alarms, FakeStore(saved))
        val completions = collectCompletions(rest)
        rest.setAppInForeground(true)

        // The countdown comes back, its alarm is armed again, and the set counts as rested.
        assertEquals(saved, rest.timer.value)
        assertEquals(listOf(saved.endsAt), alarms.scheduled)
        assertTrue(rest.hasRested(setId))
        // Its alarm arriving first, in the foreground, stays silent: the haptic follows.
        assertFalse(rest.shouldNotify(saved.endsAt.toEpochMilli()))

        advanceTimeBy(60_001)
        runCurrent()
        assertNull(rest.timer.value)
        assertEquals(1, completions.size)
        assertEquals(1, alarms.cancels)
    }

    @Test
    fun aRestThatEndedWhileTheAppWasNotRunningIsLeftToItsNotification() = runTest {
        val saved = requireNotNull(WorkoutRestTimer.start(exerciseId, setId, "Bench Press", 60, base.minusSeconds(90)))
        val store = FakeStore(saved)
        val alarms = FakeAlarms()
        val rest = controller(alarms, store)
        val completions = collectCompletions(rest)
        rest.setAppInForeground(true)

        assertNull(rest.timer.value)
        assertNull(store.saved)
        assertTrue(alarms.scheduled.isEmpty())
        assertTrue(completions.isEmpty())
        // Its alarm may still be on the way; it posts even though the app is now open.
        assertTrue(rest.shouldNotify(saved.endsAt.toEpochMilli()))
    }

    @Test
    fun aLateAlarmForARestThatEndedInTheBackgroundStillNotifiesInTheForeground() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        val completions = collectCompletions(rest)
        rest.setAppInForeground(false)
        rest.start(exerciseId, setId, "Bench Press", 60)
        val endsAt = requireNotNull(rest.timer.value).endsAt.toEpochMilli()

        advanceTimeBy(60_001)
        runCurrent()
        rest.setAppInForeground(true)

        assertNull(rest.timer.value)
        assertTrue(completions.isEmpty())
        assertEquals(0, alarms.cancels)
        assertTrue(rest.shouldNotify(endsAt))
    }

    @Test
    fun inTheForegroundOnlyARestThatWasNotAnnouncedInAppNotifies() = runTest {
        val rest = controller(FakeAlarms())
        rest.setAppInForeground(true)
        rest.start(exerciseId, setId, "Bench Press", 60)
        val endsAt = requireNotNull(rest.timer.value).endsAt.toEpochMilli()

        // Still counting down, then skipped: the in-app countdown covers it either way.
        assertFalse(rest.shouldNotify(endsAt))
        rest.skip()
        assertFalse(rest.shouldNotify(endsAt))

        // In the background every alarm posts.
        rest.setAppInForeground(false)
        assertTrue(rest.shouldNotify(endsAt))
    }

    @Test
    fun aRestThatRanOutWhileThePhoneSleptEndsOnReturnWithoutAHaptic() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        val completions = collectCompletions(rest)
        rest.start(exerciseId, setId, "Bench Press", 60)
        val endsAt = requireNotNull(rest.timer.value).endsAt.toEpochMilli()

        // Deep sleep: the wall clock runs on while the uptime clock, and the wait, stand still.
        clockShiftMillis = 120_000
        rest.setAppInForeground(true)

        assertNull(rest.timer.value)
        assertTrue(completions.isEmpty())
        assertEquals(0, alarms.cancels)
        assertTrue(rest.shouldNotify(endsAt))
    }

    @Test
    fun aClockMovedBackDuringRestDelaysTheEndInsteadOfStrandingIt() = runTest {
        val rest = controller(FakeAlarms())
        val completions = collectCompletions(rest)
        rest.setAppInForeground(true)
        rest.start(exerciseId, setId, "Bench Press", 60)

        advanceTimeBy(30_000)
        clockShiftMillis = -10_000
        advanceTimeBy(30_001)
        runCurrent()
        // The countdown on screen still shows 10 s, and the rest keeps running for them.
        assertEquals(base.plusSeconds(60), rest.timer.value?.endsAt)
        assertTrue(completions.isEmpty())

        advanceTimeBy(10_000)
        runCurrent()
        assertNull(rest.timer.value)
        assertEquals(1, completions.size)
    }

    @Test
    fun aSetCountsAsRestedOnceItsRestStarts() = runTest {
        val rest = controller(FakeAlarms())
        assertFalse(rest.hasRested(setId))
        rest.start(exerciseId, setId, "Bench Press", 0)
        assertFalse(rest.hasRested(setId))
        rest.start(exerciseId, setId, "Bench Press", 90)
        rest.skip()
        assertTrue(rest.hasRested(setId))
    }
}
