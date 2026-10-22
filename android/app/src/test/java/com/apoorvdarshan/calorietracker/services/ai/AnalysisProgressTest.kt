package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.data.AnalysisDurationEstimate
import com.apoorvdarshan.calorietracker.models.AIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the progress model of the AI meal analysis (#635): the stage sequence, the
 * elapsed time, the fallback attempt and the local duration estimate.
 */
class AnalysisProgressTest {

    private class FakeClock(var millis: Long = 0) {
        fun now(): Long = millis
    }

    @Test
    fun startReportsThePreparingStage() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.IMAGE)
        assertEquals(AnalysisStage.PREPARING, run.initial.stage)
        assertEquals(AnalysisKind.IMAGE, run.initial.kind)
        assertEquals(AnalysisOutcome.RUNNING, run.initial.outcome)
        assertEquals(1, run.initial.attempt)
    }

    @Test
    fun stagesFollowTheAnalysisOrder() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.TEXT)

        tracker.onProgress(run.number, AnalysisProgressUpdate(AnalysisStage.REQUESTING, provider = AIProvider.OPENAI))
        tracker.onProgress(run.number, AnalysisProgressUpdate(AnalysisStage.RESPONDING))
        val state = tracker.onProgress(run.number, AnalysisProgressUpdate(AnalysisStage.VALIDATING))

        assertNotNull(state)
        assertEquals(AnalysisStage.VALIDATING, state!!.stage)
        assertEquals(AIProvider.OPENAI, state.provider)
        assertEquals(AnalysisOutcome.RUNNING, state.outcome)
    }

    @Test
    fun theFallbackAttemptKeepsItsProviderAndModel() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.TEXT)

        val state = tracker.onProgress(
            run.number,
            AnalysisProgressUpdate(
                stage = AnalysisStage.FALLBACK,
                attempt = 2,
                provider = AIProvider.OPENROUTER,
                model = "openai/gpt-5.6-luna"
            )
        )

        assertEquals(AnalysisStage.FALLBACK, state?.stage)
        assertEquals(2, state?.attempt)
        assertEquals(AIProvider.OPENROUTER, state?.provider)
        assertEquals("openai/gpt-5.6-luna", state?.model)
    }

    @Test
    fun theFallbackAttemptUsesItsOwnEstimate() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.TEXT)

        tracker.onProgress(
            run.number,
            AnalysisProgressUpdate(AnalysisStage.REQUESTING, provider = AIProvider.OPENAI, estimateSeconds = 5..12)
        )
        // The backup model has no history yet: the primary's band says nothing about it.
        val state = tracker.onProgress(
            run.number,
            AnalysisProgressUpdate(AnalysisStage.FALLBACK, attempt = 2, provider = AIProvider.OPENROUTER)
        )

        assertNull(state?.estimateSeconds)
    }

    @Test
    fun theFallbackAttemptKeepsAMeasuredEstimateOfItsOwn() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.TEXT)

        tracker.onProgress(
            run.number,
            AnalysisProgressUpdate(AnalysisStage.REQUESTING, provider = AIProvider.OPENAI, estimateSeconds = 5..12)
        )
        val state = tracker.onProgress(
            run.number,
            AnalysisProgressUpdate(
                AnalysisStage.FALLBACK,
                attempt = 2,
                provider = AIProvider.OPENROUTER,
                estimateSeconds = 20..40
            )
        )

        assertEquals(20..40, state?.estimateSeconds)
    }

    @Test
    fun theEstimateSurvivesLaterUpdatesThatDoNotCarryIt() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.TEXT)

        tracker.onProgress(
            run.number,
            AnalysisProgressUpdate(AnalysisStage.REQUESTING, estimateSeconds = 5..12)
        )
        val state = tracker.onProgress(run.number, AnalysisProgressUpdate(AnalysisStage.RESPONDING))

        assertEquals(5..12, state?.estimateSeconds)
    }

    @Test
    fun elapsedTimeFollowsTheMonotonicClock() {
        val clock = FakeClock()
        val tracker = AnalysisProgressTracker { clock.now() }
        tracker.start(AnalysisKind.TEXT)

        clock.millis = 4_500
        assertEquals(4_500L, tracker.elapsedMillis())

        clock.millis = 9_000
        assertEquals(9_000L, tracker.elapsedMillis())
    }

    @Test
    fun theElapsedTimeNeverRunsBackwards() {
        val clock = FakeClock(5_000)
        val tracker = AnalysisProgressTracker { clock.now() }
        val run = tracker.start(AnalysisKind.TEXT)

        assertEquals(0L, run.initial.elapsedSeconds(clock.now()))

        clock.millis = 9_000
        assertEquals(4L, run.initial.elapsedSeconds(clock.now()))

        // A clock that jumps back before the start must not make the time negative.
        clock.millis = 1_000
        assertEquals(0L, run.initial.elapsedSeconds(clock.now()))
    }

    @Test
    fun outcomesAreRecordedForSuccessFailureAndCancellation() {
        val tracker = AnalysisProgressTracker { 0L }

        val complete = tracker.start(AnalysisKind.TEXT)
        assertEquals(AnalysisOutcome.COMPLETE, tracker.finish(complete.number, AnalysisOutcome.COMPLETE)?.outcome)

        val failed = tracker.start(AnalysisKind.TEXT)
        assertEquals(AnalysisOutcome.FAILED, tracker.finish(failed.number, AnalysisOutcome.FAILED)?.outcome)

        val cancelled = tracker.start(AnalysisKind.TEXT)
        assertEquals(AnalysisOutcome.CANCELLED, tracker.finish(cancelled.number, AnalysisOutcome.CANCELLED)?.outcome)
    }

    @Test
    fun aTimeoutIsAFailedAnalysis() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.TEXT)

        tracker.onProgress(run.number, AnalysisProgressUpdate(AnalysisStage.REQUESTING))
        // The provider call runs into the request timeout and the request fails.
        val state = tracker.finish(run.number, AnalysisOutcome.FAILED)

        assertEquals(AnalysisOutcome.FAILED, state?.outcome)
        assertEquals(AnalysisStage.REQUESTING, state?.stage)
    }

    @Test
    fun resetDropsTheTrackedAnalysis() {
        val tracker = AnalysisProgressTracker { 0L }
        tracker.start(AnalysisKind.TEXT)
        tracker.reset()
        assertNull(tracker.current)
        assertEquals(0L, tracker.elapsedMillis())
    }

    @Test
    fun aNewRunStartsFromACleanState() {
        val clock = FakeClock()
        val tracker = AnalysisProgressTracker { clock.now() }
        val first = tracker.start(AnalysisKind.TEXT)
        tracker.onProgress(first.number, AnalysisProgressUpdate(AnalysisStage.REQUESTING, estimateSeconds = 5..12))
        tracker.finish(first.number, AnalysisOutcome.CANCELLED)
        tracker.reset()

        clock.millis = 2_000
        val second = tracker.start(AnalysisKind.IMAGE)

        assertEquals(AnalysisStage.PREPARING, second.initial.stage)
        assertEquals(AnalysisKind.IMAGE, second.initial.kind)
        assertEquals(1, second.initial.attempt)
        assertNull(second.initial.estimateSeconds)
        assertEquals(2_000L, second.initial.startedAtMillis)
        assertEquals(0L, tracker.elapsedMillis())
    }

    @Test
    fun aLateReportOfAnOlderRunIsIgnored() {
        val tracker = AnalysisProgressTracker { 0L }
        val first = tracker.start(AnalysisKind.TEXT)
        tracker.onProgress(first.number, AnalysisProgressUpdate(AnalysisStage.REQUESTING))

        val second = tracker.start(AnalysisKind.IMAGE)
        // The finished request reports late and must not overwrite the newer run.
        val late = tracker.onProgress(first.number, AnalysisProgressUpdate(AnalysisStage.VALIDATING))

        assertNull(late)
        assertEquals(AnalysisStage.PREPARING, tracker.current?.stage)
        assertEquals(AnalysisKind.IMAGE, tracker.current?.kind)
        assertTrue(tracker.isRunning(second.number))
        assertFalse(tracker.isRunning(first.number))
    }

    @Test
    fun anEndedRunCannotReportAnymore() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.TEXT)
        tracker.finish(run.number, AnalysisOutcome.COMPLETE)
        tracker.reset()

        assertNull(tracker.onProgress(run.number, AnalysisProgressUpdate(AnalysisStage.RESPONDING)))
        assertNull(tracker.finish(run.number, AnalysisOutcome.FAILED))
    }

    @Test
    fun aFinishedRunCannotChangeOrBeEndedAgain() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.TEXT)
        tracker.finish(run.number, AnalysisOutcome.COMPLETE)

        // The provider may still deliver a late stage report; it must not make the
        // finished run look active again.
        assertNull(tracker.onProgress(run.number, AnalysisProgressUpdate(AnalysisStage.RESPONDING)))
        // The first outcome wins: a late error cannot turn success into failure.
        val second = tracker.finish(run.number, AnalysisOutcome.FAILED)

        assertEquals(AnalysisOutcome.COMPLETE, second?.outcome)
        assertEquals(AnalysisStage.PREPARING, tracker.current?.stage)
    }

    @Test
    fun theEstimateNeedsEnoughLocalHistory() {
        assertNull(AnalysisDurationEstimate.estimateSeconds(emptyList()))
        assertNull(AnalysisDurationEstimate.estimateSeconds(listOf(2_000, 3_000)))
        assertEquals(2..3, AnalysisDurationEstimate.estimateSeconds(listOf(2_000, 3_000, 4_000)))
    }

    @Test
    fun theEstimateIgnoresASingleOutlier() {
        val samples = listOf(4_000L, 4_200L, 4_500L, 4_600L, 90_000L)
        val estimate = AnalysisDurationEstimate.estimateSeconds(samples)
        assertTrue(estimate != null)
        // The 90 s outlier must not widen the band into a useless range.
        assertTrue(estimate!!.last <= 10)
        assertTrue(estimate.first >= 3)
    }

    @Test
    fun theEstimateIsStoredPerProviderModelAndKind() {
        val textKey = AnalysisDurationEstimate.key(AIProvider.OPENAI, "gpt-5.6-luna", AnalysisDurationEstimate.Kind.TEXT)
        val imageKey = AnalysisDurationEstimate.key(AIProvider.OPENAI, "gpt-5.6-luna", AnalysisDurationEstimate.Kind.IMAGE)
        val otherModelKey = AnalysisDurationEstimate.key(AIProvider.OPENAI, "gpt-5.6-terra", AnalysisDurationEstimate.Kind.TEXT)
        assertTrue(textKey != imageKey)
        assertTrue(textKey != otherModelKey)
        assertFalse(textKey.contains(" "))
    }

    @Test
    fun theEstimateIsHiddenOnceTheRequestRunsPastIt() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.TEXT)
        val state = tracker.onProgress(
            run.number,
            AnalysisProgressUpdate(AnalysisStage.REQUESTING, estimateSeconds = 5..12)
        )

        assertNotNull(state)
        // Inside the band the hint helps.
        assertTrue(state!!.estimateStillApplies(0))
        assertTrue(state.estimateStillApplies(12))
        // Past the upper end it would be a lie, so it goes away.
        assertFalse(state.estimateStillApplies(13))
        assertFalse(state.estimateStillApplies(40))
    }

    @Test
    fun withoutAnEstimateThereIsNothingToShow() {
        val tracker = AnalysisProgressTracker { 0L }
        val run = tracker.start(AnalysisKind.TEXT)
        val state = tracker.onProgress(run.number, AnalysisProgressUpdate(AnalysisStage.REQUESTING))

        assertNotNull(state)
        assertFalse(state!!.estimateStillApplies(0))
    }
}
