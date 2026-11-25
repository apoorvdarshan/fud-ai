package com.apoorvdarshan.calorietracker.ui.navigation

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigatorDiaryDateTest {
    @Test
    fun shortcutAndToolReadRollTodayForwardEvenWhenHomeIsNotShowing() {
        val yesterday = LocalDate.of(2026, 11, 25)
        var today = yesterday
        val navigator = AppNavigator { today }
        navigator.currentRoute.value = FudAIRoutes.COACH
        navigator.workoutDiaryDate.value = yesterday
        today = yesterday.plusDays(1)

        assertEquals(today, navigator.currentHomeDiaryDate())
        assertEquals(today, navigator.homeDiaryDate.value)
        assertEquals(today, navigator.workoutDiaryDate.value)
    }

    @Test
    fun explicitlySelectedDatesArePreservedOnResumeAndAvailableWithoutNavigation() {
        var today = LocalDate.of(2026, 11, 25)
        val navigator = AppNavigator { today }
        val past = today.minusDays(2)
        navigator.showHomeDate(past)
        today = today.plusDays(1)
        navigator.refreshDiaryDates()
        assertEquals(past, navigator.currentHomeDiaryDate())

        val yesterday = today.minusDays(1)
        navigator.showHomeDate(yesterday)
        navigator.showWorkoutDate(yesterday)
        assertEquals(yesterday, navigator.currentHomeDiaryDate())
        assertEquals(yesterday, navigator.currentWorkoutDiaryDate())
    }
}
