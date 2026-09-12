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

struct HostedAISpendReceipt: Equatable {
    let fromDaily: Int
    let fromCredits: Int
}

@MainActor
enum AIGate {
    static func requireHostedQuota(for action: HostedAIAction) throws {
        _ = try consumeIfHosted(action)
    }

    /// Consume quota only when in Hosted mode; returns a receipt for refund on failure.
    @discardableResult
    static func consumeIfHosted(_ action: HostedAIAction) throws -> HostedAISpendReceipt? {
        guard AIModeSettings.isHosted else { return nil }
        let manager = RevenueCatManager.shared
        guard manager.hasHostedEntitlement else {
            throw HostedAIQuotaError.noActiveSubscription
        }
        let result = HostedAIQuotaManager.shared.spend(
            action.cost,
            plan: manager.activePlan,
            hasEntitlement: true
        )
        switch result {
        case .spent(let fromDaily, let fromCredits):
            return HostedAISpendReceipt(fromDaily: fromDaily, fromCredits: fromCredits)
        case .rejected(let error):
            throw error
        }
    }

    static func refundHosted(_ receipt: HostedAISpendReceipt) {
        guard AIModeSettings.isHosted else { return }
        HostedAIQuotaManager.shared.refund(fromDaily: receipt.fromDaily, fromCredits: receipt.fromCredits)
    }

    static func runWithHostedQuota<T>(
        _ action: HostedAIAction,
        _ work: () async throws -> T
    ) async throws -> T {
        let receipt = try consumeIfHosted(action)
        do {
            return try await work()
        } catch {
            if error is CancellationError { throw error }
            if let receipt { refundHosted(receipt) }
            throw error
        }
    }
}
