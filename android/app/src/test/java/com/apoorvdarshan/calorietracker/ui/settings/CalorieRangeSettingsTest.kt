package com.apoorvdarshan.calorietracker.ui.settings

import com.apoorvdarshan.calorietracker.models.EnergyUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalorieRangeSettingsTest {
    @Test
    fun kilojouleInputIsStoredAsKilocalories() {
        assertEquals(2_000, calorieStoredKcal(8_368, EnergyUnit.KJ))
        assertEquals(100, calorieStoredKcal(418, EnergyUnit.KJ))
        assertEquals(0, calorieStoredKcal(0, EnergyUnit.KJ))
    }

    @Test
    fun kilocalorieInputIsStoredUnchanged() {
        assertEquals(2_000, calorieStoredKcal(2_000, EnergyUnit.KCAL))
        assertEquals(0, calorieStoredKcal(0, EnergyUnit.KCAL))
    }

    @Test
    fun showingAStoredBoundInKilojoulesAndSavingItUntouchedKeepsIt() {
        for (kcal in 0..10_000) {
            val displayed = EnergyUnit.KJ.displayValue(kcal)
            assertEquals(kcal, calorieStoredKcal(displayed, EnergyUnit.KJ))
        }
    }

    @Test
    fun boundsStayDigestOnly() {
        assertEquals(0, calorieBound(""))
        assertEquals(1_200, calorieBound("1200"))
        assertNull(calorieBound("12.5"))
        assertNull(calorieBound("-5"))
        assertNull(calorieBound("123456"))
    }
}
