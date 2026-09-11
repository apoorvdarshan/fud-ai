package com.apoorvdarshan.calorietracker.ui.workouts

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.data.ExerciseItem
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.data.ExerciseSort
import com.apoorvdarshan.calorietracker.data.WorkoutRepository
import com.apoorvdarshan.calorietracker.models.Gender
import com.apoorvdarshan.calorietracker.models.ExerciseTimerAction
import com.apoorvdarshan.calorietracker.models.WorkoutIntensity
import com.apoorvdarshan.calorietracker.models.ExerciseLiftDay
import com.apoorvdarshan.calorietracker.models.ExerciseLiftHistory
import com.apoorvdarshan.calorietracker.models.PlannedExercise
import com.apoorvdarshan.calorietracker.models.WorkoutDate
import com.apoorvdarshan.calorietracker.models.WorkoutPersistedState
import com.apoorvdarshan.calorietracker.models.WorkoutPreferences
import com.apoorvdarshan.calorietracker.models.WorkoutSplitGroup
import com.apoorvdarshan.calorietracker.models.WorkoutTabMode
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

data class WorkoutCopyDayUi(
    val date: LocalDate,
    val exerciseNames: List<String>
)

internal data class WorkoutPickerFilterState(
    val search: String = "",
    val primaryMuscle: String? = null,
    val secondaryMuscle: String? = null,
    val equipment: String? = null,
    val level: String? = null,
    val force: String? = null,
    val mechanic: String? = null,
    val category: String? = null,
    val sort: ExerciseSort = ExerciseSort.NAME
)

data class WorkoutDiaryUiState(
    val mode: WorkoutTabMode = WorkoutTabMode.Default,
    val selectedDate: LocalDate = LocalDate.now(),
    val exercises: List<PlannedExercise> = emptyList(),
    val workoutCounts: Map<LocalDate, Int> = emptyMap(),
    val caloriesBurned: Int? = null,
    val savedExerciseIds: Set<String> = emptySet(),
    val preferences: WorkoutPreferences = WorkoutPreferences(),
    val splitGroups: List<WorkoutSplitGroup> = emptyList(),
    val copyDays: List<WorkoutCopyDayUi> = emptyList(),
    val weightUnit: WorkoutWeightUnit = WorkoutWeightUnit.LBS,
    val visualGender: Gender = Gender.MALE,
    val isCalculatingBurn: Boolean = false,
    val notice: String? = null
) {
    val performedSetCount: Int
        get() = exercises.sumOf { exercise -> exercise.sets.count { it.reps.isNotBlank() } }

    val repCount: Int
        get() = exercises.sumOf { exercise -> exercise.sets.sumOf { it.reps.toIntOrNull() ?: 0 } }
}

/**
 * Holds the Workouts library filter/sort/search state, mirroring the iOS browser.
 * Persisted to SharedPreferences (the analog of iOS's ExerciseFilterStateStore) so
 * it survives process death, not just tab switches.
 */
class WorkoutsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("fudai_workouts", Context.MODE_PRIVATE)
    private val exerciseRepository = ExerciseRepository.get(app)
    private var workoutRepository: WorkoutRepository? = null
    private var repositoryJob: Job? = null
    private var latestPersistedState = WorkoutPersistedState()
    private var bodyWeightKg = 70.0
    private var workoutWeightUnit = WorkoutWeightUnit.LBS
    private var profileGender = Gender.MALE

    var diaryUiState by mutableStateOf(WorkoutDiaryUiState())
        private set

    private val _search = mutableStateOf(prefs.getString(K_SEARCH, "") ?: "")
    var search: String
        get() = _search.value
        set(v) { _search.value = v; prefs.edit().putString(K_SEARCH, v).apply() }

    private val _levels = mutableStateOf(loadSet(K_LEVELS))
    var levels: Set<String>
        get() = _levels.value
        set(v) { _levels.value = v; saveSet(K_LEVELS, v) }

    private val _equipment = mutableStateOf(loadSet(K_EQUIPMENT))
    var equipment: Set<String>
        get() = _equipment.value
        set(v) { _equipment.value = v; saveSet(K_EQUIPMENT, v) }

    private val _primary = mutableStateOf(loadSet(K_PRIMARY))
    var primaryMuscles: Set<String>
        get() = _primary.value
        set(v) { _primary.value = v; saveSet(K_PRIMARY, v) }

    private val _splitGroups = mutableStateOf(loadSet(K_SPLIT_GROUPS))
    var splitGroupTitles: Set<String>
        get() = _splitGroups.value
        set(v) {
            _splitGroups.value = v
            saveSet(K_SPLIT_GROUPS, v)
            if (v.isNotEmpty()) {
                val selectedMuscles = diaryUiState.splitGroups
                    .filter { it.title in v }
                    .flatMapTo(mutableSetOf()) { it.muscles }
                val hidePrimary = diaryUiState.preferences.split == com.apoorvdarshan.calorietracker.models.WorkoutSplit.FULL_BODY
                val normalizedPrimary = if (hidePrimary) emptySet() else primaryMuscles.intersect(selectedMuscles)
                if (normalizedPrimary != primaryMuscles) primaryMuscles = normalizedPrimary
            }
        }

    private val _secondary = mutableStateOf(loadSet(K_SECONDARY))
    var secondaryMuscles: Set<String>
        get() = _secondary.value
        set(v) { _secondary.value = v; saveSet(K_SECONDARY, v) }

    private val _forces = mutableStateOf(loadSet(K_FORCES))
    var forces: Set<String>
        get() = _forces.value
        set(v) { _forces.value = v; saveSet(K_FORCES, v) }

    private val _mechanics = mutableStateOf(loadSet(K_MECHANICS))
    var mechanics: Set<String>
        get() = _mechanics.value
        set(v) { _mechanics.value = v; saveSet(K_MECHANICS, v) }

    private val _categories = mutableStateOf(loadSet(K_CATEGORIES))
    var categories: Set<String>
        get() = _categories.value
        set(v) { _categories.value = v; saveSet(K_CATEGORIES, v) }

    private val _sort = mutableStateOf(
        runCatching { ExerciseSort.valueOf(prefs.getString(K_SORT, "") ?: "") }.getOrDefault(ExerciseSort.NAME)
    )
    var sort: ExerciseSort
        get() = _sort.value
        set(v) { _sort.value = v; prefs.edit().putString(K_SORT, v.name).apply() }

    /** Currently open exercise (by id), or null for the list. Not persisted. */
    var openExerciseId by mutableStateOf<String?>(null)

    /** Diary plans snapshot their exercise metadata, so detail still works after dataset changes. */
    var openExerciseSnapshot by mutableStateOf<ExerciseItem?>(null)

    /**
     * Binds the persistent diary without making the legacy library browser depend on DI.
     * Navigation can pass null while wiring is in progress; the final app always supplies the
     * AppContainer repository and therefore persists the default Log mode and every edit.
     */
    fun bindWorkoutRepository(
        repository: WorkoutRepository?,
        currentBodyWeightKg: Double,
        weightUnit: WorkoutWeightUnit,
        profileGender: Gender
    ) {
        bodyWeightKg = currentBodyWeightKg.takeIf { it.isFinite() && it > 0.0 } ?: 70.0
        workoutWeightUnit = weightUnit
        this.profileGender = profileGender
        if (workoutRepository === repository && repositoryJob != null) {
            rebuildDiaryState()
            return
        }

        workoutRepository = repository
        repositoryJob?.cancel()
        if (repository == null) {
            diaryUiState = diaryUiState.copy(weightUnit = weightUnit, visualGender = profileGender)
            return
        }
        repositoryJob = viewModelScope.launch {
            repository.state.collectLatest { persisted ->
                latestPersistedState = persisted
                rebuildDiaryState()
            }
        }
    }

    fun setMode(mode: WorkoutTabMode) {
        if (diaryUiState.mode == mode) return
        diaryUiState = diaryUiState.copy(mode = mode)
        viewModelScope.launch { workoutRepository?.setMode(mode) }
    }

    fun selectDate(date: LocalDate) {
        if (date == diaryUiState.selectedDate) return
        diaryUiState = diaryUiState.copy(selectedDate = date)
        rebuildDiaryState()
    }

    fun moveDate(days: Long) {
        val proposed = diaryUiState.selectedDate.plusDays(days)
        if (days > 0 && proposed.isAfter(LocalDate.now())) return
        selectDate(proposed)
    }

    fun toggleExercise(item: ExerciseItem) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.toggleExercise(item, date) }
    }

    fun removeExercise(exerciseId: UUID) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.removeExercise(exerciseId, date) }
    }

    fun setSetCount(exerciseId: UUID, count: Int) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.setSetCount(count, exerciseId, date) }
    }

    fun updateTimer(exerciseId: UUID, action: ExerciseTimerAction) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.updateTimer(exerciseId, date, action) }
    }

    fun setTimerIntensity(exerciseId: UUID, intensity: WorkoutIntensity) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.setTimerIntensity(exerciseId, date, intensity) }
    }

    fun updateWeight(exerciseId: UUID, setId: UUID, value: String) {
        updateSet(exerciseId, setId, weight = value)
    }

    fun updateReps(exerciseId: UUID, setId: UUID, value: String) {
        updateSet(exerciseId, setId, reps = value)
    }

    fun updateRpe(exerciseId: UUID, setId: UUID, value: String) {
        updateSet(exerciseId, setId, rpe = value)
    }

    private fun updateSet(
        exerciseId: UUID,
        setId: UUID,
        weight: String? = null,
        reps: String? = null,
        rpe: String? = null
    ) {
        val date = diaryUiState.selectedDate
        viewModelScope.launch {
            workoutRepository?.updateSet(
                exerciseId = exerciseId,
                setId = setId,
                date = date,
                weight = weight,
                weightUnit = if (weight != null) workoutWeightUnit else null,
                reps = reps,
                rpe = rpe
            )
        }
    }

    fun toggleSaved(itemId: String) {
        viewModelScope.launch { workoutRepository?.toggleSaved(itemId) }
    }

    internal fun pickerSource(): WorkoutPickerSource = runCatching {
        WorkoutPickerSource.valueOf(prefs.getString(K_PICKER_SOURCE, "") ?: "")
    }.getOrDefault(WorkoutPickerSource.DATASET)

    internal fun setPickerSource(source: WorkoutPickerSource) {
        prefs.edit().putString(K_PICKER_SOURCE, source.name).apply()
    }

    internal fun pickerFilter(contextId: String): WorkoutPickerFilterState {
        val prefix = "$K_PICKER_FILTER_PREFIX$contextId."
        return WorkoutPickerFilterState(
            search = prefs.getString("${prefix}search", "").orEmpty(),
            primaryMuscle = prefs.getString("${prefix}primary", null),
            secondaryMuscle = prefs.getString("${prefix}secondary", null),
            equipment = prefs.getString("${prefix}equipment", null),
            level = prefs.getString("${prefix}level", null),
            force = prefs.getString("${prefix}force", null),
            mechanic = prefs.getString("${prefix}mechanic", null),
            category = prefs.getString("${prefix}category", null),
            sort = runCatching {
                ExerciseSort.valueOf(prefs.getString("${prefix}sort", "") ?: "")
            }.getOrDefault(ExerciseSort.NAME)
        )
    }

    internal fun setPickerFilter(contextId: String, state: WorkoutPickerFilterState) {
        val prefix = "$K_PICKER_FILTER_PREFIX$contextId."
        prefs.edit().apply {
            putString("${prefix}search", state.search)
            putString("${prefix}primary", state.primaryMuscle)
            putString("${prefix}secondary", state.secondaryMuscle)
            putString("${prefix}equipment", state.equipment)
            putString("${prefix}level", state.level)
            putString("${prefix}force", state.force)
            putString("${prefix}mechanic", state.mechanic)
            putString("${prefix}category", state.category)
            putString("${prefix}sort", state.sort.name)
        }.apply()
    }

    fun copyPlan(sourceDate: LocalDate, includeSetDetails: Boolean = false) {
        val targetDate = diaryUiState.selectedDate
        viewModelScope.launch { workoutRepository?.copyPlan(sourceDate, targetDate, includeSetDetails) }
    }

    fun calculateBurn() {
        if (diaryUiState.isCalculatingBurn) return
        if (diaryUiState.exercises.none { it.hasCalculableWork }) {
            diaryUiState = diaryUiState.copy(
                notice = "Stop and save an exercise timer, or enter reps for at least one set, before calculating workout calories."
            )
            return
        }
        val repository = workoutRepository ?: return
        val date = diaryUiState.selectedDate
        diaryUiState = diaryUiState.copy(isCalculatingBurn = true)
        viewModelScope.launch {
            // Keep the state readable instead of flashing between two frames.
            delay(450)
            val saved = repository.calculateBurn(
                date = date,
                bodyWeightKg = bodyWeightKg,
                weightUnit = workoutWeightUnit
            )
            diaryUiState = diaryUiState.copy(
                isCalculatingBurn = false,
                notice = if (saved == null) {
                    "Stop and save an exercise timer, or enter reps for at least one set, before calculating workout calories."
                } else null
            )
        }
    }

    fun dismissNotice() {
        diaryUiState = diaryUiState.copy(notice = null)
    }

    fun lastExerciseLiftSummary(itemId: String, name: String): String? =
        ExerciseLiftHistory.lastSummary(
            latestPersistedState,
            itemId,
            name,
            WorkoutDate.key(diaryUiState.selectedDate),
            workoutWeightUnit
        )

    fun exerciseLiftHistory(itemId: String, name: String): List<ExerciseLiftDay> =
        ExerciseLiftHistory.history(
            latestPersistedState,
            itemId,
            name,
            WorkoutDate.key(diaryUiState.selectedDate)
        )

    fun openDiaryExercise(exercise: PlannedExercise) {
        openExerciseSnapshot = exercise.asExerciseItem()
        openExerciseId = exercise.itemId
    }

    fun closeExerciseDetail() {
        openExerciseSnapshot = null
        openExerciseId = null
    }

    private fun rebuildDiaryState() {
        val date = diaryUiState.selectedDate
        val dateKey = WorkoutDate.key(date)
        val exercises = latestPersistedState.dayPlans[dateKey]?.exercises.orEmpty()
        val burn = latestPersistedState.completedSessions
            .asSequence()
            .filter { it.diaryDateKey == dateKey && it.caloriesBurned != null }
            .maxWithOrNull(compareBy({ it.healthSyncVersion ?: 0 }, { it.completedAt }))
            ?.caloriesBurned
        val counts = latestPersistedState.dayPlans.mapNotNull { (key, plan) ->
            WorkoutDate.parse(key)?.let { it to plan.exercises.size }
        }.toMap()
        val copyDays = latestPersistedState.dayPlans.values
            .asSequence()
            .filter { it.exercises.isNotEmpty() && it.dateKey < dateKey }
            .sortedByDescending { it.dateKey }
            .mapNotNull { plan ->
                WorkoutDate.parse(plan.dateKey)?.let { planDate ->
                    WorkoutCopyDayUi(planDate, plan.exercises.map(PlannedExercise::name))
                }
            }
            .toList()
        val preferences = latestPersistedState.preferences
        val splitGroups = WorkoutSplitGroup.selectionGroups(
            split = preferences.split,
            availablePrimaryMuscles = exerciseRepository.availablePrimaryMuscles,
            availableSecondaryMuscles = exerciseRepository.availableSecondaryMuscles
        )
        val storedSplit = prefs.getString(K_SPLIT_IDENTIFIER, null)
        if (storedSplit != preferences.split.name) {
            splitGroupTitles = emptySet()
            prefs.edit().putString(K_SPLIT_IDENTIFIER, preferences.split.name).apply()
        } else {
            val validTitles = splitGroups.mapTo(mutableSetOf()) { it.title }
            val normalized = splitGroupTitles.intersect(validTitles)
            if (normalized != splitGroupTitles) splitGroupTitles = normalized
        }
        diaryUiState = diaryUiState.copy(
            mode = latestPersistedState.mode,
            exercises = exercises,
            workoutCounts = counts,
            caloriesBurned = burn,
            savedExerciseIds = latestPersistedState.savedExerciseIds,
            preferences = preferences,
            splitGroups = splitGroups,
            copyDays = copyDays,
            weightUnit = workoutWeightUnit,
            visualGender = profileGender
        )
    }

    val hasActiveFilters: Boolean
        get() = search.isNotEmpty() || splitGroupTitles.isNotEmpty() || levels.isNotEmpty() || equipment.isNotEmpty() ||
            primaryMuscles.isNotEmpty() || secondaryMuscles.isNotEmpty() || forces.isNotEmpty() ||
            mechanics.isNotEmpty() || categories.isNotEmpty() || sort != ExerciseSort.NAME

    fun reset() {
        search = ""
        splitGroupTitles = emptySet()
        levels = emptySet()
        equipment = emptySet()
        primaryMuscles = emptySet()
        secondaryMuscles = emptySet()
        forces = emptySet()
        mechanics = emptySet()
        categories = emptySet()
        sort = ExerciseSort.NAME
    }

    private fun loadSet(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    private fun saveSet(key: String, v: Set<String>) { prefs.edit().putStringSet(key, v).apply() }

    private companion object {
        const val K_SEARCH = "search"
        const val K_LEVELS = "levels"
        const val K_EQUIPMENT = "equipment"
        const val K_PRIMARY = "primary"
        const val K_SPLIT_GROUPS = "split_groups"
        const val K_SPLIT_IDENTIFIER = "split_identifier"
        const val K_SECONDARY = "secondary"
        const val K_FORCES = "forces"
        const val K_MECHANICS = "mechanics"
        const val K_CATEGORIES = "categories"
        const val K_SORT = "sort"
        const val K_PICKER_SOURCE = "picker.source"
        const val K_PICKER_FILTER_PREFIX = "picker.filter."
    }
}
