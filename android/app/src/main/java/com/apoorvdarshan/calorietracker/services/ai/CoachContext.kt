package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.ActivityLevel
import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.BodyMeasurement
import com.apoorvdarshan.calorietracker.models.EnergyUnit
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WeightGoal
import com.apoorvdarshan.calorietracker.models.WorkoutDayPlan
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.services.WeightAnalysisService
import com.apoorvdarshan.calorietracker.services.WeightForecast
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneId

/**
 * The user's profile, formulas, forecast and data overview that ground Coach's answers.
 * Coach's system prompt and the voice assistant's instructions both carry it, so the two
 * always describe the user the same way. Model input, so it is not localized.
 */
object CoachContext {
    fun summaryLines(
        profile: UserProfile,
        weights: List<WeightEntry>,
        bodyFats: List<BodyFatEntry>,
        measurements: List<BodyMeasurement> = emptyList(),
        foods: List<FoodEntry>,
        fastingSessions: List<FastingSession> = emptyList(),
        heightMetric: Boolean,
        weightMetric: Boolean,
        workoutSessions: List<WorkoutSession> = emptyList(),
        workoutPlans: List<WorkoutDayPlan> = emptyList(),
        energyUnit: EnergyUnit = EnergyUnit.Default,
        today: LocalDate? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<String> {
        val forecast: WeightForecast = WeightAnalysisService.compute(weights, foods, profile)

        fun wUnit(kg: Double): String =
            if (weightMetric) String.format(Locale.US, "%.1f kg", kg)
            else String.format(Locale.US, "%.1f lbs", kg * 2.20462)

        fun weekly(kg: Double): String =
            if (weightMetric) String.format(Locale.US, "%+.2f kg/week", kg)
            else String.format(Locale.US, "%+.2f lbs/week", kg * 2.20462)

        val bmrFormula = when {
            profile.usesBodyFatForBMR -> "Katch-McArdle (uses body fat %)"
            profile.bodyFatPercentage != null -> "Mifflin-St Jeor (user disabled the body-fat override in Settings)"
            else -> "Mifflin-St Jeor (body fat not set)"
        }

        val lines = mutableListOf<String>()
        lines.add("## User profile")
        lines.add("- Gender: ${profile.gender.name.lowercase()}")
        lines.add("- Age: ${profile.age}")
        val heightStr = if (heightMetric) String.format(Locale.US, "%.0f cm", profile.heightCm)
        else String.format(Locale.US, "%.1f in", profile.heightCm / 2.54)
        lines.add("- Height: $heightStr")
        lines.add("- Current weight: ${wUnit(profile.weightKg)}")
        lines.add("- Activity: ${activityEnglish(profile.activityLevel)}")
        lines.add("- Goal: ${goalEnglish(profile.goal)}")
        profile.goalWeightKg?.let { lines.add("- Goal weight: ${wUnit(it)}") }
        profile.bodyFatPercentage?.let { lines.add("- Body fat: ${(it * 100).toInt()}%") }
        profile.goalBodyFatPercentage?.let { lines.add("- Goal body fat: ${(it * 100).toInt()}%") }
        // Data stays kcal; a kJ user is answered in kJ. Nothing changes for kcal users.
        energyUnit.aiReplyInstruction(toolArguments = true)?.let { lines.add("- $it") }
        lines.add("")
        lines.add("## Formulas in use")
        lines.add("- BMR: $bmrFormula. Current BMR ≈ ${profile.bmr.toInt()} kcal/day")
        lines.add("- TDEE: BMR × activity multiplier ≈ ${profile.tdee.toInt()} kcal/day")
        lines.add("- Calorie goal: ${profile.effectiveCalories} kcal/day")
        lines.add("- Macro targets: ${profile.effectiveProtein}g protein, ${profile.effectiveCarbs}g carbs, ${profile.effectiveFat}g fat")
        if (today != null) {
            val todayFoods = foods.filter { it.timestamp.atZone(zoneId).toLocalDate() == today }
            val calories = todayFoods.sumOf { it.calories }
            val protein = todayFoods.sumOf { it.protein }
            val carbs = todayFoods.sumOf { it.carbs }
            val fat = todayFoods.sumOf { it.fat }
            fun grams(value: Double): String = String.format(Locale.US, "%.1f", value)

            lines.add("")
            lines.add("## Today's logged nutrition (local diary date: $today)")
            if (todayFoods.isEmpty()) {
                lines.add("- No food entries are logged for this date. The diary may be incomplete; this does not establish that the user ate nothing.")
            }
            lines.add(
                "- Logged so far: $calories kcal of ${profile.effectiveCalories} kcal; " +
                    "${profile.effectiveCalories - calories} kcal to goal. " +
                    "Protein: ${grams(protein)} g of ${profile.effectiveProtein} g; " +
                    "${grams(profile.effectiveProtein - protein)} g to goal. " +
                    "Carbs: ${grams(carbs)} g of ${profile.effectiveCarbs} g; " +
                    "${grams(profile.effectiveCarbs - carbs)} g to goal. " +
                    "Fat: ${grams(fat)} g of ${profile.effectiveFat} g; " +
                    "${grams(profile.effectiveFat - fat)} g to goal."
            )
            lines.add("- Totals are based only on diary entries; the diary may be incomplete. A negative amount to goal means logged intake is above target.")
        }
        lines.add("")
        lines.add("## Computed forecast (from their logged data)")
        if (forecast.hasEnoughData) {
            lines.add("- Days of food logged (last 90d): ${forecast.daysOfFoodData}")
            lines.add("- Weight entries available: ${forecast.weightEntriesUsed}")
            lines.add("- Avg daily intake: ${forecast.avgDailyCalories} kcal")
            val balanceSign = if (forecast.dailyEnergyBalance >= 0) "+" else ""
            lines.add("- Daily energy balance: ${balanceSign}${forecast.dailyEnergyBalance} kcal")
            lines.add("- Predicted change (from diet): ${weekly(forecast.predictedWeeklyChangeKg)}")
            forecast.observedWeeklyChangeKg?.let { lines.add("- Observed change (from scale): ${weekly(it)}") }
            lines.add("- Expected weight in 30 days: ${wUnit(forecast.predictedWeight30dKg)}")
            lines.add("- Expected weight in 60 days: ${wUnit(forecast.predictedWeight60dKg)}")
            lines.add("- Expected weight in 90 days: ${wUnit(forecast.predictedWeight90dKg)}")
            forecast.daysToGoal?.let { lines.add("- Days to goal at current pace: ~$it days") }
            if (forecast.trendsDisagree) {
                lines.add("- NOTE: Predicted and observed trends differ by >0.3 kg/week — user may be under-logging food.")
            }
        } else {
            lines.add("- Not enough data yet (need ≥2 days food + ≥2 weights). Encourage the user to log more.")
        }
        lines.add("")
        lines.add("## Data available")
        lines.add("- ${weights.size} weight entries, ${bodyFats.size} body-fat readings, ${foods.size} food entries logged total. Use get_data_summary to see exact date ranges.")
        lines.add("- ${fastingSessions.size} explicitly tracked fasting sessions are available. Never treat a missing food log as a fast.")
        lines.add("- ${workoutSessions.size} completed strength workouts and ${workoutPlans.size} dated workout plans are available through the workout tools.")
        lines.add("- Workout logs may guide training, recovery, exercise selection, and progressive-overload advice. Never use estimated workout burn or workout volume to recalculate calorie/macro targets, alter the nutrition forecast, or invent energy expenditure.")
        measurements.maxByOrNull { it.date }?.promptSummary(profile.gender, profile.heightCm)?.let { summary ->
            lines.add("")
            lines.add("## Body measurements (latest)")
            lines.add("- $summary")
            lines.add("A shrinking waist alongside steady or rising weight is recomposition (fat down, muscle up) — read it that way instead of calling a flat scale a plateau. Treat the US-Navy body-fat figure as an estimate.")
        }
        return lines
    }

    fun activityEnglish(level: ActivityLevel): String = when (level) {
        ActivityLevel.SEDENTARY -> "Sedentary"
        ActivityLevel.LIGHT -> "Light"
        ActivityLevel.MODERATE -> "Moderate"
        ActivityLevel.ACTIVE -> "Active"
        ActivityLevel.VERY_ACTIVE -> "Very Active"
        ActivityLevel.EXTRA_ACTIVE -> "Extra Active"
    }

    fun goalEnglish(goal: WeightGoal): String = when (goal) {
        WeightGoal.LOSE -> "Lose Weight"
        WeightGoal.MAINTAIN -> "Maintain"
        WeightGoal.GAIN -> "Gain Weight"
    }
}
