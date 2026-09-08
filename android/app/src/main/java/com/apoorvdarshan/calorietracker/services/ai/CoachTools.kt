package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.WorkoutDate
import com.apoorvdarshan.calorietracker.models.WorkoutDayPlan
import com.apoorvdarshan.calorietracker.models.WorkoutPreferences
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.google.gson.GsonBuilder
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * On-demand data accessor for Coach. Nutrition and strength-training history
 * stay out of the system prompt until the model requests the exact slice it
 * needs. All four workout tools are always advertised: an empty workout store
 * simply produces empty, truthful payloads.
 */
class CoachTools(
    private val weights: List<WeightEntry>,
    private val bodyFats: List<BodyFatEntry>,
    private val foods: List<FoodEntry>,
    private val fastingSessions: List<FastingSession> = emptyList(),
    private val workoutSessions: List<WorkoutSession> = emptyList(),
    private val workoutPlans: List<WorkoutDayPlan> = emptyList(),
    private val workoutPreferences: WorkoutPreferences = WorkoutPreferences(),
    private val workoutPlanWeightUnit: WorkoutWeightUnit = WorkoutWeightUnit.LBS,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    /** Android provider loops already deal in [JSONObject], so keep this adapter at the edge. */
    fun execute(name: String, args: JSONObject): String = execute(
        name,
        ToolArguments(
            from = args.optString("from").takeIf { it.isNotBlank() },
            to = args.optString("to").takeIf { it.isNotBlank() },
            limit = (args.opt("limit") as? Number)?.toInt()
        )
    )

    /** JVM-friendly entry point used by focused unit tests and non-Android callers. */
    fun execute(name: String, args: Map<String, Any?> = emptyMap()): String = execute(
        name,
        ToolArguments(
            from = args["from"] as? String,
            to = args["to"] as? String,
            limit = when (val value = args["limit"]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        )
    )

    private fun execute(name: String, args: ToolArguments): String = when (name) {
        "get_data_summary" -> getDataSummary()
        "get_weight_history" -> getWeightHistory(args)
        "get_body_fat_history" -> getBodyFatHistory(args)
        "get_calorie_totals" -> getCalorieTotals(args)
        "get_food_entries" -> getFoodEntries(args)
        "get_fasting_history" -> getFastingHistory(args)
        "get_workout_history" -> getWorkoutHistory(args)
        "get_workout_plans" -> getWorkoutPlans(args)
        "get_workout_preferences" -> getWorkoutPreferences()
        "get_training_summary" -> getTrainingSummary(args)
        else -> jsonError("Unknown tool: $name. Available tools: ${TOOL_NAMES.joinToString(", ")}")
    }

    // MARK: - Nutrition tools

    private fun getDataSummary(): String {
        val weightDates = weights.map { it.date }.sorted()
        val bodyFatDates = bodyFats.map { it.date }.sorted()
        val foodDates = foods.map { it.timestamp }.sorted()
        val effectiveSessions = effectiveWorkoutSessions()
        val workoutDateKeys = effectiveSessions.map { it.diaryDateKey }.sorted()

        return json(
            linkedMapOf(
                "weights" to dataRangePayload(weights.size, weightDates.firstOrNull(), weightDates.lastOrNull()),
                "body_fats" to dataRangePayload(bodyFats.size, bodyFatDates.firstOrNull(), bodyFatDates.lastOrNull()),
                "foods" to dataRangePayload(foods.size, foodDates.firstOrNull(), foodDates.lastOrNull()),
                "fasting" to linkedMapOf(
                    "count" to fastingSessions.size,
                    "active" to fastingSessions.any { it.isActive }
                ),
                "workouts" to linkedMapOf(
                    "count" to effectiveSessions.size,
                    "first_date" to workoutDateKeys.firstOrNull(),
                    "last_date" to workoutDateKeys.lastOrNull()
                ),
                "workout_plans" to linkedMapOf("count" to workoutPlans.size)
            )
        )
    }

    private fun getWeightHistory(args: ToolArguments): String {
        val range = parseRange(args)
        val filtered = weights
            .filter { it.date in range.fromInstant..range.toInstant }
            .sortedBy { it.date }
            .take(args.boundedLimit(default = 365, maximum = 365))
        return json(
            linkedMapOf(
                "from" to range.fromDate.toString(),
                "to" to range.toDate.toString(),
                "count" to filtered.size,
                "weights" to filtered.map { entry ->
                    linkedMapOf(
                        "date" to isoDate(entry.date),
                        "kg" to round1(entry.weightKg),
                        "lbs" to round1(entry.weightKg * 2.20462)
                    )
                }
            )
        )
    }

    private fun getBodyFatHistory(args: ToolArguments): String {
        val range = parseRange(args)
        val filtered = bodyFats
            .filter { it.date in range.fromInstant..range.toInstant }
            .sortedBy { it.date }
            .take(args.boundedLimit(default = 365, maximum = 365))
        return json(
            linkedMapOf(
                "from" to range.fromDate.toString(),
                "to" to range.toDate.toString(),
                "count" to filtered.size,
                "body_fats" to filtered.map { entry ->
                    linkedMapOf(
                        "date" to isoDate(entry.date),
                        "percent" to (entry.bodyFatFraction * 100).toInt()
                    )
                }
            )
        )
    }

    private fun getCalorieTotals(args: ToolArguments): String {
        val range = parseRange(args)
        val daily = sortedMapOf<String, Int>()
        for (food in foods) {
            if (food.timestamp !in range.fromInstant..range.toInstant) continue
            val day = isoDate(food.timestamp)
            daily[day] = (daily[day] ?: 0) + food.calories
        }
        return json(
            linkedMapOf(
                "from" to range.fromDate.toString(),
                "to" to range.toDate.toString(),
                "days_with_data" to daily.size,
                "totals" to daily.map { (day, kcal) -> linkedMapOf("date" to day, "kcal" to kcal) }
            )
        )
    }

    private fun getFoodEntries(args: ToolArguments): String {
        val range = parseRange(args)
        val filtered = foods
            .filter { it.timestamp in range.fromInstant..range.toInstant }
            .sortedBy { it.timestamp }
            .take(args.boundedLimit(default = 200, maximum = 365))
        val entries = filtered.map { food ->
            linkedMapOf<String, Any?>(
                "date" to isoDate(food.timestamp),
                "name" to food.name,
                "kcal" to food.calories,
                "protein_g" to food.protein,
                "carbs_g" to food.carbs,
                "fat_g" to food.fat,
                "meal_type" to mealTypeName(food.mealType),
                "source" to sourceName(food.source)
            ).apply {
                putIfPresent("serving_size_g", food.servingSizeGrams)
                putIfPresent("sugar_g", food.sugar)
                putIfPresent("added_sugar_g", food.addedSugar)
                putIfPresent("fiber_g", food.fiber)
                putIfPresent("saturated_fat_g", food.saturatedFat)
                putIfPresent("monounsaturated_fat_g", food.monounsaturatedFat)
                putIfPresent("polyunsaturated_fat_g", food.polyunsaturatedFat)
                putIfPresent("cholesterol_mg", food.cholesterol)
                putIfPresent("caffeine_mg", food.caffeine)
                food.supplementalNutrients.forEach { (key, value) ->
                    put("${key}_g", value)
                }
                putIfPresent("sodium_mg", food.sodium)
                putIfPresent("potassium_mg", food.potassium)
                putIfPresent("trans_fat_g", food.transFat)
                putIfPresent("calcium_mg", food.calcium)
                putIfPresent("iron_mg", food.iron)
                putIfPresent("magnesium_mg", food.magnesium)
                putIfPresent("zinc_mg", food.zinc)
                putIfPresent("vitamin_a_mcg", food.vitaminA)
                putIfPresent("vitamin_c_mg", food.vitaminC)
                putIfPresent("vitamin_d_mcg", food.vitaminD)
                putIfPresent("vitamin_b12_mcg", food.vitaminB12)
                putIfPresent("vitamin_e_mg", food.vitaminE)
                putIfPresent("vitamin_k_mcg", food.vitaminK)
                putIfPresent("folate_mcg", food.folate)
                putIfPresent("omega_3_g", food.omega3)
            }
        }
        return json(
            linkedMapOf(
                "from" to range.fromDate.toString(),
                "to" to range.toDate.toString(),
                "count" to entries.size,
                "foods" to entries
            )
        )
    }

    private fun getFastingHistory(args: ToolArguments): String {
        val range = parseRange(args)
        val now = clock.instant()
        val sessions = fastingSessions
            .filter { session ->
                val endpoint = session.endedAt ?: now
                endpoint >= range.fromInstant && session.startedAt <= range.toInstant
            }
            .sortedBy { it.startedAt }
            .take(args.boundedLimit(default = 200, maximum = 365))
            .map { session ->
                linkedMapOf(
                    "status" to if (session.isActive) "active" else "completed",
                    "started_at" to session.startedAt.toString(),
                    "ended_at" to session.endedAt?.toString(),
                    "duration_minutes" to session.durationSeconds(now) / 60,
                    "goal_minutes" to session.goalMinutes,
                    "goal_reached" to (session.durationSeconds(now) >= session.goalMinutes.toLong() * 60)
                )
            }
        return json(
            linkedMapOf(
                "from" to range.fromDate.toString(),
                "to" to range.toDate.toString(),
                "count" to sessions.size,
                "sessions" to sessions
            )
        )
    }

    // MARK: - Workout tools

    private fun getWorkoutHistory(args: ToolArguments): String {
        val range = parseRange(args)
        val sessions = effectiveWorkoutSessions()
            .filter { session ->
                WorkoutDate.parse(session.diaryDateKey)?.let { it in range.fromDate..range.toDate } == true
            }
            .sortedWith(compareBy<WorkoutSession> { it.diaryDateKey }.thenBy { it.completedAt })
            .take(args.boundedLimit(default = 100, maximum = 200))
        return json(
            linkedMapOf(
                "from" to range.fromDate.toString(),
                "to" to range.toDate.toString(),
                "count" to sessions.size,
                "workouts" to sessions.map(::workoutSessionPayload)
            )
        )
    }

    private fun getWorkoutPlans(args: ToolArguments): String {
        val range = parsePlanRange(args)
        val now = clock.instant()
        val plans = workoutPlans
            .filter { it.exercises.isNotEmpty() }
            .filter { plan -> WorkoutDate.parse(plan.dateKey)?.let { it in range.from..range.to } == true }
            .sortedBy { it.dateKey }
            .take(args.boundedLimit(default = 120, maximum = 120))
        return json(
            linkedMapOf(
                "from" to range.from.toString(),
                "to" to range.to.toString(),
                "count" to plans.size,
                "plans" to plans.map { plan ->
                    linkedMapOf(
                        "date" to plan.dateKey,
                        "exercises" to plan.exercises.map { exercise ->
                            linkedMapOf<String, Any?>(
                                "catalog_id" to exercise.itemId,
                                "name" to exercise.name,
                                "target_muscles" to exercise.primaryMuscles,
                                "equipment" to exercise.equipment,
                                "performed" to exercise.hasCalculableWork,
                                "sets" to exercise.sets.mapIndexed { index, set ->
                                    linkedMapOf<String, Any?>(
                                        "set" to index + 1,
                                        "weight_unit" to (set.weightUnit ?: workoutPlanWeightUnit).storageValue
                                    ).apply {
                                        if (set.weight.isNotEmpty()) put("weight", set.weight)
                                        if (set.reps.isNotEmpty()) put("reps", set.reps.toIntOrNull() ?: 0)
                                        if (set.rpe.isNotEmpty()) {
                                            put("rpe", normalizedDouble(set.rpe) ?: 0.0)
                                            put("rpe_scale", (set.rpeScale ?: workoutPreferences.rpeScale).title)
                                        }
                                    }
                                }
                            ).apply {
                                exercise.timer?.let { timer ->
                                    val elapsed = timer.elapsedSeconds(now)
                                    val state = when {
                                        timer.isRunning -> "running"
                                        timer.isSaved -> "saved"
                                        elapsed > 0.0 -> "paused"
                                        else -> "idle"
                                    }
                                    put("timer", linkedMapOf<String, Any?>(
                                        "state" to state,
                                        "elapsed_seconds" to elapsed
                                    ).apply {
                                        timer.runningSince?.let { put("running_since", DateTimeFormatter.ISO_INSTANT.format(it)) }
                                    })
                                    put("intensity", com.apoorvdarshan.calorietracker.models.WorkoutBurnEstimator.timerIntensity(exercise, workoutPreferences.rpeScale).title)
                                    if (timer.savedSeconds > 0.0) put("duration_seconds", timer.savedSeconds)
                                }
                            }
                        }
                    )
                }
            )
        )
    }

    private fun getWorkoutPreferences(): String {
        val strength = workoutPreferences.strength
        return json(
            linkedMapOf(
                "configured" to true,
                "target_muscles" to workoutPreferences.targetMuscles.sorted(),
                "issues_or_injuries" to workoutPreferences.issues.map { it.title }.sorted(),
                "additional_issues" to workoutPreferences.additionalIssues,
                "frequency_days_per_week" to workoutPreferences.frequencyDays,
                "duration_minutes" to workoutPreferences.durationMinutes,
                "split" to workoutPreferences.split.title,
                "custom_split" to workoutPreferences.customSplit,
                "equipment" to workoutPreferences.equipment.sorted(),
                "rpe_scale" to workoutPreferences.rpeScale.title,
                "strength_kg" to linkedMapOf(
                    "bench_press" to finiteOrNull(strength.benchPressKg),
                    "squat" to finiteOrNull(strength.squatKg),
                    "deadlift" to finiteOrNull(strength.deadliftKg),
                    "overhead_press" to finiteOrNull(strength.overheadPressKg)
                )
            )
        )
    }

    private fun getTrainingSummary(args: ToolArguments): String {
        val range = parseRange(args)
        val sessions = effectiveWorkoutSessions().filter { session ->
            WorkoutDate.parse(session.diaryDateKey)?.let { it in range.fromDate..range.toDate } == true
        }
        val aggregates = mutableMapOf<String, ExerciseAggregate>()
        for (session in sessions) {
            for (exercise in session.exercises) {
                val aggregate = aggregates.getOrPut(exercise.name) { ExerciseAggregate() }
                aggregate.sessionIds.add(session.id)
                exercise.durationSeconds?.takeIf { it.isFinite() && it > 0.0 }?.let {
                    aggregate.durationSeconds += it
                }
                for (set in exercise.sets.filter { it.isPerformed }) {
                    aggregate.sets += 1
                    val reps = set.reps.toIntOrNull() ?: 0
                    aggregate.reps += reps
                    weightKg(set.weight, set.weightUnit)?.let { kilograms ->
                        aggregate.volumeKg += kilograms * reps
                        aggregate.bestLoadKg = maxOf(aggregate.bestLoadKg ?: kilograms, kilograms)
                    }
                    normalizedDouble(set.rpe)?.takeIf { it.isFinite() }?.let { rpe ->
                        val scale = set.rpeScale?.title ?: "Unspecified"
                        val rpeAggregate = aggregate.rpeByScale.getOrPut(scale) { RpeAggregate() }
                        rpeAggregate.total += rpe
                        rpeAggregate.count += 1
                    }
                }
            }
        }

        val exercisePayloads = aggregates.toSortedMap().map { (name, aggregate) ->
            linkedMapOf<String, Any?>(
                "name" to name,
                "sessions" to aggregate.sessionIds.size,
                "duration_seconds" to aggregate.durationSeconds,
                "sets" to aggregate.sets,
                "reps" to aggregate.reps,
                "external_load_volume_kg" to round1(aggregate.volumeKg)
            ).apply {
                aggregate.bestLoadKg?.let { put("best_load_kg", round1(it)) }
                if (aggregate.rpeByScale.isNotEmpty()) {
                    val averages = aggregate.rpeByScale.toSortedMap().mapValues { (_, value) ->
                        round1(value.total / value.count)
                    }
                    put("average_rpe_by_scale", averages)
                    if (averages.size == 1) {
                        val only = averages.entries.first()
                        put("average_rpe", only.value)
                        put("rpe_scale", only.key)
                    }
                }
            }
        }
        return json(
            linkedMapOf(
                "from" to range.fromDate.toString(),
                "to" to range.toDate.toString(),
                "sessions" to sessions.size,
                "sets" to sessions.sumOf { it.performedSetCount },
                "reps" to sessions.sumOf { it.repCount },
                "calories_burned" to sessions.sumOf { it.caloriesBurned ?: 0 },
                "minutes" to sessions.sumOf { it.durationMinutes },
                "timed_exercise_seconds" to aggregates.values.sumOf { it.durationSeconds },
                "by_exercise" to exercisePayloads
            )
        )
    }

    private fun workoutSessionPayload(session: WorkoutSession): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "id" to session.id.toString(),
            "date" to session.diaryDateKey,
            "started_at" to DateTimeFormatter.ISO_INSTANT.format(session.startedAt),
            "completed_at" to DateTimeFormatter.ISO_INSTANT.format(session.completedAt),
            "duration_seconds" to session.durationSeconds,
            "exercises" to session.exercises.map { exercise ->
                linkedMapOf<String, Any?>(
                    "catalog_id" to exercise.itemId,
                    "name" to exercise.name,
                    "target_muscles" to exercise.targetMuscles,
                    "equipment" to exercise.equipment,
                    "performed" to ((exercise.durationSeconds ?: 0.0) > 0.0 || exercise.sets.any { it.isPerformed }),
                    "sets" to exercise.sets.map { set ->
                        linkedMapOf<String, Any?>(
                            "set" to set.setNumber,
                            "performed" to set.isPerformed,
                            "weight_unit" to set.weightUnit.storageValue
                        ).apply {
                            if (set.weight.isNotEmpty()) put("weight", normalizedDouble(set.weight) ?: 0.0)
                            weightKg(set.weight, set.weightUnit)?.let { put("weight_kg", it) }
                            if (set.reps.isNotEmpty()) put("reps", set.reps.toIntOrNull() ?: 0)
                            if (set.rpe.isNotEmpty()) {
                                put("rpe", normalizedDouble(set.rpe) ?: 0.0)
                                put("rpe_scale", set.rpeScale?.title ?: "Unspecified")
                            }
                        }
                    }
                ).apply {
                    exercise.durationSeconds?.takeIf { it.isFinite() && it > 0.0 }?.let {
                        put("duration_seconds", it)
                        exercise.intensity?.let { intensity -> put("intensity", intensity.title) }
                    }
                }
            }
        ).apply {
            session.caloriesBurned?.let { put("calories_burned", it) }
        }

    /**
     * Timer-era builds could leave multiple completed snapshots for one diary
     * date. Once a calculated burn exists, its newest sync version is the
     * authoritative whole-day snapshot and must replace every same-day row.
     */
    private fun effectiveWorkoutSessions(): List<WorkoutSession> = workoutSessions
        .groupBy { it.diaryDateKey }
        .values
        .flatMap { sessions ->
            val calculated = sessions.filter { it.caloriesBurned != null }
            if (calculated.isEmpty()) {
                sessions
            } else {
                listOf(
                    calculated.maxWithOrNull(
                        compareBy<WorkoutSession> { it.healthSyncVersion ?: 0 }
                            .thenBy { it.completedAt }
                    )!!
                )
            }
        }

    // MARK: - Helpers

    private fun parseRange(args: ToolArguments): DateRange {
        val toDate = parseDate(args.to) ?: LocalDate.now(clock)
        val fromDate = parseDate(args.from) ?: toDate.minusDays(30)
        val zone = clock.zone
        return DateRange(
            fromDate = fromDate,
            toDate = toDate,
            fromInstant = fromDate.atStartOfDay(zone).toInstant(),
            toInstant = toDate.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
        )
    }

    private fun parsePlanRange(args: ToolArguments): LocalDateRange {
        val today = LocalDate.now(clock)
        return LocalDateRange(
            from = parseDate(args.from) ?: today.minusDays(14),
            to = parseDate(args.to) ?: today.plusDays(90)
        )
    }

    private fun dataRangePayload(count: Int, first: Instant?, last: Instant?): Map<String, Any?> =
        linkedMapOf(
            "count" to count,
            "first_date" to first?.let(::isoDate),
            "last_date" to last?.let(::isoDate)
        )

    private fun isoDate(instant: Instant): String = instant.atZone(clock.zone).toLocalDate().toString()

    private fun parseDate(value: String?): LocalDate? = value?.let {
        runCatching { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }

    private fun normalizedDouble(value: String): Double? =
        value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun weightKg(value: String, unit: WorkoutWeightUnit): Double? {
        val numeric = normalizedDouble(value)?.takeIf { it > 0.0 } ?: return null
        return if (unit == WorkoutWeightUnit.LBS) numeric / POUNDS_PER_KILOGRAM else numeric
    }

    private fun finiteOrNull(value: Double?): Double? = value?.takeIf { it.isFinite() }

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    private fun MutableMap<String, Any?>.putIfPresent(key: String, value: Double?) {
        value?.takeIf { it.isFinite() }?.let { put(key, it) }
    }

    private fun sourceName(source: FoodSource): String = when (source) {
        FoodSource.SNAP_FOOD -> "snapFood"
        FoodSource.NUTRITION_LABEL -> "nutritionLabel"
        FoodSource.BARCODE -> "barcode"
        FoodSource.TEXT_INPUT -> "textInput"
        FoodSource.MANUAL -> "manual"
    }

    private fun mealTypeName(mealType: MealType): String = when (mealType) {
        MealType.BREAKFAST -> "breakfast"
        MealType.LUNCH -> "lunch"
        MealType.DINNER -> "dinner"
        MealType.SNACK -> "snack"
        MealType.OTHER -> "other"
    }

    private fun json(payload: Any): String = GSON.toJson(payload)

    private fun jsonError(message: String): String = json(linkedMapOf("error" to message))

    private data class ToolArguments(
        val from: String? = null,
        val to: String? = null,
        val limit: Int? = null
    ) {
        fun boundedLimit(default: Int, maximum: Int): Int = limit?.coerceIn(1, maximum) ?: default
    }

    private data class DateRange(
        val fromDate: LocalDate,
        val toDate: LocalDate,
        val fromInstant: Instant,
        val toInstant: Instant
    )

    private data class LocalDateRange(val from: LocalDate, val to: LocalDate)

    private data class ExerciseAggregate(
        val sessionIds: MutableSet<UUID> = mutableSetOf(),
        var durationSeconds: Double = 0.0,
        var sets: Int = 0,
        var reps: Int = 0,
        var volumeKg: Double = 0.0,
        var bestLoadKg: Double? = null,
        val rpeByScale: MutableMap<String, RpeAggregate> = mutableMapOf()
    )

    private data class RpeAggregate(var total: Double = 0.0, var count: Int = 0)

    companion object {
        val NUTRITION_TOOL_NAMES = listOf(
            "get_data_summary",
            "get_weight_history",
            "get_body_fat_history",
            "get_calorie_totals",
            "get_food_entries",
            "get_fasting_history"
        )

        val WORKOUT_TOOL_NAMES = listOf(
            "get_workout_history",
            "get_workout_plans",
            "get_workout_preferences",
            "get_training_summary"
        )

        /** Workout tools are deliberately always available, even before the first session. */
        val TOOL_NAMES: List<String> = NUTRITION_TOOL_NAMES + WORKOUT_TOOL_NAMES

        val TOOL_DESCRIPTIONS: Map<String, String> = mapOf(
            "get_data_summary" to "Get a quick summary of the user's available data: total counts and earliest/latest dates for weights, body-fat readings, and food entries. Call this first when the user asks anything about their history range or data spanning more than 14 days.",
            "get_weight_history" to "Fetch weight entries between two dates (inclusive). Returns date + weight (kg + lbs). Use this when the user asks about specific past dates or weight trends older than the last 10 entries.",
            "get_body_fat_history" to "Fetch body-fat readings between two dates (inclusive). Returns date + percent. Use when the user asks about body composition trends older than the last 10 readings.",
            "get_calorie_totals" to "Daily calorie totals (sum of all logged foods per day) between two dates. Returns date + kcal. Use when the user asks about intake patterns older than the last 14 days.",
            "get_food_entries" to "Individual logged food items (name + calories + macros) between two dates. Use when the user asks about specific meals, what they ate on a given date, or wants macro breakdowns rather than just kcal totals.",
            "get_fasting_history" to "Fetch explicitly tracked fasting sessions between two dates, including start/end timestamps, duration, goal, and whether the goal was reached. Never infer fasting from missing food logs.",
            "get_workout_history" to "Fetch completed workouts between two dates, including calculated calorie burn, saved exercise durations and intensity, and logged sets with weight, reps, and RPE. A timed exercise can be performed without any reps or sets.",
            "get_workout_plans" to "Fetch dated workout diary plans, set targets, saved exercise durations, and current timer state. Running or paused timer time is unsaved. Optional ISO from/to dates narrow the result; without them it returns recent and upcoming plans around today.",
            "get_workout_preferences" to "Fetch workout-only preferences such as target muscles, injuries or issues, equipment, schedule, split, RPE scale, and strength numbers.",
            "get_training_summary" to "Summarize workouts between two dates: calculated calorie burn plus sessions, saved exercise duration, sets, reps, volume, best load, and average RPE by exercise. Timed cardio does not require reps or sets."
        )

        /** One schema source is wrapped for Gemini, Anthropic, and OpenAI by [ChatService]. */
        fun parameterSchemaFor(toolName: String): Map<String, Any> {
            if (toolName == "get_data_summary" || toolName == "get_workout_preferences") {
                return linkedMapOf("type" to "object", "properties" to emptyMap<String, Any>())
            }
            if (toolName == "get_workout_plans") {
                return linkedMapOf(
                    "type" to "object",
                    "properties" to linkedMapOf(
                        "from" to linkedMapOf("type" to "string", "description" to "Optional ISO date yyyy-MM-dd, inclusive start"),
                        "to" to linkedMapOf("type" to "string", "description" to "Optional ISO date yyyy-MM-dd, inclusive end"),
                        "limit" to linkedMapOf("type" to "integer", "description" to "Optional max plans to return")
                    )
                )
            }
            return linkedMapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "from" to linkedMapOf("type" to "string", "description" to "ISO date yyyy-MM-dd, inclusive start"),
                    "to" to linkedMapOf("type" to "string", "description" to "ISO date yyyy-MM-dd, inclusive end"),
                    "limit" to linkedMapOf("type" to "integer", "description" to "Optional max entries to return")
                ),
                "required" to listOf("from", "to")
            )
        }

        private const val POUNDS_PER_KILOGRAM = 2.204_622_621_8
        private val GSON = GsonBuilder().serializeNulls().create()
    }
}
