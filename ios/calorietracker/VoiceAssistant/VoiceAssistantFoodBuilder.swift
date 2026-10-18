import Foundation
import UIKit

/// Builds diary entries from assistant drafts the same way the Review Food
/// screen does, so a voice-logged meal matches one logged by hand.
enum VoiceAssistantFoodBuilder {
    /// A diary entry for `analysis` at `servings` times its analyzed amount.
    static func entry(
        from analysis: GeminiService.FoodAnalysis,
        images: [UIImage],
        source: FoodSource,
        servings: Double,
        timestamp: Date,
        mealType: MealType
    ) -> FoodEntry {
        // Review Food shows the ingredient totals as the meal when there is a breakdown.
        let base = analysis.withIngredientMacroTotals()
        let factor = servings
        func scaled(_ value: Double?) -> Double? {
            value.map { round($0 * factor * 10) / 10 }
        }

        let known = base.servingSizeIsKnown
        let grams = base.servingSizeGrams
        let options = known ? ServingUnitOption.normalizedOptions(base.servingUnitOptions, totalGrams: grams) : []
        let preferGrams = known && FoodMeasurementSettings.preferGramsByDefault
        let unitID = ServingUnitOption.initialUnitID(
            preferredUnit: preferGrams ? nil : base.selectedServingUnit,
            options: options,
            defaultToGrams: preferGrams
        )
        let option = ServingUnitOption.option(matching: unitID, in: options)
        let quantity: Double = {
            if let selected = base.selectedServingQuantity, selected > 0, !option.isGramUnit { return selected }
            return option.gramsPerUnit > 0 ? grams / option.gramsPerUnit : grams
        }()

        return FoodEntry(
            name: base.name,
            calories: Int(round(Double(base.calories) * factor)),
            protein: base.protein * factor,
            carbs: base.carbs * factor,
            fat: base.fat * factor,
            timestamp: timestamp,
            imageData: images.first?.jpegData(compressionQuality: 0.5),
            additionalImageData: images.dropFirst().compactMap { $0.jpegData(compressionQuality: 0.5) },
            emoji: base.emoji,
            source: source,
            mealType: mealType,
            sugar: scaled(base.sugar),
            addedSugar: scaled(base.addedSugar),
            fiber: scaled(base.fiber),
            saturatedFat: scaled(base.saturatedFat),
            monounsaturatedFat: scaled(base.monounsaturatedFat),
            polyunsaturatedFat: scaled(base.polyunsaturatedFat),
            cholesterol: scaled(base.cholesterol),
            caffeine: scaled(base.caffeine),
            supplementalNutrients: AminoAcidEstimates.roundedSupplementalNutrients(base.supplementalNutrients, scaledBy: factor),
            sodium: scaled(base.sodium),
            potassium: scaled(base.potassium),
            transFat: scaled(base.transFat),
            calcium: scaled(base.calcium),
            iron: scaled(base.iron),
            magnesium: scaled(base.magnesium),
            zinc: scaled(base.zinc),
            vitaminA: scaled(base.vitaminA),
            vitaminC: scaled(base.vitaminC),
            vitaminD: scaled(base.vitaminD),
            vitaminB12: scaled(base.vitaminB12),
            vitaminE: scaled(base.vitaminE),
            vitaminK: scaled(base.vitaminK),
            folate: scaled(base.folate),
            omega3: scaled(base.omega3),
            servingSizeGrams: known ? grams * factor : nil,
            servingUnitOptions: known ? options : [],
            selectedServingUnit: known ? (options.isEmpty ? nil : option.unit) : "serving",
            selectedServingQuantity: known ? (options.isEmpty ? nil : quantity * factor) : factor,
            progressiveMeal: base.progressiveMeal,
            ingredients: base.ingredients.map { $0.scaled(by: factor) },
            productMetadata: base.productMetadata
        )
    }

    /// The draft's lines for the model and the card: the ingredients of a meal,
    /// or the food itself.
    static func items(for analysis: GeminiService.FoodAnalysis) -> [[String: Any]] {
        let base = analysis.withIngredientMacroTotals()
        if !base.ingredients.isEmpty {
            return base.ingredients.map { ingredient in
                [
                    "name": ingredient.name,
                    "serving": ingredient.grams > 0 ? "\(MacroValueFormatter.string(ingredient.grams)) g" : "",
                    "calories": ingredient.calories,
                    "protein_g": rounded(ingredient.protein),
                    "carbs_g": rounded(ingredient.carbs),
                    "fat_g": rounded(ingredient.fat),
                ]
            }
        }
        return [[
            "name": base.name,
            "serving": servingText(for: base),
            "calories": base.calories,
            "protein_g": rounded(base.protein),
            "carbs_g": rounded(base.carbs),
            "fat_g": rounded(base.fat),
        ]]
    }

    static func totals(for analysis: GeminiService.FoodAnalysis) -> [String: Any] {
        let base = analysis.withIngredientMacroTotals()
        return [
            "calories": base.calories,
            "protein_g": rounded(base.protein),
            "carbs_g": rounded(base.carbs),
            "fat_g": rounded(base.fat),
        ]
    }

    /// "2 slices (120 g)", "250 g", or "1 serving" when the amount is unknown.
    static func servingText(for analysis: GeminiService.FoodAnalysis) -> String {
        guard analysis.servingSizeIsKnown, analysis.servingSizeGrams > 0 else { return "1 serving" }
        let grams = "\(MacroValueFormatter.string(analysis.servingSizeGrams)) g"
        if let unit = analysis.selectedServingUnit?.trimmingCharacters(in: .whitespacesAndNewlines), !unit.isEmpty,
           !["g", "gram", "grams"].contains(unit.lowercased()),
           let quantity = analysis.selectedServingQuantity, quantity > 0 {
            return "\(ServingUnitEditor.formatQuantity(quantity)) \(unit) (\(grams))"
        }
        return grams
    }

    static func servingText(for entry: FoodEntry) -> String {
        if let unit = entry.selectedServingUnit?.trimmingCharacters(in: .whitespacesAndNewlines), !unit.isEmpty,
           let quantity = entry.selectedServingQuantity, quantity > 0 {
            let amount = "\(ServingUnitEditor.formatQuantity(quantity)) \(unit)"
            if let grams = entry.servingSizeGrams, grams > 0, !["g", "gram", "grams"].contains(unit.lowercased()) {
                return "\(amount) (\(MacroValueFormatter.string(grams)) g)"
            }
            return amount
        }
        if let grams = entry.servingSizeGrams, grams > 0 {
            return "\(MacroValueFormatter.string(grams)) g"
        }
        return "1 serving"
    }

    static func rounded(_ value: Double) -> Double {
        (value * 10).rounded() / 10
    }
}

extension FoodEntry {
    /// A copy that keeps the id and photos, optionally renamed, rescaled by
    /// `factor` (servings) and moved to another time or meal.
    func voiceAssistantCopy(
        id: UUID? = nil,
        name: String? = nil,
        factor: Double = 1,
        timestamp: Date? = nil,
        mealType: MealType? = nil
    ) -> FoodEntry {
        func scaled(_ value: Double?) -> Double? {
            guard factor != 1 else { return value }
            return value.map { round($0 * factor * 10) / 10 }
        }
        return FoodEntry(
            id: id ?? self.id,
            name: name ?? self.name,
            calories: factor == 1 ? calories : Int(round(Double(calories) * factor)),
            protein: protein * factor,
            carbs: carbs * factor,
            fat: fat * factor,
            timestamp: timestamp ?? self.timestamp,
            imageData: imageData,
            imageFilename: imageFilename,
            additionalImageData: additionalImageData,
            additionalImageFilenames: additionalImageFilenames,
            emoji: emoji,
            source: source,
            mealType: mealType ?? self.mealType,
            sugar: scaled(sugar),
            addedSugar: scaled(addedSugar),
            fiber: scaled(fiber),
            saturatedFat: scaled(saturatedFat),
            monounsaturatedFat: scaled(monounsaturatedFat),
            polyunsaturatedFat: scaled(polyunsaturatedFat),
            cholesterol: scaled(cholesterol),
            caffeine: scaled(caffeine),
            supplementalNutrients: factor == 1
                ? supplementalNutrients
                : AminoAcidEstimates.roundedSupplementalNutrients(supplementalNutrients, scaledBy: factor),
            sodium: scaled(sodium),
            potassium: scaled(potassium),
            transFat: scaled(transFat),
            calcium: scaled(calcium),
            iron: scaled(iron),
            magnesium: scaled(magnesium),
            zinc: scaled(zinc),
            vitaminA: scaled(vitaminA),
            vitaminC: scaled(vitaminC),
            vitaminD: scaled(vitaminD),
            vitaminB12: scaled(vitaminB12),
            vitaminE: scaled(vitaminE),
            vitaminK: scaled(vitaminK),
            folate: scaled(folate),
            omega3: scaled(omega3),
            servingSizeGrams: servingSizeGrams.map { $0 * factor },
            servingUnitOptions: servingUnitOptions,
            selectedServingUnit: selectedServingUnit,
            selectedServingQuantity: selectedServingQuantity.map { $0 * factor },
            customNote: customNote,
            progressiveMeal: progressiveMeal,
            ingredients: factor == 1 ? ingredients : ingredients.map { $0.scaled(by: factor) },
            productMetadata: productMetadata
        )
    }
}
