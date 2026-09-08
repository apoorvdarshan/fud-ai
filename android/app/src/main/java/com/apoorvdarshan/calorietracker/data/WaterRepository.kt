package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.export.DiaryImporter
import com.apoorvdarshan.calorietracker.export.DiaryImportPreview
import com.apoorvdarshan.calorietracker.export.DiaryImportMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class WaterRepository(private val prefs: PreferencesStore) {
    private val mutationMutex = Mutex()
    val entries: Flow<List<WaterEntry>> = prefs.waterEntries.map { list -> list.sortedBy { it.date } }

    suspend fun add(entry: WaterEntry) {
        if (entry.milliliters <= 0) return
        mutationMutex.withLock {
            prefs.setWaterEntries(prefs.waterEntries.first() + entry)
        }
    }

    suspend fun importDiary(preview: DiaryImportPreview, mode: DiaryImportMode) {
        if (!preview.includesWater) return
        mutationMutex.withLock {
            prefs.setWaterEntries(DiaryImporter.applyingWater(preview, prefs.waterEntries.first(), mode))
        }
    }

    suspend fun delete(id: UUID) {
        mutationMutex.withLock {
            prefs.setWaterEntries(prefs.waterEntries.first().filter { it.id != id })
        }
    }
}
