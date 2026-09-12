import Foundation

enum FreeExerciseDBRecordsCache {
    private static let lock = NSLock()
    private static var cachedRecords: [FreeExerciseDBRecord]?

    static func records() -> [FreeExerciseDBRecord] {
        lock.lock()
        defer { lock.unlock() }
        if let cachedRecords {
            return cachedRecords
        }

        guard
            let url = FreeExerciseDBAssetResolver.exercisesJSONURL(),
            let data = try? Data(contentsOf: url),
            let records = try? JSONDecoder().decode([FreeExerciseDBRecord].self, from: data)
        else {
            cachedRecords = []
            return []
        }

        cachedRecords = records
        return records
    }
}

struct FreeExerciseDBLoader {
    static func load() -> [ExerciseLibraryItem] {
        let records = FreeExerciseDBRecordsCache.records()
        guard !records.isEmpty else {
            return []
        }

        return records
            .compactMap { record in
                Self.libraryItem(from: record)
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private static func libraryItem(from record: FreeExerciseDBRecord) -> ExerciseLibraryItem? {
        let id = record.id.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = record.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty, !name.isEmpty else {
            return nil
        }

        return ExerciseLibraryItem(
            id: id,
            name: name,
            rawLevel: record.level,
            imagePaths: record.images,
            force: record.force,
            mechanic: record.mechanic,
            category: record.category,
            rawEquipment: record.equipment,
            primaryMuscles: record.primaryMuscles,
            secondaryMuscles: record.secondaryMuscles,
            instructions: record.instructions
        )
    }
}

struct FreeExerciseDBRecord: Decodable {
    let id: String
    let name: String
    let force: String?
    let level: String?
    let mechanic: String?
    let equipment: String?
    let primaryMuscles: [String]
    let secondaryMuscles: [String]
    let instructions: [String]
    let category: String?
    let images: [String]
}
