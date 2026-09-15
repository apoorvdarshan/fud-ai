import Foundation

/// Decides whether a food-analysis result that omitted or mangled `unit_options` is worth a second
/// "repair" round-trip to the model.
///
/// The repair call runs while the user is still staring at the analyzing sheet and roughly doubles
/// the wait (Flash-Lite frequently drops `unit_options`). It can only ever add non-gram serving
/// units — it never fixes macros — so when the primary response already has usable nutrition the
/// review sheet is shown immediately with gram-based portions and the repair is skipped. Only a
/// result with no macros at all (water, black coffee, zero-calorie drinks) gets the repair, because
/// there a volume unit like ml/cup is the whole point of the entry.
enum ServingUnitRepairPolicy {
    static func shouldRepair(
        requiresFallback: Bool,
        servingSizeIsKnown: Bool,
        calories: Double,
        protein: Double,
        carbs: Double,
        fat: Double
    ) -> Bool {
        guard requiresFallback, servingSizeIsKnown else { return false }
        let hasUsableMacros = calories > 0 || protein > 0 || carbs > 0 || fat > 0
        return !hasUsableMacros
    }

    static func shouldRepair(_ analysis: GeminiService.FoodAnalysis) -> Bool {
        shouldRepair(
            requiresFallback: analysis.requiresServingUnitFallback,
            servingSizeIsKnown: analysis.servingSizeIsKnown,
            calories: Double(analysis.calories),
            protein: analysis.protein,
            carbs: analysis.carbs,
            fat: analysis.fat
        )
    }

    static func shouldRepair(_ analysis: GeminiService.NutritionLabelAnalysis) -> Bool {
        shouldRepair(
            requiresFallback: analysis.requiresServingUnitFallback,
            servingSizeIsKnown: analysis.servingSizeGrams != nil,
            calories: analysis.caloriesPer100g,
            protein: analysis.proteinPer100g,
            carbs: analysis.carbsPer100g,
            fat: analysis.fatPer100g
        )
    }
}
