import Testing
@testable import calorietracker

@Suite(.serialized)
struct HostedAIQuotaManagerTests {
    private let manager = HostedAIQuotaManager.shared
    private let defaults = UserDefaults.standard

    private func resetLedger() {
        defaults.removeObject(forKey: "hostedAI.dailyUsed")
        defaults.removeObject(forKey: "hostedAI.dailyResetDay")
        defaults.removeObject(forKey: "hostedAI.creditBank")
    }

    @Test func plusDailyLimitIs30() {
        #expect(HostedAIConstants.dailyLimit(for: .plus) == 30)
    }

    @Test func proDailyLimitIs60() {
        #expect(HostedAIConstants.dailyLimit(for: .pro) == 60)
    }

    @Test func voiceFoodCostsTwo() {
        #expect(HostedAIAction.voiceFood.cost == 2)
    }

    @Test func coachMessageCostsOne() {
        #expect(HostedAIAction.coachMessage.cost == 1)
    }

    @Test func spendOrderUsesDailyBeforeCredits() {
        resetLedger()
        manager.creditBank = 100
        manager.dailyUsed = 0

        let result = manager.spend(5, plan: .plus, hasEntitlement: true)
        guard case .spent(let fromDaily, let fromCredits) = result else {
            Issue.record("Expected spend success")
            return
        }
        #expect(fromDaily == 5)
        #expect(fromCredits == 0)
        #expect(manager.dailyUsed == 5)
        #expect(manager.creditBank == 100)
    }

    @Test func spendOverflowPullsFromCreditBank() {
        resetLedger()
        manager.creditBank = 50
        manager.dailyUsed = 28

        let result = manager.spend(5, plan: .plus, hasEntitlement: true)
        guard case .spent(let fromDaily, let fromCredits) = result else {
            Issue.record("Expected spend success")
            return
        }
        #expect(fromDaily == 2)
        #expect(fromCredits == 3)
        #expect(manager.creditBank == 47)
    }

    @Test func rejectsWhenDailyAndCreditsInsufficient() {
        resetLedger()
        manager.creditBank = 2
        manager.dailyUsed = 29

        let result = manager.spend(5, plan: .plus, hasEntitlement: true)
        guard case .rejected = result else {
            Issue.record("Expected rejection")
            return
        }
        #expect(manager.dailyUsed == 29)
        #expect(manager.creditBank == 2)
    }

    @Test func dailyResetsOnNewDay() {
        resetLedger()
        manager.dailyUsed = 12
        defaults.set("2026-01-01", forKey: "hostedAI.dailyResetDay")

        manager.resetIfNeeded(today: "2026-01-02")
        #expect(manager.dailyUsed == 0)
    }

    @Test func creditsNotSpendableWithoutEntitlement() {
        resetLedger()
        manager.creditBank = 200
        let result = manager.spend(1, plan: .plus, hasEntitlement: false)
        guard case .rejected = result else {
            Issue.record("Expected rejection without entitlement")
            return
        }
        #expect(manager.creditBank == 200)
    }

    @Test func proLimitHigherThanPlus() {
        resetLedger()
        #expect(manager.availableActions(plan: .pro, hasEntitlement: true) == 60)
        #expect(manager.availableActions(plan: .plus, hasEntitlement: true) == 30)
    }
}
