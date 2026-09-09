package com.apoorvdarshan.calorietracker.models

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSnapshotWaterTest {
    private val nutrients = listOf("protein", "carbs", "fat", "fiber").map { id ->
        WidgetNutrient(id = id, label = id.replaceFirstChar(Char::uppercase), unit = "g", value = 1.0, goal = 2.0)
    }

    @Test
    fun waterReplacesOnlyFourthNutrientWhileTrackingIsEnabled() {
        val enabled = snapshot(waterEnabled = true)

        assertEquals(listOf("protein", "carbs", "fat", "water"), enabled.displayedHomeNutrients.map { it.id })
        assertEquals(750.0, enabled.displayedHomeNutrients.last().value, 0.0)
        assertEquals(2_000.0, enabled.displayedHomeNutrients.last().goal, 0.0)
    }

    @Test
    fun savedFourthNutrientReturnsWhenTrackingIsDisabled() {
        val disabled = snapshot(waterEnabled = false)

        assertEquals(listOf("protein", "carbs", "fat", "fiber"), disabled.displayedHomeNutrients.map { it.id })
    }

    @Test
    fun shorterSelectionsRoundTripWithoutDefaultPadding() {
        val choices = listOf(HomeTopNutrient.SODIUM, HomeTopNutrient.FIBER, HomeTopNutrient.PROTEIN, HomeTopNutrient.CARBS)
        for (count in 1..4) {
            val selected = choices.take(count)
            assertEquals(selected, HomeTopNutrient.fromStorage(HomeTopNutrient.toStorage(selected)))
            val widgetNutrients = selected.map { WidgetNutrient(it.storageKey, it.displayName, it.unit, 1.0, 2.0) }
            for (waterEnabled in listOf(false, true)) {
                val widget = snapshot(waterEnabled).copy(homeNutrients = widgetNutrients)
                val expected = selected.take(if (waterEnabled) 3 else 4).map { it.storageKey } +
                    if (waterEnabled) listOf("water") else emptyList()
                assertEquals(expected, widget.displayedHomeNutrients.map { it.id })
            }
        }
        assertEquals(listOf(HomeTopNutrient.SODIUM), HomeTopNutrient.fromStorage("sodium,sodium,unknown"))
        assertEquals(HomeTopNutrient.DefaultSelection, HomeTopNutrient.fromStorage(""))
        assertEquals(HomeTopNutrient.DefaultSelection, HomeTopNutrient.fromStorage("unknown"))
    }

    private fun snapshot(waterEnabled: Boolean) = WidgetSnapshot(
        date = Instant.EPOCH,
        dayStart = Instant.EPOCH,
        calories = 0,
        calorieGoal = 2_000,
        protein = 0.0,
        proteinGoal = 150,
        carbs = 0.0,
        carbsGoal = 220,
        fat = 0.0,
        fatGoal = 70,
        homeNutrients = nutrients,
        waterTrackingEnabled = waterEnabled,
        waterCurrentMl = 750,
        waterGoalMl = 2_000,
        waterUnitRaw = WaterUnit.MILLILITERS.storageValue
    )
}
