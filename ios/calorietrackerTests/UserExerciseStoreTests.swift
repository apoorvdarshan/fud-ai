import Foundation
import Testing
@testable import calorietracker

@Suite struct UserExerciseStoreTests {
    @Test func saveAndReloadUserExercise() throws {
        let defaults = UserDefaults(suiteName: "UserExerciseStoreTests")!
        defaults.removePersistentDomain(forName: "UserExerciseStoreTests")
        defer { defaults.removePersistentDomain(forName: "UserExerciseStoreTests") }

        let store = StrengthWorkoutStore(defaults: defaults, storageKey: "test.workout.state")
        let draft = UserExerciseDraft(
            name: "My Cable Pushdown",
            instructions: "Keep elbows tucked.",
            rawLevel: "Intermediate",
            force: "Push",
            mechanic: "Isolation",
            category: "Strength",
            rawEquipment: "Cable",
            primaryMuscles: ["Triceps"]
        )

        let saved = store.saveUserExercise(draft)
        #expect(saved != nil)
        #expect(UserExercise.isUserExercise(saved!.id))

        let reloaded = StrengthWorkoutStore(defaults: defaults, storageKey: "test.workout.state")
        #expect(reloaded.userExercises.count == 1)
        #expect(reloaded.userExercises[0].name == "My Cable Pushdown")
        #expect(reloaded.exerciseLibrary.exercises.contains { $0.id == saved!.id })
    }

    @Test func editingUserExerciseRefreshesExerciseLibraryCache() throws {
        let defaults = UserDefaults(suiteName: "UserExerciseStoreCacheTests")!
        defaults.removePersistentDomain(forName: "UserExerciseStoreCacheTests")
        defer { defaults.removePersistentDomain(forName: "UserExerciseStoreCacheTests") }

        let store = StrengthWorkoutStore(defaults: defaults, storageKey: "test.workout.cache")
        let item = store.saveUserExercise(UserExerciseDraft(
            name: "Old Name",
            primaryMuscles: ["Chest"]
        ))
        let itemID = try #require(item?.id)
        _ = store.exerciseLibrary

        _ = store.saveUserExercise(UserExerciseDraft(
            name: "New Name",
            primaryMuscles: ["Back"]
        ), existingItemID: itemID)

        let updated = try #require(store.exerciseLibrary.exercises.first { $0.id == itemID })
        #expect(updated.name == "New Name")
        #expect(updated.primaryMuscles == ["Back"])
    }

    @Test func deleteUserExerciseRemovesTemplate() throws {
        let defaults = UserDefaults(suiteName: "UserExerciseStoreDeleteTests")!
        defaults.removePersistentDomain(forName: "UserExerciseStoreDeleteTests")
        defer { defaults.removePersistentDomain(forName: "UserExerciseStoreDeleteTests") }

        let store = StrengthWorkoutStore(defaults: defaults, storageKey: "test.workout.delete")
        let item = store.saveUserExercise(UserExerciseDraft(name: "Temp Exercise"))
        #expect(item != nil)

        store.deleteUserExercise(itemID: item!.id)

        let reloaded = StrengthWorkoutStore(defaults: defaults, storageKey: "test.workout.delete")
        #expect(reloaded.userExercises.isEmpty)
    }
}
