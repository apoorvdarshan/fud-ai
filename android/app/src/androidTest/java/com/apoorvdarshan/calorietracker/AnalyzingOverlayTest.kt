package com.apoorvdarshan.calorietracker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apoorvdarshan.calorietracker.services.ai.AnalysisKind
import com.apoorvdarshan.calorietracker.services.ai.AnalysisProgressState
import com.apoorvdarshan.calorietracker.services.ai.AnalysisStage
import com.apoorvdarshan.calorietracker.ui.home.AnalyzingOverlay
import com.apoorvdarshan.calorietracker.ui.components.WaitingFactFeed
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalyzingOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aColdFactDoesNotAdvanceTheDeckAndStaysStableWhenTheCatalogWarms() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val language = "fixture-${UUID.randomUUID()}"
        val cold = WaitingFactFeed.pick(language, "")
        assertTrue(cold.token.startsWith("fallback:"))
        val prefs = context.getSharedPreferences("waiting_fact_deck", android.content.Context.MODE_PRIVATE)
        try {
            WaitingFactFeed.prepare(context, language)
            assertEquals(cold, WaitingFactFeed.pick(language, cold.token))
            assertTrue(!prefs.contains("v1.$language"))
            WaitingFactFeed.pick(language, "")
            val next = WaitingFactFeed.pick(language, "")
            assertTrue(next.measurement != null)
            assertEquals("2", prefs.getString("v1.$language", null)?.substringAfterLast(':'))
        } finally {
            prefs.edit().remove("v1.$language").apply()
        }
    }

    @Test
    fun factStaysWhileProgressChangesAndCancelRemainsReachableAtLargeTextSize() {
        val stage = mutableStateOf(AnalysisStage.PREPARING)
        var cancelled = false
        compose.setContent {
            val density = LocalDensity.current.density
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(density, fontScale = 1.6f),
                    LocalLayoutDirection provides LayoutDirection.Rtl
                ) {
                    Box(Modifier.size(width = 280.dp, height = 320.dp)) {
                        AnalyzingOverlay(
                            progress = AnalysisProgressState(AnalysisKind.TEXT, stage.value),
                            onCancel = { cancelled = true }
                        )
                    }
                }
            }
        }

        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("analysis.waitingFact").fetchSemanticsNode() }.isSuccess
        }
        val fact = compose.onNodeWithTag("analysis.waitingFact").performScrollTo().assertIsDisplayed()
        val initial = fact.fetchSemanticsNode().config[SemanticsProperties.Text]
        assertTrue(initial.size >= 2)
        compose.runOnIdle { stage.value = AnalysisStage.RESPONDING }
        assertEquals(initial, fact.fetchSemanticsNode().config[SemanticsProperties.Text])

        val cancel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_cancel)
        compose.onNodeWithText(cancel).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
}
