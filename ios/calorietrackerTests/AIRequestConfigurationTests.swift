import Testing
import UIKit
@testable import calorietracker

// Serialized: several tests save/mutate/restore shared UserDefaults-backed settings.
@Suite(.serialized)
struct AIRequestConfigurationTests {
    @Test func settingsHubKeepsEveryFocusedCategory() {
        #expect(ProfileSettingsCategory.allCases.count == 16)
        #expect(Set(ProfileSettingsCategory.allCases.map(\.rawValue)).count == 16)
        #expect(ProfileSettingsCategory.preferenceCases.count == 11)
        #expect(ProfileSettingsCategory.appInfoCases.count == 5)
        #expect(ProfileSettingsCategory.allCases.compactMap(\.aboutCategory).count == 5)
    }

    @Test func appInfoKeepsEveryFocusedCategory() {
        #expect(AboutSettingsCategory.allCases.count == 5)
        #expect(Set(AboutSettingsCategory.allCases.map(\.rawValue)).count == 5)
    }

    @Test func geminiUsesCurrentModelsAndFallsBackFromRetiredChoices() {
        #expect(AIProvider.gemini.defaultModel == "gemini-3.5-flash-lite")
        #expect(AIProvider.gemini.models.contains("gemini-3.6-flash"))
        #expect(AIProvider.gemini.models.contains("gemini-3.5-flash"))
        #expect(!AIProvider.gemini.models.contains("gemini-2.5-flash"))
        #expect(!AIProvider.gemini.models.contains("gemini-2.5-pro"))
        #expect(AIProvider.gemini.supportedModelOrDefault("gemini-2.5-pro") == "gemini-3.5-flash-lite")
        #expect(AIProvider.upgradedLegacyGeminiModel("gemini-3.1-flash-lite") == "gemini-3.5-flash-lite")
        #expect(AIProvider.upgradedLegacyGeminiModel("gemini-3.1-pro-preview") == nil)
        #expect(AIProvider.upgradedLegacyGeminiModel("gemini-3.5-flash") == nil)
        #expect(AIProvider.upgradedLegacyGeminiModel("gemini-3.6-flash") == nil)
    }

    @Test func localRequestTimeoutDefaultsAndClamps() {
        let original = AIProviderSettings.requestTimeoutSeconds
        defer { AIProviderSettings.requestTimeoutSeconds = original }

        AIProviderSettings.requestTimeoutSeconds = 10
        #expect(AIProviderSettings.requestTimeoutSeconds == 30)
        AIProviderSettings.requestTimeoutSeconds = 900
        #expect(AIProviderSettings.requestTimeoutSeconds == 600)
        #expect(AIProviderSettings.requestTimeout(for: .ollama) == 600)
        #expect(AIProviderSettings.requestTimeout(for: .customOpenAI) == 600)
        #expect(AIProviderSettings.requestTimeout(for: .gemini) == nil)
    }

    @Test func separateTextProviderOnlyRoutesRequestsWithoutImages() {
        let originalProvider = AIProviderSettings.selectedProvider
        let originalModel = AIProviderSettings.selectedModel
        let originalEnabled = AIProviderSettings.separateTextProviderEnabled
        let originalTextProvider = AIProviderSettings.selectedTextProvider
        let originalTextModel = AIProviderSettings.selectedTextModel
        defer {
            AIProviderSettings.selectedProvider = originalProvider
            AIProviderSettings.selectedModel = originalModel
            AIProviderSettings.separateTextProviderEnabled = originalEnabled
            AIProviderSettings.selectedTextProvider = originalTextProvider
            AIProviderSettings.selectedTextModel = originalTextModel
        }

        AIProviderSettings.selectedProvider = .openai
        AIProviderSettings.selectedModel = "gpt-5.4-mini"
        AIProviderSettings.selectedTextProvider = .deepseek
        AIProviderSettings.selectedTextModel = "deepseek-v4-pro"

        AIProviderSettings.separateTextProviderEnabled = false
        #expect(AIProviderSettings.currentConfig(requiresVision: false).provider == .openai)

        AIProviderSettings.separateTextProviderEnabled = true
        let text = AIProviderSettings.currentConfig(requiresVision: false)
        let vision = AIProviderSettings.currentConfig(requiresVision: true)
        #expect(text.provider == .deepseek)
        #expect(text.model == "deepseek-v4-pro")
        #expect(vision.provider == .openai)
        #expect(vision.model == "gpt-5.4-mini")
    }

    @Test func appleIntelligenceIsTextOnlyAndDoesNotRequireCredentials() {
        let originalProvider = AIProviderSettings.selectedProvider
        let originalEnabled = AIProviderSettings.separateTextProviderEnabled
        let originalTextProvider = AIProviderSettings.selectedTextProvider
        let originalTextModel = AIProviderSettings.selectedTextModel
        defer {
            AIProviderSettings.selectedProvider = originalProvider
            AIProviderSettings.separateTextProviderEnabled = originalEnabled
            AIProviderSettings.selectedTextProvider = originalTextProvider
            AIProviderSettings.selectedTextModel = originalTextModel
        }

        AIProviderSettings.selectedProvider = .openai
        AIProviderSettings.separateTextProviderEnabled = true
        AIProviderSettings.selectedTextProvider = .appleIntelligence
        AIProviderSettings.selectedTextModel = "System Language Model"

        let text = AIProviderSettings.currentConfig(requiresVision: false)
        let vision = AIProviderSettings.currentConfig(requiresVision: true)
        #expect(text.provider == .appleIntelligence)
        #expect(text.model == "System Language Model")
        #expect(!text.provider.requiresAPIKey)
        #expect(vision.provider == .openai)
    }

    @Test func imageAndTextFallbacksResolveIndependently() {
        let originalImageEnabled = AIProviderSettings.fallbackEnabled
        let originalImageProvider = AIProviderSettings.selectedFallbackProvider
        let originalImageModel = AIProviderSettings.selectedFallbackModel
        let originalTextEnabled = AIProviderSettings.textFallbackEnabled
        let originalTextProvider = AIProviderSettings.selectedTextFallbackProvider
        let originalTextModel = AIProviderSettings.selectedTextFallbackModel
        defer {
            AIProviderSettings.fallbackEnabled = originalImageEnabled
            AIProviderSettings.selectedFallbackProvider = originalImageProvider
            AIProviderSettings.selectedFallbackModel = originalImageModel
            AIProviderSettings.textFallbackEnabled = originalTextEnabled
            AIProviderSettings.selectedTextFallbackProvider = originalTextProvider
            AIProviderSettings.selectedTextFallbackModel = originalTextModel
        }

        AIProviderSettings.fallbackEnabled = true
        AIProviderSettings.selectedFallbackProvider = .ollama
        AIProviderSettings.selectedFallbackModel = "qwen3-vl"
        AIProviderSettings.textFallbackEnabled = true
        AIProviderSettings.selectedTextFallbackProvider = .appleIntelligence
        AIProviderSettings.selectedTextFallbackModel = "System Language Model"

        let image = AIProviderSettings.currentImageFallbackConfig(
            excludingPrimary: .openai,
            model: "gpt-5.4-mini"
        )
        let text = AIProviderSettings.currentTextFallbackConfig(
            excludingPrimary: .openai,
            model: "gpt-5.4-mini"
        )
        #expect(image?.provider == .ollama)
        #expect(image?.model == "qwen3-vl")
        #expect(text?.provider == .appleIntelligence)
        #expect(text?.model == "System Language Model")
    }

    @Test func aiSettingsInfoExplainsEveryRequestRoute() {
        let expectedPhrases: [(AISettingsInfoTopic, String)] = [
            (.primaryAI, "includes a photo"),
            (.textAI, "requests without photos"),
            (.textFallback, "backs up Primary AI"),
            (.imageFallback, "never used for text-only requests"),
            (.speechToText, "Converts microphone audio into text only"),
            (.speechFallback, "only produces a transcript"),
        ]

        for (topic, phrase) in expectedPhrases {
            #expect(!topic.title.isEmpty)
            #expect(topic.message.localizedCaseInsensitiveContains(phrase))
        }
    }

    @Test func speechFallbackRejectsNativeAsAProvider() {
        let original = SpeechSettings.selectedFallbackProvider
        defer { SpeechSettings.selectedFallbackProvider = original }

        SpeechSettings.selectedFallbackProvider = .nativeIOS
        #expect(SpeechSettings.selectedFallbackProvider == .groq)
        #expect(!SpeechProvider.remoteProviders.contains(.nativeIOS))
    }

    @Test func primaryAIProvidersMapOnlyToTheirFirstPartySpeechProvider() {
        #expect(SpeechProvider.matchingPrimaryAIProvider(.gemini) == .gemini)
        #expect(SpeechProvider.matchingPrimaryAIProvider(.openai) == .openai)
        #expect(SpeechProvider.matchingPrimaryAIProvider(.groq) == .groq)
        #expect(SpeechProvider.matchingPrimaryAIProvider(.mistral) == .mistral)
        #expect(SpeechProvider.matchingPrimaryAIProvider(.anthropic) == nil)
        #expect(SpeechProvider.matchingPrimaryAIProvider(.appleIntelligence) == nil)
    }

    @Test func existingNativeSpeechMigratesOnceAndAvoidsFallbackCollision() throws {
        let suiteName = "AIRequestConfigurationTests.speechMigration.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.set(true, forKey: "hasCompletedOnboarding")
        defaults.set(AIProvider.openai.rawValue, forKey: "selectedAIProvider")
        defaults.set(SpeechProvider.nativeIOS.rawValue, forKey: "selectedSpeechProvider")
        defaults.set(SpeechProvider.openai.rawValue, forKey: "selectedSpeechFallbackProvider")

        SpeechSettings.migrateMatchingPrimaryProviderIfNeeded(defaults: defaults)

        #expect(defaults.string(forKey: "selectedSpeechProvider") == SpeechProvider.openai.rawValue)
        #expect(defaults.string(forKey: "selectedSpeechFallbackProvider") == SpeechProvider.gemini.rawValue)

        // The one-time marker protects a later manual Native selection.
        defaults.set(AIProvider.groq.rawValue, forKey: "selectedAIProvider")
        defaults.set(SpeechProvider.nativeIOS.rawValue, forKey: "selectedSpeechProvider")
        SpeechSettings.migrateMatchingPrimaryProviderIfNeeded(defaults: defaults)
        #expect(defaults.string(forKey: "selectedSpeechProvider") == SpeechProvider.nativeIOS.rawValue)
    }

    @Test func speechMigrationPreservesCloudChoiceAndWaitsForOnboarding() throws {
        let suiteName = "AIRequestConfigurationTests.speechPreserve.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.set(AIProvider.mistral.rawValue, forKey: "selectedAIProvider")
        defaults.set(SpeechProvider.nativeIOS.rawValue, forKey: "selectedSpeechProvider")
        SpeechSettings.migrateMatchingPrimaryProviderIfNeeded(defaults: defaults)
        #expect(defaults.string(forKey: "selectedSpeechProvider") == SpeechProvider.nativeIOS.rawValue)
        #expect(defaults.integer(forKey: "matchingSpeechProviderMigrationVersion") == 0)

        defaults.set(true, forKey: "hasCompletedOnboarding")
        defaults.set(SpeechProvider.deepgram.rawValue, forKey: "selectedSpeechProvider")
        SpeechSettings.migrateMatchingPrimaryProviderIfNeeded(defaults: defaults)
        #expect(defaults.string(forKey: "selectedSpeechProvider") == SpeechProvider.deepgram.rawValue)
        #expect(defaults.integer(forKey: "matchingSpeechProviderMigrationVersion") == 1)
    }

    @Test func newUserSpeechDefaultMatchesPrimaryAndUnsupportedProvidersStayNative() throws {
        let suiteName = "AIRequestConfigurationTests.newSpeechDefault.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        SpeechSettings.setInitialProvider(matching: .mistral, defaults: defaults)
        #expect(defaults.string(forKey: "selectedSpeechProvider") == SpeechProvider.mistral.rawValue)
        #expect(defaults.integer(forKey: "matchingSpeechProviderMigrationVersion") == 1)

        SpeechSettings.setInitialProvider(matching: .openrouter, defaults: defaults)
        #expect(defaults.string(forKey: "selectedSpeechProvider") == SpeechProvider.nativeIOS.rawValue)
    }

    @Test func fallbackBaseURLIsStoredSeparatelyFromPrimary() {
        let provider = AIProvider.ollama
        let defaults = UserDefaults.standard
        let originalShared = AIProviderSettings.customBaseURL(for: provider)
        let originalFallback = AIProviderSettings.fallbackCustomBaseURL(for: provider)
        let originalEnabled = AIProviderSettings.fallbackEnabled
        let originalFallbackProvider = defaults.string(forKey: "selectedFallbackAIProvider")
        let originalFallbackModel = defaults.string(forKey: "selectedFallbackAIModel")
        defer {
            AIProviderSettings.setCustomBaseURL(originalShared, for: provider)
            AIProviderSettings.setFallbackCustomBaseURL(originalFallback, for: provider)
            AIProviderSettings.fallbackEnabled = originalEnabled
            if let originalFallbackProvider {
                defaults.set(originalFallbackProvider, forKey: "selectedFallbackAIProvider")
            } else {
                defaults.removeObject(forKey: "selectedFallbackAIProvider")
            }
            if let originalFallbackModel {
                defaults.set(originalFallbackModel, forKey: "selectedFallbackAIModel")
            } else {
                defaults.removeObject(forKey: "selectedFallbackAIModel")
            }
        }

        AIProviderSettings.setCustomBaseURL("http://primary.local:8080/v1", for: provider)
        AIProviderSettings.setFallbackCustomBaseURL("http://fallback.local:9090/v1", for: provider)
        #expect(AIProviderSettings.customBaseURL(for: provider) == "http://primary.local:8080/v1")
        #expect(AIProviderSettings.fallbackCustomBaseURL(for: provider) == "http://fallback.local:9090/v1")

        // The resolved fallback config must use the fallback-scoped URL, not the primary's.
        AIProviderSettings.fallbackEnabled = true
        AIProviderSettings.selectedFallbackProvider = provider
        AIProviderSettings.selectedFallbackModel = "llava"
        let config = AIProviderSettings.currentImageFallbackConfig(
            excludingPrimary: .gemini,
            model: AIProviderSettings.selectedModel
        )
        #expect(config?.baseURL == "http://fallback.local:9090/v1")
    }

    @Test func fallbackBaseURLMigrationSeedsSharedValueExactlyOnce() {
        let provider = AIProvider.customOpenAI
        let defaults = UserDefaults.standard
        let markerKey = "fallbackBaseURLMigrationVersion"
        let originalShared = AIProviderSettings.customBaseURL(for: provider)
        let originalFallback = AIProviderSettings.fallbackCustomBaseURL(for: provider)
        let originalMarker = defaults.object(forKey: markerKey)
        defer {
            AIProviderSettings.setCustomBaseURL(originalShared, for: provider)
            AIProviderSettings.setFallbackCustomBaseURL(originalFallback, for: provider)
            if let originalMarker {
                defaults.set(originalMarker, forKey: markerKey)
            } else {
                defaults.removeObject(forKey: markerKey)
            }
        }

        defaults.removeObject(forKey: markerKey)
        AIProviderSettings.setFallbackCustomBaseURL(nil, for: provider)
        AIProviderSettings.setCustomBaseURL("http://shared.local:1234/v1", for: provider)

        AIProviderSettings.migrateFallbackBaseURLsIfNeeded()
        #expect(AIProviderSettings.fallbackCustomBaseURL(for: provider) == "http://shared.local:1234/v1")

        // A deliberate clear after migration must survive the next launch: the marker
        // prevents the shared value from being re-seeded.
        AIProviderSettings.setFallbackCustomBaseURL(nil, for: provider)
        AIProviderSettings.migrateFallbackBaseURLsIfNeeded()
        #expect(AIProviderSettings.fallbackCustomBaseURL(for: provider) == nil)
    }

    @Test func foodPhotosAreDownscaledWithoutUpscaling() throws {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let large = UIGraphicsImageRenderer(size: CGSize(width: 3_200, height: 1_200), format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 3_200, height: 1_200))
        }
        let largeData = try GeminiService.encodedJPEGData(for: large)
        let resized = try #require(UIImage(data: largeData))
        #expect(resized.size.width == 1_600)
        #expect(resized.size.height == 600)

        let small = UIGraphicsImageRenderer(size: CGSize(width: 800, height: 400), format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 800, height: 400))
        }
        let smallData = try GeminiService.encodedJPEGData(for: small)
        let preserved = try #require(UIImage(data: smallData))
        #expect(preserved.size.width == 800)
        #expect(preserved.size.height == 400)
    }
}
