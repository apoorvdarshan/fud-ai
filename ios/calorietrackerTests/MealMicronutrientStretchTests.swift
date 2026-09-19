import Testing
@testable import calorietracker

struct MealMicronutrientStretchTests {
    @Test func factorUsesNewOverOldWhenBothPositive() {
        #expect(MealMicronutrientStretch.factor(oldGrams: 100, newGrams: 200) == 2)
        #expect(MealMicronutrientStretch.factor(oldGrams: 200, newGrams: 100) == 0.5)
    }

    @Test func factorIsNilWhenOldOrNewGramsAreNotPositive() {
        #expect(MealMicronutrientStretch.factor(oldGrams: 0, newGrams: 120) == nil)
        #expect(MealMicronutrientStretch.factor(oldGrams: 120, newGrams: 0) == nil)
        #expect(MealMicronutrientStretch.factor(oldGrams: -10, newGrams: 50) == nil)
        #expect(MealMicronutrientStretch.factor(oldGrams: 50, newGrams: -10) == nil)
    }

    @Test func scaleDoublesAndHalvesOptionalValuesAndLeavesNils() {
        #expect(MealMicronutrientStretch.scale(10, by: 2) == 20)
        #expect(MealMicronutrientStretch.scale(10, by: 0.5) == 5)
        #expect(MealMicronutrientStretch.scale(nil, by: 2) == nil)
    }

    @Test func scaleStretchesSupplementalMapAndKeepsEmptyMaps() {
        let scaled = MealMicronutrientStretch.scale(
            ["creatine": 5, "beta_alanine": 3.2],
            by: 2
        )
        #expect(scaled["creatine"] == 10)
        #expect(scaled["beta_alanine"] == 6.4)
        #expect(MealMicronutrientStretch.scale([:], by: 2).isEmpty)
    }

    @Test func snapshotStretchesPresentMicrosAndLeavesNils() {
        let stretched = MealMicronutrientSnapshot(
            sugar: 8,
            fiber: 4,
            supplementalNutrients: ["creatine": 2],
            sodium: 200,
            vitaminC: 12,
            omega3: 1.5
        ).stretched(oldGrams: 100, newGrams: 50)

        #expect(stretched.sugar == 4)
        #expect(stretched.fiber == 2)
        #expect(stretched.sodium == 100)
        #expect(stretched.vitaminC == 6)
        #expect(stretched.omega3 == 0.75)
        #expect(stretched.supplementalNutrients["creatine"] == 1)
        #expect(stretched.addedSugar == nil)
        #expect(stretched.iron == nil)
    }

    @Test func snapshotSkipsStretchWhenOldOrNewGramsAreZero() {
        let original = MealMicronutrientSnapshot(sugar: 8, sodium: 200)
        #expect(original.stretched(oldGrams: 0, newGrams: 150) == original)
        #expect(original.stretched(oldGrams: 150, newGrams: 0) == original)
    }

    @Test func applyingIngredientChangesRewritesBaseMicrosByIngredientGramsRatio() {
        let rice = MealIngredient(name: "Rice", grams: 150, calories: 195, protein: 4, carbs: 42, fat: 0.5)
        let chicken = MealIngredient(name: "Chicken", grams: 100, calories: 165, protein: 31, carbs: 0, fat: 3.6)
        let entry = FoodEntry(
            name: "Bowl",
            calories: 360,
            protein: 35,
            carbs: 42,
            fat: 4.1,
            source: .snapFood,
            sugar: 6,
            fiber: 3,
            supplementalNutrients: ["creatine": 1],
            sodium: 400,
            vitaminA: 80,
            omega3: 0.4,
            servingSizeGrams: 250,
            ingredients: [rice, chicken]
        )

        let doubledChicken = chicken.scaled(by: 2)
        let updated = entry.applyingIngredientChanges([rice, doubledChicken])

        #expect(updated.ingredients.ingredientTotals.grams == 350)
        #expect(updated.calories == 195 + doubledChicken.calories)
        expectApproximately(updated.sugar, 8.4)
        expectApproximately(updated.fiber, 4.2)
        #expect(updated.sodium == 560)
        #expect(updated.vitaminA == 112)
        expectApproximately(updated.omega3, 0.56)
        expectApproximately(updated.supplementalNutrients["creatine"], 1.4)
        #expect(updated.servingSizeGrams == 350)
        #expect(updated.addedSugar == nil)
    }

    @Test func applyingIngredientChangesLeavesMicrosWhenOldOrNewGramsAreZero() {
        let chicken = MealIngredient(name: "Chicken", grams: 100, calories: 165, protein: 31, carbs: 0, fat: 3.6)
        let withoutIngredients = FoodEntry(
            name: "Bowl",
            calories: 360,
            protein: 35,
            carbs: 42,
            fat: 4.1,
            source: .manual,
            sugar: 6,
            sodium: 400,
            servingSizeGrams: 250
        )
        let added = withoutIngredients.applyingIngredientChanges([chicken])
        #expect(added.sugar == 6)
        #expect(added.sodium == 400)
        #expect(added.servingSizeGrams == 100)

        let emptied = added.applyingIngredientChanges([])
        #expect(emptied.sugar == 6)
        #expect(emptied.sodium == 400)
        #expect(emptied.servingSizeGrams == 100)
        #expect(emptied.calories == 0)
    }
}

private func expectApproximately(_ value: Double?, _ expected: Double, accuracy: Double = 0.0001) {
    guard let value else {
        Issue.record("expected \(expected), got nil")
        return
    }
    #expect(abs(value - expected) < accuracy)
}
