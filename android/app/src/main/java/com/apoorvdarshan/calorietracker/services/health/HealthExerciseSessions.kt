package com.apoorvdarshan.calorietracker.services.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Instant
import kotlin.math.roundToInt

/** Application id shared by Fud AI's release build and its `.debug` / `.debug2` variants. */
internal const val FUD_AI_APPLICATION_ID = "com.apoorvdarshan.calorietracker"

/** Every package name a Fud AI build writes Health Connect records under. */
internal val FUD_AI_HEALTH_PACKAGES: Set<String> = setOf(
    FUD_AI_APPLICATION_ID,
    "$FUD_AI_APPLICATION_ID.debug",
    "$FUD_AI_APPLICATION_ID.debug2"
)

/** True for records any Fud AI build wrote, so they are never imported back as another app's data. */
internal fun isFudAiHealthOrigin(packageName: String, ownPackage: String = FUD_AI_APPLICATION_ID): Boolean =
    packageName == ownPackage ||
        packageName == FUD_AI_APPLICATION_ID ||
        packageName.startsWith("$FUD_AI_APPLICATION_ID.")

/** The fields of an ExerciseSessionRecord the workout import keeps, read from Health Connect. */
data class HealthExerciseSession(
    val recordId: String,
    val exerciseType: Int,
    val startTime: Instant,
    val endTime: Instant,
    val originPackage: String,
    val lastModified: Instant,
    val deviceName: String?,
    /** Sum of the session's lap lengths in meters; null when it has none. */
    val lapDistanceMeters: Double?
)

/**
 * Another app's session, or null for one written by Fud AI itself (any build) and for
 * malformed rows (no record id, or a session that does not end after it starts).
 */
internal fun ExerciseSessionRecord.toExternalHealthExerciseSession(
    ownPackage: String = FUD_AI_APPLICATION_ID
): HealthExerciseSession? {
    val origin = metadata.dataOrigin.packageName
    if (origin.isBlank() || isFudAiHealthOrigin(origin, ownPackage)) return null
    val id = metadata.id.takeIf { it.isNotBlank() } ?: return null
    if (!endTime.isAfter(startTime)) return null
    val lapMeters = laps.mapNotNull { lap ->
        lap.length?.inMeters?.takeIf { it.isFinite() && it > 0.0 }
    }
    return HealthExerciseSession(
        recordId = id,
        exerciseType = exerciseType,
        startTime = startTime,
        endTime = endTime,
        originPackage = origin,
        lastModified = metadata.lastModifiedTime,
        deviceName = healthDeviceName(metadata.device?.manufacturer, metadata.device?.model),
        lapDistanceMeters = lapMeters.takeIf { it.isNotEmpty() }?.sum()
    )
}

/** "Garmin Forerunner 265" from manufacturer + model, without repeating a manufacturer the model already names. */
internal fun healthDeviceName(manufacturer: String?, model: String?): String? {
    val maker = manufacturer?.trim()?.takeIf { it.isNotEmpty() }
    val name = model?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        maker == null -> name
        name == null -> maker
        name.startsWith(maker, ignoreCase = true) -> name
        else -> "$maker $name"
    }
}

/**
 * Active calories to show for an imported session, in kcal. The recording app's own
 * Active Calories Burned over the session wins. Otherwise the session window's active
 * energy from every app except Fud AI ([allWindowKcal] minus [ownWindowKcal]) is used,
 * so a calculated burn Fud AI wrote into the same window is never counted. Null when
 * nothing positive remains.
 */
internal fun attributedSessionActiveCalories(
    sameOriginKcal: Double?,
    allWindowKcal: Double?,
    ownWindowKcal: Double?
): Int? {
    sameOriginKcal?.takeIf { it.isFinite() && it > 0.0 }?.let { kcal ->
        return kcal.roundToInt().takeIf { it > 0 }
    }
    val all = allWindowKcal?.takeIf { it.isFinite() } ?: return null
    val own = ownWindowKcal?.takeIf { it.isFinite() } ?: 0.0
    return externalActiveCalories(all, own).roundToInt().takeIf { it > 0 }
}

/** Outcome of one session's calorie lookup. [Failed] keeps the stored value and retries later. */
sealed interface SessionCalorieLookup {
    data class Resolved(val activeCalories: Int?) : SessionCalorieLookup
    data object Failed : SessionCalorieLookup
}
