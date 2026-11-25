import Foundation

/// The provider choice in Settings → Voice Assistant.
nonisolated enum VoiceAssistantProviderChoice: String, CaseIterable, Identifiable, Sendable {
    /// The selected own-key provider when its key is saved, otherwise Fud AI
    /// hosting when subscribed, otherwise an explanation of both options.
    case automatic
    case hosted
    case gemini
    case openai
    case xai
    case qwen
    case gptLive
    case offline = "lfm3_offline"
    case localConnected = "lfm3_connected"

    var isLocal: Bool { self == .offline || self == .localConnected }

    var id: String { rawValue }

    /// The provider a fixed choice always uses; nil for Automatic.
    var fixedProvider: LiveProvider? {
        switch self {
        case .automatic, .offline, .localConnected: nil
        case .hosted: .hosted
        case .gemini: .gemini
        case .openai: .openai
        case .xai: .xai
        case .qwen: .qwen
        case .gptLive: .gptLive
        }
    }
}

/// Why the assistant can't start a session yet.
nonisolated enum VoiceAssistantAccessReason: Equatable, Sendable {
    /// Automatic found no saved key and no subscription.
    case noProvider
    /// The chosen own-key provider has no key saved in AI provider settings.
    case missingKey(LiveProvider)
    case needsQwenSetup
    /// Fud AI hosting was chosen without a Plus/Pro subscription.
    case needsSubscription
}

/// Who a session would talk to right now.
nonisolated enum VoiceAssistantRoute: Equatable, Sendable {
    case ready(LiveProvider)
    case local(connectedTools: Bool)
    case needsAccess(VoiceAssistantAccessReason)
}

/// The contract's provider resolution, kept free of app state so it can be tested.
nonisolated enum VoiceAssistantRouting {
    /// - Parameters:
    ///   - selectedOwnKeyProvider: the AI provider selected in AI provider settings,
    ///     when it is one the assistant can talk through (Gemini, OpenAI, xAI, Qwen).
    ///   - providersWithKeys: own-key providers with a key saved in AI provider settings.
    static func resolve(
        choice: VoiceAssistantProviderChoice,
        selectedOwnKeyProvider: LiveProvider?,
        providersWithKeys: Set<LiveProvider>,
        hasHostedEntitlement: Bool,
        qwenWorkspaceConfigured: Bool = false
    ) -> VoiceAssistantRoute {
        switch choice {
        case .offline: return .local(connectedTools: false)
        case .localConnected: return .local(connectedTools: true)
        case .automatic:
            if let selected = selectedOwnKeyProvider, selected != .hosted, providersWithKeys.contains(selected),
               selected != .qwen || qwenWorkspaceConfigured {
                return .ready(selected)
            }
            if hasHostedEntitlement { return .ready(.hosted) }
            return .needsAccess(selectedOwnKeyProvider == .qwen ? .needsQwenSetup : .noProvider)
        case .hosted:
            return hasHostedEntitlement ? .ready(.hosted) : .needsAccess(.needsSubscription)
        case .gemini, .openai, .xai, .qwen, .gptLive:
            let provider = choice.fixedProvider ?? .gemini
            if provider == .qwen, !qwenWorkspaceConfigured || !providersWithKeys.contains(provider) {
                return .needsAccess(.needsQwenSetup)
            }
            return providersWithKeys.contains(provider) ? .ready(provider) : .needsAccess(.missingKey(provider))
        }
    }
}

/// The reply language the assistant is told to use.
nonisolated enum VoiceAssistantLanguage: Equatable, Hashable, Sendable {
    /// The language the user speaks.
    case automatic
    /// The app's current language.
    case app
    /// One language, as a language code such as "es" or "pt-BR".
    case specific(String)

    var storageValue: String {
        switch self {
        case .automatic: "automatic"
        case .app: "app"
        case .specific(let code): code
        }
    }

    /// Nothing saved means the app's language: Automatic can pick up whatever
    /// language it hears, so it's a choice the user makes.
    init(storageValue: String?) {
        switch storageValue {
        case nil, "", "app": self = .app
        case "automatic": self = .automatic
        case let code?: self = .specific(code)
        }
    }

    /// The languages Fud AI is translated into, offered as fixed reply languages.
    static let choices = ["en", "ar", "az", "cs", "de", "es", "fr", "hi", "it", "ja", "ko", "nl", "pl", "pt-BR", "ro", "ru", "tr", "uk", "zh-Hans"]

    /// A language's name in English, for the model's instructions.
    static func englishName(for code: String) -> String {
        Locale(identifier: "en").localizedString(forIdentifier: code) ?? code
    }

    /// A language's name in the app's language, for Settings.
    static func displayName(for code: String) -> String {
        Locale.current.localizedString(forIdentifier: code)?.localizedCapitalized ?? code
    }

    /// The app's current language code.
    static var appLanguageCode: String {
        Bundle.main.preferredLocalizations.first ?? Locale.current.language.languageCode?.identifier ?? "en"
    }
}

/// Voice assistant preferences in UserDefaults. API keys are not stored here:
/// own-key sessions reuse the keys saved in AI provider settings.
nonisolated struct VoiceAssistantSettings {
    static let providerKey = "voiceAssistant.provider"
    static let languageKey = "voiceAssistant.language"
    static let autoCaptureKey = "voiceAssistant.autoCapture"
    static let confirmSavesKey = "voiceAssistant.confirmSaves"
    static let saveToCoachKey = "voiceAssistant.saveToCoach"
    static let qwenWorkspaceKey = "voiceAssistant.qwenWorkspace"
    static let localFallbackKey = "voiceAssistant.localCloudFallback"
    private static let qwenCredentialKey = "voiceAssistant.qwenWorkspaceCredential"

    static func modelKey(for provider: LiveProvider) -> String {
        "voiceAssistant.model.\(provider.settingsProvider.rawValue)"
    }

    static func voiceKey(for provider: LiveProvider) -> String {
        "voiceAssistant.voice.\(provider.settingsProvider.rawValue)"
    }

    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var providerChoice: VoiceAssistantProviderChoice {
        get { defaults.string(forKey: Self.providerKey).flatMap(VoiceAssistantProviderChoice.init(rawValue:)) ?? .automatic }
        nonmutating set { defaults.set(newValue.rawValue, forKey: Self.providerKey) }
    }

    var qwenWorkspace: String {
        get { defaults.string(forKey: Self.qwenWorkspaceKey) ?? "" }
        nonmutating set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Self.qwenWorkspaceKey) }
    }

    var offersLocalCloudFallback: Bool { defaults.bool(forKey: Self.localFallbackKey) }

    static var qwenAPIKey: String? { KeychainHelper.load(key: qwenCredentialKey) }

    func setQwenAPIKey(_ key: String?) {
        if let key, !key.isEmpty {
            KeychainHelper.save(key: Self.qwenCredentialKey, value: key)
        } else {
            KeychainHelper.delete(key: Self.qwenCredentialKey)
        }
    }

    /// The model an own-key session uses. Hosted sessions use the Worker's model.
    func model(for provider: LiveProvider) -> String {
        guard provider != .hosted else { return provider.defaultModel }
        let stored = defaults.string(forKey: Self.modelKey(for: provider))
        return stored.flatMap { provider.models.contains($0) ? $0 : nil } ?? provider.defaultModel
    }

    func setModel(_ model: String, for provider: LiveProvider) {
        guard provider != .hosted, provider.models.contains(model) else { return }
        defaults.set(model, forKey: Self.modelKey(for: provider))
    }

    func voice(for provider: LiveProvider) -> String {
        let stored = defaults.string(forKey: Self.voiceKey(for: provider))
        return stored.flatMap { provider.voices.contains($0) ? $0 : nil } ?? provider.defaultVoice
    }

    func setVoice(_ voice: String, for provider: LiveProvider) {
        guard provider.voices.contains(voice) else { return }
        defaults.set(voice, forKey: Self.voiceKey(for: provider))
    }

    var language: VoiceAssistantLanguage {
        get { VoiceAssistantLanguage(storageValue: defaults.string(forKey: Self.languageKey)) }
        nonmutating set { defaults.set(newValue.storageValue, forKey: Self.languageKey) }
    }

    /// On by default: the model takes the photo as soon as it sees the food.
    /// Off, it waits for the user to say so.
    var autoCapture: Bool {
        get { defaults.object(forKey: Self.autoCaptureKey) as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: Self.autoCaptureKey) }
    }

    /// On by default: saves wait for the user's yes (or a tap on the card).
    var confirmSaves: Bool {
        get { defaults.object(forKey: Self.confirmSavesKey) as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: Self.confirmSavesKey) }
    }

    /// On by default: each finished turn is added to the Coach chat.
    var saveToCoach: Bool {
        get { defaults.object(forKey: Self.saveToCoachKey) as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: Self.saveToCoachKey) }
    }

    /// iOS has no switch that turns the workout diary off (Coach always gets the
    /// workout tools), so workout tools are always offered. The executor and the
    /// catalog still take this flag, as the contract gates on it.
    var workoutDiaryEnabled: Bool { true }
}

extension LiveProvider {
    /// The AI provider whose saved key an own-key session uses.
    var keyProvider: AIProvider? {
        switch self {
        case .hosted: nil
        case .gemini: .gemini
        case .openai, .gptLive: .openai
        case .xai: .xai
        case .qwen: .qwen
        }
    }

    /// The assistant provider for an AI provider, if it has a live voice API.
    init?(aiProvider: AIProvider) {
        switch aiProvider {
        case .gemini: self = .gemini
        case .openai: self = .openai
        case .xai: self = .xai
        case .qwen: self = .qwen
        default: return nil
        }
    }

    /// The saved key, trimmed, or nil when none is saved.
    var savedKey: String? {
        if self == .qwen { return VoiceAssistantSettings.qwenAPIKey }
        guard let keyProvider,
              let key = AIProviderSettings.apiKey(for: keyProvider)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !key.isEmpty else { return nil }
        return key
    }

    /// The name shown to the user, localized where it isn't a brand.
    var localizedName: String {
        self == .hosted ? String(localized: "Fud AI (Plus/Pro)") : displayName
    }
}

extension VoiceAssistantProviderChoice {
    var localizedName: String {
        switch self {
        case .automatic: String(localized: "Automatic")
        case .hosted: String(localized: "Fud AI (Plus/Pro)")
        case .gemini: "Gemini"
        case .openai: "OpenAI"
        case .xai: "xAI"
        case .qwen: "Qwen"
        case .gptLive: "OpenAI GPT-Live"
        case .offline: String(localized: "Offline Voice", table: "OfflineVoice") + " (LFM3)"
        case .localConnected: String(localized: "Local voice + connected tools", table: "OfflineVoice") + " (LFM3)"
        }
    }
}

extension VoiceAssistantRouting {
    /// Resolves the route from the app's saved settings, keys and subscription.
    @MainActor
    static func current(settings: VoiceAssistantSettings = VoiceAssistantSettings(),
                        choiceOverride: VoiceAssistantProviderChoice? = nil) -> VoiceAssistantRoute {
        let choice = choiceOverride ?? settings.providerChoice
        if choice.isLocal { return .local(connectedTools: choice == .localConnected) }
        let selected = choice == .automatic ? LiveProvider(aiProvider: AIProviderSettings.selectedProvider) : nil
        let candidate = selected ?? choice.fixedProvider
        let providersWithKeys: Set<LiveProvider> = if let candidate, candidate.savedKey != nil { [candidate] } else { [] }
        return resolve(
            choice: choice,
            selectedOwnKeyProvider: selected,
            providersWithKeys: providersWithKeys,
            hasHostedEntitlement: RevenueCatManager.shared.hasHostedEntitlement,
            qwenWorkspaceConfigured: QwenRealtimeWire.isValidWorkspace(settings.qwenWorkspace)
        )
    }
}
