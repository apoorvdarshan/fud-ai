package com.apoorvdarshan.calorietracker.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.apoorvdarshan.calorietracker.models.WaterEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterHealthSyncTest {
    @Test fun queuesAddsEditsAndDeletesButNotUnchangedEntries() {
        val removed = WaterEntry(milliliters = 100)
        val edited = WaterEntry(milliliters = 200)
        val unchanged = WaterEntry(milliliters = 300)
        val added = WaterEntry(milliliters = 400)
        assertEquals(
            setOf(removed.id.toString(), edited.id.toString(), added.id.toString()),
            changedWaterIds(listOf(removed, edited, unchanged), listOf(edited.copy(milliliters = 250), unchanged, added))
        )
    }

    @Test fun disablingWaterSharingClearsPendingWrites() {
        val syncKey = booleanPreferencesKey("healthHydrationSyncEnabled")
        val pendingKey = stringSetPreferencesKey("healthHydrationPending")
        val prefs = mutablePreferencesOf(syncKey to true, pendingKey to setOf("a", "b"))
        applyHydrationSync(false, prefs)
        assertEquals(false, prefs[syncKey])
        assertNull(prefs[pendingKey])
    }

    @Test fun enablingWaterSharingKeepsPendingWrites() {
        val syncKey = booleanPreferencesKey("healthHydrationSyncEnabled")
        val pendingKey = stringSetPreferencesKey("healthHydrationPending")
        val prefs = mutablePreferencesOf(syncKey to false, pendingKey to setOf("a"))
        applyHydrationSync(true, prefs)
        assertEquals(true, prefs[syncKey])
        assertEquals(setOf("a"), prefs[pendingKey])
    }

    @Test fun oneRejectedRecordDoesNotEndTheHydrationBatch() {
        assertFalse(hydrationBatchShouldStop(0))
        assertFalse(hydrationBatchShouldStop(1))
        assertTrue(hydrationBatchShouldStop(2))
        assertTrue(hydrationBatchShouldStop(3))
    }
}
