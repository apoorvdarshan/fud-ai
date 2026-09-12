import Foundation
import SwiftUI

enum FoodLogSortOrder: String, CaseIterable, Identifiable {
    case standard
    case latestMealsFirst

    static let storageKey = "foodLogSortOrder"
    static let defaultOrder: FoodLogSortOrder = .standard

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .standard:
            LocalizedDisplayText.text("Breakfast → Lunch → Dinner", polish: "Śniadanie → Lunch → Kolacja")
        case .latestMealsFirst:
            LocalizedDisplayText.text("Latest Meals First", polish: "Najnowsze posiłki najpierw")
        }
    }

    static func order(for rawValue: String) -> FoodLogSortOrder {
        FoodLogSortOrder(rawValue: rawValue) ?? defaultOrder
    }
}

struct FoodLogMealGroup: Identifiable {
    let id: String
    let meal: MealType
    let entries: [FoodEntry]

    /// Combined nutrients for this meal group — the "chicken + pasta + sauce = one meal"
    /// total shown in the food-log section header. Sums the same fields the daily totals use.
    var totalCalories: Int { entries.reduce(0) { $0 + $1.calories } }
    var totalProtein: Double { entries.reduce(0) { $0 + $1.protein } }
    var totalCarbs: Double { entries.reduce(0) { $0 + $1.carbs } }
    var totalFat: Double { entries.reduce(0) { $0 + $1.fat } }
}

@Observable
class FoodStore {
    private(set) var entries: [FoodEntry] = []
    var onEntriesChanged: (() -> Void)?
    var onEntryAdded: ((FoodEntry) -> Void)?
    var onEntryDeleted: ((UUID) -> Void)?
    var onEntryUpdated: ((FoodEntry) -> Void)?

    private let storageKey = "foodEntries"
    private let favoritesKey = "favoriteFoodEntries"
    private(set) var favorites: [FoodEntry] = []
    private let observesExternalChanges: Bool
    private let defaults: UserDefaults

    static let externalChangeNotification = "ai.fud.foodEntriesDidChange"

    init(observesExternalChanges: Bool = true, defaults: UserDefaults = .standard) {
        self.observesExternalChanges = observesExternalChanges
        self.defaults = defaults
        loadEntries()
        loadFavorites()
        if observesExternalChanges {
            startObservingExternalChanges()
        }
    }

    deinit {
        guard observesExternalChanges else { return }
        CFNotificationCenterRemoveObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            UnsafeRawPointer(Unmanaged.passUnretained(self).toOpaque()),
            CFNotificationName(Self.externalChangeNotification as CFString),
            nil
        )
    }

    var todayEntries: [FoodEntry] {
        let calendar = Calendar.current
        return entries
            .filter { calendar.isDateInToday($0.timestamp) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    var todayEntriesByMeal: [FoodLogMealGroup] {
        let calendar = Calendar.current
        let today = entries
            .filter { calendar.isDateInToday($0.timestamp) }
            .sorted { $0.timestamp > $1.timestamp }

        return groupedEntries(today, order: .standard)
    }

    var todayCalories: Int {
        todayEntries.reduce(0) { $0 + $1.calories }
    }

    var todayProtein: Double {
        todayEntries.reduce(0) { $0 + $1.protein }
    }

    var todayCarbs: Double {
        todayEntries.reduce(0) { $0 + $1.carbs }
    }

    var todayFat: Double {
        todayEntries.reduce(0) { $0 + $1.fat }
    }

    // MARK: - Date-parameterized queries

    func entries(for date: Date) -> [FoodEntry] {
        let calendar = Calendar.current
        return entries
            .filter { calendar.isDate($0.timestamp, inSameDayAs: date) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    func entriesByMeal(for date: Date, order: FoodLogSortOrder = .standard) -> [FoodLogMealGroup] {
        let dayEntries = entries(for: date)
        return groupedEntries(dayEntries, order: order)
    }

    private func groupedEntries(_ dayEntries: [FoodEntry], order: FoodLogSortOrder) -> [FoodLogMealGroup] {
        switch order {
        case .standard:
            return MealType.allCases.compactMap { meal in
                let mealEntries = dayEntries.filter { $0.mealType == meal }
                guard !mealEntries.isEmpty else { return nil }
                return FoodLogMealGroup(id: "standard-\(meal.rawValue)", meal: meal, entries: mealEntries)
            }
        case .latestMealsFirst:
            return latestMealRuns(dayEntries)
        }
    }

    private func latestMealRuns(_ dayEntries: [FoodEntry]) -> [FoodLogMealGroup] {
        var groups: [FoodLogMealGroup] = []
        var currentMeal: MealType?
        var currentEntries: [FoodEntry] = []

        func appendCurrentGroup() {
            guard let meal = currentMeal, !currentEntries.isEmpty else { return }
            let firstEntryID = currentEntries.first?.id.uuidString ?? UUID().uuidString
            groups.append(FoodLogMealGroup(
                id: "latest-\(groups.count)-\(meal.rawValue)-\(firstEntryID)",
                meal: meal,
                entries: currentEntries
            ))
        }

        for entry in dayEntries {
            if entry.mealType == currentMeal {
                currentEntries.append(entry)
            } else {
                appendCurrentGroup()
                currentMeal = entry.mealType
                currentEntries = [entry]
            }
        }

        appendCurrentGroup()
        return groups
    }

    func calories(for date: Date) -> Int {
        entries(for: date).reduce(0) { $0 + $1.calories }
    }

    func protein(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + $1.protein }
    }

    func carbs(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + $1.carbs }
    }

    func fat(for date: Date) -> Double {
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

    func caffeine(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.caffeine ?? 0) }
    }

    func supplementalNutrient(_ nutrient: SupplementalNutrient, for date: Date) -> Double {
        entries(for: date).reduce(0) { total, entry in
            total + (entry.supplementalNutrients[nutrient.rawValue] ?? 0)
        }
    }

    func sodium(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.sodium ?? 0) }
    }

    func potassium(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.potassium ?? 0) }
    }

    func transFat(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.transFat ?? 0) }
    }

    func calcium(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.calcium ?? 0) }
    }

    func iron(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.iron ?? 0) }
    }

    func magnesium(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.magnesium ?? 0) }
    }

    func zinc(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.zinc ?? 0) }
    }

    func vitaminA(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.vitaminA ?? 0) }
    }

    func vitaminC(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.vitaminC ?? 0) }
    }

    func vitaminD(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.vitaminD ?? 0) }
    }

    func vitaminB12(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.vitaminB12 ?? 0) }
    }

    func vitaminE(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.vitaminE ?? 0) }
    }

    func vitaminK(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.vitaminK ?? 0) }
    }

    func folate(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.folate ?? 0) }
    }

    func omega3(for date: Date) -> Double {
        entries(for: date).reduce(0) { $0 + ($1.omega3 ?? 0) }
    }

    // MARK: - Recents / Frequent

    func recentEntries(days: Int = 30, now: Date = .now) -> [FoodEntry] {
        let cutoff = Calendar.current.date(byAdding: .day, value: -days, to: now) ?? now
        return entries
            .filter { $0.timestamp >= cutoff }
            .sorted { $0.timestamp > $1.timestamp }
    }

    func frequentGroups(days: Int = 90, now: Date = .now) -> [FrequentFoodGroup] {
        let cutoff = Calendar.current.date(byAdding: .day, value: -days, to: now) ?? now
        var aggregates: [String: (count: Int, template: FoodEntry)] = [:]
        for entry in entries where entry.timestamp >= cutoff {
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

    // MARK: - Favorites

    func isFavorite(_ entry: FoodEntry) -> Bool {
        favorites.contains { $0.favoriteKey == entry.favoriteKey }
    }

    func toggleFavorite(_ entry: FoodEntry) {
        if let index = favorites.firstIndex(where: { $0.favoriteKey == entry.favoriteKey }) {
            favorites.remove(at: index)
        } else {
            // Remove any existing entry with same id to prevent duplicates
            favorites.removeAll { $0.id == entry.id }
            // Make sure the favorite has its own on-disk JPEG before persisting.
            // Without this, favoriting an entry that hasn't been through
            // addEntry() yet (e.g. straight from the Food Result review screen)
            // would persist with imageData = bytes-in-memory-only — and since
            // FoodEntry.encode drops raw bytes by design, the favorite would
            // come back image-less on the next launch.
            var favorite = entry
            offloadImageToDiskIfNeeded(&favorite)
            favorites.append(favorite)
        }
        saveFavorites()
    }

    func moveFavorite(from source: IndexSet, to destination: Int) {
        favorites.move(fromOffsets: source, toOffset: destination)
        saveFavorites()
    }

    private func saveFavorites() {
        if let data = try? JSONEncoder().encode(favorites) {
            defaults.set(data, forKey: favoritesKey)
            defaults.synchronize()
        }
    }

    private func loadFavorites() {
        guard let data = defaults.data(forKey: favoritesKey),
              let decoded = try? JSONDecoder().decode([FoodEntry].self, from: data)
        else { return }
        favorites = decoded
    }

    // MARK: - CRUD

    @discardableResult
    func addEntry(_ entry: FoodEntry) -> Bool {
        guard FastingStore.persistedActiveSession(defaults: defaults) == nil else { return false }
        var entry = entry
        let photosToExport = (entry.imageData.map { [$0] } ?? []) + entry.additionalImageData
        offloadImageToDiskIfNeeded(&entry)
        entries.append(entry)
        saveEntries()
        exportLoggedMealPhotos(photosToExport)
        onEntriesChanged?()
        onEntryAdded?(entry)
        ReviewPrompter.foodWasLogged()
        return true
    }

    private func exportLoggedMealPhotos(_ photoData: [Data]) {
        guard !photoData.isEmpty else { return }
        for data in photoData {
            MealPhotoGalleryExport.saveToGalleryIfEnabled(data: data)
        }
    }

    func updateEntry(_ entry: FoodEntry) {
        guard let index = entries.firstIndex(where: { $0.id == entry.id }) else { return }
        let previousFilenames = Set(entries[index].allImageFilenames)
        var entry = entry
        offloadImageToDiskIfNeeded(&entry)
        entries[index] = entry
        if saveEntries() {
            let removedFilenames = previousFilenames.subtracting(entry.allImageFilenames)
            for filename in removedFilenames where !isImageStillReferenced(filename: filename, excludingEntryID: entry.id) {
                FoodImageStore.shared.delete(filename: filename)
            }
        }
        onEntriesChanged?()
        // Single callback so HealthKit can serialize delete-then-write atomically.
        onEntryUpdated?(entry)
    }

    func deleteEntry(_ entry: FoodEntry) {
        let id = entry.id
        // Skip the disk-delete when a favorite (or another entry) still
        // references this filename. Without this guard, favoriting a meal,
        // deleting the log entry, and relaunching wipes the favorite's image
        // because both rows share the same fudai-image-<uuid>.jpg.
        for filename in entry.allImageFilenames where !isImageStillReferenced(filename: filename, excludingEntryID: id) {
            FoodImageStore.shared.delete(filename: filename)
        }
        entries.removeAll { $0.id == id }
        saveEntries()
        onEntriesChanged?()
        onEntryDeleted?(id)
    }

    /// Merge selected diary foods into one combined meal and remove the originals.
    @discardableResult
    func combineIntoMeal(ids: Set<UUID>) -> FoodEntry? {
        guard FastingStore.persistedActiveSession(defaults: defaults) == nil else { return nil }
        guard ids.count >= 2 else { return nil }
        let selected = entries.filter { ids.contains($0.id) }
        guard selected.count >= 2 else { return nil }
        var combined = CombinedMeal.combine(selected)
        offloadImageToDiskIfNeeded(&combined)
        let favoriteFilenames = Set(favorites.flatMap(\.allImageFilenames))
        for old in selected {
            for filename in old.allImageFilenames {
                if favoriteFilenames.contains(filename) { continue }
                if combined.allImageFilenames.contains(filename) { continue }
                if isImageStillReferenced(filename: filename, excludingEntryID: old.id) { continue }
                FoodImageStore.shared.delete(filename: filename)
            }
            onEntryDeleted?(old.id)
        }
        entries = entries.filter { !ids.contains($0.id) } + [combined]
        saveEntries()
        onEntriesChanged?()
        onEntryAdded?(combined)
        ReviewPrompter.foodWasLogged()
        return combined
    }

    func reloadFromDefaults() {
        loadEntries()
        loadFavorites()
        onEntriesChanged?()
    }

    func replaceAllEntries(_ newEntries: [FoodEntry]) {
        // Delete on-disk JPEGs for any entry that's about to be removed —
        // otherwise Clear Food Log / Delete All Data orphan files in
        // Application Support forever. Skip files that a favorite or a
        // surviving entry still references (same filename, different id).
        let surviving = Set(newEntries.map(\.id))
        let survivingFilenames = Set(newEntries.flatMap(\.allImageFilenames))
        let favoriteFilenames = Set(favorites.flatMap(\.allImageFilenames))
        for old in entries where !surviving.contains(old.id) {
            for filename in old.allImageFilenames {
                if survivingFilenames.contains(filename) || favoriteFilenames.contains(filename) { continue }
                FoodImageStore.shared.delete(filename: filename)
            }
        }
        entries = newEntries.map { var e = $0; offloadImageToDiskIfNeeded(&e); return e }
        saveEntries()
        onEntriesChanged?()
    }

    /// Applies a validated diary import in one local write, then mirrors the
    /// resulting ID changes to Apple Health through the existing callbacks.
    /// Entries whose IDs survive the import are updates; new IDs are additions.
    func replaceEntriesFromImport(_ newEntries: [FoodEntry]) {
        let oldIDs = Set(entries.map(\.id))
        let newIDs = Set(newEntries.map(\.id))
        let removedIDs = oldIDs.subtracting(newIDs)
        let updatedEntries = newEntries.filter { oldIDs.contains($0.id) }
        let addedEntries = newEntries.filter { !oldIDs.contains($0.id) }

        replaceAllEntries(newEntries)

        removedIDs.forEach { onEntryDeleted?($0) }
        updatedEntries.forEach { onEntryUpdated?($0) }
        addedEntries.forEach { onEntryAdded?($0) }
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

    func reprocessEntry(_ entry: FoodEntry, withNote note: String) async throws -> GeminiService.FoodAnalysis {
        let images = entry.allImageFilenames.compactMap {
            FoodImageStore.shared.load(filename: $0).flatMap(UIImage.init(data:))
        }
        let description = Self.reprocessDescription(for: entry, note: note)
        return try await reprocessWithHostedQuota(
            images: images,
            description: description,
            progressiveMeal: entry.progressiveMeal
        )
    }

    @MainActor
    private func reprocessWithHostedQuota(
        images: [UIImage],
        description: String,
        progressiveMeal: Bool
    ) async throws -> GeminiService.FoodAnalysis {
        try await AIGate.runWithHostedQuota(.reprocessMeal) {
            if !images.isEmpty {
                return try await GeminiService.analyzeFood(
                    images: images,
                    description: description,
                    progressiveMeal: progressiveMeal,
                    skipHostedMetering: true
                )
            }
            return try await GeminiService.analyzeTextInput(description: description, skipHostedMetering: true)
        }
    }

    private static func reprocessDescription(for entry: FoodEntry, note: String) -> String {
        var parts: [String] = []
        let name = entry.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !name.isEmpty { parts.append(name) }
        if let qty = entry.selectedServingQuantity, qty > 0,
           let unit = entry.selectedServingUnit?.trimmingCharacters(in: .whitespacesAndNewlines), !unit.isEmpty {
            let q = qty.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(qty)) : String(qty)
            parts.append("\(q) \(unit)")
        } else if let grams = entry.servingSizeGrams, grams > 0 {
            parts.append("\(Int(grams)) g")
        }
        let base = parts.joined(separator: ", ")
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        if base.isEmpty { return trimmedNote }
        if trimmedNote.isEmpty { return base }
        return "\(base). \(trimmedNote)"
    }

    /// If `entry` carries in-memory `imageData` but no `imageFilename`, write
    /// the bytes to disk and stamp the filename onto the entry. No-op when
    /// there are no bytes, or when a filename is already set (idempotent).
    /// The 4 MiB UserDefaults cap demands we never persist raw bytes.
    private func offloadImageToDiskIfNeeded(_ entry: inout FoodEntry) {
        let primaryStorageID = isImageStillReferenced(filename: "\(entry.id.uuidString).jpg", excludingEntryID: entry.id)
            ? UUID() : entry.id
        if entry.imageFilename == nil, let data = entry.imageData,
           let filename = FoodImageStore.shared.store(data: data, for: primaryStorageID) {
            entry.imageFilename = filename
        }
        if entry.additionalImageFilenames.count < entry.additionalImageData.count {
            var filenames = entry.additionalImageFilenames
            for index in filenames.count..<entry.additionalImageData.count {
                let storageID = isImageStillReferenced(filename: "\(entry.id.uuidString)-\(index + 1).jpg", excludingEntryID: entry.id)
                    ? UUID() : entry.id
                if let filename = FoodImageStore.shared.store(
                    data: entry.additionalImageData[index],
                    for: storageID,
                    index: index + 1
                ) {
                    filenames.append(filename)
                }
            }
            entry.additionalImageFilenames = filenames
        }
        let filenames = entry.allImageFilenames
        if entry.imageFilename == nil { entry.imageFilename = filenames.first }
        entry.additionalImageFilenames = filenames.filter { $0 != entry.imageFilename }
    }

    /// Used by deleteEntry / replaceAllEntries to decide whether the on-disk
    /// JPEG can safely be removed. A filename can be shared by a logged entry
    /// + a favorite (same `id`, same generated `fudai-image-<uuid>.jpg`), or
    /// by two logged entries that came from the same favorite re-log.
    private func isImageStillReferenced(filename: String, excludingEntryID: UUID) -> Bool {
        if entries.contains(where: { $0.id != excludingEntryID && $0.allImageFilenames.contains(filename) }) {
            return true
        }
        return favorites.contains { $0.allImageFilenames.contains(filename) }
    }

    private func startObservingExternalChanges() {
        CFNotificationCenterAddObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            UnsafeRawPointer(Unmanaged.passUnretained(self).toOpaque()),
            { _, observer, _, _, _ in
                guard let observer else { return }
                let store = Unmanaged<FoodStore>.fromOpaque(observer).takeUnretainedValue()
                DispatchQueue.main.async {
                    store.reloadFromExternalChange()
                }
            },
            Self.externalChangeNotification as CFString,
            nil,
            .deliverImmediately
        )
    }

    private func reloadFromExternalChange() {
        loadEntries()
        onEntriesChanged?()
    }

    static func postExternalChangeNotification() {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(externalChangeNotification as CFString),
            nil,
            nil,
            true
        )
    }

    @discardableResult
    private func saveEntries() -> Bool {
        if let data = try? JSONEncoder().encode(entries) {
            defaults.set(data, forKey: storageKey)
            return defaults.synchronize()
        }
        return false
    }

    private func loadEntries() {
        guard let data = defaults.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([FoodEntry].self, from: data)
        else {
            entries = []
            return
        }
        entries = decoded

        // Legacy migration: rows written by pre-FoodImageStore builds embedded
        // JPEG bytes in the JSON blob. Offload any such rows to disk, stamp
        // the filename, and rewrite the UserDefaults blob — shrinking it from
        // multi-MB to ~a few KB so the 4 MiB cap stops silently swallowing
        // adds/deletes. Idempotent: runs only on entries that need it.
        var migrated = false
        for i in entries.indices {
            if entries[i].imageFilename == nil, let data = entries[i].imageData {
                if let filename = FoodImageStore.shared.store(data: data, for: entries[i].id) {
                    entries[i].imageFilename = filename
                    migrated = true
                }
            }
        }
        if migrated {
            saveEntries()
        }
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
