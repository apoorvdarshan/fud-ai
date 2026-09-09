import Foundation

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
    var displayCurrentWithUnit: String { "\(displayValue)\(unit)" }
    var displayGoalWithUnit: String { "\(displayGoal)\(unit)" }
    var displayPair: String { "\(displayCurrentWithUnit) / \(displayGoalWithUnit)" }
    var displayRemaining: String { "\(Self.format(max(0, goal - value)))\(unit) left" }

    func zeroedForToday() -> WidgetNutrientValue {
        WidgetNutrientValue(
            id: id,
            label: label,
            shortLabel: shortLabel,
            unit: unit,
            iconName: iconName,
            value: 0,
            goal: goal
        )
    }

    private static func format(_ value: Double) -> String {
        if abs(value.rounded() - value) < 0.0001 {
            return "\(Int(value.rounded()))"
        }
        return String(format: "%.1f", value)
    }
}

/// Small Codable snapshot of today's totals + goals that the widget extension
/// reads out of the shared App Group container. The main app writes it on
/// every FoodStore change; the widget re-reads on its timeline refresh.
///
/// The widget target has its own copy of this file (FudAIWidgets/WidgetSnapshot.swift).
/// Keep the two in sync or decoding will fail silently.
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
    let homeNutrients: [WidgetNutrientValue]?
    /// Optional for backward compatibility with snapshots written before the
    /// standalone Water widget existed.
    var waterTrackingEnabled: Bool? = nil
    var waterCurrentMl: Int? = nil
    var waterGoalMl: Int? = nil
    var waterUnitRaw: String? = nil
    /// User's theme gradient as raw hex (e.g. 0xFF375F). Optional so snapshots
    /// written by older builds still decode; consumers fall back to Fud Pink.
    var themeStartHex: UInt?
    var themeEndHex: UInt?

    private static let productionAppGroupID = "group.com.apoorvdarshan.calorietracker"
    private static let debugAppGroupID = "group.com.apoorvdarshan.calorietracker.debug"
    private static let key = "widget_snapshot_v1"
    private static let fileName = "widget_snapshot_v1.json"
    static let watchPayloadKey = "widget_snapshot_data_v1"

    static var appGroupID: String {
        if let configured = Bundle.main.object(forInfoDictionaryKey: "AppGroupIdentifier") as? String,
           !configured.isEmpty,
           !configured.contains("$(") {
            return configured
        }
        return Bundle.main.bundleIdentifier?.contains(".debug") == true
            ? debugAppGroupID
            : productionAppGroupID
    }

    static var sharedDefaults: UserDefaults? {
        UserDefaults(suiteName: appGroupID)
    }

    private static var snapshotDirectoryURL: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupID)?
            .appendingPathComponent("Library/Application Support/FudAIWidgets", isDirectory: true)
    }

    private static var snapshotFileURL: URL? {
        snapshotDirectoryURL?.appendingPathComponent(fileName, isDirectory: false)
    }

    private static func storedData() -> Data? {
        if let fileURL = snapshotFileURL,
           let data = try? Data(contentsOf: fileURL) {
            return data
        }
        return sharedDefaults?.data(forKey: key)
    }

    static func read() -> WidgetSnapshot? {
        guard let data = storedData(),
              let snapshot = try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
        else { return nil }
        return snapshot
    }

    static func write(_ snapshot: WidgetSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        if let directoryURL = snapshotDirectoryURL,
           let fileURL = snapshotFileURL {
            try? FileManager.default.createDirectory(
                at: directoryURL,
                withIntermediateDirectories: true
            )
            try? data.write(
                to: fileURL,
                options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            )
        }
        sharedDefaults?.set(data, forKey: key)
    }

    var payloadData: Data? {
        try? JSONEncoder().encode(self)
    }

    static func decodePayload(_ data: Data) -> WidgetSnapshot? {
        try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    /// Wipes the shared snapshot. Called from Delete All Data so widgets don't keep
    /// showing the previous profile's numbers after a reset.
    static func clear() {
        sharedDefaults?.removeObject(forKey: key)
        if let fileURL = snapshotFileURL {
            try? FileManager.default.removeItem(at: fileURL)
        }
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
            homeNutrients: [
                WidgetNutrientValue(id: "protein", label: "Protein", shortLabel: "P", unit: "g", iconName: "fork.knife", value: 84, goal: 150),
                WidgetNutrientValue(id: "carbs", label: "Carbs", shortLabel: "C", unit: "g", iconName: "leaf", value: 132, goal: 220),
                WidgetNutrientValue(id: "fat", label: "Fat", shortLabel: "F", unit: "g", iconName: "drop.fill", value: 42, goal: 70),
            ],
            waterTrackingEnabled: true,
            waterCurrentMl: 1_250,
            waterGoalMl: 2_000
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
            ],
            waterTrackingEnabled: false,
            waterCurrentMl: 0,
            waterGoalMl: WaterSettings.defaultDailyGoalMl
        )
    }

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

    var primaryHomeNutrient: WidgetNutrientValue {
        displayedHomeNutrients.first ?? defaultHomeNutrients[0]
    }

    var homeNutrientsSummary: String {
        displayedHomeNutrients
            .map { "\($0.summaryLabel)\($0.displayValue)" }
            .joined(separator: " · ")
    }

    func emptyForToday(_ now: Date = Date()) -> WidgetSnapshot {
        WidgetSnapshot(
            date: now,
            dayStart: Calendar.current.startOfDay(for: now),
            calories: 0,
            calorieGoal: calorieGoal,
            protein: 0,
            proteinGoal: proteinGoal,
            carbs: 0,
            carbsGoal: carbsGoal,
            fat: 0,
            fatGoal: fatGoal,
            homeNutrients: displayedHomeNutrients.map { $0.zeroedForToday() },
            waterTrackingEnabled: waterTrackingEnabled,
            waterCurrentMl: 0,
            waterGoalMl: waterGoalMl,
            waterUnitRaw: waterUnitRaw,
            themeStartHex: themeStartHex,
            themeEndHex: themeEndHex
        )
    }

    var caloriesRemaining: Int { max(0, calorieGoal - calories) }
    var proteinRemaining: Double { max(0, Double(proteinGoal) - protein) }
    var carbsRemaining: Double { max(0, Double(carbsGoal) - carbs) }
    var fatRemaining: Double { max(0, Double(fatGoal) - fat) }
    var waterIsEnabled: Bool { waterTrackingEnabled ?? false }
    var waterCurrent: Int { max(0, waterCurrentMl ?? 0) }
    var waterGoal: Int { max(1, waterGoalMl ?? WaterSettings.defaultDailyGoalMl) }
    var waterRemaining: Int { max(0, waterGoal - waterCurrent) }
    var waterProgress: Double { min(1, Double(waterCurrent) / Double(waterGoal)) }
    var waterUsesFluidOunces: Bool { waterUnitRaw == WaterUnit.fluidOunces.rawValue }
    var waterUnitSymbol: String { waterUsesFluidOunces ? "fl oz" : "ml" }
    func waterDisplayValue(_ milliliters: Int) -> String {
        guard waterUsesFluidOunces else { return milliliters.formatted() }
        let ounces = Double(milliliters) / WaterUnit.millilitersPerFluidOunce
        if abs(ounces.rounded() - ounces) < 0.05 { return Int(ounces.rounded()).formatted() }
        return ounces.formatted(.number.precision(.fractionLength(1)))
    }
    var calorieProgress: Double {
        guard calorieGoal > 0 else { return 0 }
        return min(1.0, Double(calories) / Double(calorieGoal))
    }
    var proteinProgress: Double {
        guard proteinGoal > 0 else { return 0 }
        return min(1.0, protein / Double(proteinGoal))
    }
    var carbsProgress: Double {
        guard carbsGoal > 0 else { return 0 }
        return min(1.0, carbs / Double(carbsGoal))
    }
    var fatProgress: Double {
        guard fatGoal > 0 else { return 0 }
        return min(1.0, fat / Double(fatGoal))
    }

    private var defaultHomeNutrients: [WidgetNutrientValue] {
        [
            WidgetNutrientValue(id: "protein", label: "Protein", shortLabel: "P", unit: "g", iconName: "fork.knife", value: protein, goal: Double(proteinGoal)),
            WidgetNutrientValue(id: "carbs", label: "Carbs", shortLabel: "C", unit: "g", iconName: "leaf", value: carbs, goal: Double(carbsGoal)),
            WidgetNutrientValue(id: "fat", label: "Fat", shortLabel: "F", unit: "g", iconName: "drop.fill", value: fat, goal: Double(fatGoal)),
        ]
    }

    private var waterHomeNutrient: WidgetNutrientValue {
        let divisor = waterUsesFluidOunces ? 29.5735295625 : 1
        return WidgetNutrientValue(
            id: "water",
            label: "Water",
            shortLabel: "W",
            unit: waterUsesFluidOunces ? " fl oz" : "ml",
            iconName: "drop.fill",
            value: Double(waterCurrent) / divisor,
            goal: Double(waterGoal) / divisor
        )
    }
}

private extension WidgetNutrientValue {
    var summaryLabel: String {
        String(label.prefix(1)).uppercased()
    }
}
