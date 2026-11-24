package com.apoorvdarshan.calorietracker.ui.settings

import com.apoorvdarshan.calorietracker.services.voice.VoiceSettingsSection
import com.apoorvdarshan.calorietracker.ui.about.AboutSettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCategoryTest {
    @Test
    fun hubKeepsEveryFocusedCategory() {
        assertEquals(16, SettingsCategory.entries.size)
        assertEquals(16, SettingsCategory.entries.map { it.titleRes }.toSet().size)
        assertEquals(11, SettingsCategory.preferenceEntries.size)
        // Tracking and notifications are one category (#432).
        assertTrue(SettingsCategory.TRACKING_NOTIFICATIONS in SettingsCategory.preferenceEntries)
        assertTrue(SettingsCategory.entries.none { it.name == "NOTIFICATIONS" || it.name == "TRACKING_REMINDERS" })
        assertEquals(
            listOf(SettingsCategory.APP_UPDATES, SettingsCategory.HELP_FEEDBACK, SettingsCategory.COMMUNITY, SettingsCategory.LEGAL),
            SettingsCategory.aboutEntries
        )
        assertEquals(5, SettingsCategory.entries.mapNotNull { it.aboutCategory }.size)
    }

    @Test
    fun appInfoKeepsEveryFocusedCategory() {
        assertEquals(5, AboutSettingsCategory.entries.size)
        assertEquals(5, AboutSettingsCategory.entries.map { it.titleRes }.toSet().size)
    }

    @Test
    fun everyVoiceSectionOpensACategoryThatReportsItBack() {
        for (section in VoiceSettingsSection.entries) {
            if (section == VoiceSettingsSection.ABOUT) {
                // About opens from the root navigator, not the Settings hub.
                assertEquals(null, SettingsCategory.forVoiceSection(section))
                continue
            }
            // REMINDERS is kept for compatibility and opens the page reported as NOTIFICATIONS.
            val expected = if (section == VoiceSettingsSection.REMINDERS) VoiceSettingsSection.NOTIFICATIONS else section
            assertEquals(expected, SettingsCategory.forVoiceSection(section)?.voiceSection)
        }
        assertEquals(SettingsCategory.TRACKING_NOTIFICATIONS, SettingsCategory.forVoiceSection(VoiceSettingsSection.REMINDERS))
        assertEquals(SettingsCategory.TRACKING_NOTIFICATIONS, SettingsCategory.forVoiceSection(VoiceSettingsSection.NOTIFICATIONS))
        assertEquals(SettingsCategory.VOICE_ASSISTANT, SettingsCategory.forVoiceSection(VoiceSettingsSection.VOICE_ASSISTANT))
        assertEquals(VoiceSettingsSection.ABOUT, SettingsCategory.LEGAL.voiceSection)
        assertEquals(VoiceSettingsSection.AI_PROVIDERS, SettingsCategory.AI_ACCESS.voiceSection)
    }
}
