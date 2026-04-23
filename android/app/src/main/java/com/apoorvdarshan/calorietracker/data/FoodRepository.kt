package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.MealType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * CRUD + reactive reads for food entries. Port of iOS FoodStore.
 * Backed by [PreferencesStore] (entries + favorites serialized as JSON).
 */
class FoodRepository(private val prefs: PreferencesStore) {
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

    suspend fun addEntry(entry: FoodEntry) {
        val current = prefs.foodEntries.first()
        prefs.setFoodEntries(current + entry)
    }

    suspend fun updateEntry(entry: FoodEntry) {
        val current = prefs.foodEntries.first()
        val index = current.indexOfFirst { it.id == entry.id }
        if (index < 0) return
        val updated = current.toMutableList().also { it[index] = entry }
        prefs.setFoodEntries(updated)
    }

    suspend fun deleteEntry(entryId: UUID) {
        val current = prefs.foodEntries.first()
        prefs.setFoodEntries(current.filter { it.id != entryId })
    }

    suspend fun replaceAll(entries: List<FoodEntry>) {
        prefs.setFoodEntries(entries)
    }

    suspend fun clear() {
        prefs.setFoodEntries(emptyList())
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
        val current = prefs.favoriteFoodEntries.first().toMutableList()
        val idx = current.indexOfFirst { it.favoriteKey == entry.favoriteKey }
        if (idx >= 0) {
            current.removeAt(idx)
        } else {
            // Drop any other entry with the same id (defensive — should not
            // normally happen since we matched by favoriteKey above).
            current.removeAll { it.id == entry.id }
            current.add(entry)
        }
        prefs.setFavoriteFoodEntries(current)
        prefs.setFavoriteKeys(current.map { it.favoriteKey }.toSet())
    }

    /**
     * Reorder a favorite from index [from] to index [to]. Mirrors iOS
     * FoodStore.moveFavorite using SwiftUI's `Array.move(fromOffsets:toOffset:)`
     * semantics — [to] is the *destination* index in the post-removal list.
     */
    suspend fun moveFavorite(from: Int, to: Int) {
        ensureFavoritesMigrated()
        val list = prefs.favoriteFoodEntries.first().toMutableList()
        if (from !in list.indices) return
        val item = list.removeAt(from)
        val safeTo = to.coerceIn(0, list.size)
        list.add(safeTo, item)
        prefs.setFavoriteFoodEntries(list)
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

    // -- Recents / Frequent ---------------------------------------------

    suspend fun recent(limit: Int = 50): List<FoodEntry> =
        prefs.foodEntries.first().sortedByDescending { it.timestamp }.take(limit)

    suspend fun frequent(): List<FrequentFoodGroup> {
        val all = prefs.foodEntries.first()
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
