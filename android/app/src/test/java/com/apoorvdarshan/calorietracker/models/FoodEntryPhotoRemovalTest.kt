package com.apoorvdarshan.calorietracker.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FoodEntryPhotoRemovalTest {
    @Test
    fun removingPrimaryPromotesNextPhotoAndPreservesFoodDetails() {
        val original = food()

        val edited = original.withoutImages(setOf("first.jpg"))

        assertEquals(
            original.copy(imageFilename = "second.jpg", additionalImageFilenames = listOf("third.jpg")),
            edited
        )
        assertEquals(listOf("second.jpg", "third.jpg"), edited.allImageFilenames)
    }

    @Test
    fun removingAdditionalPhotoKeepsPrimaryAndRemainingOrder() {
        val original = food()

        val edited = original.withoutImages(setOf("second.jpg"))

        assertEquals(original.copy(additionalImageFilenames = listOf("third.jpg")), edited)
        assertEquals(listOf("first.jpg", "third.jpg"), edited.allImageFilenames)
    }

    @Test
    fun combinedMealRemovesEveryReferenceToPhotoAndKeepsIngredientNutrition() {
        val firstIngredient = ingredient().copy(
            imageFilename = "first.jpg",
            additionalImageFilenames = listOf("second.jpg", "first.jpg")
        )
        val secondIngredient = ingredient().copy(name = "Rice", imageFilename = "first.jpg")
        val original = food().copy(ingredients = listOf(firstIngredient, secondIngredient))

        val edited = original.withoutImages(setOf("first.jpg"))

        assertEquals(
            original.copy(
                imageFilename = "second.jpg",
                additionalImageFilenames = listOf("third.jpg"),
                ingredients = listOf(
                    firstIngredient.copy(imageFilename = "second.jpg", additionalImageFilenames = emptyList()),
                    secondIngredient.copy(imageFilename = null)
                )
            ),
            edited
        )
        assertFalse(edited.allImageFilenames.contains("first.jpg"))
    }

    @Test
    fun ingredientOnlyPhotoIsPromotedWhenLastParentPhotoIsRemoved() {
        val original = food().copy(
            additionalImageFilenames = emptyList(),
            ingredients = listOf(ingredient().copy(imageFilename = "ingredient.jpg"))
        )

        val edited = original.withoutImages(setOf("first.jpg"))

        assertEquals("ingredient.jpg", edited.imageFilename)
        assertTrue(edited.additionalImageFilenames.isEmpty())
        assertEquals(original.ingredients, edited.ingredients)
        assertEquals(listOf("ingredient.jpg"), edited.allImageFilenames)
    }

    @Test
    fun removingAllPhotosSurvivesPersistenceWithoutChangingNutritionOrIngredients() {
        val original = food().copy(
            ingredients = listOf(ingredient().copy(imageFilename = "ingredient.jpg"))
        )
        val edited = original.withoutImages(original.allImageFilenames.toSet())
        val restored = Json.decodeFromString<FoodEntry>(Json.encodeToString(FoodEntry.serializer(), edited))

        assertNull(restored.imageFilename)
        assertTrue(restored.allImageFilenames.isEmpty())
        assertEquals(
            original.copy(
                imageFilename = null,
                additionalImageFilenames = emptyList(),
                ingredients = original.ingredients.map { it.copy(imageFilename = null) }
            ),
            restored
        )
    }

    @Test
    fun stagedRemovalLeavesOriginalAndSharedSavedMealUnchanged() {
        val original = food().copy(ingredients = listOf(ingredient().copy(imageFilename = "first.jpg")))
        val savedMeal = original.duplicatedForLogging(original.timestamp)

        val edited = original.withoutImages(setOf("first.jpg", "second.jpg", "third.jpg"))

        assertTrue(edited.allImageFilenames.isEmpty())
        assertEquals(listOf("first.jpg", "second.jpg", "third.jpg"), original.allImageFilenames)
        assertEquals(original.allImageFilenames, savedMeal.allImageFilenames)
        assertEquals("first.jpg", original.ingredients.single().imageFilename)
    }

    @Test
    fun emptyOrUnknownSelectionDoesNotChangeEntry() {
        val original = food()

        assertSame(original, original.withoutImages(emptySet()))
        assertSame(original, original.withoutImages(setOf("unrelated.jpg")))
    }

    private fun food() = FoodEntry(
        name = "Lunch",
        calories = 450,
        protein = 24.5,
        carbs = 52.5,
        fat = 15.0,
        timestamp = Instant.parse("2026-09-08T06:30:42.123Z"),
        imageFilename = "first.jpg",
        additionalImageFilenames = listOf("second.jpg", "third.jpg"),
        source = FoodSource.SNAP_FOOD,
        mealType = MealType.LUNCH,
        sugar = 4.234567,
        fiber = 5.5,
        sodium = 320.0,
        servingSizeGrams = 280.0,
        selectedServingUnit = "g",
        selectedServingQuantity = 280.0,
        customNote = "Homemade",
        emoji = "🥗"
    )

    private fun ingredient() = MealIngredient(
        name = "Tofu",
        grams = 100.0,
        calories = 120,
        protein = 14.5,
        carbs = 3.0,
        fat = 5.5,
        emoji = "🍽"
    )
}
