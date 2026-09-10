import Foundation

enum AllergenAssessment: String, Codable, Sendable {
    case declaredAllergenMatch
    case mayContain
    case possibleAllergen
    case unableToAssess

    var displayName: String {
        switch self {
        case .declaredAllergenMatch:
            return LocalizedDisplayText.text("Declared allergen match")
        case .mayContain:
            return LocalizedDisplayText.text("May contain")
        case .possibleAllergen:
            return LocalizedDisplayText.text("Possible allergen")
        case .unableToAssess:
            return LocalizedDisplayText.text("Unable to assess")
        }
    }
}

struct AllergenAnalysis: Equatable, Sendable {
    let assessment: AllergenAssessment
    let matchedSensitivities: [String]
    let evidence: [String]

    var summary: String {
        guard !matchedSensitivities.isEmpty else { return assessment.displayName }
        return "\(assessment.displayName): \(matchedSensitivities.joined(separator: ", "))"
    }

    static func evaluate(entry: FoodEntry, sensitivities: [String]) -> AllergenAnalysis {
        let configured = sensitivities.map(Self.normalize).filter { !$0.isEmpty }
        guard !configured.isEmpty else {
            return AllergenAnalysis(assessment: .unableToAssess, matchedSensitivities: [], evidence: [])
        }

        let sources: [(AllergenAssessment, String, [String])] = [
            (.declaredAllergenMatch, LocalizedDisplayText.text("Declared product allergens"), entry.productMetadata?.allergens ?? []),
            (.mayContain, LocalizedDisplayText.text("Trace warning"), entry.productMetadata?.traces ?? []),
            (.possibleAllergen, LocalizedDisplayText.text("Ingredient label"), [entry.productMetadata?.ingredientsText].compactMap { $0 }),
            (.possibleAllergen, LocalizedDisplayText.text("Ingredient name"), entry.ingredients.map(\.name)),
            (.possibleAllergen, LocalizedDisplayText.text("Food name"), [entry.name])
        ]

        for (assessment, source, values) in sources {
            let matches = configured.enumerated().compactMap { index, sensitivity in
                values.contains(where: { Self.matches(sensitivity: sensitivity, candidate: $0) })
                    ? sensitivities[index].trimmingCharacters(in: .whitespacesAndNewlines)
                    : nil
            }
            if !matches.isEmpty {
                var uniqueMatches: [String] = []
                for match in matches where !uniqueMatches.contains(match) {
                    uniqueMatches.append(match)
                }
                return AllergenAnalysis(
                    assessment: assessment,
                    matchedSensitivities: uniqueMatches,
                    evidence: [source]
                )
            }
        }

        return AllergenAnalysis(assessment: .unableToAssess, matchedSensitivities: [], evidence: [])
    }

    private static func normalize(_ value: String) -> String {
        let folded = value.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
        return folded
            .replacingOccurrences(of: "^[a-z]{2}:", with: "", options: .regularExpression)
            .replacingOccurrences(of: "[^a-z0-9]+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func matches(sensitivity: String, candidate: String) -> Bool {
        let candidateTerms = Set(normalize(candidate).split(separator: " ").map(String.init))
        let sensitivityTokens = normalize(sensitivity).split(separator: " ").map(String.init)
        guard !sensitivityTokens.isEmpty else { return false }
        let aliases: [String: Set<String>] = [
            "milk": ["milk", "dairy", "casein", "whey", "lactose"],
            "dairy": ["milk", "dairy", "casein", "whey", "lactose"],
            "soy": ["soy", "soya", "soybean"],
            "soya": ["soy", "soya", "soybean"],
            "peanut": ["peanut", "groundnut"],
            "peanuts": ["peanut", "groundnut"],
            "tree nut": ["almond", "cashew", "hazelnut", "macadamia", "pecan", "pistachio", "walnut"],
            "nuts": ["almond", "cashew", "hazelnut", "macadamia", "pecan", "pistachio", "walnut"],
            "wheat": ["wheat", "gluten"],
            "gluten": ["wheat", "gluten", "rye", "barley", "oat"]
        ]
        let key = sensitivityTokens.joined(separator: " ")
        let terms = aliases[key] ?? Set(sensitivityTokens)
        if terms.count == 1, let term = terms.first, sensitivityTokens.count == 1 {
            return candidateTerms.contains(where: { Self.tokenMatches(term: term, candidate: $0) })
        }
        return terms.contains { term in
            let tokens = term.split(separator: " ").map(String.init)
            guard !tokens.isEmpty else { return false }
            if tokens.count == 1 {
                return candidateTerms.contains(where: { Self.tokenMatches(term: tokens[0], candidate: $0) })
            }
            let candidateArray = normalize(candidate).split(separator: " ").map(String.init)
            return candidateArray.indices.contains(where: { index in
                let window = candidateArray[index...].prefix(tokens.count)
                return window.count == tokens.count
                    && zip(window, tokens).allSatisfy { Self.tokenMatches(term: $0.1, candidate: $0.0) }
            })
        } || sensitivityTokens.allSatisfy { token in
            candidateTerms.contains(where: { Self.tokenMatches(term: token, candidate: $0) })
        }
    }

    private static func tokenMatches(term: String, candidate: String) -> Bool {
        term == candidate
            || term + "s" == candidate
            || (candidate.hasSuffix("s") && String(candidate.dropLast()) == term)
    }
}

extension FoodEntry {
    func allergenAnalysis(for sensitivities: [String]) -> AllergenAnalysis {
        AllergenAnalysis.evaluate(entry: self, sensitivities: sensitivities)
    }
}
