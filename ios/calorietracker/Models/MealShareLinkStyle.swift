import Foundation

/// Which link a meal share sends. Mirrors Android `MealShareLinkStyle` and its
/// "mealShareLinkStyle" key. No stored value means the user hasn't chosen yet, so the
/// first share asks once.
nonisolated enum MealShareLinkStyle: String, CaseIterable, Identifiable, Sendable {
    /// `fud-ai.app/m/<id>`: a preview page first; the link expires after 7 days. The default.
    case short
    /// `fud-ai.app/add-meal?d=…`: opens Fud AI directly and carries the meals itself.
    case direct

    static let storageKey = "mealShareLinkStyle"
    static let defaultStyle = MealShareLinkStyle.short

    /// The saved choice, or nil before the first share has asked.
    static func chosen(in defaults: UserDefaults = .standard) -> MealShareLinkStyle? {
        defaults.string(forKey: storageKey).flatMap(MealShareLinkStyle.init(rawValue:))
    }

    var id: String { rawValue }

    var title: String {
        switch self {
        case .short: String(localized: "Short Link")
        case .direct: String(localized: "Direct Link")
        }
    }

    var detail: String {
        switch self {
        case .short: String(localized: "Shows a preview first and expires after 7 days.")
        case .direct: String(localized: "Opens Fud AI right away and never expires, but the link is longer.")
        }
    }
}
