import Foundation

enum AIAccessMode: String, CaseIterable, Codable, Identifiable {
    case bringYourOwnKey = "Bring Your Own Key"
    case fudAIPlus = "Fud AI Plus"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .bringYourOwnKey: "Bring Your Own Key"
        case .fudAIPlus: "Fud AI Plus"
        }
    }

    var shortName: String {
        switch self {
        case .bringYourOwnKey: "BYOK"
        case .fudAIPlus: "Plus"
        }
    }

    var icon: String {
        switch self {
        case .bringYourOwnKey: "key.fill"
        case .fudAIPlus: "sparkles"
        }
    }
}

struct AIAccessQuotaSnapshot: Codable, Equatable {
    struct Bucket: Codable, Equatable {
        var used: Int
        var limit: Int
        var remaining: Int
    }

    var date: String
    var food: Bucket
    var speech: Bucket
    var coach: Bucket
    var global: Bucket

    static var fallback: AIAccessQuotaSnapshot {
        AIAccessQuotaSnapshot(
            date: "",
            food: .init(used: 0, limit: AIAccessSettings.paidFoodDailyRequestLimit, remaining: AIAccessSettings.paidFoodDailyRequestLimit),
            speech: .init(used: 0, limit: AIAccessSettings.paidSpeechDailyRequestLimit, remaining: AIAccessSettings.paidSpeechDailyRequestLimit),
            coach: .init(used: 0, limit: AIAccessSettings.paidCoachDailyRequestLimit, remaining: AIAccessSettings.paidCoachDailyRequestLimit),
            global: .init(used: 0, limit: AIAccessSettings.paidGlobalDailyRequestLimit, remaining: AIAccessSettings.paidGlobalDailyRequestLimit)
        )
    }
}

struct AIAccessSettings {
    static let paidFoodDailyRequestLimit = 30
    static let paidSpeechDailyRequestLimit = 20
    static let paidCoachDailyRequestLimit = 25
    static let paidGlobalDailyRequestLimit = 70
    static let defaultProxyEndpoint = "https://fud-ai.app/api/gemini"

    private static let modeKey = "aiAccessMode"
    private static let plusEntitlementCacheKey = "fudAIPlusEntitlementCached"
    private static let plusIntroSeenKey = "fudAIPlusIntroSeen_3_4"
    private static let plusUpdateAnnouncementVersionKey = "fudAIPlusUpdateAnnouncementSeenVersion"
    private static let installIDKey = "fudAIInstallID"
    private static let proxyEndpointKey = "fudAIProxyEndpoint"

    static var mode: AIAccessMode {
        get {
            guard let raw = UserDefaults.standard.string(forKey: modeKey),
                  let mode = AIAccessMode(rawValue: raw) else {
                // Existing users keep the old BYOK behavior after updating.
                return .bringYourOwnKey
            }
            return mode
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: modeKey)
        }
    }

    static var isUsingFudAIPlus: Bool {
        mode == .fudAIPlus
    }

    static var hasActivePlusEntitlement: Bool {
        UserDefaults.standard.bool(forKey: plusEntitlementCacheKey)
    }

    static func setActivePlusEntitlement(_ active: Bool) {
        UserDefaults.standard.set(active, forKey: plusEntitlementCacheKey)
    }

    static var hasSeenPlusIntro: Bool {
        get { UserDefaults.standard.bool(forKey: plusIntroSeenKey) }
        set { UserDefaults.standard.set(newValue, forKey: plusIntroSeenKey) }
    }

    static var lastSeenPlusUpdateAnnouncementVersion: String? {
        get { UserDefaults.standard.string(forKey: plusUpdateAnnouncementVersionKey) }
        set { UserDefaults.standard.set(newValue, forKey: plusUpdateAnnouncementVersionKey) }
    }

    static var installID: String {
        if let existing = UserDefaults.standard.string(forKey: installIDKey), !existing.isEmpty {
            return existing
        }
        let newID = UUID().uuidString
        UserDefaults.standard.set(newID, forKey: installIDKey)
        return newID
    }

    static var proxyEndpoint: URL {
        let raw = UserDefaults.standard.string(forKey: proxyEndpointKey) ?? defaultProxyEndpoint
        return URL(string: raw) ?? URL(string: defaultProxyEndpoint)!
    }

    static func resetForDeleteAllData() {
        UserDefaults.standard.removeObject(forKey: modeKey)
        UserDefaults.standard.removeObject(forKey: plusEntitlementCacheKey)
        UserDefaults.standard.removeObject(forKey: plusIntroSeenKey)
        UserDefaults.standard.removeObject(forKey: plusUpdateAnnouncementVersionKey)
        UserDefaults.standard.removeObject(forKey: proxyEndpointKey)
    }
}

enum AIProvider: String, CaseIterable, Codable, Identifiable {
    case gemini = "Google Gemini"
    case openai = "OpenAI"
    case anthropic = "Anthropic Claude"
    case xai = "xAI Grok"
    case openrouter = "OpenRouter"
    case togetherai = "Together AI"
    case groq = "Groq"
    case huggingface = "Hugging Face"
    case fireworks = "Fireworks AI"
    case deepinfra = "DeepInfra"
    case mistral = "Mistral"
    case ollama = "Ollama (Local)"
    case customOpenAI = "Custom (OpenAI-compatible)"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .gemini: "sparkle"
        case .openai: "brain.head.profile"
        case .anthropic: "text.bubble"
        case .xai: "bolt.fill"
        case .openrouter: "arrow.triangle.branch"
        case .togetherai: "square.stack.3d.up"
        case .groq: "hare.fill"
        case .huggingface: "face.smiling.inverse"
        case .fireworks: "flame.fill"
        case .deepinfra: "server.rack"
        case .mistral: "wind"
        case .ollama: "desktopcomputer"
        case .customOpenAI: "wrench.and.screwdriver.fill"
        }
    }

    var baseURL: String {
        switch self {
        case .gemini: "https://generativelanguage.googleapis.com/v1beta"
        case .openai: "https://api.openai.com/v1"
        case .anthropic: "https://api.anthropic.com/v1"
        case .xai: "https://api.x.ai/v1"
        case .openrouter: "https://openrouter.ai/api/v1"
        case .togetherai: "https://api.together.xyz/v1"
        case .groq: "https://api.groq.com/openai/v1"
        case .huggingface: "https://router.huggingface.co/v1"
        case .fireworks: "https://api.fireworks.ai/inference/v1"
        case .deepinfra: "https://api.deepinfra.com/v1/openai"
        case .mistral: "https://api.mistral.ai/v1"
        case .ollama: "http://localhost:11434/v1"
        case .customOpenAI: ""  // user must supply
        }
    }

    var defaultModel: String {
        models.first ?? ""
    }

    static func normalizedModelID(_ model: String) -> String {
        switch model.trimmingCharacters(in: .whitespacesAndNewlines) {
        case "gemini-3.1-flash-lite-preview":
            return "gemini-3.1-flash-lite"
        default:
            return model
        }
    }

    func supportedModelOrDefault(_ model: String?) -> String {
        guard let model else { return defaultModel }
        let normalized = Self.normalizedModelID(model)
        if supportsCustomModelName {
            return normalized
        }
        return models.contains(normalized) ? normalized : defaultModel
    }

    /// Only models that are currently in service AND accept image input + return structured text.
    /// Text-only and deprecated/preview models are excluded since this app needs vision for food photos.
    var models: [String] {
        switch self {
        case .gemini: [
            "gemini-3.1-flash-lite",         // vision, newest, cheapest
            "gemini-3.1-pro-preview",        // vision, newest flagship
            "gemini-3-flash-preview",        // vision, newest fast
            "gemini-2.5-flash",              // vision, prior fast
            "gemini-2.5-pro",                // vision, prior flagship
            "gemini-2.0-flash",              // vision, legacy fallback
        ]
        case .openai: [
            "gpt-5",                     // vision, current flagship
            "gpt-5-mini",                // vision, cheap
            "gpt-5-nano",                // vision, cheapest
            "gpt-4o",                    // vision, legacy
            "gpt-4o-mini",               // vision, legacy cheap
            "gpt-4.1",                   // vision, legacy
            "gpt-4.1-mini",              // vision, legacy
        ]
        case .anthropic: [
            "claude-sonnet-4-6",           // vision, current Sonnet (default)
            "claude-opus-4-7",             // vision, current flagship
            "claude-haiku-4-5",            // vision, current Haiku, fastest
            "claude-opus-4-5",             // vision, prior Opus
            "claude-sonnet-4-5-20250929",  // vision, prior Sonnet (dated)
            "claude-opus-4-1-20250805",    // vision, legacy Opus
        ]
        case .xai: [
            "grok-4",                    // vision, current flagship
            "grok-2-vision-latest",      // vision, rolling alias for legacy compat
        ]
        case .openrouter: [
            "openrouter/free",           // free tier, vision, no credits required
            "google/gemini-2.5-flash",
            "openai/gpt-4o",
            "anthropic/claude-sonnet-4",
            "meta-llama/llama-4-maverick",
        ]
        case .togetherai: [
            "meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8",  // vision
            "meta-llama/Llama-3.2-90B-Vision-Instruct-Turbo",     // vision
            "Qwen/Qwen2.5-VL-72B-Instruct",                       // vision
        ]
        case .groq: [
            "meta-llama/llama-4-scout-17b-16e-instruct",          // vision
        ]
        case .huggingface: [
            "google/gemma-3-27b-it",                              // vision, open-weight Gemma 3
            "Qwen/Qwen2.5-VL-72B-Instruct",                       // vision, open-weight Qwen VL
            "meta-llama/Llama-3.2-90B-Vision-Instruct",           // vision, open-weight Llama
        ]
        case .fireworks: [
            "accounts/fireworks/models/qwen2-vl-72b-instruct",    // vision
            "accounts/fireworks/models/llama-v3p2-90b-vision-instruct",  // vision
            "accounts/fireworks/models/phi-3-vision-128k-instruct",      // vision, small
        ]
        case .deepinfra: [
            "google/gemma-3-27b-it",                              // vision, open-weight Gemma 3
            "meta-llama/Llama-3.2-90B-Vision-Instruct",           // vision
            "Qwen/Qwen2.5-VL-72B-Instruct",                       // vision
        ]
        case .mistral: [
            "pixtral-large-latest",                               // vision, open-weight Pixtral
            "pixtral-12b-latest",                                 // vision, smaller Pixtral
        ]
        case .ollama: [
            "llama3.2-vision",
            "llava",
            "moondream",
        ]
        case .customOpenAI: []  // user types model name in Settings
        }
    }

    var requiresAPIKey: Bool {
        self != .ollama
    }

    /// True for providers where the user supplies the base URL and model name themselves.
    var requiresCustomEndpoint: Bool {
        self == .customOpenAI
    }

    /// True for providers where the user types a free-form model name (no preset list).
    var requiresCustomModelName: Bool {
        self == .customOpenAI
    }

    /// True for providers where free-form input is allowed in addition to the preset list
    /// (e.g., OpenRouter / Hugging Face — user can pick a preset OR type any model ID).
    var supportsCustomModelName: Bool {
        self == .openrouter || self == .huggingface || self == .customOpenAI
    }

    /// API format grouping
    enum APIFormat {
        case gemini
        case openaiCompatible
        case anthropic
    }

    var apiFormat: APIFormat {
        switch self {
        case .gemini: .gemini
        case .anthropic: .anthropic
        case .openai, .xai, .openrouter, .togetherai, .groq, .huggingface, .fireworks, .deepinfra, .mistral, .ollama, .customOpenAI: .openaiCompatible
        }
    }

    var apiKeyPlaceholder: String {
        switch self {
        case .gemini: "AIza..."
        case .openai: "sk-..."
        case .anthropic: "sk-ant-..."
        case .xai: "xai-..."
        case .openrouter: "sk-or-..."
        case .togetherai: "..."
        case .groq: "gsk_..."
        case .huggingface: "hf_..."
        case .fireworks: "fw_..."
        case .deepinfra: "..."
        case .mistral: "..."
        case .ollama: "No key needed"
        case .customOpenAI: "API key (or anything if endpoint doesn't need one)"
        }
    }
}

extension AIProvider {
    func openAICompatibleTokenLimitKey(for model: String) -> String {
        if self == .openai || (self == .customOpenAI && Self.usesOpenAICompletionTokenLimit(model: model)) {
            return "max_completion_tokens"
        }
        return "max_tokens"
    }

    private static func usesOpenAICompletionTokenLimit(model: String) -> Bool {
        let normalized = model
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(separator: "/")
            .last
            .map(String.init) ?? model.lowercased()

        return normalized.hasPrefix("gpt-5")
            || normalized.hasPrefix("o1")
            || normalized.hasPrefix("o3")
            || normalized.hasPrefix("o4")
    }
}

// MARK: - Settings Persistence

struct AIProviderSettings {
    private static let providerKey = "selectedAIProvider"
    private static let modelKey = "selectedAIModel"
    private static let apiKeyKeychainPrefix = "apikey_"
    private static let baseURLKey = "customBaseURL_"
    private static let userContextKey = "aiUserContext"
    private static let fallbackEnabledKey = "aiFallbackEnabled"
    private static let fallbackProviderKey = "selectedFallbackAIProvider"
    private static let fallbackModelKey = "selectedFallbackAIModel"

    static var selectedProvider: AIProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: providerKey),
                  let provider = AIProvider(rawValue: raw) else { return .gemini }
            return provider
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: providerKey)
        }
    }

    static var selectedModel: String {
        get {
            let saved = UserDefaults.standard.string(forKey: modelKey)
            // Validate against the provider's supported list and fall back to default
            // if the saved one was removed (e.g., a deprecated model we no longer expose).
            return selectedProvider.supportedModelOrDefault(saved)
        }
        set {
            UserDefaults.standard.set(AIProvider.normalizedModelID(newValue), forKey: modelKey)
        }
    }

    static func apiKey(for provider: AIProvider) -> String? {
        KeychainHelper.load(key: apiKeyKeychainPrefix + provider.rawValue)
    }

    static func setAPIKey(_ key: String?, for provider: AIProvider) {
        let keychainKey = apiKeyKeychainPrefix + provider.rawValue
        if let key, !key.isEmpty {
            KeychainHelper.save(key: keychainKey, value: key)
        } else {
            KeychainHelper.delete(key: keychainKey)
        }
    }

    static func customBaseURL(for provider: AIProvider) -> String? {
        UserDefaults.standard.string(forKey: baseURLKey + provider.rawValue)
    }

    static func setCustomBaseURL(_ url: String?, for provider: AIProvider) {
        if let url, !url.isEmpty {
            UserDefaults.standard.set(url, forKey: baseURLKey + provider.rawValue)
        } else {
            UserDefaults.standard.removeObject(forKey: baseURLKey + provider.rawValue)
        }
    }

    static var currentAPIKey: String? {
        apiKey(for: selectedProvider)
    }

    static var currentBaseURL: String {
        customBaseURL(for: selectedProvider) ?? selectedProvider.baseURL
    }

    /// Optional user-supplied context (region, diet, athletic goals, etc.)
    /// prepended as a system instruction to every AI request when non-empty.
    /// Empty string ⇒ nothing injected, request shape unchanged.
    static var userContext: String {
        get { UserDefaults.standard.string(forKey: userContextKey) ?? "" }
        set {
            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                UserDefaults.standard.removeObject(forKey: userContextKey)
            } else {
                UserDefaults.standard.set(newValue, forKey: userContextKey)
            }
        }
    }

    static var currentUserContext: String? {
        let ctx = userContext.trimmingCharacters(in: .whitespacesAndNewlines)
        return ctx.isEmpty ? nil : ctx
    }

    // MARK: - Fallback Provider

    /// Master toggle for fallback. When true and primary call fails, the app retries
    /// once on the configured fallback provider before surfacing the error.
    static var fallbackEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: fallbackEnabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: fallbackEnabledKey) }
    }

    static var selectedFallbackProvider: AIProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: fallbackProviderKey),
                  let provider = AIProvider(rawValue: raw) else {
                return providersWithSavedKeys(excluding: selectedProvider).first ?? .gemini
            }
            return provider
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: fallbackProviderKey) }
    }

    static var selectedFallbackModel: String {
        get {
            let provider = selectedFallbackProvider
            let saved = UserDefaults.standard.string(forKey: fallbackModelKey)
            return provider.supportedModelOrDefault(saved)
        }
        set { UserDefaults.standard.set(AIProvider.normalizedModelID(newValue), forKey: fallbackModelKey) }
    }

    /// Providers that have a saved API key (or don't require one, e.g. Ollama),
    /// optionally excluding the primary so the fallback picker doesn't list it.
    static func providersWithSavedKeys(excluding: AIProvider? = nil) -> [AIProvider] {
        AIProvider.allCases.filter { provider in
            if let excluding, provider == excluding { return false }
            if !provider.requiresAPIKey { return true }
            return apiKey(for: provider) != nil
        }
    }

    struct FallbackConfig {
        let provider: AIProvider
        let model: String
        let baseURL: String
        let apiKey: String?
    }

    /// Returns the resolved fallback config when (a) fallback is enabled, (b) the fallback
    /// provider has a usable key (or doesn't require one), and (c) the fallback config
    /// isn't byte-for-byte identical to the primary (same provider + model = pointless retry).
    /// Same provider with a *different* model IS allowed — common pattern is e.g. Gemini Pro
    /// primary with Gemini Flash fallback for capacity-pool diversity within one provider.
    static func currentFallbackConfig(excludingPrimary primary: AIProvider) -> FallbackConfig? {
        guard fallbackEnabled else { return nil }
        let provider = selectedFallbackProvider
        let model = selectedFallbackModel
        if provider == primary, model == selectedModel { return nil }
        if provider.requiresAPIKey, apiKey(for: provider) == nil { return nil }
        return FallbackConfig(
            provider: provider,
            model: model,
            baseURL: customBaseURL(for: provider) ?? provider.baseURL,
            apiKey: apiKey(for: provider)
        )
    }

    static func deleteAllData() {
        for provider in AIProvider.allCases {
            setAPIKey(nil, for: provider)
            setCustomBaseURL(nil, for: provider)
        }
        UserDefaults.standard.removeObject(forKey: providerKey)
        UserDefaults.standard.removeObject(forKey: modelKey)
        UserDefaults.standard.removeObject(forKey: userContextKey)
        UserDefaults.standard.removeObject(forKey: fallbackEnabledKey)
        UserDefaults.standard.removeObject(forKey: fallbackProviderKey)
        UserDefaults.standard.removeObject(forKey: fallbackModelKey)
    }
}
