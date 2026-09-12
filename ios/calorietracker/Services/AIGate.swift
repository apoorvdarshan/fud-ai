//
//  AIGate.swift
//  calorietracker
//

import Foundation

/// Central gate for BYOK vs Hosted AI mode and hosted quota consumption.
enum AIModeSettings {
    private static let modeKey = "aiAccessMode"

    static var mode: AIMode {
        get {
            guard let raw = UserDefaults.standard.string(forKey: modeKey),
                  let value = AIMode(rawValue: raw) else {
                return .byok
            }
            return value
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: modeKey) }
    }

    static var isHosted: Bool { mode == .hosted }
}

@MainActor
enum AIGate {
    static func requireHostedQuota(for action: HostedAIAction) throws {
        guard AIModeSettings.isHosted else { return }
        let manager = RevenueCatManager.shared
        guard manager.hasHostedEntitlement else {
            throw HostedAIQuotaError.noActiveSubscription
        }
        let result = HostedAIQuotaManager.shared.spend(
            action.cost,
            plan: manager.activePlan,
            hasEntitlement: true
        )
        if case .rejected(let error) = result {
            throw error
        }
    }

    /// Consume quota only when in Hosted mode; no-op for BYOK.
    static func consumeIfHosted(_ action: HostedAIAction) throws {
        try requireHostedQuota(for: action)
    }
}
