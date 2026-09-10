import Foundation
import Testing
@testable import calorietracker

struct AIErrorMessagesTests {
    @Test func mapsStatusesAndCreditMarkers() {
        for marker in ["API key not valid", "API_KEY_INVALID", "API key expired", "API_KEY_EXPIRED"] {
            #expect(AIErrorKind.classify(status: 400, raw: marker) == .keyRejected)
        }
        #expect(AIErrorKind.classify(status: 401, raw: "") == .keyRejected)
        #expect(AIErrorKind.classify(status: 403, raw: "") == .keyRejected)
        #expect(AIErrorKind.classify(status: 402, raw: "") == .credits)
        #expect(AIErrorKind.classify(status: 400, raw: "Credit balance is too low") == .credits)
        #expect(AIErrorKind.classify(status: 400, raw: "INSUFFICIENT CREDITS") == .credits)
        #expect(AIErrorKind.classify(status: 404, raw: "unknown endpoint") == .modelUnavailable)
        #expect(AIErrorKind.classify(status: 429, raw: "too many requests") == .rateLimited)
        for status in [503, 529] { #expect(AIErrorKind.classify(status: status, raw: "") == .overloaded) }
    }

    @Test func quotaDoesNotMeanOutOfCredits() {
        #expect(AIErrorKind.classify(status: 429, raw: "daily quota exceeded") == .dailyQuota)
        #expect(AIErrorKind.classify(status: 429, raw: #"{"error":{"message":"Resource exhausted","details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}}"#) == .dailyQuota)
        #expect(AIErrorKind.classify(status: 429, raw: "quota exceeded") == .quota)
        #expect(AIErrorKind.classify(status: 429, raw: "insufficient_quota") == .quota)
    }

    @Test func networkFailuresHaveDistinctGuidance() {
        #expect(AIErrorKind.network(URLError(.timedOut)) == .timeout)
        #expect(AIErrorKind.network(URLError(.notConnectedToInternet)) == .offline)
        #expect(AIErrorKind.network(URLError(.cannotConnectToHost)) == .connection)
        #expect(AIErrorKind.network(NSError(domain: "private transport", code: 1)) == .connection)
    }

    @Test func unknownProviderTextStaysOutOfMessages() {
        for status in [400, 418, 500] {
            #expect(AIErrorKind.classify(status: status, raw: "sensitive provider text") == .generic)
        }
        for kind in AIErrorKind.allCases { #expect(!kind.message.isEmpty) }
    }

    @Test func combinedFailureNamesBothProviders() {
        let error = AnalysisFallbackError(primaryName: "Gemini", fallbackName: "OpenRouter", detail: AIErrorKind.credits.message)
        #expect(error.localizedDescription.contains("Gemini"))
        #expect(error.localizedDescription.contains("OpenRouter"))
        #expect(error.localizedDescription.contains(AIErrorKind.credits.message))
    }
}
