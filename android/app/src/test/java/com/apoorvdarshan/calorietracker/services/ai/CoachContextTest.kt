package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.UserProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue

class CoachContextTest {
    private val today = LocalDate.of(2026, 10, 18)
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun includesOnlyTodayLocalDiaryEntriesAndGoalRemainders() {
        val foods = listOf(
            entry("2026-10-18T06:59:59Z", calories = 900, protein = 90.0, carbs = 80.0, fat = 30.0),
            entry("2026-10-18T07:00:00Z", calories = 500, protein = 35.25, carbs = 20.0, fat = 10.0),
            entry("2026-10-18T12:00:00Z", calories = 750, protein = 40.25, carbs = 79.5, fat = 30.0)
        )

        val lines = contextLines(foods)

        assertTrue(lines.any { it == "## Today's logged nutrition (local diary date: 2026-10-18)" })
        assertTrue(
            lines.any {
                it == "- Logged so far: 1250 kcal of 2000 kcal; 750 kcal to goal. " +
                    "Protein: 75.5 g of 150 g; 74.5 g to goal. " +
                    "Carbs: 99.5 g of 220 g; 120.5 g to goal. " +
                    "Fat: 40.0 g of 70 g; 30.0 g to goal."
            }
        )
    }

    @Test
    fun emptyDayIsNotDescribedAsProofOfNoFoodIntake() {
        val lines = contextLines(emptyList())

        assertTrue(
            lines.any {
                it == "- No food entries are logged for this date. The diary may be incomplete; " +
                    "this does not establish that the user ate nothing."
            }
        )
        assertTrue(
            lines.any {
                it.startsWith("- Logged so far: 0 kcal of 2000 kcal; 2000 kcal to goal.")
            }
        )
    }

    private fun contextLines(foods: List<FoodEntry>) = CoachContext.summaryLines(
        profile = UserProfile(
            customCalories = 2_000,
            customProtein = 150,
            customCarbs = 220,
            customFat = 70
        ),
        weights = emptyList(),
        bodyFats = emptyList(),
        foods = foods,
        heightMetric = true,
        weightMetric = true,
        today = today,
        zoneId = zone
    )

    private fun entry(
        timestamp: String,
        calories: Int,
        protein: Double,
        carbs: Double,
        fat: Double
    ) = FoodEntry(
        name = "Meal",
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        timestamp = Instant.parse(timestamp),
        source = FoodSource.MANUAL
    )
}
