package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.update.AndroidUpdateState
import com.apoorvdarshan.calorietracker.ui.about.UpdateRow
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test

class AppUpdateBadgeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun confirmedUpdateContinuesFromAboutToStoreAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var storeOpens = 0
        var updateState by mutableStateOf<AndroidUpdateState>(AndroidUpdateState.Available("9.0", "build 49"))
        compose.setContent {
            MaterialTheme {
                UpdateRow(updateState, "9.0", onRefresh = {}, onOpenStore = { storeOpens++ })
            }
        }

        compose.onNodeWithText(context.getString(R.string.about_update_action)).assertHasClickAction().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, storeOpens) }

        compose.runOnIdle { updateState = AndroidUpdateState.UpToDate("9.0", "9.0") }
        compose.onNodeWithText(context.getString(R.string.about_update_action)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.about_app_version)).assertExists()
    }
}
