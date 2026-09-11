import Foundation

struct AddMenuGroupConfig: Codable, Equatable, Identifiable, Sendable {
    var id: UUID
    var name: String
    var methods: [FoodLogMethod]

    init(id: UUID = UUID(), name: String, methods: [FoodLogMethod]) {
        self.id = id
        self.name = name
        self.methods = methods
    }

    enum CodingKeys: String, CodingKey {
        case id, name, methods
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        name = try container.decode(String.self, forKey: .name)
        let rawMethods = try container.decode([String].self, forKey: .methods)
        methods = rawMethods.compactMap(FoodLogMethod.init(rawValue:))
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(methods.map(\.rawValue), forKey: .methods)
    }
}

struct AddMenuConfig: Codable, Equatable, Sendable {
    static let currentVersion = 1
    static let storageKey = "addMenu.config"

    var version: Int
    var groups: [AddMenuGroupConfig]
    /// Used when `groups` is empty — flat list under + with no nesting.
    var flatMethods: [FoodLogMethod]

    init(version: Int = currentVersion, groups: [AddMenuGroupConfig], flatMethods: [FoodLogMethod] = []) {
        self.version = version
        self.groups = groups
        self.flatMethods = flatMethods
    }

    enum CodingKeys: String, CodingKey {
        case version, groups, flatMethods
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        version = try container.decodeIfPresent(Int.self, forKey: .version) ?? Self.currentVersion
        groups = try container.decodeIfPresent([AddMenuGroupConfig].self, forKey: .groups) ?? []
        let rawFlat = try container.decodeIfPresent([String].self, forKey: .flatMethods) ?? []
        flatMethods = rawFlat.compactMap(FoodLogMethod.init(rawValue:))
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(version, forKey: .version)
        try container.encode(groups, forKey: .groups)
        try container.encode(flatMethods.map(\.rawValue), forKey: .flatMethods)
    }

    /// Matches the pre-customization iOS Home + food menu.
    static let iOSDefault = AddMenuConfig(
        groups: [
            AddMenuGroupConfig(
                name: String(localized: "Reuse Meal", comment: "Default add menu group"),
                methods: [.copyFromDay, .favorites, .frequent, .recent]
            ),
            AddMenuGroupConfig(
                name: String(localized: "Describe Meal", comment: "Default add menu group"),
                methods: [.manual, .siriPhrases, .voice, .text]
            ),
            AddMenuGroupConfig(
                name: String(localized: "Photo & Scan", comment: "Default add menu group"),
                methods: [.barcode, .photos, .camera]
            ),
        ]
    )

    var usesFlatLayout: Bool { groups.isEmpty }

    var visibleMethods: [FoodLogMethod] {
        if usesFlatLayout {
            return flatMethods
        }
        return groups.flatMap(\.methods)
    }

    func sanitized() -> AddMenuConfig {
        let allowed = Set(FoodLogMethod.addMenuCases)
        var seen = Set<FoodLogMethod>()

        func filterMethods(_ methods: [FoodLogMethod]) -> [FoodLogMethod] {
            methods.compactMap { method in
                guard allowed.contains(method), !seen.contains(method) else { return nil }
                seen.insert(method)
                return method
            }
        }

        let trimmedGroups: [AddMenuGroupConfig] = groups.prefix(3).compactMap { group in
            let methods = filterMethods(group.methods)
            guard !methods.isEmpty else { return nil }
            return AddMenuGroupConfig(
                id: group.id,
                name: group.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? String(localized: "Group", comment: "Fallback add menu group name")
                    : group.name.trimmingCharacters(in: .whitespacesAndNewlines),
                methods: methods
            )
        }

        if trimmedGroups.isEmpty {
            return AddMenuConfig(
                version: Self.currentVersion,
                groups: [],
                flatMethods: filterMethods(flatMethods)
            )
        }

        return AddMenuConfig(
            version: Self.currentVersion,
            groups: trimmedGroups,
            flatMethods: []
        )
    }
}

enum AddMenuSettings {
    static func load(store: UserDefaults = .standard) -> AddMenuConfig {
        guard let data = store.data(forKey: AddMenuConfig.storageKey),
              let decoded = try? JSONDecoder().decode(AddMenuConfig.self, from: data)
        else {
            return .iOSDefault
        }
        let sanitized = decoded.sanitized()
        if sanitized.groups.isEmpty, sanitized.flatMethods.isEmpty {
            let hadConfiguredContent = decoded.groups.contains { !$0.methods.isEmpty }
                || !decoded.flatMethods.isEmpty
            if hadConfiguredContent {
                return .iOSDefault
            }
        }
        return sanitized
    }

    static func save(_ config: AddMenuConfig, store: UserDefaults = .standard) {
        let sanitized = config.sanitized()
        guard let data = try? JSONEncoder().encode(sanitized) else { return }
        store.set(data, forKey: AddMenuConfig.storageKey)
    }

    static func reset(store: UserDefaults = .standard) {
        store.removeObject(forKey: AddMenuConfig.storageKey)
    }
}
