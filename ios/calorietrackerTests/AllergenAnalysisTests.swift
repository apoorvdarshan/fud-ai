import Foundation
import Testing
@testable import calorietracker

struct AllergenAnalysisTests {
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
