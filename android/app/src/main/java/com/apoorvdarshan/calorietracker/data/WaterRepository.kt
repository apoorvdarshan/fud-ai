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
    /** Serializes diary mutations only; Health Connect IPC never holds it. */
    private val mutationMutex = Mutex()
    /** Serializes drains; a slow sync cannot run twice and cannot block diary taps. */
    private val drainMutex = Mutex()
    val entries: Flow<List<WaterEntry>> = prefs.waterEntries.map { list -> list.sortedBy { it.date } }

    suspend fun add(entry: WaterEntry) {
        if (entry.milliliters <= 0) return
        mutationMutex.withLock {
            prefs.mutateWaterDiary { current -> current + entry }
        }
        drain()
    }

    suspend fun importDiary(preview: DiaryImportPreview, mode: DiaryImportMode) {
        if (!preview.includesWater) return
        mutationMutex.withLock {
            prefs.mutateWaterDiary { current -> DiaryImporter.applyingWater(preview, current, mode) }
        }
        drain()
    }

    suspend fun delete(id: UUID) {
        mutationMutex.withLock {
            prefs.mutateWaterDiary { current -> current.filter { it.id != id } }
        }
        drain()
    }

    suspend fun setHealthSync(enabled: Boolean) {
        // Saved without the mutation lock: an opt-out must not queue behind an
        // in-flight write batch. The drain re-reads the setting before every
        // record, so remaining writes stop on their own, and disabling clears
        // the pending set.
        prefs.setHydrationSync(enabled)
    }

    suspend fun retryHealthWrites() = drain()

    private suspend fun drain() = drainMutex.withLock { drainLocked() }

    private suspend fun drainLocked() {
        val connection = health ?: return
        if (!prefs.healthConnectEnabled.first() || !prefs.hydrationSyncEnabled.first() || !connection.hasHydrationWrite()) return
        val ids = prefs.pendingHydration.first().take(MAX_DRAIN_RECORDS)
        if (ids.isEmpty()) return
        // An unreadable diary must not read as "every entry deleted": keep everything
        // pending and retry after the blob is preserved and rewritten.
        val byId = (prefs.waterDiarySnapshot() ?: return).associateBy { it.id.toString() }
        var consecutiveFailures = 0
        for (id in ids) {
            // The opt-out (or a disconnect) may land while this batch runs; stop as soon as it did.
            if (!prefs.healthConnectEnabled.first() || !prefs.hydrationSyncEnabled.first()) return
            if (connection.syncHydration(UUID.fromString(id), byId[id])) {
                prefs.completeHydration(id)
                consecutiveFailures = 0
            } else if (hydrationBatchShouldStop(++consecutiveFailures)) {
                // Two in a row looks batch-wide; keep the rest pending for the next retry.
                return
            }
            // One record-specific failure keeps its ID pending without blocking the rest.
        }
    }

    private companion object {
        const val MAX_DRAIN_RECORDS = 20
    }
}

/** Whether a failed hydration write ends the batch: one record-specific failure keeps
 *  its ID pending and lets unrelated records proceed; two in a row stop the batch. */
internal fun hydrationBatchShouldStop(consecutiveFailures: Int): Boolean = consecutiveFailures >= 2
