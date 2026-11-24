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

    val bottomTabs = listOf(HOME, PROGRESS, COACH, SETTINGS, WORKOUTS)

    /** Whether [route] is a tab the user hid. Home and Settings are never hidden. */
    fun isHiddenTab(route: String?, hidden: Set<String>): Boolean =
        route in TabBarSettings.Hideable && route in hidden

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
