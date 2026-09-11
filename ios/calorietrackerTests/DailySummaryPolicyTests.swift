import Foundation
import Testing
@testable import calorietracker

struct DailySummaryPolicyTests {
    @Test func resolveBurnedPrefersMeasuredTotal() {
        let burned = DailySummaryPolicy.resolveBurnedCalories(
            measuredTotalCalories: 2_400,
            externalActiveCalories: 500,
            profileBmrCalories: 1_600
        )
        #expect(burned == 2_400)
    }

    @Test func resolveBurnedFallsBackToBmrPlusActive() {
        let burned = DailySummaryPolicy.resolveBurnedCalories(
            measuredTotalCalories: nil,
            externalActiveCalories: 600,
            profileBmrCalories: 1_600
        )
        #expect(burned == 2_200)
    }

    @Test func resolveBurnedReturnsNilWithoutActiveSignal() {
        let burned = DailySummaryPolicy.resolveBurnedCalories(
            measuredTotalCalories: nil,
            externalActiveCalories: 0,
            profileBmrCalories: 1_600
        )
        #expect(burned == nil)
    }

    @Test func balanceComputesDeficitSurplusAndBalanced() {
        let deficit = DailySummaryPolicy.balance(eatenCalories: 2_000, burnedCalories: 2_400)
        #expect(deficit.direction == .deficit)
        #expect(deficit.differenceCalories == 400)

        let surplus = DailySummaryPolicy.balance(eatenCalories: 2_600, burnedCalories: 2_400)
        #expect(surplus.direction == .surplus)
        #expect(surplus.differenceCalories == 200)

        let balanced = DailySummaryPolicy.balance(eatenCalories: 2_400, burnedCalories: 2_400)
        #expect(balanced.direction == .balanced)
        #expect(balanced.differenceCalories == 0)
    }
}
