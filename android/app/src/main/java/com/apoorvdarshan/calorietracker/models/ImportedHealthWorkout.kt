package com.apoorvdarshan.calorietracker.models

import androidx.annotation.StringRes
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.apoorvdarshan.calorietracker.R
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import kotlin.math.ceil

/**
 * A read-only exercise session another app (Garmin Connect, Coros, Strava, Samsung Health,
 * Fitbit, …) wrote to Health Connect. Port of iOS ImportedHealthWorkout: kept apart from
 * Fud AI's own diary and calculated burns, so Energy Burn and goal math never count it.
 */
@Serializable
data class ImportedHealthWorkout(
    /** Health Connect record id; stable when the recording app edits the session. */
    val id: String,
    /** One of ExerciseSessionRecord's EXERCISE_TYPE_* values. */
    val exerciseType: Int,
    @Serializable(with = InstantSerializer::class)
    val startedAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val endedAt: Instant,
    val durationSeconds: Int,
    /** Active kcal attributed to the session; null when no app recorded any. */
    val activeCalories: Int? = null,
    /** Sum of the session's lap lengths, when the recording app wrote laps. */
    val distanceMeters: Double? = null,
    /** Package that wrote the session to Health Connect. */
    val sourcePackage: String,
    /** That app's name on this phone, when Android can resolve it. */
    val sourceName: String? = null,
    val deviceName: String? = null,
    /** Local day the session started (yyyy-MM-dd): its Workouts diary day. */
    val diaryDateKey: String,
    /** Health Connect's last-modified time for the session, epoch milliseconds. */
    val lastModifiedEpochMs: Long = 0,
    /** [lastModifiedEpochMs] when [activeCalories] was last looked up; null retries the lookup. */
    val caloriesCheckedForModifiedMs: Long? = null
) {
    val durationMinutes: Int get() = maxOf(1, ceil(durationSeconds / 60.0).toInt())

    val diaryDate: LocalDate?
        get() = runCatching { LocalDate.parse(diaryDateKey) }.getOrNull()

    /** "Garmin Connect · Garmin Forerunner 265", or just the app when no device is known. */
    val sourceSummary: String
        get() {
            val app = sourceName ?: sourcePackage
            return deviceName?.takeIf { it != app }?.let { "$app · $it" } ?: app
        }
}

/** Sessions shown on one Workouts diary day, earliest first. */
internal fun importedWorkoutsOn(workouts: List<ImportedHealthWorkout>, day: LocalDate): List<ImportedHealthWorkout> {
    val key = day.toString()
    return workouts.filter { it.diaryDateKey == key }.sortedBy { it.startedAt }
}

/**
 * Localized name for a Health Connect exercise type. Variants Fud AI does not tell apart
 * (treadmill and outdoor running, pool and open-water swimming, …) share one name, and
 * unknown or future types read as a generic workout.
 */
@StringRes
internal fun importedWorkoutTypeLabel(exerciseType: Int): Int = when (exerciseType) {
    ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> R.string.health_exercise_badminton
    ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL -> R.string.health_exercise_baseball
    ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> R.string.health_exercise_basketball
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> R.string.health_exercise_cycling
    ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> R.string.health_exercise_boot_camp
    ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> R.string.health_exercise_boxing
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> R.string.health_exercise_calisthenics
    ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> R.string.health_exercise_cricket
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> R.string.health_exercise_dance
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> R.string.health_exercise_elliptical
    ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> R.string.health_exercise_class
    ExerciseSessionRecord.EXERCISE_TYPE_FENCING -> R.string.health_exercise_fencing
    ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN -> R.string.health_exercise_american_football
    ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN -> R.string.health_exercise_australian_football
    ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC -> R.string.health_exercise_disc_sports
    ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> R.string.health_exercise_golf
    ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING -> R.string.health_exercise_breathing
    ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS -> R.string.health_exercise_gymnastics
    ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL -> R.string.health_exercise_handball
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> R.string.health_exercise_hiit
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> R.string.health_exercise_hiking
    ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY,
    ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY -> R.string.health_exercise_hockey
    ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING,
    ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> R.string.health_exercise_skating
    ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> R.string.health_exercise_martial_arts
    ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> R.string.health_exercise_paddling
    ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING -> R.string.health_exercise_paragliding
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> R.string.health_exercise_pilates
    ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL -> R.string.health_exercise_racquetball
    ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> R.string.health_exercise_climbing
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> R.string.health_exercise_rowing
    ExerciseSessionRecord.EXERCISE_TYPE_RUGBY -> R.string.health_exercise_rugby
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> R.string.health_exercise_running
    ExerciseSessionRecord.EXERCISE_TYPE_SAILING -> R.string.health_exercise_sailing
    ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING -> R.string.health_exercise_scuba_diving
    ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> R.string.health_exercise_skiing
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> R.string.health_exercise_snowboarding
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING -> R.string.health_exercise_snowshoeing
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> R.string.health_exercise_soccer
    ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL -> R.string.health_exercise_softball
    ExerciseSessionRecord.EXERCISE_TYPE_SQUASH -> R.string.health_exercise_squash
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> R.string.health_exercise_stair_climbing
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> R.string.health_exercise_strength_training
    ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> R.string.health_exercise_stretching
    ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> R.string.health_exercise_surfing
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> R.string.health_exercise_swimming
    ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> R.string.health_exercise_table_tennis
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> R.string.health_exercise_tennis
    ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> R.string.health_exercise_volleyball
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> R.string.health_exercise_walking
    ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO -> R.string.health_exercise_water_polo
    ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> R.string.health_exercise_wheelchair
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> R.string.health_exercise_yoga
    else -> R.string.health_exercise_workout
}
