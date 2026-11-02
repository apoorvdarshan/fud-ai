package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.AIProvider
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Local, offline estimate of how long an AI analysis usually takes.
 *
 * The estimate is built only from durations this device measured itself, so it needs
 * no network call, no analytics and no extra provider round trip. With too few
 * samples the estimate stays empty and the UI shows the elapsed time only, rather
 * than a fabricated countdown.
 */
object AnalysisDurationEstimate {

    /** Below this many samples the estimate is not shown at all. */
    const val MIN_SAMPLES = 3

    /** How many recent samples are kept per provider/model/request-kind combination. */
    const val MAX_SAMPLES = 8

    /** The kind of request a duration belongs to. */
    enum class Kind { TEXT, IMAGE }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val durationsSerializer = MapSerializer(String.serializer(), ListSerializer(Long.serializer()))

    /** Storage key for the durations of one provider/model/request-kind combination. */
    fun key(provider: AIProvider, model: String, kind: Kind): String =
        "${provider.name}|$model|${kind.name}"

    /**
     * Reads the stored durations. A broken value starts over empty instead of
     * taking the progress of the app down with it.
     */
    fun readDurations(raw: String?): Map<String, List<Long>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString(durationsSerializer, raw) }.getOrDefault(emptyMap())
    }

    /** Encodes the durations for storage. */
    fun encodeDurations(durations: Map<String, List<Long>>): String =
        json.encodeToString(durationsSerializer, durations)

    /**
     * The usual duration band in seconds, or null when there is not enough history.
     *
     * The band spans the 25th to the 75th percentile of the samples, so a single
     * unusually slow request does not widen it.
     */
    fun estimateSeconds(samples: List<Long>): IntRange? {
        if (samples.size < MIN_SAMPLES) return null
        val sorted = samples.filter { it > 0 }.sorted()
        if (sorted.size < MIN_SAMPLES) return null
        val low = percentile(sorted, 0.25)
        val high = percentile(sorted, 0.75)
        val lowSeconds = ((low + 999) / 1000).toInt().coerceAtLeast(1)
        val highSeconds = ((high + 999) / 1000).toInt().coerceAtLeast(lowSeconds)
        return lowSeconds..highSeconds
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0
        if (sorted.size == 1) return sorted.first()
        // Interpolate between the two neighbouring samples instead of rounding down.
        // With three samples a floor would turn the upper quartile into the median,
        // so the band would not cover the slower half of the local history at all.
        val position = (sorted.size - 1) * fraction
        val lower = floor(position).toInt().coerceIn(0, sorted.size - 1)
        val upper = ceil(position).toInt().coerceIn(0, sorted.size - 1)
        if (lower == upper) return sorted[lower]
        val weight = position - lower
        return (sorted[lower] * (1.0 - weight) + sorted[upper] * weight).roundToLong()
    }
}
