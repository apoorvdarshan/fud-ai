import Foundation

enum SpeechProvider: String, CaseIterable, Codable, Identifiable {
    case nativeIOS = "Native iOS (On-Device)"
    case openai = "OpenAI Whisper"
    case groq = "Groq (Whisper)"
    case deepgram = "Deepgram"
    case assemblyai = "AssemblyAI"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .nativeIOS: "apple.logo"
        case .openai: "waveform"
        case .groq: "hare.fill"
        case .deepgram: "waveform.path.ecg"
        case .assemblyai: "text.bubble.fill"
        }
    }

    var requiresAPIKey: Bool { self != .nativeIOS }

    var apiKeyPlaceholder: String {
        switch self {
        case .nativeIOS: "Not needed"
        case .openai: "sk-..."
        case .groq: "gsk_..."
        case .deepgram: "Token your-deepgram-key"
        case .assemblyai: "Your AssemblyAI key"
        }
    }

    /// Default model name for the provider's STT API. Fixed per provider — user doesn't pick.
    var defaultModel: String {
        switch self {
        case .nativeIOS: ""
        case .openai: "whisper-1"
        case .groq: "whisper-large-v3"
        case .deepgram: "nova-3"
        case .assemblyai: "universal"
        }
    }

    var description: String {
        switch self {
        case .nativeIOS: "Apple's on-device speech recognition. Free, works offline on modern iPhones, real-time partial results. Recommended default."
        case .openai: "OpenAI Whisper API. High accuracy, 99+ languages, paid per minute."
        case .groq: "Groq-hosted Whisper Large v3. Very fast inference, has a free tier."
        case .deepgram: "Deepgram Nova. Real-time and batch modes, fast and accurate."
        case .assemblyai: "AssemblyAI Universal model. Strong accuracy, free tier available."
        }
    }
}

// MARK: - Settings Persistence

struct SpeechSettings {
    private static let providerKey = "selectedSpeechProvider"
    private static let apiKeyKeychainPrefix = "speechApiKey_"

    static var selectedProvider: SpeechProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: providerKey),
                  let provider = SpeechProvider(rawValue: raw) else { return .nativeIOS }
            return provider
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: providerKey)
        }
    }

    static func apiKey(for provider: SpeechProvider) -> String? {
        KeychainHelper.load(key: apiKeyKeychainPrefix + provider.rawValue)
    }

    static func setAPIKey(_ key: String?, for provider: SpeechProvider) {
        let keychainKey = apiKeyKeychainPrefix + provider.rawValue
        if let key, !key.isEmpty {
            KeychainHelper.save(key: keychainKey, value: key)
        } else {
            KeychainHelper.delete(key: keychainKey)
        }
    }

    static var currentAPIKey: String? {
        apiKey(for: selectedProvider)
    }

    static func deleteAllData() {
        for provider in SpeechProvider.allCases {
            setAPIKey(nil, for: provider)
        }
        UserDefaults.standard.removeObject(forKey: providerKey)
    }
}
