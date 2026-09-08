import Foundation

enum DiaryImportMode {
    case replaceDateRange
    case addAsNew
}

struct DiaryImportPreview {
    let entries: [FoodEntry]
    let startDate: Date
    let endDate: Date
    var waterEntries: [WaterEntry] = []
    var includesWater: Bool = false

    var entryCount: Int { entries.count }
}

enum DiaryImportError: LocalizedError {
    case fileTooLarge
    case invalidDocument
    case unsupportedDocument
    case noEntries
    case invalidDate(String)
    case invalidMeal(String)
    case invalidEntry(String)
    case invalidWaterEntry

    var errorDescription: String? {
        switch self {
        case .fileTooLarge:
            return "This file is too large to import."
        case .invalidDocument:
            return "This is not a valid Fud AI food diary JSON file."
        case .unsupportedDocument:
            return "This food diary format is not supported."
        case .noEntries:
            return "The selected diary does not contain any food or water entries."
        case .invalidDate(let value):
            return "The diary contains an invalid date or time: \(value)."
        case .invalidMeal(let value):
            return "The diary contains an unknown meal type: \(value)."
        case .invalidWaterEntry:
            return "The diary contains an invalid water entry."
        case .invalidEntry(let value):
            return "The diary contains an invalid food entry: \(value)."
        }
    }
}

enum DiaryImporter {
    static let maximumFileSize = 20 * 1_024 * 1_024

    private struct Document: Decodable {
        let export: Metadata
        let days: [Day]
    }

    private struct Metadata: Decodable {
        let app: String
        let format_version: String
        let date_range: DateRange
    }

    private struct DateRange: Decodable {
        let start: String
        let end: String
    }

    private struct Day: Decodable {
        let date: String
        let meals: [Meal]
        let water_entries: [WaterItem]?
    }

    private struct WaterItem: Decodable {
        let entry_id: String
        let time: String
        let milliliters: Int
    }

    private struct Meal: Decodable {
        let type: String
        let items: [Item]
    }

    private struct Item: Decodable {
        let entry_id: String?
        let name: String
        let quantity_g: Double?
        let calories: Int
        let protein_g: Double
        let carbs_g: Double
        let fat_g: Double
        let sugar_g: Double?
        let added_sugar_g: Double?
        let fiber_g: Double?
        let saturated_fat_g: Double?
        let monounsaturated_fat_g: Double?
        let polyunsaturated_fat_g: Double?
        let cholesterol_mg: Double?
        let caffeine_mg: Double?
        let sodium_mg: Double?
        let potassium_mg: Double?
        let supplemental_nutrients_g: [String: Double]?
        let trans_fat_g: Double?
        let calcium_mg: Double?
        let iron_mg: Double?
        let magnesium_mg: Double?
        let zinc_mg: Double?
        let vitamin_a_mcg: Double?
        let vitamin_c_mg: Double?
        let vitamin_d_mcg: Double?
        let vitamin_b12_mcg: Double?
        let vitamin_e_mg: Double?
        let vitamin_k_mcg: Double?
        let folate_mcg: Double?
        let omega3_g: Double?
        let time: String
        let source: String
        let note: String?
        let ingredients: [Ingredient]?
    }

    private struct Ingredient: Decodable {
        let name: String
        let quantity_g: Double
        let calories: Int
        let protein_g: Double
        let carbs_g: Double
        let fat_g: Double
    }

    static func parse(_ data: Data, calendar: Calendar = .current) throws -> DiaryImportPreview {
        guard data.count <= maximumFileSize else { throw DiaryImportError.fileTooLarge }

        let document: Document
        do {
            document = try JSONDecoder().decode(Document.self, from: data)
        } catch {
            throw DiaryImportError.invalidDocument
        }

        guard document.export.app.caseInsensitiveCompare("Fud AI") == .orderedSame else {
            throw DiaryImportError.invalidDocument
        }
        guard let major = Int(document.export.format_version.split(separator: ".").first ?? ""),
              major == 1 else {
            throw DiaryImportError.unsupportedDocument
        }

        let start = try parseDay(document.export.date_range.start, calendar: calendar)
        let end = try parseDay(document.export.date_range.end, calendar: calendar)
        let lowerBound = calendar.startOfDay(for: min(start, end))
        let upperBound = calendar.startOfDay(for: max(start, end))

        var entries: [FoodEntry] = []
        var waterEntries: [WaterEntry] = []
        for day in document.days {
            let date = try parseDay(day.date, calendar: calendar)
            guard date >= lowerBound, date <= upperBound else {
                throw DiaryImportError.invalidDate(day.date)
            }
            for water in day.water_entries ?? [] {
                guard water.milliliters > 0, water.milliliters <= Int(Int32.max),
                      let id = UUID(uuidString: water.entry_id) else {
                    throw DiaryImportError.invalidWaterEntry
                }
                let timestamp = try parseTimestamp(day: day.date, time: water.time, calendar: calendar)
                let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: timestamp)
                let parsedDay = String(format: "%04d-%02d-%02d", components.year ?? 0, components.month ?? 0, components.day ?? 0)
                let parsedTime = String(format: "%02d:%02d", components.hour ?? 0, components.minute ?? 0)
                guard parsedDay == day.date, parsedTime == water.time else {
                    throw DiaryImportError.invalidDate("\(day.date) \(water.time)")
                }
                waterEntries.append(WaterEntry(id: id, date: timestamp, milliliters: water.milliliters))
            }
            for meal in day.meals {
                guard let mealType = MealType(rawValue: meal.type.lowercased()) else {
                    throw DiaryImportError.invalidMeal(meal.type)
                }
                for item in meal.items {
                    try validate(item)
                    let timestamp = try parseTimestamp(day: day.date, time: item.time, calendar: calendar)
                    let ingredients = try (item.ingredients ?? []).map { ingredient -> MealIngredient in
                        guard isNonNegative(ingredient.quantity_g), ingredient.calories >= 0,
                              isNonNegative(ingredient.protein_g), isNonNegative(ingredient.carbs_g),
                              isNonNegative(ingredient.fat_g), !ingredient.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                            throw DiaryImportError.invalidEntry(item.name)
                        }
                        return MealIngredient(
                            name: ingredient.name,
                            grams: ingredient.quantity_g,
                            calories: ingredient.calories,
                            protein: ingredient.protein_g,
                            carbs: ingredient.carbs_g,
                            fat: ingredient.fat_g
                        )
                    }
                    entries.append(FoodEntry(
                        id: item.entry_id.flatMap(UUID.init(uuidString:)) ?? UUID(),
                        name: item.name.trimmingCharacters(in: .whitespacesAndNewlines),
                        calories: item.calories,
                        protein: item.protein_g,
                        carbs: item.carbs_g,
                        fat: item.fat_g,
                        timestamp: timestamp,
                        source: item.source == "manually_edited" ? .manual : .textInput,
                        mealType: mealType,
                        sugar: item.sugar_g,
                        addedSugar: item.added_sugar_g,
                        fiber: item.fiber_g,
                        saturatedFat: item.saturated_fat_g,
                        monounsaturatedFat: item.monounsaturated_fat_g,
                        polyunsaturatedFat: item.polyunsaturated_fat_g,
                        cholesterol: item.cholesterol_mg,
                        caffeine: item.caffeine_mg,
                        supplementalNutrients: item.supplemental_nutrients_g ?? [:],
                        sodium: item.sodium_mg,
                        potassium: item.potassium_mg,
                        transFat: item.trans_fat_g,
                        calcium: item.calcium_mg,
                        iron: item.iron_mg,
                        magnesium: item.magnesium_mg,
                        zinc: item.zinc_mg,
                        vitaminA: item.vitamin_a_mcg,
                        vitaminC: item.vitamin_c_mg,
                        vitaminD: item.vitamin_d_mcg,
                        vitaminB12: item.vitamin_b12_mcg,
                        vitaminE: item.vitamin_e_mg,
                        vitaminK: item.vitamin_k_mcg,
                        folate: item.folate_mcg,
                        omega3: item.omega3_g,
                        servingSizeGrams: item.quantity_g,
                        customNote: item.note,
                        ingredients: ingredients
                    ))
                }
            }
        }
        guard !entries.isEmpty || !waterEntries.isEmpty else { throw DiaryImportError.noEntries }
        return DiaryImportPreview(entries: entries, startDate: lowerBound, endDate: upperBound,
                                  waterEntries: waterEntries, includesWater: document.days.contains { $0.water_entries != nil })
    }

    static func applying(
        _ preview: DiaryImportPreview,
        to existing: [FoodEntry],
        mode: DiaryImportMode,
        calendar: Calendar = .current
    ) -> [FoodEntry] {
        switch mode {
        case .addAsNew:
            return existing + preview.entries.map { imported in
                entry(from: imported, preserving: nil, id: UUID())
            }
        case .replaceDateRange:
            let outsideRange = existing.filter {
                let day = calendar.startOfDay(for: $0.timestamp)
                return day < preview.startDate || day > preview.endDate
            }
            let inRange = existing.filter {
                let day = calendar.startOfDay(for: $0.timestamp)
                return day >= preview.startDate && day <= preview.endDate
            }
            var existingByID = Dictionary(uniqueKeysWithValues: inRange.map { ($0.id, $0) })
            var existingByKey: [String: [FoodEntry]] = Dictionary(grouping: inRange) { matchKey($0, calendar: calendar) }
            var usedIDs = Set<UUID>()
            var occupiedIDs = Set(outsideRange.map(\.id))

            let imported = preview.entries.map { item -> FoodEntry in
                var match: FoodEntry?
                if let candidate = existingByID[item.id], !usedIDs.contains(candidate.id) {
                    match = candidate
                } else {
                    let key = matchKey(item, calendar: calendar)
                    while let candidate = existingByKey[key]?.first, usedIDs.contains(candidate.id) {
                        existingByKey[key]?.removeFirst()
                    }
                    if let candidate = existingByKey[key]?.first {
                        existingByKey[key]?.removeFirst()
                        match = candidate
                    }
                }

                if let match {
                    usedIDs.insert(match.id)
                    occupiedIDs.insert(match.id)
                    existingByID.removeValue(forKey: match.id)
                    return entry(from: item, preserving: match, id: match.id)
                }

                let desiredID = occupiedIDs.contains(item.id) ? UUID() : item.id
                occupiedIDs.insert(desiredID)
                return entry(from: item, preserving: nil, id: desiredID)
            }
            return outsideRange + imported
        }
    }

    /// Legacy food-only files must never erase hydration history.
    static func applyingWater(_ preview: DiaryImportPreview, to existing: [WaterEntry],
                              mode: DiaryImportMode, calendar: Calendar = .current) -> [WaterEntry] {
        guard preview.includesWater else { return existing }
        let retained = mode == .addAsNew ? existing : existing.filter {
            let day = calendar.startOfDay(for: $0.date)
            return day < preview.startDate || day > preview.endDate
        }
        var occupied = Set(retained.map(\.id))
        let imported = preview.waterEntries.map { entry in
            let id = mode == .addAsNew || occupied.contains(entry.id) ? UUID() : entry.id
            occupied.insert(id)
            return WaterEntry(id: id, date: entry.date, milliliters: entry.milliliters)
        }
        return retained + imported
    }

    private static func entry(from imported: FoodEntry, preserving old: FoodEntry?, id: UUID) -> FoodEntry {
        FoodEntry(
            id: id,
            name: imported.name,
            calories: imported.calories,
            protein: imported.protein,
            carbs: imported.carbs,
            fat: imported.fat,
            timestamp: imported.timestamp,
            imageData: old?.imageData,
            imageFilename: old?.imageFilename,
            additionalImageData: old?.additionalImageData ?? [],
            additionalImageFilenames: old?.additionalImageFilenames ?? [],
            emoji: old?.emoji,
            source: imported.source,
            mealType: imported.mealType,
            sugar: imported.sugar,
            addedSugar: imported.addedSugar,
            fiber: imported.fiber,
            saturatedFat: imported.saturatedFat,
            monounsaturatedFat: imported.monounsaturatedFat,
            polyunsaturatedFat: imported.polyunsaturatedFat,
            cholesterol: imported.cholesterol,
            caffeine: imported.caffeine,
            supplementalNutrients: imported.supplementalNutrients,
            sodium: imported.sodium,
            potassium: imported.potassium,
            transFat: imported.transFat,
            calcium: imported.calcium,
            iron: imported.iron,
            magnesium: imported.magnesium,
            zinc: imported.zinc,
            vitaminA: imported.vitaminA,
            vitaminC: imported.vitaminC,
            vitaminD: imported.vitaminD,
            vitaminB12: imported.vitaminB12,
            vitaminE: imported.vitaminE,
            vitaminK: imported.vitaminK,
            folate: imported.folate,
            omega3: imported.omega3,
            servingSizeGrams: imported.servingSizeGrams,
            servingUnitOptions: old?.servingUnitOptions ?? [],
            selectedServingUnit: old?.selectedServingUnit,
            selectedServingQuantity: old?.selectedServingQuantity,
            customNote: imported.customNote,
            progressiveMeal: old?.progressiveMeal ?? false,
            ingredients: imported.ingredients
        )
    }

    private static func validate(_ item: Item) throws {
        let name = item.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let required = [item.protein_g, item.carbs_g, item.fat_g]
        let optional = [
            item.quantity_g, item.sugar_g, item.added_sugar_g, item.fiber_g,
            item.saturated_fat_g, item.monounsaturated_fat_g, item.polyunsaturated_fat_g,
            item.cholesterol_mg, item.caffeine_mg, item.sodium_mg, item.potassium_mg,
            item.trans_fat_g, item.calcium_mg, item.iron_mg, item.magnesium_mg,
            item.zinc_mg, item.vitamin_a_mcg, item.vitamin_c_mg, item.vitamin_d_mcg,
            item.vitamin_b12_mcg, item.vitamin_e_mg, item.vitamin_k_mcg,
            item.folate_mcg, item.omega3_g
        ].compactMap { $0 }
        let supplements = item.supplemental_nutrients_g?.values ?? Dictionary<String, Double>().values
        guard !name.isEmpty, item.calories >= 0, required.allSatisfy(isNonNegative),
              optional.allSatisfy(isNonNegative), supplements.allSatisfy(isNonNegative) else {
            throw DiaryImportError.invalidEntry(name.isEmpty ? "Unnamed food" : name)
        }
    }

    nonisolated private static func isNonNegative(_ value: Double) -> Bool {
        value.isFinite && value >= 0
    }

    private static func parseDay(_ value: String, calendar: Calendar) throws -> Date {
        let parts = value.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3,
              let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) else {
            throw DiaryImportError.invalidDate(value)
        }
        return calendar.startOfDay(for: date)
    }

    private static func parseTimestamp(day: String, time: String, calendar: Calendar) throws -> Date {
        let dayParts = day.split(separator: "-").compactMap { Int($0) }
        let timeParts = time.split(separator: ":").compactMap { Int($0) }
        guard dayParts.count == 3, timeParts.count == 2,
              let date = calendar.date(from: DateComponents(
                year: dayParts[0], month: dayParts[1], day: dayParts[2],
                hour: timeParts[0], minute: timeParts[1]
              )) else {
            throw DiaryImportError.invalidDate("\(day) \(time)")
        }
        return date
    }

    private static func matchKey(_ entry: FoodEntry, calendar: Calendar) -> String {
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: entry.timestamp)
        let dateTime = String(format: "%04d-%02d-%02d %02d:%02d",
                              components.year ?? 0, components.month ?? 0, components.day ?? 0,
                              components.hour ?? 0, components.minute ?? 0)
        return "\(dateTime)|\(entry.mealType.rawValue)|\(entry.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())"
    }
}
