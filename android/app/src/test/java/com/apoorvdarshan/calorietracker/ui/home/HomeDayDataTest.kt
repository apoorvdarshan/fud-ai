package com.apoorvdarshan.calorietracker.ui.home

import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.WaterEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDayDataTest {
    private val day = LocalDate.of(2026, 9, 19)
    private val zone = ZoneOffset.UTC

    @Test
    fun filterKeepsOnlyTheSelectedDayNewestFirst() {
        val todayMorning = Instant.parse("2026-09-19T08:00:00Z")
        val todayEvening = Instant.parse("2026-09-19T20:00:00Z")
        val yesterday = Instant.parse("2026-09-18T20:00:00Z")
        val morning = food("Breakfast", todayMorning)
        val evening = food("Dinner", todayEvening)
        val leftover = food("Yesterday", yesterday)

        val data = filterHomeDayData(
            profile = null,
            entries = listOf(morning, leftover, evening),
            favoriteKeys = setOf("fav"),
            sortOrder = FoodLogSortOrder.LATEST_MEALS_FIRST.storageValue,
            day = day,
            zone = zone
        )

        assertEquals(day, data.date)
        assertEquals(listOf(evening, morning), data.todayEntries)
        assertEquals(FoodLogSortOrder.LATEST_MEALS_FIRST, data.foodLogSortOrder)
        assertEquals(setOf("fav"), data.favoriteKeys)
    }

    @Test
    fun mergePreservesWaterFastingAndAnalysisWrittenInBetween() {
        val water = WaterEntry(date = Instant.parse("2026-09-19T12:00:00Z"), milliliters = 500)
        val fast = FastingSession(startedAt = Instant.parse("2026-09-19T08:00:00Z"), goalMinutes = 16 * 60)
        val latest = HomeUiState(
            waterTodayMl = 500,
            waterEntriesToday = listOf(water),
            fastingSessions = listOf(fast),
            analyzing = false,
            foodSaveInProgress = true
        )
        val dayData = filterHomeDayData(
            profile = null,
            entries = listOf(food("Salad", Instant.parse("2026-09-19T13:00:00Z"))),
            favoriteKeys = emptySet(),
            sortOrder = null,
            day = day,
            zone = zone
        )

        val merged = latest.withDayData(dayData)

        assertEquals(500, merged.waterTodayMl)
        assertEquals(listOf(water), merged.waterEntriesToday)
        assertEquals(listOf(fast), merged.fastingSessions)
        assertEquals(false, merged.analyzing)
        assertTrue(merged.foodSaveInProgress)
        assertEquals(listOf("Salad"), merged.todayEntries.map { it.name })
    }

    private fun food(name: String, timestamp: Instant) = FoodEntry(
        id = UUID.randomUUID(),
        name = name,
        calories = 100,
        protein = 1.0,
        carbs = 1.0,
        fat = 1.0,
        timestamp = timestamp,
        source = FoodSource.MANUAL
    )
}
