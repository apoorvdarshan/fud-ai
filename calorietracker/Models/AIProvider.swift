import Foundation

enum AIProvider: String, CaseIterable, Codable, Identifiable {
    case gemini = "Google Gemini"
    case openai = "OpenAI"
    case anthropic = "Anthropic Claude"
    case xai = "xAI Grok"
    case openrouter = "OpenRouter"
    case togetherai = "Together AI"
    case groq = "Groq"
    case ollama = "Ollama (Local)"

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
        case .ollama: "desktopcomputer"
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
        case .ollama: "http://localhost:11434/v1"
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
            "gemini-2.0-flash",          // vision, stable fallback
        ]
        case .openai: [
            "gpt-4o",                    // vision
            "gpt-4o-mini",               // vision, cheap
            "gpt-4.1",                   // vision
            "gpt-4.1-mini",              // vision
            "gpt-4.1-nano",              // vision, cheapest
        ]
        case .anthropic: [
            "claude-sonnet-4-5-20250929",  // vision, latest Sonnet
            "claude-haiku-4-5-20251001",   // vision (Haiku 3.5 had no vision; Haiku 4.5 does)
            "claude-opus-4-1-20250805",    // vision, highest quality
            "claude-3-5-sonnet-20241022",  // vision, legacy fallback
        ]
        case .xai: [
            "grok-2-vision-1212",        // only Grok model with vision
        ]
        case .openrouter: [
            "google/gemini-2.5-flash",
            "openai/gpt-4o",
            "anthropic/claude-sonnet-4",
            "meta-llama/llama-4-maverick",
        ]
        case .togetherai: [
            "meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8",
            "meta-llama/Llama-Vision-Free",
        ]
        case .groq: [
            "meta-llama/llama-4-scout-17b-16e-instruct",       // vision
            "meta-llama/llama-4-maverick-17b-128e-instruct",   // vision
        ]
        case .ollama: [
            "llama3.2-vision",
            "llava",
            "moondream",
        ]
        }
    }

    var requiresAPIKey: Bool {
        self != .ollama
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
        case .openai, .xai, .openrouter, .togetherai, .groq, .ollama: .openaiCompatible
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
        case .ollama: "No key needed"
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
            // Validate the saved model is still in the current provider's supported list;
            // fall back to default if it was removed (e.g., deprecated text-only model).
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
