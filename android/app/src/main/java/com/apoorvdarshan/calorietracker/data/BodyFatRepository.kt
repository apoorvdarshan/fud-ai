package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Local-only store for body-fat history. Mirrors WeightRepository — Codable
 * persistence via PreferencesStore, no goal-crossing notification yet, and
 * (until Health Connect is wired up) no external sync.
 *
 * The latest entry's value is treated as the user's "current" body fat % and
 * pushed back to UserProfile.bodyFatPercentage on every add via
 * syncProfileBodyFatToLatest, so Katch-McArdle BMR + Settings → Body Fat row
 * never drift apart.
 */
class BodyFatRepository(
    private val prefs: PreferencesStore,
    private val profileRepository: ProfileRepository
) {
    val entries: Flow<List<BodyFatEntry>> = prefs.bodyFatEntries.map { it.sortedBy { e -> e.date } }

    val latest: Flow<BodyFatEntry?> = prefs.bodyFatEntries.map { list ->
        list.maxByOrNull { it.date }
    }

    /** Safe to call repeatedly — no-ops once any entries exist. Mirrors
     *  WeightRepository.seedInitialWeightIfEmpty. */
    suspend fun seedInitialBodyFatIfEmpty(fraction: Double) {
        if (prefs.bodyFatEntries.first().isNotEmpty()) return
        addEntry(BodyFatEntry(bodyFatFraction = fraction))
    }

    suspend fun addEntry(entry: BodyFatEntry) {
        val current = prefs.bodyFatEntries.first()
        prefs.setBodyFatEntries(current + entry)
        syncProfileBodyFatToLatest()
    }

    suspend fun deleteEntry(id: UUID) {
        val current = prefs.bodyFatEntries.first()
        prefs.setBodyFatEntries(current.filter { it.id != id })
        syncProfileBodyFatToLatest()
    }

    suspend fun replaceAll(entries: List<BodyFatEntry>) {
        prefs.setBodyFatEntries(entries)
        syncProfileBodyFatToLatest()
    }

    suspend fun clear() {
        prefs.setBodyFatEntries(emptyList())
    }

    suspend fun entriesInRange(from: Instant, to: Instant): List<BodyFatEntry> =
        prefs.bodyFatEntries.first()
            .filter { it.date in from..to }
            .sortedBy { it.date }

    /** Bulk-add for Health Connect backfill (when wired). Bypasses the per-entry
     *  callback fan-out so importing years of historical scale data doesn't
     *  trigger N round-trips back to HC. */
    suspend fun importExternalEntries(external: List<BodyFatEntry>) {
        if (external.isEmpty()) return
        val current = prefs.bodyFatEntries.first()
        prefs.setBodyFatEntries(current + external)
        syncProfileBodyFatToLatest()
    }

    /** Keep UserProfile.bodyFatPercentage aligned with the latest reading so
     *  Katch-McArdle BMR + Settings → Body Fat row never drift apart. If the
     *  store is empty after a delete, leave the profile value alone — silently
     *  dropping someone's BMR formula because they cleared one row would
     *  surprise them; they can clear it explicitly via Settings. */
    private suspend fun syncProfileBodyFatToLatest() {
        val profile = profileRepository.current() ?: return
        val newest = prefs.bodyFatEntries.first().maxByOrNull { it.date } ?: return
        if (abs((profile.bodyFatPercentage ?: -1.0) - newest.bodyFatFraction) > 0.0001) {
            profileRepository.save(profile.copy(bodyFatPercentage = newest.bodyFatFraction))
        }
    }
}
