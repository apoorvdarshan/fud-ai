//
//  HostedAIQuotaManager.swift
//  calorietracker
//

import Foundation

/// Local quota ledger for Hosted mode. Daily pool resets at local midnight;
/// credit bank persists across renewals/cancel but is spendable only while
/// Plus/Pro is active (v1).
@MainActor
final class HostedAIQuotaManager {
    static let shared = HostedAIQuotaManager()

    private let defaults = UserDefaults.standard
    private let dailyUsedKey = "hostedAI.dailyUsed"
    private let dailyResetDayKey = "hostedAI.dailyResetDay"
    private let creditBankKey = "hostedAI.creditBank"

    private init() {}

    var creditBank: Int {
        get { defaults.integer(forKey: creditBankKey) }
        set { defaults.set(max(0, newValue), forKey: creditBankKey) }
    }

    var dailyUsed: Int {
        get { defaults.integer(forKey: dailyUsedKey) }
        set { defaults.set(max(0, newValue), forKey: dailyUsedKey) }
    }

    func resetIfNeeded(today: String = Self.localDayKey()) {
        let stored = defaults.string(forKey: dailyResetDayKey)
        if stored != today {
            dailyUsed = 0
            defaults.set(today, forKey: dailyResetDayKey)
        }
    }

    func snapshot(plan: HostedPlan) -> HostedAIQuotaSnapshot {
        resetIfNeeded()
        let limit = HostedAIConstants.dailyLimit(for: plan)
        return HostedAIQuotaSnapshot(
            dailyUsed: dailyUsed,
            dailyLimit: limit,
            creditBank: creditBank,
            plan: plan
        )
    }

    /// Returns how many actions can still be spent (daily remainder + credits when entitled).
    func availableActions(plan: HostedPlan, hasEntitlement: Bool) -> Int {
        resetIfNeeded()
        guard hasEntitlement else { return 0 }
        let dailyRemaining = max(0, HostedAIConstants.dailyLimit(for: plan) - dailyUsed)
        return dailyRemaining + creditBank
    }

    func canSpend(_ cost: Int, plan: HostedPlan, hasEntitlement: Bool) -> Bool {
        availableActions(plan: plan, hasEntitlement: hasEntitlement) >= cost
    }

    @discardableResult
    func spend(_ cost: Int, plan: HostedPlan, hasEntitlement: Bool) -> HostedAISpendResult {
        resetIfNeeded()
        guard hasEntitlement else {
            return .rejected(.noActiveSubscription)
        }
        guard cost > 0 else { return .spent(fromDaily: 0, fromCredits: 0) }

        var remaining = cost
        let limit = HostedAIConstants.dailyLimit(for: plan)
        let dailyRemaining = max(0, limit - dailyUsed)
        let fromDaily = min(dailyRemaining, remaining)
        dailyUsed += fromDaily
        remaining -= fromDaily

        var fromCredits = 0
        if remaining > 0 {
            if creditBank < remaining {
                // Roll back daily spend on failure
                dailyUsed -= fromDaily
                let snap = snapshot(plan: plan)
                return .rejected(.quotaExceeded(remainingDaily: snap.dailyRemaining, creditBank: snap.creditBank))
            }
            fromCredits = remaining
            creditBank -= fromCredits
            remaining = 0
        }

        return .spent(fromDaily: fromDaily, fromCredits: fromCredits)
    }

    func addCredits(_ amount: Int) {
        guard amount > 0 else { return }
        creditBank += amount
    }

    func refund(fromDaily: Int, fromCredits: Int) {
        resetIfNeeded()
        if fromDaily > 0 {
            dailyUsed = max(0, dailyUsed - fromDaily)
        }
        if fromCredits > 0 {
            creditBank += fromCredits
        }
    }

    static func localDayKey(for date: Date = Date(), calendar: Calendar = .current) -> String {
        let comps = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", comps.year ?? 0, comps.month ?? 0, comps.day ?? 0)
    }
}

struct HostedAIQuotaSnapshot: Equatable {
    let dailyUsed: Int
    let dailyLimit: Int
    let creditBank: Int
    let plan: HostedPlan

    var dailyRemaining: Int { max(0, dailyLimit - dailyUsed) }
}

enum HostedAISpendResult: Equatable {
    case spent(fromDaily: Int, fromCredits: Int)
    case rejected(HostedAIQuotaError)
}
