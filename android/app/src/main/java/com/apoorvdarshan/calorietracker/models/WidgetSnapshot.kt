package com.apoorvdarshan.calorietracker.models

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One user-selected Home nutrient carried in the widget snapshot. Mirrors the
 * iOS WidgetNutrientValue payload shape.
 */
@Serializable
data class WidgetNutrient(
    val id: String,
    val label: String,
    val unit: String,
    val value: Double,
    val goal: Double
) {
    val progress: Double get() = if (goal > 0) minOf(1.0, value / goal) else 0.0
}

/**
 * Small Codable snapshot of today's totals + goals that the widget reads out of DataStore.
 * The main app writes it on every FoodStore / profile change; the widget re-reads on every
 * timeline refresh and on explicit updateAll() calls.
 */
@Serializable
data class WidgetSnapshot(
    @Serializable(with = InstantSerializer::class) val date: Instant,
    @Serializable(with = InstantSerializer::class) val dayStart: Instant,
    val calories: Int,
    val calorieGoal: Int,
    val protein: Double,
    val proteinGoal: Int,
    val carbs: Double,
    val carbsGoal: Int,
    val fat: Double,
    val fatGoal: Int,
    /** All 4 saved Home nutrients. Water visibility is derived without overwriting this list. */
    val homeNutrients: List<WidgetNutrient>? = null,
    /** User's theme gradient as raw RGB hex (e.g. 0xFF375F). Fud Pink when absent. */
    val themeStartHex: Int? = null,
    val themeEndHex: Int? = null,
    /** Defaults preserve decoding of snapshots written before the Water widget. */
    val waterTrackingEnabled: Boolean = false,
    val waterCurrentMl: Int = 0,
    val waterGoalMl: Int = 2_000,
    val waterUnitRaw: String = WaterUnit.Default.storageValue
) {
    val caloriesRemaining: Int get() = maxOf(0, calorieGoal - calories)
    val proteinRemaining: Double get() = maxOf(0.0, proteinGoal.toDouble() - protein)
    val carbsRemaining: Double get() = maxOf(0.0, carbsGoal.toDouble() - carbs)
    val fatRemaining: Double get() = maxOf(0.0, fatGoal.toDouble() - fat)
    val calorieProgress: Double get() = if (calorieGoal > 0) minOf(1.0, calories.toDouble() / calorieGoal) else 0.0
    val proteinProgress: Double get() = if (proteinGoal > 0) minOf(1.0, protein / proteinGoal) else 0.0
    val carbsProgress: Double get() = if (carbsGoal > 0) minOf(1.0, carbs / carbsGoal) else 0.0
    val fatProgress: Double get() = if (fatGoal > 0) minOf(1.0, fat / fatGoal) else 0.0
    val waterRemaining: Int get() = maxOf(0, waterGoalMl - waterCurrentMl)
    val waterProgress: Double get() = if (waterGoalMl > 0) minOf(1.0, waterCurrentMl.toDouble() / waterGoalMl) else 0.0
    val waterUnit: WaterUnit get() = WaterUnit.fromStorage(waterUnitRaw)

    val isStale: Boolean get() {
        val snapshotDay = dayStart.atZone(ZoneId.systemDefault()).toLocalDate()
        return snapshotDay != LocalDate.now()
    }

    /**
     * Nutrient bars to render on widgets: 4 saved choices, or the first 3 plus Water
     * when tracking is enabled (widgets stay on a 4-slot layout).
     */
    val displayedHomeNutrients: List<WidgetNutrient> get() {
        val selected = homeNutrients?.takeIf { it.isNotEmpty() } ?: defaultHomeNutrients
        val visible = selected.take(if (waterTrackingEnabled) 3 else 4)
        if (!waterTrackingEnabled) return visible
        return visible + waterHomeNutrient
    }

    private val defaultHomeNutrients: List<WidgetNutrient>
        get() = listOf(
            WidgetNutrient("protein", "Protein", "g", protein, proteinGoal.toDouble()),
            WidgetNutrient("carbs", "Carbs", "g", carbs, carbsGoal.toDouble()),
            WidgetNutrient("fat", "Fat", "g", fat, fatGoal.toDouble()),
            WidgetNutrient("fiber", "Fiber", "g", 0.0, OptionalNutrientGoals.Default.fiber.toDouble())
        )

    private val waterHomeNutrient: WidgetNutrient
        get() = WidgetNutrient(
            id = "water",
            label = "Water",
            unit = if (waterUnit == WaterUnit.FLUID_OUNCES) " fl oz" else "ml",
            value = waterUnit.displayAmount(waterCurrentMl),
            goal = waterUnit.displayAmount(waterGoalMl)
        )

    /** First selected nutrient — what the "Protein" widget actually tracks. */
    val primaryHomeNutrient: WidgetNutrient get() = displayedHomeNutrients.first()

    fun emptyForToday(): WidgetSnapshot = copy(
        date = Instant.now(),
        dayStart = todayStart(),
        waterCurrentMl = 0
    )

    companion object {
        fun placeholder(): WidgetSnapshot {
            val now = Instant.now()
            return WidgetSnapshot(
                date = now,
                dayStart = todayStart(),
                calories = 1247, calorieGoal = 2000,
                protein = 84.0, proteinGoal = 150,
                carbs = 132.0, carbsGoal = 220,
                fat = 42.0, fatGoal = 70,
                waterTrackingEnabled = true,
                waterCurrentMl = 1_250,
                waterGoalMl = 2_000
            )
        }

        fun empty(): WidgetSnapshot {
            val now = Instant.now()
            return WidgetSnapshot(
                date = now,
                dayStart = todayStart(),
                calories = 0, calorieGoal = 2000,
                protein = 0.0, proteinGoal = 150,
                carbs = 0.0, carbsGoal = 220,
                fat = 0.0, fatGoal = 70
            )
        }

        fun todayStart(): Instant =
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
    }
}
