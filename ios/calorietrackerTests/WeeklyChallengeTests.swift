import Foundation
import SwiftUI
import Testing
@testable import calorietracker

@Suite("Weekly Challenge")
struct WeeklyChallengeTests {
    @MainActor
    @Test func longRankingNamesAndHandlesMakeNarrowCardsGrowRatherThanTruncate() throws {
        func render(name: String, handle: String, width: CGFloat, type: DynamicTypeSize) throws -> CGSize {
            let participant = WeeklyChallengeParticipant(
                rank: 1, participantId: "fixture", displayName: name,
                socialPlatform: .instagram, socialHandle: handle, score: 5, overallPoints: 5,
                activityDays: 1, nutritionDays: 1, consistencyDays: 2, hydrationDays: 1,
                activityKcal: 0, updatedAt: nil, isViewer: true
            )
            let row = WeeklyChallengeParticipantRow(
                participant: participant, category: .overall, isViewer: true, onReport: {}, onBlock: {}
            )
            let renderer = ImageRenderer(content: row.dynamicTypeSize(type).frame(width: width))
            return try #require(renderer.uiImage).size
        }

        let longName = "Alexandria Isabella Montgomery"
        let longHandle = "alexandria.isabella.montgomery"
        let short = try render(name: "Alex", handle: "alex", width: 196, type: .large)
        let name = try render(name: longName, handle: "alex", width: 196, type: .large)
        let both = try render(name: longName, handle: longHandle, width: 196, type: .accessibility2)
        let largeName = try render(name: longName, handle: "alex", width: 196, type: .accessibility2)
        #expect(name.width == 196)
        #expect(name.height > short.height)
        #expect(both.height > largeName.height)
        #expect(both.width == 196)
    }

    @Test func dailyAndMonthlyUseElapsedLocalDaysAndRewardBalancedStreaks() throws {
        let zone = try #require(TimeZone(identifier: "Asia/Kolkata"))
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        func day(_ number: Int) -> Date {
            calendar.date(from: DateComponents(year: 2026, month: 11, day: number, hour: 10))!
        }
        let foods = [1, 2, 4, 5].map { WeeklyChallengeFoodSample(date: day($0), calories: 2_000) }
        let water = [1, 2, 4].map { WeeklyChallengeWaterSample(date: day($0), milliliters: 2_000) }
        let workouts = [1, 2, 2, 5].map { WeeklyChallengeActivitySample(date: day($0), calories: 500) }
        let month = ChallengePeriodScore.calculate(
            period: .monthly, now: day(4), timeZone: zone, foods: foods, water: water, activities: workouts,
            calorieGoal: 2_000, waterGoal: 2_000
        )
        #expect(month.periodStart == "2026-11-01")
        #expect(month.activityDays == 2)
        #expect(month.balancedDays == 3)
        #expect(month.longestStreak == 2)
        #expect(month.points == 15)
        let daily = ChallengePeriodScore.calculate(
            period: .daily, now: day(4), timeZone: zone, foods: foods, water: water, activities: workouts,
            calorieGoal: 2_000, waterGoal: nil
        )
        #expect(daily.periodStart == "2026-11-04")
        #expect(daily.points == 2)
        #expect(daily.hydrationDays == 0)
    }

    @Test func leaderboardCacheSeparatesPeriodsAndPagesEvenOnTheSameDate() {
        var cache = WeeklyChallengeLeaderboardCache()
        let monday = "2026-11-02"
        for mode in ChallengePeriod.allCases {
            for offset in [0, 20] {
                cache.insert(.init(weekStart: monday, category: .overall, updatedAt: "", rankings: [], viewer: nil,
                                   offset: offset, totalCount: 105, period: mode), savedAt: .now)
            }
        }
        #expect(cache.entries.count == 6)
        #expect(cache.entry(category: .overall, weekStart: monday, offset: 20, period: .daily)?.response.period == .daily)
        cache.remove(period: .monthly)
        #expect(cache.entries.count == 4)
    }
    private var utcCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.firstWeekday = 2
        return calendar
    }

    private func date(_ value: String) -> Date {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)!
    }

    @Test("Local aggregation uses Monday weeks, qualifying days, and daily activity cap")
    func aggregateWeeklyScore() {
        let score = WeeklyChallengeAggregator.score(
            now: date("2026-08-26T12:00:00Z"),
            calendar: utcCalendar,
            foods: [
                .init(date: date("2026-08-24T08:00:00Z"), calories: 2_000),
                .init(date: date("2026-08-25T08:00:00Z"), calories: 1_000),
                .init(date: date("2026-08-27T08:00:00Z"), calories: 2_000), // future
                .init(date: date("2026-08-23T08:00:00Z"), calories: 2_000), // prior week
            ],
            water: [
                .init(date: date("2026-08-24T08:00:00Z"), milliliters: 1_000),
                .init(date: date("2026-08-24T18:00:00Z"), milliliters: 1_000),
                .init(date: date("2026-08-25T18:00:00Z"), milliliters: 1_999),
            ],
            activities: [
                .init(date: date("2026-08-24T08:00:00Z"), calories: 1_500),
                .init(date: date("2026-08-24T18:00:00Z"), calories: 1_000),
                .init(date: date("2026-08-25T08:00:00Z"), calories: nil),
                .init(date: date("2026-08-25T18:00:00Z"), calories: 0),
                .init(date: date("2026-08-26T08:00:00Z"), calories: 500),
            ],
            calorieGoal: 2_000,
            hydrationEnabled: true,
            hydrationGoalMilliliters: 2_000
        )

        #expect(score.weekStart == "2026-08-24")
        #expect(score.activityDays == 2)
        #expect(score.activityKcal == 2_500)
        #expect(score.nutritionDays == 1)
        #expect(score.consistencyDays == 2)
        #expect(score.hydrationDays == 1)
        #expect(score.overallPoints == 6)
    }

    @Test("Hydration points stay disabled when hydration tracking is off")
    func disabledHydrationDoesNotScore() {
        let score = WeeklyChallengeAggregator.score(
            now: date("2026-08-24T12:00:00Z"),
            calendar: utcCalendar,
            foods: [],
            water: [.init(date: date("2026-08-24T08:00:00Z"), milliliters: 4_000)],
            activities: [],
            calorieGoal: 2_000,
            hydrationEnabled: false,
            hydrationGoalMilliliters: 2_000
        )

        #expect(score.hydrationDays == 0)
        #expect(score.overallPoints == 0)
    }

    @Test("Profile validation matches public-name and social-handle contract")
    func profileValidation() {
        #expect(WeeklyChallengeProfileValidator.isValidDisplayName("Apoorv D."))
        #expect(WeeklyChallengeProfileValidator.isValidDisplayName("李 明"))
        #expect(!WeeklyChallengeProfileValidator.isValidDisplayName("A"))
        #expect(!WeeklyChallengeProfileValidator.isValidDisplayName("Runner 🏃"))
        #expect(!WeeklyChallengeProfileValidator.isValidDisplayName("Admin"))
        #expect(!WeeklyChallengeProfileValidator.isValidDisplayName("Fud AI"))
        #expect(!WeeklyChallengeProfileValidator.isValidDisplayName("fud.ai"))
        #expect(!WeeklyChallengeProfileValidator.isValidDisplayName("runner.example"))
        #expect(WeeklyChallengeProfileValidator.isValid(handle: "fud_ai", for: .x))
        #expect(!WeeklyChallengeProfileValidator.isValid(handle: "@fud_ai", for: .x))
        #expect(WeeklyChallengeProfileValidator.isValid(handle: "fud.ai", for: .instagram))
        #expect(!WeeklyChallengeProfileValidator.isValid(handle: "fud..ai", for: .instagram))
        #expect(!WeeklyChallengeProfileValidator.isValid(handle: "fudai.", for: .instagram))
    }

    @Test("Report details match the service plain-text and code-point contract")
    func reportDetailsValidation() {
        let sanitized = WeeklyChallengeReportDetailsValidator.sanitizedInput(
            "  First line\n\nSecond\tline\u{200B}  "
        )
        #expect(sanitized == "First line Second line ")
        #expect(WeeklyChallengeReportDetailsValidator.submissionValue(sanitized) == "First line Second line")

        let multiScalarEmoji = "👨‍👩‍👧‍👦"
        let bounded = WeeklyChallengeReportDetailsValidator.sanitizedInput(
            String(repeating: multiScalarEmoji, count: 100)
        )
        #expect(bounded.unicodeScalars.count == 300)
    }

    @Test("Create request explicitly encodes eligibility, rules, and paired null social fields")
    func createRequestEncoding() throws {
        let profile = WeeklyChallengeProfileInput(
            displayName: "Apoorv",
            socialPlatform: nil,
            socialHandle: nil
        )
        let request = WeeklyChallengeCreateProfileRequest(
            profile: profile,
            acceptedRules: true,
            eligibilityAccepted: true
        )
        let object = try #require(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as? [String: Any]
        )

        #expect(object["displayName"] as? String == "Apoorv")
        #expect(object["acceptedRules"] as? Bool == true)
        #expect(object["eligibilityAccepted"] as? Bool == true)
        #expect(object["socialPlatform"] is NSNull)
        #expect(object["socialHandle"] is NSNull)
    }

    @Test("Score encoding contains only the bounded aggregate contract")
    func scoreEncoding() throws {
        let score = WeeklyChallengeScore(
            weekStart: "2026-08-24",
            activityDays: 9,
            nutritionDays: 2,
            consistencyDays: 3,
            hydrationDays: 4,
            activityKcal: 20_000
        )
        let object = try #require(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(score)) as? [String: Any]
        )

        #expect(Set(object.keys) == Set([
            "weekStart", "overallPoints", "activityDays", "nutritionDays",
            "consistencyDays", "hydrationDays", "activityKcal",
        ]))
        #expect(object["activityDays"] as? Int == 7)
        #expect(object["activityKcal"] as? Int == 14_000)
        #expect(object["overallPoints"] as? Int == 16)
    }

    @Test("Leaderboard decodes rankings and a separately pinned viewer")
    func leaderboardDecoding() throws {
        let data = Data(
            #"{"weekStart":"2026-08-24","category":"overall","updatedAt":"2026-08-26T12:00:00Z","rankings":[],"viewer":{"rank":128,"participantId":"viewer-id","displayName":"Private Runner","socialPlatform":null,"socialHandle":null,"score":2,"overallPoints":2,"activityDays":1,"nutritionDays":1,"consistencyDays":0,"hydrationDays":0,"activityKcal":250,"updatedAt":"2026-08-26T12:00:00Z","isViewer":true}}"#.utf8
        )
        let response = try JSONDecoder().decode(WeeklyChallengeLeaderboardResponse.self, from: data)

        #expect(response.rankings.isEmpty)
        #expect(response.viewer?.rank == 128)
        #expect(response.viewer?.isViewer == true)
    }

    @Test("Offline cache keeps each week and category independently")
    func categoryCache() {
        let overall = WeeklyChallengeLeaderboardResponse(
            weekStart: "2026-08-24",
            category: .overall,
            updatedAt: "2026-08-26T12:00:00Z",
            rankings: [],
            viewer: nil
        )
        let activity = WeeklyChallengeLeaderboardResponse(
            weekStart: "2026-08-24",
            category: .activity,
            updatedAt: "2026-08-26T12:01:00Z",
            rankings: [],
            viewer: nil
        )
        var cache = WeeklyChallengeLeaderboardCache()
        cache.insert(overall, savedAt: date("2026-08-26T12:00:00Z"))
        cache.insert(activity, savedAt: date("2026-08-26T12:01:00Z"))

        #expect(cache.entry(category: .overall, weekStart: "2026-08-24")?.response == overall)
        #expect(cache.entry(category: .activity, weekStart: "2026-08-24")?.response == activity)
        #expect(cache.newestEntry?.response.category == .activity)
    }

    @Test("Expired challenge credentials cannot trap deletion or enrollment")
    func staleCredentialPolicy() {
        #expect(WeeklyChallengeSessionPolicy.deletionIsComplete(statusCode: 401))
        #expect(WeeklyChallengeSessionPolicy.deletionIsComplete(statusCode: 404))
        #expect(!WeeklyChallengeSessionPolicy.deletionIsComplete(statusCode: 500))
        #expect(
            WeeklyChallengeSessionPolicy.shouldClearLocalIdentity(
                after: WeeklyChallengeAPIError.server(
                    statusCode: 401,
                    code: "unauthorized",
                    message: "Expired"
                )
            )
        )
        #expect(
            !WeeklyChallengeSessionPolicy.shouldClearLocalIdentity(
                after: WeeklyChallengeAPIError.server(
                    statusCode: 403,
                    code: "forbidden",
                    message: "Forbidden"
                )
            )
        )
    }
}
