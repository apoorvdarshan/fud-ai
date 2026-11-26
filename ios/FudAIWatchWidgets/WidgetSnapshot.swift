import Foundation

struct WidgetSnapshot: Codable, Equatable {
    let date: Date
    let dayStart: Date
    let calories: Int
    let calorieGoal: Int
    let protein: Double
    let proteinGoal: Int
    let carbs: Double
    let carbsGoal: Int
    let fat: Double
    let fatGoal: Int
    /// Energy display unit ("kcal" or "kj"); nil in older snapshots (kcal).
    var energyUnitRaw: String? = nil

    static var appGroupID: String {
        Bundle.main.object(forInfoDictionaryKey: "AppGroupIdentifier") as? String
            ?? "group.com.apoorvdarshan.calorietracker"
    }

    private static let key = "widget_snapshot_v1"

    static var sharedDefaults: UserDefaults? {
        UserDefaults(suiteName: appGroupID)
    }

    static func read() -> WidgetSnapshot? {
        guard let data = sharedDefaults?.data(forKey: key),
              let snapshot = try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
        else { return nil }

        let today = Calendar.current.startOfDay(for: Date())
        guard Calendar.current.isDate(snapshot.dayStart, inSameDayAs: today) else {
            // New day: reset progress to zero but keep the user's goals so
            // complications don't fall back to the hard-coded defaults.
            return WidgetSnapshot(
                date: Date(),
                dayStart: today,
                calories: 0, calorieGoal: snapshot.calorieGoal,
                protein: 0, proteinGoal: snapshot.proteinGoal,
                carbs: 0, carbsGoal: snapshot.carbsGoal,
                fat: 0, fatGoal: snapshot.fatGoal,
                energyUnitRaw: snapshot.energyUnitRaw
            )
        }
        return snapshot
    }

    static var placeholder: WidgetSnapshot {
        let now = Date()
        return WidgetSnapshot(
            date: now,
            dayStart: Calendar.current.startOfDay(for: now),
            calories: 1247, calorieGoal: 2000,
            protein: 84, proteinGoal: 150,
            carbs: 132, carbsGoal: 220,
            fat: 42, fatGoal: 70
        )
    }

    static var empty: WidgetSnapshot {
        let now = Date()
        return WidgetSnapshot(
            date: now,
            dayStart: Calendar.current.startOfDay(for: now),
            calories: 0, calorieGoal: 2000,
            protein: 0, proteinGoal: 150,
            carbs: 0, carbsGoal: 220,
            fat: 0, fatGoal: 70
        )
    }

    var caloriesRemaining: Int { max(0, calorieGoal - calories) }
    var usesKilojoules: Bool { energyUnitRaw == "kj" }
    /// Complications show the unit symbol unlocalized, like the macro "g".
    var energyUnitSymbol: String { usesKilojoules ? "kJ" : "kcal" }
    /// Whole display units for a stored kcal amount (1 kcal = 4.184 kJ).
    func energyValue(_ kcal: Int) -> Int {
        usesKilojoules ? Int((Double(kcal) * 4.184).rounded()) : kcal
    }
    var displayCalories: Int { energyValue(calories) }
    var displayCalorieGoal: Int { energyValue(calorieGoal) }
    /// Goal minus eaten in display units, so "eaten / goal · left" always adds up.
    var displayCaloriesRemaining: Int { max(0, displayCalorieGoal - displayCalories) }
    var proteinRemaining: Double { max(0, Double(proteinGoal) - protein) }

    var calorieProgress: Double {
        progress(value: Double(calories), goal: calorieGoal)
    }

    var proteinProgress: Double {
        progress(value: protein, goal: proteinGoal)
    }

    var carbsProgress: Double {
        progress(value: carbs, goal: carbsGoal)
    }

    var fatProgress: Double {
        progress(value: fat, goal: fatGoal)
    }

    private func progress(value: Double, goal: Int) -> Double {
        guard goal > 0 else { return 0 }
        return min(1.0, value / Double(goal))
    }
}
