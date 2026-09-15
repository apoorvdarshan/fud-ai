import Foundation

/// Request/response details for single-shot Gemini `generateContent` food-analysis calls.
///
/// Without a `generationConfig` Gemini 3 models default to a high thinking level and free-form
/// text; on Flash-Lite that meant long, open-ended generations that kept the analyzing sheet up
/// until the request timed out (#357).
enum GeminiRequestConfiguration {
    /// Interactive food logging gets one quick retry on 429/503/529 instead of the 1s/2s/4s ladder,
    /// so an overloaded provider surfaces an actionable error instead of a minute-long spinner.
    static let interactiveRetryDelaysNs: [UInt64] = [1_500_000_000]

    /// `thinkingConfig.thinkingLevel` for Gemini 3-family models. `minimal` is the fastest level but
    /// is only accepted by Flash-Lite and the 3 / 3.5 / 3.6 Flash models; 3.7+ Flash and the Pro
    /// models reject it with a 400, so they get `low`. Pre-3 models (thinkingBudget only) and
    /// unrecognized ids return nil so nothing is sent.
    static func thinkingLevel(for model: String) -> String? {
        let id = model.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let bare = id.split(separator: "/").last.map(String.init) ?? id
        guard bare.hasPrefix("gemini-") else { return nil }
        let remainder = bare.dropFirst("gemini-".count)
        guard let dash = remainder.firstIndex(of: "-") else { return nil }
        let version = remainder[remainder.startIndex..<dash]
        let variant = String(remainder[remainder.index(after: dash)...])
        guard !variant.isEmpty else { return nil }

        let components = version.split(separator: ".", omittingEmptySubsequences: false)
        guard let major = components.first.flatMap({ Int($0) }) else { return nil }
        let minor = components.count > 1 ? Int(components[1]) ?? 0 : 0
        guard major >= 3 else { return nil }

        if variant.hasPrefix("flash-lite") { return "minimal" }
        if variant.hasPrefix("flash") {
            return (major > 3 || minor >= 7) ? "low" : "minimal"
        }
        return "low"
    }

    /// `generationConfig` for a food-analysis request, or nil when there is nothing to send.
    static func generationConfig(model: String, maxOutputTokens: Int, jsonResponse: Bool) -> [String: Any]? {
        var config: [String: Any] = [:]
        if maxOutputTokens > 0 {
            config["maxOutputTokens"] = maxOutputTokens
        }
        if jsonResponse {
            config["responseMimeType"] = "application/json"
        }
        if let level = thinkingLevel(for: model) {
            config["thinkingConfig"] = ["thinkingLevel": level]
        }
        return config.isEmpty ? nil : config
    }

    struct TextResponse {
        let text: String?
        let finishReason: String?

        var wasTruncated: Bool { finishReason == "MAX_TOKENS" }
    }

    /// Concatenates every text part of the first candidate. Thinking models may return several
    /// parts (and `thought: true` summaries) before the answer, so reading only `parts.first`
    /// turned perfectly good responses into `invalidResponse`.
    static func parseTextResponse(from data: Data) throws -> TextResponse {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let first = candidates.first
        else { throw GeminiService.AnalysisError.invalidResponse }

        let finishReason = (first["finishReason"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let parts = (first["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
        let combined = parts
            .filter { !(($0["thought"] as? Bool) ?? false) }
            .compactMap { $0["text"] as? String }
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if combined.isEmpty && finishReason != "MAX_TOKENS" {
            throw GeminiService.AnalysisError.invalidResponse
        }
        return TextResponse(text: combined.isEmpty ? nil : combined, finishReason: finishReason)
    }

    static func compactRetryPrompt(_ prompt: String, maxOutputTokens: Int, jsonResponse: Bool = true) -> String {
        let budget = maxOutputTokens > 0 ? " Keep the complete response under \(maxOutputTokens) tokens." : ""
        let shape = jsonResponse
            ? "Return only the requested compact JSON object, with no reasoning, explanation, or markdown."
            : "Return only the requested concise plain-English answer, with no reasoning, explanation, JSON, or markdown."
        return """
        \(prompt)

        IMPORTANT: The previous response was truncated. \(shape)\(budget)
        """
    }
}
