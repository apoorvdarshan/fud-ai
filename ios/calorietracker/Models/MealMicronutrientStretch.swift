import Foundation

/// Meal-level micro stretch used when ingredients change but micros are not per-ingredient.
enum MealMicronutrientStretch {
    static func factor(oldGrams: Double, newGrams: Double) -> Double? {
        guard oldGrams > 0 else { return nil }
        if newGrams <= 0 { return 0 }
        return newGrams / oldGrams
    }

    static func scale(_ value: Double?, by factor: Double) -> Double? {
        value.map { $0 * factor }
    }

    static func scale(_ values: [String: Double], by factor: Double) -> [String: Double] {
        values.mapValues { $0 * factor }
    }
}

struct MealMicronutrientSnapshot: Equatable {
    var sugar: Double?
    var addedSugar: Double?
    var fiber: Double?
    var saturatedFat: Double?
    var monounsaturatedFat: Double?
    var polyunsaturatedFat: Double?
    var cholesterol: Double?
    var caffeine: Double?
    var supplementalNutrients: [String: Double]
    var sodium: Double?
    var potassium: Double?
    var transFat: Double?
    var calcium: Double?
    var iron: Double?
    var magnesium: Double?
    var zinc: Double?
    var vitaminA: Double?
    var vitaminC: Double?
    var vitaminD: Double?
    var vitaminB12: Double?
    var vitaminE: Double?
    var vitaminK: Double?
    var folate: Double?
    var omega3: Double?

    init(
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
        omega3: Double? = nil
    ) {
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
    }

    init(from entry: FoodEntry) {
        self.init(
            sugar: entry.sugar,
            addedSugar: entry.addedSugar,
            fiber: entry.fiber,
            saturatedFat: entry.saturatedFat,
            monounsaturatedFat: entry.monounsaturatedFat,
            polyunsaturatedFat: entry.polyunsaturatedFat,
            cholesterol: entry.cholesterol,
            caffeine: entry.caffeine,
            supplementalNutrients: entry.supplementalNutrients,
            sodium: entry.sodium,
            potassium: entry.potassium,
            transFat: entry.transFat,
            calcium: entry.calcium,
            iron: entry.iron,
            magnesium: entry.magnesium,
            zinc: entry.zinc,
            vitaminA: entry.vitaminA,
            vitaminC: entry.vitaminC,
            vitaminD: entry.vitaminD,
            vitaminB12: entry.vitaminB12,
            vitaminE: entry.vitaminE,
            vitaminK: entry.vitaminK,
            folate: entry.folate,
            omega3: entry.omega3
        )
    }

    func stretched(oldGrams: Double, newGrams: Double) -> MealMicronutrientSnapshot {
        guard let factor = MealMicronutrientStretch.factor(oldGrams: oldGrams, newGrams: newGrams) else {
            return self
        }
        return MealMicronutrientSnapshot(
            sugar: MealMicronutrientStretch.scale(sugar, by: factor),
            addedSugar: MealMicronutrientStretch.scale(addedSugar, by: factor),
            fiber: MealMicronutrientStretch.scale(fiber, by: factor),
            saturatedFat: MealMicronutrientStretch.scale(saturatedFat, by: factor),
            monounsaturatedFat: MealMicronutrientStretch.scale(monounsaturatedFat, by: factor),
            polyunsaturatedFat: MealMicronutrientStretch.scale(polyunsaturatedFat, by: factor),
            cholesterol: MealMicronutrientStretch.scale(cholesterol, by: factor),
            caffeine: MealMicronutrientStretch.scale(caffeine, by: factor),
            supplementalNutrients: MealMicronutrientStretch.scale(supplementalNutrients, by: factor),
            sodium: MealMicronutrientStretch.scale(sodium, by: factor),
            potassium: MealMicronutrientStretch.scale(potassium, by: factor),
            transFat: MealMicronutrientStretch.scale(transFat, by: factor),
            calcium: MealMicronutrientStretch.scale(calcium, by: factor),
            iron: MealMicronutrientStretch.scale(iron, by: factor),
            magnesium: MealMicronutrientStretch.scale(magnesium, by: factor),
            zinc: MealMicronutrientStretch.scale(zinc, by: factor),
            vitaminA: MealMicronutrientStretch.scale(vitaminA, by: factor),
            vitaminC: MealMicronutrientStretch.scale(vitaminC, by: factor),
            vitaminD: MealMicronutrientStretch.scale(vitaminD, by: factor),
            vitaminB12: MealMicronutrientStretch.scale(vitaminB12, by: factor),
            vitaminE: MealMicronutrientStretch.scale(vitaminE, by: factor),
            vitaminK: MealMicronutrientStretch.scale(vitaminK, by: factor),
            folate: MealMicronutrientStretch.scale(folate, by: factor),
            omega3: MealMicronutrientStretch.scale(omega3, by: factor)
        )
    }
}

extension FoodEntry {
    mutating func apply(_ snapshot: MealMicronutrientSnapshot) {
        sugar = snapshot.sugar
        addedSugar = snapshot.addedSugar
        fiber = snapshot.fiber
        saturatedFat = snapshot.saturatedFat
        monounsaturatedFat = snapshot.monounsaturatedFat
        polyunsaturatedFat = snapshot.polyunsaturatedFat
        cholesterol = snapshot.cholesterol
        caffeine = snapshot.caffeine
        supplementalNutrients = snapshot.supplementalNutrients
        sodium = snapshot.sodium
        potassium = snapshot.potassium
        transFat = snapshot.transFat
        calcium = snapshot.calcium
        iron = snapshot.iron
        magnesium = snapshot.magnesium
        zinc = snapshot.zinc
        vitaminA = snapshot.vitaminA
        vitaminC = snapshot.vitaminC
        vitaminD = snapshot.vitaminD
        vitaminB12 = snapshot.vitaminB12
        vitaminE = snapshot.vitaminE
        vitaminK = snapshot.vitaminK
        folate = snapshot.folate
        omega3 = snapshot.omega3
    }

    /// Review/Edit Food ingredient edits reset meal scale to 1.0, so micros must be rewritten.
    func applyingIngredientChanges(_ displayedIngredients: [MealIngredient]) -> FoodEntry {
        let totals = displayedIngredients.ingredientTotals
        let stretched = MealMicronutrientSnapshot(from: self)
            .stretched(oldGrams: ingredients.ingredientTotals.grams, newGrams: totals.grams)
        var next = self
        next.calories = totals.calories
        next.protein = totals.protein
        next.carbs = totals.carbs
        next.fat = totals.fat
        next.apply(stretched)
        next.servingSizeGrams = totals.grams > 0 ? totals.grams : servingSizeGrams
        next.servingUnitOptions = []
        next.selectedServingUnit = nil
        next.selectedServingQuantity = nil
        next.ingredients = displayedIngredients
        return next
    }
}
