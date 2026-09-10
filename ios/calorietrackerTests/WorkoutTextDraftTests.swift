import Foundation
import Testing
@testable import calorietracker

@MainActor
struct WorkoutTextDraftTests {
    private let library = [ExerciseLibraryItem(id: "Bench", name: "Bench press", category: "strength")]
    private let json = #"{"date":"2026-09-09","exercises":[{"exercise_id":"Bench","name":"invented display name","minutes":null,"unit":"lbs","sets":[{"weight":40.5,"reps":10},{"weight":null,"reps":8}]},{"exercise_id":null,"name":"Soccer","minutes":180,"unit":"kg","sets":[]}]}"#
    private var today: Date { StrengthWorkoutDate.date(for: "2026-09-10")! }

    @Test func candidateCatalogPrioritizesNamesAndBoundsContext() {
        let many = (1...100).map { ExerciseLibraryItem(id: "Squat_\($0)", name: "Squat \($0)") } + library
        let candidates = WorkoutTextDraft.candidates(description: "bench press 3 sets of 10", library: many)
        #expect(candidates.first?.id == "Bench")
        #expect(candidates.count == 60)
    }

    @Test func timedEffortIsPreserved() throws {
        var draft = try WorkoutTextDraft.parse(json, library: library, today: today)
        draft.exercises[1].intensity = "vigorous"
        #expect(try draft.planned(library: library, today: today)[1].timer?.intensity == .vigorous)
        draft.exercises[1].intensity = "unknown"
        #expect(throws: (any Error).self) { try draft.planned(library: library, today: today) }
    }

    @Test func mixedWorkoutPreservesDateUnitsAndSavedTime() throws {
        let draft = try WorkoutTextDraft.parse("```json\n\(json)\n```", library: library, today: today)
        let planned = try draft.planned(library: library, today: today)
        #expect(draft.date == "2026-09-09")
        #expect(planned[0].name == "Bench press")
        #expect(planned[0].sets[0].weight == "40.5")
        #expect(planned[0].sets[0].weightUnit == "lbs")
        #expect(planned[0].sets[1].weight.isEmpty)
        #expect(planned[1].timer?.savedDurationSeconds == 10800)
        #expect(planned[1].timer?.isRunning == false)
        #expect(planned[1].isCardio)
        #expect(try planned.map(\.id) == draft.planned(library: library, today: today).map(\.id))
    }

    @Test func rejectsInvalidResponses() {
        let cases = [
            json.replacingOccurrences(of: "\"Bench\"", with: "\"fake\""),
            json.replacingOccurrences(of: "2026-09-09", with: "2026-09-11"),
            json.replacingOccurrences(of: "2026-09-09", with: "2026-02-30"),
            json.replacingOccurrences(of: "180", with: "-20"),
            json.replacingOccurrences(of: "180", with: "1441"),
            json.replacingOccurrences(of: "40.5", with: "-1"),
            json.replacingOccurrences(of: "\"reps\":10", with: "\"reps\":0"),
            json.replacingOccurrences(of: "\"lbs\"", with: "\"stone\""),
            json.replacingOccurrences(of: "180", with: "null"), "{}", "not json"
        ]
        for input in cases {
            #expect(throws: (any Error).self) { try WorkoutTextDraft.parse(input, library: library, today: today) }
        }
    }

    @Test func clarificationDoesNotBecomeAWorkout() {
        do {
            _ = try WorkoutTextDraft.parse(#"{"question":"How many minutes?","exercises":[]}"#, library: library, today: today)
            Issue.record("Expected clarification")
        } catch { #expect(error.localizedDescription == "How many minutes?") }
    }

    @Test func customActivityRemainsAvailableAfterDeletingItsSource() throws {
        let name = "WorkoutCustomActivityTests.\(UUID())"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let store = StrengthWorkoutStore(defaults: defaults)
        let draft = WorkoutTextDraft(date: "2026-09-09", exercises: [WorkoutTextExercise(exerciseID: nil, name: "Soccer", minutes: "20")])
        try store.addTextWorkout(draft, library: [])
        try store.addTextWorkout(draft, library: [])
        let day = StrengthWorkoutDate.date(for: draft.date)!
        let entry = store.exercises(for: day)[0]
        store.toggleSaved(entry.itemID)
        store.removeExercise(entry.id, on: day)
        let restored = StrengthWorkoutStore(defaults: defaults)
        #expect(restored.customActivities.count == 1)
        #expect(restored.customActivities[0].timer == nil)
        #expect(restored.savedExerciseIDs.contains(entry.itemID))
        #expect(restored.exerciseLibrary.exercises.contains { $0.id == entry.itemID && $0.name == "Soccer" })
    }

    @Test func appendPreservesExistingEntriesAndRetryIsIdempotent() throws {
        let name = "WorkoutTextDraftTests.\(UUID())"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let store = StrengthWorkoutStore(defaults: defaults)
        let draft = try WorkoutTextDraft.parse(json, library: library, today: today)
        let day = StrengthWorkoutDate.date(for: draft.date)!
        store.toggleExercise(library[0], on: day)
        let original = store.exercises(for: day)[0]
        try store.addTextWorkout(draft, library: library)
        try store.addTextWorkout(draft, library: library)
        #expect(store.exercises(for: day).count == 3)
        #expect(store.exercises(for: day)[0] == original)
        #expect(StrengthWorkoutStore(defaults: defaults).exercises(for: day).count == 3)
        var invalid = draft
        invalid.exercises[0].sets[0].reps = "NaN"
        #expect(throws: (any Error).self) { try store.addTextWorkout(invalid, library: library) }
        #expect(store.exercises(for: day).count == 3)
    }
}
