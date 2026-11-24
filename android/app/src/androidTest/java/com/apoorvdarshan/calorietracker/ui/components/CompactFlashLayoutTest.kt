package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.ui.theme.FudAITheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CompactFlashLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longDialogActionsWrapWithoutReducingTouchTargets() {
        val dismiss = "Keep editing"
        val primary = "Save changes to your diary"
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) {
                FudAITheme(darkTheme = false) {
                    Box(Modifier.width(216.dp).testTag("dialog.bounds")) {
                        FudGlassDialogActions(
                            primaryText = primary,
                            onPrimary = {},
                            dismissText = dismiss,
                            onDismiss = {}
                        )
                    }
                }
            }
        }

        val bounds = compose.onNodeWithTag("dialog.bounds").getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithText(dismiss).assertIsDisplayed().getUnclippedBoundsInRoot()
        val save = compose.onNodeWithText(primary).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(cancel.bottom <= save.top)
        for (action in listOf(cancel, save)) {
            assertTrue(action.bottom - action.top >= 47.dp)
            assertTrue(action.left >= bounds.left && action.right <= bounds.right)
        }
    }
}
