import Foundation

/// Tokenized AND search for the exercise library, with optional query aliases.
enum ExerciseSearchMatcher {
    /// Maps common multi-word phrases to catalog exercise IDs.
    static let aliases: [String: String] = [
        "cable pushdown": "Triceps_Pushdown",
        "triceps cable pushdown": "Triceps_Pushdown",
        "tricep cable pushdown": "Triceps_Pushdown",
        "tricep pushdown": "Triceps_Pushdown",
    ]

    static func tokens(from query: String) -> [String] {
        query
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(whereSeparator: \.isWhitespace)
            .map(String.init)
            .filter { !$0.isEmpty }
    }

    static func matches(searchableText: String, query: String, exerciseID: String) -> Bool {
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let tokens = tokens(from: normalizedQuery)
        guard !tokens.isEmpty else { return true }

        if let aliasID = aliases[normalizedQuery], aliasID == exerciseID {
            return true
        }

        return tokens.allSatisfy { searchableText.contains($0) }
    }
}
