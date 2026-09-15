import Testing
import Foundation
@testable import calorietracker

struct GeminiRequestConfigurationTests {
    @Test func minimalThinkingOnlyGoesToModelsThatAcceptIt() {
        for model in ["gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.6-flash", "gemini-3-flash", "gemini-3-flash-preview"] {
            #expect(GeminiRequestConfiguration.thinkingLevel(for: model) == "minimal", "\(model)")
        }
        for model in ["gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.1-pro-preview", "gemini-3-pro"] {
            #expect(GeminiRequestConfiguration.thinkingLevel(for: model) == "low", "\(model)")
        }
        #expect(GeminiRequestConfiguration.thinkingLevel(for: "models/gemini-3.5-flash-lite") == "minimal")
        #expect(GeminiRequestConfiguration.thinkingLevel(for: " Gemini-3.5-Flash-Lite ") == "minimal")
    }

    @Test func preGemini3AndUnknownModelsSendNoThinkingLevel() {
        for model in ["gemini-2.5-flash", "gemini-2.0-flash-lite", "gemma-3-27b-it", "custom-proxy-model", "gemini-", ""] {
            #expect(GeminiRequestConfiguration.thinkingLevel(for: model) == nil, "\(model)")
        }
    }

    @Test func foodAnalysisGenerationConfigCapsTokensAndForcesJson() throws {
        let config = try #require(GeminiRequestConfiguration.generationConfig(
            model: "gemini-3.5-flash-lite", maxOutputTokens: 1024, jsonResponse: true
        ))

        #expect(config["maxOutputTokens"] as? Int == 1024)
        #expect(config["responseMimeType"] as? String == "application/json")
        #expect((config["thinkingConfig"] as? [String: Any])?["thinkingLevel"] as? String == "minimal")
    }

    @Test func proseRequestsSkipJsonMimeTypeAndFlashUsesLowThinking() throws {
        let config = try #require(GeminiRequestConfiguration.generationConfig(
            model: "gemini-3.8-flash", maxOutputTokens: 512, jsonResponse: false
        ))

        #expect(config["maxOutputTokens"] as? Int == 512)
        #expect(config["responseMimeType"] == nil)
        #expect((config["thinkingConfig"] as? [String: Any])?["thinkingLevel"] as? String == "low")
    }

    @Test func emptyConfigurationIsOmitted() {
        #expect(GeminiRequestConfiguration.generationConfig(model: "custom-model", maxOutputTokens: 0, jsonResponse: false) == nil)
    }

    @Test func parserJoinsEveryTextPartAndSkipsThoughtParts() throws {
        let response = try GeminiRequestConfiguration.parseTextResponse(from: Data("""
        {"candidates":[{"content":{"parts":[
            {"thought":true,"text":"Let me look at the plate..."},
            {"text":"{\\"name\\":\\"Oatmeal\\","},
            {"text":"\\"calories\\":320}"}
        ],"role":"model"},"finishReason":"STOP"}]}
        """.utf8))

        #expect(response.text == #"{"name":"Oatmeal","calories":320}"#)
        #expect(response.finishReason == "STOP")
        #expect(!response.wasTruncated)
    }

    @Test func parserReportsTruncationInsteadOfInvalidResponse() throws {
        let truncated = try GeminiRequestConfiguration.parseTextResponse(from: Data(
            #"{"candidates":[{"content":{"parts":[{"thought":true,"text":"hmm"}],"role":"model"},"finishReason":"MAX_TOKENS"}]}"#.utf8
        ))
        #expect(truncated.text == nil)
        #expect(truncated.wasTruncated)

        let partial = try GeminiRequestConfiguration.parseTextResponse(from: Data(
            #"{"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Oat"}]},"finishReason":"MAX_TOKENS"}]}"#.utf8
        ))
        #expect(partial.text == #"{"name":"Oat"#)
        #expect(partial.wasTruncated)
    }

    @Test func parserRejectsEmptyOrMalformedCandidates() {
        let bodies = [
            "not json",
            #"{"promptFeedback":{"blockReason":"SAFETY"}}"#,
            #"{"candidates":[]}"#,
            #"{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}"#,
            #"{"candidates":[{"content":{"parts":[{"thought":true,"text":"only thoughts"}]},"finishReason":"STOP"}]}"#,
        ]
        for body in bodies {
            #expect(throws: GeminiService.AnalysisError.self, "\(body)") {
                try GeminiRequestConfiguration.parseTextResponse(from: Data(body.utf8))
            }
        }
    }

    @Test func compactRetryPromptMentionsBudget() {
        let jsonPrompt = GeminiRequestConfiguration.compactRetryPrompt(
            "Analyze", maxOutputTokens: 256, jsonResponse: true
        )
        #expect(jsonPrompt.hasPrefix("Analyze"))
        #expect(jsonPrompt.contains("truncated"))
        #expect(jsonPrompt.contains("JSON object"))
        #expect(jsonPrompt.contains("256 tokens"))

        let prosePrompt = GeminiRequestConfiguration.compactRetryPrompt(
            "Suggest", maxOutputTokens: 128, jsonResponse: false
        )
        #expect(prosePrompt.contains("plain-English"))
        #expect(!prosePrompt.contains("JSON object"))
    }

    @Test func servingUnitRepairSkipsWhenMacrosAreUsable() throws {
        var analysis = try GeminiService.parseFoodAnalysis(
            from: #"{"name":"Oatmeal","calories":320,"protein":12,"carbs":50,"fat":6,"serving_size_grams":250}"#
        )
        #expect(analysis.requiresServingUnitFallback)
        #expect(!ServingUnitRepairPolicy.shouldRepair(analysis))

        analysis.calories = 0
        analysis.protein = 0
        analysis.carbs = 0
        analysis.fat = 0
        #expect(ServingUnitRepairPolicy.shouldRepair(analysis))

        analysis.requiresServingUnitFallback = false
        #expect(!ServingUnitRepairPolicy.shouldRepair(analysis))

        let unknownWeight = try GeminiService.parseFoodAnalysis(
            from: #"{"name":"Water","calories":0,"protein":0,"carbs":0,"fat":0}"#
        )
        #expect(!unknownWeight.servingSizeIsKnown)
        #expect(!ServingUnitRepairPolicy.shouldRepair(unknownWeight))
    }

    @Test func nutritionLabelRepairFollowsTheSameRule() throws {
        var label = try GeminiService.parseNutritionLabel(
            from: #"{"name":"Bar","calories_per_100g":400,"protein_per_100g":20,"carbs_per_100g":40,"fat_per_100g":15,"serving_size_grams":50}"#
        )
        #expect(label.requiresServingUnitFallback)
        #expect(!ServingUnitRepairPolicy.shouldRepair(label))

        label.caloriesPer100g = 0
        label.proteinPer100g = 0
        label.carbsPer100g = 0
        label.fatPer100g = 0
        #expect(ServingUnitRepairPolicy.shouldRepair(label))

        label.servingSizeGrams = nil
        #expect(!ServingUnitRepairPolicy.shouldRepair(label))
    }
}
