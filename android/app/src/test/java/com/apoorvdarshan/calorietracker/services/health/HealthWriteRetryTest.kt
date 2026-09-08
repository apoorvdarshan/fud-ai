package com.apoorvdarshan.calorietracker.services.health

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HealthWriteRetryTest {
    @Test fun rapidEditsAndClockChangesStillProduceIncreasingVersions() {
        val versions = HealthWriteVersions()
        assertEquals(1_000L, versions.next(1_000L))
        assertEquals(1_001L, versions.next(1_000L))
        assertEquals(1_002L, versions.next(900L))
    }

    @Test fun temporaryFailureRecoversImmediatelyWithoutBackgroundWork() = runBlocking {
        var attempts = 0
        var queued = false
        val delays = mutableListOf<Long>()
        val success = retryHealthWrite(
            pause = { delays += it }, deferred = { queued = true }, failed = { fail() }
        ) { if (++attempts < 3) throw IOException() }
        assertTrue(success)
        assertFalse(queued)
        assertEquals(listOf(250L, 1_000L), delays)
    }

    @Test fun persistentFailureSchedulesOneDurableRetry() = runBlocking {
        var attempts = 0
        var queued = 0
        val success = retryHealthWrite(pause = {}, deferred = { queued++ }, failed = {}) {
            attempts++
            throw IOException()
        }
        assertFalse(success)
        assertEquals(3, attempts)
        assertEquals(1, queued)
    }

    @Test fun invalidDataAndRevokedPermissionDoNotLoop() = runBlocking {
        for (error in listOf(IllegalArgumentException(), SecurityException())) {
            var attempts = 0
            assertFalse(retryHealthWrite(pause = { fail() }, deferred = { fail() }, failed = {}) {
                attempts++
                throw error
            })
            assertEquals(1, attempts)
        }
    }

    @Test fun screenCancellationDefersSavedWriteAndPropagatesCancellation() = runBlocking {
        var queued = false
        val cancellation = CancellationException("screen closed")
        try {
            retryHealthWrite(deferred = { queued = true }, failed = { fail() }) { throw cancellation }
            fail("Cancellation must propagate")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        assertTrue(queued)
    }

    @Test fun cancellationDuringBackoffAlsoDefersWrite() = runBlocking {
        var queued = false
        try {
            retryHealthWrite(pause = { throw CancellationException() }, deferred = { queued = true }, failed = {}) {
                throw IOException()
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(queued)
    }

    @Test fun deletedEntryIsNotResurrectedByDelayedWrite() = runBlocking {
        var deleted = false
        assertTrue(retryLatestHealthEntry<String>(true, false, { true }, { null }, { fail(); false }, {
            deleted = true; true
        }))
        assertTrue(deleted)
    }

    @Test fun retryUsesCurrentValueEvenForAnOldDeleteRequest() = runBlocking {
        var written = ""
        assertTrue(retryLatestHealthEntry(true, true, { true }, { "edited meal" }, {
            written = it; true
        }, { fail(); false }))
        assertEquals("edited meal", written)
    }

    @Test fun disabledSyncAndRevokedPermissionsPreventExports() = runBlocking {
        for ((enabled, permitted) in listOf(false to true, true to false)) {
            assertTrue(retryLatestHealthEntry<String>(enabled, false, { permitted }, { fail(); null }, {
                fail(); false
            }, { fail(); false }))
        }
    }

    @Test fun explicitDeletionStillCleansUpAfterSyncDisabled() = runBlocking {
        var deleted = false
        assertTrue(retryLatestHealthEntry<String>(false, true, { true }, { fail(); null }, { fail(); false }, {
            deleted = true; true
        }))
        assertTrue(deleted)
    }
}
