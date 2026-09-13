package com.apoorvdarshan.calorietracker.ui.navigation

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
    const val BODY_MEASUREMENTS = "settings/body-measurements"
    const val ALLERGEN_SENSITIVITIES = "settings/allergen-sensitivities"
    const val WORKOUTS = "workouts"

    val bottomTabs = listOf(HOME, PROGRESS, COACH, SETTINGS, WORKOUTS)

    /** Maps a Nav destination to the bottom-tab route that should appear selected. */
    fun selectedBottomTab(route: String?): String? = when {
        route == null -> null
        route in bottomTabs -> route
        route.startsWith("settings/") -> SETTINGS
        else -> null
    }
}
