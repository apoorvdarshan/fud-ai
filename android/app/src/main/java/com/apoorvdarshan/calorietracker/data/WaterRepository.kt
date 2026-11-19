package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.export.DiaryImporter
import com.apoorvdarshan.calorietracker.export.DiaryImportPreview
import com.apoorvdarshan.calorietracker.export.DiaryImportMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import com.apoorvdarshan.calorietracker.services.health.HealthConnectManager

internal fun changedWaterIds(before: List<WaterEntry>, after: List<WaterEntry>): Set<String> {
    val old = before.associateBy { it.id }
    val new = after.associateBy { it.id }
    return (old.keys + new.keys).filter { old[it] != new[it] }.mapTo(mutableSetOf()) { it.toString() }
}

class WaterRepository(private val prefs: PreferencesStore, private val health: HealthConnectManager? = null) {
    private val mutationMutex = Mutex()
    val entries: Flow<List<WaterEntry>> = prefs.waterEntries.map { list -> list.sortedBy { it.date } }

    suspend fun add(entry: WaterEntry) {
        if (entry.milliliters <= 0) return
        mutationMutex.withLock {
            prefs.mutateWaterDiary { current -> current + entry }
            drainLocked()
        }
    }

    suspend fun importDiary(preview: DiaryImportPreview, mode: DiaryImportMode) {
        if (!preview.includesWater) return
        mutationMutex.withLock {
            prefs.mutateWaterDiary { current -> DiaryImporter.applyingWater(preview, current, mode) }
            drainLocked()
        }
    }

    suspend fun delete(id: UUID) {
        mutationMutex.withLock {
            prefs.mutateWaterDiary { current -> current.filter { it.id != id } }
            drainLocked()
        }
    }

    suspend fun setHealthSync(enabled: Boolean) = mutationMutex.withLock {
        prefs.setHydrationSync(enabled)
    }

    suspend fun retryHealthWrites() = mutationMutex.withLock { drainLocked() }

    private suspend fun drainLocked() {
        val connection = health ?: return
        if (!prefs.healthConnectEnabled.first() || !prefs.hydrationSyncEnabled.first() || !connection.hasHydrationWrite()) return
        val ids = prefs.pendingHydration.first().take(20)
        if (ids.isEmpty()) return
        val byId = prefs.waterEntries.first().associateBy { it.id.toString() }
        for (id in ids) {
            if (!connection.syncHydration(UUID.fromString(id), byId[id])) break
            prefs.completeHydration(id)
        }
    }
}
