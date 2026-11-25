package com.apoorvdarshan.calorietracker.ui.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightAxisTest {
    @Test fun outerLabelsCoverTheWholeWeightRange() {
        val ticks = weightAxisTicks(69.6, 99.8)
        assertEquals(60.0, ticks.first(), 0.0001)
        assertEquals(100.0, ticks.last(), 0.0001)
        assertTrue(ticks.last() > 96.0)
        assertTrue(ticks.zipWithNext().all { (a, b) -> b - a == 10.0 })
    }

    @Test fun roundEndpointsAndNarrowImperialRangesStillHaveAUsableScale() {
        assertEquals(listOf(90.0, 92.0, 94.0, 96.0, 98.0), weightAxisTicks(90.0, 98.0))
        val ticks = weightAxisTicks(209.34, 213.51)
        assertTrue(ticks.first() <= 209.34)
        assertTrue(ticks.last() >= 213.51)
        assertTrue(ticks.zipWithNext().all { (a, b) -> b > a })
    }
}
