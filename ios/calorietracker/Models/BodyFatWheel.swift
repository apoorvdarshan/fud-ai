import Foundation

enum BodyFatWheel {
    static let values = 30...600

    static func selection(for fraction: Double) -> Int {
        guard fraction.isFinite else { return 200 }
        return Int((min(max(fraction, 0.03), 0.60) * 1_000).rounded())
    }

    static func importedSelection(for fraction: Double?) -> Int? {
        guard let fraction, fraction.isFinite, fraction > 0 else { return nil }
        return selection(for: fraction)
    }

    static func fraction(for selection: Int) -> Double {
        Double(selection) / 1_000
    }

    static func label(for selection: Int) -> String {
        (Double(selection) / 10).formatted(.number.precision(.fractionLength(1)))
    }
}
