import Foundation
import Testing
@testable import calorietracker

/// Guards against the "decode failure silently wipes the diary" bug class and
/// the duplicate-id trap in `Dictionary(uniqueKeysWithValues:)`.
@MainActor
struct DiaryPersistenceSafetyTests {
    // MARK: - PersistedBlobGuard

    @Test func missingKeyIsReportedAsMissingNotCorrupt() throws {
        try withDefaults { defaults, backupDir in
            let blob = PersistedBlobGuard(defaults: defaults, key: "k", backupDirectory: backupDir)
            guard case .missing = blob.loadList(WaterEntry.self) else {
                Issue.record("expected .missing")
                return
            }
            #expect(!blob.isWriteBlocked)
            #expect(blob.backups.isEmpty)
        }
    }

    @Test func corruptBlobIsBackedUpAndNotTreatedAsEmpty() throws {
        try withDefaults { defaults, backupDir in
            let garbage = Data("{definitely not json".utf8)
            defaults.set(garbage, forKey: "k")
            let blob = PersistedBlobGuard(defaults: defaults, key: "k", backupDirectory: backupDir)

            guard case .corrupt = blob.loadList(WaterEntry.self) else {
                Issue.record("expected .corrupt")
                return
            }
            let backup = try #require(blob.backups.first)
            #expect(backup.url.lastPathComponent.hasPrefix("k.corrupt-"))
            let preserved = try Data(contentsOf: backup.url)
            #expect(preserved == garbage)
            // Backup exists, so a save is no longer destructive and is allowed.
            #expect(!blob.isWriteBlocked)
            #expect(blob.save([WaterEntry(milliliters: 250)]))
        }
    }

    @Test func writesAreRefusedWhileCorruptBlobHasNoBackup() throws {
        try withDefaults { defaults, _ in
            let garbage = Data("nope".utf8)
            defaults.set(garbage, forKey: "k")
            // A file path is not a directory: backup creation must fail.
            let unwritable = URL(fileURLWithPath: "/dev/null/cannot-create")
            let blob = PersistedBlobGuard(defaults: defaults, key: "k", backupDirectory: unwritable)

            guard case .corrupt = blob.loadList(WaterEntry.self) else {
                Issue.record("expected .corrupt")
                return
            }
            #expect(blob.isWriteBlocked)
            #expect(!blob.save([WaterEntry(milliliters: 250)]))
            #expect(!blob.remove())
            #expect(defaults.data(forKey: "k") == garbage)
        }
    }

    @Test func oneBadRowIsDroppedInsteadOfTheWholeList() throws {
        try withDefaults { defaults, backupDir in
            let good = WaterEntry(id: UUID(), date: Date(timeIntervalSince1970: 1_800_000_000), milliliters: 300)
            let goodJSON = try String(decoding: JSONEncoder().encode(good), as: UTF8.self)
            let mixed = Data("[\(goodJSON), {\"id\": 42}, \(goodJSON)]".utf8)
            defaults.set(mixed, forKey: "k")
            let blob = PersistedBlobGuard(defaults: defaults, key: "k", backupDirectory: backupDir)

            guard case .decoded(let entries, let dropped) = blob.loadList(WaterEntry.self) else {
                Issue.record("expected partial decode")
                return
            }
            #expect(entries == [good, good])
            #expect(dropped == 1)
            // The raw blob is preserved so the dropped row is still recoverable.
            let backup = try #require(blob.backups.first)
            let preserved = try Data(contentsOf: backup.url)
            #expect(preserved == mixed)
        }
    }

    @Test func blobReplacedExternallyWithGarbageIsBackedUpBeforeSave() throws {
        try withDefaults { defaults, backupDir in
            let blob = PersistedBlobGuard(defaults: defaults, key: "k", backupDirectory: backupDir)
            #expect(blob.save([WaterEntry(milliliters: 100)]))
            guard case .decoded = blob.loadList(WaterEntry.self) else {
                Issue.record("expected .decoded")
                return
            }
            // Another process (widget, restore) replaces the key behind the guard's back.
            let garbage = Data("{not water".utf8)
            defaults.set(garbage, forKey: "k")

            #expect(blob.save([WaterEntry(milliliters: 250)]))
            let backup = try #require(blob.backups.first)
            #expect(try Data(contentsOf: backup.url) == garbage)
        }
    }

    @Test func blobReplacedExternallyWithGarbageBlocksSaveWhenBackupFails() throws {
        try withDefaults { defaults, _ in
            let unwritable = URL(fileURLWithPath: "/dev/null/cannot-create")
            let blob = PersistedBlobGuard(defaults: defaults, key: "k", backupDirectory: unwritable)
            #expect(blob.save([WaterEntry(milliliters: 100)]))
            guard case .decoded = blob.loadList(WaterEntry.self) else {
                Issue.record("expected .decoded")
                return
            }
            #expect(!blob.isWriteBlocked)
            let garbage = Data("{not water".utf8)
            defaults.set(garbage, forKey: "k")

            #expect(!blob.save([WaterEntry(milliliters: 250)]))
            #expect(!blob.remove())
            #expect(blob.isWriteBlocked)
            #expect(defaults.data(forKey: "k") == garbage)
        }
    }

    @Test func blobReplacedExternallyWithValidDataDoesNotSpawnBackups() throws {
        try withDefaults { defaults, backupDir in
            let blob = PersistedBlobGuard(defaults: defaults, key: "k", backupDirectory: backupDir)
            _ = blob.loadList(WaterEntry.self)
            defaults.set(try JSONEncoder().encode([WaterEntry(milliliters: 100)]), forKey: "k")

            #expect(blob.save([WaterEntry(milliliters: 250)]))
            #expect(blob.backups.isEmpty)
        }
    }

    // MARK: - FastingStore

    @Test func persistedActiveSessionSurvivesOneUnreadableRow() throws {
        try withDefaults { defaults, _ in
            let active = FastingSession(startedAt: Date(timeIntervalSince1970: 1_800_000_000), goalMinutes: 960)
            let activeJSON = try String(decoding: JSONEncoder().encode(active), as: UTF8.self)
            defaults.set(Data("[{\"id\": 1}, \(activeJSON)]".utf8), forKey: FastingSettings.sessionsKey)

            #expect(FastingStore.persistedActiveSession(defaults: defaults)?.id == active.id)
        }
    }

    // MARK: - StrengthWorkoutStore

    @Test func workoutMutationsAreRefusedWhileCorruptStateHasNoBackup() throws {
        try withDefaults { defaults, _ in
            let key = "workouts"
            let garbage = Data("{broken".utf8)
            defaults.set(garbage, forKey: key)
            let unwritable = URL(fileURLWithPath: "/dev/null/cannot-create")
            let store = StrengthWorkoutStore(defaults: defaults, storageKey: key, corruptBackupDirectory: unwritable)
            let date = Date(timeIntervalSince1970: 1_800_000_000)

            #expect(store.isPersistenceBlocked)
            store.toggleExercise(makeExercise(), on: date)
            store.toggleSaved("bench")
            store.updatePreferences { $0.frequencyDays = 6 }
            #expect(store.completeWorkout(on: date, startedAt: date, elapsedSeconds: 60, weightUnit: .kg) == nil)
            store.clearAll()

            #expect(store.dayPlans.isEmpty)
            #expect(store.savedExerciseIDs.isEmpty)
            #expect(store.preferences == StrengthWorkoutPreferences())
            #expect(defaults.data(forKey: key) == garbage)
        }
    }

    @Test func reloadingCorruptWorkoutStateKeepsTheInMemoryDiary() throws {
        try withDefaults { defaults, backupDir in
            let key = "workouts"
            let store = StrengthWorkoutStore(defaults: defaults, storageKey: key, corruptBackupDirectory: backupDir)
            let date = Date(timeIntervalSince1970: 1_800_000_000)
            store.toggleExercise(makeExercise(), on: date)
            #expect(store.workoutCount(for: date) == 1)

            defaults.set(Data("garbage".utf8), forKey: key)
            store.reloadFromDefaults()

            #expect(store.workoutCount(for: date) == 1)
            #expect(!store.isPersistenceBlocked)
        }
    }

    @Test func newerWorkoutSchemaStaysWriteBlockedAfterBackup() throws {
        try withDefaults { defaults, backupDir in
            let key = "workouts"
            var newerState = StrengthWorkoutStore.PersistedState()
            newerState.version = 2
            let newer = try JSONEncoder().encode(newerState)
            defaults.set(newer, forKey: key)
            let store = StrengthWorkoutStore(defaults: defaults, storageKey: key, corruptBackupDirectory: backupDir)
            let date = Date(timeIntervalSince1970: 1_800_000_000)

            // The backup exists, yet this build must still never overwrite the newer state.
            #expect(store.isPersistenceBlocked)
            store.toggleExercise(makeExercise(), on: date)
            store.toggleSaved("bench")
            #expect(store.dayPlans.isEmpty)
            #expect(store.savedExerciseIDs.isEmpty)
            #expect(defaults.data(forKey: key) == newer)
        }
    }

    // MARK: - FoodStore

    @Test func corruptFoodBlobIsPreservedAndAddIsRefusedUntilBackedUp() throws {
        try withDefaults { defaults, _ in
            let garbage = Data("[{\"id\": \"broken\"".utf8)
            defaults.set(garbage, forKey: FoodStore.storageKey)
            let unwritable = URL(fileURLWithPath: "/dev/null/cannot-create")
            let store = FoodStore(observesExternalChanges: false, defaults: defaults, corruptBackupDirectory: unwritable)

            #expect(store.entries.isEmpty)
            #expect(store.isPersistenceBlocked)
            #expect(!store.addEntry(makeMeal()))
            store.replaceAllEntries([])
            #expect(defaults.data(forKey: FoodStore.storageKey) == garbage)
        }
    }

    @Test func corruptFoodBlobIsBackedUpThenStoreBecomesWritable() throws {
        try withDefaults { defaults, backupDir in
            let garbage = Data("[{\"id\": \"broken\"".utf8)
            defaults.set(garbage, forKey: FoodStore.storageKey)
            let store = FoodStore(observesExternalChanges: false, defaults: defaults, corruptBackupDirectory: backupDir)

            #expect(!store.isPersistenceBlocked)
            let backup = try #require(store.corruptBlobBackups.first)
            let preserved = try Data(contentsOf: backup.url)
            #expect(preserved == garbage)
            #expect(store.addEntry(makeMeal()))
            #expect(store.entries.count == 1)
        }
    }

    @Test func partiallyReadableFoodBlobKeepsGoodRows() throws {
        try withDefaults { defaults, backupDir in
            let meal = makeMeal()
            let mealJSON = try String(decoding: JSONEncoder().encode(meal), as: UTF8.self)
            defaults.set(Data("[\(mealJSON), {\"name\": \"no id\"}]".utf8), forKey: FoodStore.storageKey)
            let store = FoodStore(observesExternalChanges: false, defaults: defaults, corruptBackupDirectory: backupDir)

            #expect(store.entries.map(\.id) == [meal.id])
            #expect(store.droppedEntriesOnLoad == 1)
            #expect(store.corruptBlobBackups.count == 1)
        }
    }

    @Test func reloadingACorruptBlobKeepsTheInMemoryDiary() throws {
        try withDefaults { defaults, backupDir in
            let store = FoodStore(observesExternalChanges: false, defaults: defaults, corruptBackupDirectory: backupDir)
            #expect(store.addEntry(makeMeal()))
            defaults.set(Data("garbage".utf8), forKey: FoodStore.storageKey)

            store.reloadFromDefaults()
            #expect(store.entries.count == 1)
        }
    }

    @Test func mergingDuplicateFoodIDsDoesNotTrapAndNewestWins() throws {
        try withDefaults { defaults, backupDir in
            let id = UUID()
            let older = FoodEntry(id: id, name: "Old", calories: 100, protein: 1, carbs: 1, fat: 1, source: .manual)
            let newer = FoodEntry(id: id, name: "New", calories: 200, protein: 2, carbs: 2, fat: 2, source: .manual)
            // Simulate a diary that already contains the same id twice.
            defaults.set(try JSONEncoder().encode([older, older]), forKey: FoodStore.storageKey)
            let store = FoodStore(observesExternalChanges: false, defaults: defaults, corruptBackupDirectory: backupDir)
            #expect(store.entries.count == 2)

            store.mergeWithCloudEntries([newer, makeMeal()])
            #expect(store.entries.count == 2)
            #expect(store.entries.first { $0.id == id }?.name == "New")
        }
    }

    @Test func mergingByIDPreservesOrderAndLetsLaterDuplicatesWin() {
        let a = FoodEntry(name: "A", calories: 1, protein: 0, carbs: 0, fat: 0, source: .manual)
        let b = FoodEntry(name: "B", calories: 1, protein: 0, carbs: 0, fat: 0, source: .manual)
        let bUpdated = FoodEntry(id: b.id, name: "B2", calories: 5, protein: 0, carbs: 0, fat: 0, source: .manual)
        let merged = FoodStore.mergingByID([a, b, a], with: [bUpdated])
        #expect(merged.map(\.id) == [a.id, b.id])
        #expect(merged[1].name == "B2")
    }

    // MARK: - WeightStore

    @Test func weightMergeAndImportToleratesDuplicateIDs() throws {
        try withDefaults { defaults, backupDir in
            let id = UUID()
            let older = WeightEntry(id: id, date: Date(timeIntervalSince1970: 1), weightKg: 80)
            let newer = WeightEntry(id: id, date: Date(timeIntervalSince1970: 2), weightKg: 79)
            defaults.set(try JSONEncoder().encode([older, older]), forKey: WeightStore.storageKey)
            let store = WeightStore(observesExternalChanges: false, defaults: defaults, corruptBackupDirectory: backupDir)

            store.mergeWithCloudEntries([newer])
            #expect(store.entries.count == 1)
            #expect(store.entries.first?.weightKg == 79)

            // HealthKit restore delivering an id we already hold must not duplicate it.
            store.importExternalEntries([newer, WeightEntry(weightKg: 78), newer])
            #expect(store.entries.count == 2)
            #expect(store.entries.filter { $0.id == id }.count == 1)
        }
    }

    @Test func corruptWeightBlobIsNotReplacedByANewEntry() throws {
        try withDefaults { defaults, _ in
            let garbage = Data("broken".utf8)
            defaults.set(garbage, forKey: WeightStore.storageKey)
            let unwritable = URL(fileURLWithPath: "/dev/null/cannot-create")
            let store = WeightStore(observesExternalChanges: false, defaults: defaults, corruptBackupDirectory: unwritable)

            #expect(store.isPersistenceBlocked)
            store.addEntry(WeightEntry(weightKg: 70))
            #expect(store.entries.isEmpty)
            #expect(defaults.data(forKey: WeightStore.storageKey) == garbage)
        }
    }

    // MARK: - DiaryImporter

    @Test func replaceDateRangeImportSurvivesDuplicateExistingIDs() throws {
        let day = Calendar.current.startOfDay(for: Date(timeIntervalSince1970: 1_800_000_000))
        let id = UUID()
        let existing = [
            FoodEntry(id: id, name: "Dup 1", calories: 100, protein: 0, carbs: 0, fat: 0, timestamp: day, source: .manual),
            FoodEntry(id: id, name: "Dup 2", calories: 120, protein: 0, carbs: 0, fat: 0, timestamp: day, source: .manual),
        ]
        let imported = FoodEntry(id: id, name: "Imported", calories: 150, protein: 0, carbs: 0, fat: 0, timestamp: day, source: .manual)
        let preview = DiaryImportPreview(entries: [imported], startDate: day, endDate: day, waterEntries: [], includesWater: false)

        let result = DiaryImporter.applying(preview, to: existing, mode: .replaceDateRange)
        #expect(result.count == 1)
        #expect(result.first?.id == id)
        #expect(result.first?.name == "Imported")
    }

    // MARK: - Helpers

    private func makeExercise() -> ExerciseLibraryItem {
        ExerciseLibraryItem(
            id: "bench",
            name: "Bench Press",
            rawLevel: "intermediate",
            force: "push",
            mechanic: "compound",
            category: "strength",
            rawEquipment: "barbell",
            primaryMuscles: ["chest"],
            secondaryMuscles: ["triceps"],
            instructions: ["Control the repetition."]
        )
    }

    private func makeMeal() -> FoodEntry {
        FoodEntry(
            name: "Rice and beans", calories: 270, protein: 12, carbs: 52, fat: 2,
            timestamp: Date(timeIntervalSince1970: 1_800_000_000),
            source: .snapFood, mealType: .lunch
        )
    }

    private func withDefaults(_ body: (UserDefaults, URL) throws -> Void) throws {
        let suite = "DiaryPersistenceSafetyTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let backupDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("DiaryPersistenceSafetyTests-\(UUID().uuidString)", isDirectory: true)
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: backupDir)
        }
        try body(defaults, backupDir)
    }
}
