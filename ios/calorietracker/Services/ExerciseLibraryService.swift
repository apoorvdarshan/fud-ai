import Foundation

struct ExerciseCategoryCount: Identifiable, Hashable {
    let category: String
    let count: Int

    var id: String { category }
}

struct ExerciseLibraryService {
    static let shared = ExerciseLibraryService()

    let exercises: [ExerciseLibraryItem]

    var availableForces: [String] {
        Self.sortedUnique(exercises.map(\.force))
    }

    var availableLevels: [String] {
        Self.sortedUnique(exercises.map(\.rawLevel))
    }

    var availableMechanics: [String] {
        Self.sortedUnique(exercises.map(\.mechanic))
    }

    var availableCategoryCounts: [ExerciseCategoryCount] {
        Dictionary(grouping: exercises, by: \.category)
            .map { ExerciseCategoryCount(category: $0.key, count: $0.value.count) }
            .sorted { lhs, rhs in
                if lhs.count == rhs.count {
                    return lhs.category.localizedCaseInsensitiveCompare(rhs.category) == .orderedAscending
                }
                return lhs.count > rhs.count
            }
    }

    var availableRawEquipment: [String] {
        Self.sortedUnique(exercises.map(\.rawEquipment))
    }

    var availablePrimaryMuscles: [String] {
        Self.sortedUnique(exercises.flatMap(\.primaryMuscles))
    }

    var availableSecondaryMuscles: [String] {
        Self.sortedUnique(exercises.flatMap(\.secondaryMuscles))
    }

    init(exercises: [ExerciseLibraryItem]? = nil) {
        if let exercises {
            self.exercises = exercises
            return
        }

        self.exercises = FreeExerciseDBLoader.load()
    }

    func filtered(
        levels: Set<String>,
        rawEquipment: Set<String>,
        primaryMuscles: Set<String>,
        secondaryMuscles: Set<String>,
        forces: Set<String>,
        mechanics: Set<String>,
        categories: Set<String>,
        sort: ExerciseLibrarySort,
        searchText: String
    ) -> [ExerciseLibraryItem] {
        let filteredItems = exercises.filter { item in
            let matchesLevel = levels.isEmpty || levels.contains(item.rawLevel)
            let matchesRawEquipment = rawEquipment.isEmpty || rawEquipment.contains(item.rawEquipment)
            let matchesPrimaryMuscle = primaryMuscles.isEmpty || item.primaryMuscles.contains { primaryMuscles.contains($0) }
            let matchesSecondaryMuscle = secondaryMuscles.isEmpty || item.secondaryMuscles.contains { secondaryMuscles.contains($0) }
            let matchesForce = forces.isEmpty || forces.contains(item.force)
            let matchesMechanic = mechanics.isEmpty || mechanics.contains(item.mechanic)
            let matchesCategory = categories.isEmpty || categories.contains(item.category)
            let matchesSearch = ExerciseSearchMatcher.matches(
                searchableText: item.searchableText,
                query: searchText,
                exerciseID: item.id
            )

            return matchesLevel &&
                matchesRawEquipment &&
                matchesPrimaryMuscle &&
                matchesSecondaryMuscle &&
                matchesForce &&
                matchesMechanic &&
                matchesCategory &&
                matchesSearch
        }

        return filteredItems.sorted { lhs, rhs in
            switch sort {
            case .name:
                return lhs.name < rhs.name
            case .level:
                if Self.levelSortRank(lhs.rawLevel) == Self.levelSortRank(rhs.rawLevel) {
                    return lhs.name < rhs.name
                }
                return Self.levelSortRank(lhs.rawLevel) < Self.levelSortRank(rhs.rawLevel)
            case .primaryMuscles:
                return Self.compare(lhs.primaryMusclesTitle, rhs.primaryMusclesTitle, lhsName: lhs.name, rhsName: rhs.name)
            case .secondaryMuscles:
                return Self.compare(lhs.secondaryMusclesTitle, rhs.secondaryMusclesTitle, lhsName: lhs.name, rhsName: rhs.name)
            case .category:
                return Self.compare(lhs.category, rhs.category, lhsName: lhs.name, rhsName: rhs.name)
            case .force:
                return Self.compare(lhs.force, rhs.force, lhsName: lhs.name, rhsName: rhs.name)
            case .mechanic:
                return Self.compare(lhs.mechanic, rhs.mechanic, lhsName: lhs.name, rhsName: rhs.name)
            case .rawEquipment:
                return Self.compare(lhs.rawEquipment, rhs.rawEquipment, lhsName: lhs.name, rhsName: rhs.name)
            }
        }
    }

    private static func sortedUnique(_ values: [String]) -> [String] {
        Array(Set(values.filter { !$0.isEmpty }))
            .sorted { lhs, rhs in
                if levelSortRank(lhs) == levelSortRank(rhs) {
                    return lhs.localizedCaseInsensitiveCompare(rhs) == .orderedAscending
                }
                return levelSortRank(lhs) < levelSortRank(rhs)
            }
    }

    private static func compare(_ lhs: String, _ rhs: String, lhsName: String, rhsName: String) -> Bool {
        if lhs == rhs {
            return lhsName < rhsName
        }
        return lhs.localizedCaseInsensitiveCompare(rhs) == .orderedAscending
    }

    private static func levelSortRank(_ level: String) -> Int {
        switch level.lowercased() {
        case "beginner": return 0
        case "intermediate": return 1
        case "expert", "advanced": return 2
        default: return 3
        }
    }
}
