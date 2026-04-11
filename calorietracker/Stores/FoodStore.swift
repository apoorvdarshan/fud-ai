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

    // MARK: - Micronutrient aggregation

    func sugar(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.sugar ?? 0) }
    }

    func addedSugar(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.addedSugar ?? 0) }
    }

    func fiber(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.fiber ?? 0) }
    }

    func saturatedFat(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.saturatedFat ?? 0) }
    }

    func monounsaturatedFat(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.monounsaturatedFat ?? 0) }
    }

    func polyunsaturatedFat(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.polyunsaturatedFat ?? 0) }
    }

    func cholesterol(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.cholesterol ?? 0) }
    }

    func sodium(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.sodium ?? 0) }
    }

    func potassium(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.potassium ?? 0) }
    }

    // MARK: - Recents / Frequent

    func recentEntries(limit: Int = 50) -> [FoodEntry] {
        Array(entries.sorted { $0.timestamp > $1.timestamp }.prefix(limit))
    }

    func frequentGroups() -> [FrequentFoodGroup] {
        var aggregates: [String: (count: Int, template: FoodEntry)] = [:]
        for entry in entries {
            let key = "\(entry.name.lowercased())|\(entry.calories)"
            if let current = aggregates[key] {
                let newCount = current.count + 1
                let template = entry.timestamp > current.template.timestamp ? entry : current.template
                aggregates[key] = (newCount, template)
            } else {
                aggregates[key] = (1, entry)
            }
        }
        return aggregates.map { _, pair in
            FrequentFoodGroup(template: pair.template, count: pair.count)
        }
        .sorted { lhs, rhs in
            if lhs.count != rhs.count { return lhs.count > rhs.count }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    func addEntry(_ entry: FoodEntry) {
        entries.append(entry)
        saveEntries()
        onEntriesChanged?()
        onEntryAdded?(entry)
    }

    func updateEntry(_ entry: FoodEntry) {
        guard let index = entries.firstIndex(where: { $0.id == entry.id }) else { return }
        let oldID = entry.id
        entries[index] = entry
        saveEntries()
        onEntriesChanged?()
        // Re-sync HealthKit: delete old samples, write new ones
        onEntryDeleted?(oldID)
        onEntryAdded?(entry)
    }

    func deleteEntry(_ entry: FoodEntry) {
        let id = entry.id
        entries.removeAll { $0.id == id }
        saveEntries()
        onEntriesChanged?()
        onEntryDeleted?(id)
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

struct FrequentFoodGroup: Identifiable {
    let id: String
    let name: String
    let calories: Int
    let count: Int
    let template: FoodEntry

    init(template: FoodEntry, count: Int) {
        self.id = "\(template.name.lowercased())|\(template.calories)"
        self.name = template.name
        self.calories = template.calories
        self.count = count
        self.template = template
    }
}
