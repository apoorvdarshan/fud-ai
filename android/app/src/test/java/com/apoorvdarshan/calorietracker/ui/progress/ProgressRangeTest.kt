package com.apoorvdarshan.calorietracker.ui.progress

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class ProgressRangeTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-17")

    @Test
    fun dateRangeStepsBackWholeContiguousWindows() {
        assertEquals(
            LocalDate.parse("2026-10-11") to today,
            TimeRange.WEEK.dateRange(today, offset = 0)
        )
        assertEquals(
            LocalDate.parse("2026-10-04") to LocalDate.parse("2026-10-10"),
            TimeRange.WEEK.dateRange(today, offset = 1)
        )
        assertEquals(
            LocalDate.parse("2026-09-27") to LocalDate.parse("2026-10-03"),
            TimeRange.WEEK.dateRange(today, offset = 2)
        )
        assertEquals(
            LocalDate.parse("2026-04-21") to LocalDate.parse("2026-07-19"),
            TimeRange.THREE_MONTHS.dateRange(today, offset = 1)
        )
    }

    @Test
    fun everyRangeCoversItsDayCountAtAnyOffset() {
        TimeRange.entries.forEach { range ->
            (0..2).forEach { offset ->
                val (start, end) = range.dateRange(today, offset)
                assertEquals(range.days.toLong() - 1, end.toEpochDay() - start.toEpochDay())
            }
        }
    }

    @Test
    fun periodNavigationIsOnlyUnavailableForAllTime() {
        assertEquals(
            listOf(TimeRange.ALL_TIME),
            TimeRange.entries.filterNot { it.supportsPeriodNavigation }
        )
    }

    @Test
    fun foodStatsOnlyCountDaysInsideThePastWindow() {
        val foods = listOf(
            food(calories = 2_000, protein = 100.0, at = "2026-10-04T09:00"),
            food(calories = 1_000, protein = 50.0, at = "2026-10-10T21:00"),
            // Outside the window on either side.
            food(calories = 3_000, protein = 300.0, at = "2026-10-03T12:00"),
            food(calories = 3_000, protein = 300.0, at = "2026-10-11T01:00")
        )

        val stats = computeProgressFoodRangeStats(
            foods = foods,
            dayCount = 7,
            endDay = LocalDate.parse("2026-10-10"),
            zone = zone,
            profile = null,
            optionalGoals = OptionalNutrientGoals.Default
        )

        assertEquals(
            listOf(LocalDate.parse("2026-10-04") to 2_000, LocalDate.parse("2026-10-10") to 1_000),
            stats.dailyCalories
        )
        assertEquals(75.0, stats.avgProtein, 0.0001)
    }

    @Test
    fun nutritionIncludesTodayOnlyInTheCurrentWindow() {
        val foods = listOf(
            food(calories = 2_000, protein = 120.0, at = "2026-10-15T13:00"),
            food(calories = 2_200, protein = 140.0, at = "2026-10-16T19:00"),
            // Breakfast only so far today.
            food(calories = 400, protein = 20.0, at = "2026-10-17T08:00"),
            food(calories = 1_800, protein = 100.0, at = "2026-10-10T20:00")
        )

        fun stats(offset: Int) = computeProgressFoodRangeStats(
            foods = foods,
            dayCount = TimeRange.WEEK.days,
            endDay = TimeRange.WEEK.dateRange(today, offset).second,
            zone = zone,
            profile = null,
            optionalGoals = OptionalNutrientGoals.Default
        )

        val current = stats(offset = 0)
        val previous = stats(offset = 1)

        assertEquals(listOf(2_000, 2_200, 400), current.dailyCalories.map { it.second })
        assertEquals(280.0 / 3, current.avgProtein, 0.0001)
        assertEquals(listOf(1_800), previous.dailyCalories.map { it.second })
        assertEquals(100.0, previous.avgProtein, 0.0001)
    }

    @Test
    fun calorieBarHitTestPicksTheNearestBarAndClampsToTheEnds() {
        // Bars 20px wide with 4px gaps starting at x = 10: centres at 20, 44, 68.
        assertEquals(0, calorieBarIndexAt(x = 0f, startX = 10f, barWidth = 20f, gap = 4f, count = 3))
        assertEquals(0, calorieBarIndexAt(x = 31f, startX = 10f, barWidth = 20f, gap = 4f, count = 3))
        assertEquals(1, calorieBarIndexAt(x = 33f, startX = 10f, barWidth = 20f, gap = 4f, count = 3))
        assertEquals(2, calorieBarIndexAt(x = 68f, startX = 10f, barWidth = 20f, gap = 4f, count = 3))
        assertEquals(2, calorieBarIndexAt(x = 500f, startX = 10f, barWidth = 20f, gap = 4f, count = 3))
        assertNull(calorieBarIndexAt(x = 20f, startX = 10f, barWidth = 20f, gap = 4f, count = 0))
    }

    @Test
    fun calorieBarsKeepTheFixedGapWhileTheyFit() {
        // A week on a 900px plot: 7 bars of (900 - 6 * 4) / 7 ≈ 125px, capped at 60px.
        val (startX, barWidth, gap) = calorieBarGeometry(areaWidth = 900f, count = 7, maxBarWidth = 60f)
        assertEquals(60f, barWidth, 0.001f)
        assertEquals(4f, gap, 0.001f)
        assertEquals((900f - (7 * 60f + 6 * 4f)) / 2f, startX, 0.001f)
    }

    @Test
    fun denselyLoggedLongPeriodsFitTheWholePlotSoTheLatestDayIsReachable() {
        // 365 logged days used to need 2px + 4px per bar = 2,186px, so on a 900px plot the
        // bars ran off the right edge and the inspector stopped around the 150th day.
        val width = 900f
        for (count in listOf(150, 151, 200, 299, 300, 365, 1_000, 3_650)) {
            val (startX, barWidth, gap) = calorieBarGeometry(width, count, maxBarWidth = 60f)
            val rightEdge = startX + count * barWidth + (count - 1) * gap
            assertTrue("$count bars overflow: $rightEdge", startX >= 0f && rightEdge <= width + 0.01f)
            val lastCentre = startX + (count - 1) * (barWidth + gap) + barWidth / 2f
            assertEquals(count - 1, calorieBarIndexAt(lastCentre, startX, barWidth, gap, count))
            // A scrub clamps to the plot's right edge, which must land on the latest day.
            assertEquals(count - 1, calorieBarIndexAt(width, startX, barWidth, gap, count))
            assertEquals(0, calorieBarIndexAt(0f, startX, barWidth, gap, count))
        }
    }

    private fun food(calories: Int, protein: Double, at: String) = FoodEntry(
        name = "Meal",
        calories = calories,
        protein = protein,
        carbs = 0.0,
        fat = 0.0,
        timestamp = LocalDateTime.parse(at).toInstant(zone),
        source = FoodSource.MANUAL
    )
}
