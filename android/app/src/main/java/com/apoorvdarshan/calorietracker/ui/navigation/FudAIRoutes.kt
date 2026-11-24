package com.apoorvdarshan.calorietracker.ui.navigation

import com.apoorvdarshan.calorietracker.models.TabBarSettings

object FudAIRoutes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val PROGRESS = "progress"
    const val WEEKLY_CHALLENGE = "weeklyChallenge"
    const val COACH = "coach"
    const val SETTINGS = "settings"
    const val OPTIONAL_NUTRIENT_GOALS = "settings/optional-nutrient-goals"
    const val CALCULATION_METHODS = "settings/calculation-methods"
    const val QUICK_ACTIONS = "settings/quick-actions"
    const val ADD_MENU = "settings/add-menu"
    const val PRODUCT_INFO = "settings/product-info"
    const val TAB_BAR = "settings/tab-bar"
    const val BODY_MEASUREMENTS = "settings/body-measurements"
    const val ALLERGEN_SENSITIVITIES = "settings/allergen-sensitivities"
    const val WORKOUTS = "workouts"
    const val EXERCISE_LIBRARY = "exerciseLibrary"

    val bottomTabs = listOf(HOME, PROGRESS, WEEKLY_CHALLENGE, COACH, SETTINGS, WORKOUTS, EXERCISE_LIBRARY)

    /** The two split groups keep their existing visibility preferences. */
    fun isHiddenTab(route: String?, hidden: Set<String>): Boolean {
        val group = when (route) {
            WEEKLY_CHALLENGE -> PROGRESS
            EXERCISE_LIBRARY -> WORKOUTS
            else -> route
        }
        return group in TabBarSettings.Hideable && group in hidden
    }

    /** The bottom-bar tabs left after the user's hidden ones. */
    fun visibleBottomTabs(hidden: Set<String>): List<String> =
        bottomTabs.filterNot { isHiddenTab(it, hidden) }

    /** Maps a Nav destination to the bottom-tab route that should appear selected. */
    fun selectedBottomTab(route: String?): String? = when {
        route == null -> null
        route in bottomTabs -> route
        route.startsWith("settings/") -> SETTINGS
        else -> null
    }

    /** Peer tabs switch in place; transitions inside a tab are still directional. */
    internal fun isTabSwitch(from: String?, to: String?): Boolean {
        val fromTab = selectedBottomTab(from)
        val toTab = selectedBottomTab(to)
        return fromTab != null && toTab != null && fromTab != toTab
    }
}
