import Foundation

nonisolated enum ChallengePeriod: String, Codable, CaseIterable, Identifiable, Sendable {
    case daily, weekly, monthly
    var id: Self { self }

    var name: String.LocalizationValue {
        switch self {
        case .daily: "Daily"
        case .weekly: "Weekly"
        case .monthly: "Monthly"
        }
    }

    @MainActor func start(at now: Date, calendar: Calendar) -> Date {
        switch self {
        case .daily: calendar.startOfDay(for: now)
        case .weekly: WeeklyChallengeWeek.containing(now, calendar: calendar).start
        case .monthly: calendar.dateInterval(of: .month, for: now)?.start ?? calendar.startOfDay(for: now)
        }
    }
}

struct ChallengePeriodScore: Encodable, Equatable, Hashable {
    let period: ChallengePeriod
    let periodStart: String
    var activityDays = 0
    var nutritionDays = 0
    var consistencyDays = 0
    var hydrationDays = 0
    var balancedDays = 0
    var longestStreak = 0
    let acceptedRules = true

    var points: Int { activityDays + nutritionDays + consistencyDays + hydrationDays + balancedDays + max(0, longestStreak - 1) }

    static func calculateEnabled(
        _ periods: Set<ChallengePeriod>, now: Date, timeZone: TimeZone = .autoupdatingCurrent,
        foods: [WeeklyChallengeFoodSample], water: [WeeklyChallengeWaterSample], activities: [WeeklyChallengeActivitySample],
        calorieGoal: Int, waterGoal: Int?
    ) -> [Self] {
        ChallengePeriod.allCases.filter { $0 != .weekly && periods.contains($0) }.map {
            calculate(period: $0, now: now, timeZone: timeZone, foods: foods, water: water, activities: activities,
                      calorieGoal: calorieGoal, waterGoal: waterGoal)
        }
    }

    static func calculate(
        period: ChallengePeriod, now: Date = .now, timeZone: TimeZone = .autoupdatingCurrent,
        foods: [WeeklyChallengeFoodSample], water: [WeeklyChallengeWaterSample], activities: [WeeklyChallengeActivitySample],
        calorieGoal: Int, waterGoal: Int?
    ) -> Self {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let today = calendar.startOfDay(for: now)
        let start = period.start(at: now, calendar: calendar)
        func included(_ date: Date) -> Date? {
            let day = calendar.startOfDay(for: date)
            return day >= start && day <= today ? day : nil
        }
        var food: [Date: Int] = [:]
        var hydration: [Date: Int] = [:]
        var active = Set<Date>()
        for sample in foods {
            if let day = included(sample.date) { food[day, default: 0] += max(0, sample.calories) }
        }
        for sample in water {
            if let day = included(sample.date) { hydration[day, default: 0] += max(0, sample.milliliters) }
        }
        for sample in activities {
            if let day = included(sample.date), (sample.calories ?? 0) > 0 { active.insert(day) }
        }
        let key = VoiceAssistantParsing.isoDay(start, calendar: calendar)
        var score = Self(period: period, periodStart: key)
        var date = start
        var streak = 0
        while date <= today {
            let moves = active.contains(date)
            let logs = food[date] != nil
            let nutrition = calorieGoal > 0 && logs &&
                (Double(calorieGoal) * 0.85...Double(calorieGoal) * 1.15).contains(Double(food[date] ?? 0))
            let hydrates = waterGoal.map { $0 > 0 && hydration[date, default: 0] >= $0 } ?? false
            let habits = [moves, logs, nutrition, hydrates].filter { $0 }.count
            if moves { score.activityDays += 1 }
            if logs { score.consistencyDays += 1 }
            if nutrition { score.nutritionDays += 1 }
            if hydrates { score.hydrationDays += 1 }
            if habits >= 3 { score.balancedDays += 1 }
            streak = habits >= 2 ? streak + 1 : 0
            score.longestStreak = max(score.longestStreak, streak)
            guard let next = calendar.date(byAdding: .day, value: 1, to: date) else { break }
            date = next
        }
        return score
    }
}
