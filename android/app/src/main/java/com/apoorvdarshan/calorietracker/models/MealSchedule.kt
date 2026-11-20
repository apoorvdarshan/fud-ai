package com.apoorvdarshan.calorietracker.models

import java.time.LocalTime

data class MealSchedule(
    val breakfastStartMinutes: Int = DEFAULT_BREAKFAST_START,
    val lunchStartMinutes: Int = DEFAULT_LUNCH_START,
    val dinnerStartMinutes: Int = DEFAULT_DINNER_START,
    val snackStartMinutes: Int = DEFAULT_SNACK_START
) {
    val isValid: Boolean
        get() = breakfastStartMinutes in 0 until MINUTES_PER_DAY &&
            breakfastStartMinutes < lunchStartMinutes &&
            lunchStartMinutes < dinnerStartMinutes &&
            dinnerStartMinutes < snackStartMinutes &&
            snackStartMinutes < MINUTES_PER_DAY

    fun mealTypeAt(time: LocalTime, manualSelection: MealType? = null): MealType {
        if (manualSelection != null) return manualSelection
        val minutes = time.hour * 60 + time.minute
        return when {
            minutes >= snackStartMinutes || minutes < breakfastStartMinutes -> MealType.SNACK
            minutes >= dinnerStartMinutes -> MealType.DINNER
            minutes >= lunchStartMinutes -> MealType.LUNCH
            else -> MealType.BREAKFAST
        }
    }

    fun validatedOrDefault(): MealSchedule = if (isValid) this else Default

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_BREAKFAST_START = 5 * 60
        const val DEFAULT_LUNCH_START = 12 * 60
        const val DEFAULT_DINNER_START = 18 * 60
        const val DEFAULT_SNACK_START = 23 * 60

        val Default = MealSchedule()
    }
}

object CurrentMealSchedule {
    @Volatile
    var value: MealSchedule = MealSchedule.Default
}
