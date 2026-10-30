import Foundation

/// Asks the model to answer in the language the app is shown in, so a Czech user gets Czech
/// food names, reasons and Coach replies. English — the development language — adds nothing,
/// so English users' prompts are unchanged. Identifiers the app matches in code (unit ids,
/// exercise ids, library search queries) are explicitly kept in English.
nonisolated enum AIReplyLanguage {
    /// The localization the app's strings are shown in.
    static var shownTag: String {
        Bundle.main.preferredLocalizations.first ?? Locale.current.language.languageCode?.identifier ?? "en"
    }

    /// The instruction for the language the app is shown in, or nil when that is English.
    static func instruction(for tag: String = shownTag) -> String? {
        guard let language = languageName(for: tag) else { return nil }
        return "Reply language: write every human-readable text value — food and ingredient names, reasons, advice, questions, instructions — in \(language). "
            + "Keep JSON keys, numbers and every identifier or enumerated value exactly as the format specifies, in English: "
            + "unit identifiers (slice, cup, tbsp, ml, kg, lbs), kind, intensity, exercise_id, category, equipment and muscle values, "
            + "and exercise-library search queries."
    }

    /// `prompt` with `instruction` in front; the prompt itself when the app is shown in English.
    static func prompt(_ prompt: String, instruction: String? = instruction()) -> String {
        guard let instruction else { return prompt }
        return instruction + "\n\n" + prompt
    }

    /// The language name for "Answer in plain …" in the Coach and weight-trend prompts.
    static func plainLanguageName(for tag: String = shownTag) -> String {
        languageName(for: tag) ?? "English"
    }

    /// English name of a non-English tag ("cs" → "Czech", "pt-BR" → "Portuguese (Brazil)"); nil for English.
    static func languageName(for tag: String) -> String? {
        let code = Locale(identifier: tag).language.languageCode?.identifier ?? tag
        if code.lowercased() == "en" { return nil }
        return Locale(identifier: "en").localizedString(forIdentifier: tag) ?? tag
    }
}
