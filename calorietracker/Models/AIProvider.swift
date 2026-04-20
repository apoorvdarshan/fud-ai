import Foundation

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

    /// Only models that are currently in service AND accept image input + return structured text.
    /// Text-only and deprecated/preview models are excluded since this app needs vision for food photos.
    var models: [String] {
        switch self {
        case .gemini: [
            "gemini-2.5-flash",          // vision, fastest
            "gemini-2.5-pro",            // vision, highest quality
            "gemini-2.0-flash",          // vision, legacy fallback
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
            "claude-sonnet-4-5-20250929",  // vision, current Sonnet
            "claude-haiku-4-5-20251001",   // vision, current Haiku (Haiku 3.5 had no vision)
            "claude-opus-4-1-20250805",    // vision, highest quality
        ]
        case .xai: [
            "grok-4",                    // vision, current flagship
            "grok-2-vision-latest",      // vision, rolling alias for legacy compat
        ]
        case .openrouter: [
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

// MARK: - Settings Persistence

struct AIProviderSettings {
    private static let providerKey = "selectedAIProvider"
    private static let modelKey = "selectedAIModel"
    private static let apiKeyKeychainPrefix = "apikey_"
    private static let baseURLKey = "customBaseURL_"

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
            // Providers that allow free-form model names trust whatever the user saved.
            if selectedProvider.supportsCustomModelName {
                return saved ?? selectedProvider.defaultModel
            }
            // Otherwise validate against the provider's supported list and fall back to default
            // if the saved one was removed (e.g., a deprecated model we no longer expose).
            if let saved, selectedProvider.models.contains(saved) {
                return saved
            }
            return selectedProvider.defaultModel
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

    static var currentAPIKey: String? {
        apiKey(for: selectedProvider)
    }

    static var currentBaseURL: String {
        customBaseURL(for: selectedProvider) ?? selectedProvider.baseURL
    }

    static func deleteAllData() {
        for provider in AIProvider.allCases {
            setAPIKey(nil, for: provider)
            setCustomBaseURL(nil, for: provider)
        }
        UserDefaults.standard.removeObject(forKey: providerKey)
        UserDefaults.standard.removeObject(forKey: modelKey)
    }
}
