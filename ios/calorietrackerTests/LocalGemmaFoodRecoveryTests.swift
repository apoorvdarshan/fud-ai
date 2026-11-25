import Foundation
import Testing
@testable import calorietracker

struct LocalGemmaFoodRecoveryTests {
    @Test func incompleteMealOutputRetriesOnceLocally() async throws {
        let prompt = "Estimate oats.\n\n" + CanonicalFoodAllergens.prompt
        var calls: [String] = []
        let text = try await GeminiService.localGemmaResponse(prompt: prompt, expectsJSON: true) {
            calls.append($0)
            return calls.count == 1 ? #"{"name":"Oats"}"#
                : #"{"name":"Oats","calories":100,"protein":3,"carbs":18,"fat":2}"#
        }
        #expect(try GeminiService.parseFoodAnalysis(from: text).name == "Oats")
        #expect(calls.count == 2)
        #expect(calls[1].hasPrefix(prompt))
        #expect(calls[1].contains("compact JSON object"))
    }

    @Test func runtimeFailureIsNotRetriedOrPresentedAsANetworkError() async {
        var count = 0
        do {
            _ = try await GeminiService.localGemmaResponse(prompt: "food", expectsJSON: true) { _ in
                count += 1
                throw URLError(.cannotConnectToHost)
            }
            Issue.record("Expected a local inference error")
        } catch let error as Gemma4LocalModelManager.LocalModelError {
            guard case .inferenceFailed = error else {
                Issue.record("Wrong local error: \(error)")
                return
            }
            #expect(count == 1)
            #expect(GeminiService.analysisErrorMessage(error).hasPrefix("Gemma 4:"))
        } catch {
            Issue.record("Expected local inference error, got \(error)")
        }
    }
}
