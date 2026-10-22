package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.AIProvider

/** The kind of request an analysis runs, used for the local duration history. */
enum class AnalysisKind {
    /** A typed description. */
    TEXT,

    /** One or more photos. */
    IMAGE,

    /** A barcode lookup that does not call an AI provider. */
    BARCODE,
}

/**
 * The truthful stages of an AI meal analysis.
 *
 * The providers used by Fud AI answer with one complete JSON document, so there is no
 * token or byte level progress to report. These stages therefore only describe work
 * that actually happened, and the UI shows an indeterminate indicator for them.
 */
enum class AnalysisStage {
    /** Preparing the request: image processing and prompt assembly. */
    PREPARING,

    /** The request is with the provider; waiting for the provider's answer. */
    REQUESTING,

    /** The provider answered and the response is being read. */
    RESPONDING,

    /** The nutrition data is being checked and the review screen is prepared. */
    VALIDATING,

    /** The primary route failed and the backup model is being tried. */
    FALLBACK,
}

/** How the running analysis ended. */
enum class AnalysisOutcome {
    RUNNING,
    COMPLETE,
    FAILED,
    CANCELLED,
}

/**
 * The state of one analysis, as shown by the progress UI.
 *
 * @param stage the last reported stage.
 * @param attempt 1 for the primary request, 2 or more while a backup model is used.
 * @param estimateSeconds the usual duration of this provider/model combination, taken
 *   from the local duration history. Null when there is not enough history, so the UI
 *   shows the elapsed time only instead of a fabricated countdown.
 * @param startedAtMillis the monotonic start of the analysis, so the UI can show a
 *   running elapsed time without the service pushing updates every second.
 */
data class AnalysisProgressState(
    val kind: AnalysisKind,
    val stage: AnalysisStage,
    val attempt: Int = 1,
    val provider: AIProvider? = null,
    val model: String? = null,
    val estimateSeconds: IntRange? = null,
    val startedAtMillis: Long = 0,
    val outcome: AnalysisOutcome = AnalysisOutcome.RUNNING,
) {
    /**
     * Seconds since the analysis started. The clock is monotonic, so the running
     * time cannot jump backwards when the wall clock changes.
     */
    fun elapsedSeconds(nowMillis: Long): Long =
        if (startedAtMillis <= 0L) 0L else ((nowMillis - startedAtMillis) / 1000).coerceAtLeast(0)

    /**
     * True while the local estimate still fits the running request.
     *
     * The hint is hidden once the request runs past the upper end of the band: a
     * "usually about 10 s" next to a 40 s runtime would be a lie.
     */
    fun estimateStillApplies(elapsedSeconds: Long): Boolean {
        val estimate = estimateSeconds ?: return false
        return elapsedSeconds <= estimate.last
    }

    /**
     * True when this finished run is a clean local-history sample.
     *
     * A backup run's total includes the failed primary wait, so storing it under the
     * backup provider would make that model look slower than it is. Barcode lookups
     * and hosted runs have no provider/model to attribute the sample to.
     */
    val canRecordDuration: Boolean
        get() = outcome == AnalysisOutcome.COMPLETE &&
            kind != AnalysisKind.BARCODE &&
            attempt == 1 &&
            provider != null &&
            !model.isNullOrBlank()
}

/** One progress event of an analysis. */
data class AnalysisProgressUpdate(
    val stage: AnalysisStage,
    /**
     * 1 for the primary request, 2 or more for a backup model. Null keeps the
     * current attempt, so a stage-only update cannot reset a running fallback.
     */
    val attempt: Int? = null,
    /** Provider that is handling the current attempt, known once the request is sent. */
    val provider: AIProvider? = null,
    /** Model that is handling the current attempt. */
    val model: String? = null,
    /** Local history based estimate of the usual duration, in seconds. */
    val estimateSeconds: IntRange? = null,
)

/** Receives progress events while an analysis runs. */
fun interface AnalysisProgressReporter {
    fun onProgress(update: AnalysisProgressUpdate)
}

/**
 * A monotonic millisecond clock, shared by the tracker and the UI so the elapsed time
 * the user sees always matches the tracked one.
 */
internal fun analysisClockMillis(): Long = System.nanoTime() / 1_000_000

/**
 * A started analysis. The number identifies the run: reports of an older run are
 * ignored, so a request that answers late cannot overwrite the progress of the
 * newer one.
 */
data class AnalysisProgressRun(
    val number: Long,
    val initial: AnalysisProgressState,
)

/**
 * Tracks the state of one analysis so the UI can show an honest progress indicator.
 *
 * The tracker only records work that really happened: it never invents a percentage and
 * it never counts down. The elapsed time comes from a monotonic clock, and the optional
 * estimate is a band of durations this device measured for the same provider and model.
 */
class AnalysisProgressTracker(
    private val now: () -> Long = { analysisClockMillis() },
) {
    private var state: AnalysisProgressState? = null
    private var runNumber: Long = 0

    val current: AnalysisProgressState? get() = state

    /** The number of the newest started analysis. */
    val currentRunNumber: Long get() = runNumber

    /** Starts a new analysis and returns the run that identifies it. */
    fun start(kind: AnalysisKind): AnalysisProgressRun {
        runNumber += 1
        val initial = AnalysisProgressState(
            kind = kind,
            stage = AnalysisStage.PREPARING,
            startedAtMillis = now(),
        )
        state = initial
        return AnalysisProgressRun(number = runNumber, initial = initial)
    }

    /** True while the given run is the one being tracked. */
    fun isRunning(runNumber: Long): Boolean = runNumber == this.runNumber && state != null

    /**
     * Applies a progress event of the given run.
     *
     * Null when the run is outdated: a finished analysis must not report into the
     * progress of a newer one.
     */
    fun onProgress(runNumber: Long, update: AnalysisProgressUpdate): AnalysisProgressState? {
        if (!isRunning(runNumber)) return null
        val running = state ?: return null
        // The first ending wins: a late stage report after cancel/error/complete must
        // not make the finished run look active again.
        if (running.outcome != AnalysisOutcome.RUNNING) return null
        // A new attempt (the backup model) brings its own estimate: the primary's
        // duration says nothing about the fallback. A stage-only update has no
        // attempt, so validation after a fallback keeps that attempt and its
        // estimate instead of silently falling back to the primary.
        val nextAttempt = update.attempt ?: running.attempt
        val attemptChanged = update.attempt != null && update.attempt != running.attempt
        val estimate = if (attemptChanged) {
            update.estimateSeconds
        } else {
            update.estimateSeconds ?: running.estimateSeconds
        }
        val updated = running.copy(
            stage = update.stage,
            attempt = nextAttempt,
            provider = update.provider ?: running.provider,
            model = update.model ?: running.model,
            estimateSeconds = estimate,
        )
        state = updated
        return updated
    }

    /**
     * Ends the given run. Null when the run is outdated or nothing was tracked.
     *
     * The first outcome wins: a cancellation that is followed by the task's own error
     * must stay a cancellation.
     */
    fun finish(runNumber: Long, outcome: AnalysisOutcome): AnalysisProgressState? {
        if (!isRunning(runNumber)) return null
        val running = state ?: return null
        if (running.outcome != AnalysisOutcome.RUNNING) return running
        val updated = running.copy(outcome = outcome)
        state = updated
        return updated
    }

    /**
     * Drops the tracked analysis so a later one starts from a clean state.
     *
     * The run number moves on, so a late report of the finished run is ignored.
     */
    fun reset() {
        state = null
        runNumber += 1
    }

    /** Milliseconds since the analysis started, or 0 when nothing is running. */
    fun elapsedMillis(): Long {
        val startedAt = state?.startedAtMillis ?: return 0
        return (now() - startedAt).coerceAtLeast(0)
    }
}
