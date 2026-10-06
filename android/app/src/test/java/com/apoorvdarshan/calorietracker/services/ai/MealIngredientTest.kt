package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.totals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealIngredientTest {
    @Test
    fun ingredientListReplacesDisagreedMealMacros() {
        val analysis = FoodAnalysis(
            name = "Plate",
            calories = 820,
            protein = 40.0,
            carbs = 90.0,
            fat = 30.0,
            servingSizeGrams = 500.0,
            ingredients = listOf(
                MealIngredient("Chicken", 150.0, 250, 40.0, 0.0, 8.0),
                MealIngredient("Rice", 200.0, 300, 6.0, 66.0, 1.0),
                MealIngredient("Sauce", 40.0, 90, 1.0, 4.0, 8.0)
            )
        ).withIngredientMacroTotals()

        assertEquals(640, analysis.calories)
        assertEquals(47.0, analysis.protein, 0.001)
        assertEquals(70.0, analysis.carbs, 0.001)
        assertEquals(17.0, analysis.fat, 0.001)
    }

    @Test
    fun emptyIngredientListKeepsMealMacros() {
        val analysis = FoodAnalysis(
            name = "Banana",
            calories = 105,
            protein = 1.0,
            carbs = 27.0,
            fat = 0.0,
            servingSizeGrams = 118.0
        ).withIngredientMacroTotals()

        assertEquals(105, analysis.calories)
        assertEquals(1.0, analysis.protein, 0.001)
    }

    @Test
    fun scalingAndTotalsRecalculateMealMacros() {
        val ingredients = listOf(
            MealIngredient("Rice", 150.0, 195, 4.0, 42.0, 0.5),
            MealIngredient("Chicken", 100.0, 165, 31.0, 0.0, 3.6)
        ).map { it.scaled(0.5) }
        val totals = ingredients.totals()

        assertEquals(125.0, totals.grams, 0.001)
        assertEquals(181, totals.calories)
        assertEquals(17.5, totals.protein, 0.001)
    }

    @Test
    fun oldFoodEntryJsonWithoutIngredientsStillDecodes() {
        val json = """{"name":"Apple","calories":95,"protein":0.5,"carbs":25.0,"fat":0.3,"source":"manual"}"""
        val entry = Json { ignoreUnknownKeys = true }.decodeFromString<FoodEntry>(json)

        assertTrue(entry.ingredients.isEmpty())
        assertEquals("Apple", entry.name)
        assertEquals(FoodSource.MANUAL, entry.source)
        assertFalse(entry.progressiveMeal)
    }

    @Test
    fun progressiveMealModeSurvivesFoodEntryRoundTrip() {
        val format = Json { ignoreUnknownKeys = true }
        val original = FoodEntry(
            name = "Progressive bowl",
            calories = 400,
            protein = 30.0,
            carbs = 45.0,
            fat = 12.0,
            source = FoodSource.SNAP_FOOD,
            progressiveMeal = true
        )

        val decoded = format.decodeFromString<FoodEntry>(format.encodeToString(original))

        assertTrue(decoded.progressiveMeal)
    }

    @Test
    fun productMetadataSurvivesFoodEntryRoundTripAndDuplication() {
        val format = Json { ignoreUnknownKeys = true }
        val metadata = FoodProductMetadata(
            barcode = "3017620422003",
            packageQuantity = "400 g",
            ingredientsText = "Sugar, palm oil, hazelnuts",
            allergens = listOf("Milk", "Hazelnuts"),
            traces = listOf("Soy"),
            nutriScore = "E",
            novaGroup = 4,
            ecoScore = "D",
            labels = listOf("Vegetarian"),
            categories = listOf("Spreads"),
            imageUrl = "https://images.openfoodfacts.org/product.jpg"
        )
        val original = FoodEntry(
            name = "Hazelnut spread",
            calories = 539,
            protein = 6.3,
            carbs = 57.5,
            fat = 30.9,
            source = FoodSource.BARCODE,
            productMetadata = metadata
        )

        val decoded = format.decodeFromString<FoodEntry>(format.encodeToString(original))
        val duplicated = decoded.duplicatedForLogging(Instant.parse("2026-08-30T12:00:00Z"))

        assertEquals(metadata, decoded.productMetadata)
        assertEquals(metadata, duplicated.productMetadata)
    }

    @Test
    fun progressiveMealPromptUsesChronologicalScaleDifferences() {
        val prompt = multiPhotoAnalysisPrompt(
            progressiveMeal = true,
            description = "The plate stays on the scale"
        )

        assertTrue(prompt.contains("chronological progressive-meal sequence"))
        assertTrue(prompt.contains("current scale total minus the previous scale total"))
        assertTrue(prompt.contains("The plate stays on the scale"))
        assertFalse(prompt.contains("Treat the photos as multiple views"))
    }

    @Test
    fun standardMultiPhotoPromptKeepsMultipleViewBehavior() {
        val prompt = multiPhotoAnalysisPrompt(progressiveMeal = false)

        assertTrue(prompt.contains("Treat the photos as multiple views"))
        assertFalse(prompt.contains("chronological progressive-meal sequence"))
    }
}
