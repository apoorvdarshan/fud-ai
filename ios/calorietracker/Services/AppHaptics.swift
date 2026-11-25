import UIKit

/// App-generated feedback only. System controls keep their own feedback policy.
enum AppHaptics {
    static let storageKey = "hapticsEnabled"

    static func isEnabled(in defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: storageKey) == nil || defaults.bool(forKey: storageKey)
    }

    static func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        guard isEnabled() else { return }
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }

    static func selection() {
        guard isEnabled() else { return }
        UISelectionFeedbackGenerator().selectionChanged()
    }
}
