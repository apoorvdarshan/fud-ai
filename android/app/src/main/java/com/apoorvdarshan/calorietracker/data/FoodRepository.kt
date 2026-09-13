package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.combineFoodEntries
import com.apoorvdarshan.calorietracker.services.ReviewPrompter
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.services.health.HealthConnectManager
import com.apoorvdarshan.calorietracker.services.health.NutritionWriteGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

/**
 * CRUD + reactive reads for food entries. Port of iOS FoodStore.
 * Backed by [PreferencesStore] (entries + favorites serialized as JSON).
 */
class FoodRepository(
    private val prefs: PreferencesStore,
    private val health: HealthConnectManager? = null,
    private val imageStore: FoodImageStore? = null
) {
    val entries: Flow<List<FoodEntry>> = prefs.foodEntries

    /**
     * Favorites are now stored as an ordered list of [FoodEntry] copies (not
     * a Set of keys), mirroring iOS `FoodStore.favorites`. The list owns its
     * own copies so a favorite survives deletion of the original log entry
     * and the user-defined order persists across restarts.
     *
     * Reads also trigger a one-time migration from the legacy `favoriteKeys`
     * Set if the new list is empty but the old set has entries — done via a
     * suspend [migratedFavorites] helper that the Saved Meals UI calls
     * directly when the sheet opens.
     */
    val favorites: Flow<List<FoodEntry>> = prefs.favoriteFoodEntries

    /** Run the migration once and return the (possibly newly-seeded) list. */
    suspend fun migratedFavorites(): List<FoodEntry> {
        ensureFavoritesMigrated()
        return prefs.favoriteFoodEntries.first()
    }

    /**
     * Derived from [favorites] so existing call sites that read favoriteKeys
     * (Home list heart icon, Saved Meals heart icon, etc.) keep working
     * without change.
     */
    val favoriteKeys: Flow<Set<String>> = prefs.favoriteFoodEntries.map { list ->
        list.map { it.favoriteKey }.toSet()
    }

    fun entriesForDate(date: LocalDate): Flow<List<FoodEntry>> = entries.map { list ->
        list.filter { it.timestamp.atZone(ZoneId.systemDefault()).toLocalDate() == date }
            .sortedByDescending { it.timestamp }
    }

    fun entriesByMealForDate(date: LocalDate): Flow<List<Pair<MealType, List<FoodEntry>>>> =
        entriesForDate(date).map { dayEntries ->
            MealType.values().mapNotNull { meal ->
                val mealEntries = dayEntries.filter { it.mealType == meal }
                if (mealEntries.isEmpty()) null else meal to mealEntries
            }
        }

    suspend fun addEntry(entry: FoodEntry): Boolean {
        if (prefs.fastingSessions.first().any { it.isActive }) return false
        // Decode + append + encode in one transaction: a diary that fails to
        // decode is preserved, never replaced by `[entry]`.
        prefs.updateFoodEntries { current -> current + entry }
        healthRetry.sync(entry, isUpdate = false)
        // One-time organic review moment: the first successful food log (iOS parity).
        if (!prefs.reviewPromptedAfterFirstLog.first()) {
            prefs.setReviewPromptedAfterFirstLog(true)
            ReviewPrompter.requestReview.value = true
        }
        return true
    }

    suspend fun updateEntry(entry: FoodEntry) {
        // Legacy favorites must keep their original photos before the diary copy changes.
        ensureFavoritesMigrated()
        var previous: FoodEntry? = null
        prefs.updateFoodEntries { current ->
            val index = current.indexOfFirst { it.id == entry.id }
            if (index < 0) return@updateFoodEntries current
            previous = current[index]
            current.toMutableList().also { it[index] = entry }
        }
        val removedImages = (previous ?: return).allImageFilenames.toSet() - entry.allImageFilenames.toSet()
        if (imageStore != null && removedImages.isNotEmpty()) {
            // Only consider this edit's removed files, preserving shared saved meals,
            // other log entries and the recoverable analysis draft. A decode failure
            // skips cleanup instead of treating unreadable owners as absent.
            prefs.foodImageReferenceFilenames()?.let { referenced ->
                removedImages.filterNot { it in referenced }.forEach(imageStore::delete)
            }
        }
        if (shouldSyncHealth()) {
            healthRetry.sync(entry, isUpdate = true)
        } else {
            // Sync off: still clean up the stale HC record for this entry (iOS
            // parity, best-effort) so the restore path can't resurrect the
            // pre-edit version later.
            health?.deleteNutrition(entry.id)
            healthRetry.forget(entry.id)
        }
    }

    suspend fun deleteEntry(entryId: UUID) {
        ensureFavoritesMigrated()
        // Drop the queue entry under the same mutex retry/sync uses, before the local
        // row disappears, so an in-flight retry cannot recreate Health Connect data.
        healthRetry.forget(entryId)
        prefs.updateFoodEntries { current -> current.filter { it.id != entryId } }
        pruneOrphanedImages()
        // Delete even when sync is off (iOS parity, best-effort) — a surviving
        // fudai-tagged record would resurrect through restoreFromHealthConnect.
        health?.deleteNutrition(entryId)
    }

    /**
     * Merge selected diary foods into one combined meal entry and remove the
     * originals so macros are not double-counted.
     */
    suspend fun combineIntoMeal(entryIds: Collection<UUID>): FoodEntry? {
        if (prefs.fastingSessions.first().any { it.isActive }) return null
        ensureFavoritesMigrated()
        val idSet = entryIds.toSet()
        if (idSet.size < 2) return null
        var selected: List<FoodEntry> = emptyList()
        var combined: FoodEntry? = null
        prefs.updateFoodEntries { current ->
            selected = current.filter { it.id in idSet }
            if (selected.size < 2) return@updateFoodEntries current
            val merged = combineFoodEntries(selected)
            combined = merged
            current.filter { it.id !in idSet } + merged
        }
        val result = combined ?: return null
        pruneOrphanedImages()
        selected.forEach { health?.deleteNutrition(it.id) }
        if (shouldSyncHealth()) {
            health?.writeNutrition(result)
        }
        return result
    }

    suspend fun replaceAll(entries: List<FoodEntry>) {
        ensureFavoritesMigrated()
        prefs.setFoodEntries(entries)
        pruneOrphanedImages()
    }

    /** Apply a validated diary import in one local write and keep Health Connect in sync. */
    suspend fun replaceFromImport(entries: List<FoodEntry>) {
        ensureFavoritesMigrated()
        var previous: List<FoodEntry> = emptyList()
        prefs.updateFoodEntries { current ->
            previous = current
            entries
        }
        val previousById = previous.associateBy { it.id }
        val importedIds = entries.mapTo(mutableSetOf()) { it.id }
        val removedIds = previous.map { it.id }.filterNot { it in importedIds }
        val changed = entries.filter { previousById[it.id] != it }

        pruneOrphanedImages()

        if (shouldSyncHealth()) {
            removedIds.forEach { health?.deleteNutrition(it) }
            healthRetry.forgetAll(removedIds)
            healthRetry.syncAll(changed, isUpdate = true)
        } else {
            // Prevent stale records written by an earlier sync-enabled session
            // from restoring pre-import values later.
            removedIds.forEach { health?.deleteNutrition(it) }
            changed.filter { it.id in previousById }.forEach { health?.deleteNutrition(it.id) }
        }
    }

    suspend fun clear() {
        ensureFavoritesMigrated()
        prefs.setFoodEntries(emptyList())
        pruneOrphanedImages()
    }

    // -- Favorites --------------------------------------------------------

    suspend fun isFavorite(entry: FoodEntry): Boolean {
        return prefs.favoriteFoodEntries.first().any { it.favoriteKey == entry.favoriteKey }
    }

    /**
     * Toggle favorite status by favoriteKey. Mirrors iOS
     * FoodStore.toggleFavorite — if a favorite with the same favoriteKey
     * exists, remove it; otherwise append a *copy* of [entry] to the list.
     * The legacy `favoriteKeys` Set is also kept in sync for any older code
     * paths still reading it directly.
     */
    suspend fun toggleFavorite(entry: FoodEntry) {
        ensureFavoritesMigrated()
        val current = prefs.updateFavoriteFoodEntries { stored ->
            val list = stored.toMutableList()
            val idx = list.indexOfFirst { it.favoriteKey == entry.favoriteKey }
            if (idx >= 0) {
                list.removeAt(idx)
            } else {
                // Drop any other entry with the same id (defensive — should not
                // normally happen since we matched by favoriteKey above).
                list.removeAll { it.id == entry.id }
                list.add(entry)
            }
            list
        }
        prefs.setFavoriteKeys(current.map { it.favoriteKey }.toSet())
        pruneOrphanedImages()
    }

    /**
     * Reorder a favorite from index [from] to index [to]. Mirrors iOS
     * FoodStore.moveFavorite using SwiftUI's `Array.move(fromOffsets:toOffset:)`
     * semantics — [to] is the *destination* index in the post-removal list.
     */
    suspend fun moveFavorite(from: Int, to: Int) {
        ensureFavoritesMigrated()
        prefs.updateFavoriteFoodEntries { stored ->
            val list = stored.toMutableList()
            if (from !in list.indices) return@updateFavoriteFoodEntries stored
            val item = list.removeAt(from)
            val safeTo = to.coerceIn(0, list.size)
            list.add(safeTo, item)
            list
        }
    }

    /**
     * One-time migration: if the new ordered favoriteFoodEntries list is
     * empty but the legacy favoriteKeys Set has entries, reconstruct the
     * ordered list from current food log entries (best-effort — no preserved
     * order since the old format never tracked one).
     */
    private suspend fun ensureFavoritesMigrated() {
        val ordered = prefs.favoriteFoodEntries.first()
        if (ordered.isNotEmpty()) return
        val legacy = prefs.favoriteKeys.first()
        if (legacy.isEmpty()) return
        val all = prefs.foodEntries.first()
        val seeded = legacy.mapNotNull { key -> all.firstOrNull { it.favoriteKey == key } }
        if (seeded.isNotEmpty()) prefs.setFavoriteFoodEntries(seeded)
    }

    /**
     * Deferred retry for nutrition writes Health Connect never confirmed. The adapter keeps
     * [HealthConnectManager] out of the retry's own type signature, matching how
     * [WorkoutHealthSync] is supplied to [WorkoutRepository].
     */
    private val healthRetry = NutritionHealthRetry(
        prefs,
        health?.let { manager ->
            object : NutritionHealthSync {
                override suspend fun writeGate() = manager.nutritionWriteGate()
                override suspend fun write(entry: FoodEntry) = manager.writeNutrition(entry)
                override suspend fun update(entry: FoodEntry) = manager.updateNutrition(entry)
                override suspend fun delete(entryId: UUID) = manager.deleteNutrition(entryId)
            }
        }
    )

    /** Re-attempt writes that were never confirmed. Called from the app-foreground sync. */
    suspend fun retryPendingHealthWrites() = healthRetry.retryPending()

    /**
     * Whether the user has Health Connect sync switched on. Deliberately does not ask
     * whether the nutrition-write permission is granted: that question is answered inside
     * [NutritionHealthRetry] via [NutritionWriteGate], because a permission probe that
     * fails must not be read as "no permission". Doing so here silently discarded writes.
     */
    private suspend fun shouldSyncHealth(): Boolean {
        if (health == null) return false
        return prefs.healthConnectEnabled.first()
    }

    /**
     * Repairs image files orphaned by older Android builds. Food-log entries,
     * saved meals, and a recoverable in-progress analysis draft are all owners;
     * only filenames absent from every owner are removed.
     */
    suspend fun pruneOrphanedImages() {
        val store = imageStore ?: return
        // Preserve legacy saved meals before deciding which files are unused.
        ensureFavoritesMigrated()
        val referenced = prefs.foodImageReferenceFilenames() ?: return
        store.pruneUnreferenced(referenced)
    }

    // -- Restore from Health Connect --------------------------------------

    /**
     * Rebuilds the food log from the NutritionRecords Fud AI itself wrote to
     * Health Connect — the restore path after a reinstall or new phone, where
     * Health Connect data survives but app storage doesn't. Only records
     * carrying our fudai_(uuid) clientRecordId are considered; the original
     * entry UUID is recovered from the tag so future edits and deletes still
     * target the matching HC record. Ids already in the log and nameless
     * records are skipped, and nothing is written back to Health Connect.
     * Photos, emojis, notes and serving units aren't in HC and don't return.
     */
    suspend fun restoreFromHealthConnect(external: List<com.apoorvdarshan.calorietracker.services.health.ExternalNutrition>) {
        val manager = health ?: return
        prefs.updateFoodEntries { current ->
            val existingIds = current.map { it.id }.toMutableSet()
            val restored = external.mapNotNull { record ->
                val id = manager.ownRecordId(record.clientRecordId) ?: return@mapNotNull null
                val name = record.name?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                // `add` also dedupes ids repeated within the Health Connect batch.
                if (!existingIds.add(id)) return@mapNotNull null
                FoodEntry(
                    id = id,
                    name = name,
                    calories = (record.calories ?: 0.0).roundToInt(),
                    protein = record.protein ?: 0.0,
                    carbs = record.carbs ?: 0.0,
                    fat = record.fat ?: 0.0,
                    timestamp = record.time,
                    source = FoodSource.MANUAL,
                    mealType = record.mealType,
                    sugar = record.sugar,
                    fiber = record.fiber,
                    saturatedFat = record.saturatedFat,
                    monounsaturatedFat = record.monounsaturatedFat,
                    polyunsaturatedFat = record.polyunsaturatedFat,
                    cholesterol = record.cholesterol,
                    caffeine = record.caffeine,
                    sodium = record.sodium,
                    potassium = record.potassium,
                    transFat = record.transFat,
                    calcium = record.calcium,
                    iron = record.iron,
                    magnesium = record.magnesium,
                    zinc = record.zinc,
                    vitaminA = record.vitaminA,
                    vitaminC = record.vitaminC,
                    vitaminD = record.vitaminD,
                    vitaminB12 = record.vitaminB12,
                    vitaminE = record.vitaminE,
                    vitaminK = record.vitaminK,
                    folate = record.folate,
                    // Health Connect NutritionRecord exposes nutrient totals but
                    // no food-mass/custom-metadata field. Preserve that truth as
                    // one logged serving instead of fabricating 100 grams.
                    selectedServingUnit = "serving",
                    selectedServingQuantity = 1.0
                )
            }
            if (restored.isEmpty()) current else (current + restored).sortedBy { it.timestamp }
        }
    }

    // -- Recents / Frequent ---------------------------------------------

    suspend fun recent(days: Int = 30, now: Instant = Instant.now()): List<FoodEntry> {
        val cutoff = now.minus(days.toLong(), ChronoUnit.DAYS)
        return prefs.foodEntries.first()
            .filter { !it.timestamp.isBefore(cutoff) }
            .sortedByDescending { it.timestamp }
    }

    suspend fun frequent(days: Int = 90, now: Instant = Instant.now()): List<FrequentFoodGroup> {
        val cutoff = now.minus(days.toLong(), ChronoUnit.DAYS)
        val all = prefs.foodEntries.first().filter { !it.timestamp.isBefore(cutoff) }
        val aggregates = mutableMapOf<String, Pair<Int, FoodEntry>>()
        for (entry in all) {
            val key = entry.favoriteKey
            val existing = aggregates[key]
            if (existing != null) {
                val (count, template) = existing
                val newTemplate = if (entry.timestamp > template.timestamp) entry else template
                aggregates[key] = (count + 1) to newTemplate
            } else {
                aggregates[key] = 1 to entry
            }
        }
        return aggregates.map { (_, pair) ->
            FrequentFoodGroup(template = pair.second, count = pair.first)
        }.sortedWith(
            compareByDescending<FrequentFoodGroup> { it.count }.thenBy { it.name.lowercase() }
        )
    }
}

data class FrequentFoodGroup(
    val template: FoodEntry,
    val count: Int
) {
    val id: String = template.favoriteKey
    val name: String = template.name
    val calories: Int = template.calories
}

// Helper — converts Instant -> start-of-day in system zone.
@Suppress("unused")
internal fun Instant.toLocalDate(): LocalDate =
    this.atZone(ZoneId.systemDefault()).toLocalDate()
