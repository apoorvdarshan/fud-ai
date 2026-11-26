import Foundation
import Observation

enum WeeklyChallengeSessionPolicy {
    static func shouldClearLocalIdentity(after error: Error) -> Bool {
        guard let apiError = error as? WeeklyChallengeAPIError,
              case .server(let statusCode, _, _) = apiError
        else {
            return false
        }
        return statusCode == 401
    }

    static func deletionIsComplete(statusCode: Int) -> Bool {
        statusCode == 401 || statusCode == 404
    }
}

struct WeeklyChallengeLeaderboardCache: Codable, Equatable {
    static let pageSize = 20
    static let freshFor: TimeInterval = 60
    struct Entry: Codable, Equatable {
        let response: WeeklyChallengeLeaderboardResponse
        let savedAt: Date
    }

    private(set) var entries: [String: Entry] = [:]

    static func key(category: WeeklyChallengeCategory, weekStart: String, offset: Int = 0, period: ChallengePeriod = .weekly) -> String {
        let prefix = (period == .weekly ? "" : "\(period.rawValue)|") + "\(weekStart)|\(category.rawValue)"
        return offset == 0 ? prefix : "\(prefix)|\(offset)"
    }

    func entry(category: WeeklyChallengeCategory, weekStart: String, offset: Int = 0, period: ChallengePeriod = .weekly) -> Entry? {
        entries[Self.key(category: category, weekStart: weekStart, offset: offset, period: period)]
    }

    mutating func insert(_ response: WeeklyChallengeLeaderboardResponse, savedAt: Date) {
        entries[Self.key(category: response.category, weekStart: response.weekStart, offset: response.offset ?? 0, period: response.period ?? .weekly)] = Entry(
            response: response,
            savedAt: savedAt
        )
        if entries.count > 30 {
            let retainedKeys = Set(
                entries.sorted { $0.value.savedAt > $1.value.savedAt }
                    .prefix(30)
                    .map { $0.key }
            )
            entries = entries.filter { retainedKeys.contains($0.key) }
        }
    }

    var newestEntry: Entry? {
        entries.values.filter {
            ($0.response.offset ?? 0) == 0 && ($0.response.period ?? .weekly) == .weekly
        }.max { $0.savedAt < $1.savedAt }
    }

    mutating func remove(period: ChallengePeriod) {
        entries = entries.filter { $0.value.response.period != period }
    }

    /// Keep the last visible rankings for offline use, but don't serve them as fresh after a score write.
    mutating func markStale(period: ChallengePeriod, start: String) {
        for key in Array(entries.keys) {
            guard let entry = entries[key], entry.response.period == period,
                  entry.response.weekStart == start else { continue }
            entries[key] = Entry(response: entry.response, savedAt: .distantPast)
        }
    }

    var knownParticipants: [WeeklyChallengeParticipant] {
        var participants: [String: WeeklyChallengeParticipant] = [:]
        for entry in entries.values {
            for participant in entry.response.rankings {
                participants[participant.participantId] = participant
            }
            if let viewer = entry.response.viewer {
                participants[viewer.participantId] = viewer
            }
        }
        return Array(participants.values)
    }
}

@Observable
@MainActor
final class WeeklyChallengeStore {
    private struct CachedLeaderboard: Codable {
        let response: WeeklyChallengeLeaderboardResponse
        let savedAt: Date
    }

    private static let tokenKey = "weeklyChallenge.bearerToken.v1"
    private static let participantIDKey = "weeklyChallenge.participantID.v1"
    private static let publicProfileKey = "weeklyChallenge.publicProfile.v1"
    private static let cachedLeaderboardKey = "weeklyChallenge.cachedLeaderboard.v1"
    private static let pendingScoreKey = "weeklyChallenge.pendingScore.v1"
    private static let pendingDeletionKey = "weeklyChallenge.pendingDeletion.v1"
    private static let blockedParticipantIDsKey = "weeklyChallenge.blockedParticipantIDs.v1"
    private static let enabledPeriodsKey = "challenge.periodConsent.v1"
    private static let sharedMembershipKey = "challenge.sharedMembershipParticipant.v1"

    private(set) var leaderboard: WeeklyChallengeLeaderboardResponse?
    private(set) var publicProfile: WeeklyChallengePublicProfile?
    private(set) var participantID: String?
    private(set) var lastUpdated: Date?
    private(set) var isRefreshing = false
    private(set) var isProfileMutationInProgress = false
    private(set) var isSubmittingReport = false
    private(set) var isOffline = false
    private(set) var errorMessage: String?
    private(set) var hasPendingDeletion = false
    private(set) var blockedParticipantIDs: Set<String> = []
    private(set) var enabledPeriods: Set<ChallengePeriod> = []
    private(set) var busyPeriod: ChallengePeriod?
    private(set) var refreshingPeriod: ChallengePeriod?

    private let defaults: UserDefaults
    private let api: WeeklyChallengeAPIClient
    private var pendingScore: WeeklyChallengeScore?
    private var leaderboardCache = WeeklyChallengeLeaderboardCache()
    private var refreshGeneration = 0
    private var publishedPeriodScores: [ChallengePeriod: ChallengePeriodScore] = [:]
    private var periodRefreshGeneration = 0
    private var periodMutationTails: [ChallengePeriod: Task<Bool, Never>] = [:]

    init(
        defaults: UserDefaults = .standard,
        api: WeeklyChallengeAPIClient = WeeklyChallengeAPIClient()
    ) {
        self.defaults = defaults
        self.api = api
        participantID = defaults.string(forKey: Self.participantIDKey)
        publicProfile = Self.decode(
            WeeklyChallengePublicProfile.self,
            from: defaults.data(forKey: Self.publicProfileKey)
        )
        pendingScore = Self.decode(
            WeeklyChallengeScore.self,
            from: defaults.data(forKey: Self.pendingScoreKey)
        )
        hasPendingDeletion = KeychainHelper.load(key: Self.pendingDeletionKey) == "1"
        blockedParticipantIDs = Set(
            defaults.stringArray(forKey: Self.blockedParticipantIDsKey) ?? []
        )
        enabledPeriods = Set((defaults.stringArray(forKey: Self.enabledPeriodsKey) ?? []).compactMap(ChallengePeriod.init(rawValue:)))
        if let cache = Self.decode(
            WeeklyChallengeLeaderboardCache.self,
            from: defaults.data(forKey: Self.cachedLeaderboardKey)
        ) {
            leaderboardCache = cache
            if let newest = cache.newestEntry {
                leaderboard = newest.response
                lastUpdated = Self.serverDate(newest.response.updatedAt) ?? newest.savedAt
            }
        } else if let legacy = Self.decode(
            CachedLeaderboard.self,
            from: defaults.data(forKey: Self.cachedLeaderboardKey)
        ) {
            leaderboardCache.insert(legacy.response, savedAt: legacy.savedAt)
            leaderboard = legacy.response
            lastUpdated = Self.serverDate(legacy.response.updatedAt) ?? legacy.savedAt
            persist(leaderboardCache, key: Self.cachedLeaderboardKey)
        }

        // A public id without its private bearer cannot update or delete the
        // profile. Drop the incomplete local identity instead of presenting it
        // as a recoverable account.
        if hasPendingDeletion {
            if bearerToken == nil {
                KeychainHelper.delete(key: Self.pendingDeletionKey)
                hasPendingDeletion = false
                clearLocalIdentity(deleteCredential: false)
            }
        } else if participantID != nil, bearerToken == nil {
            clearLocalIdentity()
        } else if participantID == nil, bearerToken != nil {
            KeychainHelper.delete(key: Self.tokenKey)
        }
    }

    var isJoined: Bool {
        !hasPendingDeletion && participantID != nil && bearerToken != nil
    }

    var viewer: WeeklyChallengeParticipant? {
        if let viewer = leaderboard?.viewer { return viewer }
        guard let participantID else { return nil }
        return leaderboard?.rankings.first {
            $0.participantId == participantID || $0.isViewer
        }
    }

    var knownParticipants: [WeeklyChallengeParticipant] {
        leaderboardCache.knownParticipants
    }
    var supportsExpandedPeriods: Bool { leaderboard?.period == .weekly }

    func leaderboardFor(
        category: WeeklyChallengeCategory,
        weekStart: String,
        offset: Int = 0,
        period: ChallengePeriod = .weekly
    ) -> WeeklyChallengeLeaderboardResponse? {
        if let leaderboard,
           offset == 0,
           period == .weekly,
           leaderboard.category == category,
           leaderboard.weekStart == weekStart {
            return leaderboard
        }
        return leaderboardCache.entry(category: category, weekStart: weekStart, offset: offset, period: period)?.response
    }

    /// Paging only reads the public board; it never reuploads a score.
    @discardableResult
    func loadPage(category: WeeklyChallengeCategory, weekStart: String, offset: Int,
                  period: ChallengePeriod = .weekly, force: Bool = false) async -> Bool {
        guard offset >= 0, isJoined, let token = bearerToken,
              period == .weekly || enabledPeriods.contains(period) else { return false }
        if !force, let cached = leaderboardCache.entry(category: category, weekStart: weekStart, offset: offset, period: period),
           Date.now.timeIntervalSince(cached.savedAt) < WeeklyChallengeLeaderboardCache.freshFor {
            return true
        }
        do {
            let response = try await api.leaderboard(category: category, weekStart: weekStart, offset: offset, period: period, token: token)
            guard !Task.isCancelled, isJoined, bearerToken == token,
                  period == .weekly || enabledPeriods.contains(period),
                  (response.period ?? .weekly) == period,
                  response.category == category, response.weekStart == weekStart, response.offset == offset else { return false }
            persistLeaderboard(response)
            return true
        } catch {
            return false
        }
    }

    func refreshPeriod(category: WeeklyChallengeCategory, score: ChallengePeriodScore, accept: Bool = false, force: Bool = false) async {
        let period = score.period
        guard period != .weekly, (accept || enabledPeriods.contains(period)),
              !Task.isCancelled, isJoined else { return }
        if accept {
            guard busyPeriod == nil else { return }
            busyPeriod = period
            let joined = await publishPeriodScore(score, accept: true)
            busyPeriod = nil
            guard joined else { return }
        }
        guard !Task.isCancelled else { return }
        periodRefreshGeneration += 1
        let generation = periodRefreshGeneration
        refreshingPeriod = period
        defer {
            if generation == periodRefreshGeneration { refreshingPeriod = nil }
        }
        if !accept {
            // Join any in-flight write for this board before using its cache or requesting rankings.
            _ = await publishPeriodScore(score, accept: false)
        }
        guard !Task.isCancelled, generation == periodRefreshGeneration else { return }
        let loaded = await loadPage(category: category, weekStart: score.periodStart, offset: 0, period: period, force: force)
        guard !Task.isCancelled, generation == periodRefreshGeneration else { return }
        isOffline = !loaded && leaderboardFor(category: category, weekStart: score.periodStart, period: period) != nil
        if loaded { errorMessage = nil }
    }

    func syncEnabledPeriodScores(_ scores: [ChallengePeriodScore]) async {
        for score in scores {
            guard !Task.isCancelled else { return }
            _ = await publishPeriodScore(score, accept: false)
        }
    }

    private func publishPeriodScore(_ score: ChallengePeriodScore, accept: Bool) async -> Bool {
        let period = score.period
        guard period != .weekly, (accept || enabledPeriods.contains(period)),
              isJoined, let token = bearerToken else { return false }
        return await serializePeriodMutation(period) {
            guard self.isJoined, token == self.bearerToken,
                  accept || self.enabledPeriods.contains(period) else { return false }
            if !accept, self.publishedPeriodScores[period] == score { return true }
            do {
                try await self.api.putPeriodScore(score, token: token)
                guard self.isJoined, token == self.bearerToken else { return false }
                self.leaderboardCache.markStale(period: period, start: score.periodStart)
                self.persist(self.leaderboardCache, key: Self.cachedLeaderboardKey)
                self.publishedPeriodScores[period] = score
                if accept { self.enabledPeriods.insert(period); self.persistEnabledPeriods() }
                return true
            } catch {
                guard token == self.bearerToken else { return false }
                if WeeklyChallengeSessionPolicy.shouldClearLocalIdentity(after: error) { self.clearLocalIdentity() }
                else if accept {
                    self.record(error, fallback: WeeklyChallengeL10n.text("The leaderboard could not be updated. Pull down to try again."))
                }
                return false
            }
        }
    }

    /// Serialize writes within a board (including opt-out), without making another board wait.
    private func serializePeriodMutation(_ period: ChallengePeriod, _ operation: @escaping @MainActor () async -> Bool) async -> Bool {
        let predecessor = periodMutationTails[period]
        let mutation = Task { @MainActor in
            _ = await predecessor?.value
            return await operation()
        }
        periodMutationTails[period] = mutation
        return await mutation.value
    }

    func leavePeriod(_ period: ChallengePeriod) async {
        guard busyPeriod == nil, enabledPeriods.contains(period), let token = bearerToken else { return }
        busyPeriod = period
        defer { busyPeriod = nil }
        _ = await serializePeriodMutation(period) {
            guard self.isJoined, token == self.bearerToken else { return false }
            return await self.removePeriod(period, token: token)
        }
    }

    private func removePeriod(_ period: ChallengePeriod, token: String) async -> Bool {
        do {
            try await api.deletePeriod(period, token: token)
            guard isJoined, token == bearerToken else { return false }
            enabledPeriods.remove(period)
            publishedPeriodScores[period] = nil
            leaderboardCache.remove(period: period)
            persist(leaderboardCache, key: Self.cachedLeaderboardKey)
            persistEnabledPeriods()
            return true
        } catch {
            record(error, fallback: WeeklyChallengeL10n.text("The leaderboard could not be updated. Pull down to try again."))
            return false
        }
    }

    private func persistEnabledPeriods() {
        defaults.set(enabledPeriods.map(\.rawValue).sorted(), forKey: Self.enabledPeriodsKey)
    }

    func viewerFor(
        category: WeeklyChallengeCategory,
        weekStart: String
    ) -> WeeklyChallengeParticipant? {
        guard let response = leaderboardFor(category: category, weekStart: weekStart) else {
            return nil
        }
        if let viewer = response.viewer { return viewer }
        guard let participantID else { return nil }
        return response.rankings.first {
            $0.participantId == participantID || $0.isViewer
        }
    }

    func lastUpdatedFor(
        category: WeeklyChallengeCategory,
        weekStart: String
    ) -> Date? {
        if let leaderboard,
           leaderboard.category == category,
           leaderboard.weekStart == weekStart {
            return lastUpdated
        }
        guard let entry = leaderboardCache.entry(category: category, weekStart: weekStart) else {
            return nil
        }
        return Self.serverDate(entry.response.updatedAt) ?? entry.savedAt
    }

    func refresh(
        category: WeeklyChallengeCategory,
        score: WeeklyChallengeScore
    ) async {
        guard isJoined, let token = bearerToken else {
            isRefreshing = false
            return
        }
        refreshGeneration += 1
        let generation = refreshGeneration
        isRefreshing = true

        queueLatest(score)
        await syncPendingScore()
        guard isJoined else {
            if generation == refreshGeneration { isRefreshing = false }
            return
        }

        do {
            let response = try await api.leaderboard(
                category: category,
                weekStart: score.weekStart,
                token: token
            )
            guard generation == refreshGeneration else { return }
            leaderboard = response
            lastUpdated = Self.serverDate(response.updatedAt) ?? .now
            isOffline = false
            errorMessage = nil
            persistLeaderboard(response)
        } catch is CancellationError {
            return
        } catch {
            guard generation == refreshGeneration else { return }
            if WeeklyChallengeSessionPolicy.shouldClearLocalIdentity(after: error) {
                // The server may have removed an inactive profile. Discard an
                // unusable local enrollment so the person can opt in again.
                clearLocalIdentity()
                isOffline = false
            } else {
                record(
                    error,
                    fallback: WeeklyChallengeL10n.text("The leaderboard could not be updated. Pull down to try again.")
                )
            }
        }

        if generation == refreshGeneration {
            isRefreshing = false
        }
    }

    @discardableResult
    func join(
        input: WeeklyChallengeProfileInput,
        acceptedRules: Bool,
        eligibilityAccepted: Bool,
        score: WeeklyChallengeScore,
        category: WeeklyChallengeCategory
    ) async -> Bool {
        guard !isProfileMutationInProgress,
              !hasPendingDeletion,
              acceptedRules,
              eligibilityAccepted
        else {
            return false
        }
        isProfileMutationInProgress = true
        errorMessage = nil
        defer { isProfileMutationInProgress = false }

        do {
            let response = try await api.createProfile(
                WeeklyChallengeCreateProfileRequest(
                    profile: input,
                    acceptedRules: acceptedRules,
                    eligibilityAccepted: eligibilityAccepted
                )
            )
            guard !response.participantId.isEmpty, !response.bearerToken.isEmpty else {
                throw WeeklyChallengeAPIError.invalidResponse
            }
            participantID = response.participantId
            publicProfile = response.profile
            defaults.set(response.participantId, forKey: Self.participantIDKey)
            KeychainHelper.save(key: Self.tokenKey, value: response.bearerToken)
            persistPublicProfile(response.profile)
            // `refresh` queues and uploads the latest aggregate before fetching
            // rankings, so joining performs a single idempotent score PUT.
            await refresh(category: category, score: score)
            if isJoined { return true }
            errorMessage = WeeklyChallengeL10n.text("Fud AI could not join the challenge. Try again.")
            return false
        } catch {
            if WeeklyChallengeSessionPolicy.shouldClearLocalIdentity(after: error) {
                clearLocalIdentity()
                isOffline = false
            }
            record(
                error,
                fallback: WeeklyChallengeL10n.text("Fud AI could not join the challenge. Try again.")
            )
            return false
        }
    }

    @discardableResult
    func updateProfile(_ input: WeeklyChallengeProfileInput) async -> Bool {
        guard !isProfileMutationInProgress, let token = bearerToken else { return false }
        isProfileMutationInProgress = true
        errorMessage = nil
        defer { isProfileMutationInProgress = false }

        do {
            let response = try await api.updateProfile(input, token: token)
            publicProfile = response.profile
            persistPublicProfile(response.profile)
            isOffline = false
            return true
        } catch {
            if WeeklyChallengeSessionPolicy.shouldClearLocalIdentity(after: error) {
                clearLocalIdentity()
                isOffline = false
            }
            record(
                error,
                fallback: WeeklyChallengeL10n.text("Your public challenge profile could not be updated.")
            )
            return false
        }
    }

    @discardableResult
    func leaveAndDeleteRemoteData() async -> Bool {
        guard !isProfileMutationInProgress, let token = bearerToken else { return false }
        isProfileMutationInProgress = true
        errorMessage = nil
        markDeletionPending()
        defer { isProfileMutationInProgress = false }

        do {
            try await deleteRemoteProfile(token: token)
            clearLocalIdentity()
            isOffline = false
            return true
        } catch {
            // Keep the bearer credential when deletion fails. It is required to
            // retry and is safer than leaving an unreachable remote profile.
            record(
                error,
                fallback: WeeklyChallengeL10n.text("Remote challenge data was not deleted. Check your connection and try again.")
            )
            return false
        }
    }

    /// Used by Settings > Delete Everything. The pending marker and bearer live
    /// in Keychain so wiping UserDefaults cannot make a failed remote deletion
    /// impossible to retry on the next launch.
    @discardableResult
    func deleteRemoteProfileForFullReset() async -> Bool {
        guard let token = bearerToken else {
            clearLocalIdentity()
            return true
        }
        markDeletionPending()
        do {
            try await deleteRemoteProfile(token: token)
            clearLocalIdentity()
            isOffline = false
            return true
        } catch {
            record(
                error,
                fallback: WeeklyChallengeL10n.text("Challenge deletion is pending and will retry automatically when Fud AI is online.")
            )
            return false
        }
    }

    func retryPendingDeletionIfNeeded() async {
        guard hasPendingDeletion else { return }
        guard let token = bearerToken else {
            clearLocalIdentity()
            return
        }
        do {
            try await deleteRemoteProfile(token: token)
            clearLocalIdentity()
            isOffline = false
        } catch {
            // This retry is intentionally quiet at launch. The pending marker
            // and credential remain in Keychain for the next attempt.
            if let apiError = error as? WeeklyChallengeAPIError, apiError.isOffline {
                isOffline = true
            }
        }
    }

    func block(_ participant: WeeklyChallengeParticipant) {
        guard !participant.isViewer, participant.participantId != participantID else { return }
        blockedParticipantIDs.insert(participant.participantId)
        defaults.set(Array(blockedParticipantIDs).sorted(), forKey: Self.blockedParticipantIDsKey)
    }

    func unblock(participantID: String) {
        blockedParticipantIDs.remove(participantID)
        defaults.set(Array(blockedParticipantIDs).sorted(), forKey: Self.blockedParticipantIDsKey)
    }

    func isBlocked(participantID: String) -> Bool {
        blockedParticipantIDs.contains(participantID)
    }

    @discardableResult
    func report(
        participant: WeeklyChallengeParticipant,
        reason: WeeklyChallengeReportReason,
        details: String
    ) async -> Bool {
        guard !isSubmittingReport,
              isJoined,
              !participant.isViewer,
              participant.participantId != participantID,
              let token = bearerToken
        else { return false }

        let sanitizedDetails = WeeklyChallengeReportDetailsValidator.submissionValue(details)
        isSubmittingReport = true
        errorMessage = nil
        defer { isSubmittingReport = false }

        do {
            _ = try await api.report(
                WeeklyChallengeReportRequest(
                    reportedParticipantId: participant.participantId,
                    reason: reason,
                    details: sanitizedDetails
                ),
                token: token
            )
            isOffline = false
            return true
        } catch {
            if WeeklyChallengeSessionPolicy.shouldClearLocalIdentity(after: error) {
                clearLocalIdentity()
                isOffline = false
            }
            record(
                error,
                fallback: WeeklyChallengeL10n.text("The report could not be sent. Try again.")
            )
            return false
        }
    }

    func clearError() {
        errorMessage = nil
    }

    private var bearerToken: String? {
        KeychainHelper.load(key: Self.tokenKey)
    }

    private func queueLatest(_ score: WeeklyChallengeScore) {
        guard isJoined else { return }
        pendingScore = score
        persist(score, key: Self.pendingScoreKey)
    }

    private func syncPendingScore() async {
        guard let token = bearerToken, let pendingScore else { return }
        do {
            _ = try await api.putWeeklyScore(pendingScore, token: token)
            if self.pendingScore == pendingScore {
                self.pendingScore = nil
                defaults.removeObject(forKey: Self.pendingScoreKey)
            }
            isOffline = false
        } catch is CancellationError {
            return
        } catch {
            if WeeklyChallengeSessionPolicy.shouldClearLocalIdentity(after: error) {
                clearLocalIdentity()
                isOffline = false
            } else {
                record(
                    error,
                    fallback: WeeklyChallengeL10n.text("Your latest weekly totals are saved on this device and will retry automatically.")
                )
            }
        }
    }

    private func markDeletionPending() {
        KeychainHelper.save(key: Self.pendingDeletionKey, value: "1")
        hasPendingDeletion = true
    }

    private func deleteRemoteProfile(token: String) async throws {
        do {
            let response = try await api.deleteProfile(token: token)
            guard response.deleted else { throw WeeklyChallengeAPIError.invalidResponse }
        } catch WeeklyChallengeAPIError.server(let statusCode, _, _)
            where WeeklyChallengeSessionPolicy.deletionIsComplete(statusCode: statusCode) {
            // DELETE retries are idempotent from the app's perspective. A
            // missing or no-longer-authenticatable profile means this credential
            // cannot reference remaining public challenge data.
        }
    }

    private func clearLocalIdentity(deleteCredential: Bool = true) {
        participantID = nil
        publicProfile = nil
        pendingScore = nil
        leaderboard = nil
        lastUpdated = nil
        leaderboardCache = WeeklyChallengeLeaderboardCache()
        enabledPeriods.removeAll()
        publishedPeriodScores.removeAll()
        defaults.removeObject(forKey: Self.participantIDKey)
        defaults.removeObject(forKey: Self.publicProfileKey)
        defaults.removeObject(forKey: Self.pendingScoreKey)
        defaults.removeObject(forKey: Self.cachedLeaderboardKey)
        defaults.removeObject(forKey: Self.enabledPeriodsKey)
        defaults.removeObject(forKey: Self.sharedMembershipKey)
        if deleteCredential {
            KeychainHelper.delete(key: Self.tokenKey)
            KeychainHelper.delete(key: Self.pendingDeletionKey)
            hasPendingDeletion = false
        }
    }

    private func persistLeaderboard(_ response: WeeklyChallengeLeaderboardResponse) {
        leaderboardCache.insert(response, savedAt: .now)
        persist(leaderboardCache, key: Self.cachedLeaderboardKey)
    }

    private func persistPublicProfile(_ profile: WeeklyChallengePublicProfile) {
        persist(profile, key: Self.publicProfileKey)
    }

    private func persist<Value: Encodable>(_ value: Value, key: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        defaults.set(data, forKey: key)
    }

    private func record(_ error: Error, fallback: String) {
        if let apiError = error as? WeeklyChallengeAPIError, apiError.isOffline {
            isOffline = true
            errorMessage = nil
        } else {
            errorMessage = fallback
        }
    }

    private static func decode<Value: Decodable>(_ type: Value.Type, from data: Data?) -> Value? {
        guard let data else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private static func serverDate(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }
}
