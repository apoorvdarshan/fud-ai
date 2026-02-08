import Foundation
import SwiftUI

@Observable
class FoodStore {
    private(set) var entries: [FoodEntry] = []
    var onEntriesChanged: (() -> Void)?
    var onEntryAdded: ((FoodEntry) -> Void)?
    var onEntryDeleted: ((UUID) -> Void)?

    private let storageKey = "foodEntries"

    init() {
        loadEntries()
    }

    var todayEntries: [FoodEntry] {
        let calendar = Calendar.current
        return entries
            .filter { calendar.isDateInToday($0.timestamp) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    var todayEntriesByMeal: [(meal: MealType, entries: [FoodEntry])] {
        let calendar = Calendar.current
        let today = entries
            .filter { calendar.isDateInToday($0.timestamp) }
            .sorted { $0.timestamp > $1.timestamp }

        return MealType.allCases.compactMap { meal in
            let mealEntries = today.filter { $0.mealType == meal }
            return mealEntries.isEmpty ? nil : (meal, mealEntries)
        }
    }

    var todayCalories: Int {
        todayEntries.reduce(0) { $0 + $1.calories }
    }

    var todayProtein: Int {
        todayEntries.reduce(0) { $0 + $1.protein }
    }

    var todayCarbs: Int {
        todayEntries.reduce(0) { $0 + $1.carbs }
    }

    var todayFat: Int {
        todayEntries.reduce(0) { $0 + $1.fat }
    }

    // MARK: - Date-parameterized queries

    func entries(for date: Date) -> [FoodEntry] {
        let calendar = Calendar.current
        return entries
            .filter { calendar.isDate($0.timestamp, inSameDayAs: date) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    func entriesByMeal(for date: Date) -> [(meal: MealType, entries: [FoodEntry])] {
        let dayEntries = entries(for: date)
        return MealType.allCases.compactMap { meal in
            let mealEntries = dayEntries.filter { $0.mealType == meal }
            return mealEntries.isEmpty ? nil : (meal, mealEntries)
        }
    }

    func calories(for date: Date) -> Int {
        entries(for: date).reduce(0) { $0 + $1.calories }
    }

    func protein(for date: Date) -> Int {
        entries(for: date).reduce(0) { $0 + $1.protein }
    }

    func carbs(for date: Date) -> Int {
        entries(for: date).reduce(0) { $0 + $1.carbs }
    }

    func fat(for date: Date) -> Int {
        entries(for: date).reduce(0) { $0 + $1.fat }
    }

    func addEntry(_ entry: FoodEntry) {
        entries.append(entry)
        saveEntries()
        onEntriesChanged?()
        onEntryAdded?(entry)
        if UserDefaults.standard.string(forKey: "appleUserID") != nil {
            Task { await CloudKitService.saveFoodEntry(entry) }
        }
    }

    func deleteEntry(_ entry: FoodEntry) {
        let id = entry.id
        entries.removeAll { $0.id == id }
        saveEntries()
        onEntriesChanged?()
        onEntryDeleted?(id)
        if UserDefaults.standard.string(forKey: "appleUserID") != nil {
            Task { await CloudKitService.deleteFoodEntry(id: id) }
        }
    }

    func replaceAllEntries(_ newEntries: [FoodEntry]) {
        entries = newEntries
        saveEntries()
        onEntriesChanged?()
    }

    func mergeWithCloudEntries(_ cloudEntries: [FoodEntry]) {
        var merged = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
        for cloudEntry in cloudEntries {
            merged[cloudEntry.id] = cloudEntry
        }
        entries = Array(merged.values)
        saveEntries()
        onEntriesChanged?()
    }

    private func saveEntries() {
        if let data = try? JSONEncoder().encode(entries) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }

    private func loadEntries() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([FoodEntry].self, from: data)
        else { return }
        entries = decoded
    }
}
