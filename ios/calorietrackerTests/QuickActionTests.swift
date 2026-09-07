import Foundation
import Testing
@testable import calorietracker

struct QuickActionTests {
    @Test func defaultsMatchTheThreeInitialShortcutSlots() {
        let suiteName = "QuickActionTests.defaults.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suiteName)!
        defer { store.removePersistentDomain(forName: suiteName) }

        #expect(QuickActionSettings.action(for: 0, store: store) == .camera)
        #expect(QuickActionSettings.action(for: 1, store: store) == .voice)
        #expect(QuickActionSettings.action(for: 2, store: store) == .barcode)
    }

    @Test func storedValuesRoundTripAndInvalidValuesFallBack() {
        let suiteName = "QuickActionTests.values.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suiteName)!
        defer { store.removePersistentDomain(forName: suiteName) }

        store.set(QuickAction.favorites.rawValue, forKey: QuickActionSettings.storageKeys[0])
        store.set(QuickAction.fasting.rawValue, forKey: QuickActionSettings.storageKeys[2])
        store.set("unknown", forKey: QuickActionSettings.storageKeys[1])

        #expect(QuickActionSettings.action(for: 0, store: store) == .favorites)
        #expect(QuickActionSettings.action(for: 1, store: store) == .voice)
        #expect(QuickActionSettings.action(for: 2, store: store) == .fasting)
    }

    @Test func shortcutTypesMapOnlyToTheThreeSupportedSlots() {
        #expect(QuickActionCoordinator.slot(forShortcutType: QuickActionCoordinator.shortcutType(for: 0)) == 0)
        #expect(QuickActionCoordinator.slot(forShortcutType: QuickActionCoordinator.shortcutType(for: 2)) == 2)
        #expect(QuickActionCoordinator.slot(forShortcutType: "com.apoorvdarshan.calorietracker.quickAction.4") == nil)
        #expect(QuickActionCoordinator.slot(forShortcutType: "unrelated") == nil)
    }
}
