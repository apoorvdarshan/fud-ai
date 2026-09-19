package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.services.health.NutritionWriteGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class NutritionHealthRetryTest {

    @Test
    fun failedWriteIsQueuedAndRetriedAsAnIdempotentUpdate() = runBlocking {
        val entry = foodEntry("Spaghetti Bolognese")
        val store = FakeNutritionSyncStore(entries = listOf(entry))
        val health = FakeNutritionHealthSync(writeSucceeds = false)
        val retry = NutritionHealthRetry(store, health)

        retry.sync(entry, isUpdate = false)
        assertEquals(setOf(entry.id.toString()), store.pending.value)

        health.writeSucceeds = true
        retry.retryPending()

        // Retries go through update (delete-then-write on the entry's own clientRecordId),
        // so a record that did land after all cannot end up duplicated.
        assertEquals(listOf(entry.id), health.updated)
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun pendingIdIsRegisteredBeforeHealthConnectWrite() = runBlocking {
        val entry = foodEntry("Oats")
        val store = FakeNutritionSyncStore(entries = listOf(entry))
        val health = FakeNutritionHealthSync(onWrite = {
            assertEquals(setOf(entry.id.toString()), store.pending.value)
        })
        val retry = NutritionHealthRetry(store, health)

        retry.sync(entry, isUpdate = false)

        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun backgroundSyncDoesNotWaitForAStalledHealthConnectWrite() = runBlocking {
        val entry = foodEntry("Paneer Tikka")
        val store = FakeNutritionSyncStore(entries = listOf(entry))
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val health = FakeNutritionHealthSync(onWrite = {
            writeStarted.complete(Unit)
            releaseWrite.await()
        })
        val retry = NutritionHealthRetry(store, health, scope = this)

        // The regression: Log awaited this write, so a stalled binder froze the Review Food sheet.
        val job = retry.syncInBackground(entry, isUpdate = false)!!
        // Queued before the job has even started, so a process death cannot lose the write.
        assertEquals(setOf(entry.id.toString()), store.pending.value)
        writeStarted.await()
        assertTrue(job.isActive)

        releaseWrite.complete(Unit)
        job.join()
        assertEquals(listOf(entry.id), health.writes)
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun backgroundSyncDropsItsQueuedIdWhenPermissionIsDenied() = runBlocking {
        val entry = foodEntry("Dal")
        val store = FakeNutritionSyncStore(entries = listOf(entry))
        val health = FakeNutritionHealthSync(gate = NutritionWriteGate.DENIED)
        val retry = NutritionHealthRetry(store, health, scope = this)

        retry.syncInBackground(entry, isUpdate = false)!!.join()

        assertTrue(health.writes.isEmpty())
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun backgroundSyncDoesNotRequeueAnEntryForgottenBeforeItRan() = runBlocking {
        val entry = foodEntry("Rajma")
        val store = FakeNutritionSyncStore(entries = listOf(entry))
        val health = FakeNutritionHealthSync()
        val retry = NutritionHealthRetry(store, health, scope = this)

        val job = retry.syncInBackground(entry, isUpdate = false)!!
        retry.forget(entry.id)
        job.join()

        assertTrue(health.writes.isEmpty())
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun probeFailureDoesNotDiscardTheWrite() = runBlocking {
        val entry = foodEntry("Svinemorbrad")
        val store = FakeNutritionSyncStore(entries = listOf(entry))
        // The regression: an unreachable Health Connect used to read as "no permission".
        val health = FakeNutritionHealthSync(gate = NutritionWriteGate.UNKNOWN, writeSucceeds = false)
        val retry = NutritionHealthRetry(store, health)

        retry.sync(entry, isUpdate = false)

        assertEquals(1, health.writes.size) // attempted rather than skipped
        assertEquals(setOf(entry.id.toString()), store.pending.value)
    }

    @Test
    fun probeStillFailingKeepsTheQueueIntact() = runBlocking {
        val entry = foodEntry("Wasa Sport")
        val store = FakeNutritionSyncStore(
            entries = listOf(entry),
            pending = setOf(entry.id.toString())
        )
        val health = FakeNutritionHealthSync(gate = NutritionWriteGate.UNKNOWN)
        val retry = NutritionHealthRetry(store, health)

        retry.retryPending()

        assertTrue(health.updated.isEmpty())
        assertEquals(setOf(entry.id.toString()), store.pending.value)
    }

    @Test
    fun revokedPermissionClearsTheQueueInsteadOfRetryingForever() = runBlocking {
        val entry = foodEntry("Kaffe")
        val store = FakeNutritionSyncStore(
            entries = listOf(entry),
            pending = setOf(entry.id.toString())
        )
        val health = FakeNutritionHealthSync(gate = NutritionWriteGate.DENIED)
        val retry = NutritionHealthRetry(store, health)

        retry.retryPending()

        assertTrue(health.updated.isEmpty())
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun deletedEntryIsDroppedFromTheQueueRatherThanRecreated() = runBlocking {
        val gone = UUID.randomUUID()
        val kept = foodEntry("Skyr")
        val store = FakeNutritionSyncStore(
            entries = listOf(kept),
            pending = setOf(gone.toString(), kept.id.toString())
        )
        val health = FakeNutritionHealthSync()
        val retry = NutritionHealthRetry(store, health)

        retry.retryPending()

        assertEquals(listOf(kept.id), health.updated)
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun writeCompletingAfterForgetRollsBackHealthConnect() = runBlocking {
        val entry = foodEntry("Toast")
        val store = FakeNutritionSyncStore(
            entries = listOf(entry),
            pending = setOf(entry.id.toString())
        )
        val health = FakeNutritionHealthSync(onUpdate = {
            store.pending.value = emptySet()
        })
        val retry = NutritionHealthRetry(store, health)

        retry.retryPending()

        assertEquals(listOf(entry.id), health.deleted)
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun writeCompletingAfterTheEntryIsGoneRollsBackHealthConnect() = runBlocking {
        // The reachable half of the rollback. `forget` takes the mutex the pass is
        // holding, so the queue cannot empty mid-write; the local row can, because
        // combining, replacing or clearing the log rewrites it without the lock.
        val entry = foodEntry("Rugbrod")
        val store = FakeNutritionSyncStore(
            entries = listOf(entry),
            pending = setOf(entry.id.toString())
        )
        val health = FakeNutritionHealthSync(onUpdate = {
            store.entries.value = emptyList()
        })
        val retry = NutritionHealthRetry(store, health)

        retry.retryPending()

        assertEquals(listOf(entry.id), health.deleted)
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun syncIsANoOpWhileHealthConnectIsSwitchedOff() = runBlocking {
        val entry = foodEntry("Havregryn")
        val store = FakeNutritionSyncStore(entries = listOf(entry), enabled = false)
        val health = FakeNutritionHealthSync(writeSucceeds = false)
        val retry = NutritionHealthRetry(store, health)

        retry.sync(entry, isUpdate = false)

        assertTrue(health.writes.isEmpty())
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun successfulWriteLeavesNothingQueued() = runBlocking {
        val entry = foodEntry("Chia")
        val store = FakeNutritionSyncStore(entries = listOf(entry), pending = setOf(entry.id.toString()))
        val health = FakeNutritionHealthSync()
        val retry = NutritionHealthRetry(store, health)

        retry.sync(entry, isUpdate = false)

        assertEquals(listOf(entry.id), health.writes)
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun batchResolvesTheGateOncePerCallNotOncePerEntry() = runBlocking {
        // A diary import can carry hundreds of entries. writeGate() is a Health Connect IPC
        // round-trip, so probing per entry would put the import on the wire hundreds of times.
        val entries = (1..50).map { foodEntry("Meal $it") }
        val store = FakeNutritionSyncStore(entries = entries)
        val health = FakeNutritionHealthSync()
        val retry = NutritionHealthRetry(store, health)

        retry.syncAll(entries, isUpdate = true)

        assertEquals(1, health.gateProbes)
        assertEquals(50, health.updated.size)
    }

    @Test
    fun batchQueuesOnlyTheEntriesThatFailed() = runBlocking {
        val ok = foodEntry("Skyr")
        val bad = foodEntry("Spaghetti Bolognese")
        val store = FakeNutritionSyncStore(entries = listOf(ok, bad))
        val health = FakeNutritionHealthSync(failFor = setOf(bad.id))
        val retry = NutritionHealthRetry(store, health)

        retry.syncAll(listOf(ok, bad), isUpdate = false)

        assertEquals(setOf(bad.id.toString()), store.pending.value)
    }

    @Test
    fun syncUsesLatestEntrySnapshotFromStore() = runBlocking {
        val entry = foodEntry("Pasta")
        val updated = entry.copy(calories = 480)
        val store = FakeNutritionSyncStore(entries = listOf(updated))
        val health = FakeNutritionHealthSync()
        val retry = NutritionHealthRetry(store, health)

        retry.sync(entry, isUpdate = true)

        assertEquals(480, health.lastWrittenCalories)
    }

    private fun foodEntry(name: String) = FoodEntry(
        name = name,
        calories = 315,
        protein = 18.0,
        carbs = 40.0,
        fat = 9.0,
        timestamp = Instant.parse("2026-08-27T17:54:00Z"),
        source = FoodSource.SNAP_FOOD
    )
}

private class FakeNutritionSyncStore(
    entries: List<FoodEntry> = emptyList(),
    pending: Set<String> = emptySet(),
    enabled: Boolean = true
) : NutritionSyncStore {
    val pending = MutableStateFlow(pending)
    val entries = MutableStateFlow(entries)
    override val foodEntries: Flow<List<FoodEntry>> = this.entries
    override val healthConnectEnabled: Flow<Boolean> = MutableStateFlow(enabled)
    override val pendingNutritionHealthWrites: Flow<Set<String>> = this.pending
    override suspend fun updatePendingNutritionHealthWrites(transform: (Set<String>) -> Set<String>) {
        pending.update(transform)
    }
}

private class FakeNutritionHealthSync(
    private val gate: NutritionWriteGate = NutritionWriteGate.ALLOWED,
    var writeSucceeds: Boolean = true,
    private val failFor: Set<UUID> = emptySet(),
    private val onWrite: suspend () -> Unit = {},
    private val onUpdate: suspend () -> Unit = {}
) : NutritionHealthSync {
    val writes = mutableListOf<UUID>()
    val updated = mutableListOf<UUID>()
    val deleted = mutableListOf<UUID>()
    var gateProbes = 0
        private set
    var lastWrittenCalories: Int? = null
        private set

    override suspend fun writeGate(): NutritionWriteGate {
        gateProbes++
        return gate
    }

    override suspend fun write(entry: FoodEntry): Boolean {
        onWrite()
        writes += entry.id
        lastWrittenCalories = entry.calories
        return succeeds(entry)
    }

    override suspend fun update(entry: FoodEntry): Boolean {
        onUpdate()
        updated += entry.id
        lastWrittenCalories = entry.calories
        return succeeds(entry)
    }

    override suspend fun delete(entryId: UUID): Boolean {
        deleted += entryId
        return true
    }

    private fun succeeds(entry: FoodEntry) = writeSucceeds && entry.id !in failFor
}
