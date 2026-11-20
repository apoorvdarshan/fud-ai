import Foundation
import Testing
@testable import calorietracker

/// The optional calorie range is edited in the user's display unit (#722).
@MainActor
struct CalorieRangeSettingsTests {
    @Test func kilojouleInputIsStoredAsKilocalories() {
        #expect(CalorieRangeSettings.storedKcal(displayed: 8_368, unit: .kj) == 2_000)
        #expect(CalorieRangeSettings.storedKcal(displayed: 418, unit: .kj) == 100)
        #expect(CalorieRangeSettings.storedKcal(displayed: 0, unit: .kj) == 0)
    }

    @Test func kilocalorieInputIsStoredUnchanged() {
        #expect(CalorieRangeSettings.storedKcal(displayed: 2_000, unit: .kcal) == 2_000)
        #expect(CalorieRangeSettings.storedKcal(displayed: 0, unit: .kcal) == 0)
    }

    @Test func savingAnUntouchedKilojouleBoundKeepsTheStoredValue() {
        for kcal in 0...10_000 {
            let displayed = EnergyUnit.kj.displayValue(kcal: kcal)
            #expect(CalorieRangeSettings.storedKcal(displayed: displayed, unit: .kj) == kcal)
        }
    }
}
