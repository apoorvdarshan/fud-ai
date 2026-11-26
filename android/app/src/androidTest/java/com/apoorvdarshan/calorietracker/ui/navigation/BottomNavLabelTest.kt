package com.apoorvdarshan.calorietracker.ui.navigation

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomNavLabelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun frenchWorkoutLabelStaysOnOneLineInANarrowBar() {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.FRENCH) }
            val context = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
                LocalResources provides context.resources
            ) {
                MaterialTheme {
                    Box(Modifier.width(300.dp)) {
                        FudAIBottomNavBar(currentRoute = FudAIRoutes.WORKOUTS, onTap = {})
                    }
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Entraînements", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
    }
}
