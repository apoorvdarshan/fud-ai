import Foundation

/// Tabs the user can hide from the tab bar. Home and Settings are always shown.
enum HideableTab: String, CaseIterable, Identifiable {
    case progress
    case coach
    case workouts

    var id: String { rawValue }
}

enum TabBarSettings {
    /// Comma-separated `HideableTab` values ("coach,workouts"), the same format Android stores.
    static let hiddenTabsKey = "hiddenTabs"

    /// Unknown values (including Home and Settings) are ignored, so a missing key shows every tab.
    static func decode(_ raw: String?) -> Set<HideableTab> {
        Set(
            (raw ?? "")
                .split(separator: ",")
                .compactMap { HideableTab(rawValue: $0.trimmingCharacters(in: .whitespaces)) }
        )
    }

    static func encode(_ hidden: Set<HideableTab>) -> String {
        HideableTab.allCases.filter(hidden.contains).map(\.rawValue).joined(separator: ",")
    }

    static func hiddenTabs(in defaults: UserDefaults = .standard) -> Set<HideableTab> {
        decode(defaults.string(forKey: hiddenTabsKey))
    }

    /// The hidden tab behind an assistant screen; nil for screens that are never hidden.
    static func hiddenTab(for screen: VoiceAssistantScreen, in hidden: Set<HideableTab>) -> HideableTab? {
        let group: HideableTab? = switch screen {
        case .progress, .weeklyChallenge: .progress
        case .coach: .coach
        case .workouts, .exerciseLibrary: .workouts
        default: nil
        }
        return group.flatMap { hidden.contains($0) ? $0 : nil }
    }
}
