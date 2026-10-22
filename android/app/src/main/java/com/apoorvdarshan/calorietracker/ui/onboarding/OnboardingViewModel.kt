package com.apoorvdarshan.calorietracker.ui.onboarding

import android.accounts.Account
import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.backup.DriveCloudBackupClient
import com.apoorvdarshan.calorietracker.models.ActivityLevel
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.Gender
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightGoal
import com.apoorvdarshan.calorietracker.services.AndroidAppIconManager
import com.apoorvdarshan.calorietracker.services.hosted.AiAccessMode
import com.apoorvdarshan.calorietracker.services.hosted.HostedPrompt
import com.apoorvdarshan.calorietracker.ui.theme.AppThemeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

enum class OnboardingStep {
    WELCOME, GENDER, BIRTHDAY, HEIGHT_WEIGHT, BODY_FAT,
    ACTIVITY, GOAL, GOAL_WEIGHT, GOAL_SPEED,
    NOTIFICATIONS, HEALTH_CONNECT, PROVIDER,
    BUILDING_PLAN, PLAN_READY
}

/** Android onboarding AI step: honest BYOK-first choice, then key setup or a Plus/Pro plan. */
enum class OnboardingAiPhase {
    CHOICE,
    BYOK,
    HOSTED
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val gender: Gender = Gender.MALE,
    val birthday: LocalDate = LocalDate.now().minusYears(25),
    val heightCm: Int = 175,
    val weightKg: Double = 70.0,
    val bodyFatPercentage: Double? = null,
    /** Optional target body-fat fraction. Only meaningful when bodyFatPercentage
     *  is non-null (i.e. user picked "Yes I know my body fat" + opted into
     *  setting a goal). Display-only — does NOT participate in BMR/TDEE/macro math. */
    val goalBodyFatPercentage: Double? = null,
    val activity: ActivityLevel = ActivityLevel.MODERATE,
    val goal: WeightGoal = WeightGoal.MAINTAIN,
    val goalWeightKg: Double = 70.0,
    /** 0.25 (slow), 0.5 (moderate), 1.0 (fast) kg/week */
    val weeklyChangeKg: Double = 0.5,
    /** iOS defaults onboarding to Imperial; match that. Seeded from the persisted
     *  heightUnit/weightUnit prefs in init, and the single Imperial|Metric toggle
     *  writes both together so they stay coherent during onboarding. */
    val heightMetric: Boolean = false,
    val weightMetric: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val healthConnectEnabled: Boolean = false,
    val aiPhase: OnboardingAiPhase = OnboardingAiPhase.CHOICE,
    val aiProvider: AIProvider = AIProvider.GEMINI,
    val aiModel: String = AIProvider.GEMINI.defaultModel,
    val apiKey: String = "",
    /** Set when the entered key could not be stored, so onboarding can say so. */
    val apiKeyError: String? = null,
    val acceptedTerms: Boolean = false,
    /** RevenueCat reports an active Plus or Pro plan. */
    val hostedEntitled: Boolean = false,
    val submitting: Boolean = false,
    /** Manual overrides applied on the Plan Ready step. Null = use formula default. */
    val customCalories: Int? = null,
    val customProtein: Int? = null,
    val customCarbs: Int? = null,
    val customFat: Int? = null
) {
    /** PLAN_READY is the final step (the old Rate-fud review step was removed). */
    val isLastStep: Boolean get() = step == OnboardingStep.PLAN_READY

    /** True once BYOK fields satisfy the same gate as pre-choice onboarding. */
    val byokSetupComplete: Boolean
        get() = !aiProvider.requiresApiKey ||
            (apiKey.trim().isNotEmpty() && apiKeyError == null)

    /** AI is required for goal calculation, so BYOK users must enter an API key before leaving
     *  the provider step (Ollama needs none). All other steps advance freely. */
    val canAdvance: Boolean get() = when (step) {
        OnboardingStep.PROVIDER -> when (aiPhase) {
            OnboardingAiPhase.CHOICE -> false
            OnboardingAiPhase.BYOK -> byokSetupComplete
            OnboardingAiPhase.HOSTED -> hostedEntitled
        }
        else -> true
    }

    /** When returning to the provider step, reopen the path the user already set up. */
    fun aiPhaseForProviderStep(): OnboardingAiPhase = when {
        aiPhase == OnboardingAiPhase.HOSTED && hostedEntitled -> OnboardingAiPhase.HOSTED
        byokSetupComplete -> OnboardingAiPhase.BYOK
        else -> OnboardingAiPhase.CHOICE
    }

    /** How AI calls are powered once the user leaves the provider step. */
    val accessMode: AiAccessMode
        get() = if (aiPhase == OnboardingAiPhase.HOSTED) AiAccessMode.HOSTED else AiAccessMode.BYOK

    fun buildProfile(): UserProfile = UserProfile(
        gender = gender,
        birthday = birthday.atStartOfDay(ZoneId.systemDefault()).toInstant(),
        heightCm = heightCm.toDouble(),
        weightKg = weightKg,
        activityLevel = activity,
        goal = goal,
        bodyFatPercentage = bodyFatPercentage,
        goalBodyFatPercentage = if (bodyFatPercentage != null) goalBodyFatPercentage else null,
        weeklyChangeKg = if (goal == WeightGoal.MAINTAIN) null else weeklyChangeKg,
        goalWeightKg = if (goal == WeightGoal.MAINTAIN) null else goalWeightKg,
        customCalories = customCalories,
        customProtein = customProtein,
        customCarbs = customCarbs,
        customFat = customFat
    )
}

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(OnboardingState())
    val ui: StateFlow<OnboardingState> = _ui.asStateFlow()

    private val _returning = MutableStateFlow(ReturningUserState())
    /** The Welcome back screen over the WELCOME step. */
    val returning: StateFlow<ReturningUserState> = _returning.asStateFlow()

    init {
        viewModelScope.launch {
            val heightMetric = container.prefs.heightUnit.first() == "cm"
            val weightMetric = container.prefs.weightUnit.first() == "kg"
            _ui.value = _ui.value.copy(heightMetric = heightMetric, weightMetric = weightMetric)
        }
        viewModelScope.launch {
            container.billing.entitlement.collect { entitlement ->
                _ui.value = _ui.value.copy(hostedEntitled = entitlement.isEntitled)
            }
        }
    }

    /** Hosted AI card: open the hosted path, and the paywall right away if there is no plan yet. */
    fun selectHostedSetup() {
        _ui.value = _ui.value.copy(aiPhase = OnboardingAiPhase.HOSTED)
        if (!_ui.value.hostedEntitled) container.hostedUi.show(HostedPrompt.Paywall)
        viewModelScope.launch { container.billing.refreshCustomerInfo() }
    }

    fun showHostedPlans() {
        container.hostedUi.show(HostedPrompt.Paywall)
    }

    fun setGender(v: Gender) { _ui.value = _ui.value.copy(gender = v) }
    fun setBirthday(v: LocalDate) { _ui.value = _ui.value.copy(birthday = v) }
    fun setHeight(cm: Int) { _ui.value = _ui.value.copy(heightCm = cm) }
    fun setWeight(kg: Double) { _ui.value = _ui.value.copy(weightKg = kg, goalWeightKg = kg) }
    fun setBodyFat(pct: Double?) {
        // Clear the goal alongside the current value so a stale goal doesn't
        // linger when the user backs out of "Yes I know my body fat".
        _ui.value = _ui.value.copy(
            bodyFatPercentage = pct,
            goalBodyFatPercentage = if (pct == null) null else _ui.value.goalBodyFatPercentage
        )
    }
    fun setGoalBodyFat(pct: Double?) { _ui.value = _ui.value.copy(goalBodyFatPercentage = pct) }
    fun setActivity(v: ActivityLevel) { _ui.value = _ui.value.copy(activity = v) }
    fun setGoal(v: WeightGoal) {
        val defaultGoalWeight = when (v) {
            WeightGoal.LOSE -> _ui.value.weightKg - 5
            WeightGoal.GAIN -> _ui.value.weightKg + 5
            WeightGoal.MAINTAIN -> _ui.value.weightKg
        }
        _ui.value = _ui.value.copy(goal = v, goalWeightKg = defaultGoalWeight)
    }
    fun setGoalWeight(v: Double) { _ui.value = _ui.value.copy(goalWeightKg = v) }
    fun setWeeklyChange(v: Double) { _ui.value = _ui.value.copy(weeklyChangeKg = v) }
    fun setNotificationsEnabled(v: Boolean) {
        _ui.value = _ui.value.copy(notificationsEnabled = v)
    }
    fun setHealthConnectEnabled(v: Boolean) {
        _ui.value = _ui.value.copy(healthConnectEnabled = v)
    }
    fun setAiProvider(p: AIProvider) {
        // Update state synchronously so a slow prefs write can't overwrite a key the user typed
        // while the picker was open. Persist in the background for the Building Plan AI call.
        val existing = container.keyStore.apiKey(p) ?: ""
        _ui.value = _ui.value.copy(
            aiProvider = p,
            aiModel = p.defaultModel,
            apiKey = existing,
            apiKeyError = null
        )
        viewModelScope.launch {
            container.prefs.setSelectedAIProvider(p)
            container.prefs.setSelectedAIModel(p.defaultModel)
        }
    }
    fun setAiModel(m: String) {
        _ui.value = _ui.value.copy(aiModel = m)
        viewModelScope.launch { container.prefs.setSelectedAIModel(m) }
    }
    fun setApiKey(key: String) {
        _ui.value = _ui.value.copy(apiKey = key, apiKeyError = null)
        // Persist immediately so the in-onboarding AI plan calc can use it.
        viewModelScope.launch {
            val result = container.aiCredentials.setDefaultSecret(
                _ui.value.aiProvider,
                key.trim().takeIf { it.isNotBlank() }
            )
            // A rejected key (too long, or containing a space) must not be dropped
            // silently: the user would continue without a usable credential.
            result.exceptionOrNull()?.let { error ->
                _ui.value = _ui.value.copy(apiKeyError = error.message)
            }
        }
    }

    fun setAcceptedTerms(accepted: Boolean) {
        _ui.value = _ui.value.copy(acceptedTerms = accepted)
    }

    fun selectByokSetup() {
        // Flip to BYOK immediately so the form and Continue CTA can react; hydrate from prefs/keystore next.
        _ui.value = _ui.value.copy(aiPhase = OnboardingAiPhase.BYOK)
        viewModelScope.launch {
            val provider = container.prefs.selectedAIProvider.first()
            val storedModel = container.prefs.selectedAIModel.first()
            val existing = container.keyStore.apiKey(provider) ?: ""
            val current = _ui.value
            if (current.aiPhase != OnboardingAiPhase.BYOK) return@launch
            _ui.value = current.copy(
                aiProvider = provider,
                aiModel = provider.supportedModelOrDefault(storedModel),
                apiKey = existing.ifEmpty { current.apiKey }
            )
        }
    }
    /** The single Imperial|Metric segmented control writes BOTH unit prefs coherently:
     *  Imperial -> ftin + lbs, Metric -> cm + kg. */
    fun setUseMetric(v: Boolean) {
        _ui.value = _ui.value.copy(heightMetric = v, weightMetric = v)
        viewModelScope.launch {
            container.prefs.setHeightUnit(if (v) "cm" else "ftin")
            container.prefs.setWeightUnit(if (v) "kg" else "lbs")
        }
    }

    fun setCustomCalories(v: Int?) { planEdited = true; _ui.value = _ui.value.copy(customCalories = v) }
    fun setCustomProtein(v: Int?) { planEdited = true; _ui.value = _ui.value.copy(customProtein = v) }
    fun setCustomCarbs(v: Int?) { planEdited = true; _ui.value = _ui.value.copy(customCarbs = v) }
    fun setCustomFat(v: Int?) { planEdited = true; _ui.value = _ui.value.copy(customFat = v) }

    /** The user hand-tuned the plan — Adaptive Goals then stays off at completion
     *  so its first weekly run can't overwrite their numbers. */
    private var planEdited = false

    /** Building Plan step: compute calorie + macro targets with AI (forecast is null for a new
     *  user). On success seeds the custom targets the Plan Ready screen shows; on failure leaves
     *  them null so the formula values are used. Calls [onDone] either way. */
    fun buildPlanWithAI(onDone: () -> Unit) {
        viewModelScope.launch {
            val state = _ui.value
            val result = runCatching {
                container.foodAnalysis.calculateGoals(state.buildProfile(), heightMetric = state.heightMetric, weightMetric = state.weightMetric)
            }.getOrNull()
            if (result != null) {
                val carbs = maxOf(0, (result.calories - result.protein * 4 - result.fat * 9) / 4)
                _ui.value = _ui.value.copy(
                    customCalories = result.calories,
                    customProtein = result.protein,
                    customFat = result.fat,
                    customCarbs = carbs
                )
            }
            onDone()
        }
    }

    fun next() {
        if (_ui.value.step == OnboardingStep.PLAN_READY) return
        val nextStep = OnboardingStep.values().getOrNull(_ui.value.step.ordinal + 1) ?: return
        if (_ui.value.step == OnboardingStep.PROVIDER) {
            // Persist the AI mode before BUILDING_PLAN, whose goal calculation must
            // go through the path the user just chose.
            viewModelScope.launch {
                container.prefs.setAiAccessMode(_ui.value.accessMode)
                _ui.value = _ui.value.copy(step = nextStep)
            }
            return
        }
        _ui.value = _ui.value.copy(step = nextStep)
    }

    fun back() {
        if (_ui.value.step == OnboardingStep.PROVIDER && _ui.value.aiPhase != OnboardingAiPhase.CHOICE) {
            _ui.value = _ui.value.copy(aiPhase = OnboardingAiPhase.CHOICE)
            return
        }
        // PLAN_READY's previous ordinal is BUILDING_PLAN, which auto-reruns AI and can
        // overwrite edited targets — skip it and return to PROVIDER instead.
        if (_ui.value.step == OnboardingStep.PLAN_READY) {
            val state = _ui.value
            _ui.value = state.copy(
                step = OnboardingStep.PROVIDER,
                aiPhase = state.aiPhaseForProviderStep()
            )
            return
        }
        val prevStep = OnboardingStep.values().getOrNull(_ui.value.step.ordinal - 1) ?: return
        if (prevStep == OnboardingStep.BUILDING_PLAN) {
            val state = _ui.value
            _ui.value = state.copy(
                step = OnboardingStep.PROVIDER,
                aiPhase = state.aiPhaseForProviderStep()
            )
            return
        }
        _ui.value = _ui.value.copy(
            step = prevStep,
            aiPhase = if (prevStep == OnboardingStep.PROVIDER) {
                _ui.value.aiPhaseForProviderStep()
            } else {
                _ui.value.aiPhase
            }
        )
    }

    // -- Welcome back (returning users) -----------------------------------------------

    /** Opens Welcome back on its own when Health Connect grants survived (in-app Delete All
     *  Data keeps them). Runs once, after the first frame; the check is an IPC call, so it
     *  never blocks launch. Tests run without background services and never get promoted. */
    fun checkReturningUserSignal() {
        if (!container.backgroundServicesEnabled || _returning.value.promotionSettled) return
        viewModelScope.launch {
            val granted = withContext(Dispatchers.IO) {
                runCatching { container.health.isAvailable() && container.health.hasAnyPermission() }
                    .getOrDefault(false)
            }
            _returning.update { it.afterGrantCheck(granted, _ui.value.step) }
        }
    }

    fun openReturningUser() {
        _returning.update { it.opened() }
    }

    fun closeReturningUser() {
        _returning.update { it.closed() }
    }

    fun toggleReturningSource(source: ReturningSource) {
        _returning.update { it.toggled(source) }
    }

    /** Continue: runs the picked sources in order. Returns the first one, so the screen can
     *  open Google's account picker straight from the tap; Health Connect's sheet opens
     *  through [ReturningUserState.healthPermissionRequested]. */
    fun startReturningRun(): ReturningSource? {
        val state = _returning.value
        if (!state.canContinue) return null
        val started = state.started()
        _returning.value = started
        return started.steps.firstOrNull()?.source
    }

    /** Start fresh: skips both and runs the normal onboarding from its first question. */
    fun startFresh() {
        if (_returning.value.phase != ReturningPhase.SELECTING) return
        _returning.update { it.closed() }
        if (_ui.value.step == OnboardingStep.WELCOME) next()
    }

    /** The finished run's Continue: into the restored app, or on to the short setup. */
    fun finishReturning() {
        val state = _returning.value
        if (state.phase != ReturningPhase.FINISHED || state.restartRequested) return
        if (state.restoredSetup) {
            _returning.update { it.copy(restartRequested = true) }
        } else {
            _returning.update { it.copy(visible = false) }
            if (_ui.value.step == OnboardingStep.WELCOME) next()
        }
    }

    fun retryReturning() {
        _returning.update { it.retried() }
    }

    // Google Drive: account picker (on the screen) -> authorize -> consent if needed -> lookup
    // -> confirm -> restore. Authorization runs here, not in the screen's scope, so recreating
    // the Activity mid-call can't strand the step; the Activity is only held for that call.

    fun driveSignInCancelled() {
        viewModelScope.launch { endDrive(ReturningStepStatus.SKIPPED, ReturningMessage.DRIVE_CANCELLED) }
    }

    fun driveSignInFailed() {
        viewModelScope.launch { endDrive(ReturningStepStatus.FAILED, ReturningMessage.SIGN_IN_FAILED) }
    }

    fun authorizeDrive(activity: Activity, account: Account) {
        viewModelScope.launch {
            val outcome = try {
                container.cloudBackup.authorize(activity, account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            when (outcome) {
                is DriveCloudBackupClient.AuthOutcome.Token -> lookUpDriveBackup()
                is DriveCloudBackupClient.AuthOutcome.Resolution ->
                    _returning.update { it.copy(driveConsent = outcome.intentSender) }
                null -> endDrive(ReturningStepStatus.FAILED, ReturningMessage.SIGN_IN_FAILED)
            }
        }
    }

    fun driveConsentLaunched() {
        _returning.update { it.copy(driveConsent = null) }
    }

    fun finishDriveAuthorization(activity: Activity, data: Intent?) {
        viewModelScope.launch {
            val ok = try {
                container.cloudBackup.finishAuthorization(activity, data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (ok) lookUpDriveBackup() else endDrive(ReturningStepStatus.FAILED, ReturningMessage.SIGN_IN_FAILED)
        }
    }

    /** Signed in to Google: find the backup without restoring or uploading anything. */
    private suspend fun lookUpDriveBackup() {
        container.cloudBackup.findBackup()
            .onSuccess { file ->
                if (file == null) {
                    endDrive(ReturningStepStatus.FAILED, ReturningMessage.NO_BACKUP)
                } else {
                    _returning.update { it.copy(confirmingRestore = true, backupModifiedAt = file.modifiedTime) }
                }
            }
            .onFailure { endDrive(ReturningStepStatus.FAILED, returningDriveMessage(it)) }
    }

    fun cancelDriveRestore() {
        if (!_returning.value.confirmingRestore) return
        _returning.update { it.copy(confirmingRestore = false) }
        viewModelScope.launch { endDrive(ReturningStepStatus.SKIPPED, ReturningMessage.DRIVE_CANCELLED) }
    }

    /**
     * Restores the Drive backup over this (still empty) phone. Backup is never turned on
     * before this succeeds: an upload first would replace the real backup with nothing.
     * [OnboardingViewModel.complete] never runs afterwards, since it would overwrite the
     * restored profile, AI and goal settings.
     */
    fun restoreDriveBackup() {
        if (!_returning.value.confirmingRestore) return
        _returning.update { it.restoring() }
        viewModelScope.launch {
            val result = container.cloudBackup.restoreNow()
            val failure = result.exceptionOrNull()
            if (failure != null) {
                // Nothing local changed before the failure; a selected Health Connect
                // import still runs next.
                endDrive(ReturningStepStatus.FAILED, returningDriveMessage(failure))
                return@launch
            }
            val prefs = container.prefs
            // What FudAIApp.onCreate runs once per process: the backup may select a local
            // model this phone hasn't downloaded, or predate a model or speech migration.
            prefs.reconcileLocalModelSelections()
            prefs.migrateAIModelSelections()
            prefs.migrateMatchingSpeechProviderIfNeeded()
            AndroidAppIconManager.apply(container.appContext, AppThemeColor.fromKey(prefs.appThemeColor.first()))
            runCatching { container.rescheduleReminders() }
            // A backup taken mid-setup can hold a profile without a finished onboarding, and
            // Home can't open without a profile (the splash would wait for one forever). Only
            // both together skip setup; otherwise the short setup finishes over the restored data.
            val hasSetup = container.profileRepository.current() != null &&
                prefs.hasCompletedOnboarding.first()
            if (!hasSetup) {
                prefs.setOnboardingCompleted(false)
                val heightMetric = prefs.heightUnit.first() == "cm"
                val weightMetric = prefs.weightUnit.first() == "kg"
                _ui.update { it.copy(heightMetric = heightMetric, weightMetric = weightMetric) }
            }
            _returning.update {
                it.copy(restoredBackup = true, restoredSetup = hasSetup).finished(
                    ReturningSource.DRIVE,
                    ReturningStepStatus.DONE,
                    if (hasSetup) null else ReturningMessage.SETUP_MISSING
                )
            }
        }
    }

    /** A Drive step that didn't restore: backup stays off and the account the user signed in
     *  with is forgotten, so Settings shows backup as never set up and nothing can upload
     *  over the Drive copy later. The next picked source runs either way. */
    private suspend fun endDrive(status: ReturningStepStatus, message: ReturningMessage) {
        runCatching { container.cloudBackup.forgetSession() }
        _returning.update { it.finished(ReturningSource.DRIVE, status, message) }
    }

    // Health Connect: sheet (on the screen) -> import -> summary.

    fun healthPermissionLaunched() {
        _returning.update { it.copy(healthPermissionRequested = false) }
    }

    fun healthUnavailable() {
        _returning.update {
            it.copy(healthPermissionRequested = false)
                .finished(ReturningSource.HEALTH, ReturningStepStatus.FAILED, ReturningMessage.HEALTH_UNAVAILABLE)
        }
    }

    /**
     * Health Connect sheet answered: import what Fud AI saved there. After a restore in this
     * run the import only adds what the backup lacks; otherwise it is the regular sync, and
     * the newest weight and body fat then pre-fill the short setup.
     */
    fun importFromHealthConnect(anyGranted: Boolean) {
        if (_returning.value.step(ReturningSource.HEALTH)?.status != ReturningStepStatus.WORKING) return
        if (!anyGranted) {
            _returning.update {
                it.finished(ReturningSource.HEALTH, ReturningStepStatus.FAILED, ReturningMessage.HEALTH_DENIED)
            }
            return
        }
        // complete() writes this flag, so the state must agree or it would switch sync off.
        setHealthConnectEnabled(true)
        viewModelScope.launch {
            container.prefs.setHealthConnectEnabled(true)
            val afterRestore = _returning.value.restoredBackup
            // Nothing can be logged before onboarding, so without a restore everything in the
            // stores came from Health Connect, including what Settings' own reconcile already
            // brought in this session (imports are deduplicated, so running again is safe).
            val before = if (afterRestore) healthRecordCounts() else HealthRecordCounts()
            val imported = runCatching {
                if (afterRestore) container.importHealthConnectAddingOnly()
                else container.syncHealthConnectReadsAndWait()
            }
            imported.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (imported.getOrNull() != true) {
                // A failed read or write: say so and offer Try again instead of a summary
                // built from whatever made it in. Imports are deduplicated, so a retry is safe.
                _returning.update {
                    it.finished(ReturningSource.HEALTH, ReturningStepStatus.FAILED, ReturningMessage.HEALTH_READ_FAILED)
                }
                return@launch
            }
            val added = healthRecordCounts() - before
            if (!_returning.value.restoredSetup) {
                container.weightRepository.entries.first().maxByOrNull { it.date }?.let { latest ->
                    onboardingWeightFromReading(latest.weightKg, _ui.value.weightMetric)?.let(::setWeight)
                }
                container.bodyFatRepository.entries.first().maxByOrNull { it.date }?.let { latest ->
                    onboardingBodyFatFromReading(latest.bodyFatFraction)?.let(::setBodyFat)
                }
            }
            _returning.update {
                it.copy(healthAdded = added).finished(ReturningSource.HEALTH, ReturningStepStatus.DONE)
            }
        }
    }

    private suspend fun healthRecordCounts() = HealthRecordCounts(
        meals = container.foodRepository.entries.first().size,
        weighIns = container.weightRepository.entries.first().size,
        other = container.bodyFatRepository.entries.first().size +
            container.workoutRepository.completedSessions.first().size +
            container.importedHealthWorkoutRepository.workouts.first().size
    )

    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(submitting = true)
            val state = _ui.value
            val profile = state.buildProfile()
            container.profileRepository.save(profile)
            container.weightRepository.seedInitialWeightIfEmpty(profile.weightKg)
            // Only seed body fat when the user actually entered one in onboarding
            // (the "Yes I know my body fat %" branch); the "No" branch leaves
            // bodyFatPercentage null and the store stays empty.
            profile.bodyFatPercentage?.let {
                container.bodyFatRepository.seedInitialBodyFatIfEmpty(it)
            }
            container.prefs.setNotificationsEnabled(state.notificationsEnabled)
            container.prefs.setHealthConnectEnabled(state.healthConnectEnabled)
            container.prefs.setAiAccessMode(state.accessMode)
            container.prefs.setSelectedAIProvider(state.aiProvider)
            container.prefs.setSelectedAIModel(state.aiModel)
            if (state.apiKey.isNotBlank()) {
                container.aiCredentials.setDefaultSecret(state.aiProvider, state.apiKey.trim())
            }
            // Hosted users keep the default on-device speech recognizer (free); a remote
            // speech provider would need a BYOK key.
            if (state.accessMode == AiAccessMode.BYOK) {
                container.prefs.setInitialSpeechProviderForAIProvider(state.aiProvider)
            }
            // New installs start with Energy Burn on, and Adaptive Goals on unless the
            // user hand-tuned their plan (adaptive would overwrite it). Existing users are
            // untouched — these prefs are only written here and by the Settings toggles.
            // Onboarding just calculated goals, so stamp the weekly adaptive check as done;
            // the first auto-run lands next week.
            container.prefs.setAdaptiveGoalsEnabled(!planEdited)
            container.prefs.setHealthEnergyGoalsEnabled(true)
            container.prefs.setAdaptiveGoalsLastCheckDay(LocalDate.now().toString())
            container.prefs.setOnboardingCompleted(true)
            onDone()
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OnboardingViewModel(container) as T
    }
}
