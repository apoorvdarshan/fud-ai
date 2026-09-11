import Foundation

@Observable
final class ImportedHealthWorkoutStore {
    struct PersistedState: Codable, Equatable {
        var version = 1
        var workouts: [ImportedHealthWorkout] = []
    }

    static let defaultStorageKey = "fudai.health.workouts.imported.v1"

    private(set) var workouts: [ImportedHealthWorkout] = []

    private let defaults: UserDefaults
    private let storageKey: String

    init(defaults: UserDefaults = .standard, storageKey: String = ImportedHealthWorkoutStore.defaultStorageKey) {
        self.defaults = defaults
        self.storageKey = storageKey
        load()
    }

    var sortedWorkouts: [ImportedHealthWorkout] {
        workouts.sorted {
            if $0.diaryDateKey == $1.diaryDateKey {
                return $0.startedAt > $1.startedAt
            }
            return $0.diaryDateKey > $1.diaryDateKey
        }
    }

    func workouts(on date: Date, calendar: Calendar = .current) -> [ImportedHealthWorkout] {
        let key = StrengthWorkoutDate.key(for: calendar.startOfDay(for: date), calendar: calendar)
        return workouts
            .filter { $0.diaryDateKey == key }
            .sorted { $0.startedAt < $1.startedAt }
    }

    func workouts(from start: Date, through end: Date, calendar: Calendar = .current) -> [ImportedHealthWorkout] {
        workouts.filter {
            let day = calendar.startOfDay(for: $0.calendarDiaryDate)
            return day >= calendar.startOfDay(for: start) && day <= calendar.startOfDay(for: end)
        }
        .sorted {
            if $0.diaryDateKey == $1.diaryDateKey {
                return $0.startedAt < $1.startedAt
            }
            return $0.diaryDateKey < $1.diaryDateKey
        }
    }

    func importWorkouts(_ imported: [ImportedHealthWorkout]) {
        guard !imported.isEmpty else { return }
        var changed = false
        for workout in imported {
            if let index = workouts.firstIndex(where: { $0.id == workout.id }) {
                if workouts[index] != workout {
                    workouts[index] = workout
                    changed = true
                }
            } else {
                workouts.append(workout)
                changed = true
            }
        }
        if changed { save() }
    }

    func reloadFromDefaults() {
        workouts = []
        load()
    }

    func clearAll() {
        workouts = []
        defaults.removeObject(forKey: storageKey)
    }

    private func load() {
        guard let data = defaults.data(forKey: storageKey),
              let state = try? JSONDecoder().decode(PersistedState.self, from: data),
              state.version == 1
        else { return }
        workouts = state.workouts
    }

    private func save() {
        let state = PersistedState(workouts: workouts)
        guard let data = try? JSONEncoder().encode(state) else { return }
        defaults.set(data, forKey: storageKey)
    }
}
