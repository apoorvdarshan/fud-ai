import Foundation

/// Local, offline estimate of how long an AI analysis usually takes.
///
/// The estimate is built only from durations this device measured itself, so it needs
/// no network call, no analytics and no extra provider round trip. With too few
/// samples the estimate stays empty and the UI shows the elapsed time only, rather
/// than a fabricated countdown.
enum AnalysisDurationEstimate {

    /// Below this many samples the estimate is not shown at all.
    static let minSamples = 3

    /// How many recent samples are kept per provider/model/request-kind combination.
    static let maxSamples = 8

    /// Storage key for the durations of one provider/model/request-kind combination.
    static func key(provider: AIProvider, model: String, kind: AnalysisKind) -> String {
        "\(provider.rawValue)|\(model)|\(kind.rawValue)"
    }

    /// The usual duration band in seconds, or nil when there is not enough history.
    ///
    /// The band spans the 25th to the 75th percentile of the samples, so a single
    /// unusually slow request does not widen it.
    static func estimateSeconds(samples: [Int]) -> ClosedRange<Int>? {
        guard samples.count >= minSamples else { return nil }
        let sorted = samples.filter { $0 > 0 }.sorted()
        guard sorted.count >= minSamples else { return nil }
        let low = percentile(sorted, 0.25)
        let high = percentile(sorted, 0.75)
        let lowSeconds = max(1, Int((Double(low) / 1000).rounded(.up)))
        let highSeconds = max(lowSeconds, Int((Double(high) / 1000).rounded(.up)))
        return lowSeconds...highSeconds
    }

    private static func percentile(_ sorted: [Int], _ fraction: Double) -> Int {
        guard !sorted.isEmpty else { return 0 }
        guard sorted.count > 1 else { return sorted[0] }
        // Interpolate between the two neighbouring samples instead of rounding down.
        // With three samples a floor would turn the upper quartile into the median,
        // so the band would not cover the slower half of the local history at all.
        let position = Double(sorted.count - 1) * fraction
        let lower = Int(position.rounded(.down))
        let upper = Int(position.rounded(.up))
        if lower == upper { return sorted[lower] }
        let weight = position - Double(lower)
        let value = Double(sorted[lower]) * (1 - weight) + Double(sorted[upper]) * weight
        return Int(value.rounded())
    }
}

/// Stores the local duration history that powers the optional estimate.
///
/// The history lives as JSON in `UserDefaults`, so it is easy to inspect and to clear
/// with the other app data. The store is created with the defaults it should use, so
/// tests can work on their own suite instead of the shared one.
struct AnalysisDurationStore {

    /// The preference key the history is stored under.
    static let storageKey = "aiAnalysisDurations"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The usual duration of a provider/model combination, or nil when unknown.
    func estimate(
        provider: AIProvider,
        model: String,
        kind: AnalysisKind
    ) -> ClosedRange<Int>? {
        AnalysisDurationEstimate.estimateSeconds(
            samples: samples(provider: provider, model: model, kind: kind)
        )
    }

    /// The raw samples of a provider/model combination, oldest first.
    func samples(provider: AIProvider, model: String, kind: AnalysisKind) -> [Int] {
        history()[AnalysisDurationEstimate.key(provider: provider, model: model, kind: kind)] ?? []
    }

    /// Records how long one analysis took so later runs can show an honest estimate.
    func record(
        provider: AIProvider,
        model: String,
        kind: AnalysisKind,
        durationMillis: Int
    ) {
        guard durationMillis > 0 else { return }
        let key = AnalysisDurationEstimate.key(provider: provider, model: model, kind: kind)
        var history = self.history()
        var samples = (history[key] ?? []) + [durationMillis]
        samples = Array(samples.suffix(AnalysisDurationEstimate.maxSamples))
        history[key] = samples
        store(history)
    }

    /// The stored history. Anything unreadable is treated as "no history yet".
    func history() -> [String: [Int]] {
        if let data = defaults.data(forKey: Self.storageKey),
           let decoded = try? JSONDecoder().decode([String: [Int]].self, from: data) {
            return decoded
        }
        // A hand-written or older-format value is read once; the next write replaces
        // it with JSON.
        if let legacy = defaults.dictionary(forKey: Self.storageKey) {
            var migrated: [String: [Int]] = [:]
            for (key, value) in legacy {
                if let samples = value as? [Int] {
                    migrated[key] = samples
                } else if let numbers = value as? [NSNumber] {
                    migrated[key] = numbers.map(\.intValue)
                }
            }
            if !migrated.isEmpty {
                store(migrated)
            }
            return migrated
        }
        return [:]
    }

    private func store(_ history: [String: [Int]]) {
        guard let data = try? JSONEncoder().encode(history) else { return }
        defaults.set(data, forKey: Self.storageKey)
    }
}
