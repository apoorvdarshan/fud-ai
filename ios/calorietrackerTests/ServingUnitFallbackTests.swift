import Testing
import Foundation
@testable import calorietracker

struct ServingUnitFallbackTests {
    @Test func missingServingWeightRemainsUnknownInsteadOfBecomingOneHundredGrams() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: #"{"name":"Recovered meal","calories":420,"protein":30,"carbs":45,"fat":12}"#
        )

        #expect(!analysis.servingSizeIsKnown)
        #expect(analysis.servingSizeGrams == 1)
        #expect(analysis.selectedServingUnit == "serving")
        #expect(analysis.selectedServingQuantity == 1)
    }

    @Test func validNonemptyObjectArrayDoesNotRequireFallback() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: foodJSON(unitOptions: #"[{"unit":"slice","quantity":2,"grams_per_unit":60}]"#)
        )

        #expect(!analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.count == 1)
        #expect(analysis.servingUnitOptions.first?.unit == "slice")
        #expect(analysis.servingUnitOptions.first?.quantity == 2)
        #expect(analysis.servingUnitOptions.first?.gramsPerUnit == 60)
    }

    @Test func numericOneQuantityRemainsValid() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: foodJSON(unitOptions: #"[{"unit":"piece","quantity":1,"grams_per_unit":120}]"#)
        )

        #expect(!analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.first?.quantity == 1)
    }

    @Test func numericOneGramsPerUnitRemainsValid() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: foodJSON(unitOptions: #"[{"unit":"ml","quantity":120,"grams_per_unit":1}]"#)
        )

        #expect(!analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.first?.gramsPerUnit == 1)
    }

    @Test func validEmptyArrayDoesNotRequireFallback() throws {
        let analysis = try GeminiService.parseFoodAnalysis(from: foodJSON(unitOptions: "[]"))

        #expect(!analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.isEmpty)
    }

    @Test func legacyObjectArrayAliasDoesNotRequireFallback() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: foodJSON(
                unitOptions: #"[{"unit":"piece","quantity":2,"grams_per_unit":60}]"#,
                fieldName: "serving_unit_options"
            )
        )

        #expect(!analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.first?.unit == "piece")
    }

    @Test func legacyCamelCaseGramsPerUnitDoesNotRequireFallback() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: foodJSON(unitOptions: #"[{"unit":"slice","quantity":2,"gramsPerUnit":60}]"#)
        )

        #expect(!analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.first?.gramsPerUnit == 60)
    }

    @Test func stringArrayRequiresFallback() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: foodJSON(unitOptions: #"["slice"]"#)
        )

        #expect(analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.isEmpty)
    }

    @Test func missingFieldRequiresFallback() throws {
        let analysis = try GeminiService.parseFoodAnalysis(from: foodJSON(unitOptions: nil))

        #expect(analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.isEmpty)
    }

    @Test func incompleteObjectRequiresFallback() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: foodJSON(unitOptions: #"[{"unit":"slice","grams_per_unit":120}]"#)
        )

        #expect(analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.isEmpty)
    }

    @Test func booleanQuantityRequiresFallback() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: foodJSON(unitOptions: #"[{"unit":"piece","quantity":true,"grams_per_unit":120}]"#)
        )

        #expect(analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.isEmpty)
    }

    @Test func booleanGramsPerUnitRequiresFallback() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: foodJSON(unitOptions: #"[{"unit":"piece","quantity":1,"grams_per_unit":false}]"#)
        )

        #expect(analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.isEmpty)
    }

    @Test func inconsistentServingWeightRequiresFallback() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: foodJSON(unitOptions: #"[{"unit":"slice","quantity":2,"grams_per_unit":40}]"#)
        )

        #expect(analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.isEmpty)
    }

    @Test func nutritionLabelValidEmptyDoesNotRequireFallback() throws {
        let analysis = try GeminiService.parseNutritionLabel(
            from: nutritionLabelJSON(unitOptions: "[]")
        )

        #expect(!analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.isEmpty)
    }

    @Test func nutritionLabelStringArrayRequiresFallback() throws {
        let analysis = try GeminiService.parseNutritionLabel(
            from: nutritionLabelJSON(unitOptions: #"["slice"]"#)
        )

        #expect(analysis.requiresServingUnitFallback)
        #expect(analysis.servingUnitOptions.isEmpty)
    }

    @Test func parsesValidIngredientsAndSkipsMalformedItems() throws {
        let analysis = try GeminiService.parseFoodAnalysis(from: """
        {"name":"Yogurt Bowl","calories":300,"protein":25,"carbs":35,"fat":7,"serving_size_grams":350,"unit_options":[],
         "ingredients":[
           {"name":"Greek yogurt","grams":200,"calories":150,"protein":20,"carbs":8,"fat":2},
           {"name":"Berries","grams":100,"calories":50,"protein":1,"carbs":12,"fat":0},
           {"name":"Broken","grams":0,"calories":10,"protein":0,"carbs":0,"fat":0}
         ]}
        """)

        #expect(analysis.ingredients.count == 2)
        #expect(analysis.ingredients.first?.name == "Greek yogurt")
        #expect(analysis.ingredients.first?.grams == 200)
        #expect(analysis.calories == 200)
        #expect(analysis.protein == 21)
        #expect(analysis.carbs == 20)
        #expect(analysis.fat == 2)
    }

    @Test func parseFoodKeepsMealMacrosWhenIngredientsAreMissing() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: #"{"name":"Banana","calories":105,"protein":1,"carbs":27,"fat":0.4,"serving_size_grams":118}"#
        )
        #expect(analysis.calories == 105)
        #expect(analysis.ingredients.isEmpty)
    }

    @Test func ingredientScalingAndTotalsRecalculateMacros() {
        let ingredients = [
            MealIngredient(name: "Rice", grams: 150, calories: 195, protein: 4, carbs: 42, fat: 0.5),
            MealIngredient(name: "Chicken", grams: 100, calories: 165, protein: 31, carbs: 0, fat: 3.6),
        ].map { $0.scaled(by: 0.5) }
        let totals = ingredients.ingredientTotals

        #expect(totals.grams == 125)
        #expect(totals.calories == 181)
        #expect(totals.protein == 17.5)
    }

    @Test func oldFoodEntryWithoutIngredientsStillDecodes() throws {
        let entry = FoodEntry(name: "Apple", calories: 95, protein: 0.5, carbs: 25, fat: 0.3, source: .manual)
        let data = try JSONEncoder().encode(entry)
        let decoded = try JSONDecoder().decode(FoodEntry.self, from: data)

        #expect(decoded.ingredients.isEmpty)
        #expect(decoded.name == "Apple")
        #expect(decoded.progressiveMeal == false)
    }

    @Test func progressiveMealPromptUsesChronologicalScaleDifferences() {
        let prompt = GeminiService.multiPhotoAnalysisPrompt(
            progressiveMeal: true,
            description: "The plate stays on the scale"
        )

        #expect(prompt.contains("chronological progressive-meal sequence"))
        #expect(prompt.contains("current scale total minus the previous scale total"))
        #expect(prompt.contains("The plate stays on the scale"))
        #expect(!prompt.contains("Treat the photos as multiple views"))
    }

    @Test func progressiveMealModeSurvivesFoodEntryRoundTrip() throws {
        let original = FoodEntry(
            name: "Progressive bowl",
            calories: 400,
            protein: 30,
            carbs: 45,
            fat: 12,
            source: .snapFood,
            progressiveMeal: true
        )

        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(FoodEntry.self, from: data)

        #expect(decoded.progressiveMeal)
    }

    @Test func productMetadataSurvivesFoodEntryRoundTripAndDuplication() throws {
        let metadata = FoodProductMetadata(
            barcode: "3017620422003",
            packageQuantity: "400 g",
            ingredientsText: "Sugar, palm oil, hazelnuts",
            allergens: ["Milk", "Hazelnuts"],
            traces: ["Soy"],
            nutriScore: "E",
            novaGroup: 4,
            ecoScore: "D",
            labels: ["Vegetarian"],
            categories: ["Spreads"],
            imageURL: URL(string: "https://images.openfoodfacts.org/product.jpg")
        )
        let original = FoodEntry(
            name: "Hazelnut spread",
            calories: 539,
            protein: 6.3,
            carbs: 57.5,
            fat: 30.9,
            source: .barcode,
            productMetadata: metadata
        )

        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(FoodEntry.self, from: data)
        let duplicated = decoded.duplicatedForLogging(at: Date(timeIntervalSince1970: 1_700_000_000))

        #expect(decoded.productMetadata == metadata)
        #expect(duplicated.productMetadata == metadata)
    }

    @Test func standardMultiPhotoPromptKeepsMultipleViewBehavior() {
        let prompt = GeminiService.multiPhotoAnalysisPrompt(progressiveMeal: false)

        #expect(prompt.contains("Treat the photos as multiple views"))
        #expect(!prompt.contains("chronological progressive-meal sequence"))
    }

    private func foodJSON(unitOptions: String?, fieldName: String = "unit_options") -> String {
        let unitOptionsField = unitOptions.map { ",\"\(fieldName)\":\($0)" } ?? ""
        return """
        {"name":"Pizza","calories":240,"protein":10,"carbs":32,"fat":8,"serving_size_grams":120\(unitOptionsField)}
        """
    }

    private func nutritionLabelJSON(unitOptions: String) -> String {
        """
        {"name":"Pizza","calories_per_100g":200,"protein_per_100g":8,"carbs_per_100g":27,"fat_per_100g":7,"serving_size_grams":120,"unit_options":\(unitOptions)}
        """
    }
}
