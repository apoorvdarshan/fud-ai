import Foundation

enum CalorieBalanceDirection: Equatable {
    case deficit
    case surplus
    case balanced
}

struct DailyCalorieBalance: Equatable {
    let eatenCalories: Int
    let burnedCalories: Int
    let differenceCalories: Int
    let direction: CalorieBalanceDirection
}

/// Pure home burn / nightly-summary math, separated from HealthKit for unit tests.
enum DailySummaryPolicy {
    static func resolveBurnedCalories(
        measuredTotalCalories: Int?,
        externalActiveCalories: Int,
        profileBmrCalories: Int?
    ) -> Int? {
        if let total = measuredTotalCalories, total > 0 { return total }
        guard let bmr = profileBmrCalories, bmr > 0 else { return nil }
        guard externalActiveCalories > 0 else { return nil }
        return bmr + externalActiveCalories
    }

    static func balance(eatenCalories: Int, burnedCalories: Int) -> DailyCalorieBalance {
        let eaten = max(eatenCalories, 0)
        let burned = max(burnedCalories, 0)
        let signedDifference = burned - eaten
        let direction: CalorieBalanceDirection
        if signedDifference > 0 {
            direction = .deficit
        } else if signedDifference < 0 {
            direction = .surplus
        } else {
            direction = .balanced
        }
        return DailyCalorieBalance(
            eatenCalories: eaten,
            burnedCalories: burned,
            differenceCalories: abs(signedDifference),
            direction: direction
        )
    }
}
