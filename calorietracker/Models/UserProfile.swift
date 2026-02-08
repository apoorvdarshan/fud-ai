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

    var proteinPerKg: Double {
        switch self {
        case .sedentary: 1.0
        case .light: 1.4
        case .moderate: 1.6
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

struct UserProfile: Codable {
    var gender: Gender
    var birthday: Date
    var heightCm: Double
    var weightKg: Double
    var activityLevel: ActivityLevel
    var goal: WeightGoal
    var bodyFatPercentage: Double?
    var weeklyChangeKg: Double?
    var customCalories: Int?
    var customProtein: Int?
    var customFat: Int?
    var customCarbs: Int?

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
        Int(activityLevel.proteinPerKg * weightKg)
    }

    var fatGoal: Int {
        Int(0.6 * weightKg)
    }

    var carbsGoal: Int {
        max(0, (dailyCalories - proteinGoal * 4 - fatGoal * 9) / 4)
    }

    var effectiveCalories: Int { customCalories ?? dailyCalories }
    var effectiveProtein: Int { customProtein ?? proteinGoal }
    var effectiveFat: Int { customFat ?? fatGoal }
    var effectiveCarbs: Int { customCarbs ?? carbsGoal }

    static let `default` = UserProfile(
        gender: .male,
        birthday: Calendar.current.date(byAdding: .year, value: -25, to: Date()) ?? Date(),
        heightCm: 175,
        weightKg: 70,
        activityLevel: .moderate,
        goal: .maintain,
        bodyFatPercentage: nil,
        weeklyChangeKg: nil,
        customCalories: nil,
        customProtein: nil,
        customFat: nil,
        customCarbs: nil
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
        }
    }
}
