package com.apoorvdarshan.calorietracker.ui.settings

import com.apoorvdarshan.calorietracker.ui.about.AboutSettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoryTest {
    @Test
    fun hubKeepsEveryFocusedCategory() {
        assertEquals(15, SettingsCategory.entries.size)
        assertEquals(15, SettingsCategory.entries.map { it.titleRes }.toSet().size)
        assertEquals(10, SettingsCategory.preferenceEntries.size)
        assertEquals(5, SettingsCategory.appInfoEntries.size)
        assertEquals(5, SettingsCategory.entries.mapNotNull { it.aboutCategory }.size)
    }

    @Test
    fun appInfoKeepsEveryFocusedCategory() {
        assertEquals(5, AboutSettingsCategory.entries.size)
        assertEquals(5, AboutSettingsCategory.entries.map { it.titleRes }.toSet().size)
    }
}
