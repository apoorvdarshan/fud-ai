package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.ui.theme.FudAITheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FoodEditorLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun titleAndAllActionsFitAtNarrowWidthAndLargeFontScale() {
        val title = "Review food and nutrition information"
        val secondary = "Preview daily nutrition"
        val primary = "Save food to diary"
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) {
                FudAITheme(darkTheme = true) {
                    Box(Modifier.width(275.dp).testTag("editor.bounds")) {
                        SheetReviewToolbar(
                            title = title,
                            primaryLabel = primary,
                            secondaryLabel = secondary,
                            onCancel = {},
                            onPrimary = {},
                            onSecondary = {}
                        )
                    }
                }
            }
        }

        val container = compose.onNodeWithTag("editor.bounds").fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val secondaryBounds = compose.onNodeWithText(secondary).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val primaryBounds = compose.onNodeWithText(primary).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.bottom <= secondaryBounds.top)
        assertTrue(titleBounds.bottom <= primaryBounds.top)
        assertTrue(secondaryBounds.right <= primaryBounds.left || secondaryBounds.bottom <= primaryBounds.top)
        assertTrue(primaryBounds.right <= container.right)
        assertTrue(primaryBounds.bottom <= container.bottom)
    }
}
