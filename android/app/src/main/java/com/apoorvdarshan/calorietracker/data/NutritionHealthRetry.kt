package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.services.health.NutritionWriteGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Health Connect as the food log needs it. Mirrors [WorkoutHealthSync] so the retry path
 * can be exercised without a live Health Connect service.
 */
interface NutritionHealthSync {
    suspend fun writeGate(): NutritionWriteGate
    suspend fun write(entry: FoodEntry): Boolean

    /**
     * Delete-then-write on the entry's own clientRecordId. Used for every retry, so a
     * second attempt cannot duplicate a record that did land after all — the delete
     * targets that one entry, never the day or the whole log.
     */
    suspend fun update(entry: FoodEntry): Boolean

    /** Best-effort undo when a write completes after the local entry was removed. */
    suspend fun delete(entryId: UUID): Boolean = false
}

/** The slice of persistence the retry needs. Mirrors [WorkoutStateStore]. */
interface NutritionSyncStore {
    val foodEntries: Flow<List<FoodEntry>>
    val healthConnectEnabled: Flow<Boolean>
    val pendingNutritionHealthWrites: Flow<Set<String>>
    suspend fun setPendingNutritionHealthWrites(ids: Set<String>)
}

/**
 * Keeps food entries whose Health Connect write was never confirmed, and re-attempts them
 * on the next foreground sync.
 *
 * Before this existed, `FoodRepository.addEntry` called `writeNutrition` and discarded the
 * result, and the permission probe in front of it turned an unreachable Health Connect
 * service into "no permission". Either path dropped the entry with no exception, no log
 * and no retry: the food log kept it, Health Connect never heard about it, and nothing in
 * the app knew the two had diverged. [WorkoutRepository] already defers and retries its
 * burn writes this way; nutrition simply never got the same treatment.
 */
class NutritionHealthRetry(
    private val store: NutritionSyncStore,
    private val health: NutritionHealthSync?
) {
    private val mutex = Mutex()

    /**
     * Push [entry] to Health Connect, queueing it when the write is not confirmed.
     *
     * On [NutritionWriteGate.UNKNOWN] the write is attempted anyway. Health Connect
     * enforces its own permissions, so the worst case is a rejection we then queue and
     * resolve on the next pass — strictly better than assuming the answer and dropping
     * the entry.
     */
    suspend fun sync(entry: FoodEntry, isUpdate: Boolean) = syncAll(listOf(entry), isUpdate)

    /**
     * Push several entries in one pass. A diary import can carry hundreds of changed
     * entries, and resolving the gate per entry would mean one Health Connect IPC
     * round-trip each, so the gate and the queue are resolved once for the batch.
     */
    suspend fun syncAll(entries: List<FoodEntry>, isUpdate: Boolean) {
        val adapter = health ?: return
        if (entries.isEmpty()) return
        mutex.withLock { syncAllLocked(adapter, entries, isUpdate) }
    }

    /** Drop [id] from the queue — the write landed, or the entry no longer exists. */
    suspend fun forget(id: UUID) = forgetAll(listOf(id))

    /** Batch form of [forget], so an import does not rewrite the queue once per entry. */
    suspend fun forgetAll(ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        val keys = ids.mapTo(mutableSetOf()) { it.toString() }
        mutex.withLock { updateQueueLocked { it - keys } }
    }

    /**
     * Re-attempt every queued write. Called from the app-foreground Health Connect
     * coordinator, alongside the workout deferred writes.
     */
    suspend fun retryPending() {
        val adapter = health ?: return
        mutex.withLock { retryPendingLocked(adapter) }
    }

    private suspend fun syncAllLocked(
        adapter: NutritionHealthSync,
        entries: List<FoodEntry>,
        isUpdate: Boolean
    ) {
        if (!store.healthConnectEnabled.first()) return
        if (adapter.writeGate() == NutritionWriteGate.DENIED) return

        val keys = entries.map { it.id.toString() }
        updateQueueLocked { it + keys }

        val written = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        for (entry in entries) {
            val key = entry.id.toString()
            if (!isStillPending(key)) continue
            val currentEntry = store.foodEntries.first().find { it.id == entry.id } ?: continue

            val ok = if (isUpdate) adapter.update(currentEntry) else adapter.write(currentEntry)
            when {
                !isStillPending(key) -> undoWriteIfNeeded(adapter, entry.id, ok)
                !entryStillExists(key) -> undoWriteIfNeeded(adapter, entry.id, ok)
                ok -> written += key
                else -> failed += key
            }
        }
        updateQueueLocked { (it - written) + failed }
    }

    private suspend fun retryPendingLocked(adapter: NutritionHealthSync) {
        val pending = store.pendingNutritionHealthWrites.first()
        if (pending.isEmpty()) return
        if (!store.healthConnectEnabled.first()) return
        when (adapter.writeGate()) {
            // Still cannot reach the service. Keep the queue and try again on the next
            // foreground — discarding it here is the exact bug this retry exists to fix.
            NutritionWriteGate.UNKNOWN -> return
            // Nutrition write was revoked. Nothing is retryable any more, and an
            // unbounded queue would otherwise outlive the permission forever.
            NutritionWriteGate.DENIED -> {
                store.setPendingNutritionHealthWrites(emptySet())
                return
            }
            NutritionWriteGate.ALLOWED -> Unit
        }

        val remaining = mutableSetOf<String>()
        for (key in pending) {
            if (!isStillPending(key)) continue
            val entry = store.foodEntries.first().find { it.id.toString() == key } ?: continue

            val ok = adapter.update(entry)
            when {
                !isStillPending(key) -> undoWriteIfNeeded(adapter, entry.id, ok)
                !entryStillExists(key) -> undoWriteIfNeeded(adapter, entry.id, ok)
                ok -> Unit
                else -> remaining += key
            }
        }
        if (remaining != pending) store.setPendingNutritionHealthWrites(remaining)
    }

    private suspend fun isStillPending(key: String): Boolean =
        key in store.pendingNutritionHealthWrites.first()

    private suspend fun entryStillExists(key: String): Boolean =
        store.foodEntries.first().any { it.id.toString() == key }

    private suspend fun undoWriteIfNeeded(adapter: NutritionHealthSync, entryId: UUID, writeSucceeded: Boolean) {
        if (writeSucceeded) adapter.delete(entryId)
    }

    /** Read-modify-write of the queue. Caller must hold [mutex]. */
    private suspend fun updateQueueLocked(transform: (Set<String>) -> Set<String>) {
        val current = store.pendingNutritionHealthWrites.first()
        val next = transform(current)
        if (next != current) store.setPendingNutritionHealthWrites(next)
    }
}
