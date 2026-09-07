import Foundation

enum WeeklyChallengeL10n {
    static func text(_ value: String.LocalizationValue) -> String {
        String(localized: value, table: "WeeklyChallenge")
    }

    static func format(_ key: String, _ arguments: CVarArg...) -> String {
        let localizedFormat = Bundle.main.localizedString(
            forKey: key,
            value: key,
            table: "WeeklyChallenge"
        )
        return String(format: localizedFormat, locale: .current, arguments: arguments)
    }
}

enum ProgressOverviewMode: String, CaseIterable, Identifiable {
    case myProgress
    case weeklyChallenge

    var id: Self { self }

    var title: String {
        switch self {
        case .myProgress: WeeklyChallengeL10n.text("My Progress")
        case .weeklyChallenge: WeeklyChallengeL10n.text("Weekly Challenge")
        }
    }

    var icon: String {
        switch self {
        case .myProgress: "chart.line.uptrend.xyaxis"
        case .weeklyChallenge: "trophy.fill"
        }
    }
}

enum WeeklyChallengeCategory: String, Codable, CaseIterable, Identifiable, Hashable {
    case overall
    case activity
    case nutrition
    case consistency
    case hydration

    var id: Self { self }

    var title: String {
        switch self {
        case .overall: WeeklyChallengeL10n.text("Overall")
        case .activity: WeeklyChallengeL10n.text("Activity")
        case .nutrition: WeeklyChallengeL10n.text("Nutrition")
        case .consistency: WeeklyChallengeL10n.text("Consistency")
        case .hydration: WeeklyChallengeL10n.text("Hydration")
        }
    }

    var systemImage: String {
        switch self {
        case .overall: "trophy.fill"
        case .activity: "flame.fill"
        case .nutrition: "fork.knife"
        case .consistency: "calendar.badge.checkmark"
        case .hydration: "drop.fill"
        }
    }
}

enum WeeklyChallengeSocialPlatform: String, Codable, CaseIterable, Identifiable {
    case x
    case instagram

    var id: Self { self }

    var title: String {
        switch self {
        case .x: "X"
        case .instagram: "Instagram"
        }
    }

    func profileURL(handle: String) -> URL? {
        let host = self == .x ? "x.com" : "www.instagram.com"
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = "/\(handle)"
        return components.url
    }
}

struct WeeklyChallengeProfileInput: Codable, Equatable {
    let displayName: String
    let socialPlatform: WeeklyChallengeSocialPlatform?
    let socialHandle: String?

    private enum CodingKeys: String, CodingKey {
        case displayName, socialPlatform, socialHandle
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(displayName, forKey: .displayName)
        if let socialPlatform, let socialHandle {
            try container.encode(socialPlatform, forKey: .socialPlatform)
            try container.encode(socialHandle, forKey: .socialHandle)
        } else {
            try container.encodeNil(forKey: .socialPlatform)
            try container.encodeNil(forKey: .socialHandle)
        }
    }
}

struct WeeklyChallengeCreateProfileRequest: Encodable, Equatable {
    let displayName: String
    let socialPlatform: WeeklyChallengeSocialPlatform?
    let socialHandle: String?
    let acceptedRules: Bool
    let eligibilityAccepted: Bool

    init(
        profile: WeeklyChallengeProfileInput,
        acceptedRules: Bool,
        eligibilityAccepted: Bool
    ) {
        displayName = profile.displayName
        socialPlatform = profile.socialPlatform
        socialHandle = profile.socialHandle
        self.acceptedRules = acceptedRules
        self.eligibilityAccepted = eligibilityAccepted
    }

    private enum CodingKeys: String, CodingKey {
        case displayName, socialPlatform, socialHandle, acceptedRules, eligibilityAccepted
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(displayName, forKey: .displayName)
        try container.encode(acceptedRules, forKey: .acceptedRules)
        try container.encode(eligibilityAccepted, forKey: .eligibilityAccepted)
        if let socialPlatform, let socialHandle {
            try container.encode(socialPlatform, forKey: .socialPlatform)
            try container.encode(socialHandle, forKey: .socialHandle)
        } else {
            try container.encodeNil(forKey: .socialPlatform)
            try container.encodeNil(forKey: .socialHandle)
        }
    }
}

enum WeeklyChallengeProfileValidationError: Error, Equatable {
    case invalidDisplayName
    case disallowedDisplayName
    case missingSocialHandle
    case invalidSocialHandle

    var message: String {
        switch self {
        case .invalidDisplayName:
            WeeklyChallengeL10n.text("Use 2–40 letters or numbers. Spaces, periods, underscores, apostrophes, and hyphens are allowed.")
        case .disallowedDisplayName:
            WeeklyChallengeL10n.text("Choose a different display name that follows the Community Rules.")
        case .missingSocialHandle:
            WeeklyChallengeL10n.text("Enter the selected social handle, or choose No social link.")
        case .invalidSocialHandle:
            WeeklyChallengeL10n.text("Enter a handle only—without @, spaces, or a profile URL.")
        }
    }
}

enum WeeklyChallengeProfileValidator {
    private static let reservedNameTokens: Set<String> = [
        "admin", "administrator", "moderator", "staff", "support",
    ]
    private static let disallowedNameTokens: Set<String> = [
        "bitch", "chink", "cunt", "faggot", "fuck", "kike", "kkk",
        "nazi", "nigger", "porn", "pornhub", "shit",
    ]
    private static let disallowedCompactPhrases: Set<String> = [
        "heilhitler", "whitepower",
    ]

    static func validated(
        displayName: String,
        socialPlatform: WeeklyChallengeSocialPlatform?,
        socialHandle: String
    ) -> Result<WeeklyChallengeProfileInput, WeeklyChallengeProfileValidationError> {
        let name = normalizedDisplayName(displayName)
        if let error = displayNameValidationError(name) { return .failure(error) }

        guard let socialPlatform else {
            return .success(
                WeeklyChallengeProfileInput(
                    displayName: name,
                    socialPlatform: nil,
                    socialHandle: nil
                )
            )
        }

        let handle = socialHandle
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased(with: Locale(identifier: "en_US_POSIX"))
        guard !handle.isEmpty else { return .failure(.missingSocialHandle) }
        guard isValid(handle: handle, for: socialPlatform) else {
            return .failure(.invalidSocialHandle)
        }

        return .success(
            WeeklyChallengeProfileInput(
                displayName: name,
                socialPlatform: socialPlatform,
                socialHandle: handle
            )
        )
    }

    static func isValidDisplayName(_ value: String) -> Bool {
        displayNameValidationError(normalizedDisplayName(value)) == nil
    }

    static func normalizedDisplayName(_ value: String) -> String {
        value.precomposedStringWithCompatibilityMapping
            .replacingOccurrences(
                of: #"\s+"#,
                with: " ",
                options: .regularExpression
            )
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func displayNameValidationError(
        _ value: String
    ) -> WeeklyChallengeProfileValidationError? {
        let scalars = value.unicodeScalars
        guard (2...40).contains(scalars.count) else { return .invalidDisplayName }
        guard scalars.allSatisfy(isAllowedDisplayNameScalar),
              scalars.contains(where: isLetterOrNumber),
              !resemblesURL(value)
        else {
            return .invalidDisplayName
        }

        let tokens = nameTokens(value)
        let compact = tokens.joined()
        let impersonatesFudAI = zip(tokens, tokens.dropFirst()).contains {
            $0.0 == "fud" && $0.1 == "ai"
        } || compact == "fudai"
        guard !impersonatesFudAI,
              tokens.allSatisfy({ !reservedNameTokens.contains($0) }),
              tokens.allSatisfy({ !disallowedNameTokens.contains($0) }),
              disallowedCompactPhrases.allSatisfy({ !compact.contains($0) })
        else {
            return .disallowedDisplayName
        }
        return nil
    }

    static func isValid(handle: String, for platform: WeeklyChallengeSocialPlatform) -> Bool {
        let pattern: String
        switch platform {
        case .x:
            pattern = #"^[A-Za-z0-9_]{1,15}$"#
        case .instagram:
            pattern = #"^(?!.*\.\.)(?!.*\.$)[A-Za-z0-9._]{1,30}$"#
        }
        return handle.range(of: pattern, options: .regularExpression) != nil
    }

    private static func resemblesURL(_ value: String) -> Bool {
        value.range(
            of: #"(?:\b(?:https?://|www\.)|\b[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)+\b)"#,
            options: [.regularExpression, .caseInsensitive]
        ) != nil
    }

    private static func nameTokens(_ value: String) -> [String] {
        let lowercased = value.lowercased(with: Locale(identifier: "en_US"))
        var tokens: [String] = []
        var token = ""
        for scalar in lowercased.unicodeScalars {
            if isLetterOrNumber(scalar) {
                token.append(contentsOf: String(scalar))
            } else if !token.isEmpty {
                tokens.append(token)
                token = ""
            }
        }
        if !token.isEmpty { tokens.append(token) }
        return tokens
    }

    private static func isAllowedDisplayNameScalar(_ scalar: Unicode.Scalar) -> Bool {
        if isLetterOrNumber(scalar) { return true }
        switch scalar.properties.generalCategory {
        case .nonspacingMark, .spacingMark, .enclosingMark:
            return true
        default:
            return scalar == " " || scalar == "." || scalar == "_"
                || scalar == "'" || scalar == "’" || scalar == "-"
        }
    }

    private static func isLetterOrNumber(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter,
             .modifierLetter, .otherLetter, .decimalNumber, .letterNumber,
             .otherNumber:
            return true
        default:
            return false
        }
    }
}

struct WeeklyChallengeWeek: Equatable {
    let start: Date
    let end: Date
    let key: String

    static func containing(_ date: Date, calendar sourceCalendar: Calendar = .current) -> WeeklyChallengeWeek {
        var calendar = sourceCalendar
        calendar.firstWeekday = 2
        let day = calendar.startOfDay(for: date)
        let weekday = calendar.component(.weekday, from: day)
        let daysFromMonday = (weekday - calendar.firstWeekday + 7) % 7
        let start = calendar.date(byAdding: .day, value: -daysFromMonday, to: day) ?? day
        let end = calendar.date(byAdding: .day, value: 6, to: start) ?? start
        return WeeklyChallengeWeek(start: start, end: end, key: dayKey(for: start, calendar: calendar))
    }

    static func dayKey(for date: Date, calendar: Calendar = .current) -> String {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        return String(
            format: "%04d-%02d-%02d",
            components.year ?? 0,
            components.month ?? 0,
            components.day ?? 0
        )
    }
}

struct WeeklyChallengeFoodSample: Equatable {
    let date: Date
    let calories: Int
}

struct WeeklyChallengeWaterSample: Equatable {
    let date: Date
    let milliliters: Int
}

struct WeeklyChallengeActivitySample: Equatable {
    let date: Date
    let calories: Int?
}

struct WeeklyChallengeScore: Codable, Equatable, Hashable {
    let weekStart: String
    let overallPoints: Int
    let activityDays: Int
    let nutritionDays: Int
    let consistencyDays: Int
    let hydrationDays: Int
    let activityKcal: Int

    init(
        weekStart: String,
        activityDays: Int,
        nutritionDays: Int,
        consistencyDays: Int,
        hydrationDays: Int,
        activityKcal: Int
    ) {
        self.weekStart = weekStart
        self.activityDays = min(max(activityDays, 0), 7)
        self.nutritionDays = min(max(nutritionDays, 0), 7)
        self.consistencyDays = min(max(consistencyDays, 0), 7)
        self.hydrationDays = min(max(hydrationDays, 0), 7)
        self.overallPoints = min(
            self.activityDays + self.nutritionDays + self.consistencyDays + self.hydrationDays,
            28
        )
        self.activityKcal = min(max(activityKcal, 0), 14_000)
    }
}

enum WeeklyChallengeAggregator {
    static func score(
        now: Date = .now,
        calendar sourceCalendar: Calendar = .current,
        foods: [WeeklyChallengeFoodSample],
        water: [WeeklyChallengeWaterSample],
        activities: [WeeklyChallengeActivitySample],
        calorieGoal: Int,
        hydrationEnabled: Bool,
        hydrationGoalMilliliters: Int
    ) -> WeeklyChallengeScore {
        var calendar = sourceCalendar
        calendar.firstWeekday = 2
        let week = WeeklyChallengeWeek.containing(now, calendar: calendar)
        let today = calendar.startOfDay(for: now)
        let nextWeek = calendar.date(byAdding: .day, value: 7, to: week.start)
            ?? week.end.addingTimeInterval(86_400)

        func includedDay(for date: Date) -> Date? {
            let day = calendar.startOfDay(for: date)
            guard day >= week.start, day < nextWeek, day <= today else { return nil }
            return day
        }

        var caloriesByDay: [Date: Int] = [:]
        var foodDays: Set<Date> = []
        for sample in foods {
            guard let day = includedDay(for: sample.date) else { continue }
            foodDays.insert(day)
            caloriesByDay[day, default: 0] += sample.calories
        }

        var waterByDay: [Date: Int] = [:]
        for sample in water {
            guard let day = includedDay(for: sample.date) else { continue }
            waterByDay[day, default: 0] += max(0, sample.milliliters)
        }

        var activityDays: Set<Date> = []
        var caloriesByActivityDay: [Date: Int] = [:]
        for sample in activities {
            guard let day = includedDay(for: sample.date),
                  let calories = sample.calories,
                  calories > 0
            else { continue }
            activityDays.insert(day)
            caloriesByActivityDay[day, default: 0] += calories
        }

        let nutritionDays: Int
        if calorieGoal > 0 {
            let minimum = Double(calorieGoal) * 0.85
            let maximum = Double(calorieGoal) * 1.15
            nutritionDays = foodDays.filter { day in
                guard let calories = caloriesByDay[day] else { return false }
                return minimum...maximum ~= Double(calories)
            }.count
        } else {
            nutritionDays = 0
        }

        let hydrationDays = hydrationEnabled && hydrationGoalMilliliters > 0
            ? waterByDay.values.filter { $0 >= hydrationGoalMilliliters }.count
            : 0
        let activityKcal = caloriesByActivityDay.values.reduce(0) { total, calories in
            total + min(calories, 2_000)
        }

        return WeeklyChallengeScore(
            weekStart: week.key,
            activityDays: activityDays.count,
            nutritionDays: nutritionDays,
            consistencyDays: foodDays.count,
            hydrationDays: hydrationDays,
            activityKcal: activityKcal
        )
    }
}

struct WeeklyChallengePublicProfile: Codable, Equatable {
    let participantId: String
    let displayName: String
    let socialPlatform: WeeklyChallengeSocialPlatform?
    let socialHandle: String?
    let createdAt: String?
    let updatedAt: String?
}

struct WeeklyChallengeParticipant: Codable, Identifiable, Equatable {
    let rank: Int
    let participantId: String
    let displayName: String
    let socialPlatform: WeeklyChallengeSocialPlatform?
    let socialHandle: String?
    let score: Int
    let overallPoints: Int
    let activityDays: Int
    let nutritionDays: Int
    let consistencyDays: Int
    let hydrationDays: Int
    let activityKcal: Int
    let updatedAt: String?
    let isViewer: Bool

    var id: String { participantId }
}

struct WeeklyChallengeLeaderboardResponse: Codable, Equatable {
    let weekStart: String
    let category: WeeklyChallengeCategory
    let updatedAt: String
    let rankings: [WeeklyChallengeParticipant]
    let viewer: WeeklyChallengeParticipant?
}

struct WeeklyChallengeCreateProfileResponse: Decodable {
    let participantId: String
    let bearerToken: String
    let profile: WeeklyChallengePublicProfile
}

struct WeeklyChallengeProfileResponse: Decodable {
    let profile: WeeklyChallengePublicProfile
}

struct WeeklyChallengeScoreResponse: Decodable {
    struct RemoteScore: Decodable {
        let weekStart: String
        let overallPoints: Int
        let activityDays: Int
        let nutritionDays: Int
        let consistencyDays: Int
        let hydrationDays: Int
        let activityKcal: Int
        let updatedAt: String?
    }

    let score: RemoteScore
}

struct WeeklyChallengeDeleteResponse: Decodable {
    let deleted: Bool
}

enum WeeklyChallengeReportDetailsValidator {
    static let maximumCodePointCount = 300

    /// Mirrors the service's NFKC/plain-text contract while keeping a single
    /// trailing space usable during editing. Newlines and hidden Unicode
    /// controls become ordinary spaces, and the limit is measured in Unicode
    /// code points rather than Swift grapheme clusters.
    static func sanitizedInput(_ value: String) -> String {
        let normalized = value.precomposedStringWithCompatibilityMapping
        var result = String.UnicodeScalarView()
        var previousWasSpace = false

        for scalar in normalized.unicodeScalars {
            let isWhitespace = CharacterSet.whitespacesAndNewlines.contains(scalar)
            let isHiddenControl = switch scalar.properties.generalCategory {
            case .control, .format, .surrogate, .privateUse, .unassigned: true
            default: false
            }

            if isWhitespace || isHiddenControl {
                if !previousWasSpace, !result.isEmpty {
                    result.append(" ")
                }
                previousWasSpace = true
            } else {
                result.append(scalar)
                previousWasSpace = false
            }
        }

        return String(result.prefix(maximumCodePointCount))
    }

    static func submissionValue(_ value: String) -> String? {
        let sanitized = sanitizedInput(value)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return sanitized.isEmpty ? nil : sanitized
    }

    static func codePointCount(_ value: String) -> Int {
        sanitizedInput(value).unicodeScalars.count
    }
}

enum WeeklyChallengeReportReason: String, Codable, CaseIterable, Identifiable {
    case inappropriateName = "inappropriate_name"
    case impersonation
    case spam
    case unsafeContent = "unsafe_content"
    case other

    var id: Self { self }

    var title: String {
        switch self {
        case .inappropriateName: WeeklyChallengeL10n.text("Inappropriate name")
        case .impersonation: WeeklyChallengeL10n.text("Impersonation")
        case .spam: WeeklyChallengeL10n.text("Spam")
        case .unsafeContent: WeeklyChallengeL10n.text("Unsafe content")
        case .other: WeeklyChallengeL10n.text("Other")
        }
    }
}

struct WeeklyChallengeReportRequest: Encodable, Equatable {
    let reportedParticipantId: String
    let reason: WeeklyChallengeReportReason
    let details: String?
}

struct WeeklyChallengeReportResponse: Decodable {
    struct Report: Decodable {
        let reportId: String
        let reportedParticipantId: String
        let reason: WeeklyChallengeReportReason
        let status: String
        let createdAt: String
    }

    let report: Report
}
