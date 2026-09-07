import Foundation
import Testing
@testable import calorietracker

@MainActor
struct AIModelRegistryTests {
    @Test func modelPresetsAndDefaultsMatchTheApprovedRegistryOrder() {
        var registries: [(provider: AIProvider, models: [String])] = [
            (.gemini, [
                "gemini-3.5-flash-lite",
                "gemini-3.8-flash",
                "gemini-3.7-flash",
                "gemini-3.6-flash",
                "gemini-3.5-flash",
                "gemini-3.1-pro-preview",
            ]),
            (.openai, [
                "gpt-5.4-mini",
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.5",
                "gpt-5.4-nano",
                "gpt-4.1",
                "gpt-4.1-mini",
                "gpt-4o-mini",
            ]),
            (.anthropic, [
                "claude-sonnet-5",
                "claude-opus-5",
                "claude-fable-5",
                "claude-opus-4-8",
                "claude-haiku-4-5",
            ]),
            (.xai, [
                "grok-4.6",
                "grok-4.3",
            ]),
            (.openrouter, [
                "openrouter/free",
                "google/gemini-3.5-flash-lite",
                "google/gemini-3.8-flash",
                "google/gemini-3.7-flash",
                "openai/gpt-5.6-luna",
                "qwen/qwen3.8-27b",
                "openai/gpt-5-mini",
                "anthropic/claude-sonnet-5",
                "qwen/qwen3-vl-8b-instruct",
            ]),
            (.togetherai, [
                "Qwen/Qwen3.5-9B",
                "moonshotai/Kimi-K3",
                "Qwen/Qwen3.8-2.4T-A95B",
                "google/gemma-4-31B-it",
                "MiniMaxAI/MiniMax-M3",
            ]),
            (.groq, [
                "qwen/qwen3.6-27b",
            ]),
            (.huggingface, [
                "google/gemma-4-31B-it",
                "Qwen/Qwen3.8-27B",
                "moonshotai/Kimi-K3",
                "google/gemma-3-27b-it",
                "Qwen/Qwen3.5-9B",
            ]),
            (.fireworks, [
                "accounts/fireworks/models/qwen3p7-plus",
                "accounts/fireworks/models/kimi-k3",
                "accounts/fireworks/models/muse-glimmer-30b",
                "accounts/fireworks/models/minimax-m3",
                "accounts/fireworks/models/kimi-k2p6",
            ]),
            (.deepinfra, [
                "google/gemma-3-27b-it",
                "Qwen/Qwen3.8-27B",
                "MiniMaxAI/MiniMax-M3",
                "google/gemma-4-31B-it",
                "google/gemma-4-26B-A4B-it",
            ]),
            (.mistral, [
                "mistral-small-2603",
                "mistral-medium-3-5",
                "mistral-large-2512",
                "ministral-14b-2512",
            ]),
            (.ollama, [
                "qwen3-vl",
                "qwen3.8",
                "gemma4",
                "llama3.2-vision",
                "llava",
                "moondream",
            ]),
            (.customOpenAI, []),
        ]

        if Gemma4LocalModelManager.isCurrentDeviceSelectable {
            registries.insert((.gemma4Local, [Gemma4LocalModelManager.modelID]), at: 0)
        }

        #expect(registries.map(\.provider) == AIProvider.visionProviders)
        for registry in registries {
            #expect(registry.provider.models == registry.models)
            #expect(registry.provider.defaultModel == (registry.models.first ?? ""))
        }
    }

    @Test func textProvidersExposeCurrentTextOnlyChoicesWithoutEnteringVisionRegistry() {
        #expect(AIProvider.textProviders == AIProvider.allCases.filter(\.isAvailableOnCurrentDevice))
        #expect(!AIProvider.visionProviders.contains(.appleIntelligence))
        #expect(!AIProvider.visionProviders.contains(.deepseek))
        #expect(!AIProvider.visionProviders.contains(.cerebras))
        #expect(AIProvider.appleIntelligence.textModels == ["System Language Model"])
        #expect(!AIProvider.appleIntelligence.requiresAPIKey)
        #expect(AIProvider.gemma4Local.models == [Gemma4LocalModelManager.modelID])
        #expect(!AIProvider.gemma4Local.requiresAPIKey)
        #expect(AIProvider.deepseek.textModels == ["deepseek-v4-flash", "deepseek-v4-pro"])
        #expect(AIProvider.cerebras.textModels == ["gpt-oss-120b", "gemma-4-31b"])
        #expect(AIProvider.groq.defaultTextModel == "openai/gpt-oss-20b")
        #expect(AIProvider.groq.textModels.contains("openai/gpt-oss-120b"))
        #expect(AIProvider.togetherai.textModels.contains("deepseek-ai/DeepSeek-V4-Pro"))
        #expect(AIProvider.deepseek.supportedTextModelOrDefault("retired-model") == "deepseek-v4-flash")
    }

    @Test func removedPresetsHaveProviderScopedReplacements() {
        #expect(AIProvider.upgradedLegacyModel(for: .gemini, model: "gemini-3.1-flash-lite") == "gemini-3.5-flash-lite")
        #expect(AIProvider.upgradedLegacyModel(for: .openrouter, model: "google/gemini-3.1-flash-lite") == "google/gemini-3.5-flash-lite")
        #expect(AIProvider.upgradedLegacyModel(for: .huggingface, model: "Qwen/Qwen2.5-VL-72B-Instruct") == "Qwen/Qwen3.8-27B")
        #expect(AIProvider.upgradedLegacyModel(for: .mistral, model: "mistral-medium-2604") == "mistral-medium-3-5")
        #expect(AIProvider.upgradedLegacyModel(for: .anthropic, model: "claude-sonnet-4-6") == "claude-sonnet-5")
        #expect(AIProvider.upgradedLegacyModel(for: .anthropic, model: "claude-opus-4-7") == "claude-opus-5")

        #expect(AIProvider.upgradedLegacyModel(for: .openrouter, model: "gemini-3.1-flash-lite") == nil)
        #expect(AIProvider.upgradedLegacyModel(for: .openai, model: "gpt-5.4-mini") == nil)
        #expect(AIProvider.upgradedLegacyModel(for: .gemini, model: nil) == nil)
    }

    @Test func modelRegistryMigrationUpgradesPrimaryAndFallbackExactlyOnce() throws {
        let migrations: [(provider: AIProvider, old: String, new: String)] = [
            (.gemini, "gemini-3.1-flash-lite", "gemini-3.5-flash-lite"),
            (.openrouter, "google/gemini-3.1-flash-lite", "google/gemini-3.5-flash-lite"),
            (.huggingface, "Qwen/Qwen2.5-VL-72B-Instruct", "Qwen/Qwen3.8-27B"),
            (.mistral, "mistral-medium-2604", "mistral-medium-3-5"),
            (.anthropic, "claude-sonnet-4-6", "claude-sonnet-5"),
            (.anthropic, "claude-opus-4-7", "claude-opus-5"),
        ]

        for migration in migrations {
            let suiteName = "AIModelRegistryTests.migration.\(migration.provider.id).\(UUID().uuidString)"
            let defaults = try #require(UserDefaults(suiteName: suiteName))
            defer { defaults.removePersistentDomain(forName: suiteName) }

            defaults.set(migration.provider.rawValue, forKey: "selectedAIProvider")
            defaults.set(migration.old, forKey: "selectedAIModel")
            defaults.set(migration.provider.rawValue, forKey: "selectedFallbackAIProvider")
            defaults.set(migration.old, forKey: "selectedFallbackAIModel")
            defaults.set("preserved", forKey: "unrelatedUserData")

            AIProviderSettings.migrateModelRegistryIfNeeded(defaults: defaults)

            #expect(defaults.string(forKey: "selectedAIModel") == migration.new)
            #expect(defaults.string(forKey: "selectedFallbackAIModel") == migration.new)
            #expect(defaults.string(forKey: "unrelatedUserData") == "preserved")

            defaults.set(migration.old, forKey: "selectedAIModel")
            defaults.set(migration.old, forKey: "selectedFallbackAIModel")
            AIProviderSettings.migrateModelRegistryIfNeeded(defaults: defaults)

            #expect(defaults.string(forKey: "selectedAIModel") == migration.old)
            #expect(defaults.string(forKey: "selectedFallbackAIModel") == migration.old)
        }
    }

    @Test func freeFormProvidersPreserveUserSuppliedModels() {
        let customModel = "company/private-vision-model-v7"

        #expect(AIProvider.customOpenAI.supportedModelOrDefault(customModel) == customModel)
        #expect(AIProvider.openrouter.supportedModelOrDefault(customModel) == customModel)
        #expect(AIProvider.huggingface.supportedModelOrDefault(customModel) == customModel)
        #expect(AIProvider.openai.supportedModelOrDefault(customModel) == AIProvider.openai.defaultModel)
    }

    @Test func modelRegistryMigrationPreservesCustomPrimaryAndFallbackModels() throws {
        let suiteName = "AIModelRegistryTests.custom.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let primaryModel = "company/private-primary-v7"
        let fallbackModel = "company/private-fallback-v7"

        defaults.set(AIProvider.customOpenAI.rawValue, forKey: "selectedAIProvider")
        defaults.set(primaryModel, forKey: "selectedAIModel")
        defaults.set(AIProvider.customOpenAI.rawValue, forKey: "selectedFallbackAIProvider")
        defaults.set(fallbackModel, forKey: "selectedFallbackAIModel")

        AIProviderSettings.migrateModelRegistryIfNeeded(defaults: defaults)

        #expect(defaults.string(forKey: "selectedAIModel") == primaryModel)
        #expect(defaults.string(forKey: "selectedFallbackAIModel") == fallbackModel)
    }

    @Test func appLaunchMigrationPreservesSupportedModelsAndDoesNotGuessFallbackProvider() throws {
        let suiteName = "AIModelRegistryTests.launch.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.set(AIProvider.gemini.rawValue, forKey: "selectedAIProvider")
        defaults.set("gemini-3.5-flash", forKey: "selectedAIModel")
        defaults.set("google/gemini-3.1-flash-lite", forKey: "selectedFallbackAIModel")

        AIProviderSettings.migrateLegacyGeminiModelsIfNeeded(defaults: defaults)

        #expect(defaults.string(forKey: "selectedAIModel") == "gemini-3.5-flash")
        #expect(defaults.string(forKey: "selectedFallbackAIModel") == "google/gemini-3.1-flash-lite")
    }

    @Test func appLaunchMigrationRunsTheExactPrimaryAndFallbackRegistryUpgrades() throws {
        let suiteName = "AIModelRegistryTests.launchExact.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.set(AIProvider.gemini.rawValue, forKey: "selectedAIProvider")
        defaults.set("gemini-3.1-flash-lite", forKey: "selectedAIModel")
        defaults.set(AIProvider.openrouter.rawValue, forKey: "selectedFallbackAIProvider")
        defaults.set("google/gemini-3.1-flash-lite", forKey: "selectedFallbackAIModel")

        AIProviderSettings.migrateLegacyGeminiModelsIfNeeded(defaults: defaults)

        #expect(defaults.string(forKey: "selectedAIModel") == "gemini-3.5-flash-lite")
        #expect(defaults.string(forKey: "selectedFallbackAIModel") == "google/gemini-3.5-flash-lite")
    }

    @Test func openAI56ModelsUseTheCompletionTokenLimitKey() {
        for model in ["gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"] {
            #expect(AIProvider.openai.openAICompatibleTokenLimitKey(for: model) == "max_completion_tokens")
            #expect(AIProvider.customOpenAI.openAICompatibleTokenLimitKey(for: "openai/\(model)") == "max_completion_tokens")
        }

        #expect(AIProvider.openrouter.openAICompatibleTokenLimitKey(for: "openai/gpt-5.6-luna") == "max_tokens")
        #expect(AIProvider.customOpenAI.openAICompatibleTokenLimitKey(for: "company/private-model") == "max_tokens")
    }

    @Test func speechProviderDefaultsMatchTheApprovedModels() {
        let defaults: [(provider: SpeechProvider, model: String)] = [
            (.nativeIOS, ""),
            (.whisperBase, "base"),
            (.gemini, "gemini-3.5-transcribe"),
            (.openai, "gpt-transcribe"),
            (.groq, "whisper-large-v3"),
            (.mistral, "voxtral-mini-2602"),
            (.deepgram, "nova-3"),
            (.assemblyai, "universal-3-pro"),
        ]

        #expect(defaults.map(\.provider) == SpeechProvider.allCases)
        for entry in defaults {
            #expect(entry.provider.defaultModel == entry.model)
        }
    }

    @Test func geminiSpeechInteractionUsesTheDedicatedTranscriptionSchema() throws {
        let body = SpeechService.geminiInteractionBody(
            model: SpeechProvider.gemini.defaultModel,
            fileURI: "https://generativelanguage.googleapis.com/v1beta/files/audio",
            mimeType: "audio/m4a",
            languageCode: "hi"
        )

        #expect(body["model"] as? String == "gemini-3.5-transcribe")
        let input = try #require(body["input"] as? [[String: Any]])
        #expect(input.first?["type"] as? String == "audio")
        #expect(input.first?["mime_type"] as? String == "audio/m4a")

        let generationConfig = try #require(body["generation_config"] as? [String: Any])
        let transcriptionConfig = try #require(generationConfig["transcription_config"] as? [String: Any])
        #expect(transcriptionConfig["language_codes"] as? [String] == ["hi"])
        #expect((transcriptionConfig["mode"] as? [String: String])?["type"] == "smart")
    }

    @Test func geminiSpeechResponseParserSupportsCurrentInteractionShapes() throws {
        let outputsData = try JSONSerialization.data(withJSONObject: [
            "outputs": [["type": "text", "text": "  two eggs and toast  "]]
        ])
        #expect(SpeechService.geminiTranscript(from: outputsData) == "two eggs and toast")

        let stepsData = try JSONSerialization.data(withJSONObject: [
            "steps": [["content": [["type": "text", "text": "Greek yogurt"]]]]
        ])
        #expect(SpeechService.geminiTranscript(from: stepsData) == "Greek yogurt")
    }

    @Test func assemblyAIAutomaticLanguagePayloadUsesTheApprovedFallbackModels() throws {
        let body = SpeechService.assemblyAITranscriptBody(
            audioURL: "https://upload.example/audio",
            speechModels: [SpeechProvider.assemblyai.defaultModel, "universal-2"],
            languageCode: nil
        )

        #expect(body["audio_url"] as? String == "https://upload.example/audio")
        #expect(body["speech_models"] as? [String] == ["universal-3-pro", "universal-2"])
        #expect(body["language_detection"] as? Bool == true)
        #expect(body["language_code"] == nil)
    }

    @Test func assemblyAIExplicitLanguagePayloadDisablesAutomaticDetection() {
        let body = SpeechService.assemblyAITranscriptBody(
            audioURL: "https://upload.example/audio",
            speechModels: [SpeechProvider.assemblyai.defaultModel, "universal-2"],
            languageCode: "hi"
        )

        #expect(body["speech_models"] as? [String] == ["universal-3-pro", "universal-2"])
        #expect(body["language_code"] as? String == "hi")
        #expect(body["language_detection"] == nil)
    }

    @Test func geminiFunctionResponsesEchoCallIdentifiers() throws {
        let result = ["status": "logged"]
        let part = try #require(
            ChatService.geminiFunctionResponsePart(
                for: ["name": "log_food", "id": "call_123"],
                result: result
            )
        )
        let response = try #require(part["functionResponse"] as? [String: Any])
        let content = try #require((response["response"] as? [String: Any])?["content"] as? [String: String])

        #expect(response["name"] as? String == "log_food")
        #expect(response["id"] as? String == "call_123")
        #expect(content == result)

        let legacyPart = try #require(
            ChatService.geminiFunctionResponsePart(
                for: ["name": "log_food"],
                result: result
            )
        )
        let legacyResponse = try #require(legacyPart["functionResponse"] as? [String: Any])
        #expect(legacyResponse["id"] == nil)
        #expect(ChatService.geminiFunctionResponsePart(for: ["id": "call_123"], result: result) == nil)
    }
}
