package com.apoorvdarshan.calorietracker.ui.settings

import com.apoorvdarshan.calorietracker.models.OpenRouterReasoningEffort
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.AutoBalanceMacro
import com.apoorvdarshan.calorietracker.models.CurrentMealSchedule
import com.apoorvdarshan.calorietracker.models.MealSchedule
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import com.apoorvdarshan.calorietracker.models.QuickAction
import com.apoorvdarshan.calorietracker.models.SpeechLanguage
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WeightGoal
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.models.WorkoutRpeScale
import com.apoorvdarshan.calorietracker.models.WorkoutSplit
import com.apoorvdarshan.calorietracker.services.AndroidAppIconManager
import com.apoorvdarshan.calorietracker.services.health.HealthConnectManager
import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelId
import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelState
import com.apoorvdarshan.calorietracker.ui.theme.AppThemeColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SettingsUiState(
    val selectedAI: AIProvider = AIProvider.GEMINI,
    val selectedModel: String = AIProvider.GEMINI.defaultModel,
    val separateTextProviderEnabled: Boolean = false,
    val selectedTextAI: AIProvider = AIProvider.GEMINI,
    val selectedTextModel: String = AIProvider.GEMINI.defaultTextModel,
    val textApiKeyMasked: String = "",
    val openRouterReasoningEffort: OpenRouterReasoningEffort = OpenRouterReasoningEffort.AUTO,
    val maxResponseTokens: Int = 1024,
    val aiRequestTimeoutSeconds: Int = AIProvider.DEFAULT_REQUEST_TIMEOUT_SECONDS,
    val selectedSpeech: SpeechProvider = SpeechProvider.NATIVE,
    val selectedSpeechLanguage: SpeechLanguage = SpeechLanguage.defaultFor(SpeechProvider.NATIVE),
    val speechFallbackEnabled: Boolean = false,
    val speechFallbackProvider: SpeechProvider = SpeechProvider.GROQ,
    val speechFallbackLanguage: SpeechLanguage = SpeechLanguage.defaultFor(SpeechProvider.GROQ),
    val speechFallbackApiKeyMasked: String = "",
    /** "cm" | "ftin" — governs all length display/input. */
    val heightUnit: String = "cm",
    /** "kg" | "lbs" — governs all mass display/input. */
    val weightUnit: String = "kg",
    val preferGramsByDefault: Boolean = false,
    val saveMealPhotosToGallery: Boolean = false,
    val profile: UserProfile? = null,
    val notificationsEnabled: Boolean = false,
    val streakReminderEnabled: Boolean = false,
    val dailySummaryEnabled: Boolean = false,
    val weightReminderEnabled: Boolean = true,
    val bodyFatReminderEnabled: Boolean = true,
    val goalReachedNotificationsEnabled: Boolean = true,
    val appUpdateNotificationsEnabled: Boolean = true,
    val waterTrackingEnabled: Boolean = false,
    val waterDailyGoalMl: Int = 2_000,
    val waterUnit: WaterUnit = WaterUnit.Default,
    val waterReminderEnabled: Boolean = false,
    val fastingTrackingEnabled: Boolean = false,
    val walkRunQuickLogEnabled: Boolean = false,
    val fastingDefaultGoalMinutes: Int = 16 * 60,
    val fastingGoalNotificationEnabled: Boolean = true,
    val healthConnectEnabled: Boolean = false,
    val workoutHealthWriteGranted: Boolean = false,
    val healthEnergyGoalsEnabled: Boolean = false,
    val adaptiveGoalsEnabled: Boolean = true,
    val applyingHealthEnergyGoals: Boolean = false,
    val applyingAdaptiveGoals: Boolean = false,
    val recalculatingGoals: Boolean = false,
    val healthEnergyGoalAlertTitle: String? = null,
    val healthEnergyGoalAlertMessage: String? = null,
    val adaptiveGoalAlertTitle: String? = null,
    val adaptiveGoalAlertMessage: String? = null,
    val apiKeyMasked: String = "",
    val speechApiKeyMasked: String = "",
    val appearanceMode: String = "system",
    val appThemeColor: AppThemeColor = AppThemeColor.FUD_PINK,
    val weekStartsOnMonday: Boolean = true,
    val mealSchedule: MealSchedule = MealSchedule.Default,
    val workoutSplit: WorkoutSplit = WorkoutSplit.FULL_BODY,
    val workoutRpeScale: WorkoutRpeScale = WorkoutRpeScale.STRENGTH,
    val userContext: String = "",
    val fallbackEnabled: Boolean = false,
    val fallbackProvider: AIProvider = AIProvider.GEMINI,
    val fallbackModel: String = AIProvider.GEMINI.defaultModel,
    val fallbackApiKeyMasked: String = "",
    val textFallbackEnabled: Boolean = false,
    val textFallbackProvider: AIProvider = AIProvider.GEMINI,
    val textFallbackModel: String = AIProvider.GEMINI.defaultTextModel,
    val textFallbackApiKeyMasked: String = "",
    val optionalNutrientGoals: OptionalNutrientGoals = OptionalNutrientGoals.Default,
    val quickActions: List<QuickAction> = QuickAction.Defaults,
    val localModelStates: Map<LocalModelId, LocalModelState> = emptyMap(),
    /** A goal-relevant input changed since the last Recalculate. Drives a soft nudge on the
     *  Recalculate row; the button stays tappable at all times — this never disables it. */
    val goalsNeedRecalc: Boolean = false
) {
    val heightMetric: Boolean get() = heightUnit == "cm"
    val weightMetric: Boolean get() = weightUnit == "kg"
    val availableVisionProviders: List<AIProvider>
        get() = AIProvider.remoteVisionProviders + listOfNotNull(
            AIProvider.LOCAL_GEMMA.takeIf { localModelStates[LocalModelId.GEMMA_4_E2B]?.executable == true }
        )
    val availableTextProviders: List<AIProvider>
        get() = AIProvider.remoteTextProviders + listOfNotNull(
            AIProvider.LOCAL_GEMMA.takeIf { localModelStates[LocalModelId.GEMMA_4_E2B]?.executable == true }
        )
    val availableSpeechProviders: List<SpeechProvider>
        get() = SpeechProvider.values().filter { it != SpeechProvider.LOCAL_WHISPER } + listOfNotNull(
            SpeechProvider.LOCAL_WHISPER.takeIf { localModelStates[LocalModelId.WHISPER_BASE]?.executable == true }
        )
    val availableSpeechFallbackProviders: List<SpeechProvider>
        get() = SpeechProvider.remoteProviders + listOfNotNull(
            SpeechProvider.LOCAL_WHISPER.takeIf { localModelStates[LocalModelId.WHISPER_BASE]?.executable == true }
        )
}

internal fun adaptiveCheckDayAfterManualRecalculation(
    adaptiveEnabled: Boolean,
    today: LocalDate
): String? = today.toString().takeIf { adaptiveEnabled }

class SettingsViewModel(val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    /** Goal-input fingerprint captured at the last Recalculate (or seeded on first load). */
    private var lastRecalcSignature: String? = null

    /** True when [profile]'s goal inputs differ from the last-recalculated baseline. */
    private fun needsRecalc(profile: com.apoorvdarshan.calorietracker.models.UserProfile?): Boolean =
        lastRecalcSignature != null && profile != null && lastRecalcSignature != profile.goalInputSignature

    init {
        viewModelScope.launch {
            container.localModels.states.collect { states ->
                _ui.value = _ui.value.copy(localModelStates = states)
            }
        }

        viewModelScope.launch {
            container.workoutRepository.preferences.collect { preferences ->
                _ui.value = _ui.value.copy(
                    workoutSplit = preferences.split,
                    workoutRpeScale = preferences.rpeScale
                )
            }
        }

        viewModelScope.launch {
            container.prefs.optionalNutrientGoals.collect { goals ->
                _ui.value = _ui.value.copy(optionalNutrientGoals = goals)
            }
        }

        // Keep the profile reactive just like Home/Progress. This also primes the two profile
        // sections immediately from DataStore instead of waiting behind Health Connect work.
        viewModelScope.launch {
            container.profileRepository.profile.collect { profile ->
                _ui.value = _ui.value.copy(
                    profile = profile,
                    goalsNeedRecalc = needsRecalc(profile)
                )
            }
        }

        viewModelScope.launch {
            // The app-level migration runs asynchronously. Await it here as well so
            // Settings can never snapshot the pre-migration Native value on a cold start.
            container.prefs.migrateMatchingSpeechProviderIfNeeded()
            val provider = container.prefs.selectedAIProvider.first()
            val model = provider.supportedModelOrDefault(container.prefs.selectedAIModel.first())
            val separateTextEnabled = container.prefs.separateTextProviderEnabled.first()
            val textProvider = container.prefs.selectedTextAIProvider.first()
            val textModel = textProvider.supportedTextModelOrDefault(container.prefs.selectedTextAIModel.first())
            val speech = container.prefs.selectedSpeechProvider.first()
            val speechLanguage = container.prefs.selectedSpeechLanguage(speech).first()
            val speechFallbackEnabled = container.prefs.speechFallbackEnabled.first()
            val speechFallbackProvider = container.prefs.selectedSpeechFallbackProvider.first()
            val speechFallbackLanguage = container.prefs.selectedSpeechLanguage(speechFallbackProvider).first()
            val heightUnit = container.prefs.heightUnit.first()
            val weightUnit = container.prefs.weightUnit.first()
            val preferGramsByDefault = container.prefs.preferGramsByDefault.first()
            val saveMealPhotosToGallery = container.prefs.saveMealPhotosToGallery.first()
            val notif = container.prefs.notificationsEnabled.first()
            val streakReminder = container.prefs.streakReminderEnabled.first()
            val dailySummary = container.prefs.dailySummaryEnabled.first()
            val weightReminder = container.prefs.weightReminderEnabled.first()
            val bodyFatReminder = container.prefs.bodyFatReminderEnabled.first()
            val goalReachedNotifications = container.prefs.goalReachedNotificationsEnabled.first()
            val appUpdateNotifications = container.prefs.appUpdateNotificationsEnabled.first()
            val waterTracking = container.prefs.waterTrackingEnabled.first()
            val waterGoal = container.prefs.waterDailyGoalMl.first()
            val waterUnit = container.prefs.waterUnit.first()
            val waterReminder = container.prefs.waterReminderEnabled.first()
            val fastingTracking = container.prefs.fastingTrackingEnabled.first()
            val walkRunQuickLog = container.prefs.walkRunQuickLogEnabled.first()
            val fastingGoal = container.prefs.fastingDefaultGoalMinutes.first()
            val fastingNotification = container.prefs.fastingGoalNotificationEnabled.first()
            val workoutPreferences = container.workoutRepository.preferences.first()
            val profile = container.profileRepository.current()
            val storedHealthConnect = container.prefs.healthConnectEnabled.first()
            val storedHealthPermissionsVersion = container.prefs.healthPermissionsVersion.first()
            val workoutHealthWriteGranted = storedHealthConnect &&
                storedHealthPermissionsVersion >= HealthConnectManager.CURRENT_TYPES_VERSION
            val energyGoals = container.prefs.healthEnergyGoalsEnabled.first() && storedHealthConnect
            val adaptiveGoals = container.prefs.adaptiveGoalsEnabled.first()
            val masked = maskKey(container.keyStore.apiKey(provider))
            val textMasked = maskKey(container.keyStore.apiKey(textProvider))
            val speechMasked = maskKey(container.keyStore.speechApiKey(speech))
            val speechFallbackMasked = maskKey(container.keyStore.speechApiKey(speechFallbackProvider))
            val appearance = container.prefs.appearanceMode.first()
            val appThemeColor = AppThemeColor.fromKey(container.prefs.appThemeColor.first())
            val weekMon = container.prefs.weekStartsOnMonday.first()
            val mealSchedule = container.prefs.mealSchedule.first()
            val userContext = container.prefs.userContext.first()
            val maxTokens = container.prefs.maxResponseTokens.first()
            val requestTimeoutSeconds = container.prefs.aiRequestTimeoutSeconds.first()
            val fbEnabled = container.prefs.fallbackEnabled.first()
            val fbProvider = container.prefs.selectedFallbackProvider.first()
            val fbModel = fbProvider.supportedModelOrDefault(container.prefs.selectedFallbackModel.first())
            val fbMasked = maskKey(container.keyStore.apiKey(fbProvider))
            val textFbEnabled = container.prefs.textFallbackEnabled.first()
            val textFbProvider = container.prefs.selectedTextFallbackProvider.first()
            val textFbModel = textFbProvider.supportedTextModelOrDefault(container.prefs.selectedTextFallbackModel.first())
            val textFbMasked = maskKey(container.keyStore.apiKey(textFbProvider))
            val optionalGoals = container.prefs.optionalNutrientGoals.first()
            val quickActions = listOf(
                container.prefs.quickAction1.first(),
                container.prefs.quickAction2.first(),
                container.prefs.quickAction3.first()
            )
            // Seed the recalc baseline for existing users / first launch so the nudge only fires
            // after a genuine change from here on, never immediately on open.
            val storedSignature = container.prefs.lastRecalcGoalSignature.first()
            lastRecalcSignature = storedSignature ?: profile?.goalInputSignature
            if (storedSignature == null && profile != null) {
                container.prefs.setLastRecalcGoalSignature(profile.goalInputSignature)
            }
            _ui.value = SettingsUiState(
                selectedAI = provider,
                selectedModel = model,
                separateTextProviderEnabled = separateTextEnabled,
                selectedTextAI = textProvider,
                selectedTextModel = textModel,
                textApiKeyMasked = textMasked,
                maxResponseTokens = maxTokens,
                openRouterReasoningEffort = container.prefs.openRouterReasoningEffort.first(),
                aiRequestTimeoutSeconds = requestTimeoutSeconds,
                selectedSpeech = speech,
                selectedSpeechLanguage = speechLanguage,
                speechFallbackEnabled = speechFallbackEnabled,
                speechFallbackProvider = speechFallbackProvider,
                speechFallbackLanguage = speechFallbackLanguage,
                speechFallbackApiKeyMasked = speechFallbackMasked,
                heightUnit = heightUnit,
                weightUnit = weightUnit,
                preferGramsByDefault = preferGramsByDefault,
                saveMealPhotosToGallery = saveMealPhotosToGallery,
                profile = profile,
                notificationsEnabled = notif,
                streakReminderEnabled = streakReminder,
                dailySummaryEnabled = dailySummary,
                weightReminderEnabled = weightReminder,
                bodyFatReminderEnabled = bodyFatReminder,
                goalReachedNotificationsEnabled = goalReachedNotifications,
                appUpdateNotificationsEnabled = appUpdateNotifications,
                waterTrackingEnabled = waterTracking,
                waterDailyGoalMl = waterGoal,
                waterUnit = waterUnit,
                waterReminderEnabled = waterReminder,
                fastingTrackingEnabled = fastingTracking,
                walkRunQuickLogEnabled = walkRunQuickLog,
                fastingDefaultGoalMinutes = fastingGoal,
                fastingGoalNotificationEnabled = fastingNotification,
                healthConnectEnabled = storedHealthConnect,
                workoutHealthWriteGranted = workoutHealthWriteGranted,
                healthEnergyGoalsEnabled = energyGoals,
                adaptiveGoalsEnabled = adaptiveGoals,
                apiKeyMasked = masked,
                speechApiKeyMasked = speechMasked,
                appearanceMode = appearance,
                appThemeColor = appThemeColor,
                weekStartsOnMonday = weekMon,
                mealSchedule = mealSchedule,
                userContext = userContext,
                fallbackEnabled = fbEnabled,
                fallbackProvider = fbProvider,
                fallbackModel = fbModel,
                fallbackApiKeyMasked = fbMasked,
                textFallbackEnabled = textFbEnabled,
                textFallbackProvider = textFbProvider,
                textFallbackModel = textFbModel,
                textFallbackApiKeyMasked = textFbMasked,
                optionalNutrientGoals = optionalGoals,
                quickActions = quickActions,
                workoutSplit = workoutPreferences.split,
                workoutRpeScale = workoutPreferences.rpeScale,
                localModelStates = container.localModels.states.value,
                goalsNeedRecalc = needsRecalc(profile)
            )

            // Permission reconciliation may open Health Connect's provider and backfill data, so
            // it must never sit on the critical path for displaying Settings. Refresh only the
            // health-dependent fields after the complete local page is already available.
            val reconciledHealthConnect = reconcileHealthConnectState()
            val reconciledWorkoutWrite = reconciledHealthConnect &&
                container.health.hasActiveEnergyWrite()
            val reconciledProfile = container.profileRepository.current()
            _ui.value = _ui.value.copy(
                profile = reconciledProfile ?: _ui.value.profile,
                healthConnectEnabled = reconciledHealthConnect,
                workoutHealthWriteGranted = reconciledWorkoutWrite,
                healthEnergyGoalsEnabled = container.prefs.healthEnergyGoalsEnabled.first() &&
                    reconciledHealthConnect,
                goalsNeedRecalc = needsRecalc(reconciledProfile ?: _ui.value.profile)
            )
        }
    }

    /**
     * Reloads the AI configuration that may be written outside Settings.
     *
     * The app-scoped Settings ViewModel is warmed while onboarding is still visible. Onboarding
     * then persists the selected provider, model, and API key independently, so the one-shot
     * values loaded in [init] can otherwise remain stale until the process restarts (issue #170).
     */
    fun refreshAiConfiguration() {
        viewModelScope.launch {
            container.prefs.migrateMatchingSpeechProviderIfNeeded()
            val provider = container.prefs.selectedAIProvider.first()
            val model = provider.supportedModelOrDefault(container.prefs.selectedAIModel.first())
            val maskedKey = maskKey(container.keyStore.apiKey(provider))
            val textEnabled = container.prefs.separateTextProviderEnabled.first()
            val textProvider = container.prefs.selectedTextAIProvider.first()
            val textModel = textProvider.supportedTextModelOrDefault(container.prefs.selectedTextAIModel.first())
            val speechProvider = container.prefs.selectedSpeechProvider.first()
            val speechFallbackProvider = container.prefs.selectedSpeechFallbackProvider.first()
            _ui.value = _ui.value.copy(
                selectedAI = provider,
                selectedModel = model,
                apiKeyMasked = maskedKey,
                separateTextProviderEnabled = textEnabled,
                selectedTextAI = textProvider,
                selectedTextModel = textModel,
                textApiKeyMasked = maskKey(container.keyStore.apiKey(textProvider)),
                selectedSpeech = speechProvider,
                selectedSpeechLanguage = container.prefs.selectedSpeechLanguage(speechProvider).first(),
                speechApiKeyMasked = maskKey(container.keyStore.speechApiKey(speechProvider)),
                speechFallbackEnabled = container.prefs.speechFallbackEnabled.first(),
                speechFallbackProvider = speechFallbackProvider,
                speechFallbackLanguage = container.prefs.selectedSpeechLanguage(speechFallbackProvider).first(),
                speechFallbackApiKeyMasked = maskKey(container.keyStore.speechApiKey(speechFallbackProvider))
            )
        }
    }

    fun selectWorkoutSplit(split: WorkoutSplit) {
        viewModelScope.launch {
            container.workoutRepository.updatePreferences { it.copy(split = split) }
        }
    }

    fun selectWorkoutRpeScale(scale: WorkoutRpeScale) {
        viewModelScope.launch {
            container.workoutRepository.updatePreferences { it.copy(rpeScale = scale) }
        }
    }

    fun setOptionalNutrientGoals(goals: OptionalNutrientGoals) {
        viewModelScope.launch {
            container.prefs.setOptionalNutrientGoals(goals)
            _ui.value = _ui.value.copy(optionalNutrientGoals = goals)
        }
    }

    fun setUserContext(value: String) {
        viewModelScope.launch {
            container.prefs.setUserContext(value)
            _ui.value = _ui.value.copy(userContext = value.trim())
        }
    }

    fun setOpenRouterReasoningEffort(value: OpenRouterReasoningEffort) {
        viewModelScope.launch {
            container.prefs.setOpenRouterReasoningEffort(value)
            _ui.value = _ui.value.copy(openRouterReasoningEffort = value)
        }
    }

    fun setMaxResponseTokens(v: Int) {
        val clamped = v.coerceAtLeast(1)
        viewModelScope.launch {
            container.prefs.setMaxResponseTokens(clamped)
            _ui.value = _ui.value.copy(maxResponseTokens = clamped)
        }
    }

    fun setAiRequestTimeoutSeconds(value: Int) {
        val clamped = AIProvider.normalizedRequestTimeoutSeconds(value)
        viewModelScope.launch {
            container.prefs.setAiRequestTimeoutSeconds(clamped)
            _ui.value = _ui.value.copy(aiRequestTimeoutSeconds = clamped)
        }
    }

    fun setFallbackEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setFallbackEnabled(v)
            _ui.value = _ui.value.copy(fallbackEnabled = v)
        }
    }

    fun selectFallbackProvider(p: AIProvider) {
        if (p !in _ui.value.availableVisionProviders) return
        viewModelScope.launch {
            container.prefs.setSelectedFallbackProvider(p)
            // Reset model to provider default if old model isn't in the new provider's list.
            val current = _ui.value.fallbackModel
            val newModel = p.supportedModelOrDefault(current)
            container.prefs.setSelectedFallbackModel(newModel)
            val masked = maskKey(container.keyStore.apiKey(p))
            _ui.value = _ui.value.copy(fallbackProvider = p, fallbackModel = newModel, fallbackApiKeyMasked = masked)
        }
    }

    fun selectFallbackModel(m: String) {
        viewModelScope.launch {
            val model = _ui.value.fallbackProvider.supportedModelOrDefault(m)
            container.prefs.setSelectedFallbackModel(model)
            _ui.value = _ui.value.copy(fallbackModel = model)
        }
    }

    fun setFallbackApiKey(raw: String) {
        viewModelScope.launch {
            val p = _ui.value.fallbackProvider
            container.keyStore.setApiKey(p, raw.takeIf { it.isNotBlank() })
            val masked = maskKey(raw.takeIf { it.isNotBlank() })
            _ui.value = _ui.value.copy(
                fallbackApiKeyMasked = masked,
                apiKeyMasked = if (p == _ui.value.selectedAI) masked else _ui.value.apiKeyMasked,
                textApiKeyMasked = if (p == _ui.value.selectedTextAI) masked else _ui.value.textApiKeyMasked,
                textFallbackApiKeyMasked = if (p == _ui.value.textFallbackProvider) masked else _ui.value.textFallbackApiKeyMasked
            )
        }
    }

    fun setTextFallbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.prefs.setTextFallbackEnabled(enabled)
            _ui.value = _ui.value.copy(textFallbackEnabled = enabled)
        }
    }

    fun selectTextFallbackProvider(provider: AIProvider) {
        if (provider !in _ui.value.availableTextProviders) return
        viewModelScope.launch {
            container.prefs.setSelectedTextFallbackProvider(provider)
            val model = provider.supportedTextModelOrDefault(_ui.value.textFallbackModel)
            container.prefs.setSelectedTextFallbackModel(model)
            _ui.value = _ui.value.copy(
                textFallbackProvider = provider,
                textFallbackModel = model,
                textFallbackApiKeyMasked = maskKey(container.keyStore.apiKey(provider))
            )
        }
    }

    fun selectTextFallbackModel(model: String) {
        viewModelScope.launch {
            val resolved = _ui.value.textFallbackProvider.supportedTextModelOrDefault(model)
            container.prefs.setSelectedTextFallbackModel(resolved)
            _ui.value = _ui.value.copy(textFallbackModel = resolved)
        }
    }

    fun setTextFallbackApiKey(raw: String) {
        viewModelScope.launch {
            val provider = _ui.value.textFallbackProvider
            container.keyStore.setApiKey(provider, raw.takeIf { it.isNotBlank() })
            val masked = maskKey(raw.takeIf { it.isNotBlank() })
            _ui.value = _ui.value.copy(
                textFallbackApiKeyMasked = masked,
                apiKeyMasked = if (provider == _ui.value.selectedAI) masked else _ui.value.apiKeyMasked,
                textApiKeyMasked = if (provider == _ui.value.selectedTextAI) masked else _ui.value.textApiKeyMasked,
                fallbackApiKeyMasked = if (provider == _ui.value.fallbackProvider) masked else _ui.value.fallbackApiKeyMasked
            )
        }
    }

    fun setAppearanceMode(mode: String) {
        viewModelScope.launch {
            container.prefs.setAppearanceMode(mode)
            _ui.value = _ui.value.copy(appearanceMode = mode)
        }
    }

    fun setQuickAction(slot: Int, action: QuickAction) {
        if (slot !in 0..2) return
        viewModelScope.launch {
            container.prefs.setQuickAction(slot, action)
            val updated = _ui.value.quickActions.toMutableList().apply {
                while (size < 3) add(QuickAction.Defaults[size])
                this[slot] = action
            }
            _ui.value = _ui.value.copy(quickActions = updated)
        }
    }

    fun setAppThemeColor(themeColor: AppThemeColor) {
        viewModelScope.launch {
            container.prefs.setAppThemeColor(themeColor.key)
            AndroidAppIconManager.apply(container.appContext, themeColor)
            _ui.value = _ui.value.copy(appThemeColor = themeColor)
        }
    }

    fun setWeekStartsOnMonday(monday: Boolean) {
        viewModelScope.launch {
            container.prefs.setWeekStartsOnMonday(monday)
            _ui.value = _ui.value.copy(weekStartsOnMonday = monday)
        }
    }

    fun setMealSchedule(schedule: MealSchedule) {
        val validated = schedule.validatedOrDefault()
        viewModelScope.launch {
            container.prefs.setMealSchedule(validated)
            CurrentMealSchedule.value = validated
            _ui.value = _ui.value.copy(mealSchedule = validated)
        }
    }

    fun selectProvider(p: AIProvider) {
        if (p !in _ui.value.availableVisionProviders) return
        viewModelScope.launch {
            container.prefs.setSelectedAIProvider(p)
            container.prefs.setSelectedAIModel(p.defaultModel)
            val masked = maskKey(container.keyStore.apiKey(p))
            _ui.value = _ui.value.copy(selectedAI = p, selectedModel = p.defaultModel, apiKeyMasked = masked)
        }
    }

    fun selectModel(m: String) {
        viewModelScope.launch {
            val model = _ui.value.selectedAI.supportedModelOrDefault(m)
            container.prefs.setSelectedAIModel(model)
            _ui.value = _ui.value.copy(selectedModel = model)
        }
    }

    fun setApiKey(raw: String) {
        viewModelScope.launch {
            val p = _ui.value.selectedAI
            container.keyStore.setApiKey(p, raw.takeIf { it.isNotBlank() })
            val masked = maskKey(raw.takeIf { it.isNotBlank() })
            val speechMasked = if (_ui.value.selectedSpeech.matchingAIProvider == p) {
                maskKey(container.keyStore.speechApiKey(_ui.value.selectedSpeech))
            } else {
                _ui.value.speechApiKeyMasked
            }
            val speechFallbackMasked = if (_ui.value.speechFallbackProvider.matchingAIProvider == p) {
                maskKey(container.keyStore.speechApiKey(_ui.value.speechFallbackProvider))
            } else {
                _ui.value.speechFallbackApiKeyMasked
            }
            _ui.value = _ui.value.copy(
                apiKeyMasked = masked,
                textApiKeyMasked = if (p == _ui.value.selectedTextAI) masked else _ui.value.textApiKeyMasked,
                fallbackApiKeyMasked = if (p == _ui.value.fallbackProvider) masked else _ui.value.fallbackApiKeyMasked,
                textFallbackApiKeyMasked = if (p == _ui.value.textFallbackProvider) masked else _ui.value.textFallbackApiKeyMasked,
                speechApiKeyMasked = speechMasked,
                speechFallbackApiKeyMasked = speechFallbackMasked
            )
        }
    }

    fun setSeparateTextProviderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.prefs.setSeparateTextProviderEnabled(enabled)
            _ui.value = _ui.value.copy(separateTextProviderEnabled = enabled)
        }
    }

    fun selectTextProvider(provider: AIProvider) {
        if (provider !in _ui.value.availableTextProviders) return
        viewModelScope.launch {
            container.prefs.setSelectedTextAIProvider(provider)
            val model = provider.defaultTextModel
            container.prefs.setSelectedTextAIModel(model)
            _ui.value = _ui.value.copy(
                selectedTextAI = provider,
                selectedTextModel = model,
                textApiKeyMasked = maskKey(container.keyStore.apiKey(provider))
            )
        }
    }

    fun selectTextModel(model: String) {
        viewModelScope.launch {
            val resolved = _ui.value.selectedTextAI.supportedTextModelOrDefault(model)
            container.prefs.setSelectedTextAIModel(resolved)
            _ui.value = _ui.value.copy(selectedTextModel = resolved)
        }
    }

    fun setTextApiKey(raw: String) {
        viewModelScope.launch {
            val provider = _ui.value.selectedTextAI
            container.keyStore.setApiKey(provider, raw.takeIf { it.isNotBlank() })
            val masked = maskKey(raw.takeIf { it.isNotBlank() })
            val speechMasked = if (_ui.value.selectedSpeech.matchingAIProvider == provider) {
                maskKey(container.keyStore.speechApiKey(_ui.value.selectedSpeech))
            } else {
                _ui.value.speechApiKeyMasked
            }
            val speechFallbackMasked = if (_ui.value.speechFallbackProvider.matchingAIProvider == provider) {
                maskKey(container.keyStore.speechApiKey(_ui.value.speechFallbackProvider))
            } else {
                _ui.value.speechFallbackApiKeyMasked
            }
            _ui.value = _ui.value.copy(
                textApiKeyMasked = masked,
                apiKeyMasked = if (provider == _ui.value.selectedAI) masked else _ui.value.apiKeyMasked,
                fallbackApiKeyMasked = if (provider == _ui.value.fallbackProvider) masked else _ui.value.fallbackApiKeyMasked,
                textFallbackApiKeyMasked = if (provider == _ui.value.textFallbackProvider) masked else _ui.value.textFallbackApiKeyMasked,
                speechApiKeyMasked = speechMasked,
                speechFallbackApiKeyMasked = speechFallbackMasked
            )
        }
    }

    fun selectSpeech(p: SpeechProvider) {
        if (p !in _ui.value.availableSpeechProviders) return
        viewModelScope.launch {
            container.prefs.setSelectedSpeechProvider(p)
            var fallbackProvider = _ui.value.speechFallbackProvider
            if (p == fallbackProvider) {
                fallbackProvider = SpeechProvider.remoteProviders.first { it != p }
                container.prefs.setSelectedSpeechFallbackProvider(fallbackProvider)
            }
            // Re-pull the masked key for the new provider so the API Key row
            // reflects whether the freshly selected provider has a key saved.
            val masked = maskKey(container.keyStore.speechApiKey(p))
            val language = container.prefs.selectedSpeechLanguage(p).first()
            val fallbackLanguage = container.prefs.selectedSpeechLanguage(fallbackProvider).first()
            _ui.value = _ui.value.copy(
                selectedSpeech = p,
                selectedSpeechLanguage = language,
                speechApiKeyMasked = masked,
                speechFallbackProvider = fallbackProvider,
                speechFallbackLanguage = fallbackLanguage,
                speechFallbackApiKeyMasked = maskKey(container.keyStore.speechApiKey(fallbackProvider))
            )
        }
    }

    fun selectSpeechLanguage(language: SpeechLanguage) {
        viewModelScope.launch {
            val provider = _ui.value.selectedSpeech
            container.prefs.setSelectedSpeechLanguage(provider, language)
            _ui.value = _ui.value.copy(selectedSpeechLanguage = language)
        }
    }

    fun setSpeechApiKey(raw: String) {
        viewModelScope.launch {
            val p = _ui.value.selectedSpeech
            container.keyStore.setSpeechApiKey(p, raw.takeIf { it.isNotBlank() })
            val masked = maskKey(container.keyStore.speechApiKey(p))
            _ui.value = _ui.value.copy(
                speechApiKeyMasked = masked,
                speechFallbackApiKeyMasked = if (p == _ui.value.speechFallbackProvider) masked else _ui.value.speechFallbackApiKeyMasked
            )
        }
    }

    fun setSpeechFallbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.prefs.setSpeechFallbackEnabled(enabled)
            _ui.value = _ui.value.copy(speechFallbackEnabled = enabled)
        }
    }

    fun selectSpeechFallbackProvider(provider: SpeechProvider) {
        if (provider !in _ui.value.availableSpeechFallbackProviders) return
        viewModelScope.launch {
            container.prefs.setSelectedSpeechFallbackProvider(provider)
            val language = container.prefs.selectedSpeechLanguage(provider).first()
            _ui.value = _ui.value.copy(
                speechFallbackProvider = provider,
                speechFallbackLanguage = language,
                speechFallbackApiKeyMasked = maskKey(container.keyStore.speechApiKey(provider))
            )
        }
    }

    fun selectSpeechFallbackLanguage(language: SpeechLanguage) {
        viewModelScope.launch {
            val provider = _ui.value.speechFallbackProvider
            container.prefs.setSelectedSpeechLanguage(provider, language)
            _ui.value = _ui.value.copy(speechFallbackLanguage = language)
        }
    }

    fun setSpeechFallbackApiKey(raw: String) {
        viewModelScope.launch {
            val provider = _ui.value.speechFallbackProvider
            container.keyStore.setSpeechApiKey(provider, raw.takeIf { it.isNotBlank() })
            val masked = maskKey(container.keyStore.speechApiKey(provider))
            _ui.value = _ui.value.copy(
                speechFallbackApiKeyMasked = masked,
                speechApiKeyMasked = if (provider == _ui.value.selectedSpeech) masked else _ui.value.speechApiKeyMasked
            )
        }
    }

    fun downloadLocalModel(id: LocalModelId) {
        container.localModels.download(id)
    }

    fun deleteLocalModel(id: LocalModelId) {
        viewModelScope.launch {
            when (id) {
                LocalModelId.GEMMA_4_E2B -> resetGemmaSelectionsBeforeDelete()
                LocalModelId.WHISPER_BASE -> resetWhisperSelectionsBeforeDelete()
            }
            container.localModels.delete(id)
        }
    }

    private suspend fun resetGemmaSelectionsBeforeDelete() {
        var state = _ui.value
        if (state.selectedAI == AIProvider.LOCAL_GEMMA) {
            container.prefs.setSelectedAIProvider(AIProvider.GEMINI)
            container.prefs.setSelectedAIModel(AIProvider.GEMINI.defaultModel)
            state = state.copy(
                selectedAI = AIProvider.GEMINI,
                selectedModel = AIProvider.GEMINI.defaultModel,
                apiKeyMasked = maskKey(container.keyStore.apiKey(AIProvider.GEMINI))
            )
        }
        if (state.selectedTextAI == AIProvider.LOCAL_GEMMA) {
            container.prefs.setSelectedTextAIProvider(AIProvider.GEMINI)
            container.prefs.setSelectedTextAIModel(AIProvider.GEMINI.defaultTextModel)
            state = state.copy(
                selectedTextAI = AIProvider.GEMINI,
                selectedTextModel = AIProvider.GEMINI.defaultTextModel,
                textApiKeyMasked = maskKey(container.keyStore.apiKey(AIProvider.GEMINI))
            )
        }
        if (state.fallbackProvider == AIProvider.LOCAL_GEMMA) {
            container.prefs.setFallbackEnabled(false)
            container.prefs.setSelectedFallbackProvider(AIProvider.GEMINI)
            container.prefs.setSelectedFallbackModel(AIProvider.GEMINI.defaultModel)
            state = state.copy(
                fallbackEnabled = false,
                fallbackProvider = AIProvider.GEMINI,
                fallbackModel = AIProvider.GEMINI.defaultModel,
                fallbackApiKeyMasked = maskKey(container.keyStore.apiKey(AIProvider.GEMINI))
            )
        }
        if (state.textFallbackProvider == AIProvider.LOCAL_GEMMA) {
            container.prefs.setTextFallbackEnabled(false)
            container.prefs.setSelectedTextFallbackProvider(AIProvider.GEMINI)
            container.prefs.setSelectedTextFallbackModel(AIProvider.GEMINI.defaultTextModel)
            state = state.copy(
                textFallbackEnabled = false,
                textFallbackProvider = AIProvider.GEMINI,
                textFallbackModel = AIProvider.GEMINI.defaultTextModel,
                textFallbackApiKeyMasked = maskKey(container.keyStore.apiKey(AIProvider.GEMINI))
            )
        }
        _ui.value = state
    }

    private suspend fun resetWhisperSelectionsBeforeDelete() {
        var state = _ui.value
        if (state.selectedSpeech == SpeechProvider.LOCAL_WHISPER) {
            container.prefs.setSelectedSpeechProvider(SpeechProvider.NATIVE)
            state = state.copy(
                selectedSpeech = SpeechProvider.NATIVE,
                selectedSpeechLanguage = container.prefs.selectedSpeechLanguage(SpeechProvider.NATIVE).first(),
                speechApiKeyMasked = ""
            )
        }
        if (state.speechFallbackProvider == SpeechProvider.LOCAL_WHISPER) {
            container.prefs.setSpeechFallbackEnabled(false)
            container.prefs.setSelectedSpeechFallbackProvider(SpeechProvider.GROQ)
            state = state.copy(
                speechFallbackEnabled = false,
                speechFallbackProvider = SpeechProvider.GROQ,
                speechFallbackLanguage = container.prefs.selectedSpeechLanguage(SpeechProvider.GROQ).first(),
                speechFallbackApiKeyMasked = maskKey(container.keyStore.speechApiKey(SpeechProvider.GROQ))
            )
        }
        _ui.value = state
    }

    fun setHeightUnit(v: String) {
        viewModelScope.launch {
            container.prefs.setHeightUnit(v)
            _ui.value = _ui.value.copy(heightUnit = v)
        }
    }

    fun setWeightUnit(v: String) {
        viewModelScope.launch {
            container.prefs.setWeightUnit(v)
            _ui.value = _ui.value.copy(weightUnit = v)
        }
    }

    fun setPreferGramsByDefault(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setPreferGramsByDefault(v)
            _ui.value = _ui.value.copy(preferGramsByDefault = v)
        }
    }

    fun setSaveMealPhotosToGallery(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setSaveMealPhotosToGallery(v)
            _ui.value = _ui.value.copy(saveMealPhotosToGallery = v)
        }
    }

    fun setNotificationsEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setNotificationsEnabled(v)
            syncNotificationSchedules()
            _ui.value = _ui.value.copy(notificationsEnabled = v)
        }
    }

    fun setStreakReminderEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setStreakReminderEnabled(v)
            syncNotificationSchedules()
            _ui.value = _ui.value.copy(streakReminderEnabled = v)
        }
    }

    fun setDailySummaryEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setDailySummaryEnabled(v)
            syncNotificationSchedules()
            _ui.value = _ui.value.copy(dailySummaryEnabled = v)
        }
    }

    fun setWeightReminderEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setWeightReminderEnabled(v)
            syncNotificationSchedules()
            _ui.value = _ui.value.copy(weightReminderEnabled = v)
        }
    }

    fun setBodyFatReminderEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setBodyFatReminderEnabled(v)
            syncNotificationSchedules()
            _ui.value = _ui.value.copy(bodyFatReminderEnabled = v)
        }
    }

    fun setGoalReachedNotificationsEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setGoalReachedNotificationsEnabled(v)
            _ui.value = _ui.value.copy(goalReachedNotificationsEnabled = v)
        }
    }

    fun setAppUpdateNotificationsEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setAppUpdateNotificationsEnabled(v)
            _ui.value = _ui.value.copy(appUpdateNotificationsEnabled = v)
        }
    }

    fun setWaterTrackingEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setWaterTrackingEnabled(v)
            if (!v) {
                container.prefs.setWaterReminderEnabled(false)
                container.notifications.cancelWaterReminder()
            }
            _ui.value = _ui.value.copy(
                waterTrackingEnabled = v,
                waterReminderEnabled = if (v) _ui.value.waterReminderEnabled else false
            )
        }
    }

    fun setWaterDailyGoalMl(v: Int) {
        viewModelScope.launch {
            container.prefs.setWaterDailyGoalMl(v)
            _ui.value = _ui.value.copy(waterDailyGoalMl = v)
        }
    }

    fun setWaterUnit(v: WaterUnit) {
        viewModelScope.launch {
            container.prefs.setWaterUnit(v)
            _ui.value = _ui.value.copy(waterUnit = v)
        }
    }

    fun setWaterReminderEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setWaterReminderEnabled(v)
            _ui.value = _ui.value.copy(waterReminderEnabled = v)
            syncNotificationSchedules()
        }
    }

    fun setFastingTrackingEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setFastingTrackingEnabled(v)
            if (v) {
                container.notifications.ensureFastingChannel()
            } else {
                // End rather than cancel so disabling the feature never deletes
                // an in-progress fast. The completed session remains in history.
                container.fastingRepository.endActive()
                container.notifications.removeFastingChannel()
            }
            _ui.value = _ui.value.copy(fastingTrackingEnabled = v)
        }
    }

    fun setWalkRunQuickLogEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setWalkRunQuickLogEnabled(v)
            _ui.value = _ui.value.copy(walkRunQuickLogEnabled = v)
        }
    }

    fun setFastingDefaultGoalMinutes(v: Int) {
        viewModelScope.launch {
            container.prefs.setFastingDefaultGoalMinutes(v)
            _ui.value = _ui.value.copy(fastingDefaultGoalMinutes = v.coerceIn(60, 7 * 24 * 60))
        }
    }

    fun setFastingGoalNotificationEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setFastingGoalNotificationEnabled(v)
            _ui.value = _ui.value.copy(fastingGoalNotificationEnabled = v)
            syncNotificationSchedules()
        }
    }

    private suspend fun syncNotificationSchedules() {
        val enabled = container.prefs.notificationsEnabled.first()
        if (!enabled || !container.notifications.canPostNotifications()) {
            container.notifications.cancelStreakReminder()
            container.notifications.cancelDailySummary()
            container.notifications.cancelWeightReminder()
            container.notifications.cancelBodyFatReminder()
            container.notifications.cancelWaterReminder()
            container.notifications.cancelFastingGoal()
            return
        }

        if (container.prefs.streakReminderEnabled.first()) {
            container.notifications.scheduleStreakReminder(
                container.prefs.streakReminderHour.first(),
                container.prefs.streakReminderMinute.first()
            )
        } else {
            container.notifications.cancelStreakReminder()
        }

        if (container.prefs.dailySummaryEnabled.first()) {
            container.notifications.scheduleDailySummary(
                container.prefs.dailySummaryHour.first(),
                container.prefs.dailySummaryMinute.first()
            )
        } else {
            container.notifications.cancelDailySummary()
        }

        if (container.prefs.weightReminderEnabled.first()) {
            container.notifications.scheduleWeightReminder()
        } else {
            container.notifications.cancelWeightReminder()
        }

        val profile = container.profileRepository.current()
        if (container.prefs.bodyFatReminderEnabled.first() && profile?.bodyFatPercentage != null) {
            container.notifications.scheduleBodyFatReminder()
        } else {
            container.notifications.cancelBodyFatReminder()
        }

        if (container.prefs.waterTrackingEnabled.first() && container.prefs.waterReminderEnabled.first()) {
            container.notifications.scheduleWaterReminder(
                container.prefs.waterReminderHour.first(),
                container.prefs.waterReminderMinute.first()
            )
        } else {
            container.notifications.cancelWaterReminder()
        }

        if (container.prefs.fastingTrackingEnabled.first() &&
            container.prefs.fastingGoalNotificationEnabled.first()
        ) {
            container.notifications.scheduleFastingGoal(container.fastingRepository.active())
        } else {
            container.notifications.cancelFastingGoal()
        }
    }

    fun setHealthConnectEnabled(v: Boolean) {
        viewModelScope.launch {
            if (!v) {
                val restored = if (container.prefs.healthEnergyGoalsEnabled.first()) {
                    container.profileRepository.current()
                        ?.let { container.prefs.restoreHealthEnergyGoalPreviousTargets(it) }
                } else {
                    null
                }
                if (restored != null) {
                    container.profileRepository.save(restored)
                    container.prefs.clearHealthEnergyGoalPreviousTargets()
                }
                container.prefs.setHealthConnectEnabled(false)
                container.prefs.setHealthEnergyGoalsEnabled(false)
                _ui.value = _ui.value.copy(
                    profile = restored ?: _ui.value.profile,
                    healthConnectEnabled = false,
                    workoutHealthWriteGranted = false,
                    healthEnergyGoalsEnabled = false
                )
                return@launch
            }

            val enabled = container.health.isAvailable() && container.health.hasAnyPermission()
            container.prefs.setHealthConnectEnabled(enabled)
            if (enabled) {
                backfillHealthConnect()
                container.syncHealthConnectReads()
                if (container.health.hasActiveEnergyWrite() && container.health.hasStepsRead()) {
                    container.prefs.setHealthPermissionsVersion(HealthConnectManager.CURRENT_TYPES_VERSION)
                }
            }
            if (!enabled) container.prefs.setHealthEnergyGoalsEnabled(false)
            _ui.value = _ui.value.copy(
                healthConnectEnabled = enabled,
                workoutHealthWriteGranted = enabled && container.health.hasActiveEnergyWrite(),
                healthEnergyGoalsEnabled = if (enabled) _ui.value.healthEnergyGoalsEnabled else false
            )
        }
    }

    private suspend fun reconcileHealthConnectState(): Boolean {
        if (!container.health.isAvailable()) {
            container.prefs.setHealthConnectEnabled(false)
            return false
        }

        val granted = container.health.hasAnyPermission()
        val stored = container.prefs.healthConnectEnabled.first()
        val version = container.prefs.healthPermissionsVersion.first()
        container.prefs.setHealthConnectEnabled(granted)
        if (!granted) {
            if (container.prefs.healthEnergyGoalsEnabled.first()) {
                container.profileRepository.current()?.let { current ->
                    val restored = container.prefs.restoreHealthEnergyGoalPreviousTargets(current)
                    container.profileRepository.save(restored)
                }
                container.prefs.clearHealthEnergyGoalPreviousTargets()
            }
            container.prefs.setHealthEnergyGoalsEnabled(false)
        }

        // "Connected" is now any-permission, so revoking ONLY the energy reads leaves granted=true
        // and skips the block above. Tear Energy Burn down independently on its own capability so
        // the toggle doesn't lie about an anchor that can no longer refresh.
        if (granted && container.prefs.healthEnergyGoalsEnabled.first() && !container.health.hasEnergyRead()) {
            container.profileRepository.current()?.let { current ->
                val restored = container.prefs.restoreHealthEnergyGoalPreviousTargets(current)
                container.profileRepository.save(restored)
            }
            container.prefs.clearHealthEnergyGoalPreviousTargets()
            container.prefs.setHealthEnergyGoalsEnabled(false)
        }

        val workoutWriteGranted = container.health.hasActiveEnergyWrite()
        val stepsReadGranted = container.health.hasStepsRead()
        if (granted && (!stored || version < HealthConnectManager.CURRENT_TYPES_VERSION)) {
            backfillHealthConnect()
            // v5 adds workout Active Energy write; v6 adds steps read. Do not mark
            // complete until every permission added in those versions is granted.
            if (workoutWriteGranted && stepsReadGranted) {
                container.prefs.setHealthPermissionsVersion(HealthConnectManager.CURRENT_TYPES_VERSION)
            }
        }

        // Pull external weigh-ins / body-fat readings whenever Settings reloads while connected.
        if (granted) container.syncHealthConnectReads()

        return granted
    }

    /**
     * Energy Burn toggle. It owns no targets — it just flips a flag that the goal calc consults:
     * when on, the calc anchors maintenance to the measured Health Connect burn instead of the
     * formula TDEE. Turning it on requires Health Connect with enough energy data. Either way we
     * re-run the calc so the new (or removed) anchor applies immediately.
     */
    fun setHealthEnergyGoalsEnabled(v: Boolean) {
        viewModelScope.launch {
            if (v) {
                val granted = container.health.isAvailable() && container.health.hasEnergyRead()
                if (!granted) {
                    showHealthEnergyGoalAlert(
                        title = container.appContext.getString(R.string.vm_health_connect_needed),
                        message = container.appContext.getString(R.string.vm_health_connect_needed_msg)
                    )
                    return@launch
                }
                container.prefs.setHealthConnectEnabled(true)
                if (container.health.hasActiveEnergyWrite() && container.health.hasStepsRead()) {
                    container.prefs.setHealthPermissionsVersion(HealthConnectManager.CURRENT_TYPES_VERSION)
                }
                if (container.health.readRecentEnergySummary(days = 14) == null) {
                    showHealthEnergyGoalAlert(
                        title = container.appContext.getString(R.string.vm_not_enough_energy),
                        message = container.appContext.getString(R.string.vm_not_enough_energy_msg)
                    )
                    return@launch
                }
            }
            container.prefs.setHealthEnergyGoalsEnabled(v)
            _ui.value = _ui.value.copy(
                healthEnergyGoalsEnabled = v,
                healthConnectEnabled = if (v) true else _ui.value.healthConnectEnabled
            )
            // Re-run the goal calc so the new (or removed) measured anchor takes effect now.
            recalculateGoals()
        }
    }

    fun dismissHealthEnergyGoalAlert() {
        _ui.value = _ui.value.copy(
            healthEnergyGoalAlertTitle = null,
            healthEnergyGoalAlertMessage = null
        )
    }

    fun setAdaptiveGoalsEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setAdaptiveGoalsEnabled(v)
            if (!v) {
                val current = container.profileRepository.current()
                val restored = current?.let { container.prefs.restoreAdaptiveGoalPreviousTargets(it) }
                if (restored != null) {
                    container.profileRepository.save(restored)
                }
                container.prefs.clearAdaptiveGoalPreviousTargets()
                _ui.value = _ui.value.copy(
                    profile = restored ?: _ui.value.profile,
                    adaptiveGoalsEnabled = false,
                    applyingAdaptiveGoals = false
                )
                return@launch
            }

            _ui.value = _ui.value.copy(
                adaptiveGoalsEnabled = true,
                applyingAdaptiveGoals = true
            )
            // Adaptive owns the targets while on and auto-recalculates — drop any user locks now so
            // the (disabled) lock controls read as unlocked, even before the first weekly run lands.
            container.profileRepository.current()?.let { cur ->
                if (cur.caloriesLocked || cur.lockedMacros.isNotEmpty()) {
                    container.profileRepository.save(cur.withLocksCleared())
                }
            }
            val result = container.refreshAdaptiveGoalsIfNeeded(force = true)
            _ui.value = _ui.value.copy(
                profile = result?.profile ?: container.profileRepository.current() ?: _ui.value.profile,
                adaptiveGoalsEnabled = true,
                applyingAdaptiveGoals = false,
                adaptiveGoalAlertTitle = container.appContext.getString(R.string.settings_adaptive_goals),
                adaptiveGoalAlertMessage = result?.message
                    ?: container.appContext.getString(R.string.vm_adaptive_on_message)
            )
        }
    }

    fun dismissAdaptiveGoalAlert() {
        _ui.value = _ui.value.copy(
            adaptiveGoalAlertTitle = null,
            adaptiveGoalAlertMessage = null
        )
    }

    private fun showHealthEnergyGoalAlert(title: String, message: String) {
        _ui.value = _ui.value.copy(
            healthEnergyGoalsEnabled = false,
            healthEnergyGoalAlertTitle = title,
            healthEnergyGoalAlertMessage = message
        )
    }

    /** Push existing local entries OUT to Health Connect. Each section is gated on its own
     *  WRITE permission, so a partial grant (e.g. weight-write only) still backfills what it can. */
    private suspend fun backfillHealthConnect() {
        val caps = container.health.capabilities()
        if (caps.nutritionWrite) {
            container.foodRepository.entries.first().forEach { entry ->
                container.health.updateNutrition(entry)
            }
        }
        if (caps.weightWrite) {
            container.weightRepository.entries.first().forEach { entry ->
                container.health.writeWeight(entry)
            }
        }
        if (caps.bodyFatWrite) {
            container.bodyFatRepository.entries.first().forEach { entry ->
                container.health.writeBodyFat(entry)
            }
        }
        if (caps.activeEnergyWrite || container.health.hasActiveEnergyRead()) {
            container.workoutRepository.synchronizeWithHealth()
        }
    }

    fun deleteAllData(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            val challengeDeleted = container.weeklyChallengeRepository.prepareForDeleteEverything()
            container.prefs.clearAll()
            container.keyStore.clearAll(preserveWeeklyChallengeToken = !challengeDeleted)
            container.imageStore.clearAll()
            onComplete()
        }
    }

    fun clearFoodLog() {
        viewModelScope.launch {
            container.foodRepository.clear()
        }
    }

    fun recalculateGoals() {
        viewModelScope.launch {
            if (_ui.value.recalculatingGoals) return@launch
            val current = container.profileRepository.current() ?: return@launch
            _ui.value = _ui.value.copy(recalculatingGoals = true)
            val heightMetric = container.prefs.heightUnit.first() == "cm"
            val weightMetric = container.prefs.weightUnit.first() == "kg"
            val healthEnabledAtStart = container.prefs.healthConnectEnabled.first()
            val energyEnabledAtStart = container.prefs.healthEnergyGoalsEnabled.first()
            // AI-only — no formula fallback. If the AI provider is unavailable, leave the
            // existing goals untouched and tell the user so they can fix their key and retry.
            val result = try {
                // One privacy-safe snapshot supplies the weight trend, daily nutrition aggregates,
                // measurements, workout activity, and (when enabled) measured Health energy.
                val context = container.goalCalculationEvidence(current)
                if (container.prefs.healthConnectEnabled.first() != healthEnabledAtStart ||
                    container.prefs.healthEnergyGoalsEnabled.first() != energyEnabledAtStart
                ) {
                    _ui.value = _ui.value.copy(recalculatingGoals = false)
                    return@launch
                }
                container.foodAnalysis.calculateGoals(
                    profile = current,
                    heightMetric = heightMetric,
                    weightMetric = weightMetric,
                    measuredTdee = context.measuredTdee,
                    measurement = container.bodyMeasurementRepository.latestSnapshot(),
                    evidence = context.evidence
                )
            } catch (e: Throwable) {
                if (container.prefs.healthConnectEnabled.first() != healthEnabledAtStart ||
                    container.prefs.healthEnergyGoalsEnabled.first() != energyEnabledAtStart
                ) {
                    _ui.value = _ui.value.copy(recalculatingGoals = false)
                    return@launch
                }
                _ui.value = _ui.value.copy(
                    recalculatingGoals = false,
                    adaptiveGoalAlertTitle = "Couldn't Recalculate",
                    adaptiveGoalAlertMessage = "Fud AI couldn't reach your AI provider, so your goals are unchanged. Check your AI provider and API key in Settings, then try again. (${e.localizedMessage ?: "no response"})"
                )
                return@launch
            }
            // The provider call can take seconds. Never overwrite profile/target edits the user
            // made while it was in flight; leave the latest profile intact and let them rerun.
            val latest = container.profileRepository.current()
            if (latest == null || latest != current ||
                container.prefs.healthConnectEnabled.first() != healthEnabledAtStart ||
                container.prefs.healthEnergyGoalsEnabled.first() != energyEnabledAtStart
            ) {
                _ui.value = _ui.value.copy(
                    recalculatingGoals = false,
                    profile = latest ?: _ui.value.profile,
                    goalsNeedRecalc = true
                )
                return@launch
            }
            // Store the AI's full plan as a fixed snapshot: calories + all three macros. Protein is
            // the AI's choice within a range near the activity multiplier. Freezing carbs and fat too
            // means editing a profile input (weight, pace, …) no longer reshuffles macros — they only
            // change on the next Recalculate.
            val next = latest.recalculatedFromFormulas().copy(
                customCalories = result.calories,
                customProtein = result.protein,
                customCarbs = result.carbs,
                customFat = result.fat
            )
            val message = "Updated to ${result.calories} kcal." + (result.reason?.let { " $it" } ?: "")
            container.profileRepository.save(next)
            // Goals are now fresh — capture this input baseline so the recalc nudge clears.
            lastRecalcSignature = next.goalInputSignature
            container.prefs.setLastRecalcGoalSignature(next.goalInputSignature)
            // A successful manual run satisfies Adaptive's weekly check too. Without this marker,
            // the same tap could immediately trigger a second AI request through Adaptive Goals.
            adaptiveCheckDayAfterManualRecalculation(
                adaptiveEnabled = container.prefs.adaptiveGoalsEnabled.first(),
                today = LocalDate.now()
            )?.let { container.prefs.setAdaptiveGoalsLastCheckDay(it) }
            // Also AI-refresh the optional Other Nutrients; keep existing values on failure.
            try {
                val goals = container.foodAnalysis.estimateOptionalNutrientGoals(next)
                container.prefs.setOptionalNutrientGoals(goals)
                _ui.value = _ui.value.copy(optionalNutrientGoals = goals)
            } catch (_: Throwable) { /* keep existing nutrient goals */ }
            _ui.value = _ui.value.copy(
                recalculatingGoals = false,
                profile = next,
                adaptiveGoalAlertTitle = container.appContext.getString(R.string.vm_goals_recalculated),
                adaptiveGoalAlertMessage = message,
                goalsNeedRecalc = false
            )
        }
    }

    /**
     * Settings → Weight save: writes a WeightEntry (so the chart, Coach forecast,
     * and Health Connect sync see the change) and clears goalWeightKg if the new
     * current weight makes the goal direction impossible. Does NOT recompute calorie
     * or macro goals — those change only via Recalculate Goals (AI) or the weekly
     * Adaptive pass. Mirrors iOS ContentView.swift `case .editWeight`.
     */
    fun saveCurrentWeight(newKg: Double) {
        viewModelScope.launch {
            val current = container.profileRepository.current() ?: return@launch
            val gw = current.goalWeightKg
            val mismatch = gw != null && (
                (current.goal == WeightGoal.LOSE && gw >= newKg) ||
                (current.goal == WeightGoal.GAIN && gw <= newKg)
            )
            // WeightRepository.addEntry syncs profile.weightKg to the new value internally.
            container.weightRepository.addEntry(WeightEntry(weightKg = newKg))
            val refreshed = container.profileRepository.current() ?: return@launch
            val next = refreshed.copy(
                goalWeightKg = if (mismatch) null else refreshed.goalWeightKg
            )
            container.profileRepository.save(next)
            _ui.value = _ui.value.copy(profile = next, goalsNeedRecalc = needsRecalc(next))
        }
    }

    fun updateProfile(update: (com.apoorvdarshan.calorietracker.models.UserProfile) -> com.apoorvdarshan.calorietracker.models.UserProfile) {
        viewModelScope.launch {
            val current = container.profileRepository.current() ?: return@launch
            val next = update(current)
            container.profileRepository.save(next)
            _ui.value = _ui.value.copy(profile = next, goalsNeedRecalc = needsRecalc(next))
        }
    }

    /** Applies a calorie-goal edit: locked macros stay, unlocked macros rescale to the new total.
     *  Saving a value the user chose locks it (the lock icon / Reset button then releases it). */
    fun editCaloriesGoal(newCalories: Int) {
        updateProfile { it.applyCaloriesEdit(newCalories).copy(caloriesLocked = true) }
    }

    /** Applies a macro-goal edit through the rebalance engine, then locks the macro the user just
     *  set (honoring the max-2 cap — silently left unlocked if two macros are already locked).
     *  Invokes [onBlocked] and changes nothing when calories is locked and neither other macro can
     *  absorb the change (both locked). */
    fun editMacroGoal(macro: AutoBalanceMacro, newGrams: Int, onBlocked: () -> Unit) {
        viewModelScope.launch {
            val current = container.profileRepository.current() ?: return@launch
            val rebalanced = current.applyMacroEdit(macro, newGrams)
            if (rebalanced == null) {
                onBlocked()
                return@launch
            }
            val next = if (rebalanced.isMacroLocked(macro)) rebalanced else rebalanced.toggledMacroLock(macro)
            container.profileRepository.save(next)
            _ui.value = _ui.value.copy(profile = next, goalsNeedRecalc = needsRecalc(next))
        }
    }

    /** "Reset to Auto-balance" from the picker: release the macro's lock and re-derive it as the
     *  balancing remainder. */
    fun resetMacroLock(macro: AutoBalanceMacro) {
        updateProfile { it.resetMacroToBalance(macro) }
    }

    /** "Reset to Auto-balance" from the calories picker: release the calories lock and snap the
     *  total to the sum of the macros. */
    fun resetCaloriesLock() {
        updateProfile { it.resetCaloriesToBalance() }
    }

    fun setCustomBaseUrl(provider: AIProvider, url: String) {
        viewModelScope.launch {
            container.prefs.setCustomBaseUrl(provider, url.takeIf { it.isNotBlank() })
        }
    }

    private fun maskKey(key: String?): String =
        if (key.isNullOrBlank()) "" else key.take(4) + "..." + key.takeLast(4)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
