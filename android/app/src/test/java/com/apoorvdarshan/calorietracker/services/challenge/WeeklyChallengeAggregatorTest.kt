package com.apoorvdarshan.calorietracker.services.challenge

import com.apoorvdarshan.calorietracker.data.WeeklyChallengeStoredState
import com.apoorvdarshan.calorietracker.models.ChallengePeriod
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeCategory
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeLeaderboard
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyChallengeAggregatorTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val monday = LocalDate.of(2026, 8, 24)

    @Test
    fun monthlyRewardsBalancedDaysAndLongestStreakWithoutCountingFutureOrDuplicateWorkoutDays() {
        fun day(n: Int) = LocalDate.of(2026, 11, n)
        val input = WeeklyChallengeAggregationInput(
            foods = listOf(1, 2, 4, 5).map { food(day(it), 2_000) },
            water = listOf(1, 2, 4).map { water(day(it), 2_000) },
            workoutBurns = listOf(1, 2, 2, 5).map { workout(day(it), 500) },
            calorieGoal = 2_000, waterTrackingEnabled = true, waterGoalMl = 2_000,
            weekStart = day(2), today = day(4), zoneId = utc
        )
        val monthly = WeeklyChallengeAggregator.periodScore(ChallengePeriod.MONTHLY, input)
        assertEquals("2026-11-01", monthly.periodStart)
        assertEquals(2, monthly.activityDays)
        assertEquals(3, monthly.balancedDays)
        assertEquals(2, monthly.longestStreak)
        assertEquals(15, monthly.points)
        val daily = WeeklyChallengeAggregator.periodScore(ChallengePeriod.DAILY, input.copy(waterTrackingEnabled = false))
        assertEquals("2026-11-04", daily.periodStart)
        assertEquals(2, daily.points)
        assertEquals(0, daily.hydrationDays)
    }

    @Test
    fun diaryUpdatesRecalculateAllAndOnlyOptedInExtraPeriods() {
        val input = WeeklyChallengeAggregationInput(
            foods = emptyList(), water = emptyList(), workoutBurns = emptyList(),
            calorieGoal = 2_000, waterTrackingEnabled = true, waterGoalMl = 2_000,
            weekStart = monday, today = monday, zoneId = utc
        )
        val edited = input.copy(foods = listOf(food(monday, 2_000)), water = listOf(water(monday, 2_000)))
        val both = setOf(ChallengePeriod.DAILY, ChallengePeriod.MONTHLY)
        fun scores(periods: Set<ChallengePeriod>, snapshot: WeeklyChallengeAggregationInput) =
            WeeklyChallengeAggregator.enabledPeriodScores(periods, snapshot).associate { it.period to it.points }
        assertEquals(mapOf(ChallengePeriod.DAILY to 0, ChallengePeriod.MONTHLY to 0), scores(both, input))
        assertEquals(mapOf(ChallengePeriod.DAILY to 4, ChallengePeriod.MONTHLY to 4), scores(both, edited))
        assertEquals(mapOf(ChallengePeriod.MONTHLY to 4), scores(setOf(ChallengePeriod.WEEKLY, ChallengePeriod.MONTHLY), edited))
        assertEquals(emptyMap<ChallengePeriod, Int>(), scores(emptySet(), edited))
    }
    @Test
    fun leaderboardCacheSeparatesPeriodsAndPagesAndEvictsOldRows() {
        val start = "2026-11-02"
        val category = WeeklyChallengeCategory.OVERALL
        var stored = WeeklyChallengeStoredState()
        for (period in ChallengePeriod.entries) {
            for (page in 0..10) {
                stored = stored.caching(WeeklyChallengeLeaderboard(start, category, "", offset = page * 20, period = period),
                    period.ordinal * 20L + page)
            }
        }
        assertEquals(30, stored.cachedLeaderboards.size)
        assertEquals(ChallengePeriod.MONTHLY, stored.cached(category, start, 100, ChallengePeriod.MONTHLY)?.period)
        assertEquals(ChallengePeriod.WEEKLY, stored.cached(category, start, 100)?.period)
        assertEquals(null, stored.leaving(ChallengePeriod.MONTHLY).cached(category, start, 100, ChallengePeriod.MONTHLY))
    }

    @Test
    fun scoreUploadMarksOnlyMatchingBoardPagesStaleWithoutDiscardingThem() {
        val start = "2026-11-01"
        val otherDay = "2026-11-02"
        val category = WeeklyChallengeCategory.OVERALL
        val now = 123456L
        fun page(period: ChallengePeriod, day: String, offset: Int) =
            WeeklyChallengeLeaderboard(day, category, "", offset = offset, period = period)
        val stored = WeeklyChallengeStoredState()
            .caching(page(ChallengePeriod.DAILY, start, 0), now)
            .caching(page(ChallengePeriod.DAILY, start, 20), now)
            .caching(page(ChallengePeriod.MONTHLY, start, 0), now)
            .caching(page(ChallengePeriod.DAILY, otherDay, 0), now)
        val stale = stored.markingStale(ChallengePeriod.DAILY, start)
        assertEquals(stored.cachedLeaderboards, stale.cachedLeaderboards)
        assertEquals(0L, stale.refreshedAt(category, start, period = ChallengePeriod.DAILY))
        assertEquals(0L, stale.refreshedAt(category, start, 20, ChallengePeriod.DAILY))
        assertEquals(now, stale.refreshedAt(category, start, period = ChallengePeriod.MONTHLY))
        assertEquals(now, stale.refreshedAt(category, otherDay, period = ChallengePeriod.DAILY))
    }

    @Test
    fun aggregatesOnlyQualifyingElapsedDaysAndCapsDailyActivity() {
        val result = WeeklyChallengeAggregator.aggregate(
            WeeklyChallengeAggregationInput(
                foods = listOf(
                    food(monday, 2_000),
                    food(monday.plusDays(1), 1_000),
                    food(monday.plusDays(3), 2_000)
                ),
                water = listOf(
                    water(monday, 2_000),
                    water(monday.plusDays(1), 1_000),
                    water(monday.plusDays(3), 2_000)
                ),
                workoutBurns = listOf(
                    workout(monday, 1_500),
                    workout(monday, 1_000),
                    workout(monday.plusDays(1), 500),
                    workout(monday.plusDays(1), 0),
                    workout(monday.plusDays(3), 900)
                ),
                calorieGoal = 2_000,
                waterTrackingEnabled = true,
                waterGoalMl = 2_000,
                weekStart = monday,
                today = monday.plusDays(2),
                zoneId = utc
            )
        )

        assertEquals("2026-08-24", result.weekStart)
        assertEquals(2, result.activityDays)
        assertEquals(1, result.nutritionDays)
        assertEquals(2, result.consistencyDays)
        assertEquals(1, result.hydrationDays)
        assertEquals(2_500, result.activityKcal)
        assertEquals(6, result.overallPoints)
    }

    @Test
    fun appliesWeeklyMaximumAndDisablesHydrationWhenTrackingIsOff() {
        val allWeek = (0L..6L).toList()
        val result = WeeklyChallengeAggregator.aggregate(
            WeeklyChallengeAggregationInput(
                foods = allWeek.map { food(monday.plusDays(it), 1_700) },
                water = allWeek.map { water(monday.plusDays(it), 3_000) },
                workoutBurns = allWeek.map { workout(monday.plusDays(it), 3_000) },
                calorieGoal = 2_000,
                waterTrackingEnabled = false,
                waterGoalMl = 2_000,
                weekStart = monday,
                today = monday.plusDays(6),
                zoneId = utc
            )
        )

        assertEquals(7, result.activityDays)
        assertEquals(7, result.nutritionDays)
        assertEquals(7, result.consistencyDays)
        assertEquals(0, result.hydrationDays)
        assertEquals(14_000, result.activityKcal)
        assertEquals(21, result.overallPoints)
    }

    private fun food(date: LocalDate, calories: Int) = FoodEntry(
        name = "private food name",
        calories = calories,
        protein = 0.0,
        carbs = 0.0,
        fat = 0.0,
        timestamp = instant(date),
        source = FoodSource.MANUAL
    )

    private fun water(date: LocalDate, milliliters: Int) = WaterEntry(
        date = instant(date),
        milliliters = milliliters
    )

    private fun workout(date: LocalDate, calories: Int) = WorkoutSession(
        diaryDateKey = date.toString(),
        startedAt = instant(date),
        completedAt = instant(date).plusSeconds(1_800),
        exercises = emptyList(),
        caloriesBurned = calories
    )

    private fun instant(date: LocalDate): Instant =
        date.atTime(12, 0).toInstant(ZoneOffset.UTC)
}
