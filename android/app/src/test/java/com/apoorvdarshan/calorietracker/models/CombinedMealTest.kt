package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CombinedMealTest {
    @Test
    fun foodEntryMapsToIngredientWithoutFlatteningNested() {
        val entry = FoodEntry(
            name = "Bowl",
            calories = 500,
            protein = 30.0,
            carbs = 40.0,
            fat = 20.0,
            source = FoodSource.MANUAL,
            servingSizeGrams = 350.0,
            ingredients = listOf(
                MealIngredient("Rice", 200.0, 250, 5.0, 50.0, 1.0),
                MealIngredient("Chicken", 150.0, 250, 25.0, 0.0, 10.0)
            )
        )
        val ingredient = entry.toMealIngredient()
        assertEquals("Bowl", ingredient.name)
        assertEquals(350.0, ingredient.grams, 0.001)
        assertEquals(500, ingredient.calories)
        assertEquals(30.0, ingredient.protein, 0.001)
    }

    @Test
    fun analysisMapsToIngredient() {
        val analysis = FoodAnalysis(
            name = "Apple",
            calories = 95,
            protein = 0.5,
            carbs = 25.0,
            fat = 0.3,
            servingSizeGrams = 182.0
        )
        val ingredient = analysis.toMealIngredient()
        assertEquals("Apple", ingredient.name)
        assertEquals(182.0, ingredient.grams, 0.001)
        assertEquals(95, ingredient.calories)
    }

    @Test
    fun combineTotalsAndUsesLatestMealMetadata() {
        val older = FoodEntry(
            name = "Eggs",
            calories = 140,
            protein = 12.0,
            carbs = 1.0,
            fat = 10.0,
            timestamp = Instant.parse("2026-09-01T08:00:00Z"),
            source = FoodSource.MANUAL,
            mealType = MealType.BREAKFAST,
            servingSizeGrams = 100.0
        )
        val newer = FoodEntry(
            name = "Toast",
            calories = 120,
            protein = 4.0,
            carbs = 20.0,
            fat = 2.0,
            timestamp = Instant.parse("2026-09-01T08:05:00Z"),
            source = FoodSource.TEXT_INPUT,
            mealType = MealType.LUNCH,
            servingSizeGrams = 50.0
        )
        val combined = combineFoodEntries(listOf(older, newer))
        assertEquals("Eggs + Toast", combined.name)
        assertEquals(260, combined.calories)
        assertEquals(16.0, combined.protein, 0.001)
        assertEquals(21.0, combined.carbs, 0.001)
        assertEquals(12.0, combined.fat, 0.001)
        assertEquals(150.0, combined.servingSizeGrams!!, 0.001)
        assertEquals(MealType.LUNCH, combined.mealType)
        assertEquals(Instant.parse("2026-09-01T08:05:00Z"), combined.timestamp)
        assertEquals(2, combined.ingredients.size)
        assertTrue(combined.id != older.id && combined.id != newer.id)
    }

    @Test
    fun withIngredientsRecomputesParentTotals() {
        val entry = FoodEntry(
            name = "Meal",
            calories = 0,
            protein = 0.0,
            carbs = 0.0,
            fat = 0.0,
            source = FoodSource.MANUAL
        )
        val updated = entry.withIngredients(
            listOf(
                MealIngredient("A", 50.0, 80, 5.0, 6.0, 3.0),
                MealIngredient("B", 70.0, 120, 8.0, 10.0, 4.0)
            )
        )
        assertEquals(200, updated.calories)
        assertEquals(13.0, updated.protein, 0.001)
        assertEquals(16.0, updated.carbs, 0.001)
        assertEquals(7.0, updated.fat, 0.001)
        assertEquals(120.0, updated.servingSizeGrams!!, 0.001)
        assertEquals(2, updated.ingredients.size)
    }
}
