import CoreGraphics
import Foundation
import Testing
@testable import calorietracker

@MainActor
struct StrengthWorkoutStoreTests {
    @Test func workoutTabModeUsesModeSpecificIconsAndSafeFallback() {
        #expect(WorkoutTabMode.mode(for: WorkoutTabMode.library.rawValue) == .library)
        #expect(WorkoutTabMode.mode(for: WorkoutTabMode.log.rawValue) == .log)
        #expect(WorkoutTabMode.mode(for: "unknown") == .log)
        #expect(WorkoutTabMode.defaultMode == .log)
        #expect(WorkoutTabMode.storageKey == "fudai.workouts.tab.mode.v2")
        #expect(WorkoutTabMode.library.tabIcon == "dumbbell.fill")
        #expect(WorkoutTabMode.log.tabIcon == "figure.strengthtraining.traditional")
    }

    @Test func workoutPreferencesDefaultToFullBodyAndMigrateLegacyCustomSplits() {
        var preferences = StrengthWorkoutPreferences()

        #expect(preferences.split == .fullBody)
        #expect(StrengthWorkoutSplit.selectableCases.first == .fullBody)
        #expect(!StrengthWorkoutSplit.selectableCases.contains(.custom))

        preferences.split = .custom
        preferences.customSplit = "Chest + back / Legs / Arms"
        preferences.sanitize()

        #expect(preferences.split == .fullBody)
        #expect(preferences.customSplit.isEmpty)
    }

    @Test func workoutLogStatePreservesSelectedDayUntilFullReset() {
        let selectedDate = WorkoutTestFixture.date(2026, 7, 12)
        let session = WorkoutLogSessionState()
        session.selectedDate = selectedDate

        #expect(session.selectedDate == selectedDate)
    }

    @Test func workoutLogFullResetReturnsToToday() {
        let session = WorkoutLogSessionState()
        session.selectedDate = WorkoutTestFixture.date(2026, 7, 12)

        session.reset()

        #expect(Calendar.current.isDateInToday(session.selectedDate))
    }

    @Test func workoutLogDayNavigationMovesOneDayAndStopsAtToday() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(secondsFromGMT: 0))
        let today = try #require(calendar.date(from: DateComponents(
            year: 2026,
            month: 7,
            day: 20,
            hour: 12
        )))
        let yesterday = try #require(calendar.date(byAdding: .day, value: -1, to: today))
        let session = WorkoutLogSessionState()
        session.selectedDate = yesterday

        #expect(session.moveSelectedDay(by: 1, now: today, calendar: calendar))
        #expect(calendar.isDate(session.selectedDate, inSameDayAs: today))

        #expect(!session.moveSelectedDay(by: 1, now: today, calendar: calendar))
        #expect(calendar.isDate(session.selectedDate, inSameDayAs: today))

        #expect(session.moveSelectedDay(by: -1, now: today, calendar: calendar))
        #expect(calendar.isDate(session.selectedDate, inSameDayAs: yesterday))
    }

    @Test func workoutLogDaySwipeRequiresADeliberateHorizontalFlick() {
        #expect(WorkoutLogDaySwipeNavigation.dayDelta(for: CGSize(width: -61, height: 10)) == 1)
        #expect(WorkoutLogDaySwipeNavigation.dayDelta(for: CGSize(width: 61, height: -10)) == -1)
        #expect(WorkoutLogDaySwipeNavigation.dayDelta(for: CGSize(width: 60, height: 0)) == nil)
        #expect(WorkoutLogDaySwipeNavigation.dayDelta(for: CGSize(width: 100, height: 70)) == nil)
    }

    @Test func workoutLogKeyboardDismissesOnlyOutsideWorkoutCards() {
        let cardFrames = [
            CGRect(x: 16, y: 300, width: 320, height: 240),
            CGRect(x: 16, y: 554, width: 320, height: 280)
        ]

        #expect(!WorkoutLogKeyboardDismissal.shouldDismiss(
            at: CGPoint(x: 120, y: 420),
            cardFrames: cardFrames
        ))
        #expect(!WorkoutLogKeyboardDismissal.shouldDismiss(
            at: CGPoint(x: 300, y: 700),
            cardFrames: cardFrames
        ))
        #expect(WorkoutLogKeyboardDismissal.shouldDismiss(
            at: CGPoint(x: 120, y: 180),
            cardFrames: cardFrames
        ))
        #expect(WorkoutLogKeyboardDismissal.shouldDismiss(
            at: CGPoint(x: 8, y: 420),
            cardFrames: cardFrames
        ))
        #expect(!WorkoutLogKeyboardDismissal.shouldDismiss(
            at: CGPoint(x: 120, y: 180),
            cardFrames: []
        ))
    }

    @Test func persistenceReloadRestoresDiarySavedExercisesAndPreferences() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }

        let workoutDate = WorkoutTestFixture.date(2026, 7, 12)
        let exercise = WorkoutTestFixture.exercise(
            id: "barbell-bench-press",
            name: "Barbell Bench Press",
            equipment: "barbell",
            muscles: ["chest", "triceps"]
        )
        let store = fixture.makeStore()

        store.toggleExercise(exercise, on: workoutDate)
        let planned = try #require(store.exercises(for: workoutDate).first)
        let plannedSet = try #require(planned.sets.first)
        store.updateSet(
            exerciseID: planned.id,
            setID: plannedSet.id,
            on: workoutDate,
            weight: "82,5 kg",
            weightUnit: .kg,
            reps: "8 reps",
            rpe: "7.5"
        )
        store.toggleSaved(exercise.id)
        store.updatePreferences { preferences in
            preferences.targetMuscles = ["Chest", "Triceps"]
            preferences.issues = [.shoulder, .other]
            preferences.additionalIssues = "  Avoid deep dips  "
            preferences.frequencyDays = 10
            preferences.duration = .seventyFive
            preferences.split = .upperLower
            preferences.equipment = ["Barbell", "Bench"]
            preferences.rpeScale = .cr10
            preferences.strength.benchPressKg = 100
            preferences.strength.squatKg = -5
        }

        let reloaded = fixture.makeStore()
        let restoredExercise = try #require(reloaded.exercises(for: workoutDate).first)
        let restoredSet = try #require(restoredExercise.sets.first)

        #expect(restoredExercise.itemID == exercise.id)
        #expect(restoredSet.weight == "82.5")
        #expect(restoredSet.weightUnit == WeightUnit.kg.rawValue)
        #expect(restoredSet.reps == "8")
        #expect(restoredSet.rpe == "7.5")
        #expect(restoredSet.rpeScale == .strength)
        #expect(reloaded.savedExerciseIDs == [exercise.id])
        #expect(reloaded.preferences.targetMuscles == ["Chest", "Triceps"])
        #expect(reloaded.preferences.issues == [.shoulder, .other])
        #expect(reloaded.preferences.additionalIssues == "Avoid deep dips")
        #expect(reloaded.preferences.frequencyDays == 7)
        #expect(reloaded.preferences.duration == .seventyFive)
        #expect(reloaded.preferences.split == .upperLower)
        #expect(reloaded.preferences.rpeScale == .cr10)
        #expect(reloaded.preferences.strength.benchPressKg == 100)
        #expect(reloaded.preferences.strength.squatKg == nil)
    }

    @Test func toggleAndCopyPlanDeduplicateExercisesAndResetCopiedSets() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }

        let sourceDate = WorkoutTestFixture.date(2026, 7, 10)
        let targetDate = WorkoutTestFixture.date(2026, 7, 11)
        let bench = WorkoutTestFixture.exercise(id: "bench", name: "Bench Press")
        let row = WorkoutTestFixture.exercise(
            id: "row",
            name: "Barbell Row",
            equipment: "barbell",
            muscles: ["middle back"]
        )
        let store = fixture.makeStore()

        store.toggleExercise(bench, on: sourceDate)
        #expect(store.containsExercise(bench.id, on: sourceDate))
        store.toggleExercise(bench, on: sourceDate)
        #expect(!store.containsExercise(bench.id, on: sourceDate))
        #expect(store.plan(for: sourceDate).exercises.isEmpty)

        store.toggleExercise(bench, on: sourceDate)
        store.toggleExercise(row, on: sourceDate)
        let sourceRow = try #require(store.exercises(for: sourceDate).first { $0.itemID == row.id })
        let sourceSet = try #require(sourceRow.sets.first)
        store.updateSet(
            exerciseID: sourceRow.id,
            setID: sourceSet.id,
            on: sourceDate,
            weight: "70",
            weightUnit: .kg,
            reps: "10",
            rpe: "8"
        )
        store.setSetCount(3, exerciseID: sourceRow.id, on: sourceDate)

        store.toggleExercise(bench, on: targetDate)
        store.copyPlan(from: sourceDate, to: targetDate)
        store.copyPlan(from: sourceDate, to: targetDate)

        let copied = store.exercises(for: targetDate)
        #expect(copied.map(\.itemID) == [bench.id, row.id])
        #expect(Set(copied.map(\.itemID)).count == copied.count)
        let copiedRow = try #require(copied.first { $0.itemID == row.id })
        #expect(copiedRow.id != sourceRow.id)
        #expect(copiedRow.sets.count == 1)
        #expect(copiedRow.sets[0].weight.isEmpty)
        #expect(copiedRow.sets[0].weightUnit == nil)
        #expect(copiedRow.sets[0].reps.isEmpty)
        #expect(copiedRow.sets[0].rpe.isEmpty)
        #expect(store.previousPlanDates(before: targetDate) == [sourceDate])
    }

    @Test func copyPlanCanCarrySetDetailsWhenRequested() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }
        let sourceDate = WorkoutTestFixture.date(2026, 9, 8)
        let targetDate = WorkoutTestFixture.date(2026, 9, 9)
        let store = fixture.makeStore()
        let exercise = WorkoutTestFixture.exercise(id: "bench", name: "Bench press")
        store.toggleExercise(exercise, on: sourceDate)
        let source = try #require(store.exercises(for: sourceDate).first)
        let sourceSet = try #require(source.sets.first)
        store.updateSet(exerciseID: source.id, setID: sourceSet.id, on: sourceDate,
                        weight: "40", weightUnit: .kg, reps: "10", rpe: "8")
        store.setSetCount(2, exerciseID: source.id, on: sourceDate)

        store.copyPlan(from: sourceDate, to: targetDate, includeSetDetails: true)

        let copied = try #require(store.exercises(for: targetDate).first)
        #expect(copied.sets.count == 2)
        #expect(copied.sets.first?.weight == "40")
        #expect(copied.sets.first?.reps == "10")
        #expect(copied.sets.first?.rpe == "8")
        #expect(copied.id != source.id)
        #expect(copied.sets.first?.id != sourceSet.id)
    }

    @Test func setLimitsAddBlankRowsAndSanitizeLoadRepsAndRPEScales() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }

        let date = WorkoutTestFixture.date(2026, 7, 13)
        let exercise = WorkoutTestFixture.exercise(id: "squat", name: "Back Squat")
        let store = fixture.makeStore()
        store.toggleExercise(exercise, on: date)

        var planned = try #require(store.exercises(for: date).first)
        var firstSet = try #require(planned.sets.first)
        store.updateSet(
            exerciseID: planned.id,
            setID: firstSet.id,
            on: date,
            weight: "100,5 kg",
            weightUnit: .kg,
            reps: "12a345",
            rpe: "7.5"
        )
        store.setSetCount(99, exerciseID: planned.id, on: date)

        planned = try #require(store.exercises(for: date).first)
        #expect(planned.sets.count == 12)
        #expect(planned.sets[0].weight == "100.5")
        #expect(planned.sets[0].weightUnit == WeightUnit.kg.rawValue)
        #expect(planned.sets[0].reps == "1234")
        #expect(planned.sets[0].rpe == "7.5")
        #expect(planned.sets.dropFirst().allSatisfy {
            $0.weight == "100.5"
                && $0.weightUnit == WeightUnit.kg.rawValue
                && $0.reps == "1234"
                && $0.rpe.isEmpty
                && $0.rpeScale == nil
        })

        store.setSetCount(-4, exerciseID: planned.id, on: date)
        planned = try #require(store.exercises(for: date).first)
        #expect(planned.sets.count == 1)

        store.updatePreferences { $0.rpeScale = .cr10 }
        firstSet = try #require(planned.sets.first)
        store.updateSet(exerciseID: planned.id, setID: firstSet.id, on: date, rpe: "8,6")
        #expect(store.exercises(for: date)[0].sets[0].rpe == "8.6")

        store.updatePreferences { $0.rpeScale = .borg }
        store.updateSet(exerciseID: planned.id, setID: firstSet.id, on: date, rpe: "18")
        #expect(store.exercises(for: date)[0].sets[0].rpe == "18")
        store.updateSet(exerciseID: planned.id, setID: firstSet.id, on: date, rpe: "99")
        #expect(store.exercises(for: date)[0].sets[0].rpe == "20")
        store.updateSet(exerciseID: planned.id, setID: firstSet.id, on: date, rpe: "5")
        #expect(store.exercises(for: date)[0].sets[0].rpe == "20")
    }

    @Test func liftHistoryMatchingUsesCatalogIdExclusivelyWhenProvided() {
        #expect(
            StrengthExerciseLiftHistory.matches(
                itemID: "bench-press",
                name: "Bench Press",
                candidateItemID: "bench-press",
                candidateName: "Bench Press"
            )
        )
        #expect(
            !StrengthExerciseLiftHistory.matches(
                itemID: "bench-press",
                name: "Bench Press",
                candidateItemID: "custom-bench",
                candidateName: "Bench Press"
            )
        )
        #expect(
            StrengthExerciseLiftHistory.matches(
                itemID: "",
                name: "Bench Press",
                candidateItemID: "custom-bench",
                candidateName: "Bench Press"
            )
        )
    }

    @Test func exerciseLiftHistoryFindsMostRecentPriorDay() throws {
        let bench = WorkoutTestFixture.exercise(id: "bench", name: "Bench Press")
        let yesterday = WorkoutTestFixture.date(2026, 7, 19)
        let today = WorkoutTestFixture.date(2026, 7, 20)
        let store = WorkoutTestFixture().makeStore()
        store.toggleExercise(bench, on: yesterday)
        var planned = try #require(store.exercises(for: yesterday).first)
        var set = try #require(planned.sets.first)
        store.updateSet(
            exerciseID: planned.id,
            setID: set.id,
            on: yesterday,
            weight: "60",
            weightUnit: .kg,
            reps: "8"
        )
        _ = store.upsertCalculatedWorkout(on: yesterday, caloriesBurned: 180, weightUnit: .kg)
        store.toggleExercise(bench, on: today)

        let summary = store.lastExerciseLiftSummary(
            itemID: bench.id,
            name: bench.name,
            before: today,
            displayUnit: .kg
        )
        #expect(summary == "60 kg × 8")

        let history = store.exerciseLiftHistory(itemID: bench.id, name: bench.name, before: today)
        #expect(history.count == 1)
        #expect(history[0].dateKey == StrengthWorkoutStore.dateKey(for: yesterday))
    }

    @Test func plannedSetWeightDisplayFollowsGlobalUnitWithoutMutatingStoredLoad() {
        let metricSet = StrengthPlannedSet(weight: "100", weightUnit: WeightUnit.kg.rawValue)
        #expect(metricSet.displayWeight(in: .kg) == "100")
        #expect(metricSet.displayWeight(in: .lbs) == "220.46")
        #expect(metricSet.weight == "100")
        #expect(metricSet.weightUnit == WeightUnit.kg.rawValue)

        let imperialSet = StrengthPlannedSet(weight: "220.46", weightUnit: WeightUnit.lbs.rawValue)
        #expect(imperialSet.displayWeight(in: .kg) == "100")
        #expect(imperialSet.displayWeight(in: .lbs) == "220.46")

        let legacySet = StrengthPlannedSet(weight: "75")
        #expect(legacySet.displayWeight(in: .kg) == "75")
        #expect(legacySet.displayWeight(in: .lbs) == "75")
    }

    @Test func completionBuildsStatisticsFiltersDatesDeletesAndPersistsSessions() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }

        let firstDate = WorkoutTestFixture.date(2026, 7, 8)
        let secondDate = WorkoutTestFixture.date(2026, 7, 10)
        let thirdDate = WorkoutTestFixture.date(2026, 7, 12)
        let bench = WorkoutTestFixture.exercise(
            id: "bench",
            name: "Bench Press",
            equipment: "barbell",
            muscles: ["chest"]
        )
        let store = fixture.makeStore()

        #expect(
            store.completeWorkout(
                on: firstDate,
                startedAt: firstDate,
                completedAt: firstDate,
                elapsedSeconds: 60,
                weightUnit: .kg
            ) == nil
        )

        func addCompletedWorkout(on date: Date, weight: String, reps: String, elapsed: Int) throws -> StrengthWorkoutSession {
            store.toggleExercise(bench, on: date)
            let exercise = try #require(store.exercises(for: date).first)
            let firstSet = try #require(exercise.sets.first)
            store.updateSet(
                exerciseID: exercise.id,
                setID: firstSet.id,
                on: date,
                weight: weight,
                weightUnit: .lbs,
                reps: reps,
                rpe: "8.5"
            )
            store.setSetCount(2, exerciseID: exercise.id, on: date)
            return try #require(
                store.completeWorkout(
                    on: date,
                    startedAt: date.addingTimeInterval(-Double(elapsed)),
                    completedAt: date,
                    elapsedSeconds: elapsed,
                    weightUnit: .kg
                )
            )
        }

        let first = try addCompletedWorkout(on: firstDate, weight: "80", reps: "8", elapsed: 61)
        let second = try addCompletedWorkout(on: secondDate, weight: "82.5", reps: "6", elapsed: 600)
        let third = try addCompletedWorkout(on: thirdDate, weight: "85", reps: "5", elapsed: 900)

        #expect(first.exerciseCount == 1)
        #expect(first.exercises[0].sets.count == 2)
        #expect(first.performedSetCount == 1)
        #expect(first.repCount == 8)
        #expect(first.durationSeconds == 61)
        #expect(first.durationMinutes == 2)
        #expect(first.exercises[0].sets[0].weightUnit == WeightUnit.lbs.rawValue)
        #expect(first.exercises[0].sets[0].rpeScale == .strength)
        #expect(first.stableDiaryDateKey == "2026-07-08")
        #expect(StrengthWorkoutStore.dateKey(for: first.calendarDiaryDate) == "2026-07-08")
        #expect(!first.exercises[0].sets[1].isPerformed)
        #expect(store.latestSession(on: secondDate)?.id == second.id)

        let middleRange = store.sessions(from: firstDate, through: secondDate)
        #expect(middleRange.map(\.id) == [first.id, second.id])
        #expect(!middleRange.contains { $0.id == third.id })
        #expect(fixture.makeStore().completedSessions.count == 3)

        store.deleteSession(second.id)
        let remainingIDs = Set(store.completedSessions.map(\.id))
        let expectedIDs: Set<UUID> = [first.id, third.id]
        #expect(remainingIDs == expectedIDs)
        #expect(fixture.makeStore().completedSessions.count == 2)
    }

    @Test func burnEstimatorRequiresPerformedSetsAndRespondsToEffortAndLoad() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }

        let date = WorkoutTestFixture.date(2026, 7, 18)
        let store = fixture.makeStore()
        store.toggleExercise(
            WorkoutTestFixture.exercise(id: "curl", name: "Dumbbell Curl", equipment: "dumbbell", muscles: ["biceps"]),
            on: date
        )

        var exercise = try #require(store.exercises(for: date).first)
        var set = try #require(exercise.sets.first)
        #expect(
            StrengthWorkoutBurnEstimator.estimate(
                exercises: [exercise],
                bodyWeightKg: 75,
                defaultWeightUnit: .kg,
                defaultRPEScale: .strength
            ) == nil
        )

        store.updateSet(
            exerciseID: exercise.id,
            setID: set.id,
            on: date,
            weight: "8",
            weightUnit: .kg,
            reps: "12",
            rpe: "3"
        )
        exercise = try #require(store.exercises(for: date).first)
        let easier = try #require(
            StrengthWorkoutBurnEstimator.estimate(
                exercises: [exercise],
                bodyWeightKg: 75,
                defaultWeightUnit: .kg,
                defaultRPEScale: .strength
            )
        )

        set = try #require(exercise.sets.first)
        store.updateSet(
            exerciseID: exercise.id,
            setID: set.id,
            on: date,
            weight: "40",
            weightUnit: .kg,
            rpe: "10"
        )
        exercise = try #require(store.exercises(for: date).first)
        let harder = try #require(
            StrengthWorkoutBurnEstimator.estimate(
                exercises: [exercise],
                bodyWeightKg: 75,
                defaultWeightUnit: .kg,
                defaultRPEScale: .strength
            )
        )

        #expect(easier.performedSetCount == 1)
        #expect(easier.repCount == 12)
        #expect(harder.calories > easier.calories)
        #expect((1...5_000).contains(harder.calories))
    }

    @Test func calculatedBurnUpsertsOneStableDailyRecordAndDeletesHealthByExactID() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }

        let date = WorkoutTestFixture.date(2026, 7, 19)
        let store = fixture.makeStore()
        store.toggleExercise(WorkoutTestFixture.exercise(id: "squat", name: "Back Squat"), on: date)
        let exercise = try #require(store.exercises(for: date).first)
        let set = try #require(exercise.sets.first)
        store.updateSet(
            exerciseID: exercise.id,
            setID: set.id,
            on: date,
            weight: "100",
            weightUnit: .kg,
            reps: "8",
            rpe: "8"
        )

        var exported: [StrengthWorkoutSession] = []
        var deletedIDs: [UUID] = []
        store.onWorkoutBurnUpserted = { exported.append($0) }
        store.onWorkoutBurnDeleted = { deletedIDs.append($0) }

        let first = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: 180, weightUnit: .kg))
        let second = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: 225, weightUnit: .kg))

        #expect(first.id == second.id)
        #expect(first.healthSyncVersion == 1)
        #expect(second.healthSyncVersion == 2)
        #expect(second.caloriesBurned == 225)
        #expect(second.durationSeconds == 0)
        #expect(second.durationMinutes == 0)
        #expect(store.workoutBurnSessions.count == 1)
        #expect(store.caloriesBurned(on: date) == 225)
        #expect(exported.map(\.id) == [first.id, first.id])
        #expect(fixture.makeStore().workoutBurnSessions.first?.caloriesBurned == 225)

        store.deleteSession(second.id)
        #expect(store.workoutBurnSessions.isEmpty)
        #expect(store.exercises(for: date).count == 1)
        #expect(deletedIDs == [second.id])
    }

    @Test func logQuickCardioAppendsSavedTimedEntry() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }
        let date = WorkoutTestFixture.date(2026, 9, 8)
        let store = fixture.makeStore()
        let item = WorkoutTestFixture.exercise(id: "Walking_Outdoor", name: "Walking", category: "cardio")

        store.logQuickCardio(item, minutes: 30, on: date)

        let exercises = store.exercises(for: date)
        #expect(exercises.count == 1)
        #expect(exercises[0].itemID == "Walking_Outdoor")
        #expect(exercises[0].timer?.savedDurationSeconds == 1_800)
        #expect(exercises[0].timer?.isSaved == true)
    }

    @Test func exerciseTimersPersistThroughReloadAndExcludePausedTime() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }
        let date = WorkoutTestFixture.date(2026, 9, 8)
        let start = date.addingTimeInterval(3_600)
        var store = fixture.makeStore()
        store.toggleExercise(WorkoutTestFixture.exercise(id: "run", name: "Running"), on: date)
        let exerciseID = try #require(store.exercises(for: date).first?.id)

        store.updateTimer(.start, exerciseID: exerciseID, on: date, now: start)
        store.updateTimer(.start, exerciseID: exerciseID, on: date, now: start.addingTimeInterval(10))
        store = fixture.makeStore()
        let running = try #require(store.exercises(for: date).first?.timer)
        #expect(running.isRunning)
        #expect(running.elapsedSeconds(at: start.addingTimeInterval(90)) == 90)
        #expect(running.elapsedSeconds(at: start.addingTimeInterval(-30)) == 0)

        store.updateTimer(.pause, exerciseID: exerciseID, on: date, now: start.addingTimeInterval(90))
        store = fixture.makeStore()
        #expect(store.exercises(for: date)[0].timer?.elapsedSeconds(at: start.addingTimeInterval(900)) == 90)
        store.updateTimer(.resume, exerciseID: exerciseID, on: date, now: start.addingTimeInterval(900))
        store.updateTimer(.stop, exerciseID: exerciseID, on: date, now: start.addingTimeInterval(960))
        let saved = try #require(fixture.makeStore().exercises(for: date).first?.timer)
        #expect(saved.savedDurationSeconds == 150)
        #expect(saved.isSaved)
        #expect(!saved.isRunning)

        // A saved timer can be extended, and is not a saved estimate while running.
        store.updateTimer(.resume, exerciseID: exerciseID, on: date, now: start.addingTimeInterval(1_000))
        #expect(store.exercises(for: date)[0].timer?.savedDurationSeconds == nil)
        store.updateTimer(.stop, exerciseID: exerciseID, on: date, now: start.addingTimeInterval(1_030))
        #expect(store.exercises(for: date)[0].timer?.savedDurationSeconds == 180)
    }

    @Test func exerciseTimerRestartDiscardAndCopyLeaveOtherExercisesAndDaysUntouched() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }
        let date = WorkoutTestFixture.date(2026, 9, 8)
        let tomorrow = WorkoutTestFixture.date(2026, 9, 9)
        let store = fixture.makeStore()
        store.toggleExercise(WorkoutTestFixture.exercise(id: "run", name: "Running"), on: date)
        store.toggleExercise(WorkoutTestFixture.exercise(id: "cycle", name: "Cycling"), on: date)
        let first = store.exercises(for: date)[0].id
        let second = store.exercises(for: date)[1].id
        store.updateTimer(.start, exerciseID: first, on: date, now: date)
        store.setTimerIntensity(.vigorous, exerciseID: first, on: date)
        store.updateTimer(.start, exerciseID: second, on: date, now: date.addingTimeInterval(10))
        store.copyPlan(from: date, to: tomorrow)
        #expect(store.exercises(for: tomorrow).allSatisfy { $0.timer == nil })

        store.updateTimer(.restart, exerciseID: first, on: date, now: date.addingTimeInterval(100))
        #expect(store.exercises(for: date)[0].timer?.elapsedSeconds(at: date.addingTimeInterval(130)) == 30)
        #expect(store.exercises(for: date)[0].timer?.intensity == .vigorous)
        #expect(store.exercises(for: date)[1].timer?.elapsedSeconds(at: date.addingTimeInterval(130)) == 120)
        store.updateTimer(.stop, exerciseID: first, on: date, now: date.addingTimeInterval(140))
        #expect(store.exercises(for: date)[0].timer?.savedDurationSeconds == 40)
        store.updateTimer(.discard, exerciseID: first, on: date, now: date.addingTimeInterval(150))
        let restored = fixture.makeStore()
        #expect(restored.exercises(for: date).count == 2)
        #expect(restored.exercises(for: date)[0].timer == nil)
        #expect(restored.exercises(for: date)[1].timer?.isRunning == true)
        #expect(restored.exercises(for: tomorrow).allSatisfy { $0.timer == nil })
    }

    @Test func savedTimerSupportsCardioBurnWithoutRepsAndSurvivesCompletedSnapshotReload() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }
        let date = WorkoutTestFixture.date(2026, 9, 8)
        let store = fixture.makeStore()
        store.toggleExercise(
            WorkoutTestFixture.exercise(id: "Walking_Treadmill", name: "Walking", category: "cardio"),
            on: date
        )
        let exerciseID = store.exercises(for: date)[0].id
        store.setTimerIntensity(.light, exerciseID: exerciseID, on: date)
        store.updateTimer(.start, exerciseID: exerciseID, on: date, now: date)
        #expect(StrengthWorkoutBurnEstimator.estimate(
            exercises: store.exercises(for: date), bodyWeightKg: 70,
            defaultWeightUnit: .kg, defaultRPEScale: .strength
        ) == nil)
        store.updateTimer(.pause, exerciseID: exerciseID, on: date, now: date.addingTimeInterval(600))
        #expect(store.upsertCalculatedWorkout(on: date, caloriesBurned: 35, weightUnit: .kg) == nil)
        store.updateTimer(.stop, exerciseID: exerciseID, on: date, now: date.addingTimeInterval(900))
        let estimate = try #require(StrengthWorkoutBurnEstimator.estimate(
            exercises: store.exercises(for: date), bodyWeightKg: 70,
            defaultWeightUnit: .kg, defaultRPEScale: .strength
        ))
        #expect(estimate.calories == 34) // 2.8 MET × 70 kg × ten active minutes.
        #expect(estimate.performedSetCount == 0)
        #expect(estimate.repCount == 0)
        let first = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: estimate.calories, weightUnit: .kg))
        let second = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: estimate.calories, weightUnit: .kg))
        #expect(first.id == second.id)
        let restored = try #require(fixture.makeStore().workoutBurnSessions.first)
        #expect(restored.exercises[0].durationSeconds == 600)
        #expect(restored.exercises[0].intensity == .light)
        #expect(restored.performedSetCount == 0)
        #expect(restored.healthSyncVersion == 2)
    }

    @Test func timedCaloriesFollowRPEAndInvalidateSavedBurn() throws {
        let fixture = WorkoutTestFixture()
        let store = fixture.makeStore()
        let date = WorkoutTestFixture.date(2026, 9, 8)
        store.toggleExercise(WorkoutTestFixture.exercise(id: "curl", name: "Curl"), on: date)
        let exercise = try #require(store.exercises(for: date).first)
        store.updateTimer(.start, exerciseID: exercise.id, on: date, now: date)
        store.updateTimer(.stop, exerciseID: exercise.id, on: date, now: date.addingTimeInterval(600))
        func estimate() throws -> StrengthWorkoutBurnEstimate {
            try #require(StrengthWorkoutBurnEstimator.estimate(exercises: store.exercises(for: date), bodyWeightKg: 70, defaultWeightUnit: .kg, defaultRPEScale: .strength))
        }
        store.updateSet(exerciseID: exercise.id, setID: exercise.sets[0].id, on: date, rpe: "3")
        let light = try estimate()
        _ = store.upsertCalculatedWorkout(on: date, caloriesBurned: light.calories, weightUnit: .kg)
        store.updateSet(exerciseID: exercise.id, setID: exercise.sets[0].id, on: date, rpe: "9")
        #expect(store.workoutBurnSessions.isEmpty)
        let hard = try estimate()
        #expect(hard.calories > light.calories)
        let saved = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: hard.calories, weightUnit: .kg))
        #expect(saved.exercises[0].intensity == .vigorous)
        var timed = store.exercises(for: date)[0]
        for (rpe, scale) in [("9", StrengthWorkoutRPEScale.cr10), ("19", .borg)] {
            timed.sets[0].rpe = rpe
            timed.sets[0].rpeScale = scale
            #expect(StrengthWorkoutBurnEstimator.timerIntensity(for: timed, defaultRPEScale: .strength) == .vigorous)
        }
    }

    @Test func timedBurnReplacesSameExerciseRepsAndAddsToUntimedStrength() throws {
        var run = StrengthPlannedExercise(item: WorkoutTestFixture.exercise(
            id: "Running_Treadmill", name: "Running", category: "cardio"
        ))
        run.timer = StrengthExerciseTimer(accumulatedSeconds: 600, savedDurationSeconds: 600)
        var strength = StrengthPlannedExercise(item: WorkoutTestFixture.exercise(id: "curl", name: "Curl"))
        strength.sets[0].reps = "12"
        strength.sets[0].weight = "20"
        func estimate(_ exercises: [StrengthPlannedExercise]) throws -> StrengthWorkoutBurnEstimate {
            try #require(StrengthWorkoutBurnEstimator.estimate(
                exercises: exercises, bodyWeightKg: 70,
                defaultWeightUnit: .kg, defaultRPEScale: .strength
            ))
        }
        let timedOnly = try estimate([run])
        #expect(timedOnly.calories == 104)
        run.sets[0].reps = "100"
        run.sets[0].weight = "100"
        let timedWithReps = try estimate([run])
        #expect(timedWithReps.calories == timedOnly.calories)
        #expect(timedWithReps.repCount == 100)
        let strengthOnly = try estimate([strength])
        let mixed = try estimate([run, strength])
        #expect(abs(mixed.calories - timedOnly.calories - strengthOnly.calories) <= 1)
        #expect(mixed.performedSetCount == 2)
        #expect(mixed.repCount == 112)
        run.timer?.intensity = .vigorous
        #expect(try estimate([run]).calories > timedOnly.calories)
        run.timer?.savedDurationSeconds = 1_200
        #expect(try estimate([run]).calories > timedOnly.calories * 2)
    }

    @Test func legacyCardioRepsNeedSavedTimeAndCannotAddSetBasedCaloriesToStrength() throws {
        var cardio = StrengthPlannedExercise(item: WorkoutTestFixture.exercise(
            id: "Running_Treadmill", name: "Running", category: "cardio"
        ))
        cardio.sets[0].reps = "100"
        #expect(StrengthWorkoutBurnEstimator.estimate(
            exercises: [cardio], bodyWeightKg: 70,
            defaultWeightUnit: .kg, defaultRPEScale: .strength
        ) == nil)
        var strength = StrengthPlannedExercise(item: WorkoutTestFixture.exercise(id: "curl", name: "Curl"))
        strength.sets[0].reps = "10"
        let baseline = try #require(StrengthWorkoutBurnEstimator.estimate(
            exercises: [strength], bodyWeightKg: 70,
            defaultWeightUnit: .kg, defaultRPEScale: .strength
        ))
        let mixed = try #require(StrengthWorkoutBurnEstimator.estimate(
            exercises: [cardio, strength], bodyWeightKg: 70,
            defaultWeightUnit: .kg, defaultRPEScale: .strength
        ))
        #expect(mixed.calories == baseline.calories)
        #expect(mixed.repCount == 110)
    }

    @Test func timedStretchingUsesStretchingMETInsteadOfResistanceTraining() throws {
        var stretch = StrengthPlannedExercise(item: WorkoutTestFixture.exercise(
            id: "hamstring-stretch", name: "Hamstring Stretch", category: "stretching"
        ))
        stretch.timer = StrengthExerciseTimer(accumulatedSeconds: 600, savedDurationSeconds: 600, intensity: .vigorous)
        let estimate = try #require(StrengthWorkoutBurnEstimator.estimate(
            exercises: [stretch], bodyWeightKg: 70,
            defaultWeightUnit: .kg, defaultRPEScale: .strength
        ))
        #expect(estimate.calories == 28)
    }

    @Test func legacyExerciseJSONWithoutTimerOrCompletedDurationStillDecodes() throws {
        let exercise = StrengthPlannedExercise(item: WorkoutTestFixture.exercise(id: "row", name: "Barbell Row"))
        var plannedJSON = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(exercise)) as? [String: Any])
        plannedJSON.removeValue(forKey: "timer")
        let restored = try JSONDecoder().decode(StrengthPlannedExercise.self, from: JSONSerialization.data(withJSONObject: plannedJSON))
        #expect(restored.timer == nil)
        #expect(restored.sets == exercise.sets)
        let completedJSON: [String: Any] = [
            "id": UUID().uuidString, "itemID": "row", "name": "Barbell Row",
            "targetMuscles": ["back"], "equipment": "barbell", "sets": []
        ]
        let completed = try JSONDecoder().decode(StrengthCompletedExercise.self, from: JSONSerialization.data(withJSONObject: completedJSON))
        #expect(completed.durationSeconds == nil)
        #expect(completed.intensity == nil)
    }

    @Test func runningTimerEditsPreserveStrengthBurnUntilDurationIsSaved() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }
        let date = WorkoutTestFixture.date(2026, 9, 8)
        let store = fixture.makeStore()
        store.toggleExercise(WorkoutTestFixture.exercise(id: "curl", name: "Curl"), on: date)
        let exercise = store.exercises(for: date)[0]
        store.updateSet(exerciseID: exercise.id, setID: exercise.sets[0].id, on: date, reps: "10")
        let original = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: 40, weightUnit: .kg))
        var deleted: [UUID] = []
        store.onWorkoutBurnDeleted = { deleted.append($0) }

        store.setTimerIntensity(.vigorous, exerciseID: exercise.id, on: date)
        store.updateTimer(.start, exerciseID: exercise.id, on: date, now: date)
        store.updateTimer(.pause, exerciseID: exercise.id, on: date, now: date.addingTimeInterval(60))
        store.updateTimer(.resume, exerciseID: exercise.id, on: date, now: date.addingTimeInterval(100))
        #expect(store.caloriesBurned(on: date) == 40)
        #expect(deleted.isEmpty)

        store.updateTimer(.stop, exerciseID: exercise.id, on: date, now: date.addingTimeInterval(160))
        #expect(store.caloriesBurned(on: date) == nil)
        #expect(fixture.makeStore().caloriesBurned(on: date) == nil)
        #expect(deleted == [original.id])
        let replacement = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: 60, weightUnit: .kg))
        // Repeating Stop does not change the saved inputs or remove the result.
        store.updateTimer(.stop, exerciseID: exercise.id, on: date, now: date.addingTimeInterval(200))
        #expect(store.latestSession(on: date)?.id == replacement.id)
        #expect(deleted == [original.id])
    }

    @Test func resumingRestartingAndDiscardingSavedTimersRemoveTheirCalculatedBurn() throws {
        for action in [StrengthExerciseTimerAction.resume, .restart, .discard] {
            let fixture = WorkoutTestFixture()
            defer { fixture.cleanUp() }
            let date = WorkoutTestFixture.date(2026, 9, 8)
            let store = fixture.makeStore()
            store.toggleExercise(WorkoutTestFixture.exercise(id: "run", name: "Run", category: "cardio"), on: date)
            let exerciseID = store.exercises(for: date)[0].id
            store.updateTimer(.start, exerciseID: exerciseID, on: date, now: date)
            store.updateTimer(.stop, exerciseID: exerciseID, on: date, now: date.addingTimeInterval(300))
            let burn = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: 50, weightUnit: .kg))
            var deleted: [UUID] = []
            store.onWorkoutBurnDeleted = {
                deleted.append($0)
                #expect(fixture.makeStore().caloriesBurned(on: date) == nil)
            }

            store.updateTimer(action, exerciseID: exerciseID, on: date, now: date.addingTimeInterval(400))
            #expect(store.caloriesBurned(on: date) == nil)
            #expect(deleted == [burn.id])
            #expect(store.exercises(for: date).count == 1)
        }
    }

    @Test func changingSavedTimerIntensityInvalidatesBurnButSameIntensityDoesNot() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }
        let date = WorkoutTestFixture.date(2026, 9, 8)
        let store = fixture.makeStore()
        store.toggleExercise(WorkoutTestFixture.exercise(id: "run", name: "Run", category: "cardio"), on: date)
        let exerciseID = store.exercises(for: date)[0].id
        store.updateTimer(.start, exerciseID: exerciseID, on: date, now: date)
        store.updateTimer(.stop, exerciseID: exerciseID, on: date, now: date.addingTimeInterval(300))
        let burn = try #require(store.upsertCalculatedWorkout(on: date, caloriesBurned: 50, weightUnit: .kg))
        var deleted: [UUID] = []
        store.onWorkoutBurnDeleted = { deleted.append($0) }

        store.setTimerIntensity(.moderate, exerciseID: exerciseID, on: date)
        #expect(store.caloriesBurned(on: date) == 50)
        #expect(deleted.isEmpty)
        store.setTimerIntensity(.light, exerciseID: exerciseID, on: date)
        #expect(store.caloriesBurned(on: date) == nil)
        #expect(deleted == [burn.id])
        #expect(store.exercises(for: date)[0].timer?.savedDurationSeconds == 300)
    }

    @Test func removingSavedTimedExerciseInvalidatesOnlyItsDayAndPreservesLegacySessions() throws {
        for toggleRemoval in [false, true] {
            let fixture = WorkoutTestFixture()
            defer { fixture.cleanUp() }
            let date = WorkoutTestFixture.date(2026, 9, 8)
            let otherDate = WorkoutTestFixture.date(2026, 9, 7)
            let store = fixture.makeStore()
            let item = WorkoutTestFixture.exercise(id: "run", name: "Run", category: "cardio")
            for day in [date, otherDate] {
                store.toggleExercise(item, on: day)
                let id = store.exercises(for: day)[0].id
                store.updateTimer(.start, exerciseID: id, on: day, now: day)
                store.updateTimer(.stop, exerciseID: id, on: day, now: day.addingTimeInterval(300))
                _ = try #require(store.upsertCalculatedWorkout(on: day, caloriesBurned: 50, weightUnit: .kg))
            }
            let removedBurn = try #require(store.workoutBurnSessions.first { $0.stableDiaryDateKey == StrengthWorkoutStore.dateKey(for: date) })
            let otherBurn = try #require(store.workoutBurnSessions.first { $0.stableDiaryDateKey == StrengthWorkoutStore.dateKey(for: otherDate) })
            let legacy = try #require(store.completeWorkout(
                on: date, startedAt: date, completedAt: date.addingTimeInterval(300),
                elapsedSeconds: 300, weightUnit: .kg
            ))
            var deleted: [UUID] = []
            store.onWorkoutBurnDeleted = { deleted.append($0) }
            if toggleRemoval {
                store.toggleExercise(item, on: date)
            } else {
                store.removeExercise(store.exercises(for: date)[0].id, on: date)
            }
            let restored = fixture.makeStore()
            #expect(restored.caloriesBurned(on: date) == nil)
            #expect(restored.caloriesBurned(on: otherDate) == 50)
            #expect(Set(restored.completedSessions.map(\.id)) == [legacy.id, otherBurn.id])
            #expect(deleted == [removedBurn.id])
        }
    }

    @Test func timerEraSessionJSONWithoutBurnFieldsStillDecodes() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }

        let date = WorkoutTestFixture.date(2026, 7, 17)
        let store = fixture.makeStore()
        store.toggleExercise(WorkoutTestFixture.exercise(id: "row", name: "Barbell Row"), on: date)
        _ = try #require(
            store.completeWorkout(
                on: date,
                startedAt: date,
                completedAt: date.addingTimeInterval(600),
                elapsedSeconds: 600,
                weightUnit: .kg
            )
        )

        let encoded = try #require(fixture.defaults.data(forKey: fixture.storageKey))
        var root = try #require(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        var sessions = try #require(root["completedSessions"] as? [[String: Any]])
        sessions[0].removeValue(forKey: "caloriesBurned")
        sessions[0].removeValue(forKey: "healthSyncVersion")
        root["completedSessions"] = sessions
        fixture.defaults.set(try JSONSerialization.data(withJSONObject: root), forKey: fixture.storageKey)

        let restored = try #require(fixture.makeStore().completedSessions.first)
        #expect(restored.caloriesBurned == nil)
        #expect(restored.healthSyncVersion == nil)
        #expect(restored.durationSeconds == 600)
    }

    @Test func burnCalculationPreservesLegacySessionsAndHealthMergePreservesSetDetails() throws {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }

        let date = WorkoutTestFixture.date(2026, 7, 16)
        let store = fixture.makeStore()
        store.toggleExercise(WorkoutTestFixture.exercise(id: "press", name: "Shoulder Press"), on: date)
        let exercise = try #require(store.exercises(for: date).first)
        let set = try #require(exercise.sets.first)
        store.updateSet(
            exerciseID: exercise.id,
            setID: set.id,
            on: date,
            weight: "30",
            weightUnit: .kg,
            reps: "10",
            rpe: "8"
        )

        let legacy = try #require(
            store.completeWorkout(
                on: date,
                startedAt: date,
                completedAt: date.addingTimeInterval(600),
                elapsedSeconds: 600,
                weightUnit: .kg
            )
        )
        let burn = try #require(
            store.upsertCalculatedWorkout(
                on: date,
                caloriesBurned: 160,
                weightUnit: .kg,
                calculatedAt: date.addingTimeInterval(1_200)
            )
        )

        #expect(store.completedSessions.contains(where: { $0.id == legacy.id }))
        #expect(store.completedSessions.count == 2)
        #expect(store.workoutBurnSessions.map(\.id) == [burn.id])

        let healthVersion = StrengthWorkoutSession(
            id: burn.id,
            diaryDate: date,
            diaryDateKey: burn.stableDiaryDateKey,
            startedAt: date.addingTimeInterval(1_800),
            completedAt: date.addingTimeInterval(1_800),
            durationSeconds: 0,
            exercises: [],
            caloriesBurned: 190,
            healthSyncVersion: 2
        )
        store.importWorkoutBurnSessions([healthVersion])

        let merged = try #require(store.workoutBurnSessions.first)
        #expect(merged.caloriesBurned == 190)
        #expect(merged.healthSyncVersion == 2)
        #expect(merged.exercises.first?.name == "Shoulder Press")
        #expect(merged.performedSetCount == 1)
    }

    @Test func clearAllResetsMemoryAndRemovesPersistedWorkoutState() {
        let fixture = WorkoutTestFixture()
        defer { fixture.cleanUp() }

        let date = WorkoutTestFixture.date(2026, 7, 14)
        let exercise = WorkoutTestFixture.exercise(id: "deadlift", name: "Deadlift")
        let store = fixture.makeStore()
        store.toggleExercise(exercise, on: date)
        store.toggleSaved(exercise.id)
        store.updatePreferences {
            $0.frequencyDays = 6
            $0.targetMuscles = ["Hamstrings"]
        }

        #expect(fixture.defaults.data(forKey: fixture.storageKey) != nil)
        store.clearAll()

        #expect(store.dayPlans.isEmpty)
        #expect(store.completedSessions.isEmpty)
        #expect(store.savedExerciseIDs.isEmpty)
        #expect(store.preferences == StrengthWorkoutPreferences())
        #expect(fixture.defaults.object(forKey: fixture.storageKey) == nil)

        let reloaded = fixture.makeStore()
        #expect(reloaded.dayPlans.isEmpty)
        #expect(reloaded.completedSessions.isEmpty)
        #expect(reloaded.savedExerciseIDs.isEmpty)
        #expect(reloaded.preferences == StrengthWorkoutPreferences())
    }
}

@MainActor
final class WorkoutTestFixture {
    let suiteName: String
    let storageKey: String
    let defaults: UserDefaults

    init() {
        let identifier = UUID().uuidString
        suiteName = "StrengthWorkoutStoreTests.\(identifier)"
        storageKey = "strength-workout-state.\(identifier)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
    }

    func makeStore() -> StrengthWorkoutStore {
        StrengthWorkoutStore(defaults: defaults, storageKey: storageKey)
    }

    func cleanUp() {
        defaults.removePersistentDomain(forName: suiteName)
    }

    static func date(_ year: Int, _ month: Int, _ day: Int, hour: Int = 0) -> Date {
        Calendar.current.date(from: DateComponents(year: year, month: month, day: day, hour: hour))!
    }

    static func exercise(
        id: String,
        name: String,
        equipment: String = "barbell",
        muscles: [String] = ["chest"],
        category: String = "strength"
    ) -> ExerciseLibraryItem {
        ExerciseLibraryItem(
            id: id,
            name: name,
            rawLevel: "intermediate",
            force: "push",
            mechanic: "compound",
            category: category,
            rawEquipment: equipment,
            primaryMuscles: muscles,
            secondaryMuscles: ["triceps"],
            instructions: ["Control the repetition."]
        )
    }
}
