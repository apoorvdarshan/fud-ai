package com.apoorvdarshan.calorietracker.services.health

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Retry short service interruptions immediately; leave longer recovery to WorkManager. */
internal suspend fun retryHealthWrite(
    pause: suspend (Long) -> Unit = { delay(it) },
    deferred: () -> Unit,
    failed: (Exception) -> Unit,
    write: suspend () -> Unit
): Boolean {
    for (attempt in 0..2) {
        try {
            write()
            return true
        } catch (cancelled: CancellationException) {
            // A screen closing must not silently lose a locally saved health entry.
            deferred()
            throw cancelled
        } catch (error: Exception) {
            if (error is SecurityException || error is IllegalArgumentException) {
                failed(error)
                return false
            }
            if (attempt == 2) {
                failed(error)
                deferred()
                return false
            }
        }
        try {
            pause(if (attempt == 0) 250L else 1_000L)
        } catch (cancelled: CancellationException) {
            deferred()
            throw cancelled
        }
    }
    return false
}

/** Resolve at execution time so a delayed retry cannot restore a deleted/older payload. */
internal suspend fun <T> retryLatestHealthEntry(
    enabled: Boolean,
    deletionRequested: Boolean,
    permitted: suspend () -> Boolean,
    latest: suspend () -> T?,
    upsert: suspend (T) -> Boolean,
    delete: suspend () -> Boolean
): Boolean {
    if (!enabled && !deletionRequested) return true
    if (!permitted()) return true
    val entry = if (enabled) latest() else null
    return if (entry == null) delete() else upsert(entry)
}

/** Distinct versions even for rapid edits in the same millisecond. */
internal class HealthWriteVersions {
    private val last = AtomicLong(0)
    fun next(nowMillis: Long = System.currentTimeMillis()): Long =
        last.updateAndGet { previous -> maxOf(nowMillis, previous + 1) }
}
