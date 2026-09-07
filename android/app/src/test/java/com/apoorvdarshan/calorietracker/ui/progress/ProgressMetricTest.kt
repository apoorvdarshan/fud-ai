package com.apoorvdarshan.calorietracker.ui.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressMetricTest {
    @Test
    fun conditionalMetricsKeepApprovedPreferenceOrder() {
        assertEquals(
            listOf(
                ProgressMetric.WEIGHT,
                ProgressMetric.BODY_FAT,
                ProgressMetric.WORKOUTS
            ),
            availableProgressMetrics(bodyFatAvailable = true, workoutHistoryAvailable = true)
        )
        assertEquals(
            listOf(ProgressMetric.WEIGHT),
            availableProgressMetrics(bodyFatAvailable = false, workoutHistoryAvailable = false)
        )
    }

    @Test
    fun progressStatsSwitchToTwoRowsAtAccessibilityFontScale() {
        assertFalse(shouldUseTwoRowProgressStats(1.0f))
        assertFalse(shouldUseTwoRowProgressStats(1.19f))
        assertTrue(shouldUseTwoRowProgressStats(1.2f))
        assertTrue(shouldUseTwoRowProgressStats(2.0f))
    }
}
