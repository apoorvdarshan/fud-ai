import Foundation

enum AIProvider: String, CaseIterable, Codable, Identifiable {
    case appleIntelligence = "Apple Intelligence (On-Device)"
    case gemma4Local = "Gemma 4 E2B (On-Device)"
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
    case deepseek = "DeepSeek"
    case cerebras = "Cerebras"
    case ollama = "Ollama (Local)"
    case customOpenAI = "Custom (OpenAI-compatible)"

    var id: String { rawValue }

    var displayName: String {
        if self == .gemma4Local {
            return LocalModelStrings.text(
                "gemma.providerName",
                defaultValue: "Gemma 4 E2B (On-Device)"
            )
        }
        return rawValue
    }

    var logoAssetName: String? {
        switch self {
        case .appleIntelligence, .gemma4Local, .customOpenAI: nil
        case .gemini: "provider_gemini"
        case .openai: "provider_openai"
        case .anthropic: "provider_anthropic"
        case .xai: "provider_xai"
        case .openrouter: "provider_openrouter"
        case .togetherai: "provider_together"
        case .groq: "provider_groq"
        case .huggingface: "provider_huggingface"
        case .fireworks: "provider_fireworks"
        case .deepinfra: "provider_deepinfra"
        case .mistral: "provider_mistral"
        case .deepseek: "provider_deepseek"
        case .cerebras: "provider_cerebras"
        case .ollama: "provider_ollama"
        }
    }

    var fallbackSystemImage: String {
        switch self {
        case .appleIntelligence: "apple.logo"
        case .gemma4Local: "cpu"
        case .customOpenAI: "wrench.and.screwdriver.fill"
        default: "sparkles"
        }
    }

    var baseURL: String {
        switch self {
        case .appleIntelligence, .gemma4Local: ""
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
        case .deepseek: "https://api.deepseek.com"
        case .cerebras: "https://api.cerebras.ai/v1"
        case .ollama: "http://localhost:11434/v1"
        case .customOpenAI: ""  // user must supply
        }
    }

    var defaultModel: String {
        models.first ?? ""
    }

    var defaultTextModel: String {
        textModels.first ?? defaultModel
    }

    /// Providers exposed for photo/label analysis. DeepSeek and Cerebras are
    /// intentionally text-only here even though the same request transport is used.
    static var visionProviders: [AIProvider] {
        allCases.filter { $0.supportsVision && $0.isAvailableOnCurrentDevice }
    }

    static var textProviders: [AIProvider] {
        allCases.filter {
            ($0.isAvailableOnCurrentDevice && !$0.textModels.isEmpty) || $0.requiresCustomModelName
        }
    }

    var isAvailableOnCurrentDevice: Bool {
        self != .gemma4Local || Gemma4LocalModelManager.isCurrentDeviceSelectable
    }

    var supportsVision: Bool {
        switch self {
        case .appleIntelligence, .deepseek, .cerebras:
            false
        default:
            true
        }
    }

    static func normalizedModelID(_ model: String) -> String {
        switch model.trimmingCharacters(in: .whitespacesAndNewlines) {
        case "gemini-3.1-flash-lite-preview":
            return "gemini-3.1-flash-lite"
        default:
            return model
        }
    }

    /// One-time upgrade path for older Gemini presets. This intentionally stays
    /// separate from normalization so users may still select supported older
    /// models after the migration has run.
    static func upgradedLegacyGeminiModel(_ model: String?) -> String? {
        guard let model else { return nil }
        switch normalizedModelID(model) {
        case "gemini-2.5-flash", "gemini-2.5-pro", "gemini-3.1-flash-lite":
            return "gemini-3.5-flash-lite"
        default:
            return nil
        }
    }

    /// Provider-scoped replacements for presets removed from the current registry.
    /// This also covers providers that permit free-form model IDs: those values would
    /// otherwise remain valid indefinitely and never reach `supportedModelOrDefault`'s
    /// fixed-list fallback path.
    static func upgradedLegacyModel(for provider: AIProvider, model: String?) -> String? {
        guard let model else { return nil }
        let normalized = normalizedModelID(model)

        switch (provider, normalized) {
        case (.gemini, "gemini-3.1-flash-lite"):
            return "gemini-3.5-flash-lite"
        case (.openrouter, "google/gemini-3.1-flash-lite"):
            return "google/gemini-3.5-flash-lite"
        case (.huggingface, "Qwen/Qwen2.5-VL-72B-Instruct"):
            return "Qwen/Qwen3.8-27B"
        case (.mistral, "mistral-medium-2604"):
            return "mistral-medium-3-5"
        case (.anthropic, "claude-sonnet-4-6"):
            return "claude-sonnet-5"
        case (.anthropic, "claude-opus-4-7"):
            return "claude-opus-5"
        default:
            return nil
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

    func supportedTextModelOrDefault(_ model: String?) -> String {
        guard let model else { return defaultTextModel }
        let normalized = Self.normalizedModelID(model)
        if supportsCustomModelName {
            return normalized
        }
        return textModels.contains(normalized) ? normalized : defaultTextModel
    }

    /// Only models that are currently in service AND accept image input + return structured text.
    /// Text-only and deprecated models are excluded since this app needs vision for food photos.
    /// Lineups verified against provider docs on 2026-09-07.
    var models: [String] {
        switch self {
        case .appleIntelligence: [] // text-only system model; never offered for image requests
        case .gemma4Local: [Gemma4LocalModelManager.modelID]
        case .gemini: [
            "gemini-3.5-flash-lite",         // vision, cheapest current stable model (default)
            "gemini-3.8-flash",              // vision, latest Flash model (GA 2026-09-02)
            "gemini-3.7-flash",              // vision, prior Flash model
            "gemini-3.6-flash",              // vision, prior stable Flash model
            "gemini-3.5-flash",              // vision, stable Flash model
            "gemini-3.1-pro-preview",        // vision, current flagship (preview)
        ]
        case .openai: [
            "gpt-5.4-mini",              // vision, best price/perf
            "gpt-5.6-sol",               // vision, latest flagship
            "gpt-5.6-terra",             // vision, balanced
            "gpt-5.6-luna",              // vision, lowest-cost 5.6 model
            "gpt-5.5",                   // vision, current flagship
            "gpt-5.4-nano",              // vision, cheapest
            "gpt-4.1",                   // vision, legacy
            "gpt-4.1-mini",              // vision, legacy cheap
            "gpt-4o-mini",               // vision, legacy cheap
        ]
        case .anthropic: [
            "claude-sonnet-5",             // vision, current Sonnet (default)
            "claude-opus-5",               // vision, latest flagship
            "claude-fable-5",              // vision, latest efficient model
            "claude-opus-4-8",             // vision, prior flagship
            "claude-haiku-4-5",            // vision, current Haiku, fastest
        ]
        case .xai: [
            "grok-4.6",                  // vision, latest
            "grok-4.3",                  // vision, prior (grok-4 and grok-2-vision retired)
        ]
        case .openrouter: [
            "openrouter/free",           // free tier, vision, no credits required
            "google/gemini-3.5-flash-lite",
            "google/gemini-3.8-flash",
            "google/gemini-3.7-flash",
            "openai/gpt-5.6-luna",
            "qwen/qwen3.8-27b",
            "openai/gpt-5-mini",
            "anthropic/claude-sonnet-5",
            "qwen/qwen3-vl-8b-instruct",
        ]
        case .togetherai: [
            "Qwen/Qwen3.5-9B",                                    // vision
            "moonshotai/Kimi-K3",                                 // vision
            "Qwen/Qwen3.8-2.4T-A95B",                             // vision
            "google/gemma-4-31B-it",                              // vision
            "MiniMaxAI/MiniMax-M3",                               // vision
        ]
        case .groq: [
            "qwen/qwen3.6-27b",                                   // vision (llama-4-scout shutdown 2026-07-17)
        ]
        case .huggingface: [
            "google/gemma-4-31B-it",                              // vision, widest provider coverage
            "Qwen/Qwen3.8-27B",                                   // vision, tool calling
            "moonshotai/Kimi-K3",                                 // vision, tool calling
            "google/gemma-3-27b-it",                              // vision, open-weight Gemma 3
            "Qwen/Qwen3.5-9B",                                    // vision, open-weight Qwen
        ]
        case .fireworks: [
            "accounts/fireworks/models/qwen3p7-plus",             // vision, serverless
            "accounts/fireworks/models/kimi-k3",                  // vision, serverless
            "accounts/fireworks/models/muse-glimmer-30b",         // vision, serverless
            "accounts/fireworks/models/minimax-m3",               // vision, serverless
            "accounts/fireworks/models/kimi-k2p6",                // vision, serverless
        ]
        case .deepinfra: [
            "google/gemma-3-27b-it",                              // vision, cheapest
            "Qwen/Qwen3.8-27B",                                   // vision
            "MiniMaxAI/MiniMax-M3",                               // vision
            "google/gemma-4-31B-it",                              // vision
            "google/gemma-4-26B-A4B-it",                          // vision
        ]
        case .mistral: [
            "mistral-small-2603",                                 // vision, best value (Pixtral line retired)
            "mistral-medium-3-5",                                 // vision, frontier
            "mistral-large-2512",                                 // vision, flagship
            "ministral-14b-2512",                                 // vision, small
        ]
        case .ollama: [
            "qwen3-vl",
            "qwen3.8",
            "gemma4",
            "llama3.2-vision",
            "llava",
            "moondream",
        ]
        case .deepseek, .cerebras: [] // text-only; never offered for image requests
        case .customOpenAI: []  // user types model name in Settings
        }
    }

    /// Text-capable presets. Existing image models remain valid for text, while
    /// providers with broader text catalogs expose those additional choices here.
    /// Current hosted IDs verified against provider docs on 2026-09-07.
    var textModels: [String] {
        switch self {
        case .appleIntelligence:
            return ["System Language Model"]
        case .groq:
            return [
                "openai/gpt-oss-20b",
                "openai/gpt-oss-120b",
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant",
                "qwen/qwen3.6-27b",
                "qwen/qwen3.8-27b",
            ]
        case .togetherai:
            return [
                "MiniMaxAI/MiniMax-M2.7",
                "Qwen/Qwen3.7-Max",
                "Qwen/Qwen3.5-397B-A17B",
                "Qwen/Qwen3.6-Plus",
                "Qwen/Qwen3.5-9B",
                "moonshotai/Kimi-K2.6",
                "zai-org/GLM-5.1",
                "zai-org/GLM-5",
                "openai/gpt-oss-120b",
                "openai/gpt-oss-20b",
                "deepseek-ai/DeepSeek-V4-Pro",
            ]
        case .deepseek:
            return ["deepseek-v4-flash", "deepseek-v4-pro"]
        case .cerebras:
            return ["gpt-oss-120b", "gemma-4-31b"]
        case .ollama:
            return ["qwen3.8", "gemma4", "llama3.2", "qwen3", "mistral-small3.2"] + models
        case .customOpenAI:
            return []
        default:
            return models
        }
    }

    var requiresAPIKey: Bool {
        self != .ollama && self != .appleIntelligence && self != .gemma4Local
    }

    /// True for providers where the user supplies the base URL and model name themselves.
    var requiresCustomEndpoint: Bool {
        self == .customOpenAI
    }

    /// True for providers where the user types a free-form model name (no preset list).
    var requiresCustomModelName: Bool {
        self == .customOpenAI
    }

    /// Local and user-hosted endpoints often need substantially longer than the
    /// platform's default 60-second request timeout, especially for vision models.
    var usesConfigurableRequestTimeout: Bool {
        self == .ollama || self == .customOpenAI
    }

    /// True for providers where free-form input is allowed in addition to the preset list
    /// (e.g., OpenRouter / Hugging Face — user can pick a preset OR type any model ID).
    var supportsCustomModelName: Bool {
        self == .openrouter || self == .huggingface || self == .customOpenAI
    }

    /// API format grouping
    enum APIFormat {
        case onDevice
        case liteRTLocal
        case gemini
        case openaiCompatible
        case anthropic
    }

    var apiFormat: APIFormat {
        switch self {
        case .appleIntelligence: .onDevice
        case .gemma4Local: .liteRTLocal
        case .gemini: .gemini
        case .anthropic: .anthropic
        case .openai, .xai, .openrouter, .togetherai, .groq, .huggingface, .fireworks, .deepinfra, .mistral, .deepseek, .cerebras, .ollama, .customOpenAI: .openaiCompatible
        }
    }

    var apiKeyPlaceholder: String {
        switch self {
        case .appleIntelligence, .gemma4Local: "No key needed"
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
        case .deepseek: "sk-..."
        case .cerebras: "csk-..."
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

enum OpenRouterReasoningEffort: String, CaseIterable, Identifiable {
    case auto, none, minimal, low, medium, high, xhigh, max
    var id: String { rawValue }
    var title: String {
        switch self {
        case .auto: String(localized: "Default / Auto")
        case .none: String(localized: "None")
        case .minimal: String(localized: "Minimal")
        case .low: String(localized: "Low")
        case .medium: String(localized: "Medium")
        case .high: String(localized: "High")
        case .xhigh: String(localized: "Extra High")
        case .max: String(localized: "Max")
        }
    }
    func requestOptions(compactRetry: Bool, exclude: Bool) -> [String: Any]? {
        var options: [String: Any] = [:]
        if exclude || compactRetry { options["exclude"] = true }
        if compactRetry { options["effort"] = "low" }
        else if self != .auto { options["effort"] = rawValue }
        return options.isEmpty ? nil : options
    }
}

struct AIProviderSettings {
    static var openRouterReasoningEffort: OpenRouterReasoningEffort {
        get { OpenRouterReasoningEffort(rawValue: UserDefaults.standard.string(forKey: "openRouterReasoningEffort") ?? "auto") ?? .auto }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "openRouterReasoningEffort") }
    }

    private static let providerKey = "selectedAIProvider"
    private static let modelKey = "selectedAIModel"
    private static let separateTextProviderEnabledKey = "separateTextProviderEnabled"
    private static let textProviderKey = "selectedTextAIProvider"
    private static let textModelKey = "selectedTextAIModel"
    private static let apiKeyKeychainPrefix = "apikey_"
    private static let baseURLKey = "customBaseURL_"
    private static let userContextKey = "aiUserContext"
    private static let fallbackEnabledKey = "aiFallbackEnabled"
    private static let fallbackProviderKey = "selectedFallbackAIProvider"
    private static let fallbackModelKey = "selectedFallbackAIModel"
    private static let textFallbackEnabledKey = "textAIFallbackEnabled"
    private static let textFallbackProviderKey = "selectedTextFallbackAIProvider"
    private static let textFallbackModelKey = "selectedTextFallbackAIModel"
    private static let fallbackBaseURLKey = "fallbackCustomBaseURL_"
    private static let fallbackBaseURLMigrationVersionKey = "fallbackBaseURLMigrationVersion"
    private static let geminiModelMigrationVersionKey = "geminiModelMigrationVersion"
    private static let modelRegistryMigrationVersionKey = "aiModelRegistryMigrationVersion"
    private static let currentModelRegistryMigrationVersion = 2
    private static let maxResponseTokensKey = "aiMaxResponseTokens"
    private static let requestTimeoutSecondsKey = "aiRequestTimeoutSeconds"

    /// The AI output-token cap sent with every request (`max_tokens` /
    /// `max_completion_tokens` / Gemini `maxOutputTokens`). Default 1024; raise it for
    /// local models whose replies get truncated.
    static var maxResponseTokens: Int {
        get {
            let v = UserDefaults.standard.integer(forKey: maxResponseTokensKey)
            return v > 0 ? v : 1024 // 0 == unset -> default
        }
        set { UserDefaults.standard.set(max(1, newValue), forKey: maxResponseTokensKey) }
    }

    /// Timeout used by local/custom AI endpoints. Cloud providers retain the
    /// standard URLSession timeout. Default 180 seconds; configurable in Settings.
    static var requestTimeoutSeconds: Int {
        get {
            let value = UserDefaults.standard.integer(forKey: requestTimeoutSecondsKey)
            return value > 0 ? min(max(value, 30), 600) : 180
        }
        set {
            UserDefaults.standard.set(min(max(newValue, 30), 600), forKey: requestTimeoutSecondsKey)
        }
    }

    static func requestTimeout(for provider: AIProvider) -> TimeInterval? {
        provider.usesConfigurableRequestTimeout ? TimeInterval(requestTimeoutSeconds) : nil
    }

    static var selectedProvider: AIProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: providerKey),
                  let provider = AIProvider(rawValue: raw),
                  AIProvider.visionProviders.contains(provider) else {
                UserDefaults.standard.set(AIProvider.gemini.rawValue, forKey: providerKey)
                UserDefaults.standard.set(AIProvider.gemini.defaultModel, forKey: modelKey)
                return .gemini
            }
            return provider
        }
        set {
            let resolved = AIProvider.visionProviders.contains(newValue) ? newValue : .gemini
            UserDefaults.standard.set(resolved.rawValue, forKey: providerKey)
        }
    }

    static var separateTextProviderEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: separateTextProviderEnabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: separateTextProviderEnabledKey) }
    }

    static var selectedTextProvider: AIProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: textProviderKey),
                  let provider = AIProvider(rawValue: raw),
                  AIProvider.textProviders.contains(provider) else {
                if UserDefaults.standard.string(forKey: textProviderKey) == AIProvider.gemma4Local.rawValue {
                    UserDefaults.standard.set(false, forKey: separateTextProviderEnabledKey)
                }
                UserDefaults.standard.set(AIProvider.gemini.rawValue, forKey: textProviderKey)
                UserDefaults.standard.set(AIProvider.gemini.defaultTextModel, forKey: textModelKey)
                return .gemini
            }
            return provider
        }
        set {
            let resolved = AIProvider.textProviders.contains(newValue) ? newValue : .gemini
            UserDefaults.standard.set(resolved.rawValue, forKey: textProviderKey)
        }
    }

    static var selectedTextModel: String {
        get {
            let provider = selectedTextProvider
            let saved = UserDefaults.standard.string(forKey: textModelKey)
            let resolved = provider.supportedTextModelOrDefault(saved)
            if let saved, AIProvider.normalizedModelID(saved) != resolved {
                UserDefaults.standard.set(resolved, forKey: textModelKey)
            }
            return resolved
        }
        set { UserDefaults.standard.set(AIProvider.normalizedModelID(newValue), forKey: textModelKey) }
    }

    /// Upgrades legacy Gemini choices exactly once, including the fallback.
    /// A marker prevents a later manual choice of a still-supported older model
    /// from being overwritten on every launch.
    static func migrateLegacyGeminiModelsIfNeeded(defaults: UserDefaults = .standard) {
        if defaults.integer(forKey: geminiModelMigrationVersionKey) < 1 {
            if (storedProvider(in: defaults, key: providerKey) ?? .gemini) == .gemini,
               let upgraded = AIProvider.upgradedLegacyGeminiModel(defaults.string(forKey: modelKey)) {
                defaults.set(upgraded, forKey: modelKey)
            }

            if storedProvider(in: defaults, key: fallbackProviderKey) == .gemini,
               let upgraded = AIProvider.upgradedLegacyGeminiModel(defaults.string(forKey: fallbackModelKey)) {
                defaults.set(upgraded, forKey: fallbackModelKey)
            }

            defaults.set(1, forKey: geminiModelMigrationVersionKey)
        }

        migrateModelRegistryIfNeeded(defaults: defaults)
    }

    /// Migrates removed presets exactly once without touching credentials, endpoints,
    /// user context, or any other app data. Primary and fallback selections are handled
    /// independently and only with the migration table for their saved provider.
    static func migrateModelRegistryIfNeeded(defaults: UserDefaults = .standard) {
        guard defaults.integer(forKey: modelRegistryMigrationVersionKey) < currentModelRegistryMigrationVersion else {
            return
        }

        let primaryProvider = storedProvider(in: defaults, key: providerKey) ?? .gemini
        if let upgraded = AIProvider.upgradedLegacyModel(
            for: primaryProvider,
            model: defaults.string(forKey: modelKey)
        ) {
            defaults.set(upgraded, forKey: modelKey)
        }

        if let fallbackProvider = storedProvider(in: defaults, key: fallbackProviderKey),
           let upgraded = AIProvider.upgradedLegacyModel(
               for: fallbackProvider,
               model: defaults.string(forKey: fallbackModelKey)
           ) {
            defaults.set(upgraded, forKey: fallbackModelKey)
        }

        defaults.set(currentModelRegistryMigrationVersion, forKey: modelRegistryMigrationVersionKey)
    }

    private static func storedProvider(
        in defaults: UserDefaults,
        key: String
    ) -> AIProvider? {
        guard let rawValue = defaults.string(forKey: key),
              let provider = AIProvider(rawValue: rawValue) else {
            return nil
        }
        return provider
    }

    /// Splits the fallback role's base URL off the shared per-provider key exactly once.
    /// The fallback config historically read the same `customBaseURL_` entry as the
    /// primary, so two configurations of the same provider type could never point at
    /// different servers. Seeding the role-scoped key from the shared value keeps
    /// existing fallback setups working after the split (Custom endpoints have no
    /// default URL to fall back to).
    static func migrateFallbackBaseURLsIfNeeded() {
        let defaults = UserDefaults.standard
        guard defaults.integer(forKey: fallbackBaseURLMigrationVersionKey) < 1 else { return }

        for provider in AIProvider.allCases where fallbackCustomBaseURL(for: provider) == nil {
            if let shared = customBaseURL(for: provider) {
                defaults.set(shared, forKey: fallbackBaseURLKey + provider.rawValue)
            }
        }

        defaults.set(1, forKey: fallbackBaseURLMigrationVersionKey)
    }

    static var selectedModel: String {
        get {
            let saved = UserDefaults.standard.string(forKey: modelKey)
            // Validate against the provider's supported list and fall back to default
            // if the saved one was removed (e.g., a deprecated model we no longer expose).
            let resolved = selectedProvider.supportedModelOrDefault(saved)
            if let saved, AIProvider.normalizedModelID(saved) != resolved {
                UserDefaults.standard.set(resolved, forKey: modelKey)
            }
            return resolved
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

    /// Base URL override used when the provider runs in the fallback role. Stored
    /// separately from `customBaseURL(for:)` so a primary and fallback of the same
    /// provider type can point at different servers.
    static func fallbackCustomBaseURL(for provider: AIProvider) -> String? {
        UserDefaults.standard.string(forKey: fallbackBaseURLKey + provider.rawValue)
    }

    static func setFallbackCustomBaseURL(_ url: String?, for provider: AIProvider) {
        if let url, !url.isEmpty {
            UserDefaults.standard.set(url, forKey: fallbackBaseURLKey + provider.rawValue)
        } else {
            UserDefaults.standard.removeObject(forKey: fallbackBaseURLKey + provider.rawValue)
        }
    }

    static var currentAPIKey: String? {
        apiKey(for: selectedProvider)
    }

    static var currentBaseURL: String {
        customBaseURL(for: selectedProvider) ?? selectedProvider.baseURL
    }

    struct RequestConfig {
        let provider: AIProvider
        let model: String
        let baseURL: String
        let apiKey: String?
    }

    /// Image requests always use the vision provider. Text-only requests use the
    /// optional dedicated selection when enabled; otherwise behavior is unchanged.
    static func currentConfig(requiresVision: Bool) -> RequestConfig {
        let useSeparateText = !requiresVision && separateTextProviderEnabled
        let provider = useSeparateText ? selectedTextProvider : selectedProvider
        let model = useSeparateText ? selectedTextModel : selectedModel
        return RequestConfig(
            provider: provider,
            model: model,
            baseURL: customBaseURL(for: provider) ?? provider.baseURL,
            apiKey: apiKey(for: provider)
        )
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

    // MARK: - Image AI Fallback

    /// Master toggle for fallback. When true and primary call fails, the app retries
    /// once on the configured fallback provider before surfacing the error.
    static var fallbackEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: fallbackEnabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: fallbackEnabledKey) }
    }

    static var selectedFallbackProvider: AIProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: fallbackProviderKey),
                  let provider = AIProvider(rawValue: raw),
                  AIProvider.visionProviders.contains(provider) else {
                if UserDefaults.standard.string(forKey: fallbackProviderKey) == AIProvider.gemma4Local.rawValue {
                    UserDefaults.standard.set(false, forKey: fallbackEnabledKey)
                }
                let resolved = providersWithSavedKeys(excluding: selectedProvider).first ?? .gemini
                UserDefaults.standard.set(resolved.rawValue, forKey: fallbackProviderKey)
                UserDefaults.standard.set(resolved.defaultModel, forKey: fallbackModelKey)
                return resolved
            }
            return provider
        }
        set {
            let resolved = AIProvider.visionProviders.contains(newValue) ? newValue : .gemini
            UserDefaults.standard.set(resolved.rawValue, forKey: fallbackProviderKey)
        }
    }

    static var selectedFallbackModel: String {
        get {
            let provider = selectedFallbackProvider
            let saved = UserDefaults.standard.string(forKey: fallbackModelKey)
            let resolved = provider.supportedModelOrDefault(saved)
            if let saved, AIProvider.normalizedModelID(saved) != resolved {
                UserDefaults.standard.set(resolved, forKey: fallbackModelKey)
            }
            return resolved
        }
        set { UserDefaults.standard.set(AIProvider.normalizedModelID(newValue), forKey: fallbackModelKey) }
    }

    // MARK: - Text AI Fallback

    static var textFallbackEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: textFallbackEnabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: textFallbackEnabledKey) }
    }

    static var selectedTextFallbackProvider: AIProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: textFallbackProviderKey),
                  let provider = AIProvider(rawValue: raw),
                  AIProvider.textProviders.contains(provider) else {
                if UserDefaults.standard.string(forKey: textFallbackProviderKey) == AIProvider.gemma4Local.rawValue {
                    UserDefaults.standard.set(false, forKey: textFallbackEnabledKey)
                }
                UserDefaults.standard.set(AIProvider.gemini.rawValue, forKey: textFallbackProviderKey)
                UserDefaults.standard.set(AIProvider.gemini.defaultTextModel, forKey: textFallbackModelKey)
                return .gemini
            }
            return provider
        }
        set {
            let resolved = AIProvider.textProviders.contains(newValue) ? newValue : .gemini
            UserDefaults.standard.set(resolved.rawValue, forKey: textFallbackProviderKey)
        }
    }

    static var selectedTextFallbackModel: String {
        get {
            let provider = selectedTextFallbackProvider
            let saved = UserDefaults.standard.string(forKey: textFallbackModelKey)
            let resolved = provider.supportedTextModelOrDefault(saved)
            if let saved, AIProvider.normalizedModelID(saved) != resolved {
                UserDefaults.standard.set(resolved, forKey: textFallbackModelKey)
            }
            return resolved
        }
        set { UserDefaults.standard.set(AIProvider.normalizedModelID(newValue), forKey: textFallbackModelKey) }
    }

    /// Providers that have a saved API key (or don't require one, e.g. Ollama),
    /// optionally excluding the primary so the fallback picker doesn't list it.
    static func providersWithSavedKeys(excluding: AIProvider? = nil) -> [AIProvider] {
        AIProvider.visionProviders.filter { provider in
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
    /// isn't byte-for-byte identical to the primary (same provider + model + server =
    /// pointless retry). Same provider with a *different* model IS allowed — common pattern
    /// is e.g. Gemini Pro primary with Gemini Flash fallback for capacity-pool diversity
    /// within one provider — and so is the same model on a *different* server.
    static func currentImageFallbackConfig(excludingPrimary primary: AIProvider, model primaryModel: String) -> FallbackConfig? {
        guard fallbackEnabled else { return nil }
        let provider = selectedFallbackProvider
        let model = selectedFallbackModel
        let baseURL = fallbackCustomBaseURL(for: provider) ?? provider.baseURL
        if provider == primary, model == primaryModel,
           baseURL == (customBaseURL(for: primary) ?? primary.baseURL) { return nil }
        if provider.requiresAPIKey, apiKey(for: provider) == nil { return nil }
        return FallbackConfig(
            provider: provider,
            model: model,
            baseURL: baseURL,
            apiKey: apiKey(for: provider)
        )
    }

    static func currentTextFallbackConfig(excludingPrimary primary: AIProvider, model primaryModel: String) -> FallbackConfig? {
        guard textFallbackEnabled else { return nil }
        let provider = selectedTextFallbackProvider
        let model = selectedTextFallbackModel
        let baseURL = fallbackCustomBaseURL(for: provider) ?? provider.baseURL
        if provider == primary, model == primaryModel,
           baseURL == (customBaseURL(for: primary) ?? primary.baseURL) { return nil }
        if provider.requiresAPIKey, apiKey(for: provider) == nil { return nil }
        return FallbackConfig(
            provider: provider,
            model: model,
            baseURL: baseURL,
            apiKey: apiKey(for: provider)
        )
    }

    static func deleteAllData() {
        for provider in AIProvider.allCases {
            setAPIKey(nil, for: provider)
            setCustomBaseURL(nil, for: provider)
            setFallbackCustomBaseURL(nil, for: provider)
        }
        UserDefaults.standard.removeObject(forKey: providerKey)
        UserDefaults.standard.removeObject(forKey: modelKey)
        UserDefaults.standard.removeObject(forKey: separateTextProviderEnabledKey)
        UserDefaults.standard.removeObject(forKey: textProviderKey)
        UserDefaults.standard.removeObject(forKey: textModelKey)
        UserDefaults.standard.removeObject(forKey: userContextKey)
        UserDefaults.standard.removeObject(forKey: fallbackEnabledKey)
        UserDefaults.standard.removeObject(forKey: fallbackProviderKey)
        UserDefaults.standard.removeObject(forKey: fallbackModelKey)
        UserDefaults.standard.removeObject(forKey: textFallbackEnabledKey)
        UserDefaults.standard.removeObject(forKey: textFallbackProviderKey)
        UserDefaults.standard.removeObject(forKey: textFallbackModelKey)
        UserDefaults.standard.removeObject(forKey: fallbackBaseURLMigrationVersionKey)
        UserDefaults.standard.removeObject(forKey: requestTimeoutSecondsKey)
    }

    static func replaceDeletedLocalGemmaSelections(defaults: UserDefaults = .standard) {
        if defaults.string(forKey: providerKey) == AIProvider.gemma4Local.rawValue {
            defaults.set(AIProvider.gemini.rawValue, forKey: providerKey)
            defaults.set(AIProvider.gemini.defaultModel, forKey: modelKey)
        }
        if defaults.string(forKey: textProviderKey) == AIProvider.gemma4Local.rawValue {
            defaults.set(AIProvider.gemini.rawValue, forKey: textProviderKey)
            defaults.set(AIProvider.gemini.defaultTextModel, forKey: textModelKey)
            defaults.set(false, forKey: separateTextProviderEnabledKey)
        }
        if defaults.string(forKey: fallbackProviderKey) == AIProvider.gemma4Local.rawValue {
            defaults.set(AIProvider.gemini.rawValue, forKey: fallbackProviderKey)
            defaults.set(AIProvider.gemini.defaultModel, forKey: fallbackModelKey)
            defaults.set(false, forKey: fallbackEnabledKey)
        }
        if defaults.string(forKey: textFallbackProviderKey) == AIProvider.gemma4Local.rawValue {
            defaults.set(AIProvider.gemini.rawValue, forKey: textFallbackProviderKey)
            defaults.set(AIProvider.gemini.defaultTextModel, forKey: textFallbackModelKey)
            defaults.set(false, forKey: textFallbackEnabledKey)
        }
    }
}
