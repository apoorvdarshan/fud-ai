import Foundation
import Observation

/// The app's top-level screens, named as the tool contract names them.
enum VoiceAssistantScreen: String, CaseIterable {
    case home
    case progress
    case weeklyChallenge = "weekly_challenge"
    case coach
    case workouts
    case exerciseLibrary = "exercise_library"
    case settings
    case about
    case support
}

/// One navigation the assistant asked for. The id lets the same destination be
/// requested twice in a row.
struct VoiceAssistantNavigationRequest<Value: Equatable>: Equatable {
    let id = UUID()
    let value: Value
}

/// Moves the real app when the assistant navigates, and mirrors what the app is
/// showing so the assistant can describe it. ContentView, HomeView and the
/// Settings hub take the requests (each is handled once) and report their state.
@MainActor
@Observable
final class VoiceAssistantNavigator {
    static let shared = VoiceAssistantNavigator()

    // What the app shows (reported by the views).
    var currentScreen: VoiceAssistantScreen = .home
    var homeDiaryDate: Date
    var workoutDiaryDate: Date
    private var lastObservedToday: Date
    @ObservationIgnored private let clock: () -> Date
    @ObservationIgnored private let calendar: Calendar
    var settingsSection: ProfileSettingsCategory?
    /// The tab bar's and home indicator's height, so the compact assistant sits
    /// where the Home orb button is, above the tab bar.
    var contentBottomInset: CGFloat = 83

    // Requests, taken by the views that handle them.
    private(set) var screenRequest: VoiceAssistantNavigationRequest<VoiceAssistantScreen>?
    /// Settings section to push; a nil value shows the Settings list itself.
    private(set) var settingsRequest: VoiceAssistantNavigationRequest<ProfileSettingsCategory?>?
    private(set) var diaryDateRequest: VoiceAssistantNavigationRequest<Date>?
    private(set) var workoutDateRequest: VoiceAssistantNavigationRequest<Date>?
    private(set) var foodEntryRequest: VoiceAssistantNavigationRequest<UUID>?

    init(clock: @escaping () -> Date = Date.init, calendar: Calendar = .autoupdatingCurrent) {
        let now = clock()
        self.clock = clock
        self.calendar = calendar
        homeDiaryDate = now
        workoutDiaryDate = now
        lastObservedToday = now
    }

    /// A date that was "today" follows the local clock; browsing a past day does not.
    /// Call on foreground, system day changes and voice entry, even if Home is hidden.
    func refreshDiaryDates() {
        let now = clock()
        let previous = lastObservedToday
        guard !calendar.isDate(previous, inSameDayAs: now) else { return }
        lastObservedToday = now
        if calendar.isDate(homeDiaryDate, inSameDayAs: previous) {
            homeDiaryDate = now
            if diaryDateRequest.map({ calendar.isDate($0.value, inSameDayAs: previous) }) == true {
                diaryDateRequest = VoiceAssistantNavigationRequest(value: now)
            }
        }
        let workoutDay = workoutDateRequest?.value ?? workoutDiaryDate
        if calendar.isDate(workoutDay, inSameDayAs: previous) {
            workoutDiaryDate = now
            workoutDateRequest = VoiceAssistantNavigationRequest(value: now)
        }
    }

    func currentWorkoutDiaryDate() -> Date {
        refreshDiaryDates()
        return workoutDateRequest?.value ?? (currentScreen == .workouts ? workoutDiaryDate : clock())
    }

    func show(_ screen: VoiceAssistantScreen, settingsSection: ProfileSettingsCategory? = nil) {
        screenRequest = VoiceAssistantNavigationRequest(value: screen)
        settingsRequest = VoiceAssistantNavigationRequest(value: settingsSection)
    }

    func showDiary(on day: Date) {
        refreshDiaryDates()
        homeDiaryDate = day
        show(.home)
        diaryDateRequest = VoiceAssistantNavigationRequest(value: day)
    }

    func openFoodEntry(_ entry: FoodEntry) {
        showDiary(on: entry.timestamp)
        foodEntryRequest = VoiceAssistantNavigationRequest(value: entry.id)
    }

    func showWorkoutDiary(on day: Date) {
        refreshDiaryDates()
        workoutDiaryDate = day
        show(.workouts)
        workoutDateRequest = VoiceAssistantNavigationRequest(value: day)
    }

    func takeWorkoutDateRequest() -> Date? {
        guard let day = workoutDateRequest?.value else { return nil }
        workoutDiaryDate = day
        workoutDateRequest = nil
        return day
    }

    func takeScreenRequest() -> VoiceAssistantScreen? {
        defer { screenRequest = nil }
        return screenRequest?.value
    }

    /// The outer optional is whether there is a request; the inner one is the section.
    func takeSettingsRequest() -> ProfileSettingsCategory?? {
        defer { settingsRequest = nil }
        return settingsRequest.map(\.value)
    }

    func takeDiaryDateRequest() -> Date? {
        defer { diaryDateRequest = nil }
        return diaryDateRequest?.value
    }

    func takeFoodEntryRequest() -> UUID? {
        defer { foodEntryRequest = nil }
        return foodEntryRequest?.value
    }
}

extension ProfileSettingsCategory {
    /// The Settings section for a `settings_section` value of `open_screen`.
    init?(voiceAssistantSection: String) {
        switch voiceAssistantSection {
        case "profile": self = .personalInfo
        case "goals": self = .goalsNutrition
        // Tracking and notifications share one page (#432); "reminders" still opens it.
        case "reminders", "notifications": self = .trackingNotifications
        case "ai_providers": self = .aiProviders
        case "speech": self = .speechToText
        case "appearance": self = .appPreferences
        case "workouts": self = .workout
        case "health": self = .healthData
        case "data": self = .dataManagement
        case "voice_assistant": self = .voiceAssistant
        case "about", "app_updates": self = .appUpdates
        case "help_feedback": self = .helpFeedback
        case "community": self = .community
        case "legal": self = .legal
        default: return nil
        }
    }

    /// The contract's name for this section.
    var voiceAssistantSection: String? {
        switch self {
        case .personalInfo: "profile"
        case .goalsNutrition: "goals"
        case .trackingNotifications: "notifications"
        case .aiProviders, .aiAccess: "ai_providers"
        case .speechToText: "speech"
        case .appPreferences: "appearance"
        case .workout: "workouts"
        case .healthData: "health"
        case .dataManagement: "data"
        case .voiceAssistant: "voice_assistant"
        case .appUpdates: "app_updates"
        case .helpFeedback: "help_feedback"
        case .community: "community"
        case .legal: "legal"
        case .support: nil
        }
    }
}
