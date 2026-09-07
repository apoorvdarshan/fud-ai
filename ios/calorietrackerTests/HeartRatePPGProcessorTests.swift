import Foundation
import Testing
@testable import calorietracker

struct HeartRatePPGProcessorTests {
    @Test(arguments: [60.0, 90.0, 150.0])
    func estimatesDeterministicPulseSignals(expectedBPM: Double) throws {
        var processor = HeartRatePPGProcessor()
        let update = feedSignal(
            bpm: expectedBPM,
            duration: 22,
            processor: &processor
        )
        let result = try #require(update.result)

        #expect(update.contactDetected)
        #expect(update.progress == 1)
        #expect(update.status == .ready)
        #expect(abs(Double(result.bpm) - expectedBPM) <= 2)
        #expect(result.quality >= 0.65)
        #expect(result.duration >= 20)
    }

    @Test func waitsUntilTwentySecondsOfContinuousContact() {
        var processor = HeartRatePPGProcessor()
        let update = feedSignal(bpm: 72, duration: 12, processor: &processor)

        #expect(update.contactDetected)
        #expect(update.status == .collecting)
        #expect(update.result == nil)
        #expect(update.progress > 0.59 && update.progress < 0.61)
    }

    @Test func losingContactClearsTheMeasurementWindow() {
        var processor = HeartRatePPGProcessor()
        _ = feedSignal(bpm: 72, duration: 8, processor: &processor)
        #expect(processor.retainedSampleCount > 200)

        // A single miss should not wipe the window.
        var update = processor.process(
            sample: HeartRatePPGSample(
                timestamp: 8.1,
                red: 0.28,
                green: 0.31,
                blue: 0.29
            )
        )
        #expect(update.contactDetected)
        #expect(processor.retainedSampleCount > 200)

        for frame in 1...13 {
            update = processor.process(
                sample: HeartRatePPGSample(
                    timestamp: 8.1 + Double(frame) / 30.0,
                    red: 0.28,
                    green: 0.31,
                    blue: 0.29
                )
            )
        }

        #expect(!update.contactDetected)
        #expect(update.status == .waitingForContact)
        #expect(update.progress == 0)
        #expect(processor.retainedSampleCount == 0)
    }

    @Test func contactGateRejectsSaturationClippingAndSpatialNonuniformity() {
        var good = HeartRatePPGProcessor()
        #expect(good.process(sample: contactSample(timestamp: 0, red: 0.78)).contactDetected)

        var saturated = HeartRatePPGProcessor()
        #expect(!saturated.process(sample: contactSample(
            timestamp: 0,
            red: 254.0 / 255.0
        )).contactDetected)

        var clipped = HeartRatePPGProcessor()
        #expect(!clipped.process(sample: contactSample(
            timestamp: 0,
            red: 0.78,
            redClippedFraction: 0.40
        )).contactDetected)

        var uneven = HeartRatePPGProcessor()
        #expect(!uneven.process(sample: contactSample(
            timestamp: 0,
            red: 0.78,
            redSpatialStandardDeviation: 80.0 / 255.0
        )).contactDetected)
    }

    @Test func rejectsFlatSignal() {
        var processor = HeartRatePPGProcessor()
        let update = feedValues(duration: 22, processor: &processor) { _ in 0.78 }

        #expect(update.contactDetected)
        #expect(update.result == nil)
        #expect(update.status == .rejected(.flatSignal))
    }

    @Test func rejectsDeterministicBroadbandNoise() {
        var processor = HeartRatePPGProcessor()
        var generator = DeterministicGenerator(seed: 0xF00DCAFE)
        let update = feedValues(duration: 22, processor: &processor) { _ in
            0.78 + generator.nextSignedUnit() * 0.045
        }

        #expect(update.contactDetected)
        #expect(update.result == nil)
        #expect(update.status == .rejected(.noisySignal))
    }

    @Test func rejectsIrregularFrameCadence() {
        var processor = HeartRatePPGProcessor()
        var update = processor.currentUpdate
        var timestamp = 0.0
        for index in 0..<700 {
            timestamp += index.isMultiple(of: 20) ? 0.22 : 1.0 / 30.0
            update = processor.process(
                sample: contactSample(
                    timestamp: timestamp,
                    red: 0.78 + 0.012 * sin(2 * .pi * 1.2 * timestamp)
                )
            )
        }

        #expect(update.result == nil)
        #expect(update.status == .rejected(.irregularSampling))
    }

    @Test func prefersWeakFundamentalOverStrongerSecondHarmonic() throws {
        let expectedBPM = 78.0
        var processor = HeartRatePPGProcessor()
        let update = feedValues(duration: 22, processor: &processor) { timestamp in
            let fundamental = sin(2 * .pi * expectedBPM / 60 * timestamp)
            let secondHarmonic = sin(4 * .pi * expectedBPM / 60 * timestamp + 0.31)
            return 0.78 + 0.0045 * fundamental + 0.012 * secondHarmonic
        }
        let result = try #require(update.result)

        #expect(update.status == .ready)
        #expect(abs(Double(result.bpm) - expectedBPM) <= 2)
        #expect(result.quality >= 0.45)
    }

    @Test func doesNotHalveCleanHighPulse() throws {
        let expectedBPM = 160.0
        var processor = HeartRatePPGProcessor()
        let update = feedSignal(
            bpm: expectedBPM,
            duration: 22,
            processor: &processor
        )
        let result = try #require(update.result)

        #expect(update.status == .ready)
        #expect(abs(Double(result.bpm) - expectedBPM) <= 2)
        #expect(result.quality >= 0.65)
    }

    @Test func retainsOnlyTheBoundedThirtySecondWindow() {
        var processor = HeartRatePPGProcessor(
            configuration: .init(analysisInterval: 100)
        )
        _ = feedSignal(bpm: 90, duration: 45, processor: &processor)

        // At 30 fps, a 30-second inclusive window contains at most 901 observations.
        #expect(processor.retainedSampleCount <= 901)
        #expect(processor.retainedSampleCount >= 899)
    }

    private func feedSignal(
        bpm: Double,
        duration: TimeInterval,
        processor: inout HeartRatePPGProcessor
    ) -> HeartRatePPGUpdate {
        feedValues(duration: duration, processor: &processor) { timestamp in
            let fundamental = sin(2 * .pi * bpm / 60 * timestamp)
            let secondHarmonic = sin(4 * .pi * bpm / 60 * timestamp + 0.35)
            return 0.78 + 0.012 * fundamental + 0.002 * secondHarmonic
        }
    }

    private func feedValues(
        duration: TimeInterval,
        processor: inout HeartRatePPGProcessor,
        value: (TimeInterval) -> Double
    ) -> HeartRatePPGUpdate {
        let framesPerSecond = 30.0
        let frameCount = Int(duration * framesPerSecond)
        var update = processor.currentUpdate
        for frame in 0...frameCount {
            let timestamp = Double(frame) / framesPerSecond
            update = processor.process(
                sample: contactSample(timestamp: timestamp, red: value(timestamp))
            )
        }
        return update
    }

    private func contactSample(
        timestamp: TimeInterval,
        red: Double,
        redClippedFraction: Double = 0,
        redSpatialStandardDeviation: Double = 8.0 / 255.0
    ) -> HeartRatePPGSample {
        HeartRatePPGSample(
            timestamp: timestamp,
            red: red,
            green: 0.29,
            blue: 0.20,
            redClippedFraction: redClippedFraction,
            redSpatialStandardDeviation: redSpatialStandardDeviation
        )
    }
}

private struct DeterministicGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func nextSignedUnit() -> Double {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        let unit = Double(state >> 11) / Double(1 << 53)
        return unit * 2 - 1
    }
}
