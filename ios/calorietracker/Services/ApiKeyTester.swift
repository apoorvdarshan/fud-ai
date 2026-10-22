import Foundation

/// Checks a stored API key with the provider it belongs to.
///
/// The check sends one minimal, cheap request (a single word prompt with the smallest
/// possible token budget) to the provider, so the user learns whether the key, the model
/// and the endpoint actually work together. It costs one request on the provider's
/// account, which the UI states before the user triggers it.
///
/// Nothing about the key itself is logged.
enum ApiKeyTester {

    /// The cheapest possible request: one word, no image, no JSON.
    private static let testPrompt = "ping"

    /// The result of a key check: the failure kind, or nil when the provider accepted
    /// the key.
    struct Result {
        let failure: AIErrorKind?

        var isSuccess: Bool { failure == nil }
    }

    /// Tests `apiKey` against `provider` and `model`.
    static func test(
        provider: AIProvider,
        model: String,
        apiKey: String?,
        baseURL: String
    ) async -> Result {
        if provider.requiresAPIKey, (apiKey ?? "").trimmingCharacters(in: .whitespaces).isEmpty {
            return Result(failure: .noKey)
        }
        if baseURL.trimmingCharacters(in: .whitespaces).isEmpty {
            return Result(failure: .invalidURL)
        }
        do {
            let response = try await GeminiService.testRequest(
                provider: provider,
                model: model,
                baseURL: baseURL,
                apiKey: apiKey,
                prompt: testPrompt
            )
            // A provider that answers with an empty body did not really accept the key.
            return Result(failure: response.trimmingCharacters(in: .whitespaces).isEmpty ? .invalidResponse : nil)
        } catch let error as GeminiService.AnalysisError {
            switch error {
            case .noAPIKey:
                return Result(failure: .noKey)
            case .imageConversionFailed:
                return Result(failure: .imageConversion)
            case .networkError(let underlying):
                return Result(failure: AIErrorKind.network(underlying))
            case .invalidResponse:
                return Result(failure: .invalidResponse)
            case .apiError(let message):
                return Result(failure: AIErrorKind.classify(status: 0, raw: message))
            case .requestFailed(let kind):
                return Result(failure: kind)
            }
        } catch {
            return Result(failure: AIErrorKind.network(error))
        }
    }
}
