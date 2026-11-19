package com.apoorvdarshan.calorietracker.services.health

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class HealthReadPagingTest {
    @Test
    fun followsEveryPage() = runTest {
        val pages = mapOf<String?, Pair<List<Int>, String?>>(
            null to (listOf(1, 2) to "page2"),
            "page2" to (listOf(3) to "page3"),
            "page3" to (emptyList<Int>() to null)
        )

        assertEquals(listOf(1, 2, 3), readAllPages { pages.getValue(it) })
    }

    @Test
    fun emptyTokenEndsTheReadWithoutAnotherRequest() = runTest {
        var requests = 0
        val read = readAllPages {
            requests += 1
            check(requests == 1)
            listOf(1, 2) to ""
        }

        assertEquals(listOf(1, 2), read)
        assertEquals(1, requests)
    }

    @Test
    fun repeatedTokenFailsWithoutLoopingOrAcceptingPartialHistory() = runTest {
        var requests = 0
        val read = readAllPages {
            requests += 1
            check(requests <= 2)
            listOf(requests) to "repeated"
        }

        assertNull(read)
        assertEquals(2, requests)
    }

    @Test
    fun aFailedPageFailsTheWholeRead() = runTest {
        // A rate limit on page two must not pass off page one as the complete history:
        // the history import would mark itself done and never read the rest.
        val read = readAllPages { token ->
            if (token == null) listOf(1, 2) to "page2" else throw IOException("rate limited")
        }

        assertNull(read)
    }

    @Test
    fun exposesTheOriginalReadFailureForTheImportPreview() = runTest {
        val failure = IOException("rate limited")
        val caught = assertThrows(IOException::class.java) {
            runBlocking {
                readAllPages<Int>(onFailure = { throw it }) { throw failure }
            }
        }
        assertEquals(failure, caught)
    }

    @Test
    fun cancellationIsNotTakenForAFailedRead() {
        assertThrows(CancellationException::class.java) {
            runBlocking { readAllPages<Int> { throw CancellationException("sync cancelled") } }
        }
    }
}
