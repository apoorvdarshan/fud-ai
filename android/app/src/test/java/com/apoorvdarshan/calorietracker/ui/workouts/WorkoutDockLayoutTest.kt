package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutDockLayoutTest {
    @Test
    fun compactPhoneFitsAllFourWithoutShrinkingAddOrTouchTargets() {
        assertEquals(WorkoutDockLayout(4, 48.dp, 4.dp), WorkoutDockLayout.fit(268.dp, slots = 4))
        assertEquals(60.dp, WorkoutDockLayout.AddSide)
    }

    @Test
    fun narrowerLayoutUsesOverflowAndWiderLayoutKeepsLargerButtons() {
        assertEquals(WorkoutDockLayout(3, 52.dp, 6.dp), WorkoutDockLayout.fit(267.dp, slots = 4))
        assertEquals(WorkoutDockLayout(4, 52.dp, 6.dp), WorkoutDockLayout.fit(292.dp, slots = 4))
    }
}
