import Foundation
import Testing
@testable import calorietracker

@MainActor
struct WatchWaterLogTests {
    @Test func requestParsesOnlySupportedPresetAmounts() throws {
        let id = UUID()
        let date = Date(timeIntervalSince1970: 1_786_000_000)
        let valid: [String: Any] = [
            WatchWaterLogRequest.actionKey: WatchWaterLogRequest.actionValue,
            WatchWaterLogRequest.requestIDKey: id.uuidString,
            WatchWaterLogRequest.millilitersKey: 500,
            WatchWaterLogRequest.dateKey: date,
        ]

        let request = try #require(WatchWaterLogRequest(payload: valid))
        #expect(request == WatchWaterLogRequest(id: id, milliliters: 500, date: date))

        var unsupported = valid
        unsupported[WatchWaterLogRequest.millilitersKey] = 333
        #expect(WatchWaterLogRequest(payload: unsupported) == nil)

        var unrelated = valid
        unrelated[WatchWaterLogRequest.actionKey] = "unknown"
        #expect(WatchWaterLogRequest(payload: unrelated) == nil)
    }

    @Test func watchRequestIDDeduplicatesPersistedWaterEntries() throws {
        let suiteName = "WatchWaterLogTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let requestID = UUID()
        let date = Date(timeIntervalSince1970: 1_786_000_000)
        let store = WaterStore(defaults: defaults)

        #expect(store.add(id: requestID, milliliters: 250, on: date) != nil)
        #expect(store.add(id: requestID, milliliters: 250, on: date) != nil)
        #expect(store.entries.count == 1)
        #expect(store.total(on: date) == 250)

        let reloaded = WaterStore(defaults: defaults)
        #expect(reloaded.entries.count == 1)
        #expect(reloaded.entries.first?.id == requestID)
    }

    @Test func dailyEntriesAreNewestFirstAndCanBeDeleted() throws {
        let suiteName = "WaterLogTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let calendar = Calendar(identifier: .gregorian)
        let day = Date(timeIntervalSince1970: 1_786_000_000)
        let earlier = day.addingTimeInterval(60)
        let later = day.addingTimeInterval(120)
        let otherDay = calendar.date(byAdding: .day, value: 1, to: day)!
        let store = WaterStore(defaults: defaults)

        let earlierEntry = try #require(store.add(milliliters: 250, on: earlier))
        let laterEntry = try #require(store.add(milliliters: 500, on: later))
        #expect(store.add(milliliters: 750, on: otherDay) != nil)
        #expect(store.entries(on: day).map(\.id) == [laterEntry.id, earlierEntry.id])
        #expect(store.total(on: day) == 750)

        store.delete(id: laterEntry.id)

        #expect(store.entries(on: day).map(\.id) == [earlierEntry.id])
        #expect(store.total(on: day) == 250)
        #expect(WaterStore(defaults: defaults).entries(on: day).map(\.id) == [earlierEntry.id])
    }

    @Test func waterAppearsInsideItsTimedMealWithoutChangingFoodTotals() throws {
        let waterDate = Date(timeIntervalSince1970: 1_786_015_200)
        let meal = MealScheduleSettings.mealType(for: waterDate)
        let foodDate = waterDate.addingTimeInterval(-60)
        let food = FoodEntry(
            name: "Diary test meal",
            calories: 420,
            protein: 30,
            carbs: 45,
            fat: 12,
            timestamp: foodDate,
            source: .manual,
            mealType: meal
        )
        let water = WaterEntry(date: waterDate, milliliters: 250)

        let groups = homeDiaryMealGroups(
            foodEntries: [food],
            waterEntries: [water],
            order: .standard
        )
        let group = try #require(groups.first { $0.meal == meal })

        #expect(group.items.count == 2)
        #expect(group.items.first?.id == "water-\(water.id.uuidString)")
        #expect(group.foodEntries.map(\.id) == [food.id])
        #expect(group.totalCalories == 420)
        #expect(group.totalProtein == 30)
        #expect(group.totalCarbs == 45)
        #expect(group.totalFat == 12)
    }

    @Test func fastingAppearsInsideDiaryWithoutChangingFoodTotals() throws {
        let start = Date(timeIntervalSince1970: 1_786_015_200)
        let end = start.addingTimeInterval(16 * 3600)
        let fast = FastingSession(startedAt: start, endedAt: end, goalMinutes: 16 * 60)
        let meal = MealScheduleSettings.mealType(for: end)
        let food = FoodEntry(
            name: "Diary test meal",
            calories: 420,
            protein: 30,
            carbs: 45,
            fat: 12,
            timestamp: end.addingTimeInterval(-60),
            source: .manual,
            mealType: meal
        )

        let groups = homeDiaryMealGroups(
            foodEntries: [food],
            waterEntries: [],
            fastingSessions: [fast],
            order: .standard
        )
        let group = try #require(groups.first { $0.meal == meal })

        #expect(group.items.first?.id == "fasting-\(fast.id.uuidString)")
        #expect(group.foodEntries.map(\.id) == [food.id])
        #expect(group.totalCalories == 420)
    }

    @Test func waterReplacesOnlyTheFourthWidgetNutrientWhileEnabled() {
        let nutrients = ["protein", "carbs", "fat", "fiber"].map { id in
            WidgetNutrientValue(
                id: id,
                label: id.capitalized,
                shortLabel: String(id.prefix(1)).uppercased(),
                unit: "g",
                iconName: "circle",
                value: 1,
                goal: 2
            )
        }
        let enabled = WidgetSnapshot(
            date: .now,
            dayStart: Calendar.current.startOfDay(for: .now),
            calories: 0,
            calorieGoal: 2_000,
            protein: 0,
            proteinGoal: 150,
            carbs: 0,
            carbsGoal: 220,
            fat: 0,
            fatGoal: 70,
            homeNutrients: nutrients,
            waterTrackingEnabled: true,
            waterCurrentMl: 750,
            waterGoalMl: 2_000,
            waterUnitRaw: WaterUnit.milliliters.rawValue,
            themeStartHex: nil,
            themeEndHex: nil
        )

        #expect(enabled.displayedHomeNutrients.map(\.id) == ["protein", "carbs", "fat", "water"])
        #expect(enabled.displayedHomeNutrients.last?.value == 750)
        #expect(enabled.displayedHomeNutrients.last?.goal == 2_000)

        let disabled = WidgetSnapshot(
            date: enabled.date,
            dayStart: enabled.dayStart,
            calories: enabled.calories,
            calorieGoal: enabled.calorieGoal,
            protein: enabled.protein,
            proteinGoal: enabled.proteinGoal,
            carbs: enabled.carbs,
            carbsGoal: enabled.carbsGoal,
            fat: enabled.fat,
            fatGoal: enabled.fatGoal,
            homeNutrients: nutrients,
            waterTrackingEnabled: false,
            waterCurrentMl: enabled.waterCurrentMl,
            waterGoalMl: enabled.waterGoalMl,
            waterUnitRaw: enabled.waterUnitRaw,
            themeStartHex: nil,
            themeEndHex: nil
        )
        #expect(disabled.displayedHomeNutrients.map(\.id) == ["protein", "carbs", "fat", "fiber"])
    }
    @Test func singleNutrientWidgetDoesNotRestoreDefaults() {
        let nutrients = ["sodium"].map { id in
            WidgetNutrientValue(
                id: id,
                label: id.capitalized,
                shortLabel: String(id.prefix(1)).uppercased(),
                unit: "g",
                iconName: "circle",
                value: 1,
                goal: 2
            )
        }
        let enabled = WidgetSnapshot(
            date: .now,
            dayStart: Calendar.current.startOfDay(for: .now),
            calories: 0,
            calorieGoal: 2_000,
            protein: 0,
            proteinGoal: 150,
            carbs: 0,
            carbsGoal: 220,
            fat: 0,
            fatGoal: 70,
            homeNutrients: nutrients,
            waterTrackingEnabled: true,
            waterCurrentMl: 750,
            waterGoalMl: 2_000,
            waterUnitRaw: WaterUnit.milliliters.rawValue,
            themeStartHex: nil,
            themeEndHex: nil
        )

        #expect(enabled.displayedHomeNutrients.map(\.id) == ["sodium", "water"])
        #expect(enabled.emptyForToday().displayedHomeNutrients.map(\.id) == ["sodium", "water"])
        #expect(enabled.displayedHomeNutrients.last?.value == 750)
        #expect(enabled.displayedHomeNutrients.last?.goal == 2_000)

        let disabled = WidgetSnapshot(
            date: enabled.date,
            dayStart: enabled.dayStart,
            calories: enabled.calories,
            calorieGoal: enabled.calorieGoal,
            protein: enabled.protein,
            proteinGoal: enabled.proteinGoal,
            carbs: enabled.carbs,
            carbsGoal: enabled.carbsGoal,
            fat: enabled.fat,
            fatGoal: enabled.fatGoal,
            homeNutrients: nutrients,
            waterTrackingEnabled: false,
            waterCurrentMl: enabled.waterCurrentMl,
            waterGoalMl: enabled.waterGoalMl,
            waterUnitRaw: enabled.waterUnitRaw,
            themeStartHex: nil,
            themeEndHex: nil
        )
        #expect(disabled.displayedHomeNutrients.map(\.id) == ["sodium"])
    }
}
