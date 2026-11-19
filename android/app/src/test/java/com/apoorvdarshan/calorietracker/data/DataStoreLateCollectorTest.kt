package com.apoorvdarshan.calorietracker.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Guards against AndroidX b/431787506 (fixed in DataStore 1.3.0) with a real file-backed
 * DataStore: the write is held open after DataStore has bumped its version, and a new
 * collector starts in that window. It must still receive the write, or screens opened
 * during a save (e.g. right after logging water) keep showing stale values.
 */
class DataStoreLateCollectorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private val serializer = GatedIntSerializer()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        serializer.release()
        scope.cancel()
    }

    @Test
    fun collectorStartedDuringWriteReceivesTheWrite() = runBlocking {
        val store = newStore()
        assertEquals(0, store.data.first())

        val late = raceLateCollectorAgainstWrite(store)

        assertEquals(1, withTimeout(10_000) { late.await() })
        assertEquals(1, store.data.first())
    }

    /** Starts a write, holds it after the version bump, starts a new collector, then finishes the write. */
    private suspend fun raceLateCollectorAgainstWrite(store: DataStore<Int>): kotlinx.coroutines.Deferred<Int> {
        serializer.holdWrites()
        val write = scope.async { store.updateData { 1 } }
        withTimeout(10_000) { serializer.writeStarted.await() }

        val firstSeen = CompletableDeferred<Int>()
        val late = scope.async {
            store.data.onEach { firstSeen.complete(it) }.first { it == 1 }
        }
        // The late collector has read the old file while the write is still in progress.
        assertEquals(0, withTimeout(10_000) { firstSeen.await() })

        serializer.release()
        withTimeout(10_000) { write.await() }
        return late
    }

    private fun newStore(): DataStore<Int> = DataStoreFactory.create(
        serializer = serializer,
        scope = scope,
        produceFile = { File(folder.root, "race.store") }
    )

    /** Int serializer whose writes can be held open, as a slow disk would. */
    private class GatedIntSerializer : Serializer<Int> {
        override val defaultValue = 0

        @Volatile private var gate: CompletableDeferred<Unit>? = null
        val writeStarted = CompletableDeferred<Unit>()

        fun holdWrites() {
            gate = CompletableDeferred()
        }

        fun release() {
            gate?.complete(Unit)
        }

        override suspend fun readFrom(input: InputStream): Int = DataInputStream(input).readInt()

        override suspend fun writeTo(t: Int, output: OutputStream) {
            gate?.let {
                writeStarted.complete(Unit)
                it.await()
            }
            DataOutputStream(output).writeInt(t)
        }
    }
}
