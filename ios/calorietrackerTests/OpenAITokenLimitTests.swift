import Testing
@testable import calorietracker

struct OpenAITokenLimitTests {
    @Test func customGPT6ModelsUseCompletionTokens() {
        for model in ["gpt-6", "gpt-6-sol", "gpt-6-luna", "gpt-6-astra", "gpt-6.1-sol", "gateway/openai/gpt-6-sol", "  OpenAI/GPT-6-LUNA  "] {
            #expect(AIProvider.customOpenAI.openAICompatibleTokenLimitKey(for: model) == "max_completion_tokens")
        }
    }

    @Test func existingTokenLimitsStayProviderScoped() {
        for provider in AIProvider.allCases {
            let expected = provider == .openai || provider == .customOpenAI ? "max_completion_tokens" : "max_tokens"
            #expect(provider.openAICompatibleTokenLimitKey(for: "openai/gpt-6-luna") == expected)
        }
        for model in ["gpt-5.6-sol", "openai/o1-mini", "o3", "o4-mini"] {
            #expect(AIProvider.customOpenAI.openAICompatibleTokenLimitKey(for: model) == "max_completion_tokens")
        }
        for model in ["gpt-4.1", "openai/gpt-4o-mini", "qwen/qwen3.8", "", "   "] {
            #expect(AIProvider.customOpenAI.openAICompatibleTokenLimitKey(for: model) == "max_tokens")
        }
    }

    @Test func solAndLunaPresetsSupportVisionAndTextWithoutChangingTheDefault() {
        for model in ["gpt-6-luna", "gpt-6-sol"] {
            #expect(AIProvider.openai.supportedModelOrDefault(model) == model)
            #expect(AIProvider.openai.supportedTextModelOrDefault(model) == model)
        }
        #expect(AIProvider.openai.defaultModel == "gpt-5.4-mini")
        #expect(AIProvider.openai.defaultTextModel == "gpt-5.4-mini")
    }

    @Test func onlyDirectSolAndLunaToolRequestsDisableReasoning() {
        for provider in AIProvider.allCases {
            for model in ["gpt-6-sol", "gpt-6-luna", "  OpenAI/GPT-6-LUNA  ", "gateway/gpt-6-sol-2026-08-01"] {
                let expected: String? = provider == .openai || provider == .customOpenAI ? "none" : nil
                #expect(provider.openAICompatibleToolReasoningEffort(for: model) == expected)
            }
            for model in ["gpt-5.6-sol", "gpt-6.1-sol", "gpt-6-astra", "gpt-6", "gpt-6-solar", "", "   "] {
                #expect(provider.openAICompatibleToolReasoningEffort(for: model) == nil)
            }
        }
    }
}
