package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.data.ExerciseItem
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

@Serializable
enum class WorkoutTabMode {
    LIBRARY,
    LOG;

    companion object {
        val Default: WorkoutTabMode = LOG
    }
}

@Serializable
enum class WorkoutWeightUnit(val storageValue: String) {
    KG("kg"),
    LBS("lbs");

    companion object {
        fun fromStorage(value: String?): WorkoutWeightUnit =
            entries.firstOrNull { it.storageValue == value } ?: LBS
    }
}

object WorkoutDate {
    fun key(date: LocalDate): String = date.toString()

    fun parse(key: String): LocalDate? = runCatching { LocalDate.parse(key) }.getOrNull()

    fun requireKey(key: String): String =
        requireNotNull(parse(key)) { "Invalid workout date key: $key" }.toString()
}

@Serializable
enum class WorkoutRpeScale {
    STRENGTH,
    CR10,
    BORG;

    val title: String
        get() = when (this) {
            STRENGTH -> "Strength 1–10"
            CR10 -> "CR10 0–10"
            BORG -> "Borg 6–20"
        }

    val shortTitle: String
        get() = when (this) {
            STRENGTH -> "1–10"
            CR10 -> "CR10"
            BORG -> "Borg"
        }

    val inputPlaceholder: String
        get() = when (this) {
            STRENGTH -> "1–10"
            CR10 -> "0–10"
            BORG -> "6–20"
        }

    val allowsDecimalInput: Boolean get() = this != BORG

    val inputRange: ClosedFloatingPointRange<Double>
        get() = when (this) {
            STRENGTH -> 1.0..10.0
            CR10 -> 0.0..10.0
            BORG -> 6.0..20.0
        }

    /**
     * Matches iOS's in-progress RPE sanitizer, including values such as `7.`
     * while the user is still typing and a single fractional digit where valid.
     */
    fun sanitize(proposedValue: String, previousValue: String = ""): String {
        val normalized = proposedValue.trim().replace(',', '.')
        if (normalized.isEmpty()) return ""

        val filtered = StringBuilder()
        var hasDecimal = false
        var fractionalDigits = 0
        for (character in normalized) {
            when {
                character.isDigit() -> {
                    if (hasDecimal) {
                        if (!allowsDecimalInput || fractionalDigits >= 1) continue
                        fractionalDigits += 1
                    }
                    filtered.append(character)
                }
                character == '.' && allowsDecimalInput && !hasDecimal && filtered.isNotEmpty() -> {
                    hasDecimal = true
                    filtered.append(character)
                }
            }
        }
        if (filtered.isEmpty()) return previousValue

        val result = filtered.toString()
        val numericText = result.removeSuffix(".")
        val value = numericText.toDoubleOrNull() ?: return previousValue
        if (value > inputRange.endInclusive) return inputRange.endInclusive.toInt().toString()
        if (value < inputRange.start && !isPossibleRangePrefix(result)) return previousValue
        return result
    }

    private fun isPossibleRangePrefix(value: String): Boolean {
        val integerPrefix = value.substringBefore('.')
        if (integerPrefix.isEmpty()) return false
        val lower = ceil(inputRange.start).toInt()
        val upper = inputRange.endInclusive.toInt()
        return (lower..upper).any { it.toString().startsWith(integerPrefix) }
    }
}

@Serializable
enum class WorkoutSplit {
    PUSH_PULL_LEGS,
    UPPER_LOWER,
    BODY_PART,
    ARNOLD,
    PUSH_PULL,
    ANTAGONIST,
    HYBRID,
    FULL_BODY,
    CUSTOM;

    val title: String
        get() = when (this) {
            PUSH_PULL_LEGS -> "Push / Pull / Legs"
            UPPER_LOWER -> "Upper / Lower"
            BODY_PART -> "Body-part split"
            ARNOLD -> "Arnold split"
            PUSH_PULL -> "Push / Pull"
            ANTAGONIST -> "Antagonist split"
            HYBRID -> "Hybrid split"
            FULL_BODY -> "Full body"
            CUSTOM -> "Custom"
        }

    companion object {
        val SelectableValues: List<WorkoutSplit> = listOf(
            FULL_BODY,
            UPPER_LOWER,
            PUSH_PULL_LEGS,
            BODY_PART,
            ARNOLD,
            PUSH_PULL,
            ANTAGONIST,
            HYBRID
        )
    }
}

@Serializable
enum class WorkoutIssue(val title: String) {
    SHOULDER("Shoulder"),
    ELBOW("Elbow"),
    WRIST("Wrist"),
    LOWER_BACK("Lower back"),
    HIP("Hip"),
    KNEE("Knee"),
    ANKLE("Ankle"),
    OTHER("Other")
}

@Serializable
data class WorkoutStrengthNumbers(
    val benchPressKg: Double? = null,
    val squatKg: Double? = null,
    val deadliftKg: Double? = null,
    val overheadPressKg: Double? = null
)

@Serializable
data class WorkoutPreferences(
    val targetMuscles: Set<String> = emptySet(),
    val issues: Set<WorkoutIssue> = emptySet(),
    val additionalIssues: String = "",
    val frequencyDays: Int = 3,
    val durationMinutes: Int = 60,
    val split: WorkoutSplit = WorkoutSplit.FULL_BODY,
    val customSplit: String = "",
    val equipment: Set<String> = emptySet(),
    val rpeScale: WorkoutRpeScale = WorkoutRpeScale.STRENGTH,
    val strength: WorkoutStrengthNumbers = WorkoutStrengthNumbers()
) {
    /** Keeps legacy fields decodable while enforcing the final selectable settings. */
    fun sanitized(): WorkoutPreferences = copy(
        additionalIssues = additionalIssues.trim().takeIf { WorkoutIssue.OTHER in issues }.orEmpty(),
        frequencyDays = frequencyDays.coerceIn(1, 7),
        split = if (split == WorkoutSplit.CUSTOM) WorkoutSplit.FULL_BODY else split,
        customSplit = "",
        strength = WorkoutStrengthNumbers(
            benchPressKg = validLoad(strength.benchPressKg),
            squatKg = validLoad(strength.squatKg),
            deadliftKg = validLoad(strength.deadliftKg),
            overheadPressKg = validLoad(strength.overheadPressKg)
        )
    )

    private fun validLoad(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it > 0.0 }
}

@Serializable
data class PlannedSet(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val weight: String = "",
    val weightUnit: WorkoutWeightUnit? = null,
    val reps: String = "",
    val rpe: String = "",
    val rpeScale: WorkoutRpeScale? = null
) {
    val hasLoggedValue: Boolean
        get() = weight.isNotBlank() || reps.isNotBlank() || rpe.isNotBlank()

    fun blankCopy(carryingWeight: Boolean = false): PlannedSet = PlannedSet(
        weight = if (carryingWeight) weight else "",
        weightUnit = if (carryingWeight) weightUnit else null
    )

    /** New sets inherit weight, unit, and reps from the set above; RPE stays blank. */
    fun copyingFromPrevious(): PlannedSet = PlannedSet(
        weight = weight,
        weightUnit = weightUnit,
        reps = reps
    )

    fun displayWeight(targetUnit: WorkoutWeightUnit): String {
        val sourceUnit = weightUnit ?: return weight
        val numericWeight = weight.replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() } ?: return weight
        if (sourceUnit == targetUnit) return weight

        val poundsPerKilogram = 2.204_622_621_8
        val converted = if (sourceUnit == WorkoutWeightUnit.KG) {
            numericWeight * poundsPerKilogram
        } else {
            numericWeight / poundsPerKilogram
        }
        return String.format(Locale.US, "%.2f", converted)
            .trimEnd('0')
            .trimEnd('.')
    }
}

@Serializable
enum class WorkoutIntensity(val title: String) {
    LIGHT("Light"), MODERATE("Moderate"), VIGOROUS("Vigorous")
}

/** Persist only transitions; wall-clock anchors keep timers alive across process death. */
@Serializable
data class ExerciseTimer(
    val accumulatedSeconds: Double = 0.0,
    @Serializable(with = InstantSerializer::class)
    val runningSince: Instant? = null,
    val savedDurationSeconds: Double? = null,
    val intensity: WorkoutIntensity = WorkoutIntensity.MODERATE
) {
    val isRunning: Boolean get() = runningSince != null
    val isSaved: Boolean
        get() = !isRunning && savedDurationSeconds?.let { it.isFinite() && it > 0.0 } == true
    val savedSeconds: Double
        get() = if (isSaved) savedDurationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0 else 0.0

    fun elapsedSeconds(at: Instant = Instant.now()): Double {
        val accumulated = accumulatedSeconds.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val running = runningSince?.let {
            val elapsed = Duration.between(it, at)
            (elapsed.seconds.toDouble() + elapsed.nano / 1_000_000_000.0).coerceAtLeast(0.0)
        } ?: 0.0
        return accumulated + running
    }

    fun start(at: Instant = Instant.now()): ExerciseTimer =
        if (isRunning) this else copy(runningSince = at, savedDurationSeconds = null)

    fun pause(at: Instant = Instant.now()): ExerciseTimer =
        if (!isRunning) this else copy(accumulatedSeconds = elapsedSeconds(at), runningSince = null)

    fun stop(at: Instant = Instant.now()): ExerciseTimer {
        val duration = elapsedSeconds(at)
        return copy(accumulatedSeconds = duration, runningSince = null, savedDurationSeconds = duration)
    }

    fun restart(at: Instant = Instant.now()): ExerciseTimer = ExerciseTimer(
        runningSince = at,
        intensity = intensity
    )
}

enum class ExerciseTimerAction { START, PAUSE, RESUME, STOP, RESTART, DISCARD }

@Serializable
data class PlannedExercise(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val itemId: String,
    val name: String,
    val level: String,
    val imagePaths: List<String>,
    val force: String,
    val mechanic: String,
    val category: String,
    val equipment: String,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
    val instructions: List<String>,
    val sets: List<PlannedSet> = listOf(PlannedSet()),
    val timer: ExerciseTimer? = null
) {
    val isCardio: Boolean
        get() = category.equals("cardio", ignoreCase = true)

    val hasCalculableWork: Boolean
        get() = (timer?.savedSeconds ?: 0.0) > 0.0 ||
            (!isCardio && sets.any { (it.reps.toIntOrNull() ?: 0) > 0 })

    fun copiedForNewDay(includeSetDetails: Boolean = false): PlannedExercise = copy(
        id = UUID.randomUUID(),
        sets = if (includeSetDetails) sets.map { it.copy(id = UUID.randomUUID()) } else listOf(PlannedSet()),
        timer = if (includeSetDetails) timer else null
    )

    fun asExerciseItem(): ExerciseItem = ExerciseItem(
        id = itemId,
        name = name,
        level = level,
        imagePaths = imagePaths,
        force = force,
        mechanic = mechanic,
        category = category,
        equipment = equipment,
        primaryMuscles = primaryMuscles,
        secondaryMuscles = secondaryMuscles,
        instructions = instructions
    )

    companion object {
        fun from(item: ExerciseItem): PlannedExercise = PlannedExercise(
            itemId = item.id,
            name = item.name,
            level = item.level,
            imagePaths = item.imagePaths,
            force = item.force,
            mechanic = item.mechanic,
            category = item.category,
            equipment = item.equipment,
            primaryMuscles = item.primaryMuscles,
            secondaryMuscles = item.secondaryMuscles,
            instructions = item.instructions
        )
    }
}

@Serializable
data class WorkoutDayPlan(
    val dateKey: String,
    val exercises: List<PlannedExercise> = emptyList()
)

@Serializable
data class CompletedSet(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val setNumber: Int,
    val weight: String,
    val weightUnit: WorkoutWeightUnit,
    val reps: String,
    val rpe: String,
    val rpeScale: WorkoutRpeScale? = null
) {
    /** A set is performed once reps were entered; load or RPE alone is incomplete. */
    val isPerformed: Boolean get() = reps.isNotEmpty()
}

@Serializable
data class CompletedExercise(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val itemId: String,
    val name: String,
    val targetMuscles: List<String>,
    val equipment: String,
    val sets: List<CompletedSet>,
    val durationSeconds: Double? = null,
    val intensity: WorkoutIntensity? = null
)

@Serializable
data class WorkoutSession(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val diaryDateKey: String,
    @Serializable(with = InstantSerializer::class)
    val startedAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val completedAt: Instant,
    val durationSeconds: Int = 0,
    val exercises: List<CompletedExercise>,
    val caloriesBurned: Int? = null,
    val healthSyncVersion: Int? = null
) {
    val durationMinutes: Int get() = ceil(durationSeconds.coerceAtLeast(0) / 60.0).toInt()
    val exerciseCount: Int get() = exercises.size
    val performedSetCount: Int get() = exercises.sumOf { exercise -> exercise.sets.count { it.isPerformed } }
    val repCount: Int get() = exercises.sumOf { exercise -> exercise.sets.sumOf { it.reps.toIntOrNull() ?: 0 } }
}

@Serializable
data class WorkoutPersistedState(
    val version: Int = CurrentVersion,
    val dayPlans: Map<String, WorkoutDayPlan> = emptyMap(),
    val completedSessions: List<WorkoutSession> = emptyList(),
    val savedExerciseIds: Set<String> = emptySet(),
    val customActivities: List<PlannedExercise> = emptyList(),
    val preferences: WorkoutPreferences = WorkoutPreferences(),
    val mode: WorkoutTabMode = WorkoutTabMode.Default,
    /**
     * Pending deletes double as tombstones so a health restore cannot resurrect them.
     * The date key is retained because Health Connect deletion is scoped by stable id + day.
     */
    val healthDeletionTombstones: Map<String, String> = emptyMap(),
    /** Deletes awaiting Health Connect confirmation; tombstones outlive a successful write
     * until a subsequent owned-record read proves the sample is no longer visible. */
    val pendingHealthDeleteIds: Set<String> = emptySet(),
    /** Failed/deferred health writes can be retried without losing local calculations. */
    val pendingHealthUpsertIds: Set<String> = emptySet()
) {
    fun sanitized(): WorkoutPersistedState = if (version != CurrentVersion) {
        WorkoutPersistedState()
    } else {
        copy(preferences = preferences.sanitized())
    }

    companion object {
        const val CurrentVersion = 1
    }
}

data class WorkoutBurnEstimate(
    val calories: Int,
    val performedSetCount: Int,
    val repCount: Int
)

/** Saved exercise timers replace that exercise's rep-based estimate. */
object WorkoutBurnEstimator {
    fun estimate(
        exercises: List<PlannedExercise>,
        bodyWeightKg: Double,
        defaultWeightUnit: WorkoutWeightUnit,
        defaultRpeScale: WorkoutRpeScale
    ): WorkoutBurnEstimate? {
        val safeBodyWeight = if (bodyWeightKg.isFinite()) bodyWeightKg.coerceIn(35.0, 300.0) else 70.0
        var performedSetCount = 0
        var repCount = 0
        var activeMinutes = 0.0
        var recoveryMinutes = 0.0
        var effortTotal = 0.0
        var relativeLoadTotal = 0.0
        var exercisesWithWork = 0
        var timedCalories = 0.0
        var strengthSetCount = 0

        for (exercise in exercises) {
            val timedSeconds = exercise.timer?.savedSeconds ?: 0.0
            if (timedSeconds > 0.0 || exercise.isCardio) {
                if (timedSeconds > 0.0) {
                    val intensity = timerIntensity(exercise, defaultRpeScale)
                    val met = timedMet(exercise, intensity)
                    timedCalories += met * 3.5 * safeBodyWeight / 200.0 * (timedSeconds / 60.0)
                }
                // Logged reps remain useful statistics; their estimate must not be added twice.
                exercise.sets.forEach { set ->
                    val reps = set.reps.toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                    performedSetCount += 1
                    repCount += reps.coerceAtMost(100)
                }
                continue
            }
            var performedInExercise = 0
            for (set in exercise.sets) {
                val rawReps = set.reps.toIntOrNull()?.takeIf { it > 0 } ?: continue
                val reps = rawReps.coerceAtMost(100)
                performedSetCount += 1
                strengthSetCount += 1
                performedInExercise += 1
                repCount += reps
                activeMinutes += (reps * 2.75 / 60.0).coerceIn(0.30, 1.50)
                recoveryMinutes += 1.60
                effortTotal += normalizedEffort(set.rpe, set.rpeScale ?: defaultRpeScale)
                relativeLoadTotal += relativeLoad(
                    text = set.weight,
                    unit = set.weightUnit ?: defaultWeightUnit,
                    bodyWeightKg = safeBodyWeight
                )
            }
            if (performedInExercise > 0) exercisesWithWork += 1
        }

        if (strengthSetCount == 0 && timedCalories <= 0.0) return null

        recoveryMinutes = (recoveryMinutes - 1.60).coerceAtLeast(0.0)
        val transitionMinutes = exercisesWithWork * 0.75
        val estimatedMinutes = (activeMinutes + recoveryMinutes + transitionMinutes).coerceAtLeast(4.0)
        val averageEffort = effortTotal / strengthSetCount.coerceAtLeast(1)
        val averageRelativeLoad = relativeLoadTotal / strengthSetCount.coerceAtLeast(1)
        val met = (3.8 + (2.4 * averageEffort) + (0.5 * averageRelativeLoad)).coerceIn(3.5, 8.0)
        val strengthCalories = if (strengthSetCount > 0) met * 3.5 * safeBodyWeight / 200.0 * estimatedMinutes else 0.0
        val rawCalories = strengthCalories + timedCalories

        return WorkoutBurnEstimate(
            calories = rawCalories.roundToInt().coerceIn(1, 5_000),
            performedSetCount = performedSetCount,
            repCount = repCount
        )
    }

    /** RPE drives timed effort; missing RPE preserves legacy effort or the moderate default. */
    fun timerIntensity(exercise: PlannedExercise, defaultRpeScale: WorkoutRpeScale): WorkoutIntensity {
        val efforts = exercise.sets.mapNotNull { set ->
            set.rpe.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            normalizedEffort(set.rpe, set.rpeScale ?: defaultRpeScale)
        }
        if (efforts.isEmpty()) return exercise.timer?.intensity ?: WorkoutIntensity.MODERATE
        return when {
            efforts.average() < 0.4 -> WorkoutIntensity.LIGHT
            efforts.average() >= 0.75 -> WorkoutIntensity.VIGOROUS
            else -> WorkoutIntensity.MODERATE
        }
    }

    /**
     * Representative effort estimates from the 2024 Adult Compendium of Physical Activities:
     * https://pacompendium.com/{bicycling,walking,running,sports,conditioning-exercise}/
     * Effort labels are approximate; pace, watts, and individual efficiency are not measured.
     */
    private fun timedMet(exercise: PlannedExercise, intensity: WorkoutIntensity): Double {
        val values = if (exercise.isCardio) {
            when (exercise.itemId) {
                "Bicycling" -> listOf(4.3, 7.0, 9.0)
                "Bicycling_Stationary", "Recumbent_Bike" -> listOf(3.5, 6.0, 10.8)
                "Walking_Treadmill", "Walking_Outdoor" -> listOf(2.8, 3.8, 4.8)
                "Running_Treadmill", "Jogging_Treadmill", "Running_Outdoor" -> listOf(6.5, 8.5, 10.5)
                "Rope_Jumping" -> listOf(8.3, 11.8, 12.3)
                "Trail_Running_Walking" -> listOf(3.8, 6.0, 9.3)
                "Skating" -> listOf(7.5, 9.8, 12.3)
                "Stairmaster", "Step_Mill" -> listOf(4.5, 6.8, 9.3)
                "Elliptical_Trainer" -> listOf(5.0, 5.0, 9.0)
                "Rowing_Stationary" -> listOf(5.0, 7.3, 11.0)
                else -> listOf(3.5, 5.0, 7.5)
            }
        } else {
            when (exercise.category.lowercase(Locale.ROOT)) {
                "stretching" -> listOf(2.3, 2.3, 2.3)
                "plyometrics" -> listOf(3.5, 5.0, 7.5)
                else -> listOf(3.5, 5.0, 6.0)
            }
        }
        return values[intensity.ordinal]
    }

    private fun normalizedEffort(text: String, scale: WorkoutRpeScale): Double {
        val value = text.replace(',', '.').toDoubleOrNull() ?: return 0.60
        val normalized = when (scale) {
            WorkoutRpeScale.STRENGTH -> (value - 1.0) / 9.0
            WorkoutRpeScale.CR10 -> value / 10.0
            WorkoutRpeScale.BORG -> (value - 6.0) / 14.0
        }
        return normalized.coerceIn(0.0, 1.0)
    }

    private fun relativeLoad(text: String, unit: WorkoutWeightUnit, bodyWeightKg: Double): Double {
        val value = text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return 0.0
        val kilograms = if (unit == WorkoutWeightUnit.KG) value else value / 2.204_622_621_8
        return (kilograms / bodyWeightKg).coerceIn(0.0, 2.0)
    }
}

data class WorkoutSplitGroup(
    val title: String,
    val muscles: Set<String>
) {
    companion object {
        fun groups(split: WorkoutSplit, availableMuscles: List<String>): List<WorkoutSplitGroup> {
            val namesByLowercase = availableMuscles.associateBy { it.lowercase() }
            fun matching(vararg candidates: String): Set<String> =
                candidates.mapNotNull { namesByLowercase[it.lowercase()] }.toSet()

            return when (split) {
                WorkoutSplit.PUSH_PULL_LEGS -> listOf(
                    WorkoutSplitGroup("Push", matching("Chest", "Shoulders", "Triceps")),
                    WorkoutSplitGroup("Pull", matching("Biceps", "Forearms", "Lats", "Middle Back", "Traps", "Neck")),
                    WorkoutSplitGroup("Legs", matching("Abductors", "Adductors", "Calves", "Glutes", "Hamstrings", "Lower Back", "Quadriceps")),
                    WorkoutSplitGroup("Core", matching("Abdominals"))
                )
                WorkoutSplit.UPPER_LOWER -> listOf(
                    WorkoutSplitGroup("Upper", matching("Biceps", "Chest", "Forearms", "Lats", "Middle Back", "Neck", "Shoulders", "Traps", "Triceps")),
                    WorkoutSplitGroup("Lower", matching("Abductors", "Adductors", "Calves", "Glutes", "Hamstrings", "Lower Back", "Quadriceps")),
                    WorkoutSplitGroup("Core", matching("Abdominals"))
                )
                WorkoutSplit.BODY_PART -> listOf(
                    WorkoutSplitGroup("Chest", matching("Chest")),
                    WorkoutSplitGroup("Back", matching("Lats", "Middle Back", "Lower Back", "Traps")),
                    WorkoutSplitGroup("Shoulders", matching("Shoulders", "Traps")),
                    WorkoutSplitGroup("Arms", matching("Biceps", "Triceps", "Forearms")),
                    WorkoutSplitGroup("Legs", matching("Abductors", "Adductors", "Calves", "Glutes", "Hamstrings", "Quadriceps")),
                    WorkoutSplitGroup("Core", matching("Abdominals"))
                )
                WorkoutSplit.ARNOLD -> listOf(
                    WorkoutSplitGroup("Chest + Back", matching("Chest", "Lats", "Middle Back", "Lower Back", "Traps")),
                    WorkoutSplitGroup("Shoulders + Arms", matching("Shoulders", "Biceps", "Triceps", "Forearms", "Neck")),
                    WorkoutSplitGroup("Legs", matching("Abductors", "Adductors", "Calves", "Glutes", "Hamstrings", "Quadriceps")),
                    WorkoutSplitGroup("Core", matching("Abdominals"))
                )
                WorkoutSplit.PUSH_PULL -> listOf(
                    WorkoutSplitGroup("Push", matching("Chest", "Shoulders", "Triceps", "Quadriceps", "Calves")),
                    WorkoutSplitGroup("Pull", matching("Biceps", "Forearms", "Lats", "Middle Back", "Traps", "Glutes", "Hamstrings", "Lower Back")),
                    WorkoutSplitGroup("Accessory/Core", matching("Abdominals", "Abductors", "Adductors", "Neck"))
                )
                WorkoutSplit.ANTAGONIST -> listOf(
                    WorkoutSplitGroup("Chest + Back", matching("Chest", "Lats", "Middle Back", "Lower Back", "Traps")),
                    WorkoutSplitGroup("Biceps + Triceps", matching("Biceps", "Triceps", "Forearms")),
                    WorkoutSplitGroup("Quads + Hamstrings/Glutes", matching("Quadriceps", "Hamstrings", "Glutes")),
                    WorkoutSplitGroup("Shoulders + Lats/Traps", matching("Shoulders", "Lats", "Traps")),
                    WorkoutSplitGroup("Core/Accessory", matching("Abdominals", "Abductors", "Adductors", "Calves", "Neck"))
                )
                WorkoutSplit.HYBRID -> listOf(
                    WorkoutSplitGroup("Strength/Compound", matching("Chest", "Lats", "Middle Back", "Lower Back", "Glutes", "Hamstrings", "Quadriceps", "Shoulders", "Traps")),
                    WorkoutSplitGroup("Accessory/Hypertrophy", matching("Biceps", "Triceps", "Forearms", "Calves", "Abductors", "Adductors", "Abdominals", "Neck"))
                )
                WorkoutSplit.FULL_BODY, WorkoutSplit.CUSTOM -> emptyList()
            }
        }

        fun selectionGroups(
            split: WorkoutSplit,
            availablePrimaryMuscles: List<String>,
            availableSecondaryMuscles: List<String>
        ): List<WorkoutSplitGroup> {
            val available = (availablePrimaryMuscles + availableSecondaryMuscles).toSet().sorted()
            val configured = groups(split, available).filter { it.muscles.isNotEmpty() }
            return configured.ifEmpty { available.map { WorkoutSplitGroup(it, setOf(it)) } }
        }
    }
}

data class ExerciseLiftSet(
    val weight: String,
    val weightUnit: WorkoutWeightUnit?,
    val reps: String
)

data class ExerciseLiftDay(
    val dateKey: String,
    val sets: List<ExerciseLiftSet>
)

object ExerciseLiftHistory {
    fun normalizedName(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), " ")

    fun matches(itemId: String, name: String, candidateItemId: String, candidateName: String): Boolean {
        if (itemId.isNotEmpty() && itemId == candidateItemId) return true
        val left = normalizedName(name)
        val right = normalizedName(candidateName)
        return left.isNotEmpty() && left == right
    }

    fun performedSets(completed: List<CompletedSet>): List<ExerciseLiftSet> =
        completed.filter { it.isPerformed }.map {
            ExerciseLiftSet(
                weight = it.weight.trim(),
                weightUnit = it.weightUnit,
                reps = it.reps.trim()
            )
        }

    fun formatSetLine(set: ExerciseLiftSet, displayUnit: WorkoutWeightUnit): String {
        if (set.reps.isEmpty()) return ""
        if (set.weight.isEmpty()) return "${set.reps} reps"
        val planned = PlannedSet(weight = set.weight, weightUnit = set.weightUnit, reps = set.reps)
        return "${planned.displayWeight(displayUnit)} ${displayUnit.storageValue} × ${set.reps}"
    }

    fun formatSummary(sets: List<ExerciseLiftSet>, displayUnit: WorkoutWeightUnit): String =
        sets.mapNotNull { line ->
            formatSetLine(line, displayUnit).takeIf { it.isNotEmpty() }
        }.joinToString(", ")

    fun history(
        state: WorkoutPersistedState,
        itemId: String,
        name: String,
        beforeDateKey: String,
        limit: Int = 90
    ): List<ExerciseLiftDay> {
        val before = WorkoutDate.requireKey(beforeDateKey)
        val dateKeys = state.completedSessions
            .map { it.diaryDateKey }
            .distinct()
            .filter { it < before }
            .sortedDescending()
        val results = mutableListOf<ExerciseLiftDay>()
        for (key in dateKeys) {
            if (results.size >= limit) break
            val sets = liftSets(state, itemId, name, key)
            if (sets.isNotEmpty()) results += ExerciseLiftDay(key, sets)
        }
        return results
    }

    fun lastSummary(
        state: WorkoutPersistedState,
        itemId: String,
        name: String,
        beforeDateKey: String,
        displayUnit: WorkoutWeightUnit
    ): String? {
        val latest = history(state, itemId, name, beforeDateKey, limit = 1).firstOrNull() ?: return null
        return formatSummary(latest.sets, displayUnit).takeIf { it.isNotEmpty() }
    }

    private fun liftSets(
        state: WorkoutPersistedState,
        itemId: String,
        name: String,
        dateKey: String
    ): List<ExerciseLiftSet> {
        return preferredHistorySession(state, dateKey)
            ?.exercises
            ?.firstOrNull { matches(itemId, name, it.itemId, it.name) }
            ?.let { performedSets(it.sets) }
            .orEmpty()
    }

    private fun preferredHistorySession(state: WorkoutPersistedState, dateKey: String): WorkoutSession? {
        val sessions = state.completedSessions.filter { it.diaryDateKey == dateKey }
        if (sessions.isEmpty()) return null
        val burns = sessions.filter { it.caloriesBurned != null }
        if (burns.isNotEmpty()) {
            return burns.maxWith(
                compareBy<WorkoutSession> { it.healthSyncVersion ?: 0 }.thenBy { it.completedAt }
            )
        }
        return sessions.maxByOrNull { it.completedAt }
    }
}
