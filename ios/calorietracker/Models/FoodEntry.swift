import Foundation

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
        case .breakfast: "Breakfast"
        case .lunch: "Lunch"
        case .dinner: "Dinner"
        case .snack: "Snack"
        case .other: "Other"
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

    static var currentMeal: MealType {
        let hour = Calendar.current.component(.hour, from: .now)
        switch hour {
        case 5..<11: return .breakfast
        case 11..<15: return .lunch
        case 15..<21: return .dinner
        default: return .snack
        }
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

struct FoodEntry: Identifiable, Codable {
    let id: UUID
    var name: String
    var calories: Int
    var protein: Int
    var carbs: Int
    var fat: Int
    let timestamp: Date
    /// In-memory image bytes. NEVER persisted directly — see `imageFilename`.
    /// Kept as a property so existing views continue to read `entry.imageData`
    /// unchanged; the on-disk filename is the source of truth for persistence.
    var imageData: Data?
    /// Filename (not path) under Application Support/fudai-food-images/ where
    /// the JPEG lives. Tiny string; JSON-safe. The actual bytes live on disk
    /// to keep the foodEntries UserDefaults blob under iOS's 4 MiB cap.
    var imageFilename: String?
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
    var sodium: Double?         // milligrams
    var potassium: Double?      // milligrams
    var servingSizeGrams: Double? // grams (nil for old entries)
    var servingUnitOptions: [ServingUnitOption]
    var selectedServingUnit: String?
    var selectedServingQuantity: Double?

    init(
        id: UUID = UUID(),
        name: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
        timestamp: Date = Date(),
        imageData: Data? = nil,
        imageFilename: String? = nil,
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
        sodium: Double? = nil,
        potassium: Double? = nil,
        servingSizeGrams: Double? = nil,
        servingUnitOptions: [ServingUnitOption] = [],
        selectedServingUnit: String? = nil,
        selectedServingQuantity: Double? = nil
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
        self.sodium = sodium
        self.potassium = potassium
        self.servingSizeGrams = servingSizeGrams
        self.servingUnitOptions = servingUnitOptions
        self.selectedServingUnit = selectedServingUnit
        self.selectedServingQuantity = selectedServingQuantity
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, calories, protein, carbs, fat, timestamp
        case imageData     // legacy — old rows stored bytes inline; kept only for decode
        case imageFilename // current — filename on disk
        case emoji, source, mealType
        case sugar, addedSugar, fiber, saturatedFat
        case monounsaturatedFat, polyunsaturatedFat
        case cholesterol, sodium, potassium, servingSizeGrams
        case servingUnitOptions, selectedServingUnit, selectedServingQuantity
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        calories = try container.decode(Int.self, forKey: .calories)
        protein = try container.decode(Int.self, forKey: .protein)
        carbs = try container.decode(Int.self, forKey: .carbs)
        fat = try container.decode(Int.self, forKey: .fat)
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
        sodium = try container.decodeIfPresent(Double.self, forKey: .sodium)
        potassium = try container.decodeIfPresent(Double.self, forKey: .potassium)
        servingSizeGrams = try container.decodeIfPresent(Double.self, forKey: .servingSizeGrams)
        servingUnitOptions = try container.decodeIfPresent([ServingUnitOption].self, forKey: .servingUnitOptions) ?? []
        selectedServingUnit = try container.decodeIfPresent(String.self, forKey: .selectedServingUnit)
        selectedServingQuantity = try container.decodeIfPresent(Double.self, forKey: .selectedServingQuantity)
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
        try container.encodeIfPresent(sodium, forKey: .sodium)
        try container.encodeIfPresent(potassium, forKey: .potassium)
        try container.encodeIfPresent(servingSizeGrams, forKey: .servingSizeGrams)
        if !servingUnitOptions.isEmpty {
            try container.encode(servingUnitOptions, forKey: .servingUnitOptions)
        }
        try container.encodeIfPresent(selectedServingUnit, forKey: .selectedServingUnit)
        try container.encodeIfPresent(selectedServingQuantity, forKey: .selectedServingQuantity)
    }

    var timeString: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "h:mma"
        return formatter.string(from: timestamp).lowercased()
    }

    /// Unique key for favorite deduplication (name + calorie combo)
    var favoriteKey: String {
        "\(name.lowercased())|\(calories)"
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
            sodium: sodium,
            potassium: potassium,
            servingSizeGrams: servingSizeGrams,
            servingUnitOptions: servingUnitOptions,
            selectedServingUnit: selectedServingUnit,
            selectedServingQuantity: selectedServingQuantity
        )
    }
}
