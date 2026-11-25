package com.apoorvdarshan.calorietracker.services.challenge

import com.apoorvdarshan.calorietracker.models.ChallengePeriod
import com.apoorvdarshan.calorietracker.models.ChallengePeriodScore
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeAggregate
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeWeek
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class WeeklyChallengeAggregationInput(
    val foods: List<FoodEntry>,
    val water: List<WaterEntry>,
    val workoutBurns: List<WorkoutSession>,
    val calorieGoal: Int,
    val waterTrackingEnabled: Boolean,
    val waterGoalMl: Int,
    val weekStart: LocalDate,
    val today: LocalDate,
    val zoneId: ZoneId
)

/** Pure, deterministic conversion from local diary data to the public aggregate contract. */
object WeeklyChallengeAggregator {
    private const val DAILY_ACTIVITY_KCAL_CAP = 2_000

    fun periodScore(period: ChallengePeriod, input: WeeklyChallengeAggregationInput): ChallengePeriodScore {
        val start = period.startFor(input.today)
        val days = start..input.today
        val foods = input.foods.groupBy { it.timestamp.atZone(input.zoneId).toLocalDate() }
        val water = input.water.groupBy { it.date.atZone(input.zoneId).toLocalDate() }
        val moved = input.workoutBurns.asSequence().filter { (it.caloriesBurned ?: 0) > 0 }
            .mapNotNull { runCatching { LocalDate.parse(it.diaryDateKey) }.getOrNull() }.toSet()
        var activity = 0
        var nutrition = 0
        var consistency = 0
        var hydration = 0
        var balanced = 0
        var streak = 0
        var longest = 0
        var day = start
        while (day in days) {
            val entries = foods[day]
            val withinGoal = input.calorieGoal > 0 && entries != null &&
                entries.sumOf { it.calories.toLong().coerceAtLeast(0) }.toDouble() in
                (input.calorieGoal * 0.85)..(input.calorieGoal * 1.15)
            val hydrated = input.waterTrackingEnabled && input.waterGoalMl > 0 &&
                (water[day]?.sumOf { it.milliliters.toLong().coerceAtLeast(0) } ?: 0L) >= input.waterGoalMl
            val habits = listOf(day in moved, withinGoal, entries != null, hydrated).count { it }
            if (day in moved) activity++
            if (withinGoal) nutrition++
            if (entries != null) consistency++
            if (hydrated) hydration++
            if (habits >= 3) balanced++
            streak = if (habits >= 2) streak + 1 else 0
            longest = maxOf(longest, streak)
            day = day.plusDays(1)
        }
        return ChallengePeriodScore(period, start.toString(), activity, nutrition, consistency, hydration, balanced, longest, acceptedRules = true)
    }

    fun aggregate(input: WeeklyChallengeAggregationInput): WeeklyChallengeAggregate {
        val weekEnd = WeeklyChallengeWeek.endFor(input.weekStart)
        val effectiveEnd = minOf(weekEnd, input.today)
        if (effectiveEnd.isBefore(input.weekStart)) return WeeklyChallengeAggregate.empty(input.weekStart)
        val dayCount = ChronoUnit.DAYS.between(input.weekStart, effectiveEnd).toInt()
        val validDates = (0..dayCount).map { input.weekStart.plusDays(it.toLong()) }.toSet()

        val foodsByDay = input.foods.groupBy {
            it.timestamp.atZone(input.zoneId).toLocalDate()
        }.filterKeys { it in validDates }
        val consistencyDays = foodsByDay.keys.size.coerceIn(0, 7)
        val nutritionDays = if (input.calorieGoal > 0) {
            val lower = input.calorieGoal * 0.85
            val upper = input.calorieGoal * 1.15
            foodsByDay.values.count { entries ->
                entries.sumOf { it.calories.toLong() }.toDouble() in lower..upper
            }.coerceIn(0, 7)
        } else {
            0
        }

        val hydrationDays = if (input.waterTrackingEnabled && input.waterGoalMl > 0) {
            input.water
                .groupBy { it.date.atZone(input.zoneId).toLocalDate() }
                .filterKeys { it in validDates }
                .count { (_, entries) ->
                    entries.sumOf { it.milliliters.toLong() } >= input.waterGoalMl.toLong()
                }
                .coerceIn(0, 7)
        } else {
            0
        }

        val burnByDay = input.workoutBurns
            .mapNotNull { session ->
                val date = runCatching { LocalDate.parse(session.diaryDateKey) }.getOrNull()
                    ?: return@mapNotNull null
                val calories = session.caloriesBurned?.takeIf { it > 0 } ?: return@mapNotNull null
                if (date !in validDates) null else date to calories
            }
            .groupBy({ it.first }, { it.second })
        val activityDays = burnByDay.keys.size.coerceIn(0, 7)
        val activityKcal = burnByDay.values.sumOf { dayCalories ->
            dayCalories.sumOf { it.toLong() }
                .coerceAtMost(DAILY_ACTIVITY_KCAL_CAP.toLong())
        }.coerceIn(0, 14_000)

        val overall = (activityDays + nutritionDays + consistencyDays + hydrationDays).coerceIn(0, 28)
        return WeeklyChallengeAggregate(
            weekStart = input.weekStart.toString(),
            overallPoints = overall,
            activityDays = activityDays,
            nutritionDays = nutritionDays,
            consistencyDays = consistencyDays,
            hydrationDays = hydrationDays,
            activityKcal = activityKcal.toInt()
        )
    }
}
