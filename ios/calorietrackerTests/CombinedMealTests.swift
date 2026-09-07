import Foundation
import Testing
@testable import calorietracker

struct CombinedMealTests {
    @Test func asMealIngredientCollapsesNestedIngredients() {
        let entry = FoodEntry(
            name: "Bowl",
            calories: 500,
            protein: 30,
            carbs: 40,
            fat: 20,
            source: .manual,
            servingSizeGrams: 350,
            ingredients: [
                MealIngredient(name: "Rice", grams: 200, calories: 250, protein: 5, carbs: 50, fat: 1),
                MealIngredient(name: "Chicken", grams: 150, calories: 250, protein: 25, carbs: 0, fat: 10)
            ]
        )
        let ingredient = entry.asMealIngredient()
        #expect(ingredient.name == "Bowl")
        #expect(ingredient.grams == 350)
        #expect(ingredient.calories == 500)
        #expect(ingredient.protein == 30)
    }

    @Test func combineTotalsUsesLatestMealMetadata() {
        let older = FoodEntry(
            name: "Eggs",
            calories: 140,
            protein: 12,
            carbs: 1,
            fat: 10,
            timestamp: Date(timeIntervalSince1970: 1_000),
            source: .manual,
            mealType: .breakfast,
            servingSizeGrams: 100
        )
        let newer = FoodEntry(
            name: "Toast",
            calories: 120,
            protein: 4,
            carbs: 20,
            fat: 2,
            timestamp: Date(timeIntervalSince1970: 1_100),
            source: .textInput,
            mealType: .lunch,
            servingSizeGrams: 50
        )
        let combined = CombinedMeal.combine([older, newer])
        #expect(combined.name == "Eggs + Toast")
        #expect(combined.calories == 260)
        #expect(combined.protein == 16)
        #expect(combined.carbs == 21)
        #expect(combined.fat == 12)
        #expect(combined.servingSizeGrams == 150)
        #expect(combined.mealType == .lunch)
        #expect(combined.ingredients.count == 2)
    }

    @Test func withIngredientsRecomputesParentTotals() {
        let entry = FoodEntry(
            name: "Meal",
            calories: 0,
            protein: 0,
            carbs: 0,
            fat: 0,
            source: .manual
        )
        let updated = entry.withIngredients([
            MealIngredient(name: "A", grams: 50, calories: 80, protein: 5, carbs: 6, fat: 3),
            MealIngredient(name: "B", grams: 70, calories: 120, protein: 8, carbs: 10, fat: 4)
        ])
        #expect(updated.calories == 200)
        #expect(updated.protein == 13)
        #expect(updated.carbs == 16)
        #expect(updated.fat == 7)
        #expect(updated.servingSizeGrams == 120)
        #expect(updated.ingredients.count == 2)
    }
}
