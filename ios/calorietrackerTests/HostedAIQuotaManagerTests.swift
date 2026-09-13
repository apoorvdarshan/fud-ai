import Foundation
import Testing
@testable import calorietracker

@Suite(.serialized)
@MainActor
struct HostedAIQuotaManagerTests {
    private func makeManager() -> (HostedAIQuotaManager, UserDefaults) {
        let suiteName = "HostedAIQuotaManagerTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return (HostedAIQuotaManager(defaults: defaults), defaults)
    }

    private func snapshot(
        plan: HostedPlan = .plus,
        day: String = "2026-09-13",
        used: Int = 4,
        limit: Int = 30,
        credits: Int = 12
    ) -> HostedAIQuotaSnapshot {
        HostedAIQuotaSnapshot(plan: plan, day: day, dailyUsed: used, dailyLimit: limit, creditBank: credits)
    }

    private func date(_ iso: String) -> Date {
        ISO8601DateFormatter().date(from: iso)!
    }

    @Test func plusDailyLimitIs30() {
        #expect(HostedAIConstants.dailyLimit(for: .plus) == 30)
    }

    @Test func proDailyLimitIs60() {
        #expect(HostedAIConstants.dailyLimit(for: .pro) == 60)
    }

    @Test func voiceFoodIsEstimatedAsTwoRoundTrips() {
        #expect(HostedAIAction.voiceFood.estimatedCost == 2)
        #expect(HostedAIAction.coachMessage.estimatedCost == 1)
    }

    @Test func clientShipsNoProxySecret() {
        // The Worker authenticates by RevenueCat app user id only; the only
        // hosted header constant the client knows about is the identity header.
        #expect(HostedAIConstants.hostedUserIDHeader == "X-Fud-User-Id")
    }

    @Test func dayKeyIsUTC() {
        // 23:30 UTC-8 on Sep 12 is already Sep 13 in UTC.
        let late = date("2026-09-12T23:30:00-08:00")
        #expect(HostedAIQuotaManager.utcDayKey(for: late) == "2026-09-13")
        #expect(HostedAIQuotaManager.utcDayKey(for: date("2026-01-01T00:00:00Z")) == "2026-01-01")
    }

    @Test func appliedSnapshotIsReturnedForSameDay() {
        let (manager, _) = makeManager()
        manager.apply(snapshot())
        let now = date("2026-09-13T12:00:00Z")
        #expect(manager.snapshot(plan: .plus, now: now) == snapshot())
        #expect(manager.snapshot(plan: .plus, now: now).dailyRemaining == 26)
        #expect(manager.snapshot(plan: .plus, now: now).availableActions == 38)
    }

    @Test func dailyUsageRollsOverOnUTCDayBoundary() {
        let (manager, _) = makeManager()
        manager.apply(snapshot(used: 30))
        let nextDay = date("2026-09-14T00:00:01Z")
        let rolled = manager.snapshot(plan: .plus, now: nextDay)
        #expect(rolled.day == "2026-09-14")
        #expect(rolled.dailyUsed == 0)
        #expect(rolled.creditBank == 12)
    }

    @Test func planMismatchFallsBackToPlanLimit() {
        let (manager, _) = makeManager()
        manager.apply(snapshot(plan: .plus, credits: 7))
        let pro = manager.snapshot(plan: .pro, now: date("2026-09-13T12:00:00Z"))
        #expect(pro.dailyLimit == 60)
        #expect(pro.dailyUsed == 0)
        #expect(pro.creditBank == 7)
    }

    @Test func snapshotPersistsAcrossInstancesAndLegacyKeysAreDropped() {
        let (manager, defaults) = makeManager()
        defaults.set(99, forKey: "hostedAI.creditBank")
        defaults.set(5, forKey: "hostedAI.dailyUsed")
        manager.apply(snapshot(credits: 3))

        let reloaded = HostedAIQuotaManager(defaults: defaults)
        #expect(reloaded.cached == snapshot(credits: 3))
        #expect(defaults.object(forKey: "hostedAI.creditBank") == nil)
        #expect(defaults.object(forKey: "hostedAI.dailyUsed") == nil)
    }

    @Test func parsesQuotaHeadersCaseInsensitively() {
        let headers: [AnyHashable: Any] = [
            "x-fud-quota-plan": "pro",
            "X-Fud-Quota-Day": "2026-09-13",
            "X-FUD-QUOTA-DAILY-USED": "7",
            "X-Fud-Quota-Daily-Limit": "60",
            "X-Fud-Quota-Credits": "150",
        ]
        let parsed = HostedAIQuotaManager.snapshot(fromHeaders: headers)
        #expect(parsed == snapshot(plan: .pro, used: 7, limit: 60, credits: 150))
    }

    @Test func ignoresIncompleteQuotaHeaders() {
        let headers: [AnyHashable: Any] = ["X-Fud-Quota-Plan": "plus"]
        #expect(HostedAIQuotaManager.snapshot(fromHeaders: headers) == nil)
    }

    @Test func decodesQuotaJSONFromWorker() {
        let json: [String: Any] = [
            "plan": "plus", "day": "2026-09-13", "dailyUsed": 2, "dailyLimit": 30, "creditBank": 40,
        ]
        #expect(HostedAIQuotaSnapshot(json: json) == snapshot(used: 2, credits: 40))
        #expect(HostedAIQuotaSnapshot(json: ["plan": "gold"]) == nil)
    }
}
