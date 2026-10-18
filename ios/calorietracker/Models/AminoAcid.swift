import Foundation

/// The nine essential amino acids (#491). With the opt-in Amino acids setting
/// on, AI food analyses also return these as gram estimates. They are stored in
/// `FoodEntry.supplementalNutrients` under `storageKey`, so they persist, scale
/// with servings, export and share like the other extra nutrients; entries
/// logged before the setting (or while it was off) simply have none.
enum AminoAcid: String, CaseIterable, Identifiable, Sendable {
    case histidine
    case isoleucine
    case leucine
    case lysine
    case methionine
    case phenylalanine
    case threonine
    case tryptophan
    case valine

    nonisolated var id: String { rawValue }

    /// Key in `FoodEntry.supplementalNutrients`; distinct from every SupplementalNutrient key.
    nonisolated var storageKey: String { rawValue }

    /// Key in the AI JSON response (`<jsonKey>_per_100g` for nutrition labels).
    nonisolated var jsonKey: String { rawValue }

    /// English name, also the Localizable.xcstrings key.
    nonisolated var englishName: String {
        switch self {
        case .histidine: "Histidine"
        case .isoleucine: "Isoleucine"
        case .leucine: "Leucine"
        case .lysine: "Lysine"
        case .methionine: "Methionine"
        case .phenylalanine: "Phenylalanine"
        case .threonine: "Threonine"
        case .tryptophan: "Tryptophan"
        case .valine: "Valine"
        }
    }

    var displayName: String {
        LocalizedDisplayText.text(englishName)
    }

    /// WHO/FAO/UNU 2007 (WHO Technical Report Series 935) adult requirement in
    /// mg per kg of body weight per day. Methionine uses the methionine + cysteine
    /// value and phenylalanine the phenylalanine + tyrosine value.
    nonisolated var referenceMgPerKgPerDay: Double {
        switch self {
        case .histidine: 10
        case .isoleucine: 20
        case .leucine: 39
        case .lysine: 30
        case .methionine: 15
        case .phenylalanine: 25
        case .threonine: 15
        case .tryptophan: 4
        case .valine: 26
        }
    }

    /// Daily reference intake in grams for this body weight; nil without a usable weight.
    /// A reference for context, never a goal.
    nonisolated func referenceGrams(bodyWeightKg: Double) -> Double? {
        guard bodyWeightKg.isFinite, bodyWeightKg > 0 else { return nil }
        return referenceMgPerKgPerDay * bodyWeightKg / 1_000
    }
}

enum AminoAcidSettings {
    /// Off by default, so users who never opt in keep their prompts unchanged.
    nonisolated static let enabledKey = "aminoAcidEstimatesEnabled"

    nonisolated static var isEnabled: Bool {
        UserDefaults.standard.bool(forKey: enabledKey)
    }
}

enum AminoAcidEstimates {
    /// No single food comes near this many grams of one amino acid.
    nonisolated static let maxGrams = 1_000.0

    /// Appended to the food-analysis nutrient instructions only while the setting is on.
    nonisolated static var foodPromptInstruction: String {
        "Also include these keys in the same JSON object: \(promptKeys(suffix: "")). They are your estimate of the grams of each essential amino acid in the analyzed amount, based on its protein sources. Use null for any amino acid you cannot estimate."
    }

    /// Appended to the nutrition-label prompt only while the setting is on.
    nonisolated static var labelPromptInstruction: String {
        "Also include these keys in the same JSON object: \(promptKeys(suffix: "_per_100g")). They are the grams of each essential amino acid per 100g: use printed values when the label lists them, otherwise estimate them from the product's protein sources. Use null for any amino acid you cannot estimate."
    }

    private nonisolated static func promptKeys(suffix: String) -> String {
        AminoAcid.allCases.map { "\"\($0.jsonKey)\(suffix)\":0.0" }.joined(separator: ",")
    }

    /// Valid amino acid estimates from an AI response, keyed by storage key. A missing
    /// or invalid value (not a number, negative, non-finite, or more grams than
    /// `limitGrams`, such as the food's own weight) is dropped and never fails the analysis.
    nonisolated static func parse(
        _ json: [String: Any],
        keySuffix: String = "",
        limitGrams: Double? = nil
    ) -> [String: Double] {
        let limit = limitGrams.flatMap { $0.isFinite && $0 > 0 ? min($0, maxGrams) : nil } ?? maxGrams
        var result: [String: Double] = [:]
        for aminoAcid in AminoAcid.allCases {
            guard let value = number(json[aminoAcid.jsonKey + keySuffix]),
                  value >= 0, value <= limit
            else { continue }
            result[aminoAcid.storageKey] = value
        }
        return result
    }

    /// Daily grams per amino acid. Only amino acids that at least one entry
    /// carries appear, so a day without estimates reads as unknown, not zero.
    nonisolated static func dailyTotals(_ entries: [FoodEntry]) -> [AminoAcid: Double] {
        var totals: [AminoAcid: Double] = [:]
        for entry in entries {
            for aminoAcid in AminoAcid.allCases {
                if let value = entry.supplementalNutrients[aminoAcid.storageKey] {
                    totals[aminoAcid, default: 0] += value
                }
            }
        }
        return totals
    }

    /// `FoodEntry.supplementalNutrients` keys that hold amino acid estimates.
    nonisolated static let storageKeys = Set(AminoAcid.allCases.map(\.storageKey))

    /// Scales and rounds `FoodEntry.supplementalNutrients` wherever they are reviewed, edited,
    /// scaled from a label, logged by voice or exported. The other extras keep 0.1 g, but one
    /// food often has only a few hundredths of a gram of an amino acid (a banana has about
    /// 0.01 g tryptophan), so amino acid estimates keep milligrams instead of rounding to 0.
    nonisolated static func roundedSupplementalNutrients(
        _ values: [String: Double],
        scaledBy factor: Double = 1
    ) -> [String: Double] {
        Dictionary(uniqueKeysWithValues: values.map { key, value in
            let steps = storageKeys.contains(key) ? 1_000.0 : 10.0
            return (key, (value * factor * steps).rounded() / steps)
        })
    }

    /// Small amino acid amounts (tryptophan references are a few tenths of a gram) keep two decimals.
    nonisolated static func formattedGrams(_ grams: Double) -> String {
        String(format: grams < 1 ? "%.2f" : "%.1f", grams)
    }

    /// JSON numbers or numeric strings; booleans and non-finite values are not numbers.
    private nonisolated static func number(_ value: Any?) -> Double? {
        let number: Double?
        if let string = value as? String {
            number = Double(string.trimmingCharacters(in: .whitespacesAndNewlines))
        } else if let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() {
            number = value.doubleValue
        } else {
            number = nil
        }
        guard let number, number.isFinite else { return nil }
        return number
    }
}
