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
                "gemini-3.6-flash",
                "gemini-4-hydrogen", "gemini-4-neon", "gemini-4-argon", "gemini-4-carbon",
                "gemini-flash-latest",
                "gemini-flash-lite-latest",
                "gemini-3.1-pro-preview",
            ]),
            (.openai, [
                "gpt-5.4-mini",
                "gpt-6-luna",
                "gpt-6-sol",
                "gpt-6.1-terra", "gpt-6.2-sol", "gpt-6.2-astra",
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.5",
            ]),
            (.anthropic, [
                "claude-sonnet-5",
                "claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5",
                "claude-opus-5",
                "claude-fable-5",
                "claude-opus-4-8",
                "claude-haiku-4-5",
            ]),
            (.xai, [
                "grok-4.6",
                "grok-4.3",
                "grok-4.7",
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
                "qwen/qwen3-vl-8b-instruct", "deepseek/deepseek-v4.1-flash",
            ]),
            (.togetherai, [
                "Qwen/Qwen3.5-9B",
                "moonshotai/Kimi-K3",
                "moonshotai/Kimi-K3.1-Pro",
            ]),
            (.groq, [
                "qwen/qwen3.8-27b",
            ]),
            (.huggingface, [
                "google/gemma-4-31B-it",
                "Qwen/Qwen3.8-27B",
                "moonshotai/Kimi-K3",
                "google/gemma-3-27b-it",
                "Qwen/Qwen3.5-9B",
            ]),
            (.fireworks, [
                "accounts/fireworks/models/kimi-k3",
                "accounts/fireworks/models/minimax-m3p1-flash",
                "accounts/fireworks/models/kimi-k3p1-pro",
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
                "mistral-large-4-0",
                "ministral-14b-2512", "ministral-8b-2512",
            ]),
            (.deepseek, ["deepseek-flash"]),
            (.qwen, [
                "qwen3.7-plus",
                "qwen3.8-flash",
                "qwen3.8-max",
                "qwen3-vl-plus", "qwen4-max",
            ]),
            (.cerebras, ["qwen-3.8-27b"]),
            (.ollama, [
                "qwen3-vl",
                "qwen3.8",
                "gemma4",
                "gemma4:e2b", "qwen3-vl:2b",
                "llama3.2-vision",
                "llava",
                "moondream",
            ]),
            (.customOpenAI, []),
        ]

        var visionInsertIndex = 0
        if #available(iOS 27.0, *) {
            if AIProvider.appleIntelligence.supportsVision {
                registries.insert((.appleIntelligence, ["System Language Model"]), at: visionInsertIndex)
                visionInsertIndex += 1
            }
        }
        if Gemma4LocalModelManager.isCurrentDeviceSelectable {
            registries.insert((.gemma4Local, [Gemma4LocalModelManager.modelID]), at: visionInsertIndex)
            visionInsertIndex += 1
        }
        if Gemma4LocalModelManager.isCurrentDeviceSelectable(.e4b) {
            registries.insert((.gemma4E4BLocal, ["gemma-4-E4B-it"]), at: visionInsertIndex)
        }

        #expect(registries.map(\.provider) == AIProvider.visionProviders)
        for registry in registries {
            #expect(registry.provider.models == registry.models)
            #expect(registry.provider.defaultModel == (registry.models.first ?? ""))
        }
    }

    @Test func providersLimitPhotoSupportToDocumentedModels() {
        #expect(AIProvider.textProviders == AIProvider.allCases.filter(\.isAvailableOnCurrentDevice))
        if #available(iOS 27.0, *) {
            if AIProvider.appleIntelligence.supportsVision {
                #expect(AIProvider.visionProviders.contains(.appleIntelligence))
                #expect(AIProvider.appleIntelligence.models == ["System Language Model"])
            } else {
                #expect(!AIProvider.visionProviders.contains(.appleIntelligence))
            }
        } else {
            #expect(!AIProvider.visionProviders.contains(.appleIntelligence))
            #expect(!AIProvider.appleIntelligence.supportsVision)
        }
        #expect(AIProvider.deepseek.models == ["deepseek-flash"])
        #expect(AIProvider.cerebras.models == ["qwen-3.8-27b"])
        #expect(AIProvider.appleIntelligence.textModels == ["System Language Model"])
        #expect(!AIProvider.appleIntelligence.requiresAPIKey)
        #expect(AIProvider.gemma4Local.models == [Gemma4LocalModelManager.modelID])
        #expect(!AIProvider.gemma4Local.requiresAPIKey)
        #expect(AIProvider.gemma4E4BLocal.models == ["gemma-4-E4B-it"])
        #expect(!AIProvider.gemma4E4BLocal.requiresAPIKey)
        #expect(AIProvider.deepseek.textModels == ["deepseek-flash", "deepseek-v4-pro"])
        #expect(AIProvider.cerebras.textModels == ["gpt-oss-120b", "qwen-3.8-27b"])
        #expect(AIProvider.qwen.textModels == ["qwen3.8-flash", "qwen3.7-plus", "qwen3.8-max", "qwen4-max"])
        #expect(AIProvider.qwen.apiFormat == .openaiCompatible)
        #expect(AIProvider.qwen.baseURL == "https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
        #expect(AIProvider.qwen.openAICompatibleExtraBody["enable_thinking"] as? Bool == false)
        #expect(AIProvider.openai.openAICompatibleExtraBody.isEmpty)
        #expect(AIProvider.groq.defaultTextModel == "openai/gpt-oss-20b")
        #expect(AIProvider.groq.textModels.contains("openai/gpt-oss-120b"))
        #expect(AIProvider.togetherai.defaultTextModel == "Qwen/Qwen3.5-9B")
        #expect(AIProvider.togetherai.textModels.contains("deepseek-ai/DeepSeek-V4-Pro-0813"))
        #expect(AIProvider.deepseek.supportedTextModelOrDefault("retired-model") == "deepseek-flash")
        #expect(AIProvider.requestImageLimit(provider: .cerebras, model: "qwen-3.8-27b") == 2)
        #expect(AIProvider.requestImageLimit(provider: .cerebras, model: "gpt-oss-120b") == nil)
    }

    @Test func removedPresetsHaveProviderScopedReplacements() {
        #expect(AIProvider.upgradedLegacyModel(for: .gemini, model: "gemini-3.1-flash-lite") == "gemini-3.5-flash-lite")
        #expect(AIProvider.upgradedLegacyModel(for: .openrouter, model: "google/gemini-3.1-flash-lite") == nil)
        #expect(AIProvider.upgradedLegacyModel(for: .huggingface, model: "Qwen/Qwen2.5-VL-72B-Instruct") == nil)
        #expect(AIProvider.upgradedLegacyModel(for: .mistral, model: "mistral-medium-2604") == "mistral-large-4-0")
        #expect(AIProvider.upgradedLegacyModel(for: .anthropic, model: "claude-sonnet-4-6") == "claude-sonnet-5")
        #expect(AIProvider.upgradedLegacyModel(for: .anthropic, model: "claude-opus-4-7") == "claude-opus-5")

        #expect(AIProvider.upgradedLegacyModel(for: .openrouter, model: "gemini-3.1-flash-lite") == nil)
        #expect(AIProvider.upgradedLegacyModel(for: .openai, model: "gpt-5.4-mini") == nil)
        #expect(AIProvider.upgradedLegacyModel(for: .gemini, model: nil) == nil)
    }

    @Test func modelRegistryMigrationUpgradesPrimaryAndFallbackExactlyOnce() throws {
        let migrations: [(provider: AIProvider, old: String, new: String)] = [
            (.gemini, "gemini-3.1-flash-lite", "gemini-3.5-flash-lite"),
            (.gemini, "gemini-3.7-flash", "gemini-3.8-flash"),
            (.openai, "gpt-4.1-mini", "gpt-6-luna"),
            (.mistral, "mistral-medium-2604", "mistral-large-4-0"),
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
            defaults.set(AIProvider.groq.rawValue, forKey: "selectedTextAIProvider")
            defaults.set("llama-3.3-70b-versatile", forKey: "selectedTextAIModel")
            defaults.set(AIProvider.togetherai.rawValue, forKey: "selectedTextFallbackAIProvider")
            defaults.set("MiniMaxAI/MiniMax-M2.7", forKey: "selectedTextFallbackAIModel")
            defaults.set(2, forKey: "aiModelRegistryMigrationVersion")
            defaults.set("preserved", forKey: "unrelatedUserData")

            AIProviderSettings.migrateModelRegistryIfNeeded(defaults: defaults)

            #expect(defaults.string(forKey: "selectedAIModel") == migration.new)
            #expect(defaults.string(forKey: "selectedFallbackAIModel") == migration.new)
            #expect(defaults.string(forKey: "selectedTextAIModel") == "openai/gpt-oss-120b")
            #expect(defaults.string(forKey: "selectedTextFallbackAIModel") == "Qwen/Qwen3.5-9B")
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
        defaults.set("gemini-3.6-flash", forKey: "selectedAIModel")
        defaults.set("google/gemini-3.1-flash-lite", forKey: "selectedFallbackAIModel")

        AIProviderSettings.migrateLegacyGeminiModelsIfNeeded(defaults: defaults)

        #expect(defaults.string(forKey: "selectedAIModel") == "gemini-3.6-flash")
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
        #expect(defaults.string(forKey: "selectedFallbackAIModel") == "google/gemini-3.1-flash-lite")
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
            (.appleSpeechAnalyzer, ""),
            (.whisperBase, "base"),
            (.parakeetV3, "parakeet-tdt-0.6b-v3"),
            (.qwen3ASR, "qwen3-asr-0.6b"),
            (.gemini, "gemini-3.5-transcribe"),
            (.openai, "gpt-transcribe"),
            (.groq, "whisper-large-v3"),
            (.mistral, "voxtral-mini-transcribe-3-0"),
            (.deepgram, "nova-3"),
            (.assemblyai, "assemblyai-stt-3-7"),
        ]

        #expect(defaults.map(\.provider) == SpeechProvider.allCases)
        for entry in defaults {
            #expect(entry.provider.defaultModel == entry.model)
        }
    }

    @Test func optionalRecordedModelsDoNotChangeExistingDefaults() {
        #expect(SpeechProvider.groq.recordedModelOrDefault(nil) == "whisper-large-v3")
        #expect(SpeechProvider.groq.recordedModelOrDefault("whisper-large-v3-turbo") == "whisper-large-v3-turbo")
        #expect(SpeechProvider.mistral.recordedModelOrDefault("voxtral-small-transcribe-3-0") == "voxtral-small-transcribe-3-0")
        #expect(SpeechProvider.mistral.recordedModelOrDefault("foreign-model") == "voxtral-mini-transcribe-3-0")
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
        #expect((transcriptionConfig["mode"] as? [String: String])?["type"] == "verbatim")
        #expect(body["store"] as? Bool == false)
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
        #expect(body["speech_models"] as? [String] == ["assemblyai-stt-3-7", "universal-2"])
        #expect(body["disfluencies"] as? Bool == false)
        #expect(body["language_detection"] as? Bool == true)
        #expect(body["language_code"] == nil)
    }

    @Test func assemblyAIExplicitLanguagePayloadDisablesAutomaticDetection() {
        let body = SpeechService.assemblyAITranscriptBody(
            audioURL: "https://upload.example/audio",
            speechModels: [SpeechProvider.assemblyai.defaultModel, "universal-2"],
            languageCode: "hi"
        )

        #expect(body["speech_models"] as? [String] == ["assemblyai-stt-3-7", "universal-2"])
        #expect(body["language_code"] as? String == "hi")
        #expect(body["language_detection"] == nil)
    }

    @Test func appleIntelligenceVisionSupportIsGatedToIOS27() {
        if #available(iOS 27.0, *) {
            #expect(AIProvider.appleIntelligence.supportsVision)
            #expect(AIProvider.appleIntelligence.defaultModel == "System Language Model")
        } else {
            #expect(!AIProvider.appleIntelligence.supportsVision)
            #expect(AIProvider.appleIntelligence.models.isEmpty)
        }
    }

    @Test func appleSpeechAnalyzerIsLocalBatchAndRequiresALanguage() {
        let provider = SpeechProvider.appleSpeechAnalyzer
        #expect(provider.isLocalBatch)
        #expect(!provider.requiresAPIKey)
        #expect(!provider.languageChoices.contains(.automatic))
        #expect(provider.languageChoices.contains(.device))
        #expect(SpeechSettings.defaultLanguage(for: provider) == .device)
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
