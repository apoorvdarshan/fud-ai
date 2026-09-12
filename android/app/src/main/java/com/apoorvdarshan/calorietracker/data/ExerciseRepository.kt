package com.apoorvdarshan.calorietracker.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the bundled free-exercise-db dataset from assets and exposes filtering /
 * sorting, mirroring the iOS `ExerciseLibraryService` + `FreeExerciseDBLoader`.
 * The asset (exercises.json) is merged in from the iOS resources at build time.
 */
class ExerciseRepository private constructor(
    val exercises: List<ExerciseItem>,
    private val authoredFrames: Map<String, GenderedExerciseFrames>
) {

    fun includingActivities(activities: List<com.apoorvdarshan.calorietracker.models.PlannedExercise>): ExerciseRepository =
        if (activities.isEmpty()) this else ExerciseRepository(
            (exercises + activities.map { it.asExerciseItem() }).distinctBy { it.id }, authoredFrames
        )

    val availableLevels: List<String> by lazy { sortedUnique(exercises.map { it.level }) }
    val availablePrimaryMuscles: List<String> by lazy { sortedUnique(exercises.flatMap { it.primaryMuscles }) }
    val availableSecondaryMuscles: List<String> by lazy { sortedUnique(exercises.flatMap { it.secondaryMuscles }) }
    val availableEquipment: List<String> by lazy { sortedUnique(exercises.map { it.equipment }) }
    val availableForces: List<String> by lazy { sortedUnique(exercises.map { it.force }) }
    val availableMechanics: List<String> by lazy { sortedUnique(exercises.map { it.mechanic }) }
    val availableCategories: List<String> by lazy { sortedUnique(exercises.map { it.category }) }

    /** Categories ordered by exercise count (desc), then name (asc) — mirrors iOS availableCategoryCounts. */
    val availableCategoriesByCount: List<String> by lazy {
        exercises.groupingBy { it.category }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            .map { it.key }
    }

    fun filtered(
        levels: Set<String> = emptySet(),
        equipment: Set<String> = emptySet(),
        primaryMuscles: Set<String> = emptySet(),
        secondaryMuscles: Set<String> = emptySet(),
        forces: Set<String> = emptySet(),
        mechanics: Set<String> = emptySet(),
        categories: Set<String> = emptySet(),
        sort: ExerciseSort = ExerciseSort.NAME,
        searchText: String = ""
    ): List<ExerciseItem> {
        val items = exercises.filter { item ->
            (levels.isEmpty() || levels.contains(item.level)) &&
                (equipment.isEmpty() || equipment.contains(item.equipment)) &&
                (primaryMuscles.isEmpty() || item.primaryMuscles.any { primaryMuscles.contains(it) }) &&
                (secondaryMuscles.isEmpty() || item.secondaryMuscles.any { secondaryMuscles.contains(it) }) &&
                (forces.isEmpty() || forces.contains(item.force)) &&
                (mechanics.isEmpty() || mechanics.contains(item.mechanic)) &&
                (categories.isEmpty() || categories.contains(item.category)) &&
                ExerciseSearch.matches(item.searchableText, searchText, item.id)
        }
        return items.sortedWith(comparator(sort))
    }

    fun visualFor(
        item: ExerciseItem,
        gender: com.apoorvdarshan.calorietracker.models.Gender
    ): ExerciseVisual = ExerciseVisualResolver.resolve(item, gender, authoredFrames)

    private fun comparator(sort: ExerciseSort): Comparator<ExerciseItem> {
        // Case-SENSITIVE to match Swift's `<` (the dataset is ASCII, so ordinal == Swift order).
        val byName = Comparator<ExerciseItem> { a, b -> a.name.compareTo(b.name) }
        fun field(selector: (ExerciseItem) -> String): Comparator<ExerciseItem> =
            Comparator<ExerciseItem> { a, b -> selector(a).compareTo(selector(b), ignoreCase = true) }.then(byName)
        return when (sort) {
            ExerciseSort.NAME -> byName
            ExerciseSort.LEVEL ->
                Comparator<ExerciseItem> { a, b -> levelRank(a.level).compareTo(levelRank(b.level)) }.then(byName)
            ExerciseSort.PRIMARY -> field { it.primaryMusclesTitle }
            ExerciseSort.SECONDARY -> field { it.secondaryMusclesTitle }
            ExerciseSort.CATEGORY -> field { it.category }
            ExerciseSort.FORCE -> field { it.force }
            ExerciseSort.MECHANIC -> field { it.mechanic }
            ExerciseSort.EQUIPMENT -> field { it.equipment }
        }
    }

    private fun sortedUnique(values: List<String>): List<String> =
        values.filter { it.isNotEmpty() }.distinct().sortedWith { a, b ->
            val ra = levelRank(a)
            val rb = levelRank(b)
            if (ra == rb) a.compareTo(b, ignoreCase = true) else ra.compareTo(rb)
        }

    private fun levelRank(level: String): Int = when (level.lowercase()) {
        "beginner" -> 0
        "intermediate" -> 1
        "expert", "advanced" -> 2
        else -> 3
    }

    companion object {
        private const val TAG = "ExerciseRepository"

        @Volatile
        private var instance: ExerciseRepository? = null
        private val warming = AtomicBoolean(false)
        private val warmExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "exercise-repo-warm").apply { isDaemon = true }
        }

        /** Already-loaded singleton, or null if [get]/[warm] has not finished yet. */
        fun peek(): ExerciseRepository? = instance

        /**
         * Parses catalog + visual manifest on a background thread so the first Workouts
         * tab open is not blocked by I/O. Safe to call repeatedly.
         */
        fun warm(context: Context) {
            if (instance != null || !warming.compareAndSet(false, true)) return
            val app = context.applicationContext
            warmExecutor.execute {
                try {
                    get(app)
                } finally {
                    warming.set(false)
                }
            }
        }

        fun get(context: Context): ExerciseRepository =
            instance ?: synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }

        private fun load(context: Context): ExerciseRepository {
            val startedAt = SystemClock.elapsedRealtime()
            val items = loadExercises(context)
            // Never AssetManager.list("") here — ~7k flat PNGs make list() stall for seconds.
            // Packaging audits (ExerciseVisualResolverTest + sync script) enforce completeness.
            val authoredFrames = loadAuthoredFrames(context)
            Log.d(
                TAG,
                "load complete exercises=${items.size} visuals=${authoredFrames.size} " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return ExerciseRepository(items, authoredFrames)
        }

        private fun loadExercises(context: Context): List<ExerciseItem> = try {
            context.assets.open("exercises.json").use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val type = object : TypeToken<List<ExerciseRecord>>() {}.type
                    val records: List<ExerciseRecord> = Gson().fromJson(reader, type) ?: emptyList()
                    val mapped = records.mapNotNull { ExerciseItem.from(it) }
                        .sortedWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
                    Log.d(TAG, "parsed=${records.size} mapped=${mapped.size}")
                    mapped
                }
            }
        } catch (t: Throwable) {
            // Never swallow this silently — an empty Workouts library shipped once
            // because this catch left no trace.
            Log.e(TAG, "failed to load exercises.json", t)
            emptyList()
        }

        private fun loadAuthoredFrames(context: Context): Map<String, GenderedExerciseFrames> {
            val json = runCatching {
                context.assets.open(ExerciseVisualResolver.MANIFEST_ASSET_NAME)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }
                .onFailure { Log.e(TAG, "failed to load exercise visual manifest", it) }
                .getOrNull()
                ?: return emptyMap()
            // packagedAssetNames=null: skip AssetManager.list; trust the packaging manifest.
            return ExerciseVisualResolver.parseManifest(json)
        }

        /** Asset URI for a bundled exercise image filename (Coil-loadable). */
        fun imageAssetUri(filename: String): String = "file:///android_asset/$filename"
    }
}
