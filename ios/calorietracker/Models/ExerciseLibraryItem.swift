import Foundation

struct ExerciseLibraryItem: Identifiable, Hashable {
    let id: String
    let name: String
    let rawLevel: String
    let imagePaths: [String]
    let force: String
    let mechanic: String
    let category: String
    let rawEquipment: String
    let primaryMuscles: [String]
    let secondaryMuscles: [String]
    let instructions: [String]
    /// Precomputed once so library filtering does not rebuild haystacks every keystroke.
    let searchableText: String

    init(
        id: String,
        name: String,
        rawLevel: String? = nil,
        imagePaths: [String] = [],
        force: String? = nil,
        mechanic: String? = nil,
        category: String? = nil,
        rawEquipment: String? = nil,
        primaryMuscles: [String] = [],
        secondaryMuscles: [String] = [],
        instructions: [String] = []
    ) {
        self.id = id
        self.name = name
        self.rawLevel = Self.metadataTitle(rawLevel)
        self.imagePaths = imagePaths
        self.force = Self.metadataTitle(force)
        self.mechanic = Self.metadataTitle(mechanic)
        self.category = Self.metadataTitle(category)
        self.rawEquipment = Self.metadataTitle(rawEquipment)
        self.primaryMuscles = Self.metadataTitles(primaryMuscles)
        self.secondaryMuscles = Self.metadataTitles(secondaryMuscles)
        let cleanedInstructions = instructions.compactMap { $0.trimmed.nilIfEmpty }
        self.instructions = cleanedInstructions
        self.searchableText = Self.buildSearchableText(
            name: name,
            rawLevel: self.rawLevel,
            force: self.force,
            mechanic: self.mechanic,
            category: self.category,
            rawEquipment: self.rawEquipment,
            primaryMuscles: self.primaryMuscles,
            secondaryMuscles: self.secondaryMuscles,
            instructions: cleanedInstructions
        )
    }

    var primaryMusclesTitle: String {
        primaryMuscles.isEmpty ? "Unspecified" : primaryMuscles.joined(separator: ", ")
    }

    var secondaryMusclesTitle: String {
        secondaryMuscles.isEmpty ? "None" : secondaryMuscles.joined(separator: ", ")
    }

    var databaseMetadataSummary: String {
        [category, force, mechanic]
            .filter { $0 != "Unspecified" }
            .joined(separator: " - ")
            .nilIfEmpty ?? String(localized: "Database metadata")
    }

    nonisolated private static func buildSearchableText(
        name: String,
        rawLevel: String,
        force: String,
        mechanic: String,
        category: String,
        rawEquipment: String,
        primaryMuscles: [String],
        secondaryMuscles: [String],
        instructions: [String]
    ) -> String {
        [
            name,
            rawLevel,
            force,
            mechanic,
            category,
            rawEquipment,
            primaryMuscles.joined(separator: " "),
            secondaryMuscles.joined(separator: " "),
            instructions.joined(separator: " ")
        ]
        .joined(separator: " ")
        .lowercased()
    }

    nonisolated private static func metadataTitles(_ values: [String]) -> [String] {
        values
            .map(metadataTitle)
            .filter { $0 != "Unspecified" }
    }

    nonisolated private static func metadataTitle(_ value: String?) -> String {
        guard let value else { return "Unspecified" }

        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "Unspecified" }

        return trimmed
            .split(separator: " ")
            .map { word in
                word
                    .split(separator: "-", omittingEmptySubsequences: false)
                    .map { segment in
                        guard let first = segment.first else { return "" }
                        return first.uppercased() + segment.dropFirst().lowercased()
                    }
                    .joined(separator: "-")
            }
            .joined(separator: " ")
    }
}

enum ExerciseLibrarySort: String, CaseIterable, Identifiable, Codable, Hashable {
    case name = "Name"
    case level = "Level"
    case primaryMuscles = "Primary"
    case secondaryMuscles = "Secondary"
    case category = "Category"
    case force = "Force"
    case mechanic = "Mechanic"
    case rawEquipment = "Equipment"

    var id: String { rawValue }
    // Sort titles share catalog keys with the filter-pill titles ("Name", "Level",
    // "Primary", ...) so the results-header subtitle localizes like the rest of the UI.
    var title: String { String(localized: String.LocalizationValue(rawValue)) }
}

private extension String {
    var trimmed: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
