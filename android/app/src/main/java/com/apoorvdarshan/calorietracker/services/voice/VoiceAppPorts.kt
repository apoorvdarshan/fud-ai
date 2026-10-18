package com.apoorvdarshan.calorietracker.services.voice

import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WorkoutTextDraft
import com.apoorvdarshan.calorietracker.services.BarcodeProductLookup
import com.apoorvdarshan.calorietracker.services.ai.CoachTools
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Tabs `open_screen` can show. */
enum class VoiceScreen(val wireValue: String) {
    HOME("home"), PROGRESS("progress"), COACH("coach"), WORKOUTS("workouts"), SETTINGS("settings");

    companion object {
        fun fromWire(raw: String?): VoiceScreen? = entries.firstOrNull { it.wireValue == raw?.trim()?.lowercase() }
    }
}

/** `open_screen`'s settings_section values. */
enum class VoiceSettingsSection(val wireValue: String) {
    PROFILE("profile"),
    GOALS("goals"),
    REMINDERS("reminders"),
    NOTIFICATIONS("notifications"),
    AI_PROVIDERS("ai_providers"),
    SPEECH("speech"),
    APPEARANCE("appearance"),
    WORKOUTS("workouts"),
    HEALTH("health"),
    DATA("data"),
    VOICE_ASSISTANT("voice_assistant"),
    ABOUT("about");

    companion object {
        fun fromWire(raw: String?): VoiceSettingsSection? = entries.firstOrNull { it.wireValue == raw?.trim()?.lowercase() }
    }
}

data class ScreenState(val screen: VoiceScreen?, val section: VoiceSettingsSection?)

enum class VoiceCameraMode(val wireValue: String) { FOOD("food"), BARCODE("barcode") }

sealed interface CameraOutcome {
    data object Opened : CameraOutcome
    data object PermissionDenied : CameraOutcome
    data object Unavailable : CameraOutcome
    data object TimedOut : CameraOutcome
}

sealed interface PhotoOutcome {
    class Taken(val jpeg: ByteArray) : PhotoOutcome
    data object NotOpen : PhotoOutcome
    data object Failed : PhotoOutcome
}

sealed interface BarcodeOutcome {
    data class Scanned(val code: String) : BarcodeOutcome
    data object Cancelled : BarcodeOutcome
    data object TimedOut : BarcodeOutcome
    data object PermissionDenied : BarcodeOutcome
    data object Unavailable : BarcodeOutcome
}

sealed interface PickOutcome {
    class Picked(val images: List<ByteArray>) : PickOutcome
    data object Cancelled : PickOutcome
    data object TimedOut : PickOutcome
}

enum class SavedFoodKind(val wireValue: String) { FAVORITE("favorite"), FREQUENT("frequent"), RECENT("recent") }

/** A saved meal, favorite or recent food `search_foods` can return. */
data class SavedFood(val entry: FoodEntry, val kind: SavedFoodKind, val timesLogged: Int? = null) {
    // A favorite preserves a snapshot independently of the diary entry it came from.
    val savedId: String get() = "${if (kind == SavedFoodKind.FAVORITE) "favorite" else "food"}-${entry.id}"
}

/** A scanned product; a saved label (#485) logs as a label again, so the barcode keeps it. */
class BarcodeProduct(
    val analysis: FoodAnalysis,
    val imageBytes: ByteArray?,
    val source: FoodSource = FoodSource.BARCODE
) {
    companion object {
        fun from(outcome: BarcodeProductLookup.Outcome): BarcodeProduct = when (outcome) {
            is BarcodeProductLookup.Outcome.Product ->
                BarcodeProduct(outcome.result.analysis, outcome.result.productImageBytes)
            is BarcodeProductLookup.Outcome.SavedLabel ->
                BarcodeProduct(outcome.analysis, null, FoodSource.NUTRITION_LABEL)
        }
    }
}

data class TrackingPrefs(
    val waterEnabled: Boolean,
    val waterGoalMl: Int,
    val waterUnit: WaterUnit,
    val fastingEnabled: Boolean,
    val weightMetric: Boolean
)

/** A workout write and how to take it back. */
class WorkoutWrite(
    val name: String,
    val exerciseCount: Int,
    val date: LocalDate,
    val caloriesBurned: Int?,
    val restore: suspend () -> Unit
)

data class VoiceSettingsSnapshot(
    val themeColor: String,
    val appearance: String,
    val metric: Boolean,
    val calorieGoal: Int?,
    val proteinGoalG: Int?,
    val carbsGoalG: Int?,
    val fatGoalG: Int?,
    val waterGoalMl: Int,
    val voice: String,
    val voices: List<String>,
    val autoCapture: Boolean,
    val confirmSaves: Boolean,
    val adaptiveGoals: Boolean
)

sealed interface SettingApplied {
    class Done(val restore: suspend () -> Unit, val note: String? = null) : SettingApplied
    data class Rejected(val error: ToolError, val message: String) : SettingApplied
}

/** What the current session can do, for instructions and get_app_state. */
data class VoiceSessionContext(
    val provider: LiveProvider,
    val confirmSaves: Boolean,
    val autoCapture: Boolean,
    val workoutDiaryEnabled: Boolean = true
) {
    val cameraFrames: CameraFrames get() = provider.cameraFrames
}

/**
 * Everything the voice assistant's tools can do in the app. The Android implementation
 * ([AndroidVoiceAppPorts]) uses the real repositories, pipelines and navigation; unit
 * tests substitute an in-memory fake.
 */
interface VoiceAppPorts {
    // Time and calendar
    fun now(): Instant
    fun zone(): ZoneId
    fun today(): LocalDate = now().atZone(zone()).toLocalDate()
    fun mealForTime(time: LocalTime): MealType

    // Navigation and screen state
    fun screenState(): ScreenState
    fun diaryDate(): LocalDate
    fun workoutDiaryDate(): LocalDate?
    fun showScreen(screen: VoiceScreen, section: VoiceSettingsSection?)
    /** Tabs the user hid from the bottom bar; `open_screen` can't show them. */
    fun hiddenScreens(): Set<VoiceScreen> = emptySet()
    fun showDiaryDate(date: LocalDate)
    fun showFoodEntry(entry: FoodEntry)

    // Camera and photo picker (these may wait for the user)
    fun cameraMode(): VoiceCameraMode?
    suspend fun openCamera(mode: VoiceCameraMode): CameraOutcome
    fun closeCamera()
    /** A single capture; releases its own camera before returning, including on failure. */
    suspend fun takePhoto(): PhotoOutcome
    suspend fun scanBarcode(): BarcodeOutcome
    suspend fun pickPhotos(maxCount: Int): PickOutcome

    // Food
    suspend fun profile(): UserProfile?
    suspend fun foodEntries(): List<FoodEntry>
    /** False when food logging is paused by a running fast. */
    suspend fun addFoodEntry(entry: FoodEntry): Boolean
    suspend fun updateFoodEntry(entry: FoodEntry)
    suspend fun deleteFoodEntry(id: UUID)
    suspend fun savedFoods(): List<SavedFood>
    /** The selected food-analysis pipeline may have a lower limit than live voice. */
    suspend fun maxFoodPhotos(): Int
    /** Stores photos for a new entry and returns their filenames. */
    suspend fun storeFoodImages(images: List<ByteArray>): List<String>
    /** Discards only this attempt's newly stored files that no persisted record adopted. */
    suspend fun discardFoodImages(filenames: List<String>)
    suspend fun analyzeFoodPhotos(images: List<ByteArray>, note: String?): FoodAnalysis
    suspend fun analyzeFoodText(description: String): FoodAnalysis
    suspend fun lookupBarcode(barcode: String): BarcodeProduct

    // Water, fasting and body
    suspend fun trackingPrefs(): TrackingPrefs
    suspend fun waterEntries(): List<WaterEntry>
    suspend fun addWater(entry: WaterEntry)
    suspend fun deleteWater(id: UUID)
    suspend fun activeFast(): FastingSession?
    suspend fun startFast(goalMinutes: Int): FastingSession?
    suspend fun endFast(): FastingSession?
    /** Puts an ended fast back to running (undo of end_fast). */
    suspend fun reopenFast(session: FastingSession): Boolean
    suspend fun deleteFast(id: UUID)
    suspend fun addWeight(entry: WeightEntry)
    suspend fun deleteWeight(id: UUID)
    suspend fun addBodyFat(entry: BodyFatEntry)
    suspend fun deleteBodyFat(id: UUID)

    // Workouts
    fun workoutDiaryEnabled(): Boolean
    suspend fun describeWorkout(description: String, date: LocalDate): WorkoutTextDraft
    suspend fun saveWorkout(draft: WorkoutTextDraft): WorkoutWrite
    suspend fun logCardio(activity: String, minutes: Int, calories: Int?, date: LocalDate): WorkoutWrite

    // Settings
    suspend fun settings(provider: LiveProvider): VoiceSettingsSnapshot
    suspend fun applySetting(key: VoiceSettingKey, value: SettingValue, provider: LiveProvider): SettingApplied

    // Coach's data tools over the current data
    suspend fun coachTools(): CoachTools
}
