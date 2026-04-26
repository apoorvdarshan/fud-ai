package com.apoorvdarshan.calorietracker.models

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Single body-fat reading at a point in time. Mirrors WeightEntry — stored in
 * BodyFatRepository, persisted via PreferencesStore as a JSON list, and (when
 * Health Connect is wired up later) bidirectionally synced to HC.
 *
 * The latest entry's value also becomes the user's "current" body fat %, kept
 * in sync with UserProfile.bodyFatPercentage so Katch-McArdle BMR re-evaluates
 * after every new reading.
 */
@Serializable
data class BodyFatEntry(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    @Serializable(with = InstantSerializer::class)
    val date: Instant = Instant.now(),
    /** Stored as a fraction (0.0–1.0), same convention as UserProfile.bodyFatPercentage. */
    val bodyFatFraction: Double
) {
    /** Convenience for views that prefer 0–100 scale (e.g. "23%"). */
    val bodyFatPercent: Double get() = bodyFatFraction * 100
}
