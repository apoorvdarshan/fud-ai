package com.apoorvdarshan.calorietracker.ui.navigation

import com.apoorvdarshan.calorietracker.models.TabBarSettings

object FudAIRoutes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val PROGRESS = "progress"
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
    const val WEEKLY_CHALLENGE = "progress/challenges"
    const val EXERCISE_LIBRARY = "workouts/library"
    const val ABOUT = "settings/about"
    const val SUPPORT = "settings/support"

    val bottomTabs = listOf(HOME, PROGRESS, COACH, SETTINGS, WORKOUTS)

    /** Whether [route] is a tab the user hid. Home and Settings are never hidden. */
    fun isHiddenTab(route: String?, hidden: Set<String>): Boolean =
        selectedBottomTab(route) in TabBarSettings.Hideable && selectedBottomTab(route) in hidden

    /** The bottom-bar tabs left after the user's hidden ones. */
    fun visibleBottomTabs(hidden: Set<String>): List<String> =
        bottomTabs.filterNot { isHiddenTab(it, hidden) }

    /** Maps a Nav destination to the bottom-tab route that should appear selected. */
    fun selectedBottomTab(route: String?): String? = when {
        route == null -> null
        route in bottomTabs -> route
        route.startsWith("settings/") -> SETTINGS
        route.startsWith("progress/") -> PROGRESS
        route.startsWith("workouts/") -> WORKOUTS
        else -> null
    }
}
