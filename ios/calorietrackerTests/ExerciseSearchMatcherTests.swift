import Foundation
import Testing
@testable import calorietracker

@Suite struct ExerciseSearchMatcherTests {
    private let tricepsPushdown = ExerciseLibraryItem(
        id: "Triceps_Pushdown",
        name: "Triceps Pushdown",
        rawLevel: "Beginner",
        force: "Push",
        mechanic: "Isolation",
        category: "Strength",
        rawEquipment: "Cable",
        primaryMuscles: ["Triceps"],
        instructions: ["Attach a bar to a high pulley."]
    )

    @Test func contiguousQueryMatches() {
        #expect(
            ExerciseSearchMatcher.matches(
                searchableText: tricepsPushdown.searchableText,
                query: "triceps pushdown",
                exerciseID: tricepsPushdown.id
            )
        )
    }

    @Test func tokenizedNonContiguousQueryMatches() {
        #expect(
            ExerciseSearchMatcher.matches(
                searchableText: tricepsPushdown.searchableText,
                query: "triceps cable pushdown",
                exerciseID: tricepsPushdown.id
            )
        )
    }

    @Test func aliasQueryMatches() {
        #expect(
            ExerciseSearchMatcher.matches(
                searchableText: tricepsPushdown.searchableText,
                query: "cable pushdown",
                exerciseID: tricepsPushdown.id
            )
        )
    }

    @Test func missingTokenFails() {
        #expect(
            !ExerciseSearchMatcher.matches(
                searchableText: tricepsPushdown.searchableText,
                query: "triceps rope",
                exerciseID: tricepsPushdown.id
            )
        )
    }

    @Test func emptyQueryMatchesEverything() {
        #expect(
            ExerciseSearchMatcher.matches(
                searchableText: tricepsPushdown.searchableText,
                query: "   ",
                exerciseID: tricepsPushdown.id
            )
        )
    }
}
