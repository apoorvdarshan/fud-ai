import Foundation
import Testing
@testable import calorietracker

struct AddMenuConfigTests {
    @Test func homeShortcutsDefaultSwapAndPersistWithoutDuplicates() {
        var shortcuts = HomeFoodShortcuts(stored: "")
        #expect(shortcuts.methods == [.camera, .barcode, .text, .favorites])
        shortcuts.assign(.favorites, to: 0)
        shortcuts.assign(.manual, to: 1)
        #expect(HomeFoodShortcuts(stored: shortcuts.stored).methods == [.favorites, .manual, .text, .camera])
        #expect(HomeFoodShortcuts(stored: "camera,camera,text,favorites").methods == HomeFoodShortcuts.defaults)
    }

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

    @Test func decodeIgnoresUnknownMethodIds() throws {
        let json = """
        {"version":1,"groups":[{"id":"00000000-0000-0000-0000-000000000001","name":"Quick","methods":["camera","unknown_future_method","voice"]}],"flatMethods":[]}
        """
        let data = try #require(json.data(using: .utf8))
        let decoded = try JSONDecoder().decode(AddMenuConfig.self, from: data)
        #expect(decoded.groups.count == 1)
        #expect(decoded.groups[0].methods == [.camera, .voice])
    }

    @Test(arguments: [false, true])
    func loadFallsBackToDefaultWhenAllMethodsUnknown(flatLayout: Bool) {
        let suiteName = "AddMenuConfigTests.unknown.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suiteName)!
        defer { store.removePersistentDomain(forName: suiteName) }

        let json = flatLayout ? """
        {"version":1,"groups":[],"flatMethods":["unknown_future_method"]}
        """ : """
        {"version":1,"groups":[{"id":"00000000-0000-0000-0000-000000000001","name":"Quick","methods":["unknown_future_method"]}],"flatMethods":[]}
        """
        store.set(json.data(using: .utf8), forKey: AddMenuConfig.storageKey)
        #expect(AddMenuSettings.load(store: store) == .iOSDefault)
    }

    @Test func explicitlyEmptyMenuStaysEmptyAfterReload() {
        let suiteName = "AddMenuConfigTests.empty.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suiteName)!
        defer { store.removePersistentDomain(forName: suiteName) }

        let empty = AddMenuConfig(groups: [], flatMethods: [])
        AddMenuSettings.save(empty, store: store)
        #expect(AddMenuSettings.load(store: store) == empty)
    }

    @Test func groupsAndStandaloneMethodsCoexistAfterReload() {
        let suiteName = "AddMenuConfigTests.mixed.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suiteName)!
        defer { store.removePersistentDomain(forName: suiteName) }

        let mixed = AddMenuConfig(
            groups: [AddMenuGroupConfig(name: "Scan", methods: [.camera, .barcode])],
            flatMethods: [.voice, .text]
        )
        AddMenuSettings.save(mixed, store: store)
        let loaded = AddMenuSettings.load(store: store)
        #expect(loaded.version == 2)
        #expect(loaded.groups.map(\.methods) == [[.camera, .barcode]])
        #expect(loaded.flatMethods == [.voice, .text])
        #expect(loaded.visibleMethods == [.camera, .barcode, .voice, .text])
    }

    @Test func sanitizeKeepsAMethodInItsGroupBeforeTheStandaloneList() {
        let config = AddMenuConfig(
            groups: [AddMenuGroupConfig(name: "Scan", methods: [.camera])],
            flatMethods: [.camera, .voice]
        ).sanitized()
        #expect(config.groups[0].methods == [.camera])
        #expect(config.flatMethods == [.voice])
    }

    @Test func versionOneGroupedMenuStillIgnoresItsFlatList() {
        let json = """
        {"version":1,"groups":[{"id":"00000000-0000-0000-0000-000000000001","name":"Quick","methods":["camera"]}],"flatMethods":["voice"]}
        """
        let config = AddMenuSettings.decode(Data(json.utf8))
        #expect(config.groups.map(\.methods) == [[.camera]])
        #expect(config.flatMethods.isEmpty)
        #expect(config.version == AddMenuConfig.currentVersion)
    }

    @Test func versionOneFlatMenuDecodesUnchanged() {
        let json = """
        {"version":1,"groups":[],"flatMethods":["voice","camera"]}
        """
        let config = AddMenuSettings.decode(Data(json.utf8))
        #expect(config.groups.isEmpty)
        #expect(config.flatMethods == [.voice, .camera])
    }

    @Test func assigningMovesAMethodBetweenGroupAndStandalone() {
        let groupID = UUID()
        let config = AddMenuConfig(
            groups: [AddMenuGroupConfig(id: groupID, name: "Scan", methods: [.camera, .barcode])],
            flatMethods: [.voice]
        )

        let standalone = config.assigning(.barcode, to: .standalone)
        #expect(standalone.groups[0].methods == [.camera])
        #expect(standalone.flatMethods == [.voice, .barcode])

        let grouped = standalone.assigning(.voice, to: .group(groupID))
        #expect(grouped.groups[0].methods == [.camera, .voice])
        #expect(grouped.flatMethods == [.barcode])

        #expect(config.assigning(.text, to: .group(UUID())) == config)
    }

    @Test func sanitizeDropsEmptyGroups() {
        let config = AddMenuConfig(
            groups: [
                AddMenuGroupConfig(name: "Empty", methods: []),
                AddMenuGroupConfig(name: "Quick", methods: [.camera]),
            ],
            flatMethods: []
        ).sanitized()
        #expect(config.groups.count == 1)
        #expect(config.groups[0].methods == [.camera])
    }
}
