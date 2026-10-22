package com.apoorvdarshan.calorietracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.AnalysisDurationEstimate
import com.apoorvdarshan.calorietracker.models.BarcodeLabelMemory
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.LabelScanBarcodeHandoff
import com.apoorvdarshan.calorietracker.models.RecipeServings
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.AddMenuConfig
import com.apoorvdarshan.calorietracker.models.HomeTopNutrient
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import com.apoorvdarshan.calorietracker.models.PendingFoodAnalysisDraft
import com.apoorvdarshan.calorietracker.models.toReviewAnalysis
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WaterQuickAddPresets
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.services.BarcodeProductLookup
import com.apoorvdarshan.calorietracker.services.CalorieBalanceDirection
import com.apoorvdarshan.calorietracker.services.DailySummaryPolicy
import com.apoorvdarshan.calorietracker.services.FoodDataCentralService
import com.apoorvdarshan.calorietracker.services.OpenFoodFactsService
import com.apoorvdarshan.calorietracker.ui.components.autoSaveMealPhotoIfEnabled
import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.ai.AnalysisKind
import com.apoorvdarshan.calorietracker.services.ai.AnalysisOutcome
import com.apoorvdarshan.calorietracker.services.ai.AnalysisProgressReporter
import com.apoorvdarshan.calorietracker.services.ai.AnalysisProgressRun
import com.apoorvdarshan.calorietracker.services.ai.AnalysisProgressState
import com.apoorvdarshan.calorietracker.services.ai.AnalysisProgressTracker
import com.apoorvdarshan.calorietracker.services.ai.AnalysisStage
import com.apoorvdarshan.calorietracker.services.ai.AnalysisProgressUpdate
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class HomeBurnSummary(
    val burnedCalories: Int,
    val direction: CalorieBalanceDirection,
    val differenceCalories: Int
)

enum class FoodLogSortOrder(val storageValue: String, val displayName: String, val displayNameRes: Int) {
    STANDARD("standard", "Breakfast → Lunch → Dinner", R.string.sort_standard),
    LATEST_MEALS_FIRST("latestMealsFirst", "Latest Meals First", R.string.sort_latest_first);

    companion object {
        fun fromStorage(value: String?): FoodLogSortOrder =
            values().firstOrNull { it.storageValue == value } ?: STANDARD
    }
}

data class HomeUiState(
    val date: LocalDate = LocalDate.now(),
    val profile: UserProfile? = null,
    val todayEntries: List<FoodEntry> = emptyList(),
    val homeTopNutrients: List<HomeTopNutrient> = HomeTopNutrient.DefaultSelection,
    val optionalNutrientGoals: OptionalNutrientGoals = OptionalNutrientGoals.Default,
    val foodLogSortOrder: FoodLogSortOrder = FoodLogSortOrder.STANDARD,
    val preferGramsByDefault: Boolean = false,
    /** Opt-in amino acid estimates (#491): shows their rows in nutrition views. */
    val aminoAcidEstimatesEnabled: Boolean = false,
    val weightMetric: Boolean = true,
    val favoriteKeys: Set<String> = emptySet(),
    val waterTrackingEnabled: Boolean = false,
    val waterDailyGoalMl: Int = 2_000,
    val waterUnit: WaterUnit = WaterUnit.Default,
    val waterQuickAddPresetsMl: List<Int> = WaterQuickAddPresets.Defaults,
    val waterTodayMl: Int = 0,
    val waterEntriesToday: List<WaterEntry> = emptyList(),
    val fastingTrackingEnabled: Boolean = false,
    val fastingDefaultGoalMinutes: Int = 16 * 60,
    val fastingSessions: List<FastingSession> = emptyList(),
    val pendingAnalysis: FoodAnalysis? = null,
    val pendingImageBytes: ByteArray? = null,
    val pendingAdditionalImageBytes: List<ByteArray> = emptyList(),
    val pendingFoodSource: FoodSource? = null,
    val pendingDraftImageFilename: String? = null,
    val pendingDraftAdditionalImageFilenames: List<String> = emptyList(),
    /**
     * Set when the pendingAnalysis came from a Saved Meals tap (Recents /
     * Frequent / Favorites) instead of a fresh AI analysis. We keep the
     * original entry so saveAnalysis can reuse its imageFilename instead of
     * re-storing the image bytes as a new file on disk.
     */
    val pendingReviewSource: FoodEntry? = null,
    /**
     * The analysis rebuilt from the user's saved nutrition label when Open Food
     * Facts could not use a barcode (#485). The review shows its note only while
     * this exact object is the pending analysis.
     */
    val savedLabelAnalysis: FoodAnalysis? = null,
    val analyzing: Boolean = false,
    /**
     * Progress of the running analysis (stage, provider, elapsed time). Null when no
     * analysis is running. The overlay renders it; it never shows a fake percentage.
     */
    val analysisProgress: AnalysisProgressState? = null,
    val foodSaveInProgress: Boolean = false,
    val foodLoggingBlocked: Boolean = false,
    val fastingOverlap: Boolean = false,
    val error: String? = null,
    /** When true, the error dialog's primary action opens the food camera instead of retrying. */
    val errorOffersScanLabel: Boolean = false,
/** Daily step total from Health Connect for [date]; null when health is off, unreadable, or loading. */
    val dailySteps: Int? = null,
    val homeBurnSummary: HomeBurnSummary? = null,
    val addMenuConfig: AddMenuConfig = AddMenuConfig.Default
) {
    val caloriesToday: Int get() = todayEntries.sumOf { it.calories }
    val proteinToday: Double get() = todayEntries.sumOf { it.protein }
    val carbsToday: Double get() = todayEntries.sumOf { it.carbs }
    val fatToday: Double get() = todayEntries.sumOf { it.fat }
    val pendingImageBytesList: List<ByteArray>
        get() = listOfNotNull(pendingImageBytes) + pendingAdditionalImageBytes
    val pendingDraftImageFilenames: List<String>
        get() = listOfNotNull(pendingDraftImageFilename) + pendingDraftAdditionalImageFilenames
    val pendingUsesSavedBarcodeLabel: Boolean
        get() = pendingAnalysis != null && pendingAnalysis === savedLabelAnalysis
    /**
     * The review re-logs a logged entry (Saved Meals or a saved barcode label) that
     * already holds one portion of a split recipe, so it hides "Servings in recipe" (#458).
     */
    val pendingReviewIsLoggedPortion: Boolean
        get() = when {
            pendingReviewSource != null -> RecipeServings.isLoggedPortion(pendingReviewSource.servingUnitOptions)
            pendingUsesSavedBarcodeLabel -> RecipeServings.isLoggedPortion(pendingAnalysis?.servingUnitOptions.orEmpty())
            else -> false
        }
    val activeFast: FastingSession? get() = fastingSessions.lastOrNull { it.isActive }
    fun isFavorite(entry: FoodEntry): Boolean = entry.favoriteKey in favoriteKeys
}

internal class FoodSubmissionGate {
    private val active = AtomicBoolean(false)

    fun tryBegin(): Boolean = active.compareAndSet(false, true)

    fun finish() {
        active.set(false)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()
    // App-wide, so the voice assistant sees and sets the same diary day. A new Home still
    // opens on today, even when the process (and the navigator) outlived the last one.
    private val _selectedDate = container.navigator.homeDiaryDate.apply { value = LocalDate.now() }
private val _stepsRefreshEpoch = MutableStateFlow(0)
    private val _burnRefreshTick = MutableStateFlow(0)
    private var retryAction: (() -> Unit)? = null
    /** Barcode carried from the "Scan label" prompt into the next label photo (#485). */
    private val labelScanBarcode = LabelScanBarcodeHandoff()
    /** The barcode whose error dialog currently offers "Scan label". */
    private var scanLabelOfferBarcode: String? = null
    /** The in-flight photo/text/barcode analysis behind the analyzing overlay, so Cancel can abort it. */
    private var analysisJob: Job? = null
    /** Progress of the running analysis, so the overlay can show stage and elapsed time. */
    private val analysisProgressTracker = AnalysisProgressTracker()
    /** The run of the analysis that is tracked right now. */
    private var analysisProgressRun: AnalysisProgressRun? = null
    /** Bumped on each analysis launch so a delayed cancel cleanup cannot wipe a newer draft. */
    private var analysisGeneration: Long = 0L
    /** Invalidates an in-flight savePendingDraft if the user dismisses/logs before it finishes. */
    private var draftSaveGeneration: Long = 0L
    private val foodSubmissionGate = FoodSubmissionGate()
    private var thumbnailPrefetchJob: Job? = null
    /** Draft-photo warm-up; canceled before discard so it cannot recreate deleted thumbnails. */
    private var draftThumbnailJob: Job? = null
    private var lastPrefetchedFilenames: Set<String>? = null

    /** Re-read Health Connect energy after resume or other external invalidation. */
    fun bumpBurnRefresh() {
        _burnRefreshTick.value += 1
    }

    init {
        combine(
            container.profileRepository.profile,
            container.foodRepository.entries,
            container.foodRepository.favoriteKeys,
            container.prefs.foodLogSortOrder,
            _selectedDate
        ) { p, entries, favKeys, sortOrder, day ->
            val zone = ZoneId.systemDefault()
            val dayEntries = entries
                .filter { it.timestamp.atZone(zone).toLocalDate() == day }
                .sortedByDescending { it.timestamp }
            _ui.value.copy(
                profile = p,
                date = day,
                todayEntries = dayEntries,
                foodLogSortOrder = FoodLogSortOrder.fromStorage(sortOrder),
                favoriteKeys = favKeys
            )
        }
            .onEach { state ->
                _ui.value = state
                val filenames = state.todayEntries
                    .flatMap { it.allImageFilenames }
                    .filter { it.isNotBlank() }
                    .toSet()
                if (filenames == lastPrefetchedFilenames) return@onEach
                lastPrefetchedFilenames = filenames
                thumbnailPrefetchJob?.cancel()
                thumbnailPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
                    container.imageStore.warmThumbnails(filenames)
                }
            }
            .launchIn(viewModelScope)

        container.prefs.homeTopNutrients
            .onEach { raw ->
                _ui.value = _ui.value.copy(homeTopNutrients = HomeTopNutrient.fromStorage(raw))
            }
            .launchIn(viewModelScope)

        container.prefs.addMenuConfig
            .onEach { config ->
                _ui.value = _ui.value.copy(addMenuConfig = config)
            }
            .launchIn(viewModelScope)

        container.prefs.optionalNutrientGoals
            .onEach { goals ->
                _ui.value = _ui.value.copy(optionalNutrientGoals = goals)
            }
            .launchIn(viewModelScope)

        container.prefs.preferGramsByDefault
            .onEach { preferGrams ->
                _ui.value = _ui.value.copy(preferGramsByDefault = preferGrams)
            }
            .launchIn(viewModelScope)

        container.prefs.aminoAcidEstimatesEnabled
            .onEach { enabled -> _ui.value = _ui.value.copy(aminoAcidEstimatesEnabled = enabled) }
            .launchIn(viewModelScope)

        container.prefs.weightUnit
            .onEach { unit ->
                _ui.value = _ui.value.copy(weightMetric = unit == "kg")
            }
            .launchIn(viewModelScope)

        container.prefs.waterTrackingEnabled
            .onEach { enabled -> _ui.value = _ui.value.copy(waterTrackingEnabled = enabled) }
            .launchIn(viewModelScope)

        container.prefs.waterDailyGoalMl
            .onEach { goal -> _ui.value = _ui.value.copy(waterDailyGoalMl = goal) }
            .launchIn(viewModelScope)

        container.prefs.waterUnit
            .onEach { unit -> _ui.value = _ui.value.copy(waterUnit = unit) }
            .launchIn(viewModelScope)

        container.prefs.waterQuickAddPresetsMl
            .onEach { presets -> _ui.value = _ui.value.copy(waterQuickAddPresetsMl = presets) }
            .launchIn(viewModelScope)

        container.prefs.fastingTrackingEnabled
            .onEach { enabled -> _ui.value = _ui.value.copy(fastingTrackingEnabled = enabled) }
            .launchIn(viewModelScope)

        container.prefs.fastingDefaultGoalMinutes
            .onEach { goal -> _ui.value = _ui.value.copy(fastingDefaultGoalMinutes = goal) }
            .launchIn(viewModelScope)

        container.fastingRepository.sessions
            .onEach { sessions -> _ui.value = _ui.value.copy(fastingSessions = sessions) }
            .launchIn(viewModelScope)

        combine(container.waterRepository.entries, _selectedDate) { entries, day ->
            val zone = ZoneId.systemDefault()
            val dailyEntries = entries
                .filter { it.date.atZone(zone).toLocalDate() == day }
                .sortedByDescending { it.date }
            dailyEntries to dailyEntries.sumOf { it.milliliters }
        }
            .onEach { (entries, total) ->
                _ui.value = _ui.value.copy(
                    waterTodayMl = total,
                    waterEntriesToday = entries
                )
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            container.prefs.pendingFoodAnalysisDraft.first()?.let { restorePendingDraft(it) }
        }

viewModelScope.launch {
            combine(
                container.prefs.healthConnectEnabled,
                _selectedDate,
                _stepsRefreshEpoch
            ) { enabled, date, epoch -> Triple(enabled, date, epoch) }
                .distinctUntilChanged()
                .collect { (enabled, date, _) ->
                    if (!enabled || !container.health.hasStepsRead()) {
                        _ui.value = _ui.value.copy(dailySteps = null)
                        return@collect
                    }
                    val steps = container.health.readStepsForDay(date)
                    if (_selectedDate.value == date) {
                        _ui.value = _ui.value.copy(dailySteps = steps)
                    }
                }
        }

        combine(
            container.prefs.healthConnectEnabled,
            _selectedDate,
            container.foodRepository.entries,
            container.profileRepository.profile,
            _burnRefreshTick
        ) { healthEnabled, day, entries, profile, _ ->
            BurnRefreshInputs(healthEnabled, day, entries, profile)
        }
            .flatMapLatest { inputs ->
                flow { emit(computeHomeBurnSummary(inputs)) }
            }
            .onEach { summary ->
                _ui.value = _ui.value.copy(homeBurnSummary = summary)
            }
            .launchIn(viewModelScope)
    }

    fun refreshDailySteps() {
        _stepsRefreshEpoch.value += 1
    }

    private data class BurnRefreshInputs(
        val healthEnabled: Boolean,
        val day: LocalDate,
        val entries: List<FoodEntry>,
        val profile: UserProfile?
    )

    private suspend fun computeHomeBurnSummary(inputs: BurnRefreshInputs): HomeBurnSummary? {
        if (!inputs.healthEnabled) return null
        val zone = ZoneId.systemDefault()
        val eaten = inputs.entries
            .filter { it.timestamp.atZone(zone).toLocalDate() == inputs.day }
            .sumOf { it.calories }
        val energy = container.health.readEnergyForDay(inputs.day) ?: return null
        val burned = DailySummaryPolicy.resolveBurnedCalories(
            measuredTotalCalories = energy.totalCalories,
            externalActiveCalories = energy.activeCalories,
            profileBmrCalories = inputs.profile?.bmr?.roundToInt()
        ) ?: return null
        val balance = DailySummaryPolicy.balance(
            eatenCalories = eaten,
            burnedCalories = burned
        )
        return HomeBurnSummary(
            burnedCalories = balance.burnedCalories,
            direction = balance.direction,
            differenceCalories = balance.differenceCalories
        )
    }

    fun setSelectedDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun addWater(milliliters: Int) {
        if (milliliters <= 0) return
        viewModelScope.launch {
            container.waterRepository.add(
                WaterEntry(date = timestampForSelectedDay(), milliliters = milliliters)
            )
        }
    }

    fun deleteWater(id: UUID) {
        viewModelScope.launch {
            container.waterRepository.delete(id)
        }
    }

    fun startFast(goalMinutes: Int) {
        viewModelScope.launch {
            container.fastingRepository.start(goalMinutes)
            syncFastingNotification()
        }
    }

    fun endFast(updatedSession: FastingSession? = null) {
        viewModelScope.launch {
            val ended = container.fastingRepository.endActive(updatedSession = updatedSession)
            if (ended != null) {
                container.notifications.cancelFastingGoal()
            } else {
                _ui.value = _ui.value.copy(fastingOverlap = true)
            }
        }
    }

    fun cancelFast() {
        viewModelScope.launch {
            container.fastingRepository.cancelActive()
            container.notifications.cancelFastingGoal()
        }
    }

    fun updateFast(session: FastingSession) {
        viewModelScope.launch {
            if (container.fastingRepository.update(session)) {
                syncFastingNotification()
            } else {
                _ui.value = _ui.value.copy(fastingOverlap = true)
            }
        }
    }

    fun deleteFast(id: UUID) {
        viewModelScope.launch {
            container.fastingRepository.delete(id)
            syncFastingNotification()
        }
    }

    private suspend fun syncFastingNotification() {
        val shouldNotify = container.prefs.notificationsEnabled.first() &&
            container.prefs.fastingTrackingEnabled.first() &&
            container.prefs.fastingGoalNotificationEnabled.first() &&
            container.notifications.canPostNotifications()
        if (shouldNotify) {
            container.notifications.scheduleFastingGoal(container.fastingRepository.active())
        } else {
            container.notifications.cancelFastingGoal()
        }
    }

    fun reportFoodBlockedByFast() {
        _ui.value = _ui.value.copy(foodLoggingBlocked = true)
    }

    fun dismissFoodBlocked() {
        _ui.value = _ui.value.copy(foodLoggingBlocked = false)
    }

    fun dismissFastingOverlap() {
        _ui.value = _ui.value.copy(fastingOverlap = false)
    }

    fun setFoodLogSortOrder(order: FoodLogSortOrder) {
        viewModelScope.launch {
            container.prefs.setFoodLogSortOrder(order.storageValue)
        }
    }

    fun setHomeTopNutrients(selection: List<HomeTopNutrient>) {
        viewModelScope.launch {
            container.prefs.setHomeTopNutrients(HomeTopNutrient.toStorage(selection))
        }
    }

    /** Hosted AI quota / no-plan errors open the paywall flow instead of an error dialog. */
    private fun showAnalysisError(e: AiError) {
        val routed = container.hostedUi.handle(e)
        _ui.value = _ui.value.copy(
            analyzing = false,
            error = if (routed) null else e.userMessage(container.appContext),
            errorOffersScanLabel = false
        )
    }

    fun analyzeText(description: String) {
        retryAction = { analyzeText(description) }
        labelScanBarcode.clear()
        analysisJob = launchAnalysis { _ ->
            val previousDraftImages = _ui.value.pendingDraftImageFilenames
            container.analyzingFood.value = true
            val run = startAnalysisProgress(AnalysisKind.TEXT)
            val reporter = progressReporter(run)
            _ui.value = _ui.value.copy(
                analyzing = true,
                error = null,
                errorOffersScanLabel = false,
                pendingAnalysis = null,
                pendingImageBytes = null,
                pendingAdditionalImageBytes = emptyList(),
                pendingFoodSource = FoodSource.TEXT_INPUT,
                pendingDraftImageFilename = null,
                pendingDraftAdditionalImageFilenames = emptyList(),
                pendingReviewSource = null
            )
            discardPendingDraftSafely(previousDraftImages)
            try {
                val analysis = container.foodAnalysis.analyzeText(description, progress = reporter)
                finishAnalysisProgress(AnalysisOutcome.COMPLETE, run)
                savePendingDraft(analysis, imageBytes = null, source = FoodSource.TEXT_INPUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiError) {
                ensureActive()
                finishAnalysisProgress(AnalysisOutcome.FAILED, run)
                showAnalysisError(e)
            } catch (e: Throwable) {
                ensureActive()
                finishAnalysisProgress(AnalysisOutcome.FAILED, run)
                _ui.value = _ui.value.copy(analyzing = false, error = container.appContext.getString(R.string.ai_error_generic), errorOffersScanLabel = false)
            }
        }
    }

    fun analyzePhoto(bytes: ByteArray) {
        retryAction = { analyzePhoto(bytes) }
        labelScanBarcode.clear()
        analysisJob = launchAnalysis { _ ->
            val previousDraftImages = _ui.value.pendingDraftImageFilenames
            container.analyzingFood.value = true
            val run = startAnalysisProgress(AnalysisKind.IMAGE)
            val reporter = progressReporter(run)
            _ui.value = _ui.value.copy(
                analyzing = true,
                error = null,
                errorOffersScanLabel = false,
                pendingAnalysis = null,
                pendingImageBytes = bytes,
                pendingAdditionalImageBytes = emptyList(),
                pendingFoodSource = FoodSource.SNAP_FOOD,
                pendingDraftImageFilename = null,
                pendingDraftAdditionalImageFilenames = emptyList(),
                pendingReviewSource = null
            )
            discardPendingDraftSafely(previousDraftImages)
            try {
                val analysis = container.foodAnalysis.analyzeAuto(bytes, progress = reporter)
                finishAnalysisProgress(AnalysisOutcome.COMPLETE, run)
                savePendingDraft(analysis, imageBytes = bytes, source = FoodSource.SNAP_FOOD)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiError) {
                ensureActive()
                finishAnalysisProgress(AnalysisOutcome.FAILED, run)
                showAnalysisError(e)
            } catch (e: Throwable) {
                ensureActive()
                finishAnalysisProgress(AnalysisOutcome.FAILED, run)
                _ui.value = _ui.value.copy(analyzing = false, error = container.appContext.getString(R.string.ai_error_generic), errorOffersScanLabel = false)
            }
        }
    }

    fun analyzePhotos(
        imageBytesList: List<ByteArray>,
        note: String? = null,
        progressiveMeal: Boolean = false
    ) {
        // A label photo taken from the barcode prompt keeps that barcode (#485).
        runPhotoAnalysis(imageBytesList, note, progressiveMeal, labelScanBarcode.take())
    }

    private fun runPhotoAnalysis(
        imageBytesList: List<ByteArray>,
        note: String?,
        progressiveMeal: Boolean,
        labelBarcode: String?
    ) {
        val retryImages = imageBytesList.toList()
        retryAction = { runPhotoAnalysis(retryImages, note, progressiveMeal, labelBarcode) }
        val source = if (labelBarcode != null) FoodSource.NUTRITION_LABEL else FoodSource.SNAP_FOOD
        analysisJob = launchAnalysis { _ ->
            val images = imageBytesList.filter { it.isNotEmpty() }.take(10)
            if (images.isEmpty()) return@launchAnalysis
            val previousDraftImages = _ui.value.pendingDraftImageFilenames
            container.analyzingFood.value = true
            val run = startAnalysisProgress(AnalysisKind.IMAGE)
            val reporter = progressReporter(run)
            _ui.value = _ui.value.copy(
                analyzing = true,
                error = null,
                errorOffersScanLabel = false,
                pendingAnalysis = null,
                pendingImageBytes = images.first(),
                pendingAdditionalImageBytes = images.drop(1),
                pendingFoodSource = source,
                pendingDraftImageFilename = null,
                pendingDraftAdditionalImageFilenames = emptyList(),
                pendingReviewSource = null
            )
            discardPendingDraftSafely(previousDraftImages)
            try {
                val analyzed = container.foodAnalysis.analyzeFood(
                    images,
                    note?.takeIf { it.isNotBlank() },
                    progressiveMeal,
                    progress = reporter
                ).copy(customNote = note?.takeIf { it.isNotBlank() })
                val analysis = if (labelBarcode != null) {
                    analyzed.copy(productMetadata = BarcodeLabelMemory.labelMetadata(analyzed.productMetadata, labelBarcode))
                } else {
                    analyzed
                }
                finishAnalysisProgress(AnalysisOutcome.COMPLETE, run)
                savePendingDraft(analysis, imageBytesList = images, source = source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiError) {
                ensureActive()
                finishAnalysisProgress(AnalysisOutcome.FAILED, run)
                showAnalysisError(e)
            } catch (e: Throwable) {
                ensureActive()
                finishAnalysisProgress(AnalysisOutcome.FAILED, run)
                _ui.value = _ui.value.copy(analyzing = false, error = container.appContext.getString(R.string.ai_error_generic), errorOffersScanLabel = false)
            }
        }
    }

    fun lookupBarcode(barcode: String) {
        retryAction = { lookupBarcode(barcode) }
        labelScanBarcode.clear()
        scanLabelOfferBarcode = null
        analysisJob = launchAnalysis { _ ->
            val previousDraftImages = _ui.value.pendingDraftImageFilenames
            container.analyzingFood.value = true
            val run = startAnalysisProgress(AnalysisKind.BARCODE)
            val reporter = progressReporter(run)
            _ui.value = _ui.value.copy(
                analyzing = true,
                error = null,
                errorOffersScanLabel = false,
                pendingAnalysis = null,
                pendingImageBytes = null,
                pendingAdditionalImageBytes = emptyList(),
                pendingFoodSource = FoodSource.BARCODE,
                pendingDraftImageFilename = null,
                pendingDraftAdditionalImageFilenames = emptyList(),
                pendingReviewSource = null
            )
            discardPendingDraftSafely(previousDraftImages)
            try {
                // Open Food Facts wins when it has nutrition; otherwise reuse the label the
                // user scanned for this barcode before (#485), then USDA (#533).
                reporter.onProgress(AnalysisProgressUpdate(AnalysisStage.REQUESTING))
                val outcome = BarcodeProductLookup.lookup(
                    barcode = barcode,
                    usdaApiKey = container.keyStore.usdaFoodDataCentralApiKey(),
                    savedLabelEntries = { container.foodRepository.entries.first() }
                )
                ensureActive()
                when (outcome) {
                    is BarcodeProductLookup.Outcome.Product -> {
                        finishAnalysisProgress(AnalysisOutcome.COMPLETE, run)
                        savePendingDraft(
                            analysis = outcome.result.analysis,
                            imageBytes = outcome.result.productImageBytes,
                            source = FoodSource.BARCODE
                        )
                    }
                    is BarcodeProductLookup.Outcome.SavedLabel -> {
                        val analysis = outcome.analysis
                        _ui.value = _ui.value.copy(savedLabelAnalysis = analysis)
                        finishAnalysisProgress(AnalysisOutcome.COMPLETE, run)
                        savePendingDraft(analysis, source = FoodSource.NUTRITION_LABEL, usesSavedBarcodeLabel = true)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: OpenFoodFactsService.LookupException) {
                ensureActive()
                val offersScanLabel = error.failure == OpenFoodFactsService.LookupFailure.MISSING_NUTRITION ||
                    error.failure == OpenFoodFactsService.LookupFailure.PRODUCT_NOT_FOUND
                scanLabelOfferBarcode = barcode.takeIf { offersScanLabel }
                finishAnalysisProgress(AnalysisOutcome.FAILED, run)
                _ui.value = _ui.value.copy(
                    analyzing = false,
                    error = barcodeLookupErrorMessage(error),
                    errorOffersScanLabel = offersScanLabel
                )
            } catch (error: FoodDataCentralService.LookupException) {
                ensureActive()
                // A refused USDA key only follows an Open Food Facts miss, so the label scan still helps.
                scanLabelOfferBarcode = barcode
                finishAnalysisProgress(AnalysisOutcome.FAILED, run)
                _ui.value = _ui.value.copy(
                    analyzing = false,
                    error = container.appContext.getString(R.string.error_barcode_usda_key_rejected),
                    errorOffersScanLabel = true
                )
            } catch (e: Throwable) {
                ensureActive()
                finishAnalysisProgress(AnalysisOutcome.FAILED, run)
                _ui.value = _ui.value.copy(
                    analyzing = false,
                    error = container.appContext.getString(R.string.error_barcode_lookup_failed),
                    errorOffersScanLabel = false
                )
            }
        }
    }

    private fun barcodeLookupErrorMessage(error: OpenFoodFactsService.LookupException): String {
        val messageRes = when (error.failure) {
            OpenFoodFactsService.LookupFailure.INVALID_BARCODE -> R.string.error_barcode_invalid
            OpenFoodFactsService.LookupFailure.PRODUCT_NOT_FOUND -> R.string.error_barcode_product_not_found
            OpenFoodFactsService.LookupFailure.MISSING_NUTRITION -> R.string.error_barcode_missing_nutrition
            OpenFoodFactsService.LookupFailure.RATE_LIMITED -> R.string.error_barcode_rate_limited
            OpenFoodFactsService.LookupFailure.SERVICE_UNAVAILABLE -> R.string.error_barcode_service_unavailable
            OpenFoodFactsService.LookupFailure.UNEXPECTED_RESPONSE -> R.string.error_barcode_unexpected_response
            OpenFoodFactsService.LookupFailure.OFFLINE -> R.string.error_barcode_offline
            OpenFoodFactsService.LookupFailure.TIMEOUT -> R.string.error_barcode_timeout
            OpenFoodFactsService.LookupFailure.NETWORK -> R.string.error_barcode_network
        }
        return container.appContext.getString(messageRes)
    }

    fun saveAnalysis(
        name: String? = null,
        servingGrams: Double? = null,
        servingSizeIsKnown: Boolean = true,
        scale: Double = 1.0,
        mealType: MealType = MealType.currentMeal,
        selectedServingUnit: String? = null,
        selectedServingQuantity: Double? = null,
        editedAnalysis: FoodAnalysis? = null,
        timestamp: Instant? = null
    ) {
        val pendingAnalysis = _ui.value.pendingAnalysis ?: return
        val analysis = editedAnalysis ?: pendingAnalysis
        if (!foodSubmissionGate.tryBegin()) return
        draftSaveGeneration += 1
        _ui.value = _ui.value.copy(foodSaveInProgress = true)
        val reviewSource = _ui.value.pendingReviewSource
        val pendingFoodSource = _ui.value.pendingFoodSource
        val pendingDraftImageFilenames = _ui.value.pendingDraftImageFilenames
        viewModelScope.launch {
            try {
                val imageBytesList = _ui.value.pendingImageBytesList
                val id = UUID.randomUUID()
                // If this analysis came from a Saved Meals review, reuse the
                // template's existing on-disk image so we don't duplicate the
                // JPEG. Otherwise (fresh AI analysis), persist the in-memory
                // bytes as a new file under the new entry id.
                val filenames = when {
                    reviewSource != null -> reviewSource.allImageFilenames
                    pendingDraftImageFilenames.isNotEmpty() -> pendingDraftImageFilenames
                    // Large photos are downscaled while stored, so keep that off the main thread.
                    else -> withContext(Dispatchers.IO) {
                        imageBytesList.mapIndexedNotNull { index, bytes ->
                            container.imageStore.storeBytes(bytes, if (index == 0) id else UUID.randomUUID())
                        }
                    }
                }
                fun s(v: Int) = (v * scale).roundToInt()
                fun macro(v: Double) = v * scale
                fun s(v: Double?) = v?.let { it * scale }
                val entry = FoodEntry(
                    id = id,
                    name = name?.takeIf { it.isNotBlank() } ?: analysis.name,
                    calories = s(analysis.calories),
                    protein = macro(analysis.protein),
                    carbs = macro(analysis.carbs),
                    fat = macro(analysis.fat),
                    timestamp = timestamp ?: timestampForSelectedDay(),
                    imageFilename = filenames.firstOrNull(),
                    additionalImageFilenames = filenames.drop(1),
                    emoji = analysis.emoji,
                    source = reviewSource?.source
                        ?: pendingFoodSource
                        ?: if (imageBytesList.isNotEmpty()) FoodSource.SNAP_FOOD else FoodSource.TEXT_INPUT,
                    mealType = mealType,
                    sugar = s(analysis.sugar),
                    addedSugar = s(analysis.addedSugar),
                    fiber = s(analysis.fiber),
                    saturatedFat = s(analysis.saturatedFat),
                    monounsaturatedFat = s(analysis.monounsaturatedFat),
                    polyunsaturatedFat = s(analysis.polyunsaturatedFat),
                    cholesterol = s(analysis.cholesterol),
                    caffeine = s(analysis.caffeine),
                    supplementalNutrients = analysis.supplementalNutrients.mapValues { (_, value) -> s(value) ?: 0.0 },
                    sodium = s(analysis.sodium),
                    potassium = s(analysis.potassium),
                    transFat = s(analysis.transFat),
                    calcium = s(analysis.calcium),
                    iron = s(analysis.iron),
                    magnesium = s(analysis.magnesium),
                    zinc = s(analysis.zinc),
                    vitaminA = s(analysis.vitaminA),
                    vitaminC = s(analysis.vitaminC),
                    vitaminD = s(analysis.vitaminD),
                    vitaminB12 = s(analysis.vitaminB12),
                    vitaminE = s(analysis.vitaminE),
                    vitaminK = s(analysis.vitaminK),
                    folate = s(analysis.folate),
                    omega3 = s(analysis.omega3),
                    servingSizeGrams = if (servingSizeIsKnown) {
                        servingGrams ?: analysis.servingSizeGrams
                    } else {
                        null
                    },
                    servingUnitOptions = if (servingSizeIsKnown) analysis.servingUnitOptions else emptyList(),
                    selectedServingUnit = if (servingSizeIsKnown) {
                        if (analysis.servingUnitOptions.isEmpty()) null else selectedServingUnit
                    } else {
                        "serving"
                    },
                    selectedServingQuantity = if (servingSizeIsKnown) {
                        if (analysis.servingUnitOptions.isEmpty()) null else selectedServingQuantity
                    } else {
                        selectedServingQuantity
                    },
                    customNote = analysis.customNote,
                    progressiveMeal = analysis.progressiveMeal,
                    ingredients = analysis.ingredients.map { it.scaled(scale) },
                    productMetadata = analysis.productMetadata
                )
                if (!container.foodRepository.addEntry(entry)) {
                    reportFoodBlockedByFast()
                    return@launch
                }
                if (reviewSource == null && filenames.isNotEmpty()) {
                    filenames.forEach { filename ->
                        container.imageStore.loadBytes(filename)?.let { bytes ->
                            autoSaveMealPhotoIfEnabled(container.appContext, bytes)
                        }
                    }
                }
                container.prefs.setPendingFoodAnalysisDraft(null)
                _ui.value = _ui.value.copy(
                    pendingAnalysis = null,
                    pendingImageBytes = null,
                    pendingAdditionalImageBytes = emptyList(),
                    pendingFoodSource = null,
                    pendingDraftImageFilename = null,
                    pendingDraftAdditionalImageFilenames = emptyList(),
                    pendingReviewSource = null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _ui.value = _ui.value.copy(
                    error = error.localizedMessage ?: container.appContext.getString(R.string.error_analysis_failed),
                    errorOffersScanLabel = false
                )
            } finally {
                foodSubmissionGate.finish()
                _ui.value = _ui.value.copy(foodSaveInProgress = false)
            }
        }
    }

    suspend fun suggestMealWhatIf(entry: FoodEntry): String {
        val snapshot = _ui.value
        val profile = snapshot.profile
            ?: return container.appContext.getString(R.string.finish_onboarding_hint)
        val zone = ZoneId.systemDefault()
        val day = entry.timestamp.atZone(zone).toLocalDate()
        val dayEntries = container.foodRepository.entries.first()
            .filter { it.timestamp.atZone(zone).toLocalDate() == day }
            .sortedByDescending { it.timestamp }
        return container.foodAnalysis.suggestMealWhatIf(
            entry = entry,
            dayEntries = dayEntries,
            profile = profile,
            weightMetric = snapshot.weightMetric
        )
    }

    fun dismissPending() {
        retryAction = null
        labelScanBarcode.clear()
        scanLabelOfferBarcode = null
        draftSaveGeneration += 1
        val previousDraftImages = _ui.value.pendingDraftImageFilenames
        _ui.value = _ui.value.copy(
            pendingAnalysis = null,
            pendingImageBytes = null,
            pendingAdditionalImageBytes = emptyList(),
            pendingFoodSource = null,
            pendingDraftImageFilename = null,
            pendingDraftAdditionalImageFilenames = emptyList(),
            pendingReviewSource = null,
            error = null,
            errorOffersScanLabel = false
        )
        viewModelScope.launch {
            discardPendingDraftSafely(previousDraftImages)
        }
    }

    /**
     * "Scan label" on the barcode error: close it and carry that barcode into the
     * label photo, so the logged entry can be reused next time (#485).
     */
    fun startLabelScanForFailedBarcode() {
        val barcode = scanLabelOfferBarcode
        dismissPending()
        labelScanBarcode.arm(barcode)
    }

    /** Any other way out of a label scan (camera closed, sheet dismissed, another log method). */
    fun clearLabelScanBarcode() {
        labelScanBarcode.clear()
    }

    fun retryPendingAnalysis() {
        val action = retryAction ?: return
        _ui.value = _ui.value.copy(error = null, errorOffersScanLabel = false)
        action()
    }

    /**
     * Starts tracking an analysis and returns the run that identifies it.
     *
     * The reporter the analysis layer calls with its stage changes only publishes what
     * really happened — and only while its run is the one being tracked.
     */
    private fun startAnalysisProgress(kind: AnalysisKind): AnalysisProgressRun {
        val run = analysisProgressTracker.start(kind)
        analysisProgressRun = run
        _ui.value = _ui.value.copy(analysisProgress = run.initial)
        return run
    }

    /** The reporter the analysis layer publishes its stages through. */
    private fun progressReporter(run: AnalysisProgressRun): AnalysisProgressReporter =
        AnalysisProgressReporter { update: AnalysisProgressUpdate ->
            val next = analysisProgressTracker.onProgress(run.number, update) ?: return@AnalysisProgressReporter
            _ui.value = _ui.value.copy(analysisProgress = next)
        }

    /**
     * Ends the tracked analysis and clears the progress from the UI state.
     *
     * The duration of a successful meal analysis is recorded here, not in the service:
     * this is the time the user actually waited, including prompt and image work. Only
     * runs that finished on the primary attempt are recorded: a fallback run contains
     * the failed primary's wait, so its total would inflate the backup model's estimate.
     * The write is best-effort and runs in its own job, so it can never delay or cancel
     * the result. Runs without a known provider (hosted AI) and barcode lookups are skipped.
     */
    private fun finishAnalysisProgress(outcome: AnalysisOutcome, run: AnalysisProgressRun?) {
        if (run == null) return
        val previous = analysisProgressTracker.current
        // A newer run owns the progress state now; the old one must not clear it.
        val ended = analysisProgressTracker.finish(run.number, outcome) ?: return
        val durationMillis = analysisProgressTracker.elapsedMillis()
        val firstEnding = previous?.outcome == AnalysisOutcome.RUNNING
        analysisProgressTracker.reset()
        if (analysisProgressRun?.number == run.number) analysisProgressRun = null
        _ui.value = _ui.value.copy(analysisProgress = null)
        if (firstEnding && ended.canRecordDuration) {
            val provider = ended.provider
            val model = ended.model
            if (provider != null && !model.isNullOrBlank()) {
                val kind = if (ended.kind == AnalysisKind.IMAGE) {
                    AnalysisDurationEstimate.Kind.IMAGE
                } else {
                    AnalysisDurationEstimate.Kind.TEXT
                }
                viewModelScope.launch {
                    runCatching {
                        container.prefs.recordAnalysisDuration(provider, model, kind, durationMillis)
                    }
                }
            }
        }
    }

    private fun launchAnalysis(block: suspend CoroutineScope.(generation: Long) -> Unit): Job {
        analysisGeneration += 1
        draftSaveGeneration += 1
        // A second start (for example a fast double tap behind the overlay) must cancel
        // the older request: otherwise two paid AI calls run at once and the loser can
        // still write a draft after the newer analysis won.
        analysisJob?.cancel()
        val generation = analysisGeneration
        return viewModelScope.launch {
            try {
                block(generation)
            } finally {
                // A canceled job can unwind after a newer scan started — don't hide its overlay.
                if (analysisGeneration == generation) {
                    container.analyzingFood.value = false
                    // If the body stopped before its own error/success path (for example
                    // an empty image list), clear the overlay too instead of leaving it
                    // running forever.
                    if (_ui.value.analyzing || _ui.value.analysisProgress != null) {
                        _ui.value = _ui.value.copy(analyzing = false, analysisProgress = null)
                    }
                }
            }
        }
    }

    /**
     * Cancel button on the analyzing overlay. Cancels the coroutine (which also cancels the
     * underlying HTTP call), clears the overlay and any half-written pending draft, and drops
     * the retry action so no error dialog follows.
     */
    fun cancelAnalysis() {
        labelScanBarcode.clear()
        // The progress goes away with the overlay, also when the flag was already
        // cleared by a request that just finished.
        finishAnalysisProgress(AnalysisOutcome.CANCELLED, analysisProgressRun)
        if (!_ui.value.analyzing) return
        val job = analysisJob
        val canceledGeneration = analysisGeneration
        val draftImagesToDiscard = _ui.value.pendingDraftImageFilenames
        analysisJob = null
        retryAction = null
        draftSaveGeneration += 1
        job?.cancel()
        container.analyzingFood.value = false
        _ui.value = _ui.value.copy(
            analyzing = false,
            analysisProgress = null,
            error = null,
            errorOffersScanLabel = false,
            pendingAnalysis = null,
            pendingImageBytes = null,
            pendingAdditionalImageBytes = emptyList(),
            pendingFoodSource = null,
            pendingDraftImageFilename = null,
            pendingDraftAdditionalImageFilenames = emptyList()
        )
        viewModelScope.launch {
            job?.join()
            // Only wipe prefs/files for this canceled attempt. A newer analysis may have
            // already saved a draft after the user started another scan.
            if (analysisGeneration == canceledGeneration) {
                if (draftImagesToDiscard.isNotEmpty()) {
                    discardPendingDraft(draftImagesToDiscard)
                }
            }
        }
    }

    /**
     * Tap a row in Saved Meals (Recents / Frequent / Favorites) → open the
     * FoodResultSheet for review instead of logging immediately. The user
     * can edit name / serving / meal type, then tap "Log" to commit. Mirrors
     * iOS RecentsView's `onReview` callback path.
     */
    fun reviewSavedMeal(template: FoodEntry) {
        labelScanBarcode.clear()
        draftSaveGeneration += 1
        val analysis = template.toReviewAnalysis()
        val bytesList = template.allImageFilenames.mapNotNull {
            runCatching { container.imageStore.file(it).readBytes() }.getOrNull()
        }
        _ui.value = _ui.value.copy(
            pendingAnalysis = analysis,
            pendingImageBytes = bytesList.firstOrNull(),
            pendingAdditionalImageBytes = bytesList.drop(1),
            pendingFoodSource = template.source,
            pendingDraftImageFilename = null,
            pendingDraftAdditionalImageFilenames = emptyList(),
            pendingReviewSource = template,
            error = null
        )
    }

    fun deleteEntry(id: UUID) {
        viewModelScope.launch {
            container.foodRepository.deleteEntry(id)
        }
    }

    suspend fun analyzeIngredientText(description: String): FoodAnalysis =
        container.foodAnalysis.analyzeText(description)

    suspend fun analyzeIngredientImage(bytes: ByteArray): FoodAnalysis =
        container.foodAnalysis.analyzeAuto(bytes)

    /** Same order as the Home scan: Open Food Facts, the saved label (#485), then USDA (#533). */
    suspend fun lookupIngredientBarcode(barcode: String): FoodAnalysis =
        BarcodeProductLookup.ingredientAnalysis(
            keyRejectedMessage = container.appContext.getString(R.string.error_barcode_usda_key_rejected)
        ) {
            BarcodeProductLookup.lookup(
                barcode = barcode,
                usdaApiKey = container.keyStore.usdaFoodDataCentralApiKey(),
                savedLabelEntries = { container.foodRepository.entries.first() }
            )
        }

    fun combineIntoMeal(ids: Set<UUID>, onDone: (FoodEntry?) -> Unit = {}) {
        if (ids.size < 2) {
            onDone(null)
            return
        }
        viewModelScope.launch {
            val combined = container.foodRepository.combineIntoMeal(ids)
            if (combined == null) {
                reportFoodBlockedByFast()
            }
            onDone(combined)
        }
    }

    fun toggleFavorite(entry: FoodEntry) {
        viewModelScope.launch {
            container.foodRepository.toggleFavorite(entry)
        }
    }

    fun updateEntry(entry: FoodEntry) {
        viewModelScope.launch {
            container.foodRepository.updateEntry(entry)
        }
    }

    /** Re-log a saved meal (from Saved Meals sheet) as a new entry timestamped to the selected day. */
    fun relogMeal(template: FoodEntry) {
        viewModelScope.launch {
            if (!container.foodRepository.addEntry(template.duplicatedForLogging(timestampForSelectedDay()))) {
                reportFoodBlockedByFast()
            }
        }
    }

    fun copyEntriesToSelectedDay(entries: List<FoodEntry>) {
        if (entries.isEmpty()) return
        val copiedTimestamp = timestampForSelectedDay()
        viewModelScope.launch {
            entries.forEach { entry ->
                if (!container.foodRepository.addEntry(
                    entry.duplicatedForLogging(logDate = copiedTimestamp)
                )) {
                    reportFoodBlockedByFast()
                    return@launch
                }
            }
        }
    }

    /** Save a user-typed entry with no AI involvement (manual macro input from issue #15). */
    fun saveManualEntry(
        name: String,
        calories: Int,
        protein: Double,
        carbs: Double,
        fat: Double,
        fiber: Double?,
        mealType: MealType = MealType.currentMeal
    ) {
        viewModelScope.launch {
            if (!container.foodRepository.addEntry(
                FoodEntry(
                    name = name,
                    calories = calories,
                    protein = protein,
                    carbs = carbs,
                    fat = fat,
                    fiber = fiber,
                    timestamp = timestampForSelectedDay(),
                    source = FoodSource.MANUAL,
                    mealType = mealType
                )
            )) {
                reportFoodBlockedByFast()
            }
        }
    }

    /**
     * Mirrors iOS `logDate: selectedDate` behavior. When viewing today, returns now.
     * When viewing a past or future day, combines that day with the current wall-clock
     * time so the entry shows a sensible time and lands on the correct calendar day.
     * Also seeds the review sheet's editable Date & Time default.
     */
    fun timestampForSelectedDay(): Instant {
        val day = _selectedDate.value
        val today = LocalDate.now()
        if (day == today) return Instant.now()
        val zone = ZoneId.systemDefault()
        val nowTime = java.time.LocalTime.now()
        return day.atTime(nowTime).atZone(zone).toInstant()
    }

    private suspend fun savePendingDraft(
        analysis: FoodAnalysis,
        imageBytes: ByteArray? = null,
        imageBytesList: List<ByteArray> = imageBytes?.let(::listOf).orEmpty(),
        source: FoodSource,
        usesSavedBarcodeLabel: Boolean = false
    ) {
        retryAction = null
        draftSaveGeneration += 1
        val saveGeneration = draftSaveGeneration
        // Drop the analyzing overlay immediately — disk + DataStore must not keep the user waiting
        // after Gemini already returned (large first photos made this look like a permanent hang).
        _ui.value = _ui.value.copy(
            analyzing = false,
            analysisProgress = null,
            pendingAnalysis = analysis,
            pendingImageBytes = imageBytesList.firstOrNull(),
            pendingAdditionalImageBytes = imageBytesList.drop(1),
            pendingFoodSource = source,
            pendingDraftImageFilename = null,
            pendingDraftAdditionalImageFilenames = emptyList(),
            pendingReviewSource = null
        )
        val imageFilenames = withContext(Dispatchers.IO) {
            imageBytesList.mapNotNull {
                container.imageStore.storeBytes(it, UUID.randomUUID(), writeThumbnail = false)
            }
        }
        // User dismissed/logged while we were writing — drop orphans and do not recreate the draft.
        if (saveGeneration != draftSaveGeneration || _ui.value.pendingAnalysis !== analysis) {
            if (imageFilenames.isNotEmpty()) {
                withContext(Dispatchers.IO) { imageFilenames.forEach { container.imageStore.delete(it) } }
            }
            return
        }
        val imageFilename = imageFilenames.firstOrNull()
        val additionalImageFilenames = imageFilenames.drop(1)
        container.prefs.setPendingFoodAnalysisDraft(
            PendingFoodAnalysisDraft(
                analysis = analysis,
                imageFilename = imageFilename,
                additionalImageFilenames = additionalImageFilenames,
                source = source,
                usesSavedBarcodeLabel = usesSavedBarcodeLabel
            )
        )
        if (saveGeneration != draftSaveGeneration || _ui.value.pendingAnalysis !== analysis) {
            container.prefs.setPendingFoodAnalysisDraft(null)
            if (imageFilenames.isNotEmpty()) {
                withContext(Dispatchers.IO) { imageFilenames.forEach { container.imageStore.delete(it) } }
            }
            return
        }
        _ui.value = _ui.value.copy(
            pendingDraftImageFilename = imageFilename,
            pendingDraftAdditionalImageFilenames = additionalImageFilenames
        )
        if (imageFilenames.isNotEmpty()) {
            draftThumbnailJob?.cancel()
            draftThumbnailJob = viewModelScope.launch(Dispatchers.IO) {
                container.imageStore.warmThumbnails(imageFilenames)
            }
        }
    }

    private suspend fun restorePendingDraft(draft: PendingFoodAnalysisDraft) {
        val restoreDraftGeneration = draftSaveGeneration
        val restoreAnalysisGeneration = analysisGeneration
        val bytesList = withContext(Dispatchers.IO) {
            (listOfNotNull(draft.imageFilename) + draft.additionalImageFilenames).mapNotNull {
                runCatching { container.imageStore.file(it).readBytes() }.getOrNull()
            }
        }
        if (restoreDraftGeneration != draftSaveGeneration ||
            restoreAnalysisGeneration != analysisGeneration ||
            _ui.value.analyzing ||
            _ui.value.pendingAnalysis != null
        ) {
            return
        }
        _ui.value = _ui.value.copy(
            analyzing = false,
            pendingAnalysis = draft.analysis,
            pendingImageBytes = bytesList.firstOrNull(),
            pendingAdditionalImageBytes = bytesList.drop(1),
            pendingFoodSource = draft.source,
            pendingDraftImageFilename = draft.imageFilename,
            pendingDraftAdditionalImageFilenames = draft.additionalImageFilenames,
            pendingReviewSource = null,
            // Keeps the saved-label notice on a review restored after the app was closed (#485).
            savedLabelAnalysis = draft.savedLabelAnalysis,
            error = null
        )
    }

    /**
     * Cleanup before a new analysis. It is best-effort: a broken local draft store must
     * not crash the coroutine or leave the analyzing overlay hanging.
     */
    private suspend fun discardPendingDraftSafely(imageFilenames: List<String>) {
        try {
            discardPendingDraft(imageFilenames)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The old draft is only a convenience; the new analysis can still start.
        }
    }

    /**
     * Clears the in-progress draft. Prefer UI-known filenames; if the list is empty (restore
     * still in flight), fall back to one prefs read so draft JPEGs are not orphaned.
     */
    private suspend fun discardPendingDraft(imageFilenames: List<String>) {
        draftSaveGeneration += 1
        val warmJob = draftThumbnailJob
        draftThumbnailJob = null
        warmJob?.cancel()
        warmJob?.join()
        val toDelete = if (imageFilenames.isNotEmpty()) {
            imageFilenames
        } else {
            container.prefs.pendingFoodAnalysisDraft.first()?.let {
                listOfNotNull(it.imageFilename) + it.additionalImageFilenames
            }.orEmpty()
        }
        container.prefs.setPendingFoodAnalysisDraft(null)
        if (toDelete.isNotEmpty()) {
            withContext(Dispatchers.IO) { toDelete.forEach { container.imageStore.delete(it) } }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(container) as T
    }

    suspend fun reprocessFoodEntry(entry: FoodEntry, updatedNote: String): FoodAnalysis {
        val imageBytesList = entry.allImageFilenames.mapNotNull {
            runCatching { container.imageStore.file(it).readBytes() }.getOrNull()
        }
        // Compose name + serving + note so a photo-less (text / voice / emoji) entry
        // keeps its food context instead of re-analyzing the bare note; a photo entry
        // gets the name/note as extra grounding on top of the image.
        val description = reprocessDescription(entry, updatedNote)
        val result = if (imageBytesList.isNotEmpty()) {
            container.foodAnalysis.analyzeFood(
                imageBytesList,
                description.takeIf { it.isNotBlank() },
                entry.progressiveMeal
            )
        } else {
            container.foodAnalysis.analyzeText(description)
        }
        return result.copy(customNote = updatedNote.takeIf { it.isNotBlank() })
    }

    private fun reprocessDescription(entry: FoodEntry, note: String): String {
        val parts = mutableListOf<String>()
        entry.name.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        val qty = entry.selectedServingQuantity
        val unit = entry.selectedServingUnit?.trim()
        if (qty != null && qty > 0 && !unit.isNullOrEmpty()) {
            val q = if (qty % 1.0 == 0.0) qty.toInt().toString() else qty.toString()
            parts += "$q $unit"
        } else {
            entry.servingSizeGrams?.takeIf { it > 0 }?.let { parts += "${it.toInt()} g" }
        }
        val base = parts.joinToString(", ")
        val trimmed = note.trim()
        return when {
            base.isEmpty() -> trimmed
            trimmed.isEmpty() -> base
            else -> "$base. $trimmed"
        }
    }
}
