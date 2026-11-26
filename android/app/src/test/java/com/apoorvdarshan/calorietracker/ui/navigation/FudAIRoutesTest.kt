package com.apoorvdarshan.calorietracker.ui.navigation

import com.apoorvdarshan.calorietracker.models.TabBarSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FudAIRoutesTest {
    @Test
    fun challengesAndExerciseLibraryBelongToTheirClassicTabs() {
        assertEquals(FudAIRoutes.PROGRESS, FudAIRoutes.selectedBottomTab(FudAIRoutes.WEEKLY_CHALLENGE))
        assertEquals(FudAIRoutes.WORKOUTS, FudAIRoutes.selectedBottomTab(FudAIRoutes.EXERCISE_LIBRARY))
    }

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
        assertEquals(FudAIRoutes.SETTINGS, FudAIRoutes.selectedBottomTab(FudAIRoutes.TAB_BAR))
    }

    @Test
    fun hiddenTabsLeaveHomeAndSettingsInTheBar() {
        assertEquals(FudAIRoutes.bottomTabs, FudAIRoutes.visibleBottomTabs(emptySet()))
        assertEquals(
            listOf(FudAIRoutes.HOME, FudAIRoutes.PROGRESS, FudAIRoutes.SETTINGS),
            FudAIRoutes.visibleBottomTabs(setOf(FudAIRoutes.COACH, FudAIRoutes.WORKOUTS))
        )
        val everything = FudAIRoutes.bottomTabs.toSet()
        assertEquals(
            listOf(FudAIRoutes.HOME, FudAIRoutes.SETTINGS),
            FudAIRoutes.visibleBottomTabs(everything)
        )
        assertFalse(FudAIRoutes.isHiddenTab(FudAIRoutes.HOME, everything))
        assertFalse(FudAIRoutes.isHiddenTab(FudAIRoutes.SETTINGS, everything))
        assertFalse(FudAIRoutes.isHiddenTab(null, everything))
        assertTrue(FudAIRoutes.isHiddenTab(FudAIRoutes.COACH, setOf(FudAIRoutes.COACH)))
        assertTrue(FudAIRoutes.isHiddenTab(FudAIRoutes.WEEKLY_CHALLENGE, setOf(FudAIRoutes.PROGRESS)))
        assertTrue(FudAIRoutes.isHiddenTab(FudAIRoutes.EXERCISE_LIBRARY, setOf(FudAIRoutes.WORKOUTS)))
    }

    @Test
    fun aboutAndSupportBelongToSettings() {
        assertEquals(FudAIRoutes.SETTINGS, FudAIRoutes.selectedBottomTab(FudAIRoutes.ABOUT))
        assertEquals(FudAIRoutes.SETTINGS, FudAIRoutes.selectedBottomTab(FudAIRoutes.SUPPORT))
    }

    @Test
    fun hiddenTabStorageMatchesTheTabRoutes() {
        assertEquals(
            listOf(FudAIRoutes.PROGRESS, FudAIRoutes.COACH, FudAIRoutes.WORKOUTS),
            TabBarSettings.Hideable
        )
        assertEquals(emptySet<String>(), TabBarSettings.decode(null))
        assertEquals(setOf(FudAIRoutes.PROGRESS), TabBarSettings.decode("home, settings,kitchen , progress"))
        assertEquals("coach,workouts", TabBarSettings.encode(setOf(FudAIRoutes.WORKOUTS, FudAIRoutes.COACH)))
        assertEquals(setOf(FudAIRoutes.COACH, FudAIRoutes.WORKOUTS), TabBarSettings.decode("coach,workouts"))
    }

    @Test
    fun hidingOneTabKeepsTheOtherStoredTabs() {
        // Coach was hidden by a restore after Settings loaded "": hiding Workouts must keep it.
        assertEquals("coach,workouts", TabBarSettings.withTabHidden("coach", FudAIRoutes.WORKOUTS, hidden = true))
        assertEquals("coach", TabBarSettings.withTabHidden("coach,workouts", FudAIRoutes.WORKOUTS, hidden = false))
        assertEquals("", TabBarSettings.withTabHidden(null, FudAIRoutes.PROGRESS, hidden = false))
        assertEquals("progress", TabBarSettings.withTabHidden("progress", FudAIRoutes.PROGRESS, hidden = true))
        // Home and Settings can't be hidden; unknown stored values are dropped.
        assertEquals("coach", TabBarSettings.withTabHidden("coach,kitchen", FudAIRoutes.HOME, hidden = true))
    }
}
