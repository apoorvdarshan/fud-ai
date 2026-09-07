package com.apoorvdarshan.calorietracker.services.heartrate

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Privacy-safe scalar extracted from a frame. No bitmap, frame, or waveform leaves memory. */
data class PpgFrameSample(
    val timestampNanos: Long,
    val redMean: Double,
    val greenMean: Double,
    val blueMean: Double,
    /** Fraction of pixels in the sampled ROI whose red channel is clipped near its maximum. */
    val redClippedFraction: Double = 0.0,
    /** Spatial red-channel standard deviation in the ROI, or NaN when it was not measured. */
    val redSpatialStdDev: Double = Double.NaN
)

enum class PpgStage {
    FINDING_FINGER,
    MEASURING,
    COMPLETE,
    POOR_SIGNAL
}

data class HeartRateMeasurement(
    val bpm: Int,
    val quality: Double,
    val durationSeconds: Double
)

data class PpgUpdate(
    val stage: PpgStage,
    val progress: Double = 0.0,
    val measurement: HeartRateMeasurement? = null,
    /** True once the minimum capture window has elapsed but a stable BPM is not ready yet. */
    val refiningSignal: Boolean = false
)

/**
 * Pure contact-PPG processor. It gates fingertip contact, keeps only in-memory scalar samples,
 * and estimates the pulse frequency without retaining frames or a waveform.
 *
 * [maximumSessionSeconds] is a continuous-contact measurement budget. Losing contact resets that
 * budget and its samples. [acquisitionTimeoutSeconds] is a separate optional wall-clock limit for
 * finding and maintaining contact; passing null lets the user keep trying until the dialog closes.
 */
class HeartRateSignalProcessor(
    private val targetSeconds: Double = 15.0,
    private val maximumSessionSeconds: Double = 30.0,
    private val minimumQuality: Double = 0.32,
    private val acquisitionTimeoutSeconds: Double? = DEFAULT_ACQUISITION_TIMEOUT_SECONDS
) {
    private val samples = ArrayList<PpgFrameSample>(DEFAULT_SAMPLE_CAPACITY)
    private var acquisitionStartNanos: Long? = null
    private var contactStartNanos: Long? = null
    private var lastFrameNanos: Long? = null
    private var lastAnalysisNanos: Long? = null
    private var terminalUpdate: PpgUpdate? = null
    private var consecutiveMissedContactFrames = 0
    private var lastMeasuringUpdate: PpgUpdate? = null

    init {
        require(targetSeconds in MIN_ANALYSIS_SECONDS..maximumSessionSeconds)
        require(maximumSessionSeconds in targetSeconds..HARD_MEASUREMENT_CAP_SECONDS)
        require(minimumQuality in 0.0..1.0)
        require(acquisitionTimeoutSeconds == null || acquisitionTimeoutSeconds > 0.0)
    }

    fun reset() {
        samples.clear()
        acquisitionStartNanos = null
        contactStartNanos = null
        lastFrameNanos = null
        lastAnalysisNanos = null
        terminalUpdate = null
        consecutiveMissedContactFrames = 0
        lastMeasuringUpdate = null
    }

    fun ingest(sample: PpgFrameSample): PpgUpdate {
        terminalUpdate?.let { return it }

        val previousTimestamp = lastFrameNanos
        if (previousTimestamp != null && sample.timestampNanos <= previousTimestamp) {
            return terminalPoorSignal()
        }
        lastFrameNanos = sample.timestampNanos

        val acquisitionStart = acquisitionStartNanos
            ?: sample.timestampNanos.also { acquisitionStartNanos = it }
        if (contactStartNanos == null && acquisitionTimeoutSeconds != null &&
            secondsBetween(acquisitionStart, sample.timestampNanos) >= acquisitionTimeoutSeconds
        ) {
            return terminalPoorSignal()
        }

        if (!hasFingerContact(sample)) {
            consecutiveMissedContactFrames += 1
            if (contactStartNanos == null ||
                consecutiveMissedContactFrames > MISSED_CONTACT_TOLERANCE_FRAMES
            ) {
                clearContactWindow()
                return PpgUpdate(PpgStage.FINDING_FINGER)
            }
            // Keep measuring through brief coverage gaps (uneven flash / slight finger slip).
            return lastMeasuringUpdate ?: PpgUpdate(PpgStage.MEASURING)
        }
        consecutiveMissedContactFrames = 0

        val contactStart = contactStartNanos
            ?: sample.timestampNanos.also { contactStartNanos = it }
        if (samples.size >= MAX_BUFFERED_SAMPLES) return terminalPoorSignal()
        samples += sample

        val elapsed = secondsBetween(contactStart, sample.timestampNanos)
        val analysisIsDue = elapsed >= targetSeconds && (
            lastAnalysisNanos == null ||
                secondsBetween(lastAnalysisNanos ?: sample.timestampNanos, sample.timestampNanos) >=
                ANALYSIS_INTERVAL_SECONDS ||
                elapsed >= maximumSessionSeconds
            )
        if (analysisIsDue) {
            lastAnalysisNanos = sample.timestampNanos
            val result = estimate(samples)
            if (result != null && result.quality >= minimumQuality) {
                return PpgUpdate(PpgStage.COMPLETE, 1.0, result).also { terminalUpdate = it }
            }
        }

        // An inconclusive early analysis is not terminal: use the remaining continuous-contact
        // budget to collect a cleaner signal, including one final analysis at the deadline.
        if (elapsed >= maximumSessionSeconds) return terminalPoorSignal()
        // Map progress across the full contact budget so the bar does not sit at 100% while
        // quality checks continue after the minimum capture window.
        return PpgUpdate(
            stage = PpgStage.MEASURING,
            progress = (elapsed / maximumSessionSeconds).coerceIn(0.0, 1.0),
            refiningSignal = elapsed >= targetSeconds
        ).also { lastMeasuringUpdate = it }
    }

    private fun clearContactWindow() {
        samples.clear()
        contactStartNanos = null
        lastAnalysisNanos = null
        consecutiveMissedContactFrames = 0
        lastMeasuringUpdate = null
    }

    private fun terminalPoorSignal(): PpgUpdate =
        PpgUpdate(PpgStage.POOR_SIGNAL).also { terminalUpdate = it }

    companion object {
        private const val DEFAULT_ACQUISITION_TIMEOUT_SECONDS = 45.0
        private const val HARD_MEASUREMENT_CAP_SECONDS = 30.0
        private const val ANALYSIS_INTERVAL_SECONDS = 0.5
        private const val DEFAULT_SAMPLE_CAPACITY = 960
        internal const val MAX_BUFFERED_SAMPLES = 2_701 // 30 seconds at the accepted 90 Hz ceiling.
        private const val MISSED_CONTACT_TOLERANCE_FRAMES = 12

        private const val MIN_ANALYSIS_SECONDS = 10.0
        private const val MIN_SAMPLE_RATE_HZ = 10.0
        private const val MAX_SAMPLE_RATE_HZ = 90.0
        private const val MAX_GAP_SECONDS = 0.30
        private const val MAX_GAP_TO_MEDIAN_RATIO = 6.0
        private const val MAX_CADENCE_COEFFICIENT = 0.45
        private const val MAX_CADENCE_OUTLIER_FRACTION = 0.30

        // Torch + fingertip PPG is usually bright/near-saturated red. Rejecting high
        // red means or clipped pixels made real devices never detect contact.
        private const val MIN_RED_LEVEL = 35.0
        private const val MAX_RED_LEVEL = 255.0
        private const val MIN_RED_DOMINANCE_RATIO = 1.03
        private const val MIN_RED_DOMINANCE_DELTA = 5.0
        private const val MAX_RED_CLIPPED_FRACTION = 0.98
        private const val MAX_RED_SPATIAL_STD_DEV = 140.0

        private const val MIN_SIGNAL_STD_DEV = 0.12
        private const val MAX_SIGNAL_STD_DEV = 30.0
        private const val MIN_PEAK_POWER = 0.22
        private const val MAX_RUNNER_UP_RATIO = 0.68
        private const val MAX_RESIDUAL_NOISE_RATIO = 0.78
        private const val MIN_AUTOCORRELATION = 0.34
        private const val MIN_HARMONIC_AUTOCORRELATION_GAIN = 0.08
        private const val MAX_HALF_BPM_DELTA = 8.0
        private const val MIN_HALF_PEAK_POWER = 0.18
        private const val RUNNER_UP_EXCLUSION_BPM = 8.0
        private const val HARMONIC_MATCH_TOLERANCE_BPM = 2.0
        private const val MIN_PLAUSIBLE_SUBHARMONIC_POWER = 0.02
        private const val MIN_PLAUSIBLE_SUBHARMONIC_POWER_RATIO = 0.025
        private const val MIN_FUNDAMENTAL_TO_HARMONIC_POWER_RATIO = 0.08
        private const val MAX_FUNDAMENTAL_TO_HARMONIC_POWER_RATIO = 0.62
        private const val MIN_RECOVERED_FUNDAMENTAL_POWER = 0.06
        private const val HARMONIC_EVIDENCE_WEIGHT = 0.35

        internal const val MIN_CAMERA_BPM = 40
        internal const val MAX_CAMERA_BPM = 200

        fun hasFingerContact(sample: PpgFrameSample): Boolean {
            val channelsAreValid = sample.redMean.isFinite() &&
                sample.greenMean.isFinite() &&
                sample.blueMean.isFinite() &&
                sample.redMean in MIN_RED_LEVEL..MAX_RED_LEVEL &&
                sample.greenMean in 0.0..255.0 &&
                sample.blueMean in 0.0..255.0
            if (!channelsAreValid) return false

            val clippingIsValid = sample.redClippedFraction.isFinite() &&
                sample.redClippedFraction in 0.0..MAX_RED_CLIPPED_FRACTION
            if (!clippingIsValid) return false

            val spatialUniformityIsValid = sample.redSpatialStdDev.isNaN() ||
                (sample.redSpatialStdDev.isFinite() &&
                    sample.redSpatialStdDev in 0.0..MAX_RED_SPATIAL_STD_DEV)
            if (!spatialUniformityIsValid) return false

            val strongestOtherChannel = max(sample.greenMean, sample.blueMean)
            return sample.redMean >= strongestOtherChannel * MIN_RED_DOMINANCE_RATIO &&
                sample.redMean - strongestOtherChannel >= MIN_RED_DOMINANCE_DELTA
        }

        /** Returns null for short, malformed, clipped, unstable, noisy, or poorly sampled signals. */
        fun estimate(input: List<PpgFrameSample>): HeartRateMeasurement? {
            if (input.size !in 2..MAX_BUFFERED_SAMPLES) return null
            if (input.any { !hasFingerContact(it) }) return null
            if (!hasStrictlyIncreasingTimestamps(input)) return null

            val start = input.first().timestampNanos
            val duration = secondsBetween(start, input.last().timestampNanos)
            if (duration !in MIN_ANALYSIS_SECONDS..HARD_MEASUREMENT_CAP_SECONDS) return null

            val times = input.map { secondsBetween(start, it.timestampNanos) }
            val cadence = cadenceMetrics(times) ?: return null
            if (cadence.sampleRate !in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ ||
                cadence.maximumGap > MAX_GAP_SECONDS ||
                cadence.maximumGap > cadence.medianGap * MAX_GAP_TO_MEDIAN_RATIO ||
                cadence.coefficientOfVariation > MAX_CADENCE_COEFFICIENT ||
                cadence.outlierFraction > MAX_CADENCE_OUTLIER_FRACTION
            ) return null

            val raw = input.map(PpgFrameSample::redMean)
            val detrended = removeLinearTrend(times, raw)
            val energy = detrended.sumOf { it * it }
            val stdDev = sqrt(energy / detrended.size)
            if (!stdDev.isFinite() || stdDev !in MIN_SIGNAL_STD_DEV..MAX_SIGNAL_STD_DEV) return null

            val powers = scanPowers(times, detrended, energy)
            val selection = selectPulseFrequency(powers) ?: return null
            val best = selection.fundamental
            if (!selection.rawPeak.power.isFinite() || selection.rawPeak.power < MIN_PEAK_POWER ||
                !selection.evidencePower.isFinite() || selection.evidencePower < MIN_PEAK_POWER
            ) return null

            // Compute the runner-up after the winner is known. Updating it during a one-way scan
            // can accidentally retain a neighbour of an earlier provisional winner.
            val runnerUpPower = powers.asSequence()
                .filter { abs(it.bpm - best.bpm) > RUNNER_UP_EXCLUSION_BPM }
                .filter { candidate ->
                    selection.secondHarmonic?.let { harmonic ->
                        abs(candidate.bpm - harmonic.bpm) > RUNNER_UP_EXCLUSION_BPM
                    } ?: true
                }
                .maxOfOrNull(FrequencyPower::power)
                ?.coerceAtLeast(0.0)
                ?: 0.0
            val runnerUpRatio = runnerUpPower / selection.evidencePower
            if (!runnerUpRatio.isFinite() || runnerUpRatio > MAX_RUNNER_UP_RATIO) return null

            val residualNoiseRatio = residualNoiseRatio(
                times = times,
                values = detrended,
                bpm = best.bpm,
                includeSecondHarmonic = selection.recoveredFromSecondHarmonic
            )
            if (!residualNoiseRatio.isFinite() || residualNoiseRatio > MAX_RESIDUAL_NOISE_RATIO) {
                return null
            }

            val autocorrelation = autocorrelationAtPulsePeriod(times, detrended, best.bpm)
            if (!autocorrelation.isFinite() || autocorrelation < MIN_AUTOCORRELATION) return null
            if (selection.recoveredFromSecondHarmonic) {
                val harmonicAutocorrelation = autocorrelationAtPulsePeriod(
                    times,
                    detrended,
                    selection.secondHarmonic?.bpm ?: return null
                )
                // A real fundamental makes consecutive second-harmonic cycles alternate slightly.
                // Requiring this correlation gain avoids halving a clean, genuinely high pulse.
                if (!harmonicAutocorrelation.isFinite() ||
                    autocorrelation - harmonicAutocorrelation < MIN_HARMONIC_AUTOCORRELATION_GAIN
                ) return null
            }

            val split = input.size / 2
            if (split < 2 || input.size - split < 2) return null
            val firstHalf = dominantFrequency(
                times.subList(0, split),
                detrended.subList(0, split)
            ) ?: return null
            val secondHalf = dominantFrequency(
                times.subList(split, times.size),
                detrended.subList(split, detrended.size)
            ) ?: return null
            if (firstHalf.power < MIN_HALF_PEAK_POWER ||
                secondHalf.power < MIN_HALF_PEAK_POWER ||
                abs(firstHalf.bpm - secondHalf.bpm) > MAX_HALF_BPM_DELTA
            ) return null

            val cadenceQuality = (1.0 - cadence.coefficientOfVariation / MAX_CADENCE_COEFFICIENT)
                .coerceIn(0.0, 1.0)
            val spectralQuality = ((selection.evidencePower - MIN_PEAK_POWER) /
                (1.0 - MIN_PEAK_POWER))
                .coerceIn(0.0, 1.0)
            val separationQuality = (1.0 - runnerUpRatio / MAX_RUNNER_UP_RATIO)
                .coerceIn(0.0, 1.0)
            val autocorrelationQuality = ((autocorrelation - MIN_AUTOCORRELATION) /
                (1.0 - MIN_AUTOCORRELATION)).coerceIn(0.0, 1.0)
            val stabilityQuality = (1.0 - abs(firstHalf.bpm - secondHalf.bpm) /
                MAX_HALF_BPM_DELTA).coerceIn(0.0, 1.0)
            val noiseQuality = (1.0 - residualNoiseRatio / MAX_RESIDUAL_NOISE_RATIO)
                .coerceIn(0.0, 1.0)
            val quality = (
                0.34 * spectralQuality +
                    0.18 * autocorrelationQuality +
                    0.16 * stabilityQuality +
                    0.14 * separationQuality +
                    0.10 * noiseQuality +
                    0.08 * cadenceQuality
                ).coerceIn(0.0, 1.0)

            val rounded = best.bpm.roundToInt()
            if (rounded !in MIN_CAMERA_BPM..MAX_CAMERA_BPM) return null
            return HeartRateMeasurement(rounded, quality, duration)
        }

        private data class FrequencyPower(val bpm: Double, val power: Double)

        private data class PulseFrequencySelection(
            val fundamental: FrequencyPower,
            val rawPeak: FrequencyPower,
            val secondHarmonic: FrequencyPower? = null,
            val evidencePower: Double = fundamental.power
        ) {
            val recoveredFromSecondHarmonic: Boolean
                get() = secondHarmonic != null
        }

        private data class CadenceMetrics(
            val sampleRate: Double,
            val medianGap: Double,
            val maximumGap: Double,
            val coefficientOfVariation: Double,
            val outlierFraction: Double
        )

        private fun hasStrictlyIncreasingTimestamps(input: List<PpgFrameSample>): Boolean =
            input.zipWithNext().all { (left, right) -> right.timestampNanos > left.timestampNanos }

        private fun cadenceMetrics(times: List<Double>): CadenceMetrics? {
            if (times.size < 3) return null
            val gaps = times.zipWithNext { left, right -> right - left }
            if (gaps.any { !it.isFinite() || it <= 0.0 }) return null
            val sortedGaps = gaps.sorted()
            val median = if (sortedGaps.size % 2 == 0) {
                val right = sortedGaps.size / 2
                (sortedGaps[right - 1] + sortedGaps[right]) / 2.0
            } else {
                sortedGaps[sortedGaps.size / 2]
            }
            if (!median.isFinite() || median <= 0.0) return null
            val mean = gaps.average()
            val variance = gaps.sumOf { (it - mean) * (it - mean) } / gaps.size
            val coefficient = sqrt(variance) / max(mean, 1e-9)
            val outlierFraction = gaps.count { gap ->
                gap < median * 0.5 || gap > median * 1.75
            }.toDouble() / gaps.size
            val duration = times.last() - times.first()
            if (duration <= 0.0) return null
            return CadenceMetrics(
                sampleRate = (times.size - 1) / duration,
                medianGap = median,
                maximumGap = gaps.maxOrNull() ?: return null,
                coefficientOfVariation = coefficient,
                outlierFraction = outlierFraction
            )
        }

        private fun scanPowers(
            times: List<Double>,
            values: List<Double>,
            energy: Double
        ): List<FrequencyPower> {
            if (energy <= 0.0) return emptyList()
            val powers = ArrayList<FrequencyPower>(
                ((MAX_CAMERA_BPM - MIN_CAMERA_BPM) * 2) + 1
            )
            var bpm = MIN_CAMERA_BPM.toDouble()
            while (bpm <= MAX_CAMERA_BPM) {
                val angularFrequency = 2.0 * PI * (bpm / 60.0)
                var sineProjection = 0.0
                var cosineProjection = 0.0
                for (index in times.indices) {
                    val phase = angularFrequency * times[index]
                    sineProjection += values[index] * sin(phase)
                    cosineProjection += values[index] * cos(phase)
                }
                val power = (2.0 * (
                    sineProjection * sineProjection + cosineProjection * cosineProjection
                    ) / (values.size * energy)).coerceAtLeast(0.0)
                powers += FrequencyPower(bpm, power)
                bpm += 0.5
            }
            return powers
        }

        /**
         * A contact-PPG waveform is not sinusoidal, so its second harmonic can legitimately be
         * stronger than its pulse fundamental. Recover the lower rate only when there is a clear,
         * bounded peak very close to half the raw winner. A near-equal pair remains ambiguous and
         * is deliberately left for the runner-up gate to reject.
         */
        private fun selectPulseFrequency(
            powers: List<FrequencyPower>
        ): PulseFrequencySelection? {
            val rawPeak = powers.maxByOrNull(FrequencyPower::power) ?: return null
            if (!rawPeak.power.isFinite() || rawPeak.power <= 0.0) return null

            val expectedFundamentalBpm = rawPeak.bpm / 2.0
            if (expectedFundamentalBpm < MIN_CAMERA_BPM) {
                return PulseFrequencySelection(rawPeak, rawPeak)
            }
            val fundamental = powers.asSequence()
                .filter {
                    abs(it.bpm - expectedFundamentalBpm) <= HARMONIC_MATCH_TOLERANCE_BPM
                }
                .maxByOrNull(FrequencyPower::power)
                ?: return PulseFrequencySelection(rawPeak, rawPeak)
            val supportRatio = fundamental.power / rawPeak.power
            val hasPlausibleSubharmonic = fundamental.power >=
                MIN_PLAUSIBLE_SUBHARMONIC_POWER &&
                supportRatio >= MIN_PLAUSIBLE_SUBHARMONIC_POWER_RATIO
            if (!hasPlausibleSubharmonic) {
                return PulseFrequencySelection(rawPeak, rawPeak)
            }
            val hasConservativeFundamentalSupport = fundamental.power.isFinite() &&
                fundamental.power >= MIN_RECOVERED_FUNDAMENTAL_POWER &&
                supportRatio >= MIN_FUNDAMENTAL_TO_HARMONIC_POWER_RATIO &&
                supportRatio <= MAX_FUNDAMENTAL_TO_HARMONIC_POWER_RATIO
            if (!hasConservativeFundamentalSupport) {
                // There is enough lower-frequency energy to make the raw 2x answer suspect, but
                // not enough to identify the lower peak confidently. Reject instead of reporting
                // a potentially doubled BPM.
                return null
            }

            val evidencePower = fundamental.power + HARMONIC_EVIDENCE_WEIGHT * rawPeak.power
            return PulseFrequencySelection(
                fundamental = fundamental,
                rawPeak = rawPeak,
                secondHarmonic = rawPeak,
                evidencePower = evidencePower.coerceAtMost(1.0)
            )
        }

        private fun dominantFrequency(times: List<Double>, values: List<Double>): FrequencyPower? {
            val localTimes = times.map { it - times.first() }
            val localValues = removeLinearTrend(localTimes, values)
            val energy = localValues.sumOf { it * it }
            if (energy <= 0.0) return null
            val selection = selectPulseFrequency(scanPowers(localTimes, localValues, energy))
                ?: return null
            return FrequencyPower(selection.fundamental.bpm, selection.evidencePower)
        }

        private fun residualNoiseRatio(
            times: List<Double>,
            values: List<Double>,
            bpm: Double,
            includeSecondHarmonic: Boolean
        ): Double {
            val harmonicOrders = if (includeSecondHarmonic) intArrayOf(1, 2) else intArrayOf(1)
            val components = harmonicOrders.map { order ->
                val angularFrequency = 2.0 * PI * (bpm / 60.0) * order
                var sineProjection = 0.0
                var cosineProjection = 0.0
                var sineEnergy = 0.0
                var cosineEnergy = 0.0
                for (index in times.indices) {
                    val sine = sin(angularFrequency * times[index])
                    val cosine = cos(angularFrequency * times[index])
                    sineProjection += values[index] * sine
                    cosineProjection += values[index] * cosine
                    sineEnergy += sine * sine
                    cosineEnergy += cosine * cosine
                }
                HarmonicFitComponent(
                    angularFrequency = angularFrequency,
                    sineCoefficient = sineProjection / max(sineEnergy, 1e-9),
                    cosineCoefficient = cosineProjection / max(cosineEnergy, 1e-9)
                )
            }
            var residualEnergy = 0.0
            var signalEnergy = 0.0
            for (index in times.indices) {
                val fitted = components.sumOf { component ->
                    component.sineCoefficient * sin(component.angularFrequency * times[index]) +
                        component.cosineCoefficient * cos(component.angularFrequency * times[index])
                }
                val residual = values[index] - fitted
                residualEnergy += residual * residual
                signalEnergy += values[index] * values[index]
            }
            return sqrt(residualEnergy / max(signalEnergy, 1e-9))
        }

        private data class HarmonicFitComponent(
            val angularFrequency: Double,
            val sineCoefficient: Double,
            val cosineCoefficient: Double
        )

        private fun autocorrelationAtPulsePeriod(
            times: List<Double>,
            values: List<Double>,
            bpm: Double
        ): Double {
            val cadence = cadenceMetrics(times) ?: return Double.NaN
            val periodSeconds = 60.0 / bpm
            val lag = (periodSeconds / cadence.medianGap).roundToInt()
            if (lag <= 0 || lag >= values.size) return Double.NaN
            var numerator = 0.0
            var leadingEnergy = 0.0
            var laggedEnergy = 0.0
            for (index in lag until values.size) {
                val leading = values[index]
                val lagged = values[index - lag]
                numerator += leading * lagged
                leadingEnergy += leading * leading
                laggedEnergy += lagged * lagged
            }
            return numerator / sqrt(max(leadingEnergy * laggedEnergy, 1e-12))
        }

        private fun removeLinearTrend(times: List<Double>, values: List<Double>): List<Double> {
            val meanT = times.average()
            val meanV = values.average()
            var numerator = 0.0
            var denominator = 0.0
            for (index in times.indices) {
                val dt = times[index] - meanT
                numerator += dt * (values[index] - meanV)
                denominator += dt * dt
            }
            val slope = if (denominator > 0.0) numerator / denominator else 0.0
            return times.indices.map { index ->
                values[index] - (meanV + slope * (times[index] - meanT))
            }
        }

        private fun secondsBetween(startNanos: Long, endNanos: Long): Double =
            (endNanos - startNanos) / 1_000_000_000.0
    }
}
