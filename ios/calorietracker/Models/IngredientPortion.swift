import Foundation

/// Unrounded nutrition baseline used while typing a new ingredient weight.
struct IngredientPortion: Equatable, Sendable {
    var grams: Double
    var calories: Double
    var protein: Double
    var carbs: Double
    var fat: Double

    nonisolated init(grams: Double, calories: Double, protein: Double, carbs: Double, fat: Double) {
        self.grams = grams
        self.calories = calories
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
    }

    nonisolated func resized(to newGrams: Double) -> IngredientPortion? {
        guard grams.isFinite, grams > 0, newGrams.isFinite, newGrams > 0 else { return nil }
        let factor = newGrams / grams
        let result = IngredientPortion(grams: newGrams, calories: calories * factor,
                                      protein: protein * factor, carbs: carbs * factor, fat: fat * factor)
        guard [result.calories, result.protein, result.carbs, result.fat].allSatisfy({ $0.isFinite && $0 >= 0 && $0 < Double(Int32.max) }) else { return nil }
        return result
    }
}
