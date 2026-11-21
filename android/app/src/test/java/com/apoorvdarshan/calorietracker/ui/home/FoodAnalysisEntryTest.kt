package com.apoorvdarshan.calorietracker.ui.home

import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodAnalysisEntryTest {
    private val analysis = FoodAnalysis(
        name = "Pizza margherita",
        calories = 850,
        protein = 32.0,
        carbs = 110.0,
        fat = 30.0,
        servingSizeGrams = 400.0,
        sugar = 8.0
    )
    private val moment = Instant.parse("2026-10-24T12:00:00Z")

    @Test
    fun copiesAnalysisIntoEntryWithTheGivenSourceMealAndTime() {
        val id = UUID.randomUUID()
        val entry = analysis.toFoodEntry(id = id, timestamp = moment, source = FoodSource.TEXT_INPUT, mealType = MealType.LUNCH)

        assertEquals(id, entry.id)
        assertEquals("Pizza margherita", entry.name)
        assertEquals(850, entry.calories)
        assertEquals(32.0, entry.protein, 0.0)
        assertEquals(8.0, entry.sugar!!, 0.0)
        assertEquals(moment, entry.timestamp)
        assertEquals(FoodSource.TEXT_INPUT, entry.source)
        assertEquals(MealType.LUNCH, entry.mealType)
        assertEquals(400.0, entry.servingSizeGrams!!, 0.0)
    }

    @Test
    fun scaleMultipliesCaloriesMacrosAndOptionalNutrients() {
        val entry = analysis.toFoodEntry(scale = 0.5, source = FoodSource.SNAP_FOOD, mealType = MealType.DINNER)

        assertEquals(425, entry.calories)
        assertEquals(16.0, entry.protein, 0.0)
        assertEquals(4.0, entry.sugar!!, 0.0)
        assertNull(entry.fiber)
    }

    @Test
    fun blankNameFallsBackToTheAnalysisName() {
        val entry = analysis.toFoodEntry(name = "  ", source = FoodSource.TEXT_INPUT, mealType = MealType.SNACK)
        assertEquals("Pizza margherita", entry.name)

        val renamed = analysis.toFoodEntry(name = "Pizza", source = FoodSource.TEXT_INPUT, mealType = MealType.SNACK)
        assertEquals("Pizza", renamed.name)
    }

    @Test
    fun unknownServingSizeDropsGramsAndMarksServing() {
        val entry = analysis.toFoodEntry(
            servingSizeIsKnown = false,
            selectedServingQuantity = 2.0,
            source = FoodSource.TEXT_INPUT,
            mealType = MealType.LUNCH
        )

        assertNull(entry.servingSizeGrams)
        assertEquals("serving", entry.selectedServingUnit)
        assertEquals(2.0, entry.selectedServingQuantity!!, 0.0)
    }

    @Test
    fun imageFilenamesSplitIntoPrimaryAndAdditional() {
        val entry = analysis.toFoodEntry(
            imageFilenames = listOf("a.jpg", "b.jpg", "c.jpg"),
            source = FoodSource.SNAP_FOOD,
            mealType = MealType.LUNCH
        )

        assertEquals("a.jpg", entry.imageFilename)
        assertEquals(listOf("b.jpg", "c.jpg"), entry.additionalImageFilenames)
    }
}
