package com.apoorvdarshan.calorietracker.services.heartrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class HeartRateSignalProcessorTest {
    @Test fun estimates60Bpm() = assertSyntheticRate(60)
    @Test fun estimates90Bpm() = assertSyntheticRate(90)
    @Test fun estimates150Bpm() = assertSyntheticRate(150)

    @Test
    fun recoversWeakFundamentalWhenSecondHarmonicIsStronger() {
        listOf(72, 96).forEach { expectedBpm ->
            val harmonicRich = samples(24) { _, time ->
                178.0 +
                    4.8 * sin(2.0 * PI * (expectedBpm / 60.0) * time) +
                    11.0 * sin(2.0 * PI * (expectedBpm / 30.0) * time) +
                    0.7 * sin(2.0 * PI * 0.18 * time)
            }

            val result = HeartRateSignalProcessor.estimate(harmonicRich)

            requireNotNull(result)
            assertEquals(expectedBpm.toDouble(), result.bpm.toDouble(), 2.0)
            assertTrue(result.quality >= 0.42)
        }
    }

    @Test
    fun doesNotHalveCleanHighRateSignalWithoutFundamentalSupport() {
        val result = HeartRateSignalProcessor.estimate(synthetic(160))

        requireNotNull(result)
        assertEquals(160.0, result.bpm.toDouble(), 2.0)
    }

    @Test
    fun rejectsUnresolvedSubharmonicRatherThanReportingDoubleBpm() {
        val unresolved = samples(24) { _, time ->
            178.0 +
                2.0 * sin(2.0 * PI * 1.2 * time) +
                11.0 * sin(2.0 * PI * 2.4 * time)
        }

        assertNull(HeartRateSignalProcessor.estimate(unresolved))
    }

    @Test
    fun cameraEstimatorUsesConservativeBounds() {
        assertEquals(40, HeartRateSignalProcessor.MIN_CAMERA_BPM)
        assertEquals(200, HeartRateSignalProcessor.MAX_CAMERA_BPM)
    }

    @Test
    fun rejectsFlatAndNonContactSignals() {
        val flat = samples(24) { _, _ -> 180.0 }
        val noContact = samples(24, green = 65.0, blue = 48.0) { index, _ ->
            55.0 + sin(index.toDouble())
        }

        assertNull(HeartRateSignalProcessor.estimate(flat))
        assertNull(HeartRateSignalProcessor.estimate(noContact))
    }

    @Test
    fun contactGateRejectsClippingSaturationAndSpatiallyUnevenFrames() {
        assertTrue(HeartRateSignalProcessor.hasFingerContact(contactSample(timestampNanos = 1L)))
        assertTrue(!HeartRateSignalProcessor.hasFingerContact(
            contactSample(timestampNanos = 1L, redMean = 254.0)
        ))
        assertTrue(!HeartRateSignalProcessor.hasFingerContact(
            contactSample(timestampNanos = 1L, clippedFraction = 0.45)
        ))
        assertTrue(!HeartRateSignalProcessor.hasFingerContact(
            contactSample(timestampNanos = 1L, spatialStdDev = 85.0)
        ))
        assertTrue(!HeartRateSignalProcessor.hasFingerContact(
            contactSample(timestampNanos = 1L, redMean = 100.0, greenMean = 95.0)
        ))
    }

    @Test
    fun rejectsSignalThatIsTooShort() {
        assertNull(HeartRateSignalProcessor.estimate(synthetic(90, seconds = 8)))
    }

    @Test
    fun rejectsDeterministicBroadbandNoise() {
        val random = Random(7)
        val noise = samples(24) { _, _ -> 178.0 + random.nextDouble(-16.0, 16.0) }

        assertNull(HeartRateSignalProcessor.estimate(noise))
    }

    @Test
    fun rejectsExcessiveSignalNoiseEvenWhenContactChannelsRemainPlausible() {
        val highAmplitudeNoise = samples(24) { index, _ ->
            178.0 + if (index % 2 == 0) 35.0 else -35.0
        }

        assertNull(HeartRateSignalProcessor.estimate(highAmplitudeNoise))
    }

    @Test
    fun rejectsCompetingFundamentalAndHarmonic() {
        val ambiguous = samples(24) { _, time ->
            178.0 + 8.0 * sin(2.0 * PI * 1.25 * time) +
                8.0 * sin(2.0 * PI * 2.5 * time)
        }

        assertNull(HeartRateSignalProcessor.estimate(ambiguous))
    }

    @Test
    fun rejectsRateThatChangesBetweenMeasurementHalves() {
        val motionShift = samples(24) { _, time ->
            val bpm = if (time < 12.0) 70.0 else 132.0
            178.0 + 11.5 * sin(2.0 * PI * (bpm / 60.0) * time)
        }

        assertNull(HeartRateSignalProcessor.estimate(motionShift))
    }

    @Test
    fun rejectsExcessiveTimestampGap() {
        val regular = synthetic(90).toMutableList()
        val gapIndex = regular.size / 2
        for (index in gapIndex until regular.size) {
            regular[index] = regular[index].copy(
                timestampNanos = regular[index].timestampNanos + 300_000_000L
            )
        }

        assertNull(HeartRateSignalProcessor.estimate(regular))
    }

    @Test
    fun rejectsIrregularCaptureCadence() {
        var timestamp = 0L
        val irregular = List(721) { index ->
            if (index > 0) timestamp += if (index % 2 == 0) 18_000_000L else 49_000_000L
            val time = timestamp / 1_000_000_000.0
            contactSample(
                timestampNanos = timestamp,
                redMean = pulseValue(90, time)
            )
        }

        assertNull(HeartRateSignalProcessor.estimate(irregular))
    }

    @Test
    fun toleratesNormalCameraFrameDrops() {
        var timestamp = 0L
        val droppedFrames = buildList {
            while (timestamp <= 24_000_000_000L) {
                val time = timestamp / 1_000_000_000.0
                add(contactSample(timestamp, pulseValue(84, time)))
                val frame = size
                timestamp += when {
                    frame % 83 == 0 -> FRAME_NANOS * 3
                    frame % 29 == 0 -> FRAME_NANOS * 2
                    else -> FRAME_NANOS
                }
            }
        }

        val result = HeartRateSignalProcessor.estimate(droppedFrames)

        requireNotNull(result)
        assertEquals(84.0, result.bpm.toDouble(), 2.0)
    }

    @Test
    fun estimateRejectsDuplicateAndRegressingTimestamps() {
        val duplicate = synthetic(90).toMutableList().also {
            it[100] = it[100].copy(timestampNanos = it[99].timestampNanos)
        }
        val regression = synthetic(90).toMutableList().also {
            it[100] = it[100].copy(timestampNanos = it[99].timestampNanos - 1L)
        }

        assertNull(HeartRateSignalProcessor.estimate(duplicate))
        assertNull(HeartRateSignalProcessor.estimate(regression))
    }

    @Test
    fun ingestTerminatesOnDuplicateOrRegressingTimestamp() {
        val duplicateProcessor = HeartRateSignalProcessor(acquisitionTimeoutSeconds = null)
        duplicateProcessor.ingest(contactSample(timestampNanos = 1_000_000_000L))
        assertEquals(
            PpgStage.POOR_SIGNAL,
            duplicateProcessor.ingest(contactSample(timestampNanos = 1_000_000_000L)).stage
        )

        val regressionProcessor = HeartRateSignalProcessor(acquisitionTimeoutSeconds = null)
        regressionProcessor.ingest(contactSample(timestampNanos = 2_000_000_000L))
        assertEquals(
            PpgStage.POOR_SIGNAL,
            regressionProcessor.ingest(contactSample(timestampNanos = 1_999_999_999L)).stage
        )
    }

    @Test
    fun contactLossResetsContinuousMeasurementBudgetAndProgress() {
        val processor = HeartRateSignalProcessor(
            targetSeconds = 12.0,
            maximumSessionSeconds = 15.0,
            acquisitionTimeoutSeconds = 40.0
        )
        for (index in 0..300) {
            val time = index / 30.0
            processor.ingest(contactSample(index * FRAME_NANOS, pulseValue(90, time)))
        }

        // Brief misses should keep the measuring window alive.
        assertEquals(
            PpgStage.MEASURING,
            processor.ingest(nonContactSample(301 * FRAME_NANOS)).stage
        )

        var lost: PpgUpdate? = null
        for (index in 302..(301 + 13)) {
            lost = processor.ingest(nonContactSample(index * FRAME_NANOS))
        }
        assertEquals(PpgStage.FINDING_FINGER, lost?.stage)

        val resumed = processor.ingest(
            contactSample(320 * FRAME_NANOS, pulseValue(90, 320 / 30.0))
        )

        assertEquals(PpgStage.MEASURING, resumed.stage)
        assertEquals(0.0, resumed.progress, 0.0001)
    }

    @Test
    fun acquisitionTimeoutIsSeparateFromContinuousContactBudget() {
        val processor = HeartRateSignalProcessor(
            targetSeconds = 12.0,
            maximumSessionSeconds = 15.0,
            acquisitionTimeoutSeconds = 2.0
        )

        assertEquals(PpgStage.FINDING_FINGER, processor.ingest(nonContactSample(0L)).stage)
        assertEquals(PpgStage.FINDING_FINGER, processor.ingest(nonContactSample(1_000_000_000L)).stage)
        assertEquals(PpgStage.POOR_SIGNAL, processor.ingest(nonContactSample(2_000_000_000L)).stage)
    }

    @Test
    fun contactAcquiredBeforeTimeoutReceivesItsOwnFullMeasurementBudget() {
        val processor = HeartRateSignalProcessor(
            targetSeconds = 12.0,
            maximumSessionSeconds = 15.0,
            acquisitionTimeoutSeconds = 5.0
        )

        assertEquals(PpgStage.FINDING_FINGER, processor.ingest(nonContactSample(0L)).stage)
        assertEquals(PpgStage.FINDING_FINGER, processor.ingest(nonContactSample(4_000_000_000L)).stage)
        assertEquals(PpgStage.MEASURING, processor.ingest(contactSample(4_900_000_000L)).stage)
        assertEquals(PpgStage.MEASURING, processor.ingest(contactSample(6_000_000_000L)).stage)
    }

    @Test
    fun acquisitionTimeoutCanBeDisabled() {
        val processor = HeartRateSignalProcessor(acquisitionTimeoutSeconds = null)

        assertEquals(PpgStage.FINDING_FINGER, processor.ingest(nonContactSample(0L)).stage)
        assertEquals(
            PpgStage.FINDING_FINGER,
            processor.ingest(nonContactSample(120_000_000_000L)).stage
        )
    }

    @Test
    fun inconclusiveAnalysisKeepsMeasuringUntilContinuousContactDeadline() {
        val processor = HeartRateSignalProcessor(
            targetSeconds = 12.0,
            maximumSessionSeconds = 15.0,
            acquisitionTimeoutSeconds = null
        )
        var atMinimum: PpgUpdate? = null
        var beforeDeadline: PpgUpdate? = null
        var atDeadline: PpgUpdate? = null
        for (index in 0..449) {
            val update = processor.ingest(contactSample(index * FRAME_NANOS, redMean = 178.0))
            if (index == 361) atMinimum = update
            if (index == 449) beforeDeadline = update
        }
        atDeadline = processor.ingest(contactSample(15_000_000_000L, redMean = 178.0))

        assertEquals(PpgStage.MEASURING, atMinimum?.stage)
        assertEquals(true, atMinimum?.refiningSignal)
        assertEquals(12.0 / 15.0, atMinimum?.progress ?: -1.0, 0.03)
        assertEquals(PpgStage.MEASURING, beforeDeadline?.stage)
        assertEquals(true, beforeDeadline?.refiningSignal)
        assertEquals(PpgStage.POOR_SIGNAL, atDeadline?.stage)
    }

    @Test
    fun fixedSampleHardCapPreventsUnboundedBuffering() {
        val processor = HeartRateSignalProcessor(
            targetSeconds = 12.0,
            maximumSessionSeconds = 30.0,
            acquisitionTimeoutSeconds = null
        )
        var update = PpgUpdate(PpgStage.FINDING_FINGER)
        for (index in 0..HeartRateSignalProcessor.MAX_BUFFERED_SAMPLES) {
            update = processor.ingest(
                contactSample(timestampNanos = index * 10_000_000L, redMean = 178.0)
            )
        }

        assertEquals(PpgStage.POOR_SIGNAL, update.stage)
    }

    @Test
    fun estimateRejectsInputBeyondFixedSampleHardCap() {
        val oversized = List(HeartRateSignalProcessor.MAX_BUFFERED_SAMPLES + 1) { index ->
            contactSample(
                timestampNanos = index * 10_000_000L,
                redMean = pulseValue(90, index / 100.0)
            )
        }

        assertNull(HeartRateSignalProcessor.estimate(oversized))
    }

    @Test
    fun fixedTimeHardCapCannotBeRaisedByCaller() {
        var threw = false
        try {
            HeartRateSignalProcessor(
                targetSeconds = 22.0,
                maximumSessionSeconds = 31.0
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }

        assertTrue(threw)
    }

    private fun assertSyntheticRate(expected: Int) {
        val result = HeartRateSignalProcessor.estimate(synthetic(expected))
        requireNotNull(result)
        assertEquals(expected.toDouble(), result.bpm.toDouble(), 2.0)
        assertTrue(result.quality >= 0.42)
    }

    private fun synthetic(bpm: Int, seconds: Int = 24): List<PpgFrameSample> =
        samples(seconds) { _, time -> pulseValue(bpm, time) }

    private fun pulseValue(bpm: Int, time: Double): Double =
        178.0 + 11.5 * sin(2.0 * PI * (bpm / 60.0) * time) +
            0.7 * sin(2.0 * PI * 0.18 * time)

    private fun samples(
        seconds: Int,
        green: Double = 65.0,
        blue: Double = 48.0,
        red: (index: Int, timeSeconds: Double) -> Double
    ): List<PpgFrameSample> = List(seconds * 30 + 1) { index ->
        val timestamp = index * FRAME_NANOS
        PpgFrameSample(
            timestampNanos = timestamp,
            redMean = red(index, timestamp / 1_000_000_000.0),
            greenMean = green,
            blueMean = blue
        )
    }

    private fun contactSample(
        timestampNanos: Long,
        redMean: Double = 178.0,
        greenMean: Double = 65.0,
        blueMean: Double = 48.0,
        clippedFraction: Double = 0.0,
        spatialStdDev: Double = 8.0
    ) = PpgFrameSample(
        timestampNanos = timestampNanos,
        redMean = redMean,
        greenMean = greenMean,
        blueMean = blueMean,
        redClippedFraction = clippedFraction,
        redSpatialStdDev = spatialStdDev
    )

    private fun nonContactSample(timestampNanos: Long) = PpgFrameSample(
        timestampNanos = timestampNanos,
        redMean = 55.0,
        greenMean = 50.0,
        blueMean = 48.0,
        redClippedFraction = 0.0,
        redSpatialStdDev = 8.0
    )

    companion object {
        private const val FRAME_NANOS = 33_333_333L
    }
}
