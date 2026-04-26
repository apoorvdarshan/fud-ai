import Foundation

// MARK: - Enums

enum Gender: String, Codable, CaseIterable {
    case male, female, other

    var displayName: String {
        switch self {
        case .male: "Male"
        case .female: "Female"
        case .other: "Other"
        }
    }

    var icon: String {
        switch self {
        case .male: "figure.stand"
        case .female: "figure.stand.dress"
        case .other: "figure.wave"
        }
    }
}

enum ActivityLevel: String, Codable, CaseIterable {
    case sedentary
    case light
    case moderate
    case active
    case veryActive
    case extraActive

    var displayName: String {
        switch self {
        case .sedentary: "Sedentary"
        case .light: "Light"
        case .moderate: "Moderate"
        case .active: "Active"
        case .veryActive: "Very Active"
        case .extraActive: "Extra Active"
        }
    }

    var subtitle: String {
        switch self {
        case .sedentary: "Little or no exercise"
        case .light: "Exercise 1–3 times / week"
        case .moderate: "Exercise 4–5 times / week"
        case .active: "Daily exercise or intense 3–4x / week"
        case .veryActive: "Intense exercise 6–7 times / week"
        case .extraActive: "Very intense daily, or physical job"
        }
    }

    var icon: String {
        switch self {
        case .sedentary: "figure.stand"
        case .light: "figure.walk"
        case .moderate: "figure.run"
        case .active: "figure.highintensity.intervaltraining"
        case .veryActive: "figure.strengthtraining.traditional"
        case .extraActive: "figure.martial.arts"
        }
    }

    var multiplier: Double {
        switch self {
        case .sedentary: 1.2
        case .light: 1.375
        case .moderate: 1.465
        case .active: 1.55
        case .veryActive: 1.725
        case .extraActive: 1.9
        }
    }

    /// g protein per kg bodyweight per activity level (ISSN 2017 / Morton et al 2018 aligned).
    var proteinPerKg: Double {
        switch self {
        case .sedentary: 0.8   // RDA floor
        case .light: 1.2
        case .moderate: 1.6    // Morton et al: point of diminishing returns for hypertrophy
        case .active: 1.8
        case .veryActive: 2.0
        case .extraActive: 2.2
        }
    }
}

enum WeightGoal: String, Codable, CaseIterable {
    case lose, maintain, gain

    var displayName: String {
        switch self {
        case .lose: "Lose Weight"
        case .maintain: "Maintain"
        case .gain: "Gain Weight"
        }
    }

    var icon: String {
        switch self {
        case .lose: "arrow.down.right"
        case .maintain: "equal"
        case .gain: "arrow.up.right"
        }
    }
}

// MARK: - User Profile

struct UserProfile: Codable, Equatable {
    var name: String?
    var gender: Gender
    var birthday: Date
    var heightCm: Double
    var weightKg: Double
    var activityLevel: ActivityLevel
    var goal: WeightGoal
    var bodyFatPercentage: Double?
    /// Target body-fat fraction (0.0–1.0). Display-only — does NOT participate
    /// in BMR / TDEE / macro calculations. Only shown to users who entered a
    /// current body-fat % in onboarding (or set one later via Settings).
    var goalBodyFatPercentage: Double?
    var weeklyChangeKg: Double?
    var goalWeightKg: Double?
    var customCalories: Int?
    var customProtein: Int?
    var customFat: Int?
    var customCarbs: Int?
    var autoBalanceMacro: AutoBalanceMacro?

    var displayName: String {
        if let name, !name.isEmpty { return name }
        return "User"
    }

    var initials: String {
        let parts = displayName.split(separator: " ")
        if parts.count >= 2 {
            return String(parts[0].prefix(1) + parts[1].prefix(1)).uppercased()
        }
        return String(displayName.prefix(1)).uppercased()
    }

    var age: Int {
        Calendar.current.dateComponents([.year], from: birthday, to: Date()).year ?? 25
    }

    var bmr: Double {
        if let bf = bodyFatPercentage {
            // Katch-McArdle
            return 370 + 21.6 * (1 - bf) * weightKg
        }
        // Mifflin-St Jeor
        let base = 10 * weightKg + 6.25 * heightCm - 5 * Double(age) - 161
        switch gender {
        case .male: return base + 166
        case .female, .other: return base
        }
    }

    var tdee: Double {
        bmr * activityLevel.multiplier
    }

    var calorieAdjustment: Int {
        switch goal {
        case .maintain:
            return 0
        case .lose:
            let rate = weeklyChangeKg ?? 0.5
            return -Int(rate * 7000 / 7)
        case .gain:
            let rate = weeklyChangeKg ?? 0.5
            return Int(rate * 7000 / 7)
        }
    }

    var dailyCalories: Int {
        Int(tdee) + calorieAdjustment
    }

    var proteinGoal: Int {
        // +0.2 g/kg during cutting phase to preserve lean mass (Helms et al 2014).
        let cuttingBoost = goal == .lose ? 0.2 : 0.0
        return Int((activityLevel.proteinPerKg + cuttingBoost) * weightKg)
    }

    var fatGoal: Int {
        Int(0.6 * weightKg)
    }

    var carbsGoal: Int {
        max(0, (dailyCalories - proteinGoal * 4 - fatGoal * 9) / 4)
    }

    var effectiveCalories: Int { customCalories ?? dailyCalories }

    /// A macro is "pinned" when its custom value is set; "auto" when nil.
    /// Auto macros split the remaining calories (after subtracting pinned macros) using
    /// their formula values as weights.
    func isPinned(_ macro: AutoBalanceMacro) -> Bool {
        customValue(macro) != nil
    }

    var pinnedCount: Int {
        AutoBalanceMacro.allCases.filter { isPinned($0) }.count
    }

    var effectiveProtein: Int {
        customProtein ?? autoMacroValue(.protein)
    }

    var effectiveCarbs: Int {
        customCarbs ?? autoMacroValue(.carbs)
    }

    var effectiveFat: Int {
        customFat ?? autoMacroValue(.fat)
    }

    private func customValue(_ macro: AutoBalanceMacro) -> Int? {
        switch macro {
        case .protein: return customProtein
        case .carbs:   return customCarbs
        case .fat:     return customFat
        }
    }

    private func formulaValue(_ macro: AutoBalanceMacro) -> Int {
        switch macro {
        case .protein: return proteinGoal
        case .carbs:   return carbsGoal
        case .fat:     return fatGoal
        }
    }

    /// Compute an auto (unpinned) macro's value: split remaining calories among auto macros
    /// using their formula values as weights, then convert kcal -> grams.
    private func autoMacroValue(_ macro: AutoBalanceMacro) -> Int {
        let pinnedKcal = AutoBalanceMacro.allCases.reduce(0) { sum, m in
            sum + (customValue(m).map { $0 * m.kcalPerGram } ?? 0)
        }
        let remaining = max(0, effectiveCalories - pinnedKcal)

        let autoMacros = AutoBalanceMacro.allCases.filter { !isPinned($0) }
        guard autoMacros.contains(macro) else { return 0 }

        // Only one auto macro: it absorbs all the remaining calories.
        if autoMacros.count == 1 {
            return remaining / macro.kcalPerGram
        }

        // Multiple auto macros: split remaining calories proportional to their formula kcal.
        let totalFormulaKcal = autoMacros.reduce(0) { $0 + formulaValue($1) * $1.kcalPerGram }
        guard totalFormulaKcal > 0 else { return formulaValue(macro) }

        let mySharedKcal = remaining * formulaValue(macro) * macro.kcalPerGram / totalFormulaKcal
        return mySharedKcal / macro.kcalPerGram
    }

    /// Recompute calories from weight/activity/goal formulas and reset all three macros to auto.
    /// User can pin individual macros afterwards (max 2).
    mutating func recalculateGoalsFromFormulas() {
        customCalories = dailyCalories
        customProtein = nil
        customFat = nil
        customCarbs = nil
        autoBalanceMacro = nil
    }

    static let `default` = UserProfile(
        name: nil,
        gender: .male,
        birthday: Calendar.current.date(byAdding: .year, value: -25, to: Date()) ?? Date(),
        heightCm: 175,
        weightKg: 70,
        activityLevel: .moderate,
        goal: .maintain,
        bodyFatPercentage: nil,
        goalBodyFatPercentage: nil,
        weeklyChangeKg: nil,
        goalWeightKg: nil,
        customCalories: nil,
        customProtein: nil,
        customFat: nil,
        customCarbs: nil,
        autoBalanceMacro: nil
    )

    // MARK: - Persistence

    static func load() -> UserProfile? {
        guard let data = UserDefaults.standard.data(forKey: "userProfile"),
              let profile = try? JSONDecoder().decode(UserProfile.self, from: data)
        else { return nil }
        return profile
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: "userProfile")
            NotificationCenter.default.post(name: .userProfileDidChange, object: nil)
        }
    }
}

extension Notification.Name {
    static let userProfileDidChange = Notification.Name("userProfileDidChange")
    static let weightGoalReached = Notification.Name("weightGoalReached")
}

enum AutoBalanceMacro: String, Codable, CaseIterable, Identifiable {
    case protein, carbs, fat
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var kcalPerGram: Int { self == .fat ? 9 : 4 }
}
