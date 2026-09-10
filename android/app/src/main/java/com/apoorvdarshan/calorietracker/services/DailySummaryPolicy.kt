package com.apoorvdarshan.calorietracker.services

enum class CalorieBalanceDirection {
    DEFICIT,
    SURPLUS,
    BALANCED
}

internal data class DailyCalorieBalance(
    val eatenCalories: Int,
    val burnedCalories: Int,
    val differenceCalories: Int,
    val direction: CalorieBalanceDirection
)

/** Pure nightly-summary math, separated from Android/Health Connect for JVM tests. */
internal object DailySummaryPolicy {
    fun resolveBurnedCalories(
        measuredTotalCalories: Int?,
        externalActiveCalories: Int,
        profileBmrCalories: Int?
    ): Int? {
        measuredTotalCalories?.takeIf { it > 0 }?.let { return it }
        val bmr = profileBmrCalories?.takeIf { it > 0 } ?: return null
        val active = externalActiveCalories.takeIf { it > 0 } ?: return null
        return bmr + active
    }

    fun balance(eatenCalories: Int, burnedCalories: Int): DailyCalorieBalance {
        val eaten = eatenCalories.coerceAtLeast(0)
        val burned = burnedCalories.coerceAtLeast(0)
        val signedDifference = burned - eaten
        val direction = when {
            signedDifference > 0 -> CalorieBalanceDirection.DEFICIT
            signedDifference < 0 -> CalorieBalanceDirection.SURPLUS
            else -> CalorieBalanceDirection.BALANCED
        }
        return DailyCalorieBalance(
            eatenCalories = eaten,
            burnedCalories = burned,
            differenceCalories = kotlin.math.abs(signedDifference),
            direction = direction
        )
    }
}
