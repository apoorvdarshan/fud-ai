import Foundation

enum WeeklyChallengeAPIError: Error {
    case invalidRequest
    case invalidResponse
    case transport(URLError)
    case server(statusCode: Int, code: String?, message: String?)

    var isOffline: Bool {
        guard case .transport(let error) = self else { return false }
        switch error.code {
        case .notConnectedToInternet, .networkConnectionLost, .cannotConnectToHost,
             .cannotFindHost, .dnsLookupFailed, .timedOut, .internationalRoamingOff:
            return true
        default:
            return false
        }
    }
}

private struct WeeklyChallengeAPIErrorEnvelope: Decodable {
    struct Payload: Decodable {
        let code: String?
        let message: String?
        let fields: [String]?
    }

    let error: Payload
}

struct WeeklyChallengeAPIClient {
    static let productionBaseURL = URL(string: "https://fud-ai.app/api/challenge/v1")!

    private let baseURL: URL
    private let session: URLSession
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(
        baseURL: URL = WeeklyChallengeAPIClient.productionBaseURL,
        session: URLSession = .shared
    ) {
        self.baseURL = baseURL
        self.session = session
        encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        decoder = JSONDecoder()
    }

    func createProfile(
        _ request: WeeklyChallengeCreateProfileRequest
    ) async throws -> WeeklyChallengeCreateProfileResponse {
        try await send(
            path: "profile",
            method: "POST",
            token: nil,
            body: request,
            response: WeeklyChallengeCreateProfileResponse.self
        )
    }

    func updateProfile(
        _ input: WeeklyChallengeProfileInput,
        token: String
    ) async throws -> WeeklyChallengeProfileResponse {
        try await send(
            path: "profile",
            method: "PATCH",
            token: token,
            body: input,
            response: WeeklyChallengeProfileResponse.self
        )
    }

    func deleteProfile(token: String) async throws -> WeeklyChallengeDeleteResponse {
        try await sendWithoutBody(
            path: "profile",
            method: "DELETE",
            token: token,
            response: WeeklyChallengeDeleteResponse.self
        )
    }

    func putWeeklyScore(
        _ score: WeeklyChallengeScore,
        token: String
    ) async throws -> WeeklyChallengeScoreResponse {
        try await send(
            path: "weekly-score",
            method: "PUT",
            token: token,
            body: score,
            response: WeeklyChallengeScoreResponse.self
        )
    }

    func leaderboard(
        category: WeeklyChallengeCategory,
        weekStart: String,
        limit: Int = 20,
        offset: Int = 0,
        period: ChallengePeriod = .weekly,
        token: String
    ) async throws -> WeeklyChallengeLeaderboardResponse {
        guard var components = URLComponents(
            url: baseURL.appendingPathComponent("leaderboard"),
            resolvingAgainstBaseURL: false
        ) else {
            throw WeeklyChallengeAPIError.invalidRequest
        }
        components.queryItems = [
            URLQueryItem(name: "category", value: category.rawValue),
            URLQueryItem(name: period == .weekly ? "weekStart" : "periodStart", value: weekStart),
            URLQueryItem(name: "limit", value: String(min(max(limit, 1), 100))),
        ]
        if offset > 0 { components.queryItems?.append(URLQueryItem(name: "offset", value: String(offset))) }
        if period != .weekly { components.queryItems?.append(URLQueryItem(name: "period", value: period.rawValue)) }
        guard let url = components.url else { throw WeeklyChallengeAPIError.invalidRequest }
        var request = request(url: url, method: "GET", token: token)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let response = try await perform(request, response: WeeklyChallengeLeaderboardResponse.self)
        // Older Workers return a fixed top 100 with no server paging metadata.
        if period == .weekly, offset == 0, limit < 100, response.totalCount == nil, response.rankings.count == limit {
            return try await leaderboard(category: category, weekStart: weekStart, limit: 100, token: token)
        }
        return response
    }

    private struct PeriodAcknowledgement: Decodable { let ok: Bool }

    func putPeriodScore(_ score: ChallengePeriodScore, token: String) async throws {
        let response = try await send(path: "period-score", method: "PUT", token: token, body: score,
                                      response: PeriodAcknowledgement.self)
        guard response.ok else { throw WeeklyChallengeAPIError.invalidResponse }
    }

    func deletePeriod(_ period: ChallengePeriod, token: String) async throws {
        guard var url = URLComponents(url: baseURL.appendingPathComponent("period-score"), resolvingAgainstBaseURL: false)
        else { throw WeeklyChallengeAPIError.invalidRequest }
        url.queryItems = [URLQueryItem(name: "period", value: period.rawValue)]
        guard let endpoint = url.url else { throw WeeklyChallengeAPIError.invalidRequest }
        let response = try await perform(request(url: endpoint, method: "DELETE", token: token),
                                         response: WeeklyChallengeDeleteResponse.self)
        guard response.deleted else { throw WeeklyChallengeAPIError.invalidResponse }
    }

    func report(
        _ report: WeeklyChallengeReportRequest,
        token: String
    ) async throws -> WeeklyChallengeReportResponse {
        try await send(
            path: "reports",
            method: "POST",
            token: token,
            body: report,
            response: WeeklyChallengeReportResponse.self
        )
    }

    private func send<Body: Encodable, Response: Decodable>(
        path: String,
        method: String,
        token: String?,
        body: Body,
        response: Response.Type
    ) async throws -> Response {
        var request = request(
            url: baseURL.appendingPathComponent(path),
            method: method,
            token: token
        )
        request.httpBody = try encoder.encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return try await perform(request, response: response)
    }

    private func sendWithoutBody<Response: Decodable>(
        path: String,
        method: String,
        token: String?,
        response: Response.Type
    ) async throws -> Response {
        let request = request(
            url: baseURL.appendingPathComponent(path),
            method: method,
            token: token
        )
        return try await perform(request, response: response)
    }

    private func request(url: URL, method: String, token: String?) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private func perform<Response: Decodable>(
        _ request: URLRequest,
        response: Response.Type
    ) async throws -> Response {
        let data: Data
        let urlResponse: URLResponse
        do {
            (data, urlResponse) = try await session.data(for: request)
        } catch let error as URLError {
            throw WeeklyChallengeAPIError.transport(error)
        }

        guard let httpResponse = urlResponse as? HTTPURLResponse else {
            throw WeeklyChallengeAPIError.invalidResponse
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            let envelope = try? decoder.decode(WeeklyChallengeAPIErrorEnvelope.self, from: data)
            throw WeeklyChallengeAPIError.server(
                statusCode: httpResponse.statusCode,
                code: envelope?.error.code,
                message: envelope?.error.message
            )
        }

        do {
            return try decoder.decode(response, from: data)
        } catch {
            throw WeeklyChallengeAPIError.invalidResponse
        }
    }
}
