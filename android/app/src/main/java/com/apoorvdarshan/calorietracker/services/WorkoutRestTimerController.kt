package com.apoorvdarshan.calorietracker.services

import android.content.Context
import com.apoorvdarshan.calorietracker.models.WorkoutRestTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The background alert for a rest that ends while Fud AI is not in front of the user. */
interface WorkoutRestAlarms {
    fun schedule(timer: WorkoutRestTimer)
    fun cancel()
}

/** Keeps the running rest across a process restart, so its countdown and in-app alert come back. */
interface WorkoutRestTimerStore {
    fun load(): WorkoutRestTimer?
    fun save(timer: WorkoutRestTimer?)
}

/** [WorkoutRestTimerStore] in a small private preferences file of its own. */
class SharedPreferencesWorkoutRestTimerStore(context: Context) : WorkoutRestTimerStore {
    private val prefs = context.getSharedPreferences("fudai_workout_rest", Context.MODE_PRIVATE)

    override fun load(): WorkoutRestTimer? = runCatching {
        val endsAt = prefs.getLong(K_ENDS_AT, 0L).takeIf { it > 0 } ?: return null
        WorkoutRestTimer(
            exerciseId = UUID.fromString(prefs.getString(K_EXERCISE_ID, null) ?: return null),
            setId = UUID.fromString(prefs.getString(K_SET_ID, null) ?: return null),
            exerciseName = prefs.getString(K_EXERCISE_NAME, null).orEmpty(),
            startedAt = Instant.ofEpochMilli(prefs.getLong(K_STARTED_AT, endsAt)),
            endsAt = Instant.ofEpochMilli(endsAt)
        )
    }.getOrNull()

    override fun save(timer: WorkoutRestTimer?) {
        val editor = prefs.edit().clear()
        if (timer != null) {
            editor.putString(K_EXERCISE_ID, timer.exerciseId.toString())
                .putString(K_SET_ID, timer.setId.toString())
                .putString(K_EXERCISE_NAME, timer.exerciseName)
                .putLong(K_STARTED_AT, timer.startedAt.toEpochMilli())
                .putLong(K_ENDS_AT, timer.endsAt.toEpochMilli())
        }
        editor.apply()
    }

    private companion object {
        const val K_EXERCISE_ID = "exercise_id"
        const val K_SET_ID = "set_id"
        const val K_EXERCISE_NAME = "exercise_name"
        const val K_STARTED_AT = "started_at"
        const val K_ENDS_AT = "ends_at"
    }
}

/**
 * Owns the one rest countdown that runs after a logged strength set (iOS
 * `WorkoutRestTimerController`). It is app-scoped, so switching tabs or days keeps it, and the
 * running rest is saved, so a process restart brings it back.
 *
 * Every rest is announced exactly once. One that ends while the app is in the foreground is
 * announced through [completions] (the UI turns that into a haptic) and its alarm is cancelled.
 * Any other rest, one that ended in the background or while the app was not running, is
 * announced by its alarm's notification, even if that alarm arrives once the app is open again
 * (see [shouldNotify]).
 */
class WorkoutRestTimerController(
    private val scope: CoroutineScope,
    private val alarms: WorkoutRestAlarms,
    private val store: WorkoutRestTimerStore? = null,
    private val clock: () -> Instant = Instant::now,
    private val canPlayForegroundHaptic: () -> Boolean = { true }
) {
    private val _timer = MutableStateFlow<WorkoutRestTimer?>(null)
    val timer: StateFlow<WorkoutRestTimer?> = _timer.asStateFlow()

    private val _completions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val completions: SharedFlow<Unit> = _completions.asSharedFlow()

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    /** The end (epoch millis) of the last rest that finished without its in-app alert. */
    @Volatile
    private var unannouncedEndMillis: Long? = null

    /** Sets whose rest already started; filling their reps in again is a correction. */
    private val restedSetIds = mutableSetOf<UUID>()

    private var completionJob: Job? = null

    init {
        store?.load()?.let { saved ->
            restedSetIds += saved.setId
            if (saved.isFinished(clock())) {
                // It ended while the app was not running, so its alarm announces it.
                unannouncedEndMillis = saved.endsAt.toEpochMilli()
                store.save(null)
            } else {
                // Re-armed too, in case the alarm went with the process (a force stop).
                replace(saved)
            }
        }
    }

    fun setAppInForeground(foreground: Boolean) {
        isAppInForeground = foreground
        // The wait runs on the uptime clock, which stops while the phone sleeps: a rest that
        // ended meanwhile is wrapped up on return rather than at the wait's next step.
        if (foreground) _timer.value?.let { if (it.isFinished(clock())) finish(it) }
    }

    fun start(exerciseId: UUID, setId: UUID, exerciseName: String, durationSeconds: Int) {
        val next = WorkoutRestTimer.start(exerciseId, setId, exerciseName, durationSeconds, clock()) ?: return
        restedSetIds += setId
        replace(next)
    }

    /** Whether a rest has already started after this set, here or before a process restart. */
    fun hasRested(setId: UUID): Boolean = setId in restedSetIds

    /** +15 s. */
    fun extend() {
        val current = _timer.value ?: return
        replace(current.extended(at = clock()))
    }

    fun skip() {
        completionJob?.cancel()
        completionJob = null
        setTimer(null)
        alarms.cancel()
    }

    /**
     * Whether the alarm for the rest ending at [endsAtMillis] should post its notification. In
     * the background it always does. In the foreground only for a rest whose end was not
     * announced in-app: a rest still counting down, or one just skipped or extended, gets the
     * in-app alert or none.
     */
    fun shouldNotify(endsAtMillis: Long): Boolean =
        !isAppInForeground || unannouncedEndMillis == endsAtMillis ||
            (!canPlayForegroundHaptic() && timer.value?.endsAt?.toEpochMilli() == endsAtMillis)

    private fun replace(next: WorkoutRestTimer) {
        setTimer(next)
        alarms.schedule(next)
        completionJob?.cancel()
        completionJob = scope.launch {
            // Waits on the wall clock the countdown shows, in steps of at most a second, so a
            // clock change during rest cannot leave it finished on screen but still running.
            while (true) {
                val waitMillis = Duration.between(clock(), next.endsAt).toMillis()
                if (waitMillis <= 0) break
                delay(waitMillis.coerceAtMost(MAX_WAIT_STEP_MILLIS))
            }
            finish(next)
        }
    }

    /** Runs when the countdown reaches zero, or as soon as a frozen process resumes after it did. */
    private fun finish(expected: WorkoutRestTimer) {
        if (_timer.value != expected) return
        val now = clock()
        if (!expected.isFinished(now)) return
        if (isAppInForeground && expected.shouldAlertInApp(now) && canPlayForegroundHaptic()) {
            // Ended in front of the user: the haptic is the alert, so no notification follows.
            alarms.cancel()
            _completions.tryEmit(Unit)
        } else {
            // When no haptic will play, leave the alarm's notification intact.
            unannouncedEndMillis = expected.endsAt.toEpochMilli()
        }
        completionJob?.cancel()
        completionJob = null
        setTimer(null)
    }

    private fun setTimer(next: WorkoutRestTimer?) {
        _timer.value = next
        store?.save(next)
    }

    private companion object {
        const val MAX_WAIT_STEP_MILLIS = 1_000L
    }
}
