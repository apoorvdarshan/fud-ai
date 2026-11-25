import Foundation

enum AIProvider: String, CaseIterable, Codable, Identifiable {
    case appleIntelligence = "Apple Intelligence (On-Device)"
    case gemma4Local = "Gemma 4 E2B (On-Device)"
    case gemma4E4BLocal = "Gemma 4 E4B (On-Device)"
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
    case qwen = "Alibaba Qwen"
    case cerebras = "Cerebras"
    case ollama = "Ollama (Local)"
    case customOpenAI = "Custom (OpenAI-compatible)"

    var id: String { rawValue }

    /// The app's normal multi-photo meal limit.
    static let defaultMealPhotoLimit = 10

    /// Groq's Qwen vision models accept at most three images per request (#712).
    static let groqRequestImageLimit = 3

    /// The OpenRouter catalog marks both Nemotron variants as text-only.
    static func isKnownTextOnlyModel(provider: AIProvider, model: String) -> Bool {
        provider == .openrouter && model.prefix(while: { $0 != ":" }) == "nvidia/nemotron-3-ultra-550b-a55b"
    }

    /// Photos one request to this model accepts; nil takes the full selection.
    static func requestImageLimit(provider: AIProvider, model: String) -> Int? {
        if provider == .groq { return groqRequestImageLimit }
        if provider == .cerebras, model == "qwen-3.8-27b" { return 2 }
        return nil
    }

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
        case .appleIntelligence, .gemma4Local, .gemma4E4BLocal, .customOpenAI: nil
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
        case .qwen: "provider_qwen"
        case .cerebras: "provider_cerebras"
        case .ollama: "provider_ollama"
        }
    }

    var fallbackSystemImage: String {
        switch self {
        case .appleIntelligence: "apple.logo"
        case .gemma4Local, .gemma4E4BLocal: "cpu"
        case .customOpenAI: "wrench.and.screwdriver.fill"
        default: "sparkles"
        }
    }

    var baseURL: String {
        switch self {
        case .appleIntelligence, .gemma4Local, .gemma4E4BLocal: ""
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
        // Model Studio's public international (Singapore) endpoint.
        case .qwen: "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
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

    /// Providers with a photo/label preset or an explicit custom endpoint.
    static var visionProviders: [AIProvider] {
        allCases.filter { $0.supportsVision && $0.isAvailableOnCurrentDevice }
    }

    static var textProviders: [AIProvider] {
        allCases.filter {
            ($0.isAvailableOnCurrentDevice && !$0.textModels.isEmpty) || $0.requiresCustomModelName
        }
    }

    var isAvailableOnCurrentDevice: Bool {
        localGemmaVariant.map(Gemma4LocalModelManager.isCurrentDeviceSelectable) ?? true
    }

    var localGemmaVariant: Gemma4LocalModelManager.Variant? {
        switch self {
        case .gemma4Local: .e2b
        case .gemma4E4BLocal: .e4b
        default: nil
        }
    }

    var supportsVision: Bool {
        switch self {
        case .appleIntelligence:
            if #available(iOS 27.0, *) {
                true
            } else {
                false
            }
        default:
            !models.isEmpty || requiresCustomModelName
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
        case "gemini-2.5-pro":
            return "gemini-3.8-flash"
        case "gemini-2.5-flash", "gemini-3.1-flash-lite":
            return "gemini-3.5-flash-lite"
        default:
            return nil
        }
    }

    /// Provider-scoped replacements for presets removed from the current registry.
    /// Don't rewrite manually entered router or custom endpoint model IDs.
    static func upgradedLegacyModel(for provider: AIProvider, model: String?, text: Bool = false) -> String? {
        guard let model else { return nil }
        let normalized = normalizedModelID(model)

        switch (provider, normalized) {
        case (.gemini, "gemini-3.7-flash"): return "gemini-3.8-flash"
        case (.gemini, "gemini-3.5-flash"): return "gemini-3.6-flash"
        case (.gemini, _): return upgradedLegacyGeminiModel(normalized)
        case (.openai, "gpt-4.1"), (.openai, "gpt-4.1-mini"),
             (.openai, "gpt-4o-mini"), (.openai, "gpt-5.4-nano"):
            return "gpt-6-luna"
        case (.mistral, "mistral-medium-2604"), (.mistral, "mistral-medium-3-5"), (.mistral, "mistral-large-2512"):
            return "mistral-large-4-0"
        case (.anthropic, "claude-sonnet-4-6"):
            return "claude-sonnet-5"
        case (.anthropic, "claude-opus-4-7"):
            return "claude-opus-5"
        case (.groq, "qwen/qwen3.6-27b"): return "qwen/qwen3.8-27b"
        case (.groq, "llama-3.1-8b-instant") where text: return "openai/gpt-oss-20b"
        case (.groq, "llama-3.3-70b-versatile") where text: return "openai/gpt-oss-120b"
        case (.togetherai, "MiniMaxAI/MiniMax-M2.7"), (.togetherai, "Qwen/Qwen3.5-397B-A17B"),
             (.togetherai, "zai-org/GLM-5"), (.togetherai, "zai-org/GLM-5.1"),
             (.togetherai, "Qwen/Qwen3.8-2.4T-A95B"), (.togetherai, "google/gemma-4-31B-it"),
             (.togetherai, "MiniMaxAI/MiniMax-M3"):
            return "Qwen/Qwen3.5-9B"
        case (.togetherai, "moonshotai/Kimi-K2.6"): return text ? "Qwen/Qwen3.7-Max" : "moonshotai/Kimi-K3"
        case (.togetherai, "openai/gpt-oss-20b") where text: return "openai/gpt-oss-120b"
        case (.togetherai, "deepseek-ai/DeepSeek-V4-Pro") where text: return "deepseek-ai/DeepSeek-V4-Pro-0813"
        case (.fireworks, "accounts/fireworks/models/qwen3p7-plus"),
             (.fireworks, "accounts/fireworks/models/muse-glimmer-30b"),
             (.fireworks, "accounts/fireworks/models/kimi-k2p6"):
            return "accounts/fireworks/models/kimi-k3"
        case (.fireworks, "accounts/fireworks/models/minimax-m3") where !text:
            return "accounts/fireworks/models/minimax-m3p1-flash"
        case (.deepseek, "deepseek-v4-flash"), (.deepseek, "deepseek-v4-flash-vision-exp"): return "deepseek-flash"
        case (.cerebras, "gemma-4-31b"): return "qwen-3.8-27b"
        default:
            return nil
        }
    }

    func supportedModelOrDefault(_ model: String?) -> String {
        guard let model else { return defaultModel }
        if supportsCustomModelName {
            return model
        }
        let normalized = Self.upgradedLegacyModel(for: self, model: model) ?? Self.normalizedModelID(model)
        return models.contains(normalized) ? normalized : defaultModel
    }

    func supportedTextModelOrDefault(_ model: String?) -> String {
        guard let model else { return defaultTextModel }
        if supportsCustomModelName {
            return model
        }
        let normalized = Self.upgradedLegacyModel(for: self, model: model, text: true) ?? Self.normalizedModelID(model)
        return textModels.contains(normalized) ? normalized : defaultTextModel
    }

    /// Public vision presets for food photos and labels, not dedicated deployments.
    var models: [String] {
        switch self {
        case .appleIntelligence:
            if #available(iOS 27.0, *) {
                ["System Language Model"]
            } else {
                [String]()
            }
        case .gemma4Local: [Gemma4LocalModelManager.modelID]
        case .gemma4E4BLocal: [Gemma4LocalModelManager.Variant.e4b.modelID]
        case .gemini: [
            "gemini-3.5-flash-lite",         // vision, cheapest current stable model (default)
            "gemini-3.8-flash",              // vision, latest Flash model (GA 2026-09-02)
            "gemini-3.6-flash",              // vision, prior stable Flash model
            "gemini-4-hydrogen",
            "gemini-4-neon",
            "gemini-4-argon",
            "gemini-4-carbon",
            "gemini-flash-latest",           // vision, Google's alias for the newest Flash (#358)
            "gemini-flash-lite-latest",      // vision, Google's alias for the newest Flash-Lite (#358)
            "gemini-3.1-pro-preview",
        ]
        case .openai: [
            "gpt-5.4-mini",              // vision, best price/perf
            // GPT-6 presets verified 2026-10-17; Coach uses reasoning_effort=none.
            "gpt-6-luna",                // vision, efficient GPT-6 model
            "gpt-6-sol",                 // vision, capable GPT-6 model
            "gpt-6.1-terra",
            "gpt-6.2-sol",
            "gpt-6.2-astra",
            "gpt-5.6-sol",               // vision, previous Sol model
            "gpt-5.6-terra",             // vision, balanced
            "gpt-5.6-luna",              // vision, lowest-cost 5.6 model
            "gpt-5.5",                   // vision, previous generation
        ]
        case .anthropic: [
            "claude-sonnet-5",             // vision, current Sonnet (default)
            "claude-sonnet-5-5",
            "claude-haiku-5-5",
            "claude-opus-5-5",
            "claude-opus-5",               // vision, latest flagship
            "claude-fable-5",
            "claude-opus-4-8",             // vision, prior flagship
            "claude-haiku-4-5",            // vision, current Haiku, fastest
        ]
        case .xai: [
            "grok-4.6",
            "grok-4.3",
            "grok-4.7",
        ]
        case .openrouter: [
            "openrouter/free",           // availability depends on routing and capacity
            "google/gemini-3.5-flash-lite",
            "google/gemini-3.8-flash",
            "google/gemini-3.7-flash",
            "openai/gpt-5.6-luna",
            "qwen/qwen3.8-27b",
            "openai/gpt-5-mini",
            "anthropic/claude-sonnet-5",
            "qwen/qwen3-vl-8b-instruct",
            "deepseek/deepseek-v4.1-flash",
        ]
        case .togetherai: [
            "Qwen/Qwen3.5-9B",                                    // vision
            "moonshotai/Kimi-K3",                                 // vision
            "moonshotai/Kimi-K3.1-Pro",
        ]
        case .groq: [
            "qwen/qwen3.8-27b",                                   // vision
        ]
        case .huggingface: [
            "google/gemma-4-31B-it",
            "Qwen/Qwen3.8-27B",                                   // vision, tool calling
            "moonshotai/Kimi-K3",                                 // vision, tool calling
            "google/gemma-3-27b-it",                              // vision, open-weight Gemma 3
            "Qwen/Qwen3.5-9B",                                    // vision, open-weight Qwen
        ]
        case .fireworks: [
            "accounts/fireworks/models/kimi-k3",                  // vision, serverless
            "accounts/fireworks/models/minimax-m3p1-flash",
            "accounts/fireworks/models/kimi-k3p1-pro",
        ]
        case .deepinfra: [
            "google/gemma-3-27b-it",
            "Qwen/Qwen3.8-27B",                                   // vision
            "MiniMaxAI/MiniMax-M3",                               // vision
            "google/gemma-4-31B-it",                              // vision
            "google/gemma-4-26B-A4B-it",                          // vision
        ]
        case .mistral: [
            "mistral-small-2603",                                 // vision, best value (Pixtral line retired)
            "mistral-large-4-0",
            "ministral-14b-2512",                                 // vision, small
            "ministral-8b-2512",
        ]
        case .ollama: [
            "qwen3-vl",
            "qwen3.8",
            "gemma4",
            "gemma4:e2b",
            "qwen3-vl:2b",
            "llama3.2-vision",
            "llava",
            "moondream",
        ]
        case .qwen: [
            "qwen3.7-plus",                                       // vision, balanced
            "qwen3.8-flash",
            "qwen3.8-max",                                        // vision, flagship
            "qwen3-vl-plus",                                      // vision-specific
            "qwen4-max",
        ]
        case .deepseek: ["deepseek-flash"]
        case .cerebras: ["qwen-3.8-27b"]
        case .customOpenAI: []  // user types model name in Settings
        }
    }

    /// Text-capable presets. Existing image models remain valid for text, while
    /// providers with broader text catalogs expose those additional choices here.
    var textModels: [String] {
        switch self {
        case .appleIntelligence:
            return ["System Language Model"]
        case .groq:
            return [
                "openai/gpt-oss-20b",
                "openai/gpt-oss-120b",
                "qwen/qwen3.8-27b",
            ]
        case .togetherai:
            return [
                "Qwen/Qwen3.5-9B",
                "Qwen/Qwen3.7-Max",
                "Qwen/Qwen3.6-Plus",
                "openai/gpt-oss-120b",
                "MiniMaxAI/MiniMax-M3.1-Flash",
                "deepseek-ai/DeepSeek-V4-Pro-0813",
            ]
        case .fireworks:
            return models + ["accounts/fireworks/models/minimax-m3"]
        case .deepseek:
            return ["deepseek-flash", "deepseek-v4-pro"]
        case .qwen:
            return ["qwen3.8-flash", "qwen3.7-plus", "qwen3.8-max", "qwen4-max"]
        case .cerebras:
            return ["gpt-oss-120b", "qwen-3.8-27b"]
        case .ollama:
            let preferred = ["qwen3.8", "gemma4", "llama3.2", "qwen3", "mistral-small3.2"]
            return preferred + models.filter { !preferred.contains($0) }
        case .customOpenAI:
            return []
        default:
            return models
        }
    }

    var requiresAPIKey: Bool {
        self != .ollama && self != .appleIntelligence && localGemmaVariant == nil
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
        case .gemma4Local, .gemma4E4BLocal: .liteRTLocal
        case .gemini: .gemini
        case .anthropic: .anthropic
        case .openai, .xai, .openrouter, .togetherai, .groq, .huggingface, .fireworks, .deepinfra, .mistral, .deepseek, .qwen, .cerebras, .ollama, .customOpenAI: .openaiCompatible
        }
    }

    /// True for the models that run on this iPhone (Apple Intelligence and the
    /// downloaded Gemma). Ollama is a server the user runs elsewhere.
    var runsOnDevice: Bool {
        switch apiFormat {
        case .onDevice, .liteRTLocal: true
        case .gemini, .openaiCompatible, .anthropic: false
        }
    }

    /// Short name used in “Paste X API key” hints (not the full picker label).
    var apiKeyBrandName: String {
        switch self {
        case .gemini: "Gemini"
        case .openai: "OpenAI"
        case .anthropic: "Claude"
        case .xai: "Grok"
        case .openrouter: "OpenRouter"
        case .togetherai: "Together AI"
        case .groq: "Groq"
        case .huggingface: "Hugging Face"
        case .fireworks: "Fireworks"
        case .deepinfra: "DeepInfra"
        case .mistral: "Mistral"
        case .deepseek: "DeepSeek"
        case .qwen: "Qwen"
        case .cerebras: "Cerebras"
        case .customOpenAI: "API"
        case .appleIntelligence, .gemma4Local, .gemma4E4BLocal, .ollama: "API"
        }
    }

    var apiKeyPlaceholder: String {
        switch self {
        case .appleIntelligence, .gemma4Local, .gemma4E4BLocal, .ollama: String(localized: "No key needed")
        case .customOpenAI: String(localized: "API key (or anything if endpoint doesn't need one)")
        default: String(localized: "Paste \(apiKeyBrandName) API key")
        }
    }
}

extension AIProvider {
    func needsResponsesForTools(_ model: String) -> Bool {
        self == .openai && (model.hasPrefix("gpt-6.1-") || model.hasPrefix("gpt-6.2-"))
    }
    /// Qwen's hybrid models think by default, which slows replies and is refused
    /// for non-streaming requests, so their requests turn it off.
    var openAICompatibleExtraBody: [String: Any] {
        self == .qwen ? ["enable_thinking": false] : [:]
    }

    func openAICompatibleExtraBody(model: String) -> [String: Any] {
        if self == .deepseek { return ["thinking": ["type": "disabled"]] }
        if (self == .mistral && model == "mistral-large-4-0") || (self == .xai && model == "grok-4.7")
            || needsResponsesForTools(model) {
            return ["reasoning_effort": "low"]
        }
        if self == .openai, model == "gpt-6-luna" { return ["reasoning_effort": "none"] }
        return openAICompatibleExtraBody
    }

    var compatibleRequestByteLimit: Int? {
        switch self {
        case .cerebras: 10 * 1024 * 1024
        case .deepseek: 48 * 1024 * 1024
        default: nil
        }
    }

    func openAICompatibleTokenLimitKey(for model: String) -> String {
        if self == .openai || (self == .customOpenAI && Self.usesOpenAICompletionTokenLimit(model: model)) {
            return "max_completion_tokens"
        }
        return "max_tokens"
    }

    /// GPT-6 Sol/Luna require non-reasoning mode for Chat Completions tool calls.
    func openAICompatibleToolReasoningEffort(for model: String) -> String? {
        guard self == .openai || self == .customOpenAI else { return nil }
        let normalized = Self.normalizedOpenAIModel(model)
        return ["gpt-6-sol", "gpt-6-luna"].contains {
            normalized == $0 || normalized.hasPrefix("\($0)-")
        } ? "none" : nil
    }

    private static func normalizedOpenAIModel(_ model: String) -> String {
        model
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(separator: "/")
            .last
            .map(String.init) ?? model.lowercased()
    }

    private static func usesOpenAICompletionTokenLimit(model: String) -> Bool {
        let normalized = normalizedOpenAIModel(model)

        return normalized.hasPrefix("gpt-5")
            || normalized.hasPrefix("gpt-6")
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
    func requestOptions(compactRetry: Bool, exclude: Bool, structuredModel: String? = nil) -> [String: Any]? {
        var options: [String: Any] = [:]
        if exclude || compactRetry { options["exclude"] = true }
        // Excluding reasoning only hides it from the response; it still uses output tokens.
        if let structuredModel, Self.canDisableAnalysisReasoning(structuredModel), compactRetry || self == .auto {
            options["effort"] = "none"
        }
        else if compactRetry { options["effort"] = "low" }
        else if self != .auto { options["effort"] = rawValue }
        return options.isEmpty ? nil : options
    }

    private static func canDisableAnalysisReasoning(_ model: String) -> Bool {
        model == "openrouter/free" || [
            "deepseek/deepseek-v4.1-flash", "deepseek/deepseek-v4.1-flash-1017", "nvidia/nemotron-3-ultra-550b-a55b"
        ].contains(String(model.prefix(while: { $0 != ":" })))
    }
}

struct AIProviderSettings {
    static var openRouterReasoningEffort: OpenRouterReasoningEffort {
        get { OpenRouterReasoningEffort(rawValue: UserDefaults.standard.string(forKey: "openRouterReasoningEffort") ?? "auto") ?? .auto }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "openRouterReasoningEffort") }
    }

    private static let providerKey = "selectedAIProvider"
    private static let modelKey = "selectedAIModel"
    private static let lastPrimaryCustomModelKey = "lastPrimaryCustomAIModel"
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
    private static let currentModelRegistryMigrationVersion = 3
    private static let maxResponseTokensKey = "aiMaxResponseTokens"
    private static let requestTimeoutSecondsKey = "aiRequestTimeoutSeconds"
    static let geminiTimeoutSecondsKey = "byokGeminiRequestTimeoutSeconds"

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
        if provider == .gemini {
            let seconds = UserDefaults.standard.integer(forKey: geminiTimeoutSecondsKey)
            return (120...600).contains(seconds) ? TimeInterval(seconds) : nil
        }
        return provider.usesConfigurableRequestTimeout ? TimeInterval(requestTimeoutSeconds) : nil
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

    /// Keep a free-form primary Custom model when switching providers in Settings.
    static func switchPrimaryProvider(to provider: AIProvider, defaults: UserDefaults = .standard) -> String {
        if defaults.string(forKey: providerKey) == AIProvider.customOpenAI.rawValue,
           let model = defaults.string(forKey: modelKey) {
            defaults.set(model, forKey: lastPrimaryCustomModelKey)
        }
        let resolved = AIProvider.visionProviders.contains(provider) ? provider : .gemini
        let model = resolved == .customOpenAI
            ? defaults.string(forKey: lastPrimaryCustomModelKey) ?? resolved.defaultModel
            : resolved.defaultModel
        defaults.set(resolved.rawValue, forKey: providerKey)
        defaults.set(model, forKey: modelKey)
        return model
    }

    static var selectedTextProvider: AIProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: textProviderKey),
                  let provider = AIProvider(rawValue: raw),
                  AIProvider.textProviders.contains(provider) else {
                if let raw = UserDefaults.standard.string(forKey: textProviderKey),
                   AIProvider(rawValue: raw)?.localGemmaVariant != nil {
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
            if let saved, saved != resolved {
                UserDefaults.standard.set(resolved, forKey: textModelKey)
            }
            return resolved
        }
        set { UserDefaults.standard.set(newValue, forKey: textModelKey) }
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

        for (savedProvider, savedModel, text) in [
            (providerKey, modelKey, false), (fallbackProviderKey, fallbackModelKey, false),
            (textProviderKey, textModelKey, true), (textFallbackProviderKey, textFallbackModelKey, true)
        ] {
            let provider = storedProvider(in: defaults, key: savedProvider)
                ?? (savedProvider == providerKey ? .gemini : nil)
            if let provider, let upgraded = AIProvider.upgradedLegacyModel(
                for: provider, model: defaults.string(forKey: savedModel), text: text
            ) {
                defaults.set(upgraded, forKey: savedModel)
            }
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
            if let saved, saved != resolved {
                UserDefaults.standard.set(resolved, forKey: modelKey)
            }
            return resolved
        }
        set {
            UserDefaults.standard.set(newValue, forKey: modelKey)
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
                if let raw = UserDefaults.standard.string(forKey: fallbackProviderKey),
                   AIProvider(rawValue: raw)?.localGemmaVariant != nil {
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
            if let saved, saved != resolved {
                UserDefaults.standard.set(resolved, forKey: fallbackModelKey)
            }
            return resolved
        }
        set { UserDefaults.standard.set(newValue, forKey: fallbackModelKey) }
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
                if let raw = UserDefaults.standard.string(forKey: textFallbackProviderKey),
                   AIProvider(rawValue: raw)?.localGemmaVariant != nil {
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
            if let saved, saved != resolved {
                UserDefaults.standard.set(resolved, forKey: textFallbackModelKey)
            }
            return resolved
        }
        set { UserDefaults.standard.set(newValue, forKey: textFallbackModelKey) }
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
    /// The photo cap for the meal capture flow: the smallest limit of the primary
    /// vision provider and the image fallback (#712). Hosted AI keeps its own
    /// request-side cap, so the picker stays at the app's normal limit there.
    static var mealPhotoCaptureLimit: Int {
        if AIModeSettings.isHosted { return AIProvider.defaultMealPhotoLimit }
        let primary = currentConfig(requiresVision: true)
        var limits = [AIProvider.requestImageLimit(provider: primary.provider, model: primary.model)].compactMap { $0 }
        if let fallback = currentImageFallbackConfig(excludingPrimary: primary.provider, model: primary.model),
           let fallbackLimit = AIProvider.requestImageLimit(provider: fallback.provider, model: fallback.model) {
            limits.append(fallbackLimit)
        }
        return mealPhotoLimit(
            primaryLimit: limits.first,
            fallbackLimit: limits.count > 1 ? limits.last : nil
        )
    }

    /** The smallest photo cap of the primary provider and the image fallback. Pure:
     *  testable without the Keychain or UserDefaults-backed settings state. */
    static func mealPhotoLimit(primaryLimit: Int?, fallbackLimit: Int?) -> Int {
        [primaryLimit, fallbackLimit].compactMap { $0 }.min() ?? AIProvider.defaultMealPhotoLimit
    }

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
        let voiceSettings = VoiceAssistantSettings()
        voiceSettings.setQwenAPIKey(nil)
        voiceSettings.qwenWorkspace = ""
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
        UserDefaults.standard.removeObject(forKey: lastPrimaryCustomModelKey)
        UserDefaults.standard.removeObject(forKey: geminiTimeoutSecondsKey)
    }

    static func replaceDeletedLocalGemmaSelections(
        provider: AIProvider = .gemma4Local, defaults: UserDefaults = .standard
    ) {
        if defaults.string(forKey: providerKey) == provider.rawValue {
            defaults.set(AIProvider.gemini.rawValue, forKey: providerKey)
            defaults.set(AIProvider.gemini.defaultModel, forKey: modelKey)
        }
        if defaults.string(forKey: textProviderKey) == provider.rawValue {
            defaults.set(AIProvider.gemini.rawValue, forKey: textProviderKey)
            defaults.set(AIProvider.gemini.defaultTextModel, forKey: textModelKey)
            defaults.set(false, forKey: separateTextProviderEnabledKey)
        }
        if defaults.string(forKey: fallbackProviderKey) == provider.rawValue {
            defaults.set(AIProvider.gemini.rawValue, forKey: fallbackProviderKey)
            defaults.set(AIProvider.gemini.defaultModel, forKey: fallbackModelKey)
            defaults.set(false, forKey: fallbackEnabledKey)
        }
        if defaults.string(forKey: textFallbackProviderKey) == provider.rawValue {
            defaults.set(AIProvider.gemini.rawValue, forKey: textFallbackProviderKey)
            defaults.set(AIProvider.gemini.defaultTextModel, forKey: textFallbackModelKey)
            defaults.set(false, forKey: textFallbackEnabledKey)
        }
    }
}
