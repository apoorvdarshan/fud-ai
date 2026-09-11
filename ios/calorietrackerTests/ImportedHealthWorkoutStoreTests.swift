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
    }

    private func makeDefaults(_ identifier: String) -> UserDefaults {
        let suiteName = "ImportedHealthWorkoutStoreTests.\(identifier)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
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
