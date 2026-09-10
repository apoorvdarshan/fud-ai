import Foundation

/// Stable categories keep remote response bodies out of user-facing messages.
enum AIErrorKind: String, CaseIterable {
    case noKey = "ai.error.no_key"
    case imageConversion = "ai.error.image_conversion"
    case invalidResponse = "ai.error.invalid_response"
    case invalidURL = "ai.error.invalid_url"
    case offline = "ai.error.offline"
    case connection = "ai.error.connection"
    case timeout = "ai.error.timeout"
    case credits = "ai.error.credits"
    case dailyQuota = "ai.error.daily_quota"
    case quota = "ai.error.quota"
    case modelUnavailable = "ai.error.model"
    case rateLimited = "ai.error.rate_limit"
    case keyRejected = "ai.error.key_rejected"
    case overloaded = "ai.error.overloaded"
    case generic = "ai.error.generic"
    case localUnavailable = "ai.error.local_unavailable"
    case unsupportedDevice = "ai.error.unsupported_device"
    case truncated = "ai.error.truncated"
    case textOnly = "ai.error.text_only"

    var defaultMessage: String {
        switch self {
        case .noKey: "No API key configured. Add your key in Settings → AI Provider."
        case .imageConversion: "Failed to process the image. Try another photo."
        case .invalidResponse: "Could not understand the AI response. Please try again."
        case .invalidURL: "Invalid API URL. Check your provider settings."
        case .offline: "You appear to be offline. Check your connection and try again."
        case .connection: "Could not connect to the AI provider. Check your connection and provider URL, then try again."
        case .timeout: "The AI took too long to respond. Try again, or raise the request timeout in Settings → AI Provider."
        case .credits: "Your AI account is out of credits. Top up, or switch provider in Settings → AI Provider."
        case .dailyQuota: "Your AI account’s daily quota is used up. Try again tomorrow, or switch provider in Settings → AI Provider."
        case .quota: "Your AI account’s usage quota was exceeded. Check your provider’s limits, or switch provider in Settings → AI Provider."
        case .modelUnavailable: "The selected model or API endpoint is unavailable. Check the model and API URL in Settings → AI Provider."
        case .rateLimited: "Rate limit hit on your API key. Wait a minute, or switch provider in Settings → AI Provider. If you use a free tier, check whether its daily quota is used up."
        case .keyRejected: "Your API key was rejected. Open Settings → AI Provider and re-paste a valid key."
        case .overloaded: "The AI provider is overloaded right now. We retried a few times — try again in a minute, or switch provider/model in Settings → AI Provider."
        case .generic: "The AI request failed. Try again, or switch provider in Settings → AI Provider."
        case .localUnavailable: "The on-device AI runtime is unavailable. Check the downloaded model in Settings → AI Provider."
        case .unsupportedDevice: "Apple Intelligence requires iOS 26 or later on a supported iPhone."
        case .truncated: "The AI response was truncated twice. Try a shorter input or another model."
        case .textOnly: "Apple Intelligence is available for text-only requests."
        }
    }
    var message: String {
        NSLocalizedString(rawValue, tableName: "AIErrorMessages", bundle: .main, value: defaultMessage, comment: "Actionable AI request error")
    }

    static func classify(status: Int, raw: String) -> AIErrorKind {
        let text = raw.lowercased()
        func has(_ markers: String...) -> Bool { markers.contains { text.contains($0) } }
        if status == 401 || status == 403 || (status == 400 && has("api key not valid", "api_key_invalid", "api key expired", "api_key_expired")) { return .keyRejected }
        if status == 402 || has("insufficient credits", "insufficient credit", "credit balance is too low", "out of credits", "billing_hard_limit_reached") { return .credits }
        if has("quota", "limit", "resource_exhausted") && has("daily", "per day", "per_day", "perday", "requestsperday") { return .dailyQuota }
        if has("quota exceeded", "quota_exceeded", "exceeded your current quota", "insufficient_quota") { return .quota }
        if status == 404 { return .modelUnavailable }
        if status == 429 { return .rateLimited }
        if status == 503 || status == 529 { return .overloaded }
        return .generic
    }

    static func network(_ error: Error) -> AIErrorKind {
        let nsError = error as NSError
        guard nsError.domain == NSURLErrorDomain else { return .connection }
        switch nsError.code {
        case URLError.timedOut.rawValue: return .timeout
        case URLError.notConnectedToInternet.rawValue, URLError.cannotFindHost.rawValue, URLError.dnsLookupFailed.rawValue: return .offline
        default: return .connection
        }
    }
}

struct AnalysisFallbackError: LocalizedError {
    let primaryName: String
    let fallbackName: String
    let detail: String

    var errorDescription: String? {
        let format = NSLocalizedString("ai.error.both_failed", tableName: "AIErrorMessages", bundle: .main,
            value: "%1$@ and fallback %2$@ both failed. %3$@", comment: "Both AI providers failed; provider names and actionable error")
        return String(format: format, primaryName, fallbackName, detail)
    }
}
