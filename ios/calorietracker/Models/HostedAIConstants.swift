//
//  HostedAIConstants.swift
//  calorietracker
//
//  Product IDs, entitlements, and hosted AI limits for Fud AI v7.
//

import Foundation

enum HostedAIConstants {
    // MARK: - Entitlements (RevenueCat)

    static let plusEntitlementID = "plus"
    static let proEntitlementID = "pro"

    // MARK: - Subscriptions

    static let plusMonthlyProductID = "com.apoorvdarshan.calorietracker.plus.monthly"
    static let plusYearlyProductID = "com.apoorvdarshan.calorietracker.plus.yearly"
    static let proMonthlyProductID = "com.apoorvdarshan.calorietracker.pro.monthly"
    static let proYearlyProductID = "com.apoorvdarshan.calorietracker.pro.yearly"

    static let subscriptionProductIDs: [String] = [
        plusMonthlyProductID,
        plusYearlyProductID,
        proMonthlyProductID,
        proYearlyProductID,
    ]

    // MARK: - Credit packs (consumables)

    static let credits50ProductID = "com.apoorvdarshan.calorietracker.credits.50"
    static let credits150ProductID = "com.apoorvdarshan.calorietracker.credits.150"
    static let credits400ProductID = "com.apoorvdarshan.calorietracker.credits.400"

    static let creditProductIDs: [String] = [
        credits50ProductID,
        credits150ProductID,
        credits400ProductID,
    ]

    static func creditAmount(for productID: String) -> Int? {
        switch productID {
        case credits50ProductID: 50
        case credits150ProductID: 150
        case credits400ProductID: 400
        default: nil
        }
    }

    // MARK: - Tips (iOS — unchanged; see TipJarView.swift)

    static let tipProductIDs: [String] = [
        "com.apoorvdarshan.calorietracker.tip.snack",
        "com.apoorvdarshan.calorietracker.tip.proteinshake",
        "com.apoorvdarshan.calorietracker.tip.lunch",
        "com.apoorvdarshan.calorietracker.tip.feast",
    ]

    // MARK: - Daily limits

    static let plusDailyLimit = 30
    static let proDailyLimit = 60

    static func dailyLimit(for plan: HostedPlan) -> Int {
        switch plan {
        case .pro: proDailyLimit
        case .plus: plusDailyLimit
        case .none: 0
        }
    }

    // MARK: - Hosted proxy

    /// Production hosted path on fud-ai.app. Override in debug via UserDefaults if needed.
    static let hostedAIBaseURL = "https://fud-ai.app/api/hosted-ai/v1"

    /// Shared app secret — set the same value in Worker env `FUD_HOSTED_AI_APP_SECRET`.
    /// v1 client-side gating; rotate with a worker + app update together.
    static let hostedAIAppSecret = "ceFjlmDVmRQqAWB900qNv6uFahLGJekHpT1bvo1xsuM"

    static let maxHostedImages = 3
}

enum HostedPlan: String, Codable, CaseIterable {
    case none
    case plus
    case pro

    var displayName: String {
        switch self {
        case .none: String(localized: "None")
        case .plus: String(localized: "Plus")
        case .pro: String(localized: "Pro")
        }
    }
}

enum AIMode: String, CaseIterable, Identifiable {
    case byok
    case hosted

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .byok: String(localized: "BYOK")
        case .hosted: String(localized: "Hosted (Plus/Pro)")
        }
    }
}

enum HostedAIAction: Equatable {
    case photoFood
    case textFood
    case voiceFood
    case ingredientAI
    case reprocessMeal
    case whatIf
    case allergensLab
    case siriFood
    case coachMessage
    case workoutAI
    case manualRecalculateGoals(llmCalls: Int)
    case hostedSTT

    var cost: Int {
        switch self {
        case .voiceFood: 2
        case .manualRecalculateGoals(let llmCalls): max(1, llmCalls)
        default: 1
        }
    }

    /// Adaptive Goals and onboarding first calc are never metered.
    var isMetered: Bool { true }
}

enum HostedAIQuotaError: LocalizedError, Equatable {
    case notHostedMode
    case noActiveSubscription
    case quotaExceeded(remainingDaily: Int, creditBank: Int)

    var errorDescription: String? {
        switch self {
        case .notHostedMode:
            return nil
        case .noActiveSubscription:
            return String(localized: "Subscribe to Plus or Pro to use Hosted AI, or switch to BYOK in Settings → AI Access.")
        case .quotaExceeded:
            return String(localized: "You're out of hosted AI actions for today. Buy credits, upgrade, or switch to BYOK.")
        }
    }
}
