package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.*
import org.junit.Test

class IngredientPortionTest {
    private val base = IngredientPortion(100.0, 165.0, 31.0, 2.0, 3.6)

    @Test fun doublingAndHalvingScalesEveryNutritionValue() {
        assertEquals(IngredientPortion(200.0, 330.0, 62.0, 4.0, 7.2), base.resized(200.0))
        assertEquals(IngredientPortion(50.0, 82.5, 15.5, 1.0, 1.8), base.resized(50.0))
    }

    @Test fun typingThroughSmallWeightsDoesNotChangeTheBaseline() {
        base.resized(2.0)
        base.resized(20.0)
        assertEquals(base, base.resized(100.0))
        assertEquals(330.0, base.resized(200.0)!!.calories, 0.0)
    }

    @Test fun manualNutritionEditAtCurrentWeightBecomesNewBaseline() {
        val edited = base.resized(200.0)!!.copy(calories = 400.0, protein = 80.0)
        assertEquals(IngredientPortion(100.0, 200.0, 40.0, 2.0, 3.6), edited.resized(100.0))
    }

    @Test fun rejectsZeroNegativeNonfiniteAndOverflowWeights() {
        for (weight in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            assertNull(base.resized(weight))
        }
        assertNull(base.copy(grams = 0.0).resized(100.0))
        assertNull(base.copy(protein = Double.MAX_VALUE).resized(200.0))
    }
}
