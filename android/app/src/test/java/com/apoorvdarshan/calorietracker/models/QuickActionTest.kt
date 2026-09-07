package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickActionTest {
    @Test
    fun defaultsMatchTheThreeInitialShortcutSlots() {
        assertEquals(
            listOf(QuickAction.CAMERA, QuickAction.VOICE, QuickAction.BARCODE),
            QuickAction.Defaults
        )
    }

    @Test
    fun storedValuesRoundTripAndInvalidValuesFallBack() {
        assertEquals(QuickAction.FAVORITES, QuickAction.fromStorage("FAVORITES"))
        assertEquals(QuickAction.FASTING, QuickAction.fromStorage("FASTING"))
        assertEquals(QuickAction.VOICE, QuickAction.fromStorage("unknown", QuickAction.VOICE))
    }
}
