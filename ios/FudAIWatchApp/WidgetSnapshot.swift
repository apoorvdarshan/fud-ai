import Foundation

/// One user-selected Home nutrient (protein/carbs/fat/fiber by default, editable
/// in the iPhone app). Mirror of the phone's WidgetNutrientValue — keep in sync.
struct WidgetNutrientValue: Codable, Equatable, Identifiable {
    let id: String
    let label: String
    let shortLabel: String
    let unit: String
    let iconName: String
    let value: Double
    let goal: Double

    var progress: Double {
        guard goal > 0 else { return 0 }
        return min(1.0, value / goal)
    }

    var displayValue: String { Self.format(value) }
    var displayGoal: String { Self.format(goal) }

    func zeroedForToday() -> WidgetNutrientValue {
        WidgetNutrientValue(
            id: id, label: label, shortLabel: shortLabel, unit: unit,
            iconName: iconName, value: 0, goal: goal
        )
    }

    private static func format(_ value: Double) -> String {
        if abs(value.rounded() - value) < 0.0001 {
            return "\(Int(value.rounded()))"
        }
        return String(format: "%.1f", value)
    }
}

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
    /// The user's 4 selected Home nutrients. Optional — snapshots from older
    /// iPhone builds lack it; fall back to the legacy protein/carbs/fat fields.
    var homeNutrients: [WidgetNutrientValue]?
    /// Optional for backward compatibility with snapshots written before
    /// water tracking was available on Apple Watch.
    var waterTrackingEnabled: Bool? = nil
    var waterCurrentMl: Int? = nil
    var waterGoalMl: Int? = nil
    var waterUnitRaw: String? = nil
    /// User's theme gradient as raw hex (e.g. 0xFF375F). Fud Pink when absent.
    var themeStartHex: UInt?
    var themeEndHex: UInt?

    static var appGroupID: String {
        Bundle.main.object(forInfoDictionaryKey: "AppGroupIdentifier") as? String
            ?? "group.com.apoorvdarshan.calorietracker"
    }

    static let watchPayloadKey = "widget_snapshot_data_v1"
    private static let key = "widget_snapshot_v1"

    static var sharedDefaults: UserDefaults? {
        UserDefaults(suiteName: appGroupID)
    }

    static func read() -> WidgetSnapshot? {
        guard let data = sharedDefaults?.data(forKey: key),
              let snapshot = try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
        else { return nil }

        return snapshot.normalizedForToday()
    }

    func normalizedForToday(_ now: Date = Date()) -> WidgetSnapshot {
        let today = Calendar.current.startOfDay(for: now)
        guard Calendar.current.isDate(dayStart, inSameDayAs: today) else {
            return zeroedForToday(now)
        }
        return self
    }

    private func zeroedForToday(_ now: Date) -> WidgetSnapshot {
        let today = Calendar.current.startOfDay(for: now)
        // New day: reset progress to zero but keep the user's goals, nutrient
        // selection, and theme so the Watch doesn't fall back to defaults.
        return WidgetSnapshot(
            date: now,
            dayStart: today,
            calories: 0, calorieGoal: calorieGoal,
            protein: 0, proteinGoal: proteinGoal,
            carbs: 0, carbsGoal: carbsGoal,
            fat: 0, fatGoal: fatGoal,
            homeNutrients: homeNutrients?.map { $0.zeroedForToday() },
            waterTrackingEnabled: waterTrackingEnabled,
            waterCurrentMl: 0,
            waterGoalMl: waterGoalMl,
            waterUnitRaw: waterUnitRaw,
            themeStartHex: themeStartHex,
            themeEndHex: themeEndHex
        )
    }

    static func write(_ snapshot: WidgetSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        sharedDefaults?.set(data, forKey: key)
    }

    static func decodePayload(_ data: Data) -> WidgetSnapshot? {
        try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    static var placeholder: WidgetSnapshot {
        let now = Date()
        return WidgetSnapshot(
            date: now,
            dayStart: Calendar.current.startOfDay(for: now),
            calories: 1247, calorieGoal: 2000,
            protein: 84, proteinGoal: 150,
            carbs: 132, carbsGoal: 220,
            fat: 42, fatGoal: 70,
            // Match iPhone Home: up to 3 selectable nutrients; water is injected
            // as the locked 4th pillar when tracking is enabled.
            homeNutrients: [
                WidgetNutrientValue(id: "protein", label: "Protein", shortLabel: "P", unit: "g", iconName: "fork.knife", value: 84, goal: 150),
                WidgetNutrientValue(id: "carbs", label: "Carbs", shortLabel: "C", unit: "g", iconName: "leaf", value: 132, goal: 220),
                WidgetNutrientValue(id: "fat", label: "Fat", shortLabel: "F", unit: "g", iconName: "drop.fill", value: 42, goal: 70),
            ],
            waterTrackingEnabled: true,
            waterCurrentMl: 1_250,
            waterGoalMl: 2_000,
            waterUnitRaw: "ml"
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
            fat: 0, fatGoal: 70,
            homeNutrients: [
                WidgetNutrientValue(id: "protein", label: "Protein", shortLabel: "P", unit: "g", iconName: "fork.knife", value: 0, goal: 150),
                WidgetNutrientValue(id: "carbs", label: "Carbs", shortLabel: "C", unit: "g", iconName: "leaf", value: 0, goal: 220),
                WidgetNutrientValue(id: "fat", label: "Fat", shortLabel: "F", unit: "g", iconName: "drop.fill", value: 0, goal: 70),
                WidgetNutrientValue(id: "fiber", label: "Fiber", shortLabel: "Fi", unit: "g", iconName: "leaf.fill", value: 0, goal: 34),
            ]
        )
    }

    /// The nutrient pillars to render, matching iPhone Home: up to 4 selected
    /// nutrients, or 3 + a locked Water pillar when Water Tracking is enabled.
    var displayedHomeNutrients: [WidgetNutrientValue] {
        let selected = homeNutrients?.filter { !$0.id.isEmpty && $0.id != "water" } ?? []
        var merged: [WidgetNutrientValue] = []
        for nutrient in selected.isEmpty ? defaultHomeNutrients : selected {
            guard !merged.contains(where: { $0.id == nutrient.id }) else { continue }
            merged.append(nutrient)
            if merged.count == (waterIsEnabled ? 3 : 4) { break }
        }
        if waterIsEnabled { merged.append(waterHomeNutrient) }
        return merged
    }

    private var defaultHomeNutrients: [WidgetNutrientValue] {
        [
            WidgetNutrientValue(id: "protein", label: "Protein", shortLabel: "P", unit: "g", iconName: "fork.knife", value: protein, goal: Double(proteinGoal)),
            WidgetNutrientValue(id: "carbs", label: "Carbs", shortLabel: "C", unit: "g", iconName: "leaf", value: carbs, goal: Double(carbsGoal)),
            WidgetNutrientValue(id: "fat", label: "Fat", shortLabel: "F", unit: "g", iconName: "drop.fill", value: fat, goal: Double(fatGoal)),
            WidgetNutrientValue(id: "fiber", label: "Fiber", shortLabel: "Fi", unit: "g", iconName: "leaf.fill", value: 0, goal: 34),
        ]
    }

    private var waterHomeNutrient: WidgetNutrientValue {
        let usesFluidOunces = waterUnitRaw == "floz"
        let divisor = usesFluidOunces ? 29.5735295625 : 1
        return WidgetNutrientValue(
            id: "water",
            label: "Water",
            shortLabel: "W",
            unit: usesFluidOunces ? " fl oz" : "ml",
            iconName: "drop.fill",
            value: Double(waterCurrent) / divisor,
            goal: Double(waterGoal) / divisor
        )
    }

    var caloriesRemaining: Int { max(0, calorieGoal - calories) }
    var proteinRemaining: Double { max(0, Double(proteinGoal) - protein) }
    var carbsRemaining: Double { max(0, Double(carbsGoal) - carbs) }
    var fatRemaining: Double { max(0, Double(fatGoal) - fat) }
    var waterIsEnabled: Bool { waterTrackingEnabled ?? false }
    var waterCurrent: Int { max(0, waterCurrentMl ?? 0) }
    var waterGoal: Int { max(1, waterGoalMl ?? 2_000) }
    var waterProgress: Double { min(1, Double(waterCurrent) / Double(waterGoal)) }

    var waterUnitSymbol: String {
        waterUnitRaw == "floz" ? "fl oz" : "mL"
    }

    func waterDisplayValue(_ milliliters: Int) -> String {
        guard waterUnitRaw == "floz" else { return "\(milliliters)" }
        let ounces = Double(milliliters) / 29.5735295625
        if abs(ounces.rounded() - ounces) < 0.05 {
            return "\(Int(ounces.rounded()))"
        }
        return String(format: "%.1f", ounces)
    }

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
