package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.services.health.ExternalWeight
import com.apoorvdarshan.calorietracker.services.health.HealthConnectManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/** Emitted when the user crosses their goal weight (under -> at/below for lose, over -> at/above for gain). */
data class WeightGoalReachedEvent(val reachedEntry: WeightEntry)

/**
 * CRUD + reactive reads for weight entries. Port of iOS WeightStore.
 * Also handles two cross-cutting behaviors:
 *
 * 1. After each add/delete, syncs [UserProfile.weightKg] to the latest entry
 *    so BMR/TDEE math and Settings stay in sync with Progress.
 * 2. Returns a [WeightGoalReachedEvent] from [addEntry] when the new weight
 *    crosses the user's goal, for the caller to surface as a celebration UI.
 */
class WeightRepository(
    private val prefs: PreferencesStore,
    private val profileRepository: ProfileRepository,
    private val health: HealthConnectManager? = null
) {
    val entries: Flow<List<WeightEntry>> = prefs.weightEntries.map { it.sortedBy { e -> e.date } }

    val latest: Flow<WeightEntry?> = prefs.weightEntries.map { list ->
        list.maxByOrNull { it.date }
    }

    /** Safe to call repeatedly — no-ops once any entries exist. */
    suspend fun seedInitialWeightIfEmpty(weightKg: Double) {
        if (prefs.weightEntries.first().isNotEmpty()) return
        addEntry(WeightEntry(weightKg = weightKg))
    }

    suspend fun addEntry(entry: WeightEntry): WeightGoalReachedEvent? {
        var latestBeforeAdd: WeightEntry? = null
        prefs.updateWeightEntries { current ->
            latestBeforeAdd = current.maxByOrNull { it.date }
            current + entry
        }
        val previousLatest = latestBeforeAdd

        syncProfileWeightToLatest()
        if (shouldSyncHealth()) {
            health?.writeWeight(entry)
        }

        val profile = profileRepository.current()
        val goal = profile?.goalWeightKg
        if (profile != null && goal != null && previousLatest != null) {
            val crossed = when (profile.goal) {
                com.apoorvdarshan.calorietracker.models.WeightGoal.LOSE ->
                    previousLatest.weightKg > goal && entry.weightKg <= goal
                com.apoorvdarshan.calorietracker.models.WeightGoal.GAIN ->
                    previousLatest.weightKg < goal && entry.weightKg >= goal
                com.apoorvdarshan.calorietracker.models.WeightGoal.MAINTAIN -> false
            }
            if (crossed) return WeightGoalReachedEvent(entry)
        }
        return null
    }

    suspend fun deleteEntry(id: UUID) {
        prefs.updateWeightEntries { current -> current.filter { it.id != id } }
        syncProfileWeightToLatest()
        // Delete the HC record even when sync is off (iOS parity, best-effort) —
        // a surviving fudai-tagged record would resurrect through the own-record
        // restore path on the next full backfill.
        health?.deleteWeight(id)
    }

    suspend fun replaceAll(entries: List<WeightEntry>) {
        prefs.setWeightEntries(entries)
        syncProfileWeightToLatest()
    }

    suspend fun clear() {
        prefs.setWeightEntries(emptyList())
    }

    suspend fun entriesInRange(from: Instant, to: Instant): List<WeightEntry> =
        prefs.weightEntries.first()
            .filter { it.date in from..to }
            .sortedBy { it.date }

    private suspend fun syncProfileWeightToLatest() {
        val profile = profileRepository.current() ?: return
        val newest = prefs.weightEntries.first().maxByOrNull { it.date } ?: return
        if (abs(profile.weightKg - newest.weightKg) > 0.01) {
            profileRepository.save(profile.copy(weightKg = newest.weightKg))
        }
    }

    /**
     * Merge externally-sourced weigh-ins (e.g. a Withings scale via Health Connect)
     * into local history. Idempotent: each external record maps to a deterministic id
     * so repeated imports upsert in place instead of duplicating, and the user's own
     * manual entries (random ids) are preserved. Records Fud AI itself wrote restore
     * under their original UUID — after a reinstall the local store is empty and this
     * is what brings the history back; when the entry still exists locally the
     * same-id upsert is a no-op. The change-token path filters own records at the
     * manager level, so live echo-imports are still suppressed.
     */
    suspend fun importExternalWeights(external: List<ExternalWeight>) {
        val manager = health ?: return
        val incoming = external
            .map {
                WeightEntry(
                    id = manager.ownRecordId(it.clientRecordId)
                        ?: externalId(it.clientRecordId, it.recordId, it.time),
                    date = it.time,
                    weightKg = it.weightKg
                )
            }
        if (incoming.isEmpty()) return
        var changed = false
        prefs.updateWeightEntries { current ->
            // associateBy keeps the last duplicate id, so a history that already
            // holds the same id twice collapses instead of failing the import.
            val byId = current.associateBy { it.id }.toMutableMap()
            for (entry in incoming) {
                val existing = byId[entry.id]
                if (existing == null || abs(existing.weightKg - entry.weightKg) > 0.0001 || existing.date != entry.date) {
                    byId[entry.id] = entry
                    changed = true
                }
            }
            if (changed) byId.values.sortedBy { it.date } else current
        }
        if (!changed) return
        syncProfileWeightToLatest()
    }

    /** Stable id for an external record: prefer the source's clientRecordId, then the
     *  Health Connect record id, then the timestamp. Crucially the VALUE is never part of
     *  the seed, so an in-place correction (same record, new weight) upserts in place
     *  instead of leaving an orphaned duplicate. */
    private fun externalId(clientRecordId: String?, recordId: String, time: Instant): UUID {
        val seed = clientRecordId?.takeIf { it.isNotBlank() }
            ?: recordId.takeIf { it.isNotBlank() }
            ?: "hc-weight:${time.toEpochMilli()}"
        return UUID.nameUUIDFromBytes(seed.toByteArray())
    }

    /** Write gate — only push to Health Connect when the user granted weight WRITE. */
    private suspend fun shouldSyncHealth(): Boolean {
        val manager = health ?: return false
        return prefs.healthConnectEnabled.first() && manager.hasWeightWrite()
    }
}
