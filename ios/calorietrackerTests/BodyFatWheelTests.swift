import Testing
@testable import calorietracker

@MainActor
struct BodyFatWheelTests {
    @Test func tenthsRoundTripWithoutDroppingPrecision() {
        for value in BodyFatWheel.values {
            #expect(BodyFatWheel.selection(for: BodyFatWheel.fraction(for: value)) == value)
        }
        #expect(BodyFatWheel.selection(for: 0.193) == 193)
        #expect(BodyFatWheel.selection(for: 0.02) == 30)
        #expect(BodyFatWheel.selection(for: 0.65) == 600)
    }
}
