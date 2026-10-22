package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.AIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the local duration history behind the "usually about N s" hint (#635):
 * the stored format, the sample threshold and the breakdown per provider/model/kind.
 */
class AnalysisDurationHistoryTest {

    private val key = AnalysisDurationEstimate.key(
        AIProvider.OPENAI,
        "gpt-5.6-luna",
        AnalysisDurationEstimate.Kind.TEXT
    )

    @Test
    fun nothingStoredMeansNoEstimate() {
        assertTrue(AnalysisDurationEstimate.readDurations(null).isEmpty())
        assertTrue(AnalysisDurationEstimate.readDurations("").isEmpty())
        assertNull(
            AnalysisDurationEstimate.estimateSeconds(
                AnalysisDurationEstimate.readDurations(null)[key].orEmpty()
            )
        )
    }

    @Test
    fun aBrokenValueStartsOverInsteadOfCrashing() {
        assertTrue(AnalysisDurationEstimate.readDurations("{not json").isEmpty())
    }

    @Test
    fun theDurationsSurviveTheRoundTrip() {
        val durations = mapOf(
            key to listOf(4_000L, 5_000L, 6_000L),
            AnalysisDurationEstimate.key(
                AIProvider.OPENAI,
                "gpt-5.6-luna",
                AnalysisDurationEstimate.Kind.IMAGE
            ) to listOf(9_000L, 10_000L, 11_000L)
        )

        val stored = AnalysisDurationEstimate.encodeDurations(durations)
        val restored = AnalysisDurationEstimate.readDurations(stored)

        assertEquals(durations, restored)
        assertEquals(4..5, AnalysisDurationEstimate.estimateSeconds(restored[key].orEmpty()))
    }

    @Test
    fun theEstimateOnlyAppearsAfterEnoughRuns() {
        var durations = emptyMap<String, List<Long>>()
        repeat(AnalysisDurationEstimate.MIN_SAMPLES - 1) { run ->
            durations = durations + (key to (durations[key].orEmpty() + (run + 1) * 1_000L))
            assertNull(
                "after ${run + 1} run(s) there is no honest estimate yet",
                AnalysisDurationEstimate.estimateSeconds(durations[key].orEmpty())
            )
        }
        durations = durations + (key to (durations[key].orEmpty() + 3_000L))
        assertEquals(1..2, AnalysisDurationEstimate.estimateSeconds(durations[key].orEmpty()))
    }

    @Test
    fun onlyTheLastSamplesAreKept() {
        val samples = (1..20).map { it * 1_000L }
        val kept = samples.takeLast(AnalysisDurationEstimate.MAX_SAMPLES)
        assertEquals(AnalysisDurationEstimate.MAX_SAMPLES, kept.size)
        // The old samples are gone, so the band follows the recent ones.
        assertEquals(14..18, AnalysisDurationEstimate.estimateSeconds(kept))
    }
}
