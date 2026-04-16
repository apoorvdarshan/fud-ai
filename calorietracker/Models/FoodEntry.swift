import Foundation

enum FoodSource: String, Codable {
    case snapFood
    case nutritionLabel
    case textInput
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

struct FoodEntry: Identifiable, Codable {
    let id: UUID
    var name: String
    var calories: Int
    var protein: Int
    var carbs: Int
    var fat: Int
    let timestamp: Date
    var imageData: Data?
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

    init(
        id: UUID = UUID(),
        name: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
        timestamp: Date = Date(),
        imageData: Data? = nil,
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
        servingSizeGrams: Double? = nil
    ) {
        self.id = id
        self.name = name
        self.calories = calories
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
        self.timestamp = timestamp
        self.imageData = imageData
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
        imageData = try container.decodeIfPresent(Data.self, forKey: .imageData)
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
    func duplicatedForLogging(at logDate: Date, mealType: MealType = .currentMeal) -> FoodEntry {
        FoodEntry(
            name: name,
            calories: calories,
            protein: protein,
            carbs: carbs,
            fat: fat,
            timestamp: logDate,
            imageData: imageData,
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
            sodium: sodium,
            potassium: potassium,
            servingSizeGrams: servingSizeGrams
        )
    }
}
