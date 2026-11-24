import Foundation
import UIKit

#if DEBUG && targetEnvironment(simulator)
/// Demo data and a starting screen for the README and website screenshots, captured
/// headless by scripts/screenshots/capture-ios.sh. Debug simulator builds only, and only
/// alongside --ui-testing, so it can never touch a device, a Release build or real data.
enum ScreenshotMode {
    enum Scene: String {
        case home
        case voice
        case food
        case nutrition
        case homeNutrients
        case hostedQuota
        case progress
        case weeklyChallenge
        case coach
        case workouts
        case exerciseLibrary
        case settings
        case aiProviders
        case voiceSettings
    }

    /// Also requires --ui-testing-reset, so the demo data only ever lands in a freshly
    /// wiped Debug data domain and can't replace a developer's own simulator data.
    static var isEnabled: Bool {
        let arguments = CommandLine.arguments
        return SimulatorTestEnvironment.isRunningTests
            && arguments.contains("--screenshot-demo")
            && arguments.contains("--ui-testing-reset")
    }

    /// From the launch argument `-screenshotScene <name>`.
    static var scene: Scene? {
        guard isEnabled else { return nil }
        return UserDefaults.standard.string(forKey: "screenshotScene").flatMap(Scene.init(rawValue:))
    }

    /// The capture script copies the demo photos into Documents/ScreenshotDemo.
    private static func photo(_ name: String) -> Data? {
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appending(path: "ScreenshotDemo", directoryHint: .isDirectory)
        return try? Data(contentsOf: folder.appending(path: "\(name).jpg"))
    }

    static let yogurtID = UUID(uuidString: "5C0FFEE0-0000-4000-8000-000000000001")!

    // MARK: - Demo data

    static func seed(
        food: FoodStore,
        weight: WeightStore,
        bodyFat: BodyFatStore,
        water: WaterStore,
        fasting: FastingStore,
        chat: ChatStore,
        strength: StrengthWorkoutStore,
        profile: ProfileStore
    ) {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)
        func at(_ day: Int, _ hour: Int, _ minute: Int) -> Date {
            calendar.date(byAdding: .day, value: -day, to: today)!
                .addingTimeInterval(TimeInterval(hour * 3600 + minute * 60))
        }

        var person = UserProfile.load() ?? .default
        person.name = "Alex"
        person.gender = .male
        person.birthday = calendar.date(from: DateComponents(year: 1996, month: 3, day: 14))!
        person.heightCm = 178
        person.weightKg = 78.4
        person.activityLevel = .moderate
        person.goal = .lose
        person.goalWeightKg = 72
        person.weeklyChangeKg = 0.5
        person.bodyFatPercentage = 0.221
        person.save()
        profile.reloadFromDisk()

        let defaults = UserDefaults.standard
        defaults.set(true, forKey: WaterSettings.enabledKey)
        defaults.set(2500, forKey: WaterSettings.dailyGoalKey)
        defaults.set(WeightUnit.kg.rawValue, forKey: WeightUnit.storageKey)
        defaults.set(true, forKey: FastingSettings.enabledKey)

        // Today: two photo meals, two quick text logs.
        var entries: [FoodEntry] = [
            FoodEntry(
                id: yogurtID,
                name: "Greek Yogurt with Blueberries, Granola, and Honey",
                calories: 375, protein: 17, carbs: 52, fat: 11,
                timestamp: at(0, 8, 12), imageData: photo("yogurt"), source: .snapFood, mealType: .breakfast,
                sugar: 31, fiber: 4, saturatedFat: 4.8, cholesterol: 15, sodium: 95, potassium: 420,
                calcium: 260, iron: 1.4, magnesium: 52, vitaminC: 9, vitaminD: 0.1,
                servingSizeGrams: 330
            ),
            FoodEntry(
                name: "Café Latte", calories: 134, protein: 7, carbs: 11, fat: 7,
                timestamp: at(0, 8, 20), emoji: "☕️", source: .textInput, mealType: .breakfast,
                sugar: 11, saturatedFat: 4.3, cholesterol: 24, caffeine: 128, sodium: 105, calcium: 270,
                servingSizeGrams: 245
            ),
            FoodEntry(
                name: "Roasted Turkey Breast with Asparagus and Butter",
                calories: 430, protein: 48, carbs: 6, fat: 23,
                timestamp: at(0, 13, 5), imageData: photo("turkey"), source: .snapFood, mealType: .lunch,
                sugar: 2, fiber: 3, saturatedFat: 11, cholesterol: 145, sodium: 410, potassium: 690,
                iron: 3.1, zinc: 3.6, vitaminA: 120, vitaminK: 48, folate: 75,
                servingSizeGrams: 330
            ),
            FoodEntry(
                name: "Apple and Cottage Cheese", calories: 210, protein: 14, carbs: 28, fat: 5,
                timestamp: at(0, 16, 30), emoji: "🍎", source: .textInput, mealType: .snack,
                sugar: 22, fiber: 4, saturatedFat: 3, sodium: 360, potassium: 300, calcium: 95, vitaminC: 8,
                servingSizeGrams: 282
            ),
        ]
        // Ten weeks of earlier days, close to the goal, for the history charts and the recap.
        typealias Meal = (name: String, emoji: String, kcal: Int, protein: Double, carbs: Double, fat: Double, hour: Int, minute: Int)
        let breakfasts: [Meal] = [
            ("Overnight Oats with Banana", "🥣", 410, 26, 62, 9, 8, 5),
            ("Scrambled Eggs on Toast", "🍳", 430, 28, 30, 22, 8, 30),
        ]
        let lunches: [Meal] = [
            ("Chicken Burrito Bowl", "🌯", 640, 46, 68, 20, 13, 10),
            ("Turkey Sandwich", "🥪", 520, 36, 50, 18, 12, 45),
        ]
        let dinners: [Meal] = [
            ("Salmon with Rice and Broccoli", "🍣", 610, 42, 58, 22, 19, 40),
            ("Chicken Stir-Fry with Vegetables", "🥘", 560, 44, 48, 20, 20, 0),
            ("Spaghetti Bolognese", "🍝", 680, 36, 82, 22, 19, 15),
        ]
        let shake: Meal = ("Protein Shake", "🥤", 180, 30, 6, 3, 16, 15)
        for day in 1...70 {
            var meals = [breakfasts[day % 2], lunches[(day / 2) % 2], dinners[day % 3]]
            if day % 4 != 0 { meals.append(shake) }
            for (index, meal) in meals.enumerated() {
                let wobble = Double((day * 37 + index * 11) % 13 - 6) / 100
                entries.append(FoodEntry(
                    name: meal.name,
                    calories: Int((Double(meal.kcal) * (1 + wobble)).rounded()),
                    protein: meal.protein, carbs: meal.carbs, fat: meal.fat,
                    timestamp: at(day, meal.hour, meal.minute), emoji: meal.emoji, source: .textInput,
                    mealType: meal.emoji == "🥤" ? .snack : index == 0 ? .breakfast : index == 1 ? .lunch : .dinner
                ))
            }
            if day == 3 {
                entries.append(FoodEntry(
                    name: "Cheeseburger, French Fries, and Coca-Cola",
                    calories: 1120, protein: 42, carbs: 132, fat: 46,
                    timestamp: at(day, 19, 30), imageData: photo("burger"), source: .snapFood, mealType: .dinner
                ))
            }
        }
        food.replaceAllEntries(entries)

        // Four months of weigh-ins trending toward the goal (about 0.35 kg a week), with
        // day-to-day noise that settles near today so this week reads as a loss.
        var weights: [WeightEntry] = []
        for day in stride(from: 120, through: 0, by: -1) where day % 2 == 0 || day < 14 {
            let trend = 78.4 + Double(day) * 0.05
            let noise = (sin(Double(day) * 1.7) * 0.3 + sin(Double(day) * 0.45) * 0.2) * min(1, Double(day) / 21)
            weights.append(WeightEntry(date: at(day, 7, 30), weightKg: ((trend + noise) * 10).rounded() / 10))
        }
        weight.replaceAllEntries(weights)

        bodyFat.replaceAllEntries(stride(from: 119, through: 0, by: -7).map { day in
            BodyFatEntry(date: at(day, 7, 35), bodyFatFraction: 0.221 + Double(day) * 0.00023)
        })

        // Last night's 12-hour fast, finished just before breakfast.
        fasting.clear()
        if fasting.start(goalMinutes: 12 * 60, at: at(1, 20, 0)) != nil {
            fasting.endActive(at: at(0, 8, 5))
        }

        water.replaceEntriesFromImport([
            WaterEntry(date: at(0, 8, 0), milliliters: 500),
            WaterEntry(date: at(0, 11, 30), milliliters: 500),
            WaterEntry(date: at(0, 14, 0), milliliters: 250),
            WaterEntry(date: at(0, 16, 45), milliliters: 500),
        ])

        // Today's workout: three lifts with their sets.
        let library = FreeExerciseDBLoader.load()
        let lifts: [(String, [(String, String)])] = [
            ("Barbell Bench Press - Medium Grip", [("60", "10"), ("70", "8"), ("70", "8"), ("75", "6")]),
            ("Barbell Full Squat", [("80", "8"), ("90", "6"), ("90", "6")]),
            ("Bent Over Barbell Row", [("60", "10"), ("65", "8"), ("65", "8")]),
        ]
        for (name, sets) in lifts {
            guard let item = library.first(where: { $0.name == name }) else { continue }
            var exercise = StrengthPlannedExercise(item: item)
            exercise.sets = sets.map { StrengthPlannedSet(weight: $0.0, weightUnit: WeightUnit.kg.rawValue, reps: $0.1) }
            strength.addPlannedExercise(exercise, on: today)
        }

        chat.reset()
        chat.append(ChatMessage(role: .user, content: "How am I doing this week?", timestamp: at(0, 17, 2)))
        chat.append(ChatMessage(role: .assistant, content: """
        Nice week, Alex. You averaged 1,926 kcal a day, right on your 1,924 target, and logged every day.

        Your weight trend is down about 0.3 kg this week, on pace for 72 kg in about 18 weeks. Dinners are where most of your carbs land, so a palm-sized portion of chicken or fish there would raise protein without adding calories.
        """, timestamp: at(0, 17, 2)))
        chat.append(ChatMessage(role: .user, content: "What should I eat for dinner to hit my protein?", timestamp: at(0, 17, 4)))
        chat.append(ChatMessage(role: .assistant, content: """
        You have 775 kcal and 55 g of protein left today. Options that fit:

        • 180 g grilled salmon with rice and greens: about 560 kcal, 44 g protein
        • Chicken stir-fry with vegetables: about 520 kcal, 46 g protein
        • Add a Greek yogurt for dessert for 17 g more
        """, timestamp: at(0, 17, 4)))
    }

    // MARK: - Screens

    static func show(
        _ scene: Scene, navigator: VoiceAssistantNavigator, voiceAssistant: VoiceAssistantController,
        showSplitDestination: (Scene) -> Void
    ) {
        switch scene {
        case .home, .nutrition, .homeNutrients, .hostedQuota, .food:
            navigator.show(.home)
        case .voice:
            navigator.show(.home)
            voiceAssistant.showScreenshotConversation()
        case .progress:
            navigator.show(.progress)
        case .weeklyChallenge, .exerciseLibrary:
            showSplitDestination(scene)
        case .coach:
            navigator.show(.coach)
        case .workouts:
            navigator.show(.workouts)
        case .settings:
            navigator.show(.settings)
        case .aiProviders:
            navigator.show(.settings, settingsSection: .aiProviders)
        case .voiceSettings:
            navigator.show(.settings, settingsSection: .voiceAssistant)
        }
    }
}
#endif
