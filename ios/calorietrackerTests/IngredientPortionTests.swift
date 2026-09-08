import XCTest
@testable import calorietracker

final class IngredientPortionTests: XCTestCase {
    private let base = IngredientPortion(grams: 100, calories: 165, protein: 31, carbs: 2, fat: 3.6)

    func testDoublingAndHalvingScalesEveryNutritionValue() {
        XCTAssertEqual(base.resized(to: 200), IngredientPortion(grams: 200, calories: 330, protein: 62, carbs: 4, fat: 7.2))
        XCTAssertEqual(base.resized(to: 50), IngredientPortion(grams: 50, calories: 82.5, protein: 15.5, carbs: 1, fat: 1.8))
    }

    func testTypingThroughSmallWeightsDoesNotChangeTheBaseline() {
        _ = base.resized(to: 2)
        _ = base.resized(to: 20)
        XCTAssertEqual(base.resized(to: 100), base)
        XCTAssertEqual(base.resized(to: 200)?.calories, 330)
    }

    func testManualNutritionEditAtCurrentWeightBecomesNewBaseline() throws {
        var edited = try XCTUnwrap(base.resized(to: 200))
        edited.calories = 400
        edited.protein = 80
        XCTAssertEqual(edited.resized(to: 100), IngredientPortion(grams: 100, calories: 200, protein: 40, carbs: 2, fat: 3.6))
    }

    func testRejectsInvalidWeightsAndOverflow() {
        for weight in [0, -1, Double.nan, Double.infinity, Double.greatestFiniteMagnitude] {
            XCTAssertNil(base.resized(to: weight))
        }
        var invalid = base
        invalid.grams = 0
        XCTAssertNil(invalid.resized(to: 100))
        invalid = base
        invalid.protein = Double.greatestFiniteMagnitude
        XCTAssertNil(invalid.resized(to: 200))
    }
}
