package com.apoorvdarshan.calorietracker.ui.navigation

import com.apoorvdarshan.calorietracker.services.voice.VoiceSettingsSection
import com.apoorvdarshan.calorietracker.ui.about.AboutSettingsCategory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.LocalDate
import java.util.UUID

/** A navigation the app performs for someone other than the user's own taps (the voice assistant). */
sealed interface NavRequest {
    /** Show a bottom tab. */
    data class Tab(val route: String) : NavRequest

    /** Show Settings, at its hub or straight in one section. */
    data class Settings(val section: VoiceSettingsSection?) : NavRequest
    data class About(val section: AboutSettingsCategory) : NavRequest
}

/**
 * App-wide navigation state shared by the screens and the voice assistant. Screens
 * publish what they show; the assistant requests tabs, settings sections, Home's diary
 * day and food entries, and the screens perform them like the user's own taps.
 */
class AppNavigator(private val today: () -> LocalDate = { LocalDate.now() }) {
    /** The NavHost route on screen, published by [FudAINavHost]. */
    val currentRoute = MutableStateFlow<String?>(null)

    /** Tab routes the user hid from the bottom bar, published by [FudAINavHost]. */
    val hiddenTabs = MutableStateFlow<Set<String>>(emptySet())

    /** The Settings section on screen (null at the hub), published by the Settings screen. */
    val settingsSection = MutableStateFlow<VoiceSettingsSection?>(null)

    /** Home's diary day. Home reads and writes it, so the assistant sees and sets the same day. */
    private var lastObservedToday = today()
    val homeDiaryDate = MutableStateFlow(lastObservedToday)

    /** The Workouts diary's selected day, published by the Workouts screen. */
    val workoutDiaryDate = MutableStateFlow<LocalDate?>(null)
    val pendingWorkoutDiaryDate = MutableStateFlow<LocalDate?>(null)
    val aboutSection = MutableStateFlow<AboutSettingsCategory?>(null)

    /** A Settings section waiting for the Settings screen to open it. */
    val pendingSettingsSection = MutableStateFlow<VoiceSettingsSection?>(null)

    /** A food entry waiting for Home to open its edit sheet. */
    val pendingFoodEntry = MutableStateFlow<UUID?>(null)

    private val _requests = MutableSharedFlow<NavRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<NavRequest> = _requests.asSharedFlow()

    /** Follow the new local day only if the diary was following today's date. */
    fun refreshDiaryDates() {
        val day = today()
        val previous = lastObservedToday
        if (previous == day) return
        lastObservedToday = day
        homeDiaryDate.compareAndSet(previous, day)
        val workout = pendingWorkoutDiaryDate.value ?: workoutDiaryDate.value
        if (workout == previous) {
            workoutDiaryDate.value = day
            pendingWorkoutDiaryDate.value = day
        }
    }

    fun currentHomeDiaryDate(): LocalDate {
        refreshDiaryDates()
        return homeDiaryDate.value
    }

    fun currentWorkoutDiaryDate(): LocalDate? {
        refreshDiaryDates()
        return pendingWorkoutDiaryDate.value ?: workoutDiaryDate.value.takeIf { currentRoute.value == FudAIRoutes.WORKOUTS }
    }

    val requestedNestedScreen = MutableStateFlow<String?>(null)
    val progressScreen = MutableStateFlow(FudAIRoutes.PROGRESS)
    val workoutScreen = MutableStateFlow(FudAIRoutes.WORKOUTS)

    fun showTab(route: String) {
        if (FudAIRoutes.isHiddenTab(route, hiddenTabs.value)) return
        requestedNestedScreen.value = when (route) {
            FudAIRoutes.PROGRESS, FudAIRoutes.WEEKLY_CHALLENGE,
            FudAIRoutes.WORKOUTS, FudAIRoutes.EXERCISE_LIBRARY -> route
            else -> null
        }
        _requests.tryEmit(NavRequest.Tab(route))
    }

    fun showSettings(section: VoiceSettingsSection?) {
        pendingSettingsSection.value = section
        _requests.tryEmit(NavRequest.Settings(section))
    }

    fun showHomeDate(date: LocalDate) {
        refreshDiaryDates()
        homeDiaryDate.value = date
        showTab(FudAIRoutes.HOME)
    }

    fun showWorkoutDate(date: LocalDate) {
        refreshDiaryDates()
        pendingWorkoutDiaryDate.value = date
        showTab(FudAIRoutes.WORKOUTS)
    }

    fun showAbout(section: AboutSettingsCategory?) {
        showSettings(section?.voiceSection ?: VoiceSettingsSection.APP_UPDATES)
    }

    fun showFoodEntry(id: UUID, date: LocalDate) {
        refreshDiaryDates()
        homeDiaryDate.value = date
        pendingFoodEntry.value = id
        showTab(FudAIRoutes.HOME)
    }
}
