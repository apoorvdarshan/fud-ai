import Testing
@testable import calorietracker

struct OptionalNutrientGoalsTests {
    @Test func customValueIsNotSnappedToPresetWheel() {
        let goals = OptionalNutrientGoals.defaults.settingGoal(17, for: .vitaminC)

        #expect(goals.goal(for: .vitaminC) == 17)
    }

    @Test func customValueUsesTechnicalBounds() {
        let tooHigh = OptionalNutrientGoals.defaults.settingGoal(1_000_000, for: .vitaminD)
        let negative = OptionalNutrientGoals.defaults.settingGoal(-1, for: .iron)

        #expect(tooHigh.goal(for: .vitaminD) == OptionalNutrientGoals.maximumCustomGoal)
        #expect(negative.goal(for: .iron) == 0)
    }

    @Test func vitaminDProvidesIUConversion() {
        #expect(OptionalNutrient.vitaminD.customValueDetail(for: 250) == "10000 IU")
        #expect(OptionalNutrient.vitaminC.customValueDetail(for: 250) == nil)
    }

    @Test func homeSelectionPreservesOneToFourChoices() {
        let choices: [HomeTopNutrient] = [.sodium, .fiber, .protein, .carbs]
        for count in 1...4 {
            let selection = Array(choices.prefix(count))
            #expect(HomeTopNutrient.selection(from: HomeTopNutrient.storageValue(for: selection)) == selection)
        }
        #expect(HomeTopNutrient.selection(from: "sodium,sodium,unknown") == [.sodium])
        #expect(HomeTopNutrient.selection(from: "") == HomeTopNutrient.defaultSelection)
        #expect(HomeTopNutrient.selection(from: "unknown") == HomeTopNutrient.defaultSelection)
    }
}
