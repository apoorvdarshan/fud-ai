package com.apoorvdarshan.calorietracker.ui.workouts

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.WorkoutRestTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * The rest countdown is the one thing the bar must show (#433): on a 360 dp phone the pills and
 * the Rest label give way to it, even with the longest translations or very large text.
 */
class WorkoutRestTimerBarTest {
    @get:Rule val compose = createComposeRule()

    private data class Case(val language: String, val fontScale: Float)

    // An already finished rest keeps the clock still ("0:00") while the layout is checked.
    private val timer = WorkoutRestTimer(
        exerciseId = UUID.randomUUID(),
        setId = UUID.randomUUID(),
        exerciseName = "Barbell Bench Press - Medium Grip",
        startedAt = Instant.now().minusSeconds(120),
        endsAt = Instant.now().minusSeconds(30)
    )

    @Test fun countdownStaysWholeWithLongTranslationsAndLargeText() {
        var case by mutableStateOf(Case("en", 1f))
        compose.setContent {
            Localized(case) {
                MaterialTheme {
                    // A 360 dp phone minus the workout log's 16 dp gutters.
                    Box(Modifier.width(328.dp).testTag("bar")) {
                        WorkoutRestTimerBar(timer = timer, onExtend = {}, onSkip = {})
                    }
                }
            }
        }

        val cases = listOf(
            Case("en", 1f), Case("uk", 1f), Case("de", 1f), Case("cs", 1f), Case("ru", 1f),
            Case("uk", 1.3f), Case("de", 1.3f), Case("en", 2f), Case("uk", 2f)
        )
        for (next in cases) {
            compose.runOnIdle { case = next }
            compose.waitForIdle()
            val skipDescription = localized(next).getString(R.string.workout_rest_skip_description)
            val addDescription = localized(next).getString(R.string.workout_rest_add_15_description)

            val clock = compose.onNodeWithTag(RestClockTestTag, useUnmergedTree = true).fetchSemanticsNode()
            val layout = textLayout(clock)
            assertEquals("$next clock", "0:00", layout.layoutInput.text.text)
            assertEquals("$next clock lines", 1, layout.lineCount)
            assertTrue("$next clock is cut off", layout.showsWholeLine())

            val bar = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
            val add = compose.onNodeWithContentDescription(addDescription).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription(skipDescription).assertIsDisplayed()
            assertTrue("$next clock starts inside the bar", clock.boundsInRoot.left >= bar.left)
            assertTrue("$next clock ends before the buttons", clock.boundsInRoot.right <= add.left)
        }
    }

    @Test fun buttonsKeepTheirWholeLabelsWhenThereIsRoom() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(328.dp)) {
                    WorkoutRestTimerBar(timer = timer, onExtend = {}, onSkip = {})
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (label in listOf(R.string.workout_rest_add_15, R.string.workout_rest_skip)) {
            val text = compose.onNodeWithText(context.getString(label), useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(context.getString(label), textLayout(text).showsWholeLine())
        }
    }

    private fun textLayout(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /**
     * Whether the text shows on one line in full. The semantics rebuild this layout at the
     * width the parent offered rather than the width the text took, so its hasVisualOverflow
     * reports any text with room to spare as overflowing; the text's measured width against
     * the width its line needs is what tells a cut-off text apart.
     */
    private fun TextLayoutResult.showsWholeLine(): Boolean =
        lineCount == 1 && !isLineEllipsized(0) && size.width >= multiParagraph.maxIntrinsicWidth

    private fun localized(case: Case) = InstrumentationRegistry.getInstrumentation().targetContext.let { base ->
        base.createConfigurationContext(configuration(base.resources.configuration, case))
    }

    private fun configuration(base: Configuration, case: Case) = Configuration(base).apply {
        setLocale(Locale.forLanguageTag(case.language))
        fontScale = case.fontScale
    }

    @Composable
    private fun Localized(case: Case, content: @Composable () -> Unit) {
        val base = LocalContext.current
        val configuration = configuration(LocalConfiguration.current, case)
        CompositionLocalProvider(
            LocalContext provides base.createConfigurationContext(configuration),
            LocalConfiguration provides configuration,
            LocalDensity provides Density(LocalDensity.current.density, case.fontScale),
            content = content
        )
    }
}
