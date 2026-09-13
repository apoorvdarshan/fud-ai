//
//  HostedAIQuotaManager.swift
//  calorietracker
//

import Foundation

/// Client-side *view* of the hosted quota. The Worker owns the ledger (daily
/// pool + credit bank, keyed by RevenueCat app user id) and returns the
/// current numbers on every hosted response; this type only caches the latest
/// snapshot for display. Nothing here grants or spends quota, so reinstalls,
/// backups, or edited preferences cannot change what the server enforces.
@MainActor
@Observable
final class HostedAIQuotaManager {
    static let shared = HostedAIQuotaManager()

    private let defaults: UserDefaults
    private let snapshotKey = "hostedAI.serverQuotaSnapshot.v2"

    /// Keys used by the pre-server-ledger client and no longer read.
    private static let legacyKeys = ["hostedAI.dailyUsed", "hostedAI.dailyResetDay", "hostedAI.creditBank"]

    private(set) var cached: HostedAIQuotaSnapshot?
    private(set) var isRefreshing = false

    /// The refresh currently talking to the Worker, if any. Concurrent callers
    /// coalesce onto it instead of issuing duplicate requests.
    private var inFlight: Task<Void, Never>?
    /// Monotonic id assigned to every refresh run; `lastForcedRunID` is the id
    /// of the most recent run that asked the Worker to re-verify RevenueCat.
    private var runCounter = 0
    private var lastForcedRunID = 0

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        for key in Self.legacyKeys { defaults.removeObject(forKey: key) }
        if let data = defaults.data(forKey: snapshotKey),
           let snapshot = try? JSONDecoder().decode(HostedAIQuotaSnapshot.self, from: data) {
            cached = snapshot
        }
    }

    /// Latest known numbers for `plan`, rolled over to zero daily usage when
    /// the cached snapshot belongs to an earlier UTC day.
    func snapshot(plan: HostedPlan, now: Date = Date()) -> HostedAIQuotaSnapshot {
        let today = Self.utcDayKey(for: now)
        guard let cached, cached.plan == plan else {
            return HostedAIQuotaSnapshot(
                plan: plan,
                day: today,
                dailyUsed: 0,
                dailyLimit: HostedAIConstants.dailyLimit(for: plan),
                creditBank: cached?.creditBank ?? 0
            )
        }
        if cached.day == today { return cached }
        return HostedAIQuotaSnapshot(
            plan: cached.plan,
            day: today,
            dailyUsed: 0,
            dailyLimit: cached.dailyLimit,
            creditBank: cached.creditBank
        )
    }

    /// Records the snapshot returned by the Worker (response headers or `/quota`).
    func apply(_ snapshot: HostedAIQuotaSnapshot) {
        cached = snapshot
        if let data = try? JSONEncoder().encode(snapshot) {
            defaults.set(data, forKey: snapshotKey)
        }
    }

    func clear() {
        cached = nil
        defaults.removeObject(forKey: snapshotKey)
    }

    /// Pulls the authoritative snapshot from the Worker. `force` asks the
    /// Worker to re-verify entitlements with RevenueCat immediately (used right
    /// after a purchase or restore so new credits show up without waiting for
    /// the server-side cache TTL).
    ///
    /// Concurrent calls coalesce onto the in-flight request. A forced call is
    /// never dropped: it is satisfied by a forced run that is already in flight,
    /// otherwise it waits for the current (non-forced) run and then issues its
    /// own forced request, so a purchase completing during a routine refresh
    /// still gets re-verified.
    func refresh(force: Bool = false) async {
        let arrivalRunID = runCounter
        while let current = inFlight {
            await current.value
            if !force || lastForcedRunID >= arrivalRunID { return }
            // A non-forced run finished (or a forced one that predates us was
            // not in flight); another waiter may have started the forced run
            // we need, so re-check before starting our own.
        }
        await run(forced: force)
    }

    private func run(forced: Bool) async {
        runCounter += 1
        if forced { lastForcedRunID = runCounter }
        isRefreshing = true
        // The task body runs on the main actor and clears `inFlight` before it
        // completes, so waiters resuming from `inFlight.value` observe the
        // slot as free (or already reused) rather than a finished task.
        let task = Task { @MainActor [self] in
            if let snapshot = try? await HostedAIService.fetchQuota(forceRefresh: forced) {
                apply(snapshot)
            }
            inFlight = nil
            isRefreshing = false
        }
        inFlight = task
        await task.value
    }

    /// Parses the `X-Fud-Quota-*` headers the Worker attaches to every hosted response.
    nonisolated static func snapshot(fromHeaders headers: [AnyHashable: Any]) -> HostedAIQuotaSnapshot? {
        func header(_ name: String) -> String? {
            for (key, value) in headers {
                if let key = key as? String, key.caseInsensitiveCompare(name) == .orderedSame {
                    return value as? String
                }
            }
            return nil
        }
        guard let planRaw = header("X-Fud-Quota-Plan"),
              let plan = HostedPlan(rawValue: planRaw),
              let day = header("X-Fud-Quota-Day"),
              let used = header("X-Fud-Quota-Daily-Used").flatMap(Int.init),
              let limit = header("X-Fud-Quota-Daily-Limit").flatMap(Int.init),
              let credits = header("X-Fud-Quota-Credits").flatMap(Int.init)
        else { return nil }
        return HostedAIQuotaSnapshot(plan: plan, day: day, dailyUsed: used, dailyLimit: limit, creditBank: credits)
    }

    /// Day key matching the Worker's ledger (UTC, `YYYY-MM-DD`).
    nonisolated static func utcDayKey(for date: Date = Date()) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        let comps = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", comps.year ?? 0, comps.month ?? 0, comps.day ?? 0)
    }
}

struct HostedAIQuotaSnapshot: Equatable, Codable {
    let plan: HostedPlan
    /// UTC day (`YYYY-MM-DD`) the daily counter applies to.
    let day: String
    let dailyUsed: Int
    let dailyLimit: Int
    let creditBank: Int

    var dailyRemaining: Int { max(0, dailyLimit - dailyUsed) }
    var availableActions: Int { dailyRemaining + creditBank }

    init(plan: HostedPlan, day: String, dailyUsed: Int, dailyLimit: Int, creditBank: Int) {
        self.plan = plan
        self.day = day
        self.dailyUsed = dailyUsed
        self.dailyLimit = dailyLimit
        self.creditBank = creditBank
    }

    /// Decodes the `quota` object in Worker JSON responses.
    init?(json: [String: Any]) {
        guard let planRaw = json["plan"] as? String,
              let plan = HostedPlan(rawValue: planRaw),
              let day = json["day"] as? String,
              let dailyUsed = json["dailyUsed"] as? Int,
              let dailyLimit = json["dailyLimit"] as? Int,
              let creditBank = json["creditBank"] as? Int
        else { return nil }
        self.init(plan: plan, day: day, dailyUsed: dailyUsed, dailyLimit: dailyLimit, creditBank: creditBank)
    }
}
