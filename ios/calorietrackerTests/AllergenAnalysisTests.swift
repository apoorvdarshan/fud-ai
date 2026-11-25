import Foundation
import Foundation
import Testing
@testable import calorietracker

struct AllergenAnalysisTests {
    @Test func canonicalIDsSurviveParsingAndBackupAsPossibleAllergens() throws {
        let analysis = try GeminiService.parseFoodAnalysis(from: """
        {"name":"Pokrm","calories":160,"protein":4,"carbs":27,"fat":4,"serving_size_grams":100,
         "allergens":["milk","MILK",17,"mléko","not-an-allergen"],
         "ingredients":[{"name":"Příloha","grams":100,"calories":160,"protein":4,"carbs":27,"fat":4,"allergens":["sesame"]}]}
        """)
        #expect(analysis.aiAllergens == ["milk"])
        #expect(analysis.ingredients.first?.aiAllergens == ["sesame"])
        let entry = FoodEntry(name: analysis.name, calories: analysis.calories, protein: analysis.protein,
                              carbs: analysis.carbs, fat: analysis.fat, source: .textInput,
                              ingredients: analysis.ingredients, aiAllergens: analysis.aiAllergens)
        let restored = try JSONDecoder().decode(FoodEntry.self, from: JSONEncoder().encode(entry))
        let result = restored.allergenAnalysis(for: ["mlékem", "芝麻"])
        #expect(result.assessment == .possibleAllergen)
        #expect(result.matchedSensitivities == ["mlékem", "芝麻"])
        #expect(restored.allergenAnalysis(for: ["بيض"]).assessment == .unableToAssess)
    }

    @Test func labelAllergenSurvivesServingScale() throws {
        let analysis = try GeminiService.parseNutritionLabel(from: """
        {"name":"Product","calories_per_100g":50,"protein_per_100g":3,"carbs_per_100g":4,"fat_per_100g":1,"allergens":["soy"]}
        """)
        #expect(analysis.scaled(to: 60).aiAllergens == ["soy"])
    }
    @Test
    func matchesCanonicalAllergensAcrossReviewedLanguages() {
        #expect(AllergenAnalysis.mentionsAny(of: ["mléko"], in: "en:milk"))
        #expect(AllergenAnalysis.mentionsAny(of: ["milk"], in: "молоко"))
        #expect(!AllergenAnalysis.mentionsAny(of: ["milk"], in: "oats"))
    }
    @Test
    func russianEggMatchesCanonicalLabelInBothDirections() {
        // Normalization folds й to и, so the alias table must use folded keys too.
        #expect(AllergenAnalysis.mentionsAny(of: ["яйцо"], in: "en:egg"))
        #expect(AllergenAnalysis.mentionsAny(of: ["egg"], in: "яйцо"))
        #expect(AllergenAnalysis.mentionsAny(of: ["яйцо"], in: "egg"))
    }

    @Test
    func declaredAllergenWinsOverWeakerEvidence() {
        let entry = makeEntry(metadata: FoodProductMetadata(
            barcode: "1",
            packageQuantity: nil,
            ingredientsText: "oats",
            allergens: ["Milk"],
            traces: ["Nuts"],
            nutriScore: nil,
            novaGroup: nil,
            ecoScore: nil,
            labels: [],
            categories: [],
            imageURL: nil
        ))

        let result = entry.allergenAnalysis(for: ["milk", "nuts"])

        #expect(result.assessment == .declaredAllergenMatch)
        #expect(result.matchedSensitivities == ["milk"])
    }

    @Test
    func tracesAndIngredientNamesAreNotReportedAsSafe() {
        let traceEntry = makeEntry(metadata: FoodProductMetadata(
            barcode: "1", packageQuantity: nil, ingredientsText: nil,
            allergens: [], traces: ["Peanuts"], nutriScore: nil, novaGroup: nil,
            ecoScore: nil, labels: [], categories: [], imageURL: nil
        ))
        #expect(traceEntry.allergenAnalysis(for: ["peanut"]).assessment == .mayContain)

        let ingredientEntry = makeEntry(ingredients: [
            MealIngredient(id: UUID(), name: "Sesame seeds", grams: 1, calories: 1, protein: 0, carbs: 0, fat: 0)
        ])
        #expect(ingredientEntry.allergenAnalysis(for: ["sesame"]).assessment == .possibleAllergen)
    }

    @Test
    func missingEvidenceIsUnableToAssess() {
        #expect(makeEntry().allergenAnalysis(for: ["milk"]).assessment == .unableToAssess)
    }

    @Test
    func profileSensitivitiesRoundTripThroughLocalJson() throws {
        var profile = UserProfile.default
        profile.allergenSensitivities = ["milk", "peanuts"]
        let data = try JSONEncoder().encode(profile)
        let restored = try JSONDecoder().decode(UserProfile.self, from: data)
        #expect(restored.configuredAllergenSensitivities == ["milk", "peanuts"])
    }

    private func makeEntry(
        metadata: FoodProductMetadata? = nil,
        ingredients: [MealIngredient] = []
    ) -> FoodEntry {
        FoodEntry(
            name: "Plain meal",
            calories: 100,
            protein: 1,
            carbs: 10,
            fat: 1,
            source: .manual,
            ingredients: ingredients,
            productMetadata: metadata
        )
    }
}
