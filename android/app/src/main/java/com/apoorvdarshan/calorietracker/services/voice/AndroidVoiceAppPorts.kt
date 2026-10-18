package com.apoorvdarshan.calorietracker.services.voice

import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.data.ExerciseItem
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.models.AutoBalanceMacro
import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.CurrentMealSchedule
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WorkoutDate
import com.apoorvdarshan.calorietracker.models.WorkoutDayPlan
import com.apoorvdarshan.calorietracker.models.WorkoutPersistedState
import com.apoorvdarshan.calorietracker.models.WorkoutTextDraft
import com.apoorvdarshan.calorietracker.models.WorkoutTextExercise
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import com.apoorvdarshan.calorietracker.services.AndroidAppIconManager
import com.apoorvdarshan.calorietracker.services.BarcodeProductLookup
import com.apoorvdarshan.calorietracker.services.ai.CoachTools
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.services.hosted.AiAccessMode
import com.apoorvdarshan.calorietracker.services.hosted.HostedAiConstants
import com.apoorvdarshan.calorietracker.ui.components.autoSaveMealPhotoIfEnabled
import com.apoorvdarshan.calorietracker.ui.navigation.FudAIRoutes
import com.apoorvdarshan.calorietracker.ui.theme.AppThemeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the tools need from the assistant's on-screen UI (the controller provides it). */
interface VoiceUiBridge {
    /** Shrinks the assistant to its bubble so a screen the tools opened is visible. */
    fun collapseForNavigation()
    fun cameraMode(): VoiceCameraMode?
    suspend fun openCamera(mode: VoiceCameraMode): CameraOutcome
    fun closeCamera()
    /** Captures once and closes only that camera, without ending the voice session. */
    suspend fun takePhoto(): PhotoOutcome
    suspend fun scanBarcode(): BarcodeOutcome
    suspend fun pickPhotos(maxCount: Int): PickOutcome
}

/** The voice tools against the real app: repositories, AI pipelines, navigation and settings. */
class AndroidVoiceAppPorts(
    private val container: AppContainer,
    private val ui: VoiceUiBridge
) : VoiceAppPorts {
    private val navigator get() = container.navigator
    private val prefs get() = container.prefs

    /** Photos stored for this session's logs; saved to the gallery once logged, like Home. */
    private val freshImages = ConcurrentHashMap.newKeySet<String>()

    fun clearSessionImages() = freshImages.clear()

    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun mealForTime(time: LocalTime): MealType = CurrentMealSchedule.value.mealTypeAt(time)

    // MARK: - Navigation

    override fun screenState(): ScreenState {
        val route = navigator.currentRoute.value
        val screen = when (FudAIRoutes.selectedBottomTab(route)) {
            FudAIRoutes.HOME -> VoiceScreen.HOME
            FudAIRoutes.PROGRESS -> VoiceScreen.PROGRESS
            FudAIRoutes.COACH -> VoiceScreen.COACH
            FudAIRoutes.WORKOUTS -> VoiceScreen.WORKOUTS
            FudAIRoutes.SETTINGS -> VoiceScreen.SETTINGS
            else -> null
        }
        val section = if (screen == VoiceScreen.SETTINGS) navigator.settingsSection.value else null
        return ScreenState(screen, section)
    }

    override fun diaryDate(): LocalDate = navigator.homeDiaryDate.value
    override fun workoutDiaryDate(): LocalDate? = navigator.workoutDiaryDate.value

    override fun hiddenScreens(): Set<VoiceScreen> {
        val hidden = navigator.hiddenTabs.value
        return VoiceScreen.entries.filter { FudAIRoutes.isHiddenTab(it.wireValue, hidden) }.toSet()
    }

    override fun showScreen(screen: VoiceScreen, section: VoiceSettingsSection?) {
        ui.collapseForNavigation()
        when (screen) {
            VoiceScreen.HOME -> navigator.showTab(FudAIRoutes.HOME)
            VoiceScreen.PROGRESS -> navigator.showTab(FudAIRoutes.PROGRESS)
            VoiceScreen.COACH -> navigator.showTab(FudAIRoutes.COACH)
            VoiceScreen.WORKOUTS -> navigator.showTab(FudAIRoutes.WORKOUTS)
            VoiceScreen.SETTINGS -> navigator.showSettings(section)
        }
    }

    override fun showDiaryDate(date: LocalDate) {
        ui.collapseForNavigation()
        navigator.showHomeDate(date)
    }

    override fun showFoodEntry(entry: FoodEntry) {
        ui.collapseForNavigation()
        navigator.showFoodEntry(entry.id, entry.timestamp.atZone(zone()).toLocalDate())
    }

    // MARK: - Camera and photos

    override fun cameraMode(): VoiceCameraMode? = ui.cameraMode()
    override suspend fun openCamera(mode: VoiceCameraMode): CameraOutcome = ui.openCamera(mode)
    override fun closeCamera() = ui.closeCamera()
    override suspend fun takePhoto(): PhotoOutcome = ui.takePhoto()
    override suspend fun scanBarcode(): BarcodeOutcome = ui.scanBarcode()
    override suspend fun pickPhotos(maxCount: Int): PickOutcome = ui.pickPhotos(maxCount)

    // MARK: - Food

    override suspend fun profile(): UserProfile? = container.profileRepository.current()

    override suspend fun foodEntries(): List<FoodEntry> = container.foodRepository.entries.first()

    override suspend fun maxFoodPhotos(): Int =
        if (prefs.foodAiCallSettings(forImages = true).accessMode == AiAccessMode.HOSTED) HostedAiConstants.MAX_IMAGES else 4

    override suspend fun addFoodEntry(entry: FoodEntry): Boolean {
        if (!container.foodRepository.addEntry(entry)) return false
        entry.allImageFilenames.forEach { filename ->
            if (freshImages.remove(filename)) {
                val bytes = withContext(Dispatchers.IO) { container.imageStore.loadBytes(filename) }
                bytes?.let { autoSaveMealPhotoIfEnabled(container.appContext, it) }
            }
        }
        return true
    }

    override suspend fun updateFoodEntry(entry: FoodEntry) = container.foodRepository.updateEntry(entry)

    override suspend fun deleteFoodEntry(id: UUID) = container.foodRepository.deleteEntry(id)

    override suspend fun savedFoods(): List<SavedFood> {
        val repository = container.foodRepository
        val favorites = repository.migratedFavorites().map { SavedFood(it, SavedFoodKind.FAVORITE) }
        val frequent = repository.frequent(days = 90).map { SavedFood(it.template, SavedFoodKind.FREQUENT, it.count) }
        val recent = repository.recent(days = 30).map { SavedFood(it, SavedFoodKind.RECENT) }
        return favorites + frequent + recent
    }

    override suspend fun storeFoodImages(images: List<ByteArray>): List<String> {
        val stored = mutableListOf<String>()
        try {
            return withContext(Dispatchers.IO) {
                images.forEach { bytes ->
                    val id = UUID.randomUUID()
                    val filename = container.imageStore.storeBytes(bytes, id)
                    if (filename == null) container.imageStore.delete("$id.jpg")
                    else {
                        stored += filename
                        freshImages.add(filename)
                    }
                }
                stored.toList()
            }
        } catch (error: Throwable) {
            // withContext may be cancelled after writing files, before returning their names.
            discardFoodImages(stored)
            throw error
        }
    }

    override suspend fun discardFoodImages(filenames: List<String>) = withContext(NonCancellable + Dispatchers.IO) {
        // A write can commit before a later step throws: preserve every persisted owner.
        val referenced = prefs.foodImageReferenceFilenames() ?: return@withContext
        filenames.forEach { filename ->
            freshImages.remove(filename)
            if (filename !in referenced) container.imageStore.delete(filename)
        }
    }

    override suspend fun analyzeFoodPhotos(images: List<ByteArray>, note: String?): FoodAnalysis =
        container.foodAnalysis.analyzeFood(images, note?.takeIf { it.isNotBlank() }, progressiveMeal = false)

    override suspend fun analyzeFoodText(description: String): FoodAnalysis =
        container.foodAnalysis.analyzeText(description)

    /** Same order as the Home scan: Open Food Facts, the saved label (#485), then USDA (#533). */
    override suspend fun lookupBarcode(barcode: String): BarcodeProduct = BarcodeProduct.from(
        BarcodeProductLookup.lookup(
            barcode = barcode,
            usdaApiKey = container.keyStore.usdaFoodDataCentralApiKey(),
            savedLabelEntries = { container.foodRepository.entries.first() }
        )
    )

    // MARK: - Water, fasting and body

    override suspend fun trackingPrefs(): TrackingPrefs = TrackingPrefs(
        waterEnabled = prefs.waterTrackingEnabled.first(),
        waterGoalMl = prefs.waterDailyGoalMl.first(),
        waterUnit = prefs.waterUnit.first(),
        fastingEnabled = prefs.fastingTrackingEnabled.first(),
        weightMetric = prefs.weightUnit.first() == "kg"
    )

    override suspend fun waterEntries(): List<WaterEntry> = container.waterRepository.entries.first()
    override suspend fun addWater(entry: WaterEntry) = container.waterRepository.add(entry)
    override suspend fun deleteWater(id: UUID) = container.waterRepository.delete(id)

    override suspend fun activeFast(): FastingSession? = container.fastingRepository.active()

    override suspend fun startFast(goalMinutes: Int): FastingSession? =
        container.fastingRepository.start(goalMinutes)?.also { syncFastingNotification() }

    override suspend fun endFast(): FastingSession? =
        container.fastingRepository.endActive()?.also { container.notifications.cancelFastingGoal() }

    override suspend fun reopenFast(session: FastingSession): Boolean =
        container.fastingRepository.update(session.copy(endedAt = null)).also { syncFastingNotification() }

    override suspend fun deleteFast(id: UUID) {
        container.fastingRepository.delete(id)
        syncFastingNotification()
    }

    /** Same rule as Home: the goal alarm follows the running fast when its notification is on. */
    private suspend fun syncFastingNotification() {
        val notify = prefs.notificationsEnabled.first() &&
            prefs.fastingTrackingEnabled.first() &&
            prefs.fastingGoalNotificationEnabled.first() &&
            container.notifications.canPostNotifications()
        if (notify) {
            container.notifications.scheduleFastingGoal(container.fastingRepository.active())
        } else {
            container.notifications.cancelFastingGoal()
        }
    }

    override suspend fun addWeight(entry: WeightEntry) {
        val reached = container.weightRepository.addEntry(entry)
        // Same celebration path as Progress.
        if (reached != null && prefs.notificationsEnabled.first() && prefs.goalReachedNotificationsEnabled.first()) {
            container.notifications.showGoalReached()
        }
    }

    override suspend fun deleteWeight(id: UUID) = container.weightRepository.deleteEntry(id)
    override suspend fun addBodyFat(entry: BodyFatEntry) = container.bodyFatRepository.addEntry(entry)
    override suspend fun deleteBodyFat(id: UUID) = container.bodyFatRepository.deleteEntry(id)

    // MARK: - Workouts

    /** Android has no switch for the workout diary: it is always on. */
    override fun workoutDiaryEnabled(): Boolean = true

    private suspend fun library(): List<ExerciseItem> = withContext(Dispatchers.IO) {
        ExerciseRepository.get(container.appContext).exercises
    }

    private suspend fun workoutUnit(): WorkoutWeightUnit =
        if (prefs.weightUnit.first() == "kg") WorkoutWeightUnit.KG else WorkoutWeightUnit.LBS

    private suspend fun bodyWeightKg(): Double =
        profile()?.weightKg?.takeIf { it.isFinite() && it > 0 } ?: 70.0

    override suspend fun describeWorkout(description: String, date: LocalDate): WorkoutTextDraft =
        container.foodAnalysis.analyzeWorkout(description, date, workoutUnit(), library())

    override suspend fun saveWorkout(draft: WorkoutTextDraft): WorkoutWrite {
        val library = library()
        val planned = draft.planned(library)
        val date = LocalDate.parse(draft.date)
        val before = container.workoutRepository.snapshot()
        container.workoutRepository.addTextWorkout(draft, library)
        return WorkoutWrite(
            name = planned.joinToString(", ") { it.name },
            exerciseCount = planned.size,
            date = date,
            caloriesBurned = null,
            restore = { removeWorkoutExercises(date, planned.map { it.id }, before) }
        )
    }

    override suspend fun logCardio(activity: String, minutes: Int, calories: Int?, date: LocalDate): WorkoutWrite {
        val library = library()
        val unit = workoutUnit()
        val match = cardioMatch(activity, library)
        val draft = WorkoutTextDraft(
            date = date.toString(),
            exercises = listOf(
                WorkoutTextExercise(
                    exerciseId = match?.id,
                    name = match?.name ?: activity.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    minutes = minutes.toString(),
                    unit = unit.storageValue,
                    intensity = "moderate"
                )
            )
        )
        val planned = draft.planned(library)
        val before = container.workoutRepository.snapshot()
        val repository = container.workoutRepository
        repository.addTextWorkout(draft, library)
        val bodyWeight = bodyWeightKg()
        val session = if (calories != null) {
            // The burn is one snapshot for the whole day: the rest of the day's estimate plus the given calories.
            val snapshot = repository.snapshot()
            val others = snapshot.dayPlans[WorkoutDate.key(date)]?.exercises.orEmpty()
                .filterNot { exercise -> planned.any { it.id == exercise.id } }
            val othersBurn = repository.estimateBurn(others, bodyWeight, unit, snapshot.preferences.rpeScale)?.calories ?: 0
            repository.upsertCalculatedWorkout(date, othersBurn + calories, unit)
        } else {
            repository.calculateBurn(date, bodyWeight, unit)
        }
        return WorkoutWrite(
            name = planned.first().name,
            exerciseCount = 1,
            date = date,
            caloriesBurned = session?.caloriesBurned,
            restore = { removeWorkoutExercises(date, planned.map { it.id }, before) }
        )
    }

    /** Preserve the user's actual saved calories, which can differ from an estimated burn. */
    private suspend fun removeWorkoutExercises(date: LocalDate, ids: List<UUID>, before: WorkoutPersistedState) {
        val repository = container.workoutRepository
        ids.forEach { repository.removeExercise(it, date, container.imageStore) }
        val key = WorkoutDate.key(date)
        val previous = before.completedSessions.filter { it.diaryDateKey == key && it.caloriesBurned != null }
            .maxByOrNull { it.completedAt }
        if (previous != null) {
            val restored = repository.restoreBurnAfterUndo(previous, before.dayPlans[key] ?: WorkoutDayPlan(key))
            // A diary edit made since the voice log stays intact and gets a fresh estimate.
            if (!restored) repository.calculateBurn(date, bodyWeightKg(), workoutUnit())
        }
    }

    // MARK: - Settings

    override suspend fun settings(provider: LiveProvider): VoiceSettingsSnapshot {
        val profile = profile()
        val voice = prefs.voiceAssistantPreferences.first()
        return VoiceSettingsSnapshot(
            themeColor = AppThemeColor.fromKey(prefs.appThemeColor.first()).key,
            appearance = prefs.appearanceMode.first(),
            metric = prefs.weightUnit.first() == "kg",
            calorieGoal = profile?.effectiveCalories,
            proteinGoalG = profile?.effectiveProtein,
            carbsGoalG = profile?.effectiveCarbs,
            fatGoalG = profile?.effectiveFat,
            waterGoalMl = prefs.waterDailyGoalMl.first(),
            voice = voice.voice(provider),
            voices = provider.voices,
            autoCapture = voice.autoCapture,
            confirmSaves = voice.confirmSaves,
            adaptiveGoals = prefs.adaptiveGoalsEnabled.first()
        )
    }

    override suspend fun applySetting(key: VoiceSettingKey, value: SettingValue, provider: LiveProvider): SettingApplied {
        val context = container.appContext
        return when (key) {
            VoiceSettingKey.THEME_COLOR -> {
                val color = (value as SettingValue.ThemeColor).color
                val previous = AppThemeColor.fromKey(prefs.appThemeColor.first())
                prefs.setAppThemeColor(color.key)
                AndroidAppIconManager.apply(context, color)
                SettingApplied.Done(restore = {
                    prefs.setAppThemeColor(previous.key)
                    AndroidAppIconManager.apply(context, previous)
                })
            }
            VoiceSettingKey.APPEARANCE -> {
                val previous = prefs.appearanceMode.first()
                prefs.setAppearanceMode((value as SettingValue.Appearance).mode)
                SettingApplied.Done(restore = { prefs.setAppearanceMode(previous) })
            }
            VoiceSettingKey.UNITS -> {
                val metric = (value as SettingValue.Units).metric
                val height = prefs.heightUnit.first()
                val weight = prefs.weightUnit.first()
                val water = prefs.waterUnit.first()
                val useMetric = prefs.useMetric.first()
                prefs.setHeightUnit(if (metric) "cm" else "ftin")
                prefs.setWeightUnit(if (metric) "kg" else "lbs")
                prefs.setWaterUnit(if (metric) WaterUnit.MILLILITERS else WaterUnit.FLUID_OUNCES)
                prefs.setUseMetric(metric)
                SettingApplied.Done(restore = {
                    prefs.setHeightUnit(height)
                    prefs.setWeightUnit(weight)
                    prefs.setWaterUnit(water)
                    prefs.setUseMetric(useMetric)
                })
            }
            VoiceSettingKey.CALORIE_GOAL -> {
                val profile = profile() ?: return noProfile()
                val calories = (value as SettingValue.Amount).value
                val next = profile.applyCaloriesEdit(calories).copy(caloriesLocked = true)
                container.profileRepository.save(next)
                SettingApplied.Done(restore = {
                    prefs.updateUserProfile { current -> restoreVoiceGoalEdit(current, profile, next) }
                })
            }
            VoiceSettingKey.PROTEIN_GOAL_G, VoiceSettingKey.CARBS_GOAL_G, VoiceSettingKey.FAT_GOAL_G -> {
                val profile = profile() ?: return noProfile()
                val macro = when (key) {
                    VoiceSettingKey.PROTEIN_GOAL_G -> AutoBalanceMacro.PROTEIN
                    VoiceSettingKey.CARBS_GOAL_G -> AutoBalanceMacro.CARBS
                    else -> AutoBalanceMacro.FAT
                }
                // Same rebalance as the Settings picker: the edited macro is locked afterwards.
                val rebalanced = profile.applyMacroEdit(macro, (value as SettingValue.Amount).value)
                    ?: return SettingApplied.Rejected(
                        ToolError.INVALID_ARGUMENTS,
                        "Calories and the other macros are locked, so this macro can't change. The user can unlock one in Settings, Goals & Nutrition."
                    )
                val next = if (rebalanced.isMacroLocked(macro)) rebalanced else rebalanced.toggledMacroLock(macro)
                container.profileRepository.save(next)
                SettingApplied.Done(restore = {
                    prefs.updateUserProfile { current -> restoreVoiceGoalEdit(current, profile, next) }
                })
            }
            VoiceSettingKey.WATER_GOAL_ML -> {
                val previous = prefs.waterDailyGoalMl.first()
                prefs.setWaterDailyGoalMl((value as SettingValue.Amount).value)
                SettingApplied.Done(restore = { prefs.setWaterDailyGoalMl(previous) })
            }
            VoiceSettingKey.ASSISTANT_VOICE -> {
                val previous = prefs.voiceAssistantPreferences.first().voice(provider)
                prefs.setVoiceAssistantVoice(provider, (value as SettingValue.Voice).id)
                SettingApplied.Done(
                    restore = { prefs.setVoiceAssistantVoice(provider, previous) },
                    note = "The new voice is used from the next conversation."
                )
            }
            VoiceSettingKey.ASSISTANT_AUTO_CAPTURE -> {
                val previous = prefs.voiceAssistantPreferences.first().autoCapture
                prefs.setVoiceAssistantAutoCapture((value as SettingValue.Toggle).on)
                SettingApplied.Done(restore = { prefs.setVoiceAssistantAutoCapture(previous) })
            }
            VoiceSettingKey.ASSISTANT_CONFIRM_SAVES -> {
                val previous = prefs.voiceAssistantPreferences.first().confirmSaves
                prefs.setVoiceAssistantConfirmSaves((value as SettingValue.Toggle).on)
                SettingApplied.Done(restore = { prefs.setVoiceAssistantConfirmSaves(previous) })
            }
        }
    }

    private fun noProfile() = SettingApplied.Rejected(ToolError.UNSUPPORTED, "Goals need a finished profile first.")

    // MARK: - Coach data

    override suspend fun coachTools(): CoachTools {
        val workouts = container.workoutRepository.snapshot()
        return CoachTools(
            weights = container.weightRepository.entries.first(),
            bodyFats = container.bodyFatRepository.entries.first(),
            foods = foodEntries(),
            fastingSessions = container.fastingRepository.sessions.first(),
            workoutSessions = workouts.completedSessions,
            workoutPlans = workouts.dayPlans.values.toList(),
            workoutPreferences = workouts.preferences,
            workoutPlanWeightUnit = workoutUnit(),
            exerciseLibrary = withContext(Dispatchers.IO) {
                ExerciseRepository.get(container.appContext)
                    .includingActivities(workouts.customActivities + workouts.userExercises)
                    .exercises
            }
        )
    }

    companion object {
        /** Everyday cardio words to the catalog's outdoor/common entries. */
        private val CARDIO_IDS = mapOf(
            "walk" to "Walking_Outdoor",
            "walking" to "Walking_Outdoor",
            "run" to "Running_Outdoor",
            "running" to "Running_Outdoor",
            "jog" to "Running_Outdoor",
            "jogging" to "Running_Outdoor"
        )

        /** The catalog exercise for a cardio activity, or null to log it as a custom timed activity. */
        internal fun cardioMatch(activity: String, library: List<ExerciseItem>): ExerciseItem? {
            val wanted = activity.trim().lowercase(Locale.ROOT)
            CARDIO_IDS[wanted]?.let { id -> library.firstOrNull { it.id == id }?.let { return it } }
            val cardio = library.filter { it.category.equals("cardio", ignoreCase = true) }
            val stem = wanted.removeSuffix("ing").removeSuffix("e")
            return cardio.firstOrNull { it.name.equals(activity.trim(), ignoreCase = true) }
                ?: cardio.firstOrNull { it.name.lowercase(Locale.ROOT).contains(wanted) }
                ?: cardio.firstOrNull { stem.length >= 3 && it.name.lowercase(Locale.ROOT).contains(stem) }
        }
    }
}
