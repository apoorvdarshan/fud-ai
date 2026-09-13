//
//  HostedAIService.swift
//  calorietracker
//

import Foundation
import RevenueCat

enum HostedAIServiceError: LocalizedError {
    case invalidURL
    case unauthorized
    case serverError(String)
    case invalidResponse
    case upstreamUnavailable

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid hosted AI URL."
        case .unauthorized: return "Hosted AI could not identify your subscription. Try Restore Purchases."
        case .serverError(let msg): return msg
        case .invalidResponse: return "Could not understand the hosted AI response."
        case .upstreamUnavailable: return "Hosted AI is temporarily unavailable. Please try again shortly."
        }
    }
}

/// Client for the Fud-operated proxy (`web/hosted-ai-api.ts`).
///
/// The app ships no proxy secret: each request carries only the RevenueCat app
/// user id, and the Worker verifies the plan with RevenueCat and meters usage
/// in a server-side ledger. Quota numbers come back in `X-Fud-Quota-*`
/// headers and are cached by `HostedAIQuotaManager` for display.
enum HostedAIService {
    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        return URLSession(configuration: config)
    }()

    static func generate(prompt: String, imageDataList: [Data], systemInstruction: String?) async throws -> String {
        let cappedImages = Array(imageDataList.prefix(HostedAIConstants.maxHostedImages))
        let body: [String: Any] = [
            "prompt": prompt,
            "images": cappedImages.map { $0.base64EncodedString() },
            "systemInstruction": systemInstruction as Any,
        ].compactMapValues { $0 }

        let data = try await post(path: "generate", jsonBody: body)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = json["text"] as? String else {
            throw HostedAIServiceError.invalidResponse
        }
        return text
    }

    /// One call = one metered hosted action. The Worker only forwards
    /// `contents`, `systemInstruction`, bounded `generationConfig`, and
    /// `functionDeclarations` tools; anything else is rejected with 400.
    static func geminiGenerate(requestBody: [String: Any]) async throws -> Data {
        try await post(path: "gemini", jsonBody: ["requestBody": requestBody])
    }

    static func transcribe(audioData: Data, mimeType: String, language: String?) async throws -> String {
        var body: [String: Any] = [
            "audio": audioData.base64EncodedString(),
            "mimeType": mimeType,
        ]
        if let language, !language.isEmpty {
            body["language"] = language
        }
        let data = try await post(path: "transcribe", jsonBody: body)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = json["text"] as? String else {
            throw HostedAIServiceError.invalidResponse
        }
        return text
    }

    /// Fetches the authoritative quota snapshot. `forceRefresh` makes the Worker
    /// re-verify entitlements with RevenueCat instead of using its cache.
    static func fetchQuota(forceRefresh: Bool) async throws -> HostedAIQuotaSnapshot {
        let path = forceRefresh ? "quota?refresh=1" : "quota"
        let data = try await send(method: "GET", path: path, jsonBody: nil)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let quotaJSON = json["quota"] as? [String: Any],
              let snapshot = HostedAIQuotaSnapshot(json: quotaJSON) else {
            throw HostedAIServiceError.invalidResponse
        }
        return snapshot
    }

    private static func post(path: String, jsonBody: [String: Any]) async throws -> Data {
        try await send(method: "POST", path: path, jsonBody: jsonBody)
    }

    private static func send(method: String, path: String, jsonBody: [String: Any]?) async throws -> Data {
        guard let url = URL(string: "\(HostedAIConstants.hostedAIBaseURL)/\(path)") else {
            throw HostedAIServiceError.invalidURL
        }

        let userID = await RevenueCatManager.shared.appUserID()

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(userID, forHTTPHeaderField: HostedAIConstants.hostedUserIDHeader)
        if let jsonBody {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: jsonBody)
        }

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw HostedAIServiceError.invalidResponse
        }

        if let snapshot = HostedAIQuotaManager.snapshot(fromHeaders: http.allHeaderFields) {
            await HostedAIQuotaManager.shared.apply(snapshot)
        }

        if (200...299).contains(http.statusCode) {
            return data
        }

        let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        let code = json["error"] as? String
        if let quotaJSON = json["quota"] as? [String: Any],
           let snapshot = HostedAIQuotaSnapshot(json: quotaJSON) {
            await HostedAIQuotaManager.shared.apply(snapshot)
        }

        switch http.statusCode {
        case 401:
            throw HostedAIServiceError.unauthorized
        case 402:
            let plan = await RevenueCatManager.shared.activePlan
            let quota = await HostedAIQuotaManager.shared.snapshot(plan: plan)
            throw HostedAIQuotaError.quotaExceeded(remainingDaily: quota.dailyRemaining, creditBank: quota.creditBank)
        case 403 where code == "subscription_required":
            throw HostedAIQuotaError.noActiveSubscription
        case 429:
            throw HostedAIQuotaError.rateLimited
        case 502, 503, 504:
            throw HostedAIServiceError.upstreamUnavailable
        default:
            throw HostedAIServiceError.serverError(
                Self.userFacingMessage(for: code, status: http.statusCode)
            )
        }
    }

    /// Worker error codes are short identifiers, never upstream text; map the
    /// ones a user can act on and fall back to a generic message.
    private static func userFacingMessage(for code: String?, status: Int) -> String {
        switch code {
        case "too_many_images":
            return String(localized: "Hosted AI accepts up to \(HostedAIConstants.maxHostedImages) photos per request.")
        case "image_too_large", "audio_too_large", "body_too_large", "prompt_too_long":
            return String(localized: "That request is too large for Hosted AI. Try a smaller photo or shorter text.")
        case "invalid_request_body":
            return String(localized: "Hosted AI rejected this request. Please update Fud AI and try again.")
        default:
            return String(localized: "Hosted AI request failed (\(status)).")
        }
    }
}
