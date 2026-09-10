package com.apoorvdarshan.calorietracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.HomeTopNutrient
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import com.apoorvdarshan.calorietracker.models.PendingFoodAnalysisDraft
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.services.OpenFoodFactsService
import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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
    val weightMetric: Boolean = true,
    val favoriteKeys: Set<String> = emptySet(),
    val waterTrackingEnabled: Boolean = false,
    val waterDailyGoalMl: Int = 2_000,
    val waterUnit: WaterUnit = WaterUnit.Default,
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
    val analyzing: Boolean = false,
    val foodSaveInProgress: Boolean = false,
    val foodLoggingBlocked: Boolean = false,
    val fastingOverlap: Boolean = false,
    val error: String? = null,
    /** When true, the error dialog's primary action opens the food camera instead of retrying. */
    val errorOffersScanLabel: Boolean = false,
    /** Daily step total from Health Connect for [date]; null when health is off, unreadable, or loading. */
    val dailySteps: Int? = null
) {
    val caloriesToday: Int get() = todayEntries.sumOf { it.calories }
    val proteinToday: Double get() = todayEntries.sumOf { it.protein }
    val carbsToday: Double get() = todayEntries.sumOf { it.carbs }
    val fatToday: Double get() = todayEntries.sumOf { it.fat }
    val pendingImageBytesList: List<ByteArray>
        get() = listOfNotNull(pendingImageBytes) + pendingAdditionalImageBytes
    val pendingDraftImageFilenames: List<String>
        get() = listOfNotNull(pendingDraftImageFilename) + pendingDraftAdditionalImageFilenames
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

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    private var retryAction: (() -> Unit)? = null
    private val foodSubmissionGate = FoodSubmissionGate()

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
            .onEach { _ui.value = it }
            .launchIn(viewModelScope)

        container.prefs.homeTopNutrients
            .onEach { raw ->
                _ui.value = _ui.value.copy(homeTopNutrients = HomeTopNutrient.fromStorage(raw))
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
                _selectedDate
            ) { enabled, date -> enabled to date }
                .distinctUntilChanged()
                .collect { (enabled, date) ->
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

    fun analyzeText(description: String) {
        retryAction = { analyzeText(description) }
        viewModelScope.launch {
            val previousDraftImages = _ui.value.pendingDraftImageFilenames
            container.analyzingFood.value = true
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
            discardPendingDraft(previousDraftImages)
            try {
                val analysis = container.foodAnalysis.analyzeText(description)
                savePendingDraft(analysis, imageBytes = null, source = FoodSource.TEXT_INPUT)
            } catch (e: AiError) {
                _ui.value = _ui.value.copy(analyzing = false, error = e.userMessage(container.appContext), errorOffersScanLabel = false)
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(analyzing = false, error = container.appContext.getString(R.string.ai_error_generic), errorOffersScanLabel = false)
            } finally {
                container.analyzingFood.value = false
            }
        }
    }

    fun analyzePhoto(bytes: ByteArray) {
        retryAction = { analyzePhoto(bytes) }
        viewModelScope.launch {
            val previousDraftImages = _ui.value.pendingDraftImageFilenames
            container.analyzingFood.value = true
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
            discardPendingDraft(previousDraftImages)
            try {
                val analysis = container.foodAnalysis.analyzeAuto(bytes)
                savePendingDraft(analysis, imageBytes = bytes, source = FoodSource.SNAP_FOOD)
            } catch (e: AiError) {
                _ui.value = _ui.value.copy(analyzing = false, error = e.userMessage(container.appContext), errorOffersScanLabel = false)
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(analyzing = false, error = container.appContext.getString(R.string.ai_error_generic), errorOffersScanLabel = false)
            } finally {
                container.analyzingFood.value = false
            }
        }
    }

    fun analyzePhotos(
        imageBytesList: List<ByteArray>,
        note: String? = null,
        progressiveMeal: Boolean = false
    ) {
        val retryImages = imageBytesList.toList()
        retryAction = { analyzePhotos(retryImages, note, progressiveMeal) }
        viewModelScope.launch {
            val images = imageBytesList.filter { it.isNotEmpty() }.take(10)
            if (images.isEmpty()) return@launch
            val previousDraftImages = _ui.value.pendingDraftImageFilenames
            container.analyzingFood.value = true
            _ui.value = _ui.value.copy(
                analyzing = true,
                error = null,
                errorOffersScanLabel = false,
                pendingAnalysis = null,
                pendingImageBytes = images.first(),
                pendingAdditionalImageBytes = images.drop(1),
                pendingFoodSource = FoodSource.SNAP_FOOD,
                pendingDraftImageFilename = null,
                pendingDraftAdditionalImageFilenames = emptyList(),
                pendingReviewSource = null
            )
            discardPendingDraft(previousDraftImages)
            try {
                val analysis = container.foodAnalysis.analyzeFood(
                    images,
                    note?.takeIf { it.isNotBlank() },
                    progressiveMeal
                ).copy(customNote = note?.takeIf { it.isNotBlank() })
                savePendingDraft(analysis, imageBytesList = images, source = FoodSource.SNAP_FOOD)
            } catch (e: AiError) {
                _ui.value = _ui.value.copy(analyzing = false, error = e.userMessage(container.appContext), errorOffersScanLabel = false)
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(analyzing = false, error = container.appContext.getString(R.string.ai_error_generic), errorOffersScanLabel = false)
            } finally {
                container.analyzingFood.value = false
            }
        }
    }

    fun lookupBarcode(barcode: String) {
        retryAction = { lookupBarcode(barcode) }
        viewModelScope.launch {
            val previousDraftImages = _ui.value.pendingDraftImageFilenames
            container.analyzingFood.value = true
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
            discardPendingDraft(previousDraftImages)
            try {
                val lookup = OpenFoodFactsService.lookupWithImage(barcode)
                savePendingDraft(
                    analysis = lookup.analysis,
                    imageBytes = lookup.productImageBytes,
                    source = FoodSource.BARCODE
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: OpenFoodFactsService.LookupException) {
                _ui.value = _ui.value.copy(
                    analyzing = false,
                    error = barcodeLookupErrorMessage(error),
                    errorOffersScanLabel = error.failure == OpenFoodFactsService.LookupFailure.MISSING_NUTRITION ||
                        error.failure == OpenFoodFactsService.LookupFailure.PRODUCT_NOT_FOUND
                )
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(
                    analyzing = false,
                    error = container.appContext.getString(R.string.error_barcode_lookup_failed),
                    errorOffersScanLabel = false
                )
            } finally {
                container.analyzingFood.value = false
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
        editedAnalysis: FoodAnalysis? = null
    ) {
        val pendingAnalysis = _ui.value.pendingAnalysis ?: return
        val analysis = editedAnalysis ?: pendingAnalysis
        if (!foodSubmissionGate.tryBegin()) return
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
                    else -> imageBytesList.mapIndexedNotNull { index, bytes ->
                        container.imageStore.storeBytes(bytes, if (index == 0) id else UUID.randomUUID())
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
                    timestamp = timestampForSelectedDay(),
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
        return container.foodAnalysis.suggestMealWhatIf(
            entry = entry,
            dayEntries = snapshot.todayEntries,
            profile = profile,
            weightMetric = snapshot.weightMetric
        )
    }

    fun dismissPending() {
        retryAction = null
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
            discardPendingDraft(previousDraftImages)
        }
    }

    fun retryPendingAnalysis() {
        val action = retryAction ?: return
        _ui.value = _ui.value.copy(error = null, errorOffersScanLabel = false)
        action()
    }

    /**
     * Tap a row in Saved Meals (Recents / Frequent / Favorites) → open the
     * FoodResultSheet for review instead of logging immediately. The user
     * can edit name / serving / meal type, then tap "Log" to commit. Mirrors
     * iOS RecentsView's `onReview` callback path.
     */
    fun reviewSavedMeal(template: FoodEntry) {
        val analysis = template.toAnalysis()
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

    suspend fun lookupIngredientBarcode(barcode: String): FoodAnalysis =
        OpenFoodFactsService.lookupWithImage(barcode).analysis

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
     */
    private fun timestampForSelectedDay(): Instant {
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
        source: FoodSource
    ) {
        retryAction = null
        val imageFilenames = imageBytesList.mapNotNull { container.imageStore.storeBytes(it, UUID.randomUUID()) }
        val imageFilename = imageFilenames.firstOrNull()
        val additionalImageFilenames = imageFilenames.drop(1)
        container.prefs.setPendingFoodAnalysisDraft(
            PendingFoodAnalysisDraft(
                analysis = analysis,
                imageFilename = imageFilename,
                additionalImageFilenames = additionalImageFilenames,
                source = source
            )
        )
        _ui.value = _ui.value.copy(
            analyzing = false,
            pendingAnalysis = analysis,
            pendingImageBytes = imageBytesList.firstOrNull(),
            pendingAdditionalImageBytes = imageBytesList.drop(1),
            pendingFoodSource = source,
            pendingDraftImageFilename = imageFilename,
            pendingDraftAdditionalImageFilenames = additionalImageFilenames,
            pendingReviewSource = null
        )
    }

    private fun restorePendingDraft(draft: PendingFoodAnalysisDraft) {
        val bytesList = (listOfNotNull(draft.imageFilename) + draft.additionalImageFilenames).mapNotNull {
            runCatching { container.imageStore.file(it).readBytes() }.getOrNull()
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
            error = null
        )
    }

    private suspend fun discardPendingDraft(imageFilenames: List<String> = _ui.value.pendingDraftImageFilenames) {
        val filenames = imageFilenames.ifEmpty {
            container.prefs.pendingFoodAnalysisDraft.first()?.let {
                listOfNotNull(it.imageFilename) + it.additionalImageFilenames
            }.orEmpty()
        }
        container.prefs.setPendingFoodAnalysisDraft(null)
        filenames.forEach { container.imageStore.delete(it) }
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

/**
 * Map a logged FoodEntry back into a FoodAnalysis so the FoodResultSheet
 * (which only knows how to render a FoodAnalysis) can review a saved meal
 * before re-logging. A missing original mass is represented as one logged
 * serving, never as a fabricated 100g amount.
 */
private fun FoodEntry.toAnalysis(): FoodAnalysis = FoodAnalysis(
    name = name,
    calories = calories,
    protein = protein,
    carbs = carbs,
    fat = fat,
    servingSizeGrams = reviewServingReference,
    emoji = emoji,
    sugar = sugar,
    addedSugar = addedSugar,
    fiber = fiber,
    saturatedFat = saturatedFat,
    monounsaturatedFat = monounsaturatedFat,
    polyunsaturatedFat = polyunsaturatedFat,
    cholesterol = cholesterol,
    caffeine = caffeine,
    supplementalNutrients = supplementalNutrients,
    sodium = sodium,
    potassium = potassium,
    transFat = transFat,
    calcium = calcium,
    iron = iron,
    magnesium = magnesium,
    zinc = zinc,
    vitaminA = vitaminA,
    vitaminC = vitaminC,
    vitaminD = vitaminD,
    vitaminB12 = vitaminB12,
    vitaminE = vitaminE,
    vitaminK = vitaminK,
    folate = folate,
    omega3 = omega3,
    servingUnitOptions = reviewServingUnitOptions,
    selectedServingUnit = reviewSelectedServingUnit,
    selectedServingQuantity = reviewSelectedServingQuantity,
    servingSizeIsKnown = hasKnownServingSize,
    customNote = customNote,
    progressiveMeal = progressiveMeal,
    ingredients = ingredients,
    productMetadata = productMetadata
)
