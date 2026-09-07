import Foundation
import UIKit

enum QuickAction: String, CaseIterable, Identifiable, Sendable {
    case camera
    case photos
    case voice
    case text
    case barcode
    case favorites
    case frequent
    case recent
    case manual
    case fasting

    var id: String { rawValue }

    var title: String {
        switch self {
        case .camera: "Camera + Note"
        case .photos: "Photos"
        case .voice: "Voice"
        case .text: "Text"
        case .barcode: "Barcode"
        case .favorites: "Favorites"
        case .frequent: "Frequent"
        case .recent: "Recent"
        case .manual: "Manual"
        case .fasting: "Fasting"
        }
    }

    var systemImageName: String {
        switch self {
        case .camera: "camera.fill"
        case .photos: "photo.on.rectangle.angled"
        case .voice: "mic.fill"
        case .text: "text.bubble.fill"
        case .barcode: "barcode.viewfinder"
        case .favorites: "heart.fill"
        case .frequent: "repeat"
        case .recent: "clock.arrow.circlepath"
        case .manual: "square.and.pencil"
        case .fasting: "timer"
        }
    }
}

struct QuickActionRequest: Identifiable, Equatable, Sendable {
    let id = UUID()
    let action: QuickAction
}

enum QuickActionSettings {
    static let storageKeys = [
        "quickAction.slot1",
        "quickAction.slot2",
        "quickAction.slot3",
    ]
    static let defaults: [QuickAction] = [.camera, .voice, .barcode]

    static func action(for slot: Int, store: UserDefaults = .standard) -> QuickAction {
        guard storageKeys.indices.contains(slot) else { return defaults[0] }
        return store.string(forKey: storageKeys[slot]).flatMap(QuickAction.init(rawValue:))
            ?? defaults[slot]
    }

    static func set(_ action: QuickAction, for slot: Int, store: UserDefaults = .standard) {
        guard storageKeys.indices.contains(slot) else { return }
        store.set(action.rawValue, forKey: storageKeys[slot])
        registerApplicationShortcuts(store: store)
    }

    @MainActor
    static func registerApplicationShortcuts(store: UserDefaults = .standard) {
        UIApplication.shared.shortcutItems = storageKeys.indices.map { slot in
            let action = action(for: slot, store: store)
            return UIApplicationShortcutItem(
                type: QuickActionCoordinator.shortcutType(for: slot),
                localizedTitle: action.title,
                localizedSubtitle: "Quick Action \(slot + 1)",
                icon: UIApplicationShortcutIcon(systemImageName: action.systemImageName),
                userInfo: nil
            )
        }
    }
}

extension Notification.Name {
    static let quickActionRequested = Notification.Name("FudAI.quickActionRequested")
}

enum QuickActionCoordinator {
    private static let pendingKey = "quickAction.pending"
    private static let shortcutPrefix = "com.apoorvdarshan.calorietracker.quickAction."

    static func shortcutType(for slot: Int) -> String { shortcutPrefix + String(slot + 1) }

    static func slot(forShortcutType type: String) -> Int? {
        guard type.hasPrefix(shortcutPrefix),
              let slotNumber = Int(type.dropFirst(shortcutPrefix.count)),
              (1...3).contains(slotNumber)
        else { return nil }
        return slotNumber - 1
    }

    @MainActor
    static func request(_ action: QuickAction) {
        UserDefaults.standard.set(action.rawValue, forKey: pendingKey)
        NotificationCenter.default.post(name: .quickActionRequested, object: action)
    }

    static func consumePending() -> QuickAction? {
        let store = UserDefaults.standard
        defer { store.removeObject(forKey: pendingKey) }
        return store.string(forKey: pendingKey).flatMap(QuickAction.init(rawValue:))
    }

    @MainActor
    static func handle(_ shortcutItem: UIApplicationShortcutItem) -> Bool {
        guard let slot = slot(forShortcutType: shortcutItem.type) else { return false }
        request(QuickActionSettings.action(for: slot))
        return true
    }
}
