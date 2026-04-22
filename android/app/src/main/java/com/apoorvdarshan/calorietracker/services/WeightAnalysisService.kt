package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WeightGoal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure thermodynamic / statistical forecast of where the user's weight is heading based on
 * recent calorie intake, logged weight history, and their profile. No network, no LLM —
 * energy-balance math + linear regression.
 */
data class WeightForecast(
    val avgDailyCalories: Int,
    val tdee: Int,
    val dailyEnergyBalance: Int,
    val predictedWeeklyChangeKg: Double,
    val observedWeeklyChangeKg: Double?,
    val currentWeightKg: Double,
    val predictedWeight30dKg: Double,
    val predictedWeight60dKg: Double,
    val predictedWeight90dKg: Double,
    val daysToGoal: Int?,
    val goalReachDate: Instant?,
    val hasEnoughData: Boolean,
    val trendsDisagree: Boolean,
    val daysOfFoodData: Int,
    val weightEntriesUsed: Int
) {
    companion object {
        const val MAX_LOOKBACK_DAYS = 90
    }
}

object WeightAnalysisService {

    fun compute(
        weights: List<WeightEntry>,
        foods: List<FoodEntry>,
        profile: UserProfile
    ): WeightForecast {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val cutoff = now.minusSeconds(WeightForecast.MAX_LOOKBACK_DAYS * 86_400L)

        val recentFoods = foods.filter { it.timestamp in cutoff..now }
        val daysLogged = recentFoods.map { it.timestamp.atZone(zone).toLocalDate() }.toSet().size
        val totalRecentCal = recentFoods.sumOf { it.calories }
        val avgDailyCal = if (daysLogged > 0) totalRecentCal / daysLogged else 0

        val tdee = profile.tdee.toInt()
        val balance = avgDailyCal - tdee
        // 7,700 kcal ≈ 1 kg body fat (ISSN standard for deficit/surplus math).
        val predictedWeeklyKg = balance.toDouble() * 7.0 / 7_700.0

        val sortedWeights = weights.sortedByDescending { it.date }
        val currentWeight = sortedWeights.firstOrNull()?.weightKg ?: profile.weightKg

        val regressionWindow = sortedWeights.filter { it.date >= cutoff }
        val observedWeeklyKg = linearRegressionSlopePerDay(regressionWindow)?.let { it * 7.0 }

        val pred30 = currentWeight + predictedWeeklyKg * 30.0 / 7.0
        val pred60 = currentWeight + predictedWeeklyKg * 60.0 / 7.0
        val pred90 = currentWeight + predictedWeeklyKg * 90.0 / 7.0

        var daysToGoal: Int? = null
        var goalReachDate: Instant? = null
        val goalKg = profile.goalWeightKg
        if (goalKg != null && predictedWeeklyKg != 0.0 && profile.goal != WeightGoal.MAINTAIN) {
            val kgRemaining = goalKg - currentWeight
            val movingCorrectWay =
                (profile.goal == WeightGoal.LOSE && predictedWeeklyKg < 0 && kgRemaining < 0) ||
                        (profile.goal == WeightGoal.GAIN && predictedWeeklyKg > 0 && kgRemaining > 0)
            if (movingCorrectWay) {
                val daysPerKg = 7.0 / abs(predictedWeeklyKg)
                val days = (abs(kgRemaining) * daysPerKg).roundToInt()
                daysToGoal = days
                goalReachDate = now.plusSeconds(days * 86_400L)
            }
        }

        val hasEnoughData = daysLogged >= 2 && weights.size >= 2

        val trendsDisagree = observedWeeklyKg?.let { observed ->
            hasEnoughData && abs(predictedWeeklyKg - observed) > 0.3
        } ?: false

        return WeightForecast(
            avgDailyCalories = avgDailyCal,
            tdee = tdee,
            dailyEnergyBalance = balance,
            predictedWeeklyChangeKg = predictedWeeklyKg,
            observedWeeklyChangeKg = observedWeeklyKg,
            currentWeightKg = currentWeight,
            predictedWeight30dKg = pred30,
            predictedWeight60dKg = pred60,
            predictedWeight90dKg = pred90,
            daysToGoal = daysToGoal,
            goalReachDate = goalReachDate,
            hasEnoughData = hasEnoughData,
            trendsDisagree = trendsDisagree,
            daysOfFoodData = daysLogged,
            weightEntriesUsed = regressionWindow.size
        )
    }

    /**
     * Slope of a simple linear regression (y = mx + b) over weight entries, returning m in
     * kg per day. Returns null if fewer than 2 entries or all x's are the same.
     */
    private fun linearRegressionSlopePerDay(entries: List<WeightEntry>): Double? {
        if (entries.size < 2) return null
        val xs = entries.map { it.date.epochSecond.toDouble() }
        val ys = entries.map { it.weightKg }
        val n = xs.size.toDouble()
        val meanX = xs.sum() / n
        val meanY = ys.sum() / n
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            num += dx * (ys[i] - meanY)
            den += dx * dx
        }
        if (den == 0.0) return null
        val kgPerSecond = num / den
        return kgPerSecond * 86_400.0
    }
}

@Suppress("unused")
private fun Instant.toLocalDateInZone(zone: ZoneId): LocalDate =
    this.atZone(zone).toLocalDate()
