import Foundation
import Observation

/// A named API-key profile. The secret itself never lives in this record; it stays in
/// the platform credential store (iOS Keychain) under `aiCredentialSecret_<id>`.
///
/// `id` is the stable association used by routing slots, so renaming or replacing a
/// key never changes which routes point at it.
struct AICredential: Codable, Identifiable, Equatable {
    let id: String
    let provider: AIProvider
    /// User-defined nickname. `nil` marks the provider's unnamed "key slot" — the
    /// profile the legacy one-key-per-provider API writes to. The UI shows a localized
    /// default name for it.
    let label: String?
    let createdAt: TimeInterval

    init(
        id: String = UUID().uuidString,
        provider: AIProvider,
        label: String? = nil,
        createdAt: TimeInterval = Date().timeIntervalSince1970
    ) {
        self.id = id
        self.provider = provider
        self.label = label
        self.createdAt = createdAt
    }
}

/// The AI request slots whose credential can be chosen independently.
enum AIRoute: String, CaseIterable, Identifiable {
    case primary
    case text
    case imageFallback
    case textFallback

    var id: String { rawValue }
}

/// Minimal secure-storage seam so the credential store can be tested without a Keychain.
/// Main-actor isolated like the rest of the app, so Keychain access stays on the actor
/// that owns the settings UI.
@MainActor
protocol CredentialSecretStoring: AnyObject {
    func loadSecret(key: String) -> String?
    func saveSecret(key: String, value: String)
    func deleteSecret(key: String)
}

/// Keychain-backed secret storage used by the shipping app.
@MainActor
final class KeychainCredentialSecretStore: CredentialSecretStoring {
    static let shared = KeychainCredentialSecretStore()

    func loadSecret(key: String) -> String? { KeychainHelper.load(key: key) }
    func saveSecret(key: String, value: String) { KeychainHelper.save(key: key, value: value) }
    func deleteSecret(key: String) { KeychainHelper.delete(key: key) }
}

/// Stores named API-key profiles and the credential bound to each routing slot.
///
/// Legacy installs kept exactly one key per provider under `apikey_<PROVIDER>`. That key
/// becomes the provider's unnamed profile, and the store keeps mirroring the provider's
/// effective key into the legacy entry so older call sites that only know a provider keep
/// working without ever seeing another profile's secret.
///
/// Resolution rules:
/// - A route that is bound to a profile always uses that profile — even after its key
///   was cleared, so a route never silently switches to a different key.
/// - A route without a binding uses the provider's default profile: its unnamed profile
///   when it has one, otherwise its oldest profile.
/// - A binding to a different provider is ignored, so changing a route's provider can
///   never accidentally send one provider's key to another service.
@Observable
@MainActor
final class AICredentialStore {
    static let shared = AICredentialStore(defaults: .standard, secrets: KeychainCredentialSecretStore.shared)

    /// Bumped whenever a profile changes, so views that show a profile refresh.
    private(set) var revision: Int = 0

    private static let profilesKey = "aiCredentialProfiles_v1"
    private static let profilesBackupKey = "aiCredentialProfiles_v1_corrupt_backup"
    private static let migrationKey = "aiCredentialMigrationVersion"
    private static let migrationVersion = 1
    private static let secretPrefix = "aiCredentialSecret_"
    private static let routePrefix = "aiRouteCredential_"
    private static let legacyPrefix = "apikey_"
    static let maxLabelLength = 60
    static let maxSecretLength = 8_192

    private let defaults: UserDefaults
    private let secrets: any CredentialSecretStoring
    private let now: () -> TimeInterval
    private let newID: () -> String

    init(
        defaults: UserDefaults,
        secrets: any CredentialSecretStoring,
        now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 },
        newID: @escaping () -> String = { UUID().uuidString }
    ) {
        self.defaults = defaults
        self.secrets = secrets
        self.now = now
        self.newID = newID
    }

    // MARK: - Migration

    /// Adopts legacy per-provider keys as profiles exactly once. The legacy secret is
    /// never deleted, so a damaged profile file can still be rebuilt on the next launch.
    func ensureMigrated() {
        let stored = readProfiles()
        if stored != nil, defaults.integer(forKey: Self.migrationKey) >= Self.migrationVersion {
            return
        }
        var profiles = stored ?? []
        for provider in AIProvider.allCases where provider.requiresAPIKey {
            let legacy = secrets.loadSecret(key: Self.legacyKey(provider))?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !legacy.isEmpty else { continue }
            if profiles.contains(where: { $0.provider == provider }) { continue }
            let credential = AICredential(
                id: newID(),
                provider: provider,
                label: nil,
                createdAt: nextCreatedAt(profiles)
            )
            secrets.saveSecret(key: Self.secretKey(credential.id), value: legacy)
            profiles.append(credential)
        }
        writeProfiles(profiles)
        for provider in Set(profiles.map(\.provider)) {
            syncLegacyDefault(provider)
        }
        defaults.set(Self.migrationVersion, forKey: Self.migrationKey)
    }

    // MARK: - Reading

    func all() -> [AICredential] {
        ensureMigrated()
        let profiles = readProfiles() ?? []
        return profiles.sorted {
            if $0.createdAt == $1.createdAt { return $0.id < $1.id }
            return $0.createdAt < $1.createdAt
        }
    }

    func profiles(for provider: AIProvider) -> [AICredential] {
        all().filter { $0.provider == provider }
    }

    /// The provider's default profile: its unnamed profile when that one still holds a
    /// key, otherwise the oldest profile with a key. When no profile has a key, the
    /// oldest one is returned so the UI can still show which profile is empty.
    func defaultProfile(for provider: AIProvider) -> AICredential? {
        ensureMigrated()
        return defaultIn(readProfiles() ?? [], provider: provider)
    }

    /// Secret of the provider's default profile. Used by surfaces that are not bound to a
    /// routing slot (chat, voice assistant, speech fallback).
    func defaultSecret(for provider: AIProvider) -> String? {
        defaultProfile(for: provider).flatMap { rawSecret($0.id) }
    }

    /// The provider's effective key: the profile bound to the primary route when that
    /// route runs on this provider, otherwise the provider's default profile. Legacy
    /// "one key per provider" readers use this, so they follow what the user sees as the
    /// primary AI configuration.
    func effectiveSecret(for provider: AIProvider) -> String? {
        ensureMigrated()
        return effectiveSecretForMirror(provider)
    }

    func profile(id: String?) -> AICredential? {
        guard let id else { return nil }
        return all().first { $0.id == id }
    }

    func secret(for id: String) -> String? {
        ensureMigrated()
        return rawSecret(id)
    }

    /// Reads a stored secret without triggering the migration pass.
    private func rawSecret(_ id: String) -> String? {
        secrets.loadSecret(key: Self.secretKey(id))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
    }

    /// Stable, non-reversible display fingerprint. Never includes a long key prefix.
    func maskedSecret(for id: String) -> String {
        Self.mask(secret(for: id))
    }

    func routeProfile(_ route: AIRoute, provider: AIProvider) -> AICredential? {
        ensureMigrated()
        if let bound = boundProfile(route, provider: provider) { return bound }
        return defaultProfile(for: provider)
    }

    /// The profile a route is explicitly bound to, when that binding is usable.
    private func boundProfile(_ route: AIRoute, provider: AIProvider) -> AICredential? {
        guard let boundId = defaults.string(forKey: Self.routeKey(route)),
              let bound = (readProfiles() ?? []).first(where: { $0.id == boundId }),
              bound.provider == provider else { return nil }
        return bound
    }

    func routeSecret(_ route: AIRoute, provider: AIProvider) -> String? {
        routeProfile(route, provider: provider).flatMap { rawSecret($0.id) }
    }

    func routeBindings() -> [AIRoute: String?] {
        var bindings: [AIRoute: String?] = [:]
        for route in AIRoute.allCases {
            bindings[route] = defaults.string(forKey: Self.routeKey(route))
        }
        return bindings
    }

    // MARK: - Writing

    /// Binds a slot to a profile. A binding to another provider is ignored.
    func bindRoute(_ route: AIRoute, credentialID: String?) {
        ensureMigrated()
        guard let credentialID else {
            defaults.removeObject(forKey: Self.routeKey(route))
            return
        }
        guard let credential = profile(id: credentialID) else { return }
        defaults.set(credential.id, forKey: Self.routeKey(route))
        // The legacy mirror follows the primary route, so a rebuild from a damaged
        // profile file recovers the key the user actually uses.
        syncLegacyDefault(credential.provider)
        revision &+= 1
    }

    /// Clears a slot binding after its provider changed, so the new provider's default applies.
    func clearRoute(_ route: AIRoute) {
        let previous = profile(id: defaults.string(forKey: Self.routeKey(route)))
        defaults.removeObject(forKey: Self.routeKey(route))
        // Clearing the primary binding changes the effective key the legacy mirror
        // should expose, exactly like rebinding it does.
        if let previous {
            syncLegacyDefault(previous.provider)
        }
        revision &+= 1
    }

    /// A route must never keep a binding to a provider the user just switched away from.
    func clearRouteIfForeign(_ route: AIRoute, provider: AIProvider) {
        ensureMigrated()
        if let bound = profile(id: defaults.string(forKey: Self.routeKey(route))),
           bound.provider != provider {
            defaults.removeObject(forKey: Self.routeKey(route))
            syncLegacyDefault(bound.provider)
            revision &+= 1
        }
    }

    /// Adds a named profile. A nil `label` creates the provider's unnamed key slot; a
    /// blank label is rejected.
    @discardableResult
    func add(provider: AIProvider, label: String?, secret: String) -> Result<AICredential, AICredentialError> {
        ensureMigrated()
        let normalizedLabel = label?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let normalizedLabel {
            if normalizedLabel.isEmpty { return .failure(.labelRequired) }
            if normalizedLabel.count > Self.maxLabelLength { return .failure(.labelTooLong) }
        }
        let normalizedSecret = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        if normalizedSecret.isEmpty { return .failure(.keyRequired) }
        if normalizedSecret.count > Self.maxSecretLength { return .failure(.keyTooLong) }
        if normalizedSecret.contains(where: { $0.isWhitespace }) { return .failure(.keyContainsWhitespace) }

        var profiles = readProfiles() ?? []
        let credential = AICredential(
            id: newID(),
            provider: provider,
            label: normalizedLabel,
            createdAt: nextCreatedAt(profiles)
        )
        secrets.saveSecret(key: Self.secretKey(credential.id), value: normalizedSecret)
        profiles.append(credential)
        writeProfiles(profiles)
        syncLegacyDefault(provider)
        revision &+= 1
        return .success(credential)
    }

    @discardableResult
    func rename(id: String, label: String) -> Result<AICredential, AICredentialError> {
        update(id: id, label: label, secret: nil)
    }

    /// Renames a profile and optionally replaces its key, as one edit.
    ///
    /// Both values are validated before anything is written, so a rejected key does not
    /// leave the profile half-renamed.
    @discardableResult
    func update(id: String, label: String, secret: String?) -> Result<AICredential, AICredentialError> {
        ensureMigrated()
        let normalizedLabel = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedLabel.isEmpty else { return .failure(.labelRequired) }
        guard normalizedLabel.count <= Self.maxLabelLength else { return .failure(.labelTooLong) }
        let normalizedSecret = secret?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let normalizedSecret {
            if normalizedSecret.isEmpty { return .failure(.keyRequired) }
            if normalizedSecret.count > Self.maxSecretLength { return .failure(.keyTooLong) }
            if normalizedSecret.contains(where: { $0.isWhitespace }) { return .failure(.keyContainsWhitespace) }
        }
        var profiles = readProfiles() ?? []
        guard let index = profiles.firstIndex(where: { $0.id == id }) else {
            return .failure(.notFound)
        }
        let updated = AICredential(
            id: profiles[index].id,
            provider: profiles[index].provider,
            label: normalizedLabel,
            createdAt: profiles[index].createdAt
        )
        profiles[index] = updated
        if let normalizedSecret {
            secrets.saveSecret(key: Self.secretKey(id), value: normalizedSecret)
        }
        writeProfiles(profiles)
        syncLegacyDefault(updated.provider)
        revision &+= 1
        return .success(updated)
    }

    /// Replaces the secret of one profile only; other profiles and route bindings stay untouched.
    @discardableResult
    func replaceSecret(id: String, secret: String) -> Result<AICredential, AICredentialError> {
        ensureMigrated()
        let normalizedSecret = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        if normalizedSecret.isEmpty { return .failure(.keyRequired) }
        if normalizedSecret.count > Self.maxSecretLength { return .failure(.keyTooLong) }
        if normalizedSecret.contains(where: { $0.isWhitespace }) { return .failure(.keyContainsWhitespace) }
        guard let credential = profile(id: id) else { return .failure(.notFound) }
        secrets.saveSecret(key: Self.secretKey(id), value: normalizedSecret)
        syncLegacyDefault(credential.provider)
        revision &+= 1
        return .success(credential)
    }

    /// Removes the stored key of one profile but keeps the profile itself, so a named
    /// profile keeps its label and its route binding, and the user can paste a new key
    /// into the same profile later.
    @discardableResult
    func clearSecret(id: String) -> Result<AICredential, AICredentialError> {
        ensureMigrated()
        guard let credential = profile(id: id) else { return .failure(.notFound) }
        secrets.deleteSecret(key: Self.secretKey(id))
        syncLegacyDefault(credential.provider)
        revision &+= 1
        return .success(credential)
    }

    /// Deletes a profile.
    ///
    /// Every route that would resolve to the deleted profile is captured first — both
    /// explicit bindings and routes that used it as the provider default — and moved to
    /// `replacementID` when it belongs to the same provider, or left unconfigured
    /// otherwise. Routes that never used the profile keep their own credential.
    @discardableResult
    func delete(
        id: String,
        replacementID: String? = nil,
        routeProviders: [AIRoute: AIProvider] = [:]
    ) -> Result<Void, AICredentialError> {
        ensureMigrated()
        var profiles = readProfiles() ?? []
        guard let credential = profiles.first(where: { $0.id == id }) else {
            return .failure(.notFound)
        }
        var replacement: AICredential?
        if let candidate = profile(id: replacementID), candidate.id != id,
           candidate.provider == credential.provider {
            replacement = candidate
        }

        // Capture the routes that use this profile before it disappears, including the
        // routes that only used it because it was the provider default. Explicit
        // bindings always count, even when the caller does not pass the route map.
        let affectedRoutes = AIRoute.allCases.filter { route in
            if defaults.string(forKey: Self.routeKey(route)) == id { return true }
            guard let provider = routeProviders[route] else { return false }
            return routeProfile(route, provider: provider)?.id == id
        }

        profiles.removeAll { $0.id == id }
        writeProfiles(profiles)
        secrets.deleteSecret(key: Self.secretKey(id))
        for route in affectedRoutes {
            if let replacement {
                defaults.set(replacement.id, forKey: Self.routeKey(route))
            } else {
                defaults.removeObject(forKey: Self.routeKey(route))
            }
        }
        syncLegacyDefault(credential.provider)
        revision &+= 1
        return .success(())
    }

    /// Compatibility entry point for onboarding and older callers that only know a
    /// provider. It only ever writes to the provider's unnamed profile, so a key the
    /// user named is never overwritten. A new unnamed profile is created when the
    /// provider has none.
    @discardableResult
    func setDefaultSecret(provider: AIProvider, secret: String?) -> Result<AICredential?, AICredentialError> {
        ensureMigrated()
        let value = secret?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        // The provider-wide API only ever writes the unnamed key slot, so a key the
        // user named is never overwritten.
        let target = profiles(for: provider).first { $0.label?.isNilOrEmpty ?? true }
        if value.isEmpty {
            // Clearing a key removes the secret; the profile and its bindings stay, so
            // the route reports "no key" instead of silently using another one.
            if let target {
                _ = clearSecret(id: target.id)
            }
            return .success(nil)
        }
        if let target {
            return replaceSecret(id: target.id, secret: value).map { Optional($0) }
        }
        return add(provider: provider, label: nil, secret: value).map { Optional($0) }
    }

    /// All slots that currently resolve to `id`, including implicit defaults.
    func routesUsing(id: String, routeProviders: [AIRoute: AIProvider]) -> [AIRoute] {
        guard let credential = profile(id: id) else { return [] }
        return AIRoute.allCases.filter { route in
            guard let provider = routeProviders[route], provider == credential.provider else { return false }
            return routeProfile(route, provider: provider)?.id == id
        }
    }

    func deleteAll() {
        (readProfiles() ?? []).forEach { secrets.deleteSecret(key: Self.secretKey($0.id)) }
        for provider in AIProvider.allCases {
            secrets.deleteSecret(key: Self.legacyKey(provider))
        }
        for route in AIRoute.allCases {
            defaults.removeObject(forKey: Self.routeKey(route))
        }
        defaults.removeObject(forKey: Self.profilesKey)
        defaults.removeObject(forKey: Self.migrationKey)
        revision &+= 1
    }

    // MARK: - Internals

    /// The provider's default profile out of `profiles`: its unnamed profile when that
    /// one still holds a key, otherwise the oldest profile with a key. Without a key
    /// anywhere, the oldest profile is returned so the UI can show which profile is empty.
    private func defaultIn(_ profiles: [AICredential], provider: AIProvider) -> AICredential? {
        let candidates = profiles
            .filter { $0.provider == provider }
            .sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
        let withKey = candidates.filter { rawSecret($0.id) != nil }
        return withKey.first { $0.label?.isNilOrEmpty ?? true }
            ?? withKey.first
            ?? candidates.first { $0.label?.isNilOrEmpty ?? true }
            ?? candidates.first
    }

    /// The effective key without running the migration. The migration calls the legacy
    /// mirror before it writes its marker, so this variant prevents a recursive pass.
    private func effectiveSecretForMirror(_ provider: AIProvider) -> String? {
        let target = boundProfile(.primary, provider: provider)
            ?? defaultIn(readProfiles() ?? [], provider: provider)
        return target.flatMap { rawSecret($0.id) }
    }

    /// Mirrors the provider's effective key into `apikey_<PROVIDER>` for legacy readers.
    private func syncLegacyDefault(_ provider: AIProvider) {
        let value = effectiveSecretForMirror(provider)
        if let value, !value.isEmpty {
            secrets.saveSecret(key: Self.legacyKey(provider), value: value)
        } else {
            secrets.deleteSecret(key: Self.legacyKey(provider))
        }
    }

    private func readProfiles() -> [AICredential]? {
        guard let data = defaults.data(forKey: Self.profilesKey) else { return [] }
        guard let profiles = try? JSONDecoder().decode([AICredential].self, from: data) else {
            // Keep the damaged file so a manual recovery is still possible; the
            // migration then rebuilds the profiles from the secure legacy entries.
            defaults.set(data, forKey: Self.profilesBackupKey)
            return nil
        }
        var seen = Set<String>()
        return profiles.filter { !$0.id.isEmpty && seen.insert($0.id).inserted }
    }

    private func writeProfiles(_ profiles: [AICredential]) {
        guard let data = try? JSONEncoder().encode(profiles) else { return }
        defaults.set(data, forKey: Self.profilesKey)
    }

    private func nextCreatedAt(_ profiles: [AICredential]) -> TimeInterval {
        max(now(), (profiles.map(\.createdAt).max() ?? 0) + 0.001)
    }

    /// Last four characters only, or a fully masked value for very short secrets.
    static func mask(_ secret: String?) -> String {
        let value = secret?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if value.isEmpty { return "" }
        if value.count < 8 { return "••••" }
        return "••••" + String(value.suffix(4))
    }

    private static func secretKey(_ id: String) -> String { secretPrefix + id }
    private static func routeKey(_ route: AIRoute) -> String { routePrefix + route.rawValue }
    private static func legacyKey(_ provider: AIProvider) -> String { legacyPrefix + provider.rawValue }
}

enum AICredentialError: Error, Equatable {
    case labelRequired
    case labelTooLong
    case keyRequired
    case keyTooLong
    case keyContainsWhitespace
    case notFound
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
    var isNilOrEmpty: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
