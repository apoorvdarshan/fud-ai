import Foundation

enum LocalizedDisplayText {
    static func text(_ key: String, polish: String? = nil) -> String {
        if usesPolish, let polish = polish ?? polishFallbacks[key] {
            return polish
        }
        return String(localized: String.LocalizationValue(key))
    }

    private static var usesPolish: Bool {
        let preferred = Bundle.main.preferredLocalizations.first
            ?? Locale.preferredLanguages.first
            ?? Locale.autoupdatingCurrent.identifier
        return preferred.lowercased().hasPrefix("pl")
    }

    private static let polishFallbacks: [String: String] = [
        "Calories": "Kalorie",
        "Protein": "Białko",
        "Carbs": "Węglowodany",
        "Fat": "Tłuszcz",
        "Sugar": "Cukier",
        "Added Sugar": "Cukier dodany",
        "Fiber": "Błonnik",
        "Saturated Fat": "Tłuszcze nasycone",
        "Mono Unsat. Fat": "Tłuszcze jednonienasycone",
        "Poly Unsat. Fat": "Tłuszcze wielonienasycone",
        "Mono Fat": "Tłuszcze mono",
        "Poly Fat": "Tłuszcze poli",
        "Cholesterol": "Cholesterol",
        "Sodium": "Sód",
        "Potassium": "Potas",
        "Trans Fat": "Tłuszcze trans",
        "Calcium": "Wapń",
        "Iron": "Żelazo",
        "Magnesium": "Magnez",
        "Zinc": "Cynk",
        "Vitamin A": "Witamina A",
        "Vitamin C": "Witamina C",
        "Vitamin D": "Witamina D",
        "Vitamin B12": "Witamina B12",
        "Vitamin E": "Witamina E",
        "Vitamin K": "Witamina K",
        "Folate": "Foliany",
        "Omega-3": "Omega-3",
        "Current": "Aktualna",
        "Goal": "Cel",
        "Net Change": "Zmiana netto",
        "Average": "Średnia",
        "Current Streak": "Aktualna seria",
        "Best Streak": "Najlepsza seria",
        "Days on Target": "Dni w celu",
        "Total Entries": "Wszystkie wpisy",
        "Log Weight": "Dodaj wagę",
        "Log Body Fat": "Dodaj pomiar tkanki tłuszczowej",
        "Name": "Nazwa",
        "Meal": "Posiłek",
        "Protein (g)": "Białko (g)",
        "Carbs (g)": "Węglowodany (g)",
        "Fat (g)": "Tłuszcz (g)",
        "Nutrition Data": "Dane żywieniowe",
        "Weight Sync": "Synchronizacja wagi",
        "Body Measurements": "Pomiary ciała",
        "Health Score": "Wynik zdrowia",
        "Fats": "Tłuszcze",
        "Height": "Wzrost",
        "Feet": "Stopy",
        "Inches": "Cale"
    ]
}

enum FoodSource: String, Codable {
    case snapFood
    case nutritionLabel
    case barcode
    case textInput
    case manual
}

enum MealType: String, Codable, CaseIterable {
    case breakfast
    case lunch
    case dinner
    case snack
    case other

    var displayName: String {
        switch self {
        case .breakfast: LocalizedDisplayText.text("Breakfast", polish: "Śniadanie")
        case .lunch: LocalizedDisplayText.text("Lunch", polish: "Lunch")
        case .dinner: LocalizedDisplayText.text("Dinner", polish: "Kolacja")
        case .snack: LocalizedDisplayText.text("Snack", polish: "Przekąska")
        case .other: LocalizedDisplayText.text("Other", polish: "Inne")
        }
    }

    var icon: String {
        switch self {
        case .breakfast: "sunrise.fill"
        case .lunch: "sun.max.fill"
        case .dinner: "moon.fill"
        case .snack: "cup.and.saucer.fill"
        case .other: "fork.knife"
        }
    }

    nonisolated static var currentMeal: MealType {
        MealScheduleSettings.mealType(for: .now)
    }
}

struct MealSchedule: Equatable, Sendable {
    var breakfastStartMinutes: Int
    var lunchStartMinutes: Int
    var dinnerStartMinutes: Int
    var snackStartMinutes: Int

    nonisolated static let defaults = MealSchedule(
        breakfastStartMinutes: 5 * 60,
        lunchStartMinutes: 12 * 60,
        dinnerStartMinutes: 18 * 60,
        snackStartMinutes: 23 * 60
    )

    nonisolated var isValid: Bool {
        (0..<1440).contains(breakfastStartMinutes)
            && breakfastStartMinutes < lunchStartMinutes
            && lunchStartMinutes < dinnerStartMinutes
            && dinnerStartMinutes < snackStartMinutes
            && snackStartMinutes < 1440
    }

    nonisolated func mealType(for date: Date, calendar: Calendar = .current) -> MealType {
        let components = calendar.dateComponents([.hour, .minute], from: date)
        let minutes = (components.hour ?? 0) * 60 + (components.minute ?? 0)
        switch minutes {
        case snackStartMinutes..<1440, 0..<breakfastStartMinutes:
            return MealType.snack
        case dinnerStartMinutes..<snackStartMinutes:
            return MealType.dinner
        case lunchStartMinutes..<dinnerStartMinutes:
            return MealType.lunch
        default:
            return MealType.breakfast
        }
    }
}

enum MealScheduleSettings {
    nonisolated static let breakfastStartKey = "mealBreakfastStartMinutes"
    nonisolated static let lunchStartKey = "mealLunchStartMinutes"
    nonisolated static let dinnerStartKey = "mealDinnerStartMinutes"
    nonisolated static let snackStartKey = "mealSnackStartMinutes"

    nonisolated static var current: MealSchedule {
        let defaults = MealSchedule.defaults
        let stored = MealSchedule(
            breakfastStartMinutes: storedMinutes(forKey: breakfastStartKey, default: defaults.breakfastStartMinutes),
            lunchStartMinutes: storedMinutes(forKey: lunchStartKey, default: defaults.lunchStartMinutes),
            dinnerStartMinutes: storedMinutes(forKey: dinnerStartKey, default: defaults.dinnerStartMinutes),
            snackStartMinutes: storedMinutes(forKey: snackStartKey, default: defaults.snackStartMinutes)
        )
        return stored.isValid ? stored : defaults
    }

    nonisolated static func mealType(for date: Date) -> MealType {
        current.mealType(for: date)
    }

    private nonisolated static func storedMinutes(forKey key: String, default defaultValue: Int) -> Int {
        guard UserDefaults.standard.object(forKey: key) != nil else { return defaultValue }
        return UserDefaults.standard.integer(forKey: key)
    }
}

struct ServingUnitOption: Codable, Hashable, Identifiable {
    var unit: String
    var gramsPerUnit: Double
    var quantity: Double?

    var id: String { normalizedUnit }

    var normalizedUnit: String {
        unit.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    var isGramUnit: Bool {
        ["g", "gram", "grams"].contains(normalizedUnit)
    }

    var isValid: Bool {
        !normalizedUnit.isEmpty && gramsPerUnit > 0
    }

    static let grams = ServingUnitOption(unit: "g", gramsPerUnit: 1)
    /// Internal unit used when a system-health restore contains the logged
    /// nutrition totals but no recoverable food weight. The numeric basis is a
    /// serving count, not grams; callers must keep `servingSizeGrams` nil when
    /// persisting this option.
    static func loggedServing(quantity: Double = 1) -> ServingUnitOption {
        ServingUnitOption(unit: "serving", gramsPerUnit: 1, quantity: quantity)
    }

    func quantity(for totalGrams: Double) -> Double {
        if let quantity, quantity > 0 {
            return quantity
        }
        guard gramsPerUnit > 0 else { return totalGrams }
        return totalGrams / gramsPerUnit
    }

    func displayUnit(for quantity: Double?) -> String {
        guard let quantity, abs(quantity - 1) > 0.0001 else { return unit }
        switch normalizedUnit {
        case "g", "gram", "grams", "kg", "mg", "ml", "l", "oz", "fl oz", "tbsp", "tsp":
            return unit
        case "piece":
            return "pieces"
        default:
            return unit.hasSuffix("s") ? unit : "\(unit)s"
        }
    }
}

struct FoodMeasurementSettings {
    static let preferGramsByDefaultKey = "foodMeasurementPreferGramsByDefault"

    static var preferGramsByDefault: Bool {
        get { UserDefaults.standard.bool(forKey: preferGramsByDefaultKey) }
        set { UserDefaults.standard.set(newValue, forKey: preferGramsByDefaultKey) }
    }
}

enum MacroValueFormatter {
    static func string(_ value: Double) -> String {
        if abs(value.rounded() - value) < 0.0001 {
            return String(Int(value.rounded()))
        }
        return String(format: "%.1f", value)
    }

    static func withUnit(_ value: Double) -> String {
        "\(string(value))g"
    }
}

struct MealIngredient: Identifiable, Codable, Equatable, Sendable {
    var id: UUID
    var name: String
    var grams: Double
    var calories: Int
    var protein: Double
    var carbs: Double
    var fat: Double
    var imageFilename: String?
    var additionalImageFilenames: [String]?
    var emoji: String?

    nonisolated init(
        id: UUID = UUID(),
        name: String,
        grams: Double,
        calories: Int,
        protein: Double,
        carbs: Double,
        fat: Double,
        imageFilename: String? = nil,
        additionalImageFilenames: [String]? = nil,
        emoji: String? = nil
    ) {
        self.id = id
        self.name = name
        self.grams = grams
        self.calories = calories
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
        self.imageFilename = imageFilename
        self.additionalImageFilenames = additionalImageFilenames
        self.emoji = emoji
    }

    nonisolated var allImageFilenames: [String] {
        (imageFilename.map { [$0] } ?? []) + (additionalImageFilenames ?? [])
    }

    nonisolated func scaled(by factor: Double) -> MealIngredient {
        var result = self
        result.grams *= factor
        result.calories = Int(round(Double(calories) * factor))
        result.protein *= factor
        result.carbs *= factor
        result.fat *= factor
        return result
    }
}

struct MealIngredientTotals: Equatable, Sendable {
    var grams: Double
    var calories: Int
    var protein: Double
    var carbs: Double
    var fat: Double
}

extension Collection where Element == MealIngredient {
    nonisolated var ingredientTotals: MealIngredientTotals {
        reduce(MealIngredientTotals(grams: 0, calories: 0, protein: 0, carbs: 0, fat: 0)) { total, item in
            MealIngredientTotals(
                grams: total.grams + item.grams,
                calories: total.calories + item.calories,
                protein: total.protein + item.protein,
                carbs: total.carbs + item.carbs,
                fat: total.fat + item.fat
            )
        }
    }
}

struct FoodEntry: Identifiable, Codable {
    let id: UUID
    var name: String
    var calories: Int
    var protein: Double
    var carbs: Double
    var fat: Double
    let timestamp: Date
    /// In-memory image bytes. NEVER persisted directly — see `imageFilename`.
    /// Kept as a property so existing views continue to read `entry.imageData`
    /// unchanged; the on-disk filename is the source of truth for persistence.
    var imageData: Data?
    /// Filename (not path) under Application Support/fudai-food-images/ where
    /// the JPEG lives. Tiny string; JSON-safe. The actual bytes live on disk
    /// to keep the foodEntries UserDefaults blob under iOS's 4 MiB cap.
    var imageFilename: String?
    /// Additional photos captured for the same meal. The first photo remains in
    /// `imageData` / `imageFilename` for backward compatibility with older builds.
    var additionalImageData: [Data]
    var additionalImageFilenames: [String]
    var emoji: String?
    var source: FoodSource
    var mealType: MealType

    // Micronutrients (all optional, nil when unavailable)
    var sugar: Double?          // grams
    var addedSugar: Double?     // grams
    var fiber: Double?          // grams
    var saturatedFat: Double?   // grams
    var monounsaturatedFat: Double? // grams
    var polyunsaturatedFat: Double? // grams
    var cholesterol: Double?    // milligrams
    var caffeine: Double?       // milligrams
    /// Optional sports-nutrition compounds in grams, keyed by SupplementalNutrient.rawValue.
    var supplementalNutrients: [String: Double]
    var sodium: Double?         // milligrams
    var potassium: Double?      // milligrams
    var transFat: Double?       // grams
    var calcium: Double?        // milligrams
    var iron: Double?           // milligrams
    var magnesium: Double?      // milligrams
    var zinc: Double?           // milligrams
    var vitaminA: Double?       // micrograms
    var vitaminC: Double?       // milligrams
    var vitaminD: Double?       // micrograms
    var vitaminB12: Double?     // micrograms
    var vitaminE: Double?       // milligrams
    var vitaminK: Double?       // micrograms
    var folate: Double?         // micrograms
    var omega3: Double?         // grams
    var servingSizeGrams: Double? // grams (nil for old entries)
    var servingUnitOptions: [ServingUnitOption]
    var selectedServingUnit: String?
    var selectedServingQuantity: Double?
    var customNote: String?
    var progressiveMeal: Bool
    var ingredients: [MealIngredient]
    var productMetadata: FoodProductMetadata?

    /// HealthKit/Health Connect can restore nutrition totals without restoring
    /// the food's original mass. Keep that state explicit instead of silently
    /// turning an unknown amount into 100 g in edit and re-log screens.
    var hasKnownServingSize: Bool {
        servingSizeGrams.map { $0.isFinite && $0 > 0 } ?? false
    }

    /// Working scale used by serving editors. For an unknown mass this is the
    /// number of logged servings, so an unchanged edit keeps nutrition at 1x.
    var reviewServingReference: Double {
        if let servingSizeGrams, servingSizeGrams.isFinite, servingSizeGrams > 0 {
            return servingSizeGrams
        }
        if let selectedServingQuantity, selectedServingQuantity.isFinite, selectedServingQuantity > 0 {
            return selectedServingQuantity
        }
        return 1
    }

    var reviewServingUnitOptions: [ServingUnitOption] {
        hasKnownServingSize ? servingUnitOptions : [.loggedServing(quantity: reviewServingReference)]
    }

    var reviewSelectedServingUnit: String? {
        hasKnownServingSize ? selectedServingUnit : "serving"
    }

    var reviewSelectedServingQuantity: Double? {
        hasKnownServingSize ? selectedServingQuantity : reviewServingReference
    }

    nonisolated init(
        id: UUID = UUID(),
        name: String,
        calories: Int,
        protein: Double,
        carbs: Double,
        fat: Double,
        timestamp: Date = Date(),
        imageData: Data? = nil,
        imageFilename: String? = nil,
        additionalImageData: [Data] = [],
        additionalImageFilenames: [String] = [],
        emoji: String? = nil,
        source: FoodSource,
        mealType: MealType = .other,
        sugar: Double? = nil,
        addedSugar: Double? = nil,
        fiber: Double? = nil,
        saturatedFat: Double? = nil,
        monounsaturatedFat: Double? = nil,
        polyunsaturatedFat: Double? = nil,
        cholesterol: Double? = nil,
        caffeine: Double? = nil,
        supplementalNutrients: [String: Double] = [:],
        sodium: Double? = nil,
        potassium: Double? = nil,
        transFat: Double? = nil,
        calcium: Double? = nil,
        iron: Double? = nil,
        magnesium: Double? = nil,
        zinc: Double? = nil,
        vitaminA: Double? = nil,
        vitaminC: Double? = nil,
        vitaminD: Double? = nil,
        vitaminB12: Double? = nil,
        vitaminE: Double? = nil,
        vitaminK: Double? = nil,
        folate: Double? = nil,
        omega3: Double? = nil,
        servingSizeGrams: Double? = nil,
        servingUnitOptions: [ServingUnitOption] = [],
        selectedServingUnit: String? = nil,
        selectedServingQuantity: Double? = nil,
        customNote: String? = nil,
        progressiveMeal: Bool = false,
        ingredients: [MealIngredient] = [],
        productMetadata: FoodProductMetadata? = nil
    ) {
        self.id = id
        self.name = name
        self.calories = calories
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
        self.timestamp = timestamp
        self.imageData = imageData
        self.imageFilename = imageFilename
        self.additionalImageData = additionalImageData
        self.additionalImageFilenames = additionalImageFilenames
        self.emoji = emoji
        self.source = source
        self.mealType = mealType
        self.sugar = sugar
        self.addedSugar = addedSugar
        self.fiber = fiber
        self.saturatedFat = saturatedFat
        self.monounsaturatedFat = monounsaturatedFat
        self.polyunsaturatedFat = polyunsaturatedFat
        self.cholesterol = cholesterol
        self.caffeine = caffeine
        self.supplementalNutrients = supplementalNutrients
        self.sodium = sodium
        self.potassium = potassium
        self.transFat = transFat
        self.calcium = calcium
        self.iron = iron
        self.magnesium = magnesium
        self.zinc = zinc
        self.vitaminA = vitaminA
        self.vitaminC = vitaminC
        self.vitaminD = vitaminD
        self.vitaminB12 = vitaminB12
        self.vitaminE = vitaminE
        self.vitaminK = vitaminK
        self.folate = folate
        self.omega3 = omega3
        self.servingSizeGrams = servingSizeGrams
        self.servingUnitOptions = servingUnitOptions
        self.selectedServingUnit = selectedServingUnit
        self.selectedServingQuantity = selectedServingQuantity
        self.customNote = customNote
        self.progressiveMeal = progressiveMeal
        self.ingredients = ingredients
        self.productMetadata = productMetadata
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, calories, protein, carbs, fat, timestamp
        case imageData     // legacy — old rows stored bytes inline; kept only for decode
        case imageFilename // current — filename on disk
        case additionalImageFilenames
        case emoji, source, mealType
        case sugar, addedSugar, fiber, saturatedFat
        case monounsaturatedFat, polyunsaturatedFat
        case cholesterol, caffeine, supplementalNutrients, sodium, potassium
        case transFat, calcium, iron, magnesium, zinc
        case vitaminA, vitaminC, vitaminD, vitaminB12, vitaminE, vitaminK, folate, omega3
        case servingSizeGrams
        case servingUnitOptions, selectedServingUnit, selectedServingQuantity, customNote, progressiveMeal, ingredients
        case productMetadata
    }

    private static func decodeDouble(
        _ container: KeyedDecodingContainer<CodingKeys>,
        forKey key: CodingKeys
    ) throws -> Double {
        if let value = try? container.decode(Double.self, forKey: key) {
            return value
        }
        if let value = try? container.decode(Int.self, forKey: key) {
            return Double(value)
        }
        throw DecodingError.typeMismatch(
            Double.self,
            DecodingError.Context(codingPath: container.codingPath, debugDescription: "Expected number for \(key.stringValue)")
        )
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        calories = try container.decode(Int.self, forKey: .calories)
        protein = try Self.decodeDouble(container, forKey: .protein)
        carbs = try Self.decodeDouble(container, forKey: .carbs)
        fat = try Self.decodeDouble(container, forKey: .fat)
        timestamp = try container.decode(Date.self, forKey: .timestamp)

        // Prefer filename (new format). Fall back to inline bytes (legacy rows).
        // FoodStore.loadEntries() migrates legacy rows to disk on first load so
        // subsequent saves shed the inline bytes and fit under the 4 MiB cap.
        imageFilename = try container.decodeIfPresent(String.self, forKey: .imageFilename)
        if let filename = imageFilename {
            imageData = FoodImageStore.shared.load(filename: filename)
        } else {
            imageData = try container.decodeIfPresent(Data.self, forKey: .imageData)
        }
        additionalImageFilenames = try container.decodeIfPresent([String].self, forKey: .additionalImageFilenames) ?? []
        additionalImageData = additionalImageFilenames.compactMap { FoodImageStore.shared.load(filename: $0) }

        emoji = try container.decodeIfPresent(String.self, forKey: .emoji)
        source = try container.decode(FoodSource.self, forKey: .source)
        mealType = try container.decodeIfPresent(MealType.self, forKey: .mealType) ?? .other
        sugar = try container.decodeIfPresent(Double.self, forKey: .sugar)
        addedSugar = try container.decodeIfPresent(Double.self, forKey: .addedSugar)
        fiber = try container.decodeIfPresent(Double.self, forKey: .fiber)
        saturatedFat = try container.decodeIfPresent(Double.self, forKey: .saturatedFat)
        monounsaturatedFat = try container.decodeIfPresent(Double.self, forKey: .monounsaturatedFat)
        polyunsaturatedFat = try container.decodeIfPresent(Double.self, forKey: .polyunsaturatedFat)
        cholesterol = try container.decodeIfPresent(Double.self, forKey: .cholesterol)
        caffeine = try container.decodeIfPresent(Double.self, forKey: .caffeine)
        supplementalNutrients = try container.decodeIfPresent([String: Double].self, forKey: .supplementalNutrients) ?? [:]
        sodium = try container.decodeIfPresent(Double.self, forKey: .sodium)
        potassium = try container.decodeIfPresent(Double.self, forKey: .potassium)
        transFat = try container.decodeIfPresent(Double.self, forKey: .transFat)
        calcium = try container.decodeIfPresent(Double.self, forKey: .calcium)
        iron = try container.decodeIfPresent(Double.self, forKey: .iron)
        magnesium = try container.decodeIfPresent(Double.self, forKey: .magnesium)
        zinc = try container.decodeIfPresent(Double.self, forKey: .zinc)
        vitaminA = try container.decodeIfPresent(Double.self, forKey: .vitaminA)
        vitaminC = try container.decodeIfPresent(Double.self, forKey: .vitaminC)
        vitaminD = try container.decodeIfPresent(Double.self, forKey: .vitaminD)
        vitaminB12 = try container.decodeIfPresent(Double.self, forKey: .vitaminB12)
        vitaminE = try container.decodeIfPresent(Double.self, forKey: .vitaminE)
        vitaminK = try container.decodeIfPresent(Double.self, forKey: .vitaminK)
        folate = try container.decodeIfPresent(Double.self, forKey: .folate)
        omega3 = try container.decodeIfPresent(Double.self, forKey: .omega3)
        servingSizeGrams = try container.decodeIfPresent(Double.self, forKey: .servingSizeGrams)
        servingUnitOptions = try container.decodeIfPresent([ServingUnitOption].self, forKey: .servingUnitOptions) ?? []
        selectedServingUnit = try container.decodeIfPresent(String.self, forKey: .selectedServingUnit)
        selectedServingQuantity = try container.decodeIfPresent(Double.self, forKey: .selectedServingQuantity)
        customNote = try container.decodeIfPresent(String.self, forKey: .customNote)
        progressiveMeal = try container.decodeIfPresent(Bool.self, forKey: .progressiveMeal) ?? false
        ingredients = try container.decodeIfPresent([MealIngredient].self, forKey: .ingredients) ?? []
        productMetadata = try container.decodeIfPresent(FoodProductMetadata.self, forKey: .productMetadata)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(calories, forKey: .calories)
        try container.encode(protein, forKey: .protein)
        try container.encode(carbs, forKey: .carbs)
        try container.encode(fat, forKey: .fat)
        try container.encode(timestamp, forKey: .timestamp)
        // Persist ONLY the filename — never the raw bytes. This is the fix for
        // the silent 4 MiB UserDefaults cap that was dropping adds/deletes.
        try container.encodeIfPresent(imageFilename, forKey: .imageFilename)
        if !additionalImageFilenames.isEmpty {
            try container.encode(additionalImageFilenames, forKey: .additionalImageFilenames)
        }
        try container.encodeIfPresent(emoji, forKey: .emoji)
        try container.encode(source, forKey: .source)
        try container.encode(mealType, forKey: .mealType)
        try container.encodeIfPresent(sugar, forKey: .sugar)
        try container.encodeIfPresent(addedSugar, forKey: .addedSugar)
        try container.encodeIfPresent(fiber, forKey: .fiber)
        try container.encodeIfPresent(saturatedFat, forKey: .saturatedFat)
        try container.encodeIfPresent(monounsaturatedFat, forKey: .monounsaturatedFat)
        try container.encodeIfPresent(polyunsaturatedFat, forKey: .polyunsaturatedFat)
        try container.encodeIfPresent(cholesterol, forKey: .cholesterol)
        try container.encodeIfPresent(caffeine, forKey: .caffeine)
        if !supplementalNutrients.isEmpty {
            try container.encode(supplementalNutrients, forKey: .supplementalNutrients)
        }
        try container.encodeIfPresent(sodium, forKey: .sodium)
        try container.encodeIfPresent(potassium, forKey: .potassium)
        try container.encodeIfPresent(transFat, forKey: .transFat)
        try container.encodeIfPresent(calcium, forKey: .calcium)
        try container.encodeIfPresent(iron, forKey: .iron)
        try container.encodeIfPresent(magnesium, forKey: .magnesium)
        try container.encodeIfPresent(zinc, forKey: .zinc)
        try container.encodeIfPresent(vitaminA, forKey: .vitaminA)
        try container.encodeIfPresent(vitaminC, forKey: .vitaminC)
        try container.encodeIfPresent(vitaminD, forKey: .vitaminD)
        try container.encodeIfPresent(vitaminB12, forKey: .vitaminB12)
        try container.encodeIfPresent(vitaminE, forKey: .vitaminE)
        try container.encodeIfPresent(vitaminK, forKey: .vitaminK)
        try container.encodeIfPresent(folate, forKey: .folate)
        try container.encodeIfPresent(omega3, forKey: .omega3)
        try container.encodeIfPresent(servingSizeGrams, forKey: .servingSizeGrams)
        if !servingUnitOptions.isEmpty {
            try container.encode(servingUnitOptions, forKey: .servingUnitOptions)
        }
        try container.encodeIfPresent(selectedServingUnit, forKey: .selectedServingUnit)
        try container.encodeIfPresent(selectedServingQuantity, forKey: .selectedServingQuantity)
        try container.encodeIfPresent(customNote, forKey: .customNote)
        if progressiveMeal {
            try container.encode(true, forKey: .progressiveMeal)
        }
        if !ingredients.isEmpty {
            try container.encode(ingredients, forKey: .ingredients)
        }
        try container.encodeIfPresent(productMetadata, forKey: .productMetadata)
    }

    var timeString: String {
        DateFormatter.localizedString(from: timestamp, dateStyle: .none, timeStyle: .short)
    }

    /// Unique key for favorite deduplication (name + calorie combo)
    var favoriteKey: String {
        "\(name.lowercased())|\(calories)"
    }

    var allImageData: [Data] {
        var result: [Data] = []
        var seen = Set<String>()
        if let data = imageData ?? imageFilename.flatMap({ FoodImageStore.shared.load(filename: $0) }) {
            result.append(data)
            if let imageFilename { seen.insert(imageFilename) }
        }
        for index in 0..<max(additionalImageData.count, additionalImageFilenames.count) {
            let filename = additionalImageFilenames.indices.contains(index) ? additionalImageFilenames[index] : nil
            if let filename, seen.contains(filename) { continue }
            let data = additionalImageData.indices.contains(index) ? additionalImageData[index] : filename.flatMap { FoodImageStore.shared.load(filename: $0) }
            if let data {
                result.append(data)
                if let filename { seen.insert(filename) }
            }
        }
        for filename in ingredients.flatMap(\.allImageFilenames) where seen.insert(filename).inserted {
            if let data = FoodImageStore.shared.load(filename: filename) { result.append(data) }
        }
        return result
    }

    nonisolated var allImageFilenames: [String] {
        var seen = Set<String>()
        return ((imageFilename.map { [$0] } ?? []) + additionalImageFilenames + ingredients.flatMap(\.allImageFilenames))
            .filter { seen.insert($0).inserted }
    }

    /// New entry for the given log date (new id), copying nutrition and media from this entry.
    /// Uses current time's meal type by default.
    func duplicatedForLogging(at logDate: Date, mealType: MealType = .currentMeal) -> FoodEntry {
        let resolvedMealType = mealType
        return FoodEntry(
            name: name,
            calories: calories,
            protein: protein,
            carbs: carbs,
            fat: fat,
            timestamp: logDate,
            imageData: imageData,
            imageFilename: nil,  // new id → new filename will be assigned on save
            additionalImageData: additionalImageData,
            additionalImageFilenames: [],
            emoji: emoji,
            source: source,
            mealType: resolvedMealType,
            sugar: sugar,
            addedSugar: addedSugar,
            fiber: fiber,
            saturatedFat: saturatedFat,
            monounsaturatedFat: monounsaturatedFat,
            polyunsaturatedFat: polyunsaturatedFat,
            cholesterol: cholesterol,
            caffeine: caffeine,
            supplementalNutrients: supplementalNutrients,
            sodium: sodium,
            potassium: potassium,
            transFat: transFat,
            calcium: calcium,
            iron: iron,
            magnesium: magnesium,
            zinc: zinc,
            vitaminA: vitaminA,
            vitaminC: vitaminC,
            vitaminD: vitaminD,
            vitaminB12: vitaminB12,
            vitaminE: vitaminE,
            vitaminK: vitaminK,
            folate: folate,
            omega3: omega3,
            servingSizeGrams: servingSizeGrams,
            servingUnitOptions: servingUnitOptions,
            selectedServingUnit: selectedServingUnit,
            selectedServingQuantity: selectedServingQuantity,
            customNote: customNote,
            progressiveMeal: progressiveMeal,
            ingredients: ingredients,
            productMetadata: productMetadata
        )
    }
}

extension FoodEntry {
    /// One ingredient line for combine / add-ingredient flows (nested ingredients stay collapsed).
    nonisolated func asMealIngredient() -> MealIngredient {
        MealIngredient(
            name: name,
            grams: servingSizeGrams.flatMap { $0.isFinite && $0 > 0 ? $0 : nil } ?? 0,
            calories: calories,
            protein: protein,
            carbs: carbs,
            fat: fat,
            imageFilename: allImageFilenames.first,
            additionalImageFilenames: Array(allImageFilenames.dropFirst()),
            emoji: emoji
        )
    }

    /// Recompute parent macros from an ingredient list.
    nonisolated func withIngredients(_ ingredients: [MealIngredient]) -> FoodEntry {
        let totals = ingredients.ingredientTotals
        return FoodEntry(
            id: id,
            name: name,
            calories: totals.calories,
            protein: totals.protein,
            carbs: totals.carbs,
            fat: totals.fat,
            timestamp: timestamp,
            imageData: imageData,
            imageFilename: imageFilename,
            additionalImageData: additionalImageData,
            additionalImageFilenames: additionalImageFilenames,
            emoji: emoji,
            source: source,
            mealType: mealType,
            sugar: sugar,
            addedSugar: addedSugar,
            fiber: fiber,
            saturatedFat: saturatedFat,
            monounsaturatedFat: monounsaturatedFat,
            polyunsaturatedFat: polyunsaturatedFat,
            cholesterol: cholesterol,
            caffeine: caffeine,
            supplementalNutrients: supplementalNutrients,
            sodium: sodium,
            potassium: potassium,
            transFat: transFat,
            calcium: calcium,
            iron: iron,
            magnesium: magnesium,
            zinc: zinc,
            vitaminA: vitaminA,
            vitaminC: vitaminC,
            vitaminD: vitaminD,
            vitaminB12: vitaminB12,
            vitaminE: vitaminE,
            vitaminK: vitaminK,
            folate: folate,
            omega3: omega3,
            servingSizeGrams: totals.grams > 0 ? totals.grams : servingSizeGrams,
            servingUnitOptions: [],
            selectedServingUnit: nil,
            selectedServingQuantity: nil,
            customNote: customNote,
            progressiveMeal: progressiveMeal,
            ingredients: ingredients,
            productMetadata: productMetadata
        )
    }
}

enum CombinedMeal {
    nonisolated static func combinedName(for entries: [FoodEntry]) -> String {
        let names = entries.map { $0.name.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        if names.isEmpty { return "Combined meal" }
        if names.count <= 3 { return names.joined(separator: " + ") }
        return names.prefix(2).joined(separator: " + ") + " + \(names.count - 2) more"
    }

    nonisolated static func combine(_ entries: [FoodEntry]) -> FoodEntry {
        precondition(entries.count >= 2, "Combine requires at least two food entries")
        let ingredients = entries.map { $0.asMealIngredient() }
        let totals = ingredients.ingredientTotals
        let latest = entries.max(by: { $0.timestamp < $1.timestamp }) ?? entries[0]
        var seen = Set<String>()
        let filenames = entries.flatMap(\.allImageFilenames).filter { seen.insert($0).inserted }
        return FoodEntry(
            name: combinedName(for: entries),
            calories: totals.calories,
            protein: totals.protein,
            carbs: totals.carbs,
            fat: totals.fat,
            timestamp: latest.timestamp,
            imageFilename: filenames.first,
            additionalImageFilenames: Array(filenames.dropFirst()),
            emoji: entries.compactMap(\.emoji).first,
            source: .manual,
            mealType: latest.mealType,
            servingSizeGrams: totals.grams > 0 ? totals.grams : nil,
            ingredients: ingredients
        )
    }
}
