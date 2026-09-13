package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.FastingDefaults
import com.apoorvdarshan.calorietracker.models.FastingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

class FastingRepository(private val prefs: PreferencesStore) {
    val sessions: Flow<List<FastingSession>> = prefs.fastingSessions.map { list -> list.sortedBy { it.startedAt } }

    suspend fun active(): FastingSession? = prefs.fastingSessions.first().lastOrNull { it.isActive }

    suspend fun start(goalMinutes: Int, at: Instant = Instant.now()): FastingSession? {
        val session = FastingSession(
            startedAt = at,
            goalMinutes = goalMinutes.coerceIn(FastingDefaults.MIN_GOAL_MINUTES, FastingDefaults.MAX_GOAL_MINUTES)
        )
        var started = false
        prefs.updateFastingSessions { current ->
            if (current.any { it.isActive } || current.any { overlaps(session, it) }) return@updateFastingSessions current
            started = true
            current + session
        }
        return if (started) session else null
    }

    /**
     * Completes the active session. Lookup, validation and replacement all run
     * against the list inside the DataStore transaction, so a session started,
     * edited or deleted by a concurrent caller cannot slip past the overlap
     * check or make this report success after changing nothing.
     */
    suspend fun endActive(at: Instant = Instant.now(), updatedSession: FastingSession? = null): FastingSession? {
        var completed: FastingSession? = null
        prefs.updateFastingSessions { current ->
            val active = current.lastOrNull { it.isActive } ?: return@updateFastingSessions current
            val proposed = updatedSession?.takeIf { it.id == active.id }
            val source = proposed
                ?.copy(
                    endedAt = null,
                    goalMinutes = proposed.goalMinutes.coerceIn(
                        FastingDefaults.MIN_GOAL_MINUTES,
                        FastingDefaults.MAX_GOAL_MINUTES
                    )
                )
                ?: active
            val candidate = source.copy(endedAt = maxOf(at, source.startedAt))
            if (current.any { it.id != candidate.id && overlaps(candidate, it) }) return@updateFastingSessions current
            completed = candidate
            current.map { if (it.id == active.id) candidate else it }
        }
        return completed
    }

    suspend fun cancelActive() {
        prefs.updateFastingSessions { current -> current.filterNot { it.isActive } }
    }

    /** Returns false when the session no longer exists or the edit would violate an invariant. */
    suspend fun update(session: FastingSession): Boolean {
        var updated = false
        prefs.updateFastingSessions { current ->
            if (current.none { it.id == session.id }) return@updateFastingSessions current
            if (session.isActive && current.any { it.id != session.id && it.isActive }) return@updateFastingSessions current
            val validated = session.copy(
                endedAt = session.endedAt?.let { maxOf(it, session.startedAt) },
                goalMinutes = session.goalMinutes.coerceIn(
                    FastingDefaults.MIN_GOAL_MINUTES,
                    FastingDefaults.MAX_GOAL_MINUTES
                )
            )
            if (current.any { it.id != validated.id && overlaps(validated, it) }) return@updateFastingSessions current
            updated = true
            current.map { if (it.id == session.id) validated else it }
        }
        return updated
    }

    suspend fun delete(id: UUID) {
        prefs.updateFastingSessions { current -> current.filterNot { it.id == id } }
    }

    private fun overlaps(left: FastingSession, right: FastingSession): Boolean {
        val leftEnd = left.endedAt ?: Instant.MAX
        val rightEnd = right.endedAt ?: Instant.MAX
        return left.startedAt < rightEnd && right.startedAt < leftEnd
    }
}
