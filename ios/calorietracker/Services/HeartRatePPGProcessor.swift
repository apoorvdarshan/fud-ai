import Foundation

/// One camera-frame observation for contact photoplethysmography (PPG).
///
/// The capture layer should calculate channel means from a small, central pixel grid and
/// normalize each channel to `0...1`. Pixel buffers and frames never enter this type.
nonisolated struct HeartRatePPGSample: Equatable, Sendable {
    let timestamp: TimeInterval
    let red: Double
    let green: Double
    let blue: Double
    /// Fraction of sampled pixels whose red channel is clipped near its maximum.
    let redClippedFraction: Double
    /// Spatial red-channel standard deviation across the sampled region.
    let redSpatialStandardDeviation: Double

    init(
        timestamp: TimeInterval,
        red: Double,
        green: Double,
        blue: Double,
        redClippedFraction: Double = 0,
        redSpatialStandardDeviation: Double = 0.03
    ) {
        self.timestamp = timestamp
        self.red = red
        self.green = green
        self.blue = blue
        self.redClippedFraction = redClippedFraction
        self.redSpatialStandardDeviation = redSpatialStandardDeviation
    }
}

nonisolated struct HeartRatePPGResult: Equatable, Sendable {
    let bpm: Int
    /// A bounded signal-quality score where `1` is the strongest result.
    let quality: Double
    let duration: TimeInterval
    let sampleCount: Int
}

nonisolated enum HeartRatePPGRejection: Equatable, Sendable {
    case irregularSampling
    case flatSignal
    case noisySignal
    case implausibleRate
}

nonisolated enum HeartRatePPGStatus: Equatable, Sendable {
    case waitingForContact
    case collecting
    case ready
    case rejected(HeartRatePPGRejection)
}

nonisolated struct HeartRatePPGUpdate: Equatable, Sendable {
    let contactDetected: Bool
    /// Progress toward the minimum measurement duration, bounded to `0...1`.
    let progress: Double
    let result: HeartRatePPGResult?
    let status: HeartRatePPGStatus
}

/// Pure, bounded-memory heart-rate estimator for camera PPG.
///
/// Feed it normalized mean RGB values in timestamp order. It validates fingertip contact from
/// red-channel brightness and dominance, then estimates pulse from a continuously bounded
/// 20–30 second red-channel window. It stores no images, pixel buffers, or persisted waveform.
nonisolated struct HeartRatePPGProcessor {
    nonisolated struct Configuration: Equatable, Sendable {
        var minimumMeasurementDuration: TimeInterval = 20
        var maximumMeasurementDuration: TimeInterval = 30
        var minimumBPM: Double = 40
        var maximumBPM: Double = 200
        var minimumSamplingRate: Double = 15
        var maximumSamplingRate: Double = 90
        var minimumRedLevel: Double = 0.32
        var maximumRedLevel: Double = 252.0 / 255.0
        var minimumBrightness: Double = 0.16
        var minimumRedDominanceRatio: Double = 1.05
        var minimumRedDominanceDifference: Double = 0.02
        var maximumRedClippedFraction: Double = 0.30
        var maximumRedSpatialStandardDeviation: Double = 70.0 / 255.0
        var analysisInterval: TimeInterval = 0.5
        /// Brief fingertip slip / uneven flash coverage should not wipe the whole window.
        var missedContactToleranceFrames: Int = 12
        /// A hard memory ceiling independent of timestamps or camera frame rate.
        var maximumRetainedSamples: Int = 3_601
    }

    private struct TimedValue: Sendable {
        let timestamp: TimeInterval
        let value: Double
    }

    private enum AnalysisOutcome {
        case result(HeartRatePPGResult)
        case rejection(HeartRatePPGRejection)
    }

    private let configuration: Configuration
    private var samples: RingBuffer<TimedValue>
    private var lastAnalysisTimestamp: TimeInterval?
    private var consecutiveMissedContactFrames = 0
    private(set) var currentUpdate = HeartRatePPGUpdate(
        contactDetected: false,
        progress: 0,
        result: nil,
        status: .waitingForContact
    )

    init(configuration: Configuration = Configuration()) {
        precondition(configuration.minimumMeasurementDuration > 0)
        precondition(configuration.maximumMeasurementDuration >= configuration.minimumMeasurementDuration)
        precondition(configuration.minimumBPM > 0)
        precondition(configuration.maximumBPM > configuration.minimumBPM)
        precondition(configuration.minimumSamplingRate > 0)
        precondition(configuration.maximumSamplingRate > configuration.minimumSamplingRate)
        precondition(configuration.maximumRetainedSamples > 1)
        self.configuration = configuration
        samples = RingBuffer(capacity: configuration.maximumRetainedSamples)
    }

    /// Number of scalar observations currently retained. Exposed internally for diagnostics/tests.
    var retainedSampleCount: Int { samples.count }

    mutating func reset() {
        samples.removeAll()
        lastAnalysisTimestamp = nil
        consecutiveMissedContactFrames = 0
        currentUpdate = HeartRatePPGUpdate(
            contactDetected: false,
            progress: 0,
            result: nil,
            status: .waitingForContact
        )
    }

    /// Adds one frame-derived sample and returns the latest measurement state.
    @discardableResult
    mutating func process(sample: HeartRatePPGSample) -> HeartRatePPGUpdate {
        guard isValid(sample) else {
            return registerMissedContact()
        }
        guard hasFingerContact(sample) else {
            return registerMissedContact()
        }
        consecutiveMissedContactFrames = 0

        if let last = samples.last, sample.timestamp <= last.timestamp {
            samples.removeAll()
            lastAnalysisTimestamp = nil
            currentUpdate = HeartRatePPGUpdate(
                contactDetected: true,
                progress: 0,
                result: nil,
                status: .rejected(.irregularSampling)
            )
            return currentUpdate
        }

        samples.append(TimedValue(timestamp: sample.timestamp, value: sample.red))
        trimSamples(endingAt: sample.timestamp)

        guard let first = samples.first else {
            reset()
            return currentUpdate
        }

        let duration = sample.timestamp - first.timestamp
        let progress = min(max(duration / configuration.minimumMeasurementDuration, 0), 1)
        guard duration >= configuration.minimumMeasurementDuration else {
            currentUpdate = HeartRatePPGUpdate(
                contactDetected: true,
                progress: progress,
                result: nil,
                status: .collecting
            )
            return currentUpdate
        }

        if let lastAnalysisTimestamp,
           sample.timestamp - lastAnalysisTimestamp < configuration.analysisInterval {
            currentUpdate = HeartRatePPGUpdate(
                contactDetected: true,
                progress: progress,
                result: currentUpdate.result,
                status: currentUpdate.status
            )
            return currentUpdate
        }

        lastAnalysisTimestamp = sample.timestamp
        switch analyze(samples.values) {
        case let .result(result):
            currentUpdate = HeartRatePPGUpdate(
                contactDetected: true,
                progress: progress,
                result: result,
                status: .ready
            )
        case let .rejection(reason):
            currentUpdate = HeartRatePPGUpdate(
                contactDetected: true,
                progress: progress,
                result: nil,
                status: .rejected(reason)
            )
        }
        return currentUpdate
    }

    private func isValid(_ sample: HeartRatePPGSample) -> Bool {
        sample.timestamp.isFinite
            && sample.red.isFinite
            && sample.green.isFinite
            && sample.blue.isFinite
            && sample.redClippedFraction.isFinite
            && sample.redSpatialStandardDeviation.isFinite
            && (0...1).contains(sample.red)
            && (0...1).contains(sample.green)
            && (0...1).contains(sample.blue)
            && (0...1).contains(sample.redClippedFraction)
            && (0...1).contains(sample.redSpatialStandardDeviation)
    }

    private mutating func registerMissedContact() -> HeartRatePPGUpdate {
        consecutiveMissedContactFrames += 1
        if samples.count == 0
            || consecutiveMissedContactFrames > configuration.missedContactToleranceFrames {
            reset()
            return currentUpdate
        }
        // Keep the in-progress window through brief coverage gaps so the UI does not
        // flicker between “measuring” and “cover the camera” on every noisy frame.
        return currentUpdate
    }

    private func hasFingerContact(_ sample: HeartRatePPGSample) -> Bool {
        let strongestOtherChannel = max(sample.green, sample.blue)
        let brightness = (sample.red + sample.green + sample.blue) / 3
        let dominanceRatio = sample.red / max(strongestOtherChannel, 0.001)
        return sample.red >= configuration.minimumRedLevel
            && sample.red <= configuration.maximumRedLevel
            && brightness >= configuration.minimumBrightness
            && dominanceRatio >= configuration.minimumRedDominanceRatio
            && sample.red - strongestOtherChannel >= configuration.minimumRedDominanceDifference
            && sample.redClippedFraction <= configuration.maximumRedClippedFraction
            && sample.redSpatialStandardDeviation <= configuration.maximumRedSpatialStandardDeviation
    }

    private mutating func trimSamples(endingAt timestamp: TimeInterval) {
        let cutoff = timestamp - configuration.maximumMeasurementDuration
        while let first = samples.first, first.timestamp < cutoff {
            samples.removeFirst()
        }
    }

    private func analyze(_ input: [TimedValue]) -> AnalysisOutcome {
        guard let first = input.first, let last = input.last, input.count > 2 else {
            return .rejection(.irregularSampling)
        }

        let duration = last.timestamp - first.timestamp
        let samplingRate = Double(input.count - 1) / duration
        guard samplingRate >= configuration.minimumSamplingRate,
              samplingRate <= configuration.maximumSamplingRate else {
            return .rejection(.irregularSampling)
        }

        let intervals = zip(input.dropFirst(), input).map { newer, older in
            newer.timestamp - older.timestamp
        }
        guard let medianInterval = median(intervals), medianInterval > 0 else {
            return .rejection(.irregularSampling)
        }
        let maximumGap = intervals.max() ?? .infinity
        let meanAbsoluteCadenceError = intervals.reduce(0.0) {
            $0 + abs($1 - medianInterval)
        } / Double(intervals.count)
        guard maximumGap <= min(0.25, medianInterval * 3.5),
              meanAbsoluteCadenceError / medianInterval <= 0.30 else {
            return .rejection(.irregularSampling)
        }

        let times = input.map { $0.timestamp - first.timestamp }
        let rawValues = input.map(\.value)
        let meanLevel = rawValues.reduce(0, +) / Double(rawValues.count)
        let values = linearlyDetrended(values: rawValues, times: times)
        let energy = values.reduce(0.0) { $0 + $1 * $1 }
        let standardDeviation = sqrt(energy / Double(values.count))
        let coefficientOfVariation = standardDeviation / max(meanLevel, 0.001)

        guard coefficientOfVariation >= 0.00035 else {
            return .rejection(.flatSignal)
        }
        guard coefficientOfVariation <= 0.12 else {
            return .rejection(.noisySignal)
        }

        let scan = frequencyScan(values: values, times: times)
        guard scan.bpm >= configuration.minimumBPM,
              scan.bpm <= configuration.maximumBPM else {
            return .rejection(.implausibleRate)
        }
        guard scan.explainedPower >= 0.28, scan.peakToMedianRatio >= 6 else {
            return .rejection(.noisySignal)
        }

        let periodCorrelation = autocorrelation(
            values: values,
            lag: Int((samplingRate * 60 / scan.bpm).rounded())
        )
        guard periodCorrelation >= 0.42 else {
            return .rejection(.noisySignal)
        }

        let midpoint = values.count / 2
        guard midpoint >= 2, values.count - midpoint >= 2 else {
            return .rejection(.noisySignal)
        }
        let firstHalf = frequencyScan(
            values: Array(values[..<midpoint]),
            times: Array(times[..<midpoint])
        )
        let secondHalfTimes = Array(times[midpoint...])
        let secondHalfStart = secondHalfTimes.first ?? 0
        let secondHalf = frequencyScan(
            values: Array(values[midpoint...]),
            times: secondHalfTimes.map { $0 - secondHalfStart }
        )
        let halfDifference = abs(firstHalf.bpm - secondHalf.bpm)
        guard firstHalf.explainedPower >= 0.18,
              secondHalf.explainedPower >= 0.18,
              halfDifference <= 7 else {
            return .rejection(.noisySignal)
        }

        let spectralQuality = ((scan.explainedPower - 0.28) / 0.72).clamped(to: 0...1)
        let periodicityQuality = ((periodCorrelation - 0.42) / 0.58).clamped(to: 0...1)
        let stabilityQuality = (1 - halfDifference / 7).clamped(to: 0...1)
        let separationQuality = ((scan.peakToMedianRatio - 6) / 24).clamped(to: 0...1)
        let quality = (
            spectralQuality * 0.40
                + periodicityQuality * 0.30
                + stabilityQuality * 0.20
                + separationQuality * 0.10
        ).clamped(to: 0...1)

        return .result(
            HeartRatePPGResult(
                bpm: Int(scan.bpm.rounded()),
                quality: quality,
                duration: duration,
                sampleCount: input.count
            )
        )
    }

    private func linearlyDetrended(values: [Double], times: [Double]) -> [Double] {
        let count = Double(values.count)
        let meanTime = times.reduce(0, +) / count
        let meanValue = values.reduce(0, +) / count
        var numerator = 0.0
        var denominator = 0.0
        for index in values.indices {
            let centeredTime = times[index] - meanTime
            numerator += centeredTime * (values[index] - meanValue)
            denominator += centeredTime * centeredTime
        }
        let slope = denominator > 0 ? numerator / denominator : 0
        return values.indices.map { index in
            values[index] - meanValue - slope * (times[index] - meanTime)
        }
    }

    private func frequencyScan(values: [Double], times: [Double]) -> (
        bpm: Double,
        explainedPower: Double,
        peakToMedianRatio: Double
    ) {
        let energy = max(values.reduce(0.0) { $0 + $1 * $1 }, .leastNonzeroMagnitude)
        let count = Double(values.count)
        let step = 0.25
        let numberOfSteps = Int(((configuration.maximumBPM - configuration.minimumBPM) / step).rounded(.down))
        var powers: [Double] = []
        powers.reserveCapacity(numberOfSteps + 1)
        var bestBPM = configuration.minimumBPM
        var bestPower = -Double.infinity
        var bestIndex = 0

        for stepIndex in 0...numberOfSteps {
            let bpm = configuration.minimumBPM + Double(stepIndex) * step
            let angularFrequency = 2 * Double.pi * bpm / 60
            var sineProjection = 0.0
            var cosineProjection = 0.0
            for index in values.indices {
                let phase = angularFrequency * times[index]
                sineProjection += values[index] * sin(phase)
                cosineProjection += values[index] * cos(phase)
            }
            let power = 2 * (
                sineProjection * sineProjection + cosineProjection * cosineProjection
            ) / (count * energy)
            powers.append(power)
            if power > bestPower {
                bestPower = power
                bestBPM = bpm
                bestIndex = stepIndex
            }
        }

        let medianPower = median(powers) ?? 0
        var selectedBPM = bestBPM
        var selectedExplainedPower = bestPower
        var selectedPeakToMedianRatio = bestPower / max(medianPower, 1e-12)

        // Camera PPG waveforms are not perfect sinusoids, so their second harmonic
        // can be stronger than the fundamental and otherwise double the reported BPM.
        // Prefer the lower fundamental only when it is itself a distinct spectral
        // peak with meaningful power relative to the dominant harmonic.
        let possibleFundamental = bestBPM / 2
        if possibleFundamental >= configuration.minimumBPM {
            let targetIndex = Int(((possibleFundamental - configuration.minimumBPM) / step).rounded())
            let searchRadius = Int((1.0 / step).rounded())
            let lowerIndex = max(0, targetIndex - searchRadius)
            let upperIndex = min(powers.count - 1, targetIndex + searchRadius)
            if lowerIndex <= upperIndex,
               let fundamentalIndex = (lowerIndex...upperIndex).max(by: { powers[$0] < powers[$1] }) {
                let fundamentalPower = powers[fundamentalIndex]
                let relativePower = fundamentalPower / max(bestPower, 1e-12)
                let fundamentalSeparation = fundamentalPower / max(medianPower, 1e-12)
                if fundamentalIndex != bestIndex,
                   relativePower >= 0.10,
                   fundamentalSeparation >= 6 {
                    selectedBPM = configuration.minimumBPM + Double(fundamentalIndex) * step
                    selectedExplainedPower = min(1, fundamentalPower + bestPower)
                    selectedPeakToMedianRatio = selectedExplainedPower / max(medianPower, 1e-12)
                }
            }
        }

        return (
            selectedBPM,
            selectedExplainedPower.clamped(to: 0...1),
            selectedPeakToMedianRatio
        )
    }

    private func autocorrelation(values: [Double], lag: Int) -> Double {
        guard lag > 0, lag < values.count else { return 0 }
        var numerator = 0.0
        var leadingEnergy = 0.0
        var trailingEnergy = 0.0
        for index in lag..<values.count {
            let leading = values[index]
            let trailing = values[index - lag]
            numerator += leading * trailing
            leadingEnergy += leading * leading
            trailingEnergy += trailing * trailing
        }
        let denominator = sqrt(leadingEnergy * trailingEnergy)
        return denominator > 0 ? numerator / denominator : 0
    }

    private func median(_ values: [Double]) -> Double? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        let middle = sorted.count / 2
        if sorted.count.isMultiple(of: 2) {
            return (sorted[middle - 1] + sorted[middle]) / 2
        }
        return sorted[middle]
    }
}

nonisolated private struct RingBuffer<Element> {
    private var storage: [Element?]
    private var head = 0
    private(set) var count = 0

    init(capacity: Int) {
        storage = Array(repeating: nil, count: capacity)
    }

    var first: Element? {
        guard count > 0 else { return nil }
        return storage[head]
    }

    var last: Element? {
        guard count > 0 else { return nil }
        return storage[(head + count - 1) % storage.count]
    }

    var values: [Element] {
        (0..<count).compactMap { offset in
            storage[(head + offset) % storage.count]
        }
    }

    mutating func append(_ element: Element) {
        if count < storage.count {
            storage[(head + count) % storage.count] = element
            count += 1
        } else {
            storage[head] = element
            head = (head + 1) % storage.count
        }
    }

    mutating func removeFirst() {
        guard count > 0 else { return }
        storage[head] = nil
        head = (head + 1) % storage.count
        count -= 1
        if count == 0 { head = 0 }
    }

    mutating func removeAll() {
        storage = Array(repeating: nil, count: storage.count)
        head = 0
        count = 0
    }
}

private extension Comparable {
    nonisolated func clamped(to limits: ClosedRange<Self>) -> Self {
        min(max(self, limits.lowerBound), limits.upperBound)
    }
}
