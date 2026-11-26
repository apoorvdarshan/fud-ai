package com.apoorvdarshan.calorietracker.testing

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.apoorvdarshan.calorietracker.models.CurrentMealSchedule
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.services.ReviewPrompter
import com.apoorvdarshan.calorietracker.services.hosted.AiAccessMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant

/** Reset only before launching an Activity; relaunch/recreation never resets saved state. */
object FudAITestState {
    fun reset(onboarded: Boolean = true) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireTestTarget(context)
        val app = context.applicationContext as? FudAITestApplication
            ?: error("FudAITestRunner must create the isolated test Application.")

        runBlocking {
            val prefs = app.container.prefs
            // Use the live DataStore transaction: deleting its file would leave
            // a cached instance and break persistence/relaunch assertions.
            prefs.clearAll()
            prefs.setNotificationsEnabled(false)
            prefs.setHealthConnectEnabled(false)
            prefs.setCloudBackupEnabled(false)
            prefs.setAdaptiveGoalsEnabled(false)
            prefs.setWaterTrackingEnabled(false)
            prefs.setAiAccessMode(AiAccessMode.BYOK)
            if (onboarded) {
                prefs.setUserProfile(UserProfile(
                    name = "Test User",
                    birthday = Instant.parse("2000-01-01T00:00:00Z")
                ))
                prefs.setOnboardingCompleted(true)
            }
            CurrentMealSchedule.value = prefs.mealSchedule.first()
        }
        check(app.getSharedPreferences("fudai_workouts", Context.MODE_PRIVATE)
            .edit().clear().commit())
        ReviewPrompter.consumed()
        app.container.navigator.homeDiaryDate.value = java.time.LocalDate.now()
        app.container.analyzingFood.value = false
    }
}
