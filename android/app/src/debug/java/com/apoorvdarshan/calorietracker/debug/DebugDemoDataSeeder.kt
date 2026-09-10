package com.apoorvdarshan.calorietracker.debug

import com.apoorvdarshan.calorietracker.FudAIApp
import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.BodyMeasurement
import com.apoorvdarshan.calorietracker.models.CompletedExercise
import com.apoorvdarshan.calorietracker.models.CompletedSet
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.PlannedExercise
import com.apoorvdarshan.calorietracker.models.PlannedSet
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WeightGoal
import com.apoorvdarshan.calorietracker.models.WorkoutDayPlan
import com.apoorvdarshan.calorietracker.models.WorkoutPreferences
import com.apoorvdarshan.calorietracker.models.WorkoutRpeScale
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.models.WorkoutSplit
import com.apoorvdarshan.calorietracker.models.WorkoutStrengthNumbers
import com.apoorvdarshan.calorietracker.models.WorkoutTabMode
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.first

internal data class DebugSeedReport(
    val days: Int,
    val throughDate: LocalDate,
    val foodEntries: Int,
    val weightEntries: Int,
    val bodyFatEntries: Int,
    val bodyMeasurements: Int,
    val waterEntries: Int,
    val fastingSessions: Int,
    val workoutSessions: Int
)

/**
 * Builds a year of local demo history, with an optional future horizon, without calling any sync API.
 *
 * This file lives under src/debug, so neither the dataset nor its launch hook can be packaged in
 * release. UUIDs are deterministic, making repeated ADB launches safe: generated rows are merged
 * by id and existing user-created debug rows are preserved. The seed rolls forward with the current
 * date; futureDays can prefill up to a year ahead for daily testing without another ADB launch.
 */
internal class DebugDemoDataSeeder(private val application: FudAIApp) {
    private val container get() = application.container
    private val zone: ZoneId = ZoneId.systemDefault()

    suspend fun seed(futureDays: Int = 0): DebugSeedReport {
        require(futureDays in 0..YEAR_DAYS) { "futureDays must be between 0 and $YEAR_DAYS" }
        val anchor = LocalDate.now(zone)
        val throughDate = anchor.plusDays(futureDays.toLong())
        val dates = (YEAR_DAYS - 1 downTo -futureDays).map { offset -> anchor.minusDays(offset.toLong()) }

        val foods = dates.flatMapIndexed(::foodsForDate)
        val firstDate = dates.first()
        val totalDays = (YEAR_DAYS - 1).toDouble()
        val weights = (sampleDates(dates, intervalDays = 7, denseTailDays = 14) + anchor).distinct().sorted().map { date ->
            val elapsedDays = java.time.temporal.ChronoUnit.DAYS.between(firstDate, date).toDouble()
            val progress = (elapsedDays / totalDays).coerceIn(0.0, 1.0)
            WeightEntry(
                id = stableId("weight:$date"),
                date = instant(date, LocalTime.of(7, 10)),
                weightKg = oneDecimal(79.2 - 8.1 * progress + sin(elapsedDays / 7.0 * 0.73) * 0.35)
            )
        }
        val bodyFat = (sampleDates(dates, intervalDays = 14, denseTailDays = 14) + anchor).distinct().sorted().map { date ->
            val elapsedDays = java.time.temporal.ChronoUnit.DAYS.between(firstDate, date).toDouble()
            val progress = (elapsedDays / totalDays).coerceIn(0.0, 1.0)
            BodyFatEntry(
                id = stableId("body-fat:$date"),
                date = instant(date, LocalTime.of(7, 15)),
                bodyFatFraction = (0.247 - 0.056 * progress + sin(elapsedDays / 14.0 * 0.51) * 0.0025)
                    .coerceIn(0.12, 0.35)
            )
        }
        val measurements = sampleDates(dates, 30).mapIndexed { index, date ->
            val count = sampleDates(dates, 30).lastIndex.coerceAtLeast(1)
            val progress = index.toDouble() / count
            BodyMeasurement(
                id = stableId("measurements:$date"),
                date = instant(date, LocalTime.of(7, 20)),
                neckCm = oneDecimal(39.0 - progress * 1.1),
                waistCm = oneDecimal(94.0 - progress * 11.8),
                hipsCm = oneDecimal(101.5 - progress * 6.0),
                chestCm = oneDecimal(103.0 - progress * 2.4),
                upperArmCm = oneDecimal(33.0 + progress * 1.1),
                thighCm = oneDecimal(59.0 - progress * 2.0),
                calfCm = oneDecimal(38.5 - progress * 0.5),
                wristCm = 17.2
            )
        }
        val water = dates.mapIndexed { index, date ->
            WaterEntry(
                id = stableId("water:$date"),
                date = instant(date, LocalTime.of(20, 30)),
                milliliters = 2_000 + (index % 5) * 150 + if (date.dayOfWeek == DayOfWeek.FRIDAY) 250 else 0
            )
        }
        val fasting = dates.asSequence()
            .filter { it < throughDate && it.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY) }
            .mapIndexed { index, date ->
                val start = instant(date, LocalTime.of(20, 0))
                val durationHours = if (index % 4 == 0) 14L else 16L
                FastingSession(
                    id = stableId("fasting:$date"),
                    startedAt = start,
                    endedAt = start.plus(Duration.ofHours(durationHours)),
                    goalMinutes = 16 * 60
                )
            }
            .toList()
        val workoutDates = dates.filter {
            it.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        }.toMutableList().apply {
            if (anchor !in this) add(anchor)
            sort()
        }
        val workoutSessions = workoutDates.mapIndexed(::workoutForDate)

        if (container.profileRepository.current() == null) {
            container.profileRepository.save(
                UserProfile.Default.copy(
                    name = "Debug Athlete",
                    weightKg = weights.last().weightKg,
                    bodyFatPercentage = bodyFat.last().bodyFatFraction,
                    goalBodyFatPercentage = 0.17,
                    goal = WeightGoal.LOSE,
                    weeklyChangeKg = 0.5,
                    goalWeightKg = 68.0,
                    customCalories = 2_330,
                    customProtein = 156,
                    customCarbs = 268,
                    customFat = 70
                )
            )
        }
        container.prefs.setOnboardingCompleted(true)

        // Write local demo rows without pruning photos or applying future measurements to the profile.
        container.prefs.setFoodEntries(
            mergeById(container.foodRepository.entries.first(), foods) { it.id }
                .sortedBy { it.timestamp }
        )
        container.prefs.setWeightEntries(
            mergeById(container.weightRepository.entries.first(), weights) { it.id }
                .sortedBy { it.date }
        )
        container.prefs.setBodyFatEntries(
            mergeById(container.bodyFatRepository.entries.first(), bodyFat) { it.id }
                .sortedBy { it.date }
        )
        container.bodyMeasurementRepository.replaceAll(
            mergeById(container.bodyMeasurementRepository.entries.first(), measurements) { it.id }
                .sortedBy { it.date }
        )
        container.prefs.setWaterEntries(
            mergeById(container.prefs.waterEntries.first(), water) { it.id }.sortedBy { it.date }
        )
        container.prefs.setFastingSessions(
            mergeById(container.prefs.fastingSessions.first(), fasting) { it.id }.sortedBy { it.startedAt }
        )
        container.prefs.setWaterTrackingEnabled(true)
        container.prefs.setFastingTrackingEnabled(true)

        val currentWorkout = container.workoutRepository.snapshot()
        val mergedPlans = currentWorkout.dayPlans.toMutableMap()
        workoutSessions.forEach { session ->
            // Existing plans may have been edited since the last seed. Only fill new dates.
            mergedPlans.putIfAbsent(session.diaryDateKey, session.asDayPlan())
        }
        val seededPreferences = when {
            currentWorkout.completedSessions.isEmpty() -> demoWorkoutPreferences
            currentWorkout.preferences == legacyDemoWorkoutPreferences -> demoWorkoutPreferences
            else -> currentWorkout.preferences
        }
        container.prefs.setWorkoutState(
            currentWorkout.copy(
                dayPlans = mergedPlans,
                completedSessions = mergeById(
                    currentWorkout.completedSessions,
                    workoutSessions
                ) { it.id }.sortedByDescending { it.completedAt },
                preferences = seededPreferences,
                mode = WorkoutTabMode.LOG
            )
        )

        val currentFavorites = container.prefs.favoriteFoodEntries.first()
        val demoFavorites = foods.takeLast(4)
        container.prefs.setFavoriteFoodEntries(
            currentFavorites + demoFavorites.filter { candidate ->
                currentFavorites.none { it.favoriteKey == candidate.favoriteKey }
            }
        )

        return DebugSeedReport(
            days = dates.size,
            throughDate = throughDate,
            foodEntries = foods.size,
            weightEntries = weights.size,
            bodyFatEntries = bodyFat.size,
            bodyMeasurements = measurements.size,
            waterEntries = water.size,
            fastingSessions = fasting.size,
            workoutSessions = workoutSessions.size
        )
    }

    private fun foodsForDate(dayIndex: Int, date: LocalDate): List<FoodEntry> {
        val menu = menus[dayIndex % menus.size]
        val dailyScale = 0.96 + (dayIndex % 7) * 0.012
        return menu.map { meal ->
            FoodEntry(
                id = stableId("food:$date:${meal.type}"),
                name = meal.name,
                calories = (meal.calories * dailyScale).roundToInt(),
                protein = oneDecimal(meal.protein * dailyScale),
                carbs = oneDecimal(meal.carbs * dailyScale),
                fat = oneDecimal(meal.fat * dailyScale),
                timestamp = instant(date, meal.time),
                emoji = meal.emoji,
                source = FoodSource.MANUAL,
                mealType = meal.type,
                sugar = oneDecimal(meal.sugar * dailyScale),
                addedSugar = oneDecimal(meal.addedSugar * dailyScale),
                fiber = oneDecimal(meal.fiber * dailyScale),
                saturatedFat = oneDecimal(meal.fat * 0.24 * dailyScale),
                monounsaturatedFat = oneDecimal(meal.fat * 0.36 * dailyScale),
                polyunsaturatedFat = oneDecimal(meal.fat * 0.23 * dailyScale),
                cholesterol = oneDecimal(meal.cholesterol * dailyScale),
                sodium = oneDecimal(meal.sodium * dailyScale),
                potassium = oneDecimal(meal.potassium * dailyScale),
                calcium = oneDecimal(meal.calcium * dailyScale),
                iron = oneDecimal(meal.iron * dailyScale),
                magnesium = oneDecimal(meal.magnesium * dailyScale),
                zinc = oneDecimal(meal.zinc * dailyScale),
                vitaminA = oneDecimal(meal.vitaminA * dailyScale),
                vitaminC = oneDecimal(meal.vitaminC * dailyScale),
                vitaminD = oneDecimal(meal.vitaminD * dailyScale),
                vitaminB12 = oneDecimal(meal.vitaminB12 * dailyScale),
                omega3 = oneDecimal(meal.omega3 * dailyScale),
                servingSizeGrams = oneDecimal(meal.servingGrams * dailyScale),
                customNote = "Android debug demo data"
            )
        }
    }

    private fun workoutForDate(index: Int, date: LocalDate): WorkoutSession {
        val template = workoutTemplates[index % workoutTemplates.size]
        val trainingProgress = index.toDouble() / 160.0
        val startedAt = instant(date, LocalTime.of(18, 0))
        val durationMinutes = 48 + index % 17
        val exercises = template.exercises.mapIndexed { exerciseIndex, seed ->
            val load = seed.baseWeightKg + trainingProgress * seed.yearGainKg
            CompletedExercise(
                id = stableId("workout-exercise:$date:${seed.itemId}"),
                itemId = seed.itemId,
                name = seed.name,
                targetMuscles = seed.muscles,
                equipment = seed.equipment,
                sets = (1..3).map { setNumber ->
                    CompletedSet(
                        id = stableId("workout-set:$date:${seed.itemId}:$setNumber"),
                        setNumber = setNumber,
                        weight = String.format(Locale.US, "%.1f", load - (setNumber - 1) * 1.25),
                        weightUnit = WorkoutWeightUnit.KG,
                        reps = (seed.reps - if (setNumber == 3) 1 else 0).toString(),
                        rpe = String.format(Locale.US, "%.1f", 7.0 + setNumber * 0.4),
                        rpeScale = WorkoutRpeScale.STRENGTH
                    )
                }
            )
        }
        return WorkoutSession(
            id = stableId("workout-session:$date"),
            diaryDateKey = date.toString(),
            startedAt = startedAt,
            completedAt = startedAt.plus(Duration.ofMinutes(durationMinutes.toLong())),
            durationSeconds = durationMinutes * 60,
            exercises = exercises,
            caloriesBurned = 300 + durationMinutes * 3 + index % 35,
            healthSyncVersion = null
        )
    }

    private fun WorkoutSession.asDayPlan(): WorkoutDayPlan = WorkoutDayPlan(
        dateKey = diaryDateKey,
        exercises = exercises.map { exercise ->
            PlannedExercise(
                id = stableId("workout-plan:$diaryDateKey:${exercise.itemId}"),
                itemId = exercise.itemId,
                name = exercise.name,
                level = "intermediate",
                imagePaths = emptyList(),
                force = "push",
                mechanic = "compound",
                category = "strength",
                equipment = exercise.equipment,
                primaryMuscles = exercise.targetMuscles,
                secondaryMuscles = emptyList(),
                instructions = listOf("Use a controlled range of motion and stop if form breaks down."),
                sets = exercise.sets.map { set ->
                    PlannedSet(
                        id = stableId("workout-plan-set:$diaryDateKey:${exercise.itemId}:${set.setNumber}"),
                        weight = set.weight,
                        weightUnit = set.weightUnit,
                        reps = set.reps,
                        rpe = set.rpe,
                        rpeScale = set.rpeScale
                    )
                }
            )
        }
    )

    private fun instant(date: LocalDate, time: LocalTime): Instant = date.atTime(time).atZone(zone).toInstant()

    private fun sampleDates(
        dates: List<LocalDate>,
        intervalDays: Int,
        denseTailDays: Int = 0
    ): List<LocalDate> {
        val denseTailStart = dates.last().minusDays((denseTailDays - 1).coerceAtLeast(0).toLong())
        return dates.filterIndexed { index, date ->
            index % intervalDays == 0 || (denseTailDays > 0 && !date.isBefore(denseTailStart))
        }.toMutableList().apply {
            val last = dates.last()
            if (last !in this) add(last)
        }
    }

    private fun stableId(key: String): UUID = UUID.nameUUIDFromBytes(
        "$SEED_NAMESPACE:$key".toByteArray(StandardCharsets.UTF_8)
    )

    private fun oneDecimal(value: Double): Double = (value * 10.0).roundToInt() / 10.0

    private fun <T, K> mergeById(existing: List<T>, seed: List<T>, key: (T) -> K): List<T> {
        val known = existing.mapTo(mutableSetOf(), key)
        return existing + seed.filter { known.add(key(it)) }
    }

    private data class MealSeed(
        val name: String,
        val emoji: String,
        val type: MealType,
        val time: LocalTime,
        val calories: Int,
        val protein: Double,
        val carbs: Double,
        val fat: Double,
        val sugar: Double,
        val addedSugar: Double,
        val fiber: Double,
        val cholesterol: Double,
        val sodium: Double,
        val potassium: Double,
        val calcium: Double,
        val iron: Double,
        val magnesium: Double,
        val zinc: Double,
        val vitaminA: Double,
        val vitaminC: Double,
        val vitaminD: Double,
        val vitaminB12: Double,
        val omega3: Double,
        val servingGrams: Double
    )

    private data class ExerciseSeed(
        val itemId: String,
        val name: String,
        val muscles: List<String>,
        val equipment: String,
        val baseWeightKg: Double,
        val yearGainKg: Double,
        val reps: Int
    )

    private data class WorkoutTemplate(val exercises: List<ExerciseSeed>)

    private companion object {
        const val YEAR_DAYS = 365
        const val SEED_NAMESPACE = "fud-ai-android-debug-year-v1"

        val demoWorkoutPreferences = WorkoutPreferences(
            targetMuscles = setOf(
                "Chest",
                "Lats",
                "Middle Back",
                "Lower Back",
                "Quadriceps",
                "Hamstrings",
                "Shoulders"
            ),
            frequencyDays = 3,
            durationMinutes = 60,
            split = WorkoutSplit.PUSH_PULL_LEGS,
            equipment = setOf("Barbell", "Dumbbell", "Cable", "Body Only"),
            strength = WorkoutStrengthNumbers(
                benchPressKg = 72.5,
                squatKg = 100.0,
                deadliftKg = 125.0,
                overheadPressKg = 45.0
            )
        )

        // Migrates devices seeded before canonical exercise-library labels were used.
        val legacyDemoWorkoutPreferences = demoWorkoutPreferences.copy(
            targetMuscles = setOf("chest", "back", "quadriceps", "hamstrings", "shoulders"),
            equipment = setOf("barbell", "dumbbell", "cable", "body only")
        )

        val menus = listOf(
            listOf(
                meal("Greek Yogurt Berry Oats", "🥣", MealType.BREAKFAST, 8, 10, 510, 31.0, 67.0, 14.0, 20.0, 4.0, 11.0, 18.0, 180.0, 760.0, 310.0, 4.2, 120.0, 3.1, 110.0, 38.0, 2.0, 1.2, 1.1, 420.0),
                meal("Chicken Quinoa Power Bowl", "🥗", MealType.LUNCH, 13, 5, 690, 52.0, 76.0, 19.0, 9.0, 1.0, 13.0, 112.0, 760.0, 1_120.0, 120.0, 6.1, 170.0, 5.2, 210.0, 64.0, 0.5, 1.8, 0.7, 540.0),
                meal("Apple & Peanut Butter", "🍎", MealType.SNACK, 16, 25, 270, 8.0, 31.0, 14.0, 21.0, 2.0, 6.5, 0.0, 120.0, 510.0, 45.0, 1.2, 65.0, 1.6, 12.0, 9.0, 0.0, 0.0, 0.1, 205.0),
                meal("Salmon, Rice & Greens", "🐟", MealType.DINNER, 19, 35, 720, 48.0, 74.0, 25.0, 7.0, 0.0, 10.0, 88.0, 640.0, 1_180.0, 155.0, 4.0, 150.0, 3.8, 240.0, 72.0, 14.0, 5.2, 2.9, 590.0)
            ),
            listOf(
                meal("Veggie Omelette & Sourdough", "🍳", MealType.BREAKFAST, 8, 0, 495, 30.0, 42.0, 23.0, 7.0, 1.0, 7.0, 425.0, 710.0, 620.0, 210.0, 4.0, 85.0, 3.4, 340.0, 52.0, 3.2, 2.4, 0.4, 380.0),
                meal("Turkey Avocado Wrap", "🌯", MealType.LUNCH, 12, 45, 650, 43.0, 62.0, 26.0, 8.0, 2.0, 12.0, 95.0, 980.0, 960.0, 180.0, 4.8, 120.0, 5.5, 180.0, 36.0, 0.5, 2.0, 0.6, 455.0),
                meal("Cottage Cheese & Pineapple", "🍍", MealType.SNACK, 16, 15, 245, 23.0, 28.0, 5.0, 22.0, 0.0, 2.0, 28.0, 410.0, 380.0, 180.0, 0.5, 30.0, 1.4, 55.0, 18.0, 0.2, 1.1, 0.1, 280.0),
                meal("Beef Stir Fry & Noodles", "🍜", MealType.DINNER, 19, 20, 750, 48.0, 85.0, 23.0, 14.0, 3.0, 11.0, 105.0, 1_080.0, 1_240.0, 105.0, 7.2, 125.0, 7.0, 280.0, 82.0, 0.2, 4.5, 0.5, 610.0)
            ),
            listOf(
                meal("Banana Protein Smoothie", "🥤", MealType.BREAKFAST, 7, 50, 480, 36.0, 61.0, 11.0, 29.0, 3.0, 9.0, 38.0, 240.0, 980.0, 360.0, 2.1, 130.0, 3.0, 90.0, 24.0, 2.5, 2.0, 0.5, 520.0),
                meal("Paneer Tikka Rice Bowl", "🍚", MealType.LUNCH, 13, 0, 710, 36.0, 82.0, 27.0, 10.0, 1.0, 10.0, 65.0, 870.0, 890.0, 410.0, 4.8, 110.0, 4.2, 220.0, 46.0, 1.2, 1.8, 0.6, 560.0),
                meal("Trail Mix & Orange", "🍊", MealType.SNACK, 16, 30, 280, 8.0, 34.0, 14.0, 20.0, 2.0, 6.0, 0.0, 40.0, 520.0, 95.0, 2.0, 80.0, 2.4, 30.0, 78.0, 0.0, 0.0, 0.8, 190.0),
                meal("Lentil Curry & Roti", "🍛", MealType.DINNER, 19, 30, 690, 32.0, 104.0, 17.0, 12.0, 2.0, 24.0, 12.0, 780.0, 1_360.0, 140.0, 8.8, 165.0, 5.3, 190.0, 34.0, 0.0, 0.2, 0.4, 640.0)
            ),
            listOf(
                meal("Overnight Chia Oats", "🫐", MealType.BREAKFAST, 8, 20, 525, 24.0, 73.0, 18.0, 18.0, 3.0, 14.0, 12.0, 170.0, 820.0, 290.0, 4.7, 145.0, 3.6, 80.0, 32.0, 1.5, 1.0, 2.2, 430.0),
                meal("Tuna Pasta Salad", "🥗", MealType.LUNCH, 12, 55, 670, 46.0, 78.0, 20.0, 9.0, 1.0, 10.0, 70.0, 790.0, 900.0, 160.0, 4.0, 105.0, 3.7, 170.0, 28.0, 3.5, 3.2, 1.8, 520.0),
                meal("Hummus, Carrots & Pita", "🥕", MealType.SNACK, 16, 20, 265, 9.0, 39.0, 9.0, 7.0, 0.0, 8.0, 0.0, 480.0, 560.0, 95.0, 2.8, 65.0, 2.5, 260.0, 18.0, 0.0, 0.0, 0.1, 240.0),
                meal("Chicken Burrito Bowl", "🥙", MealType.DINNER, 19, 25, 740, 50.0, 91.0, 20.0, 11.0, 2.0, 17.0, 105.0, 960.0, 1_290.0, 150.0, 6.3, 155.0, 5.7, 190.0, 48.0, 0.5, 1.8, 0.5, 620.0)
            )
        )

        val workoutTemplates = listOf(
            WorkoutTemplate(listOf(
                ExerciseSeed("barbell-bench-press", "Barbell Bench Press", listOf("chest", "triceps"), "barbell", 52.5, 20.0, 8),
                ExerciseSeed("standing-dumbbell-press", "Standing Dumbbell Press", listOf("shoulders"), "dumbbell", 18.0, 6.0, 10),
                ExerciseSeed("cable-triceps-pushdown", "Cable Triceps Pushdown", listOf("triceps"), "cable", 22.5, 10.0, 12)
            )),
            WorkoutTemplate(listOf(
                ExerciseSeed("barbell-deadlift", "Barbell Deadlift", listOf("lower back", "hamstrings"), "barbell", 85.0, 35.0, 6),
                ExerciseSeed("wide-grip-lat-pulldown", "Wide-Grip Lat Pulldown", listOf("lats"), "cable", 45.0, 15.0, 10),
                ExerciseSeed("alternating-dumbbell-curl", "Alternating Dumbbell Curl", listOf("biceps"), "dumbbell", 11.0, 4.0, 12)
            )),
            WorkoutTemplate(listOf(
                ExerciseSeed("barbell-full-squat", "Barbell Full Squat", listOf("quadriceps", "glutes"), "barbell", 67.5, 32.5, 8),
                ExerciseSeed("romanian-deadlift", "Romanian Deadlift", listOf("hamstrings"), "barbell", 55.0, 25.0, 10),
                ExerciseSeed("standing-calf-raises", "Standing Calf Raises", listOf("calves"), "machine", 42.5, 17.5, 15)
            ))
        )

        fun meal(
            name: String, emoji: String, type: MealType, hour: Int, minute: Int,
            calories: Int, protein: Double, carbs: Double, fat: Double, sugar: Double,
            addedSugar: Double, fiber: Double, cholesterol: Double, sodium: Double,
            potassium: Double, calcium: Double, iron: Double, magnesium: Double, zinc: Double,
            vitaminA: Double, vitaminC: Double, vitaminD: Double, vitaminB12: Double,
            omega3: Double, servingGrams: Double
        ) = MealSeed(
            name, emoji, type, LocalTime.of(hour, minute), calories, protein, carbs, fat,
            sugar, addedSugar, fiber, cholesterol, sodium, potassium, calcium, iron, magnesium,
            zinc, vitaminA, vitaminC, vitaminD, vitaminB12, omega3, servingGrams
        )
    }
}
