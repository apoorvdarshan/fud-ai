package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.WaterEntry
import org.junit.Assert.assertEquals
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
}
