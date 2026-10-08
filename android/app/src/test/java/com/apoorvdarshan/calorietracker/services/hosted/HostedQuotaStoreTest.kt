package com.apoorvdarshan.calorietracker.services.hosted

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class HostedQuotaStoreTest {

    private class MemoryPersistence(var value: String? = null) : QuotaSnapshotPersistence {
        override fun load(): String? = value
        override fun save(value: String) { this.value = value }
    }

    private fun snapshot(plan: String = "plus", day: String = "2026-10-08", used: Int = 3, limit: Int = 30, credits: Int = 50) =
        HostedQuotaSnapshot(plan, day, used, limit, credits)

    private fun store(
        persistence: QuotaSnapshotPersistence = MemoryPersistence(),
        now: Instant = Instant.parse("2026-10-08T12:00:00Z"),
        fetch: suspend (Boolean) -> HostedQuotaSnapshot = { snapshot() }
    ) = HostedQuotaStore(persistence, CoroutineScope(SupervisorJob() + Dispatchers.Default), { now }, fetch)

    @Test
    fun headersNeedAllFiveFieldsAndAreCaseInsensitive() {
        val headers = mapOf(
            "x-fud-quota-plan" to "pro",
            "x-fud-quota-day" to "2026-10-08",
            "x-fud-quota-daily-used" to "4",
            "x-fud-quota-daily-limit" to "60",
            "x-fud-quota-credits" to "150"
        )
        val parsed = HostedQuotaSnapshot.fromHeaders { headers[it.lowercase()] }
        assertEquals(snapshot("pro", used = 4, limit = 60, credits = 150), parsed)
        assertNull(HostedQuotaSnapshot.fromHeaders { if (it == "X-Fud-Quota-Credits") null else headers[it.lowercase()] })
        assertNull(HostedQuotaSnapshot.fromHeaders { if (it == "X-Fud-Quota-Plan") "gold" else headers[it.lowercase()] })
    }

    @Test
    fun decodeRejectsUnknownPlansAndKeepsValidJson() {
        assertNull(HostedQuotaSnapshot.decode("""{"plan":"gold","day":"2026-10-08","dailyUsed":0,"dailyLimit":0,"creditBank":0}"""))
        assertEquals(snapshot(), HostedQuotaSnapshot.decode(HostedQuotaSnapshot.encode(snapshot())))
    }

    @Test
    fun utcDayKeyIgnoresLocalTimeZone() {
        // 23:30 at UTC-8 is already the next UTC day.
        assertEquals("2026-10-09", HostedQuotaSnapshot.utcDayKey(Instant.parse("2026-10-09T07:30:00Z")))
    }

    @Test
    fun snapshotForRollsOverAndHandlesPlanChanges() {
        val persistence = MemoryPersistence(HostedQuotaSnapshot.encode(snapshot(day = "2026-10-07", used = 29)))
        val quota = store(persistence)
        // A new UTC day zeroes usage but keeps credits.
        assertEquals(snapshot(used = 0), quota.snapshotFor(HostedPlan.PLUS))
        // A different plan uses that plan's limit and keeps credits.
        assertEquals(snapshot("pro", used = 0, limit = 60), quota.snapshotFor(HostedPlan.PRO))
    }

    @Test
    fun olderResponsesNeverOverwriteNewerOnes() {
        val quota = store()
        val first = quota.nextRequestSequence()
        val second = quota.nextRequestSequence()
        quota.apply(snapshot(used = 5), second)
        quota.apply(snapshot(used = 2), first)
        assertEquals(5, quota.snapshot.value?.dailyUsed)
        // ...unless the late response is from a newer UTC day.
        quota.apply(snapshot(day = "2026-10-09", used = 0), first)
        assertEquals("2026-10-09", quota.snapshot.value?.day)
    }

    @Test
    fun appliedSnapshotsPersistAcrossInstances() {
        val persistence = MemoryPersistence()
        store(persistence).apply(snapshot(credits = 400), 1)
        assertEquals(400, store(persistence).snapshot.value?.creditBank)
    }

    @Test
    fun concurrentRefreshesShareOneRequest() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val quota = store { calls.incrementAndGet(); gate.await(); snapshot() }
        val a = async(Dispatchers.Default) { quota.refresh() }
        val b = async(Dispatchers.Default) { quota.refresh() }
        waitUntil { calls.get() == 1 }
        yield()
        gate.complete(Unit)
        a.await(); b.await()
        assertEquals(1, calls.get())
    }

    @Test
    fun forcedRefreshNeedsARunThatStartedAfterIt() = runBlocking {
        val forcedFlags = mutableListOf<Boolean>()
        val firstGate = CompletableDeferred<Unit>()
        val quota = store { forced ->
            synchronized(forcedFlags) { forcedFlags += forced }
            if (forcedFlags.size == 1) firstGate.await()
            snapshot()
        }
        // A forced run is already in flight (e.g. a purchase), then another purchase forces again.
        val first = async(Dispatchers.Default) { quota.refresh(force = true) }
        waitUntil { synchronized(forcedFlags) { forcedFlags.size == 1 } }
        val second = async(Dispatchers.Default) { quota.refresh(force = true) }
        yield()
        firstGate.complete(Unit)
        first.await(); second.await()
        assertEquals(listOf(true, true), synchronized(forcedFlags) { forcedFlags.toList() })
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) yield() }
    }
}
