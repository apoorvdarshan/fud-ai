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

    suspend fun endActive(at: Instant = Instant.now(), updatedSession: FastingSession? = null): FastingSession? {
        val current = prefs.fastingSessions.first()
        val active = current.lastOrNull { it.isActive } ?: return null
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
        val completed = source.copy(endedAt = maxOf(at, source.startedAt))
        if (current.any { it.id != completed.id && overlaps(completed, it) }) return null
        prefs.updateFastingSessions { stored -> stored.map { if (it.id == active.id) completed else it } }
        return completed
    }

    suspend fun cancelActive() {
        prefs.updateFastingSessions { current -> current.filterNot { it.isActive } }
    }

    suspend fun update(session: FastingSession): Boolean {
        val current = prefs.fastingSessions.first()
        if (session.isActive && current.any { it.id != session.id && it.isActive }) return false
        val validated = session.copy(
            endedAt = session.endedAt?.let { maxOf(it, session.startedAt) },
            goalMinutes = session.goalMinutes.coerceIn(
                FastingDefaults.MIN_GOAL_MINUTES,
                FastingDefaults.MAX_GOAL_MINUTES
            )
        )
        if (current.any { it.id != validated.id && overlaps(validated, it) }) return false
        prefs.updateFastingSessions { stored -> stored.map { if (it.id == session.id) validated else it } }
        return true
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
