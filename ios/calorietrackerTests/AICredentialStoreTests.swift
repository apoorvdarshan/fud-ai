import Foundation
import Testing
@testable import calorietracker

private extension Result {
    var isFailure: Bool {
        if case .failure = self { return true }
        return false
    }
}

/// Verifies the named API-key profile store (#644): migration of legacy per-provider
/// keys, isolation between profiles of the same provider, route bindings and deletion.
@MainActor
struct AICredentialStoreTests {
    private final class MemorySecrets: CredentialSecretStoring {
        var values: [String: String] = [:]

        func loadSecret(key: String) -> String? { values[key] }
        func saveSecret(key: String, value: String) { values[key] = value }
        func deleteSecret(key: String) { values.removeValue(forKey: key) }
    }

    private func makeStore(
        secrets: MemorySecrets? = nil,
        suiteName: String = "AICredentialStoreTests"
    ) -> (AICredentialStore, UserDefaults) {
        let secrets = secrets ?? MemorySecrets()
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        var counter = 0
        var clock = 1_000.0
        let store = AICredentialStore(
            defaults: defaults,
            secrets: secrets,
            now: { clock += 1; return clock },
            newID: { counter += 1; return "id-\(counter)" }
        )
        return (store, defaults)
    }

    @Test func migrationAdoptsLegacyKeysOnce() {
        let secrets = MemorySecrets()
        secrets.values["apikey_OpenRouter"] = "sk-or-legacy-1234"
        secrets.values["apikey_OpenAI"] = "sk-openai-5678"
        let (store, defaults) = makeStore(secrets: secrets)

        let migrated = store.all()
        #expect(migrated.count == 2)
        let openRouter = migrated.first { $0.provider == .openrouter }
        #expect(openRouter != nil)
        #expect(openRouter?.label == nil)
        #expect(store.secret(for: openRouter!.id) == "sk-or-legacy-1234")
        #expect(secrets.values["apikey_OpenRouter"] == "sk-or-legacy-1234")

        // A second launch must not duplicate profiles.
        store.ensureMigrated()
        #expect(store.all().map(\.id) == migrated.map(\.id))
        defaults.removePersistentDomain(forName: "AICredentialStoreTests")
    }

    @Test func aRouteReadRunsTheMigrationOnItsOwn() {
        let secrets = MemorySecrets()
        secrets.values["apikey_OpenRouter"] = "sk-or-legacy-1234"
        let (store, _) = makeStore(secrets: secrets)

        // The first call may be a route read, before any UI lists the profiles.
        #expect(store.routeSecret(.primary, provider: .openrouter) == "sk-or-legacy-1234")
        #expect(store.profiles(for: .openrouter).count == 1)
    }

    @Test func twoProfilesForOneProviderStayIsolated() throws {
        let secrets = MemorySecrets()
        let (store, _) = makeStore(secrets: secrets)
        let personal = try store.add(provider: .openrouter, label: "Personal", secret: "sk-or-personal-1111").get()
        let work = try store.add(provider: .openrouter, label: "Work", secret: "sk-or-work-2222").get()

        #expect(personal.id != work.id)
        #expect(store.profiles(for: .openrouter).count == 2)
        #expect(store.secret(for: personal.id) == "sk-or-personal-1111")
        #expect(store.secret(for: work.id) == "sk-or-work-2222")
        #expect(store.maskedSecret(for: personal.id) == "••••1111")
        #expect(store.maskedSecret(for: work.id) == "••••2222")

        _ = try store.replaceSecret(id: work.id, secret: "sk-or-work-replaced-3333").get()
        #expect(store.secret(for: personal.id) == "sk-or-personal-1111")
        #expect(store.secret(for: work.id) == "sk-or-work-replaced-3333")
    }

    @Test func routesBindIndependentlyAndIgnoreForeignProviders() throws {
        let (store, _) = makeStore()
        let personal = try store.add(provider: .openrouter, label: "Personal", secret: "sk-or-personal-1111").get()
        let work = try store.add(provider: .openrouter, label: "Work", secret: "sk-or-work-2222").get()
        let openAI = try store.add(provider: .openai, label: "Main", secret: "sk-openai-3333").get()

        store.bindRoute(.primary, credentialID: personal.id)
        store.bindRoute(.imageFallback, credentialID: work.id)

        #expect(store.routeProfile(.primary, provider: .openrouter)?.id == personal.id)
        #expect(store.routeProfile(.imageFallback, provider: .openrouter)?.id == work.id)
        #expect(store.routeSecret(.primary, provider: .openrouter) == "sk-or-personal-1111")
        #expect(store.routeSecret(.imageFallback, provider: .openrouter) == "sk-or-work-2222")

        // A binding for OpenRouter must never be handed to another provider.
        #expect(store.routeProfile(.imageFallback, provider: .openai)?.id == openAI.id)
        #expect(store.routeSecret(.imageFallback, provider: .openai) == "sk-openai-3333")
    }

    @Test func renamingKeepsRouteAndSecretStable() throws {
        let (store, _) = makeStore()
        let profile = try store.add(provider: .openrouter, label: "Old name", secret: "sk-or-secret-9999").get()
        store.bindRoute(.text, credentialID: profile.id)

        let renamed = try store.rename(id: profile.id, label: "New name").get()

        #expect(renamed.id == profile.id)
        #expect(store.profile(id: profile.id)?.label == "New name")
        #expect(store.routeSecret(.text, provider: .openrouter) == "sk-or-secret-9999")
    }

    @Test func deletingAReferencedProfileReassignsOrClearsTheRoute() throws {
        let (store, _) = makeStore()
        let first = try store.add(provider: .openrouter, label: "First", secret: "sk-or-first-1111").get()
        let second = try store.add(provider: .openrouter, label: "Second", secret: "sk-or-second-2222").get()
        store.bindRoute(.primary, credentialID: first.id)
        store.bindRoute(.text, credentialID: first.id)

        _ = try store.delete(id: first.id, replacementID: second.id).get()

        #expect((store.routeBindings()[.primary])!! == second.id)
        #expect((store.routeBindings()[.text])!! == second.id)
        #expect(store.routeProfile(.primary, provider: .openrouter)?.id == second.id)
        #expect(store.routeProfile(.text, provider: .openrouter)?.id == second.id)
        #expect(store.profile(id: first.id) == nil)
        #expect(store.secret(for: first.id) == nil)

        _ = try store.delete(id: second.id).get()
        #expect(store.routeProfile(.primary, provider: .openrouter) == nil)
    }

    @Test func deletingTheBoundProfileWithoutReplacementLeavesTheRouteUnconfigured() throws {
        let (store, _) = makeStore()
        let first = try store.add(provider: .openrouter, label: "First", secret: "sk-or-first-1111").get()
        let second = try store.add(provider: .openrouter, label: "Second", secret: "sk-or-second-2222").get()
        store.bindRoute(.primary, credentialID: first.id)

        _ = try store.delete(id: first.id, routeProviders: [.primary: .openrouter]).get()

        #expect(store.routeProfile(.primary, provider: .openrouter) == nil)
        #expect(store.routeSecret(.primary, provider: .openrouter) == nil)
        #expect(store.effectiveSecret(for: .openrouter) == nil)
        // The other profile is still available for an explicit choice, but the route
        // must not silently switch to it.
        #expect(store.defaultProfile(for: .openrouter)?.id == second.id)

        // The unconfigured marker belongs to OpenRouter. A route switched to another
        // provider starts from that provider's default key.
        let openAI = try store.add(provider: .openai, label: "Main", secret: "sk-openai-3333").get()
        #expect(store.routeProfile(.primary, provider: .openai)?.id == openAI.id)
        #expect(store.routeSecret(.primary, provider: .openai) == "sk-openai-3333")
    }

    @Test func switchingTheProviderClearsAnUnconfiguredMarkerAndRestoresTheOldMirror() throws {
        let secrets = MemorySecrets()
        let (store, _) = makeStore(secrets: secrets)
        let first = try store.add(provider: .openai, label: "First", secret: "sk-openai-first-1111").get()
        let second = try store.add(provider: .openai, label: "Second", secret: "sk-openai-second-2222").get()
        store.bindRoute(.primary, credentialID: first.id)

        _ = try store.delete(id: first.id, routeProviders: [.primary: .openai]).get()

        #expect((store.routeBindings()[.primary])!! == "__unconfigured__:OpenAI")
        #expect(store.effectiveSecret(for: .openai) == nil)
        #expect(secrets.values["apikey_OpenAI"] == nil)

        // Switching the primary route to Gemini must drop the old provider's marker and
        // restore the mirror from OpenAI's remaining default profile.
        store.clearRouteIfForeign(.primary, provider: .gemini)

        #expect((store.routeBindings()[.primary])! == nil)
        #expect(store.routeProfile(.primary, provider: .openai)?.id == second.id)
        #expect(store.effectiveSecret(for: .openai) == "sk-openai-second-2222")
        #expect(secrets.values["apikey_OpenAI"] == "sk-openai-second-2222")
    }

    @Test func aSameProviderUnconfiguredMarkerSurvivesTheProviderCheck() throws {
        let secrets = MemorySecrets()
        let (store, _) = makeStore(secrets: secrets)
        let first = try store.add(provider: .openai, label: "First", secret: "sk-openai-first-1111").get()
        _ = try store.add(provider: .openai, label: "Second", secret: "sk-openai-second-2222").get()
        store.bindRoute(.primary, credentialID: first.id)

        _ = try store.delete(id: first.id, routeProviders: [.primary: .openai]).get()
        store.clearRouteIfForeign(.primary, provider: .openai)

        #expect((store.routeBindings()[.primary])!! == "__unconfigured__:OpenAI")
        #expect(store.routeSecret(.primary, provider: .openai) == nil)
        #expect(store.effectiveSecret(for: .openai) == nil)
        #expect(secrets.values["apikey_OpenAI"] == nil)
    }

    @Test func switchingTheProviderStillClearsAForeignProfileBinding() throws {
        let secrets = MemorySecrets()
        let (store, _) = makeStore(secrets: secrets)
        let openAI = try store.add(provider: .openai, label: "Main", secret: "sk-openai-3333").get()
        let gemini = try store.add(provider: .gemini, label: "Main", secret: "sk-gemini-4444").get()
        store.bindRoute(.primary, credentialID: openAI.id)

        store.clearRouteIfForeign(.primary, provider: .gemini)

        #expect((store.routeBindings()[.primary])! == nil)
        #expect(store.routeProfile(.primary, provider: .gemini)?.id == gemini.id)
        #expect(store.routeSecret(.primary, provider: .gemini) == "sk-gemini-4444")
        #expect(store.effectiveSecret(for: .openai) == "sk-openai-3333")
        #expect(secrets.values["apikey_OpenAI"] == "sk-openai-3333")
    }

    @Test func deletingTheImplicitDefaultLeavesTheActiveRouteUnconfigured() throws {
        let (store, _) = makeStore()
        let first = try store.add(provider: .openrouter, label: "First", secret: "sk-or-first-1111").get()
        _ = try store.add(provider: .openrouter, label: "Second", secret: "sk-or-second-2222").get()

        _ = try store.delete(id: first.id, routeProviders: [.primary: .openrouter]).get()

        #expect(store.routeProfile(.primary, provider: .openrouter) == nil)
        #expect(store.routeSecret(.primary, provider: .openrouter) == nil)
    }

    @Test func deletingOneOfTwoProfilesKeepsTheDefaultAvailable() throws {
        let (store, _) = makeStore()
        let first = try store.add(provider: .openai, label: "First", secret: "sk-openai-first-1111").get()
        let second = try store.add(provider: .openai, label: "Second", secret: "sk-openai-second-2222").get()
        #expect(store.routeProfile(.primary, provider: .openai)?.id == first.id)

        _ = try store.delete(id: first.id).get()

        #expect(store.routeProfile(.primary, provider: .openai)?.id == second.id)
        #expect(store.defaultProfile(for: .openai)?.id == second.id)
    }

    @Test func legacySetterNeverOverwritesANamedProfile() throws {
        let (store, _) = makeStore()
        let personal = try store.add(provider: .openrouter, label: "Personal", secret: "sk-or-first-1111").get()
        let work = try store.add(provider: .openrouter, label: "Work", secret: "sk-or-work-2222").get()

        // The provider-wide API (onboarding) writes to the provider's unnamed slot and
        // leaves the keys the user named alone.
        _ = try store.setDefaultSecret(provider: .openrouter, secret: "sk-or-updated-4444").get()

        #expect(store.secret(for: personal.id) == "sk-or-first-1111")
        #expect(store.secret(for: work.id) == "sk-or-work-2222")
        let unnamed = try #require(store.profiles(for: .openrouter).first { $0.label == nil })
        #expect(store.secret(for: unnamed.id) == "sk-or-updated-4444")
        #expect(store.defaultProfile(for: .openrouter)?.id == unnamed.id)
    }

    @Test func legacySetterUpdatesTheUnnamedSlot() throws {
        let (store, _) = makeStore()
        let unnamed = try store.add(provider: .openrouter, label: nil, secret: "sk-or-first-1111").get()

        _ = try store.setDefaultSecret(provider: .openrouter, secret: "sk-or-updated-4444").get()

        #expect(store.secret(for: unnamed.id) == "sk-or-updated-4444")
        #expect(store.profiles(for: .openrouter).count == 1)

        // Clearing keeps the slot but drops its key, so a route bound to it reports
        // "no key" instead of silently using another profile's key.
        _ = try store.setDefaultSecret(provider: .openrouter, secret: nil).get()
        #expect(store.secret(for: unnamed.id) == nil)
        #expect(store.profile(id: unnamed.id) != nil)
    }

    @Test func aRouteBoundToAKeylessProfileNeverBorrowsAnotherKey() throws {
        let (store, _) = makeStore()
        let personal = try store.add(provider: .openrouter, label: "Personal", secret: "sk-or-personal-1111").get()
        let work = try store.add(provider: .openrouter, label: "Work", secret: "sk-or-work-2222").get()
        store.bindRoute(.text, credentialID: work.id)

        // Clearing the bound profile's key must not fall back to the other profile.
        _ = try store.clearSecret(id: work.id).get()

        #expect(store.routeSecret(.text, provider: .openrouter) == nil)
        #expect(store.secret(for: personal.id) == "sk-or-personal-1111")
    }

    @Test func deletingTheDefaultProfileMovesTheRoutesThatUsedIt() throws {
        let (store, _) = makeStore()
        let first = try store.add(provider: .openrouter, label: "Personal", secret: "sk-or-first-1111").get()
        let second = try store.add(provider: .openrouter, label: "Work", secret: "sk-or-work-2222").get()
        let routeProviders: [AIRoute: AIProvider] = [.primary: .openrouter]

        // The primary route uses the default profile; deleting it with a replacement must
        // move the route, not leave it on another profile.
        _ = try store.delete(id: first.id, replacementID: second.id, routeProviders: routeProviders).get()

        #expect(store.routeProfile(.primary, provider: .openrouter)?.id == second.id)
        #expect(store.routeSecret(.primary, provider: .openrouter) == "sk-or-work-2222")
    }

    @Test func theLegacyMirrorFollowsThePrimaryRoute() throws {
        let (store, _) = makeStore()
        let personal = try store.add(provider: .openrouter, label: "Personal", secret: "sk-or-personal-1111").get()
        let work = try store.add(provider: .openrouter, label: "Work", secret: "sk-or-work-2222").get()

        store.bindRoute(.primary, credentialID: work.id)

        #expect(store.effectiveSecret(for: .openrouter) == "sk-or-work-2222")
        #expect(store.secret(for: personal.id) == "sk-or-personal-1111")

        // Clearing the primary binding must restore the mirror to the provider default.
        store.clearRoute(.primary)
        #expect(store.effectiveSecret(for: .openrouter) == "sk-or-personal-1111")
    }

    @Test func theProviderEffectiveKeyFollowsThePrimaryRoute() throws {
        let (store, _) = makeStore()
        let personal = try store.add(provider: .openrouter, label: "Personal", secret: "sk-or-personal-1111").get()
        _ = try store.add(provider: .openrouter, label: "Work", secret: "sk-or-work-2222").get()

        // Before a binding the provider's default profile answers.
        #expect(store.effectiveSecret(for: .openrouter) == "sk-or-personal-1111")

        // Binding the primary route moves the provider-wide key with it.
        let work = try #require(store.profiles(for: .openrouter).first { $0.id != personal.id })
        store.bindRoute(.primary, credentialID: work.id)
        #expect(store.effectiveSecret(for: .openrouter) == "sk-or-work-2222")
        // Other routes keep their own credential.
        #expect(store.routeSecret(.text, provider: .openrouter) == "sk-or-personal-1111")
    }

    @Test func deletingTheDefaultProfileFallsBackToAnotherOne() throws {
        let (store, _) = makeStore()
        let first = try store.add(provider: .openrouter, label: nil, secret: "sk-or-first-1111").get()
        let second = try store.add(provider: .openrouter, label: "Work", secret: "sk-or-work-2222").get()

        _ = try store.delete(id: first.id).get()

        #expect(store.defaultProfile(for: .openrouter)?.id == second.id)
        #expect(store.routeSecret(.primary, provider: .openrouter) == "sk-or-work-2222")
    }

    @Test func invalidInputIsRejectedWithoutStoringAnything() {
        let (store, _) = makeStore()

        #expect(store.add(provider: .openai, label: " ", secret: "sk-12345678").isFailure)
        #expect(store.add(provider: .openai, label: "Work", secret: "  ").isFailure)
        #expect(store.add(provider: .openai, label: "Work", secret: "sk 1234 5678").isFailure)
        #expect(store.all().isEmpty)
    }

    @Test func routesUsingReportsActiveSlots() throws {
        let (store, _) = makeStore()
        let profile = try store.add(provider: .openrouter, label: "Personal", secret: "sk-or-personal-1111").get()
        store.bindRoute(.primary, credentialID: profile.id)

        let routes = store.routesUsing(id: profile.id, routeProviders: [.primary: .openrouter, .text: .openai])

        #expect(routes == [.primary])
    }

    @Test func deleteAllRemovesProfilesRoutesAndLegacyMirrors() throws {
        let secrets = MemorySecrets()
        let (store, _) = makeStore(secrets: secrets)
        let profile = try store.add(provider: .openrouter, label: "Personal", secret: "sk-or-personal-1111").get()
        store.bindRoute(.primary, credentialID: profile.id)

        store.deleteAll()

        #expect(store.all().isEmpty)
        #expect(store.routeProfile(.primary, provider: .openrouter) == nil)
        #expect(secrets.values["apikey_OpenRouter"] == nil)
    }
}
