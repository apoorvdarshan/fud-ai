import Foundation
import Testing
@testable import calorietracker

struct AddMenuConfigTests {
    @Test func defaultMatchesLegacyThreeGroups() {
        let config = AddMenuConfig.iOSDefault
        #expect(config.groups.count == 3)
        #expect(config.groups[0].methods == [.copyFromDay, .favorites, .frequent, .recent])
        #expect(config.groups[1].methods == [.manual, .siriPhrases, .voice, .text])
        #expect(config.groups[2].methods == [.barcode, .photos, .camera])
    }

    @Test func storageRoundTripsAndDedupesMethods() {
        let suiteName = "AddMenuConfigTests.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suiteName)!
        defer { store.removePersistentDomain(forName: suiteName) }

        let custom = AddMenuConfig(
            groups: [
                AddMenuGroupConfig(name: "Quick", methods: [.camera, .camera, .voice]),
            ],
            flatMethods: []
        )
        AddMenuSettings.save(custom, store: store)
        let loaded = AddMenuSettings.load(store: store)
        #expect(loaded.groups.count == 1)
        #expect(loaded.groups[0].methods == [.camera, .voice])
    }

    @Test func missingStorageFallsBackToDefault() {
        let suiteName = "AddMenuConfigTests.missing.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suiteName)!
        defer { store.removePersistentDomain(forName: suiteName) }

        #expect(AddMenuSettings.load(store: store) == .iOSDefault)
    }

    @Test func foodLogMethodMapsQuickActions() {
        #expect(FoodLogMethod(quickAction: .camera) == .camera)
        #expect(FoodLogMethod(quickAction: .fasting) == nil)
        #expect(FoodLogMethod.recent.quickAction == .recent)
    }
}
