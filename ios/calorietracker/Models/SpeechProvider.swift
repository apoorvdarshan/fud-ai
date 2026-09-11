import Foundation

enum SpeechProvider: String, CaseIterable, Codable, Identifiable {
    case nativeIOS = "Native iOS (On-Device)"
    case whisperBase = "Whisper Base (On-Device)"
    case gemini = "Gemini Audio"
    case openai = "OpenAI Whisper"
    case groq = "Groq (Whisper)"
    case mistral = "Mistral Voxtral"
    case deepgram = "Deepgram"
    case assemblyai = "AssemblyAI"

    var id: String { rawValue }

    static var remoteProviders: [SpeechProvider] {
        allCases.filter(\.requiresAPIKey)
    }

    static var availableProviders: [SpeechProvider] {
        allCases.filter { $0 != .whisperBase || WhisperBaseModelManager.isCurrentDeviceSelectable }
    }

    static var availableBatchProviders: [SpeechProvider] {
        availableProviders.filter { $0 != .nativeIOS }
    }

    /// The STT provider that shares credentials and account ownership with a
    /// supported Primary AI provider. Providers without a first-party STT route
    /// intentionally return nil and keep Native speech as the default.
    static func matchingPrimaryAIProvider(_ provider: AIProvider) -> SpeechProvider? {
        switch provider {
        case .gemini: .gemini
        case .openai: .openai
        case .groq: .groq
        case .mistral: .mistral
        default: nil
        }
    }

    var matchingAIProvider: AIProvider? {
        switch self {
        case .gemini: .gemini
        case .openai: .openai
        case .groq: .groq
        case .mistral: .mistral
        case .nativeIOS, .whisperBase, .deepgram, .assemblyai: nil
        }
    }

    /// User-facing provider name. Raw values stay stable because they are also
    /// used for persisted settings and Keychain lookup keys.
    var displayName: String {
        switch self {
        case .whisperBase:
            LocalModelStrings.text(
                "whisper.providerName",
                defaultValue: "Whisper Base (On-Device)"
            )
        case .openai: "OpenAI GPT-Transcribe"
        default: rawValue
        }
    }

    var logoAssetName: String? {
        switch self {
        case .nativeIOS, .whisperBase: nil
        case .gemini: "provider_gemini"
        case .openai: "provider_openai"
        case .groq: "provider_groq"
        case .mistral: "provider_mistral"
        case .deepgram: "provider_deepgram"
        case .assemblyai: "provider_assemblyai"
        }
    }

    var fallbackSystemImage: String {
        self == .whisperBase ? "waveform.badge.mic" : "apple.logo"
    }

    var requiresAPIKey: Bool {
        self != .nativeIOS && self != .whisperBase
    }

    var apiKeyPlaceholder: String {
        switch self {
        case .nativeIOS, .whisperBase: "Not needed"
        case .gemini: "AIza..."
        case .openai: "sk-..."
        case .groq: "gsk_..."
        case .mistral: "Your Mistral API key"
        case .deepgram: "Token your-deepgram-key"
        case .assemblyai: "Your AssemblyAI key"
        }
    }

    /// Default model name for the provider's STT API. Fixed per provider — user doesn't pick.
    var defaultModel: String {
        switch self {
        case .nativeIOS: ""
        case .whisperBase: "base"
        case .gemini: "gemini-3.5-transcribe"
        case .openai: "gpt-transcribe"
        case .groq: "whisper-large-v3"
        case .mistral: "voxtral-mini-2602"
        case .deepgram: "nova-3"
        case .assemblyai: "universal-3-pro"
        }
    }

    var description: String {
        switch self {
        case .nativeIOS:
            LocalizedDisplayText.text(
                "Apple's on-device speech recognition. Free, works offline on modern iPhones, real-time partial results. Recommended default.",
                polish: "Rozpoznawanie mowy Apple na urządzeniu. Bezpłatne, działa offline na nowoczesnych iPhone'ach, pokazuje częściowe wyniki w czasie rzeczywistym. Zalecane domyślnie."
            )
        case .whisperBase:
            LocalModelStrings.text(
                "whisper.providerDescription",
                defaultValue: "Multilingual Whisper Base running entirely on this iPhone. Download once for private offline transcription."
            )
        case .gemini:
            LocalizedDisplayText.text(
                "Gemini 3.5 Transcribe for accurate batch transcription with automatic language detection.",
                polish: "Gemini 3.5 Transcribe do dokładnej transkrypcji wsadowej z automatycznym wykrywaniem języka."
            )
        case .openai:
            LocalizedDisplayText.text(
                "OpenAI GPT-Transcribe, the current high-accuracy model for recorded audio.",
                polish: "OpenAI GPT-Transcribe, aktualny model o wysokiej dokładności do nagranego dźwięku."
            )
        case .groq:
            LocalizedDisplayText.text(
                "Groq-hosted Whisper Large v3. Very fast inference, has a free tier.",
                polish: "Whisper Large v3 hostowany przez Groq. Bardzo szybkie wnioskowanie, dostępny darmowy limit."
            )
        case .mistral:
            LocalizedDisplayText.text(
                "Voxtral Mini Transcribe 2 for accurate multilingual batch transcription.",
                polish: "Voxtral Mini Transcribe 2 do dokładnej wielojęzycznej transkrypcji wsadowej."
            )
        case .deepgram:
            LocalizedDisplayText.text(
                "Deepgram Nova. Real-time and batch modes, fast and accurate.",
                polish: "Deepgram Nova. Tryb czasu rzeczywistego i wsadowy, szybki i dokładny."
            )
        case .assemblyai:
            LocalizedDisplayText.text(
                "AssemblyAI Universal-3 Pro with Universal-2 fallback for broader language support.",
                polish: "AssemblyAI Universal-3 Pro z modelem Universal-2 jako rezerwowym dla szerszej obsługi języków."
            )
        }
    }
}

enum SpeechLanguage: String, CaseIterable, Codable, Identifiable {
    case automatic
    case device
    case english
    case german
    case spanish
    case french
    case italian
    case portuguese
    case dutch
    case hindi
    case japanese
    case chinese
    case korean
    case czech

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .automatic: LocalizedDisplayText.text("Provider Auto", polish: "Auto dostawcy")
        case .device: LocalizedDisplayText.text("Use iPhone Language", polish: "Użyj języka iPhone'a")
        case .english: LocalizedDisplayText.text("English", polish: "Angielski")
        case .german: LocalizedDisplayText.text("German", polish: "Niemiecki")
        case .spanish: LocalizedDisplayText.text("Spanish", polish: "Hiszpański")
        case .french: LocalizedDisplayText.text("French", polish: "Francuski")
        case .italian: LocalizedDisplayText.text("Italian", polish: "Włoski")
        case .portuguese: LocalizedDisplayText.text("Portuguese", polish: "Portugalski")
        case .dutch: LocalizedDisplayText.text("Dutch", polish: "Niderlandzki")
        case .hindi: LocalizedDisplayText.text("Hindi", polish: "Hindi")
        case .japanese: LocalizedDisplayText.text("Japanese", polish: "Japoński")
        case .chinese: LocalizedDisplayText.text("Chinese", polish: "Chiński")
        case .korean: LocalizedDisplayText.text("Korean", polish: "Koreański")
        case .czech: LocalizedDisplayText.text("Czech", polish: "Czeski")
        }
    }

    var apiLanguageCode: String? {
        switch self {
        case .automatic:
            nil
        case .device:
            Locale.autoupdatingCurrent.language.languageCode?.identifier.lowercased()
        case .english:
            "en"
        case .german:
            "de"
        case .spanish:
            "es"
        case .french:
            "fr"
        case .italian:
            "it"
        case .portuguese:
            "pt"
        case .dutch:
            "nl"
        case .hindi:
            "hi"
        case .japanese:
            "ja"
        case .chinese:
            "zh"
        case .korean:
            "ko"
        case .czech:
            "cs"
        }
    }

    var preferredNativeLocale: Locale {
        switch self {
        case .automatic, .device:
            Locale.autoupdatingCurrent
        case .english:
            Locale(identifier: "en-US")
        case .german:
            Locale(identifier: "de-DE")
        case .spanish:
            Locale(identifier: "es-ES")
        case .french:
            Locale(identifier: "fr-FR")
        case .italian:
            Locale(identifier: "it-IT")
        case .portuguese:
            Locale(identifier: "pt-BR")
        case .dutch:
            Locale(identifier: "nl-NL")
        case .hindi:
            Locale(identifier: "hi-IN")
        case .japanese:
            Locale(identifier: "ja-JP")
        case .chinese:
            Locale(identifier: "zh-Hans")
        case .korean:
            Locale(identifier: "ko-KR")
        case .czech:
            Locale(identifier: "cs-CZ")
        }
    }
}

// MARK: - Settings Persistence

struct SpeechSettings {
    private static let providerKey = "selectedSpeechProvider"
    private static let fallbackEnabledKey = "speechFallbackEnabled"
    private static let fallbackProviderKey = "selectedSpeechFallbackProvider"
    private static let languageKey = "selectedSpeechLanguage"
    private static let languageKeyPrefix = "selectedSpeechLanguage_"
    private static let apiKeyKeychainPrefix = "speechApiKey_"
    private static let primaryAIProviderKey = "selectedAIProvider"
    private static let onboardingCompletedKey = "hasCompletedOnboarding"
    private static let matchingProviderMigrationVersionKey = "matchingSpeechProviderMigrationVersion"
    private static let currentMatchingProviderMigrationVersion = 1

    /// Existing v7 users receive this once: Native speech is replaced with the
    /// first-party STT provider matching their Primary AI provider. An explicit
    /// cloud speech selection is preserved, and incomplete onboarding is left
    /// unmarked so its final step can apply the new-user default instead.
    static func migrateMatchingPrimaryProviderIfNeeded(defaults: UserDefaults = .standard) {
        guard defaults.integer(forKey: matchingProviderMigrationVersionKey)
                < currentMatchingProviderMigrationVersion,
              defaults.bool(forKey: onboardingCompletedKey) else {
            return
        }

        let currentSpeech = defaults.string(forKey: providerKey)
            .flatMap(SpeechProvider.init(rawValue:)) ?? .nativeIOS
        if currentSpeech == .nativeIOS {
            let primaryAI = defaults.string(forKey: primaryAIProviderKey)
                .flatMap(AIProvider.init(rawValue:)) ?? .gemini
            if let matched = SpeechProvider.matchingPrimaryAIProvider(primaryAI) {
                defaults.set(matched.rawValue, forKey: providerKey)
                resolveFallbackCollision(with: matched, defaults: defaults)
            }
        }

        defaults.set(currentMatchingProviderMigrationVersion, forKey: matchingProviderMigrationVersionKey)
    }

    /// Called when onboarding completes. This establishes the matching default
    /// and marks the migration so later Primary AI changes never force STT again.
    static func setInitialProvider(
        matching primaryAI: AIProvider,
        defaults: UserDefaults = .standard
    ) {
        let provider = SpeechProvider.matchingPrimaryAIProvider(primaryAI) ?? .nativeIOS
        defaults.set(provider.rawValue, forKey: providerKey)
        resolveFallbackCollision(with: provider, defaults: defaults)
        defaults.set(currentMatchingProviderMigrationVersion, forKey: matchingProviderMigrationVersionKey)
    }

    private static func resolveFallbackCollision(
        with provider: SpeechProvider,
        defaults: UserDefaults
    ) {
        let fallback = defaults.string(forKey: fallbackProviderKey)
            .flatMap(SpeechProvider.init(rawValue:)) ?? .groq
        guard fallback == provider,
              let alternate = SpeechProvider.remoteProviders.first(where: { $0 != provider }) else {
            return
        }
        defaults.set(alternate.rawValue, forKey: fallbackProviderKey)
    }

    static var selectedProvider: SpeechProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: providerKey),
                  let provider = SpeechProvider(rawValue: raw),
                  SpeechProvider.availableProviders.contains(provider) else {
                UserDefaults.standard.set(SpeechProvider.nativeIOS.rawValue, forKey: providerKey)
                return .nativeIOS
            }
            return provider
        }
        set {
            let resolved = SpeechProvider.availableProviders.contains(newValue) ? newValue : .nativeIOS
            UserDefaults.standard.set(resolved.rawValue, forKey: providerKey)
        }
    }

    static var fallbackEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: fallbackEnabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: fallbackEnabledKey) }
    }

    static var selectedFallbackProvider: SpeechProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: fallbackProviderKey),
                  let provider = SpeechProvider(rawValue: raw),
                  SpeechProvider.availableBatchProviders.contains(provider) else {
                if UserDefaults.standard.string(forKey: fallbackProviderKey) == SpeechProvider.whisperBase.rawValue {
                    UserDefaults.standard.set(false, forKey: fallbackEnabledKey)
                }
                UserDefaults.standard.set(SpeechProvider.groq.rawValue, forKey: fallbackProviderKey)
                return .groq
            }
            return provider
        }
        set {
            let resolved = SpeechProvider.availableBatchProviders.contains(newValue) ? newValue : .groq
            UserDefaults.standard.set(resolved.rawValue, forKey: fallbackProviderKey)
        }
    }

    static var selectedLanguage: SpeechLanguage {
        get {
            selectedLanguage(for: selectedProvider)
        }
        set {
            setLanguage(newValue, for: selectedProvider)
        }
    }

    static func selectedLanguage(for provider: SpeechProvider) -> SpeechLanguage {
        let key = languageKeyPrefix + provider.rawValue
        guard let raw = UserDefaults.standard.string(forKey: key),
              let language = SpeechLanguage(rawValue: raw) else {
            return defaultLanguage(for: provider)
        }
        return language
    }

    static func setLanguage(_ language: SpeechLanguage, for provider: SpeechProvider) {
        UserDefaults.standard.set(language.rawValue, forKey: languageKeyPrefix + provider.rawValue)
    }

    static func defaultLanguage(for provider: SpeechProvider) -> SpeechLanguage {
        switch provider {
        case .nativeIOS:
            .device
        case .whisperBase, .gemini, .openai, .groq, .mistral:
            .automatic
        case .deepgram:
            .device
        case .assemblyai:
            .automatic
        }
    }

    static func apiKey(for provider: SpeechProvider) -> String? {
        if let dedicatedKey = KeychainHelper.load(key: apiKeyKeychainPrefix + provider.rawValue),
           !dedicatedKey.isEmpty {
            return dedicatedKey
        }
        guard let aiProvider = provider.matchingAIProvider else { return nil }
        return AIProviderSettings.apiKey(for: aiProvider)
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
        UserDefaults.standard.removeObject(forKey: fallbackEnabledKey)
        UserDefaults.standard.removeObject(forKey: fallbackProviderKey)
        UserDefaults.standard.removeObject(forKey: matchingProviderMigrationVersionKey)
        UserDefaults.standard.removeObject(forKey: languageKey)
        for provider in SpeechProvider.allCases {
            UserDefaults.standard.removeObject(forKey: languageKeyPrefix + provider.rawValue)
        }
    }

    static func replaceDeletedWhisperSelections(defaults: UserDefaults = .standard) {
        if defaults.string(forKey: providerKey) == SpeechProvider.whisperBase.rawValue {
            defaults.set(SpeechProvider.nativeIOS.rawValue, forKey: providerKey)
        }
        if defaults.string(forKey: fallbackProviderKey) == SpeechProvider.whisperBase.rawValue {
            defaults.set(SpeechProvider.groq.rawValue, forKey: fallbackProviderKey)
            defaults.set(false, forKey: fallbackEnabledKey)
        }
    }
}
