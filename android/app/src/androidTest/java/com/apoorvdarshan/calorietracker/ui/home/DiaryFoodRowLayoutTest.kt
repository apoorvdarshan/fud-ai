package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.ui.theme.FudAITheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DiaryFoodRowLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactRowFitsThreeMacrosBesideTheThumbnail() {
        val entry = showFood("Oat bowl", fontScale = 1f)

        val row = compose.onNodeWithTag("diary.food.${entry.id}").getUnclippedBoundsInRoot()
        val thumbnail = compose.onNodeWithTag("diary.food.${entry.id}.thumbnail").getUnclippedBoundsInRoot()
        val macros = macroBounds()
        assertEquals(56f, (thumbnail.right - thumbnail.left).value, 1f)
        assertTrue("A one-line food entry should not grow into a card", row.bottom - row.top <= 82.dp)
        assertTrue("All three macro chips fit on one line", macros.all { it.top == macros.first().top })
        assertTrue(macros.all { it.left >= thumbnail.right && it.right <= row.right })
    }

    @Test
    fun largeTextKeepsTheWholeNameAndAllMacrosVisible() {
        val name = "Roasted salmon with wild rice and seasonal greens"
        val entry = showFood(name, fontScale = 1.45f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }

        val nameLayout = layouts.single()
        assertTrue(nameLayout.lineCount > 2)
        assertFalse(nameLayout.hasVisualOverflow)
        val row = compose.onNodeWithTag("diary.food.${entry.id}").getUnclippedBoundsInRoot()
        assertTrue(macroBounds().all { it.right <= row.right && it.bottom <= row.bottom })
    }

    private fun macroBounds() = listOf("P 7.7g", "C 29.8g", "F 13.4g").map { label ->
        compose.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot()
    }

    private fun showFood(name: String, fontScale: Float): FoodEntry {
        val entry = FoodEntry(
            name = name, calories = 259, protein = 7.7, carbs = 29.8, fat = 13.4,
            servingSizeGrams = 196.8, emoji = "🥣", source = FoodSource.MANUAL
        )
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                FudAITheme(darkTheme = true) {
                    Box(Modifier.width(275.dp)) { FoodRow(entry) }
                }
            }
        }
        return entry
    }
}
