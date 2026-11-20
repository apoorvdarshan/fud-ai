import Testing
@testable import calorietracker

@MainActor
struct BodyFatWheelTests {
    @Test func importRejectsMissingAndInvalidReadings() {
        for fraction: Double? in [nil, 0, -0.01, .nan, .infinity] {
            #expect(BodyFatWheel.importedSelection(for: fraction) == nil)
        }
        #expect(BodyFatWheel.importedSelection(for: 0.193) == 193)
        #expect(BodyFatWheel.importedSelection(for: 0.02) == 30)
    }

    @Test func tenthsRoundTripWithoutDroppingPrecision() {
        for value in BodyFatWheel.values {
            #expect(BodyFatWheel.selection(for: BodyFatWheel.fraction(for: value)) == value)
        }
        #expect(BodyFatWheel.selection(for: 0.193) == 193)
        #expect(BodyFatWheel.selection(for: 0.02) == 30)
        #expect(BodyFatWheel.selection(for: 0.65) == 600)
    }
}
