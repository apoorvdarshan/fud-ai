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
    /// Version 2 shows `flatMethods` next to `groups`; version 1 ignored them whenever a group existed.
    /// Version 3 distinguishes explicitly saved orders from the old iOS default.
    static let currentVersion = 3
    static let storageKey = "addMenu.config"

    var version: Int
    var groups: [AddMenuGroupConfig]
    /// Standalone methods shown directly under +, after the groups. With no groups this is the whole menu.
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

    /// Matches the Android default order, with Siri Phrases as an extra iOS entry.
    static let iOSDefault = AddMenuConfig(
        groups: [
            AddMenuGroupConfig(
                name: String(localized: "Photo & Scan", comment: "Default add menu group"),
                methods: [.camera, .photos, .barcode]
            ),
            AddMenuGroupConfig(
                name: String(localized: "Describe Meal", comment: "Default add menu group"),
                methods: [.text, .voice, .manual, .siriPhrases]
            ),
            AddMenuGroupConfig(
                name: String(localized: "Reuse Meal", comment: "Default add menu group"),
                methods: [.recent, .frequent, .favorites, .copyFromDay]
            ),
        ]
    )

    fileprivate func updatingLegacyDefaultOrder() -> AddMenuConfig {
        let ordered = Self.iOSDefault.groups
        let legacyMethods: [[FoodLogMethod]] = [
            [.copyFromDay, .favorites, .frequent, .recent],
            [.manual, .siriPhrases, .voice, .text],
            [.barcode, .photos, .camera],
        ]
        guard version < 3, flatMethods.isEmpty, groups.map(\.methods) == legacyMethods,
              groups.map(\.name) == ordered.reversed().map(\.name) else { return self }

        var updated = self
        updated.groups.reverse()
        for (index, group) in ordered.enumerated() {
            updated.groups[index].methods = group.methods
        }
        return updated
    }

    var usesFlatLayout: Bool { groups.isEmpty }

    var visibleMethods: [FoodLogMethod] {
        groups.flatMap(\.methods) + flatMethods
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

        // Version 1 only showed the flat list when there were no groups.
        let ignoresFlatMethods = version < 2 && !trimmedGroups.isEmpty

        return AddMenuConfig(
            version: Self.currentVersion,
            groups: trimmedGroups,
            flatMethods: ignoresFlatMethods ? [] : filterMethods(flatMethods)
        )
    }
}

/// Where the + menu editor adds a method: one group, or directly under +.
enum AddMenuMethodTarget: Identifiable, Hashable {
    case group(UUID)
    case standalone

    var id: String {
        switch self {
        case let .group(id): id.uuidString
        case .standalone: "standalone"
        }
    }
}

extension AddMenuConfig {
    /// Moves `method` to the end of `target`, removing it from every other list first.
    func assigning(_ method: FoodLogMethod, to target: AddMenuMethodTarget) -> AddMenuConfig {
        var updated = self
        updated.flatMethods.removeAll { $0 == method }
        for index in updated.groups.indices {
            updated.groups[index].methods.removeAll { $0 == method }
        }
        switch target {
        case let .group(groupID):
            guard let index = updated.groups.firstIndex(where: { $0.id == groupID }) else { return self }
            updated.groups[index].methods.append(method)
        case .standalone:
            updated.flatMethods.append(method)
        }
        return updated
    }
}

enum AddMenuSettings {
    static func load(store: UserDefaults = .standard) -> AddMenuConfig {
        decode(store.data(forKey: AddMenuConfig.storageKey))
    }

    /// Decodes the stored menu, e.g. from an `@AppStorage` value so Home refreshes after edits.
    static func decode(_ data: Data?) -> AddMenuConfig {
        guard let data,
              let decoded = try? JSONDecoder().decode(AddMenuConfig.self, from: data)
        else {
            return .iOSDefault
        }
        let sanitized = decoded.updatingLegacyDefaultOrder().sanitized()
        if sanitized.groups.isEmpty, sanitized.flatMethods.isEmpty {
            // Decoding drops unknown method IDs. Inspect the persisted IDs to
            // distinguish an unavailable configuration from an intentional empty menu.
            let raw = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let groups = raw?["groups"] as? [[String: Any]] ?? []
            let hadConfiguredContent = groups.contains { ($0["methods"] as? [String])?.isEmpty == false }
                || (raw?["flatMethods"] as? [String])?.isEmpty == false
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
