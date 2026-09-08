package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.apoorvdarshan.calorietracker.models.IngredientPortion
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodDraftSaversTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun <T, S : Any> restore(saver: Saver<T, S>, value: T): T? {
        val saved = with(saver) { scope.save(value) } ?: error("Draft was not saved")
        return saver.restore(saved)
    }

    @Test
    fun ingredientEditorRestoresSelectedIndexAndAllMediaReferences() {
        val ingredient = MealIngredient(
            name = "Peanut butter", grams = 37.0, calories = 234,
            protein = 8.5, carbs = 8.6, fat = 18.5,
            imageFilename = "ingredient.jpg",
            additionalImageFilenames = listOf("label.jpg"), emoji = "🥜"
        )
        val target = IngredientEditorTarget(2, ingredient)
        assertEquals(target, restore(foodDraftSaver<IngredientEditorTarget?>(), target))
        assertEquals(listOf(ingredient), restore(foodDraftSaver<List<MealIngredient>>(), listOf(ingredient)))
        assertNull(restore(foodDraftSaver<IngredientEditorTarget?>(), null))
    }

    @Test
    fun reviewRestoresServingOptionsAndOptionalNutrientValues() {
        val options = listOf(ServingUnitOption.grams, ServingUnitOption.loggedServing(135.0))
        val nutrients = mapOf("selenium" to 12.3, "manganese" to 0.8)
        assertEquals(options, restore(foodDraftSaver<List<ServingUnitOption>>(), options))
        assertEquals(nutrients, restore(foodDraftSaver<Map<String, Double>>(), nutrients))
    }

    @Test
    fun ingredientKeepsEditedNutritionBaselineForWeightChangesAfterRestore() {
        // This baseline has already been rebased by a manual calorie/macro edit.
        val baseline = IngredientPortion(75.0, 123.0, 4.5, 11.2, 6.3)
        val restored = restore(IngredientPortionSaver, baseline)!!
        assertEquals(baseline.resized(200.0), restored.resized(200.0))
        assertEquals(328.0, restored.resized(200.0)!!.calories, 0.00001)
    }
}
