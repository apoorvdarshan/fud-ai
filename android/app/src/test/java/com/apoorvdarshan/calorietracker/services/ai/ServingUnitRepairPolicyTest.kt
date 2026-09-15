package com.apoorvdarshan.calorietracker.services.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServingUnitRepairPolicyTest {
    private fun food(
        calories: Int = 320,
        protein: Double = 12.0,
        carbs: Double = 50.0,
        fat: Double = 6.0,
        servingSizeIsKnown: Boolean = true
    ) = FoodAnalysis(
        name = "Oatmeal",
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        servingSizeGrams = 250.0,
        servingSizeIsKnown = servingSizeIsKnown
    )

    @Test
    fun usableMacrosSkipTheRepairRoundTrip() {
        assertFalse(ServingUnitRepairPolicy.shouldRepair(food(), shouldRequestFallback = true))
        assertFalse(ServingUnitRepairPolicy.shouldRepair(food(calories = 0, protein = 0.0, carbs = 0.0, fat = 0.4), shouldRequestFallback = true))
    }

    @Test
    fun validUnitOptionsNeverRepair() {
        assertFalse(ServingUnitRepairPolicy.shouldRepair(food(calories = 0, protein = 0.0, carbs = 0.0, fat = 0.0), shouldRequestFallback = false))
    }

    @Test
    fun zeroCalorieFoodsWithKnownWeightStillGetUnits() {
        assertTrue(ServingUnitRepairPolicy.shouldRepair(food(calories = 0, protein = 0.0, carbs = 0.0, fat = 0.0), shouldRequestFallback = true))
    }

    @Test
    fun unknownServingWeightCannotBeRepaired() {
        assertFalse(
            ServingUnitRepairPolicy.shouldRepair(
                food(calories = 0, protein = 0.0, carbs = 0.0, fat = 0.0, servingSizeIsKnown = false),
                shouldRequestFallback = true
            )
        )
    }

    @Test
    fun nutritionLabelsFollowTheSameRule() {
        val label = NutritionLabelAnalysis(
            name = "Bar",
            caloriesPer100g = 400.0,
            proteinPer100g = 20.0,
            carbsPer100g = 40.0,
            fatPer100g = 15.0,
            servingSizeGrams = 50.0
        )
        assertFalse(ServingUnitRepairPolicy.shouldRepair(label, shouldRequestFallback = true))

        val water = label.copy(caloriesPer100g = 0.0, proteinPer100g = 0.0, carbsPer100g = 0.0, fatPer100g = 0.0)
        assertTrue(ServingUnitRepairPolicy.shouldRepair(water, shouldRequestFallback = true))
        assertFalse(ServingUnitRepairPolicy.shouldRepair(water.copy(servingSizeGrams = null), shouldRequestFallback = true))
    }
}
