import Foundation
import Testing
@testable import calorietracker

@Suite("AI request error integration", .serialized)
@MainActor
struct AIRequestErrorIntegrationTests {
    @Test func dailyQuotaRetriesFourTimesAndReadsMetadata() async throws {
        var count = 0
        let session = makeSession { request in
            count += 1
            return self.response(request, status: 429, body: #"{"error":{"message":"Resource exhausted","details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}}"#)
        }
        defer { session.invalidateAndCancel() }
        await expectFailure(session, kind: .dailyQuota)
        #expect(count == 4)
    }

    @Test func nonRetryableResponsesAreSafeAndAttemptedOnce() async throws {
        for (status, kind) in [(402, AIErrorKind.credits), (404, .modelUnavailable), (500, .generic)] {
            var count = 0
            let session = makeSession { request in
                count += 1
                return self.response(request, status: status, body: #"{"error":{"message":"private provider details"}}"#)
            }
            await expectFailure(session, kind: kind)
            session.invalidateAndCancel()
            #expect(count == 1)
        }
    }

    @Test func transientFailureCanRecover() async throws {
        var count = 0
        let session = makeSession { request in
            count += 1
            return self.response(request, status: count == 1 ? 503 : 200, body: count == 1 ? "{}" : "success")
        }
        defer { session.invalidateAndCancel() }
        let data = try await send(session)
        #expect(String(data: data, encoding: .utf8) == "success")
        #expect(count == 2)
    }

    @Test func networkErrorsReachPresentationWithSpecificGuidance() async {
        for (code, kind) in [(URLError.Code.timedOut, AIErrorKind.timeout), (.notConnectedToInternet, .offline), (.cannotConnectToHost, .connection)] {
            let session = makeSession { _ in throw URLError(code) }
            await expectFailure(session, kind: kind)
            session.invalidateAndCancel()
        }
    }

    @Test func cancellationIsNotShownAsNetworkFailure() async {
        let session = makeSession { _ in throw URLError(.cancelled) }
        defer { session.invalidateAndCancel() }
        do {
            _ = try await send(session)
            Issue.record("Expected cancellation")
        } catch {
            #expect(error is CancellationError)
        }
    }

    @Test func presentationHidesUnknownErrorDetails() {
        let error = NSError(domain: "private provider details", code: 500)
        #expect(GeminiService.analysisErrorMessage(error) == AIErrorKind.generic.message)
        #expect(GeminiService.analysisErrorMessage(GeminiService.AnalysisError.apiError("private provider details")) == AIErrorKind.generic.message)
    }

    private func expectFailure(_ session: URLSession, kind: AIErrorKind) async {
        do {
            _ = try await send(session)
            Issue.record("Expected request failure")
        } catch {
            #expect(GeminiService.analysisErrorMessage(error) == kind.message)
            #expect(!error.localizedDescription.contains("private provider details"))
            #expect(!error.localizedDescription.hasPrefix("API error:"))
        }
    }

    private func send(_ session: URLSession) async throws -> Data {
        try await GeminiService.makeRequest(url: URL(string: "https://ai.test/analyze")!, headers: [:], body: [:], provider: .gemini, session: session)
    }

    private func makeSession(handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)) -> URLSession {
        AIErrorURLProtocol.handler = handler
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AIErrorURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func response(_ request: URLRequest, status: Int, body: String) -> (HTTPURLResponse, Data) {
        (HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!, Data(body.utf8))
    }
}

private final class AIErrorURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            guard let handler = Self.handler else { throw URLError(.unknown) }
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }
    override func stopLoading() {}
}
