package com.apoorvdarshan.calorietracker.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarySwipeThresholdsTest {
    @Test
    fun wideDiaryKeepsExistingActionDistances() {
        val thresholds = diarySwipeThresholds(400f, favoritePx = 150f, deletePx = 220f)

        assertEquals(150f, thresholds.favorite, 0.01f)
        assertEquals(220f, thresholds.delete, 0.01f)
    }

    @Test
    fun narrowDiaryCanStillReachBothActions() {
        val thresholds = diarySwipeThresholds(270f, favoritePx = 150f, deletePx = 220f)

        assertTrue(thresholds.favorite > 0f)
        assertTrue(thresholds.favorite < thresholds.delete)
        assertTrue(thresholds.delete < thresholds.limit)
        assertTrue(thresholds.limit < 220f) // The old fixed threshold was impossible.
    }
}
