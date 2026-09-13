package com.apoorvdarshan.calorietracker.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FudAIRoutesTest {
    @Test
    fun selectedBottomTabMapsNestedSettingsToSettings() {
        assertEquals(FudAIRoutes.SETTINGS, FudAIRoutes.selectedBottomTab(FudAIRoutes.SETTINGS))
        assertEquals(
            FudAIRoutes.SETTINGS,
            FudAIRoutes.selectedBottomTab(FudAIRoutes.QUICK_ACTIONS)
        )
        assertEquals(
            FudAIRoutes.SETTINGS,
            FudAIRoutes.selectedBottomTab(FudAIRoutes.OPTIONAL_NUTRIENT_GOALS)
        )
        assertEquals(FudAIRoutes.HOME, FudAIRoutes.selectedBottomTab(FudAIRoutes.HOME))
        assertNull(FudAIRoutes.selectedBottomTab(FudAIRoutes.ONBOARDING))
        assertNull(FudAIRoutes.selectedBottomTab(null))
    }
}
