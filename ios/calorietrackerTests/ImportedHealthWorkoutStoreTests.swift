import Foundation
import HealthKit
import Testing
@testable import calorietracker

@Suite struct ImportedHealthWorkoutStoreTests {
    @Test func importWorkoutsDedupesByHealthKitUUID() {
        let defaults = makeDefaults("dedupe")
        let store = ImportedHealthWorkoutStore(defaults: defaults)
        let id = UUID()
        let first = sampleWorkout(id: id, title: "Running", calories: 300)
        let updated = sampleWorkout(id: id, title: "Running", calories: 420)

        store.importWorkouts([first])
        store.importWorkouts([updated])

        #expect(store.workouts.count == 1)
        #expect(store.workouts.first?.totalEnergyBurned == 420)
    }

    @Test func workoutsOnDateFiltersByDiaryDay() {
        let defaults = makeDefaults("day-filter")
        let store = ImportedHealthWorkoutStore(defaults: defaults)
        let day = StrengthWorkoutDate.date(for: "2026-09-10")!
        let otherDay = StrengthWorkoutDate.date(for: "2026-09-09")!
        store.importWorkouts([
            sampleWorkout(id: UUID(), title: "Cycling", calories: 250, day: day),
            sampleWorkout(id: UUID(), title: "Yoga", calories: 120, day: otherDay),
        ])

        #expect(store.workouts(on: day).count == 1)
        #expect(store.workouts(on: day).first?.activityTitle == "Cycling")
    }

    @Test func dailyAggregationGroupsSessionsByDay() {
        let start = StrengthWorkoutDate.date(for: "2026-09-08")!
        let end = StrengthWorkoutDate.date(for: "2026-09-10")!
        let day = StrengthWorkoutDate.date(for: "2026-09-09")!
        let workouts = [
            sampleWorkout(id: UUID(), title: "Walk", calories: 100, day: day),
            sampleWorkout(id: UUID(), title: "Run", calories: 200, day: day),
        ]

        let days = ImportedHealthWorkoutAggregation.daily(
            workouts: workouts,
            in: start...end
        )

        #expect(days.count == 1)
        #expect(days.first?.sessionCount == 2)
        #expect(days.first?.totalCalories == 300)
    }

    @Test func activityTitleMapsCommonTypes() {
        #expect(ImportedHealthWorkoutFormatting.activityTitle(for: .running) == "Running")
        #expect(ImportedHealthWorkoutFormatting.activityTitle(for: .traditionalStrengthTraining) == "Strength Training")
        #expect(ImportedHealthWorkoutFormatting.activityTitle(for: .other) == "Workout")
        #expect(ImportedHealthWorkoutFormatting.activityTitle(for: .handCycling) == "Hand Cycling")
        #expect(ImportedHealthWorkoutFormatting.activityTitle(for: .fitnessGaming) == "Fitness Gaming")
        #expect(ImportedHealthWorkoutFormatting.activityTitle(for: .cardioDance) == "Cardio Dance")
        #expect(ImportedHealthWorkoutFormatting.activityTitle(for: .socialDance) == "Social Dance")
        #expect(ImportedHealthWorkoutFormatting.activityTitle(for: .taiChi) == "Tai Chi")
    }

    @Test func synchronizeRemovesWorkoutsDeletedFromHealthInQueryWindow() {
        let defaults = makeDefaults("reconcile-delete")
        let store = ImportedHealthWorkoutStore(defaults: defaults)
        let queryStart = StrengthWorkoutDate.date(for: "2026-09-01")!
        let keptDay = StrengthWorkoutDate.date(for: "2026-09-10")!
        let removedDay = StrengthWorkoutDate.date(for: "2026-09-09")!
        let kept = sampleWorkout(id: UUID(), title: "Run", calories: 300, day: keptDay)
        let removed = sampleWorkout(id: UUID(), title: "Walk", calories: 100, day: removedDay)
        store.importWorkouts([kept, removed])
        #expect(store.workouts.count == 2)

        store.synchronize(with: [kept], queryStart: queryStart)

        #expect(store.workouts.count == 1)
        #expect(store.workouts.first?.id == kept.id)
    }

    @Test func synchronizeUpdatesExistingWorkoutFields() {
        let defaults = makeDefaults("reconcile-update")
        let store = ImportedHealthWorkoutStore(defaults: defaults)
        let id = UUID()
        let day = StrengthWorkoutDate.date(for: "2026-09-10")!
        let queryStart = StrengthWorkoutDate.date(for: "2026-09-01")!
        store.importWorkouts([sampleWorkout(id: id, title: "Run", calories: 200, day: day)])
        let updated = sampleWorkout(id: id, title: "Run", calories: 450, day: day)

        store.synchronize(with: [updated], queryStart: queryStart)

        #expect(store.workouts.count == 1)
        #expect(store.workouts.first?.totalEnergyBurned == 450)
    }

    @Test func dailyAverageIgnoresDaysWithoutCalorieSamples() {
        let start = StrengthWorkoutDate.date(for: "2026-09-08")!
        let end = StrengthWorkoutDate.date(for: "2026-09-10")!
        let dayWithCalories = StrengthWorkoutDate.date(for: "2026-09-09")!
        let dayWithoutCalories = StrengthWorkoutDate.date(for: "2026-09-10")!
        let workouts = [
            sampleWorkout(id: UUID(), title: "Run", calories: 300, day: dayWithCalories),
            sampleWorkoutWithoutCalories(id: UUID(), title: "Stretch", day: dayWithoutCalories),
        ]

        let days = ImportedHealthWorkoutAggregation.daily(
            workouts: workouts,
            in: start...end
        )
        let daysWithCalories = days.filter { $0.totalCalories > 0 }
        let average = daysWithCalories.isEmpty
            ? 0
            : Int((Double(daysWithCalories.reduce(0) { $0 + $1.totalCalories }) / Double(daysWithCalories.count)).rounded())

        #expect(days.count == 2)
        #expect(average == 300)
    }

    private func makeDefaults(_ identifier: String) -> UserDefaults {
        let suiteName = "ImportedHealthWorkoutStoreTests.\(identifier)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }

    private func sampleWorkoutWithoutCalories(
        id: UUID,
        title: String,
        day: Date = StrengthWorkoutDate.date(for: "2026-09-10")!
    ) -> ImportedHealthWorkout {
        let key = StrengthWorkoutDate.key(for: day)
        let start = Calendar.current.date(bySettingHour: 8, minute: 0, second: 0, of: day) ?? day
        let end = Calendar.current.date(byAdding: .minute, value: 30, to: start) ?? start
        return ImportedHealthWorkout(
            id: id,
            activityTypeRaw: HKWorkoutActivityType.yoga.rawValue,
            activityTitle: title,
            startedAt: start,
            endedAt: end,
            durationSeconds: 30 * 60,
            totalEnergyBurned: nil,
            sourceName: "Apple Watch",
            deviceName: nil,
            diaryDateKey: key
        )
    }

    private func sampleWorkout(
        id: UUID,
        title: String,
        calories: Int,
        day: Date = StrengthWorkoutDate.date(for: "2026-09-10")!
    ) -> ImportedHealthWorkout {
        let key = StrengthWorkoutDate.key(for: day)
        let start = Calendar.current.date(bySettingHour: 8, minute: 0, second: 0, of: day) ?? day
        let end = Calendar.current.date(byAdding: .minute, value: 45, to: start) ?? start
        return ImportedHealthWorkout(
            id: id,
            activityTypeRaw: HKWorkoutActivityType.running.rawValue,
            activityTitle: title,
            startedAt: start,
            endedAt: end,
            durationSeconds: 45 * 60,
            totalEnergyBurned: calories,
            sourceName: "Apple Watch",
            deviceName: "Apoorv’s Apple Watch",
            diaryDateKey: key
        )
    }
}
