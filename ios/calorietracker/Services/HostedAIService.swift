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

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid hosted AI URL."
        case .unauthorized: return "Hosted AI authentication failed."
        case .serverError(let msg): return msg
        case .invalidResponse: return "Could not understand the hosted AI response."
        }
    }
}

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

    private static func post(path: String, jsonBody: [String: Any]) async throws -> Data {
        guard let url = URL(string: "\(HostedAIConstants.hostedAIBaseURL)/\(path)") else {
            throw HostedAIServiceError.invalidURL
        }

        let userID = await RevenueCatManager.shared.appUserID()
        let plan = await RevenueCatManager.shared.activePlan.rawValue

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(HostedAIConstants.hostedAIAppSecret)", forHTTPHeaderField: "Authorization")
        request.setValue(userID, forHTTPHeaderField: "X-Fud-User-Id")
        request.setValue(plan, forHTTPHeaderField: "X-Fud-Plan")
        request.httpBody = try JSONSerialization.data(withJSONObject: jsonBody)

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw HostedAIServiceError.invalidResponse
        }
        if http.statusCode == 401 {
            throw HostedAIServiceError.unauthorized
        }
        if !(200...299).contains(http.statusCode) {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw HostedAIServiceError.serverError(message ?? "Hosted AI request failed (\(http.statusCode)).")
        }
        return data
    }
}
