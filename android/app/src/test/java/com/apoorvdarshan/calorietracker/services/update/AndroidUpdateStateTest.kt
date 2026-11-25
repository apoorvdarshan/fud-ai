package com.apoorvdarshan.calorietracker.services.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUpdateStateTest {
    @Test
    fun confirmedUpdateSurvivesFailureButClearsOnSuccessfulCheck() {
        val available = AndroidUpdateState.Available("9.0", "build 49")
        val failed = AndroidUpdateState.Failed("9.0")
        val upToDate = AndroidUpdateState.UpToDate("9.0", "9.0")

        assertEquals(failed, AndroidUpdateState.Idle.resolving(failed))
        assertEquals(available, available.resolving(failed))
        assertTrue(available.resolving(failed).isUpdateAvailable)
        assertFalse(available.resolving(upToDate).isUpdateAvailable)
    }
}
