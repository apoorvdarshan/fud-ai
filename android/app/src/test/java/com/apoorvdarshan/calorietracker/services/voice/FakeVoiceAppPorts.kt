package com.apoorvdarshan.calorietracker.services.voice

import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WorkoutTextDraft
import com.apoorvdarshan.calorietracker.services.ai.CoachTools
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.ui.theme.AppThemeColor
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** An in-memory app for the voice tools. */
class FakeVoiceAppPorts : VoiceAppPorts {
    var instant: Instant = Instant.parse("2026-10-16T12:30:00Z")
    val zoneId: ZoneId = ZoneOffset.UTC

    val foods = mutableListOf<FoodEntry>()
    val water = mutableListOf<WaterEntry>()
    val weights = mutableListOf<WeightEntry>()
    val bodyFats = mutableListOf<BodyFatEntry>()
    val fasts = mutableListOf<FastingSession>()
    val saved = mutableListOf<SavedFood>()
    val storedImages = mutableListOf<String>()
    val shownScreens = mutableListOf<Pair<VoiceScreen, VoiceSettingsSection?>>()
    var hiddenScreens: Set<VoiceScreen> = emptySet()
    var shownEntry: FoodEntry? = null
    var barcodeProduct: BarcodeProduct? = null
    var diary: LocalDate = LocalDate.parse("2026-10-16")
    var fastingEnabled = true
    var waterEnabled = true
    var metric = true
    var camera: VoiceCameraMode? = null
    var cameraOutcome: CameraOutcome = CameraOutcome.Opened
    var photo: PhotoOutcome = PhotoOutcome.Taken(byteArrayOf(1, 2, 3))
    var barcode: BarcodeOutcome = BarcodeOutcome.Scanned("737628064502")
    var picked: PickOutcome = PickOutcome.Picked(listOf(byteArrayOf(4, 5)))
    var maxPhotos = 4
    var requestedMaxPhotos: Int? = null
    var analyzedPhotoCount = 0
    var foodWriteGate: (suspend () -> Unit)? = null
    var foodWriteError: Throwable? = null
    var waterWriteError: Throwable? = null
    var analysis: FoodAnalysis = FoodAnalysis(
        name = "Eggs and toast",
        calories = 320,
        protein = 18.0,
        carbs = 24.0,
        fat = 15.0,
        servingSizeGrams = 180.0
    )
    var analysisError: Throwable? = null
    var profile: UserProfile? = null
    var settingsValues = mutableMapOf<VoiceSettingKey, String>()
    var adaptiveGoals = false
    var workoutDraft: WorkoutTextDraft? = null
    val workoutWrites = mutableListOf<String>()

    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
    override fun mealForTime(time: LocalTime): MealType = when {
        time.hour < 11 -> MealType.BREAKFAST
        time.hour < 16 -> MealType.LUNCH
        else -> MealType.DINNER
    }

    override fun screenState() = ScreenState(VoiceScreen.HOME, null)
    override fun diaryDate(): LocalDate = diary
    override fun workoutDiaryDate(): LocalDate? = null
    override fun showScreen(screen: VoiceScreen, section: VoiceSettingsSection?) {
        shownScreens += screen to section
    }
    override fun hiddenScreens(): Set<VoiceScreen> = hiddenScreens
    override fun showDiaryDate(date: LocalDate) {
        diary = date
    }
    override fun showFoodEntry(entry: FoodEntry) {
        shownEntry = entry
    }

    override fun cameraMode(): VoiceCameraMode? = camera
    override suspend fun openCamera(mode: VoiceCameraMode): CameraOutcome {
        if (cameraOutcome == CameraOutcome.Opened) camera = mode
        return cameraOutcome
    }
    override fun closeCamera() {
        camera = null
    }
    override suspend fun takePhoto(): PhotoOutcome {
        if (camera == null) return PhotoOutcome.NotOpen
        return photo.also { closeCamera() }
    }
    override suspend fun scanBarcode(): BarcodeOutcome = barcode
    override suspend fun pickPhotos(maxCount: Int): PickOutcome {
        requestedMaxPhotos = maxCount
        return picked
    }

    override suspend fun profile(): UserProfile? = profile
    override suspend fun foodEntries(): List<FoodEntry> = foods.toList()
    override suspend fun maxFoodPhotos(): Int = maxPhotos
    override suspend fun addFoodEntry(entry: FoodEntry): Boolean {
        foodWriteGate?.invoke()
        foodWriteError?.let { throw it }
        if (fasts.any { it.isActive }) return false
        foods += entry
        return true
    }
    override suspend fun updateFoodEntry(entry: FoodEntry) {
        val index = foods.indexOfFirst { it.id == entry.id }
        if (index >= 0) foods[index] = entry
    }
    override suspend fun deleteFoodEntry(id: UUID) {
        foods.removeAll { it.id == id }
    }
    override suspend fun savedFoods(): List<SavedFood> = saved.toList()
    override suspend fun storeFoodImages(images: List<ByteArray>): List<String> =
        images.map { "img-${storedImages.size}.jpg".also(storedImages::add) }
    override suspend fun discardFoodImages(filenames: List<String>) {
        val referenced = (foods + saved.map { it.entry }).flatMap { it.allImageFilenames }.toSet()
        storedImages.removeAll(filenames.filterNot { it in referenced }.toSet())
    }
    override suspend fun analyzeFoodPhotos(images: List<ByteArray>, note: String?): FoodAnalysis {
        analyzedPhotoCount = images.size
        analysisError?.let { throw it }
        return analysis
    }
    override suspend fun analyzeFoodText(description: String): FoodAnalysis {
        analysisError?.let { throw it }
        return analysis.copy(name = description)
    }
    override suspend fun lookupBarcode(barcode: String): BarcodeProduct =
        barcodeProduct ?: BarcodeProduct(analysis.copy(name = "Granola bar"), null)

    override suspend fun trackingPrefs() = TrackingPrefs(waterEnabled, 2_000, WaterUnit.MILLILITERS, fastingEnabled, metric)
    override suspend fun waterEntries(): List<WaterEntry> = water.toList()
    override suspend fun addWater(entry: WaterEntry) {
        waterWriteError?.let { throw it }
        water += entry
    }
    override suspend fun deleteWater(id: UUID) {
        water.removeAll { it.id == id }
    }
    override suspend fun activeFast(): FastingSession? = fasts.lastOrNull { it.isActive }
    override suspend fun startFast(goalMinutes: Int): FastingSession? {
        if (fasts.any { it.isActive }) return null
        return FastingSession(startedAt = instant, goalMinutes = goalMinutes).also { fasts += it }
    }
    override suspend fun endFast(): FastingSession? {
        val active = fasts.lastOrNull { it.isActive } ?: return null
        val ended = active.copy(endedAt = instant)
        fasts[fasts.indexOf(active)] = ended
        return ended
    }
    override suspend fun reopenFast(session: FastingSession): Boolean {
        val index = fasts.indexOfFirst { it.id == session.id }
        if (index < 0) return false
        fasts[index] = session.copy(endedAt = null)
        return true
    }
    override suspend fun deleteFast(id: UUID) {
        fasts.removeAll { it.id == id }
    }
    override suspend fun addWeight(entry: WeightEntry) {
        weights += entry
    }
    override suspend fun deleteWeight(id: UUID) {
        weights.removeAll { it.id == id }
    }
    override suspend fun addBodyFat(entry: BodyFatEntry) {
        bodyFats += entry
    }
    override suspend fun deleteBodyFat(id: UUID) {
        bodyFats.removeAll { it.id == id }
    }

    override fun workoutDiaryEnabled(): Boolean = true
    override suspend fun describeWorkout(description: String, date: LocalDate): WorkoutTextDraft =
        workoutDraft ?: error("No workout")
    override suspend fun saveWorkout(draft: WorkoutTextDraft): WorkoutWrite {
        workoutWrites += draft.date
        return WorkoutWrite("Workout", draft.exercises.size, LocalDate.parse(draft.date), null) {
            workoutWrites.remove(draft.date)
        }
    }
    override suspend fun logCardio(activity: String, minutes: Int, calories: Int?, date: LocalDate): WorkoutWrite {
        workoutWrites += "$activity:$minutes"
        return WorkoutWrite(activity, 1, date, calories) { workoutWrites.remove("$activity:$minutes") }
    }

    override suspend fun settings(provider: LiveProvider) = VoiceSettingsSnapshot(
        themeColor = settingsValues[VoiceSettingKey.THEME_COLOR] ?: AppThemeColor.DEFAULT_KEY,
        appearance = settingsValues[VoiceSettingKey.APPEARANCE] ?: "system",
        metric = metric,
        calorieGoal = 2_000,
        proteinGoalG = 150,
        carbsGoalG = 220,
        fatGoalG = 70,
        waterGoalMl = 2_000,
        voice = settingsValues[VoiceSettingKey.ASSISTANT_VOICE] ?: provider.defaultVoice,
        voices = provider.voices,
        autoCapture = false,
        confirmSaves = true,
        adaptiveGoals = adaptiveGoals
    )

    override suspend fun applySetting(key: VoiceSettingKey, value: SettingValue, provider: LiveProvider): SettingApplied {
        val previous = settingsValues[key]
        settingsValues[key] = value.display
        return SettingApplied.Done(restore = {
            if (previous == null) settingsValues.remove(key) else settingsValues[key] = previous
        })
    }

    override suspend fun coachTools(): CoachTools = CoachTools(
        weights = weights,
        bodyFats = bodyFats,
        foods = foods,
        fastingSessions = fasts,
        clock = Clock.fixed(instant, zoneId)
    )
}
