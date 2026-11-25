package com.apoorvdarshan.calorietracker.ui.progress

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeCategory
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeLeaderboardRow
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeSocialPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChallengeRankingRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowLeaderboardCardWrapsTheWholeNameAndHandleAndPreservesTheLink() {
        val name = "Alexandria Isabella Montgomery"
        val handle = "alexandria.isabella.montgomery"
        var opened: String? = null
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened = uri }
        }
        compose.setContent {
            val density = LocalDensity.current.density
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(density, fontScale = 1.6f),
                    LocalUriHandler provides uriHandler
                ) {
                    Box(Modifier.width(210.dp)) {
                        RankingRow(
                            row = WeeklyChallengeLeaderboardRow(
                                rank = 1, participantId = "fixture", displayName = name,
                                socialPlatform = WeeklyChallengeSocialPlatform.INSTAGRAM, socialHandle = handle,
                                score = 5, overallPoints = 5, activityDays = 1, nutritionDays = 1,
                                consistencyDays = 2, hydrationDays = 1, activityKcal = 0
                            ),
                            category = WeeklyChallengeCategory.OVERALL, onReport = {}, onBlock = {}
                        )
                    }
                }
            }
        }

        for (text in listOf(name, "@$handle")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue(layout.lineCount in 2..4)
            assertFalse(layout.hasVisualOverflow)
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.challenge_open_social, handle, "Instagram")
        compose.onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        assertEquals("https://www.instagram.com/$handle/", opened)
    }
}
