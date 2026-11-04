import Foundation
import UserNotifications

/// Every change to pending notification requests goes through this one serial
/// queue, never the main thread. UNUserNotificationCenter's remove calls wait for
/// a synchronous reply from the notification daemon; made on the main thread, a
/// slow daemon froze the UI for minutes (seen in CI after toggling the water
/// reminder). The queue keeps removes and adds in the order the app made them, so
/// a reminder that is removed and re-added is never left removed, and each change
/// keeps the app running in the background until it has reached the daemon, so a
/// reminder switched off just before the app is closed is still cancelled.
nonisolated enum PendingNotificationRequests {
    private static let queue = DispatchQueue(
        label: "com.apoorvdarshan.calorietracker.pending-notification-requests",
        qos: .utility
    )

    /// Queues `work`, holding an expiring-activity assertion from now until it has run.
    private static func perform(_ work: @escaping @Sendable (UNUserNotificationCenter) -> Void) {
        let finished = DispatchSemaphore(value: 0)
        ProcessInfo.processInfo.performExpiringActivity(withReason: "Update pending notifications") { expired in
            // Called again with `expired` when the time is up; the work still runs on resume.
            if !expired { finished.wait() }
        }
        queue.async {
            work(UNUserNotificationCenter.current())
            finished.signal()
        }
    }

    static func remove(_ identifiers: [String]) {
        perform { $0.removePendingNotificationRequests(withIdentifiers: identifiers) }
    }

    static func removeAll() {
        perform { $0.removeAllPendingNotificationRequests() }
    }

    static func add(_ request: UNNotificationRequest) {
        perform { $0.add(request) }
    }

    /// Builds the request when the queue reaches it, so a countdown trigger starts
    /// when it is added, after any earlier, slower change. Returning nil adds nothing.
    static func addWhenQueued(_ makeRequest: @escaping @Sendable () -> UNNotificationRequest?) {
        perform { center in
            if let request = makeRequest() { center.add(request) }
        }
    }

    /// Adds once every earlier change has been applied, and reports whether it worked.
    static func addAfterPendingChanges(_ request: UNNotificationRequest) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            perform { center in
                center.add(request) { error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume()
                    }
                }
            }
        }
    }
}

@Observable
class NotificationManager {
    var authorizationStatus: UNAuthorizationStatus = .notDetermined

    // MARK: - Authorization

    func requestAuthorization() async -> Bool {
        do {
            let granted = try await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound, .badge])
            await refreshAuthorizationStatus()
            return granted
        } catch {
            return false
        }
    }

    func refreshAuthorizationStatus() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        authorizationStatus = settings.authorizationStatus
    }

    // MARK: - Meal Reminders (repeating daily)

    func scheduleMealReminders(
        breakfastEnabled: Bool, breakfastHour: Int, breakfastMinute: Int,
        lunchEnabled: Bool, lunchHour: Int, lunchMinute: Int,
        dinnerEnabled: Bool, dinnerHour: Int, dinnerMinute: Int
    ) {
        // Cancel existing meal reminders
        PendingNotificationRequests.remove([
            "meal.breakfast", "meal.lunch", "meal.dinner"
        ])

        if breakfastEnabled {
            scheduleRepeatingMeal(
                id: "meal.breakfast",
                title: String(localized: "Breakfast Time", comment: "Title of the daily breakfast reminder notification."),
                body: String(localized: "Don't forget to log your breakfast!", comment: "Body of the daily breakfast reminder notification."),
                hour: breakfastHour, minute: breakfastMinute
            )
        }

        if lunchEnabled {
            scheduleRepeatingMeal(
                id: "meal.lunch",
                title: String(localized: "Lunch Time", comment: "Title of the daily lunch reminder notification."),
                body: String(localized: "Snap a photo to keep tracking!", comment: "Body of the daily lunch reminder notification; suggests logging lunch with a photo."),
                hour: lunchHour, minute: lunchMinute
            )
        }

        if dinnerEnabled {
            scheduleRepeatingMeal(
                id: "meal.dinner",
                title: String(localized: "Dinner Time", comment: "Title of the daily dinner reminder notification."),
                body: String(localized: "Log your dinner to stay on track!", comment: "Body of the daily dinner reminder notification."),
                hour: dinnerHour, minute: dinnerMinute
            )
        }
    }

    private func scheduleRepeatingMeal(id: String, title: String, body: String, hour: Int, minute: Int) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.filterCriteria = FudAIFocusFilterCriteria.mealReminder

        var dateComponents = DateComponents()
        dateComponents.hour = hour
        dateComponents.minute = minute

        let trigger = UNCalendarNotificationTrigger(dateMatching: dateComponents, repeats: true)
        let request = UNNotificationRequest(identifier: id, content: content, trigger: trigger)
        PendingNotificationRequests.add(request)
    }

    // MARK: - Water Reminder (repeating daily)

    func scheduleWaterReminder(enabled: Bool, hour: Int, minute: Int) {
        PendingNotificationRequests.remove(["water.reminder"])
        guard enabled else { return }

        let content = UNMutableNotificationContent()
        content.title = String(localized: "Time to Hydrate", comment: "Title of the daily water reminder notification.")
        content.body = String(localized: "Have some water and log it in Fud AI.", comment: "Body of the daily water reminder notification. Fud AI is the app name.")
        content.sound = .default

        var dateComponents = DateComponents()
        dateComponents.hour = hour
        dateComponents.minute = minute
        let trigger = UNCalendarNotificationTrigger(dateMatching: dateComponents, repeats: true)
        PendingNotificationRequests.add(UNNotificationRequest(identifier: "water.reminder", content: content, trigger: trigger))
    }

    // MARK: - Repeating Reminders: refresh text

    /// The saved meal and water reminder settings, with the same defaults as the
    /// Notifications settings screen and onboarding.
    struct RepeatingReminderSettings: Equatable {
        struct Time: Equatable {
            var enabled: Bool
            var hour: Int
            var minute: Int
        }

        var breakfast: Time
        var lunch: Time
        var dinner: Time
        /// Enabled only while water tracking and its reminder are both on.
        var water: Time

        init(defaults: UserDefaults) {
            func time(_ name: String, enabled: Bool, hour: Int) -> Time {
                Time(
                    enabled: defaults.object(forKey: "\(name)ReminderEnabled") as? Bool ?? enabled,
                    hour: defaults.object(forKey: "\(name)ReminderHour") as? Int ?? hour,
                    minute: defaults.object(forKey: "\(name)ReminderMinute") as? Int ?? 0
                )
            }
            breakfast = time("breakfast", enabled: true, hour: 8)
            lunch = time("lunch", enabled: true, hour: 12)
            dinner = time("dinner", enabled: true, hour: 19)
            water = Time(
                enabled: defaults.bool(forKey: WaterSettings.enabledKey)
                    && defaults.bool(forKey: WaterSettings.reminderEnabledKey),
                hour: defaults.object(forKey: WaterSettings.reminderHourKey) as? Int ?? 14,
                minute: defaults.object(forKey: WaterSettings.reminderMinuteKey) as? Int ?? 0
            )
        }
    }

    /// Meal and water reminders repeat daily, so they keep the text of the language
    /// the app ran in when they were added. Re-adding them from the saved settings
    /// whenever the app becomes active moves reminders added by an older version, or
    /// before a language change (iOS relaunches the app then), to the current language.
    func rescheduleRepeatingReminders(defaults: UserDefaults = .standard) {
        let settings = RepeatingReminderSettings(defaults: defaults)
        scheduleMealReminders(
            breakfastEnabled: settings.breakfast.enabled, breakfastHour: settings.breakfast.hour, breakfastMinute: settings.breakfast.minute,
            lunchEnabled: settings.lunch.enabled, lunchHour: settings.lunch.hour, lunchMinute: settings.lunch.minute,
            dinnerEnabled: settings.dinner.enabled, dinnerHour: settings.dinner.hour, dinnerMinute: settings.dinner.minute
        )
        scheduleWaterReminder(enabled: settings.water.enabled, hour: settings.water.hour, minute: settings.water.minute)
    }

    // MARK: - Fasting Goal (one-shot)

    func scheduleFastingGoal(enabled: Bool, session: FastingSession?) {
        PendingNotificationRequests.remove(["fasting.goal"])
        guard enabled, let session, session.isActive else { return }

        let goalDate = session.goalDate
        guard goalDate.timeIntervalSinceNow > 1 else { return }

        let content = UNMutableNotificationContent()
        content.title = String(localized: "Fasting Goal Reached", comment: "Title of the notification sent when an active fast reaches its goal.")
        let goal = FastingDurationFormatter.goal(minutes: session.goalMinutes)
        content.body = String(
            localized: "You reached your \(goal) fasting goal. End the fast when you're ready.",
            comment: "Body of the fasting goal notification. The argument is the goal length, such as 16h."
        )
        content.sound = .default
        let message = content.copy() as! UNNotificationContent

        // The countdown is measured when the request is added, so a slow earlier
        // change cannot push the reminder past the goal.
        PendingNotificationRequests.addWhenQueued {
            let interval = goalDate.timeIntervalSinceNow
            guard interval > 1 else { return nil }
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
            return UNNotificationRequest(identifier: "fasting.goal", content: message, trigger: trigger)
        }
    }

    func cancelFastingGoal() {
        PendingNotificationRequests.remove(["fasting.goal"])
    }

    // MARK: - Streak Reminder (one-shot, rescheduled on foreground/log)

    func scheduleStreakReminder(enabled: Bool, hour: Int, minute: Int, hasLoggedToday: Bool, currentStreak: Int) {
        PendingNotificationRequests.remove(["smart.streak"])

        guard enabled, !hasLoggedToday, currentStreak > 0 else { return }

        // Only schedule if the time hasn't passed yet today
        let now = Date()
        let calendar = Calendar.current
        guard let fireDate = calendar.date(
            bySettingHour: hour, minute: minute, second: 0, of: now
        ), fireDate > now else { return }

        let content = UNMutableNotificationContent()
        content.title = String(
            localized: "Don't Break Your \(currentStreak)-Day Streak!",
            comment: "Title of the streak reminder notification. The number is how many days in a row the user has logged food; the translation varies by plural."
        )
        content.body = String(localized: "Log something before the day ends.", comment: "Body of the streak reminder notification.")
        content.sound = .default

        var dateComponents = DateComponents()
        dateComponents.hour = hour
        dateComponents.minute = minute

        let trigger = UNCalendarNotificationTrigger(dateMatching: dateComponents, repeats: false)
        let request = UNNotificationRequest(identifier: "smart.streak", content: content, trigger: trigger)
        PendingNotificationRequests.add(request)
    }

    // MARK: - Daily Summary (one-shot, rescheduled on foreground/log)

    func scheduleDailySummary(
        enabled: Bool,
        hour: Int,
        minute: Int,
        todayCalories: Int,
        calorieGoal: Int,
        energyUnit: EnergyUnit = .current
    ) {
        PendingNotificationRequests.remove(["smart.summary"])

        guard enabled else { return }

        let now = Date()
        let calendar = Calendar.current
        guard let fireDate = calendar.date(
            bySettingHour: hour, minute: minute, second: 0, of: now
        ), fireDate > now else { return }

        let content = UNMutableNotificationContent()
        content.title = String(localized: "Daily Summary", comment: "Title of the evening daily summary notification.")
        content.body = Self.dailySummaryBody(todayCalories: todayCalories, calorieGoal: calorieGoal, energyUnit: energyUnit)
        content.sound = .default

        var dateComponents = DateComponents()
        dateComponents.hour = hour
        dateComponents.minute = minute

        let trigger = UNCalendarNotificationTrigger(dateMatching: dateComponents, repeats: false)
        let request = UNNotificationRequest(identifier: "smart.summary", content: content, trigger: trigger)
        PendingNotificationRequests.add(request)
    }

    /// Daily summary text in the user's energy unit. kJ has its own sentence so
    /// languages keep their plural agreement for the remaining amount.
    nonisolated static func dailySummaryBody(todayCalories: Int, calorieGoal: Int, energyUnit: EnergyUnit) -> String {
        switch energyUnit {
        case .kcal:
            let remaining = max(0, calorieGoal - todayCalories)
            return String(
                localized: "You ate \(todayCalories) of \(calorieGoal) kcal today. \(remaining) remaining!",
                comment: "Body of the daily summary notification: calories eaten, the daily calorie goal, and calories left. kcal is an abbreviation that does not change with the number; vary the remaining count by plural where the language needs it."
            )
        case .kj:
            let eaten = energyUnit.displayValue(kcal: todayCalories)
            let goal = energyUnit.displayValue(kcal: calorieGoal)
            let remaining = max(0, goal - eaten)
            return String(
                localized: "You ate \(eaten) of \(goal) kJ today. \(remaining) remaining!",
                comment: "Body of the daily summary notification for users who track energy in kilojoules: kJ eaten, the daily kJ goal, and kJ left. kJ is an abbreviation that does not change with the number; vary the remaining count by plural where the language needs it."
            )
        }
    }

    // MARK: - Weight Log Reminder (one-shot, rescheduled on foreground/log)

    /// Daily nudge to step on the scale. Skips firing if the user has already
    /// logged a weight today — same "smart" pattern as the streak reminder so
    /// users who weigh in early don't get a redundant evening ping.
    func scheduleWeightLogReminder(enabled: Bool, hour: Int, minute: Int, hasLoggedWeightToday: Bool) {
        PendingNotificationRequests.remove(["smart.weight"])

        guard enabled, !hasLoggedWeightToday else { return }

        let now = Date()
        let calendar = Calendar.current
        guard let fireDate = calendar.date(
            bySettingHour: hour, minute: minute, second: 0, of: now
        ), fireDate > now else { return }

        let content = UNMutableNotificationContent()
        content.title = String(localized: "Time to Weigh In", comment: "Title of the daily weigh-in reminder notification.")
        content.body = String(localized: "Log today's weight to keep your progress chart accurate.", comment: "Body of the daily weigh-in reminder notification.")
        content.sound = .default

        var dateComponents = DateComponents()
        dateComponents.hour = hour
        dateComponents.minute = minute

        let trigger = UNCalendarNotificationTrigger(dateMatching: dateComponents, repeats: false)
        let request = UNNotificationRequest(identifier: "smart.weight", content: content, trigger: trigger)
        PendingNotificationRequests.add(request)
    }

    // MARK: - Body Fat Log Reminder (one-shot, rescheduled on foreground/log)

    /// Daily nudge to log a body-fat reading. Skips firing if the user has
    /// already logged one today. Most users don't measure body fat daily, so
    /// this defaults OFF — users opt in via Settings → Tracking & Notifications.
    func scheduleBodyFatLogReminder(enabled: Bool, hour: Int, minute: Int, hasLoggedBodyFatToday: Bool) {
        PendingNotificationRequests.remove(["smart.bodyfat"])

        guard enabled, !hasLoggedBodyFatToday else { return }

        let now = Date()
        let calendar = Calendar.current
        guard let fireDate = calendar.date(
            bySettingHour: hour, minute: minute, second: 0, of: now
        ), fireDate > now else { return }

        let content = UNMutableNotificationContent()
        content.title = String(localized: "Body Fat Check-In", comment: "Title of the daily body fat reminder notification.")
        content.body = String(localized: "Log today's body fat % to track your composition trend.", comment: "Body of the daily body fat reminder notification. % means body fat percentage.")
        content.sound = .default

        var dateComponents = DateComponents()
        dateComponents.hour = hour
        dateComponents.minute = minute

        let trigger = UNCalendarNotificationTrigger(dateMatching: dateComponents, repeats: false)
        let request = UNNotificationRequest(identifier: "smart.bodyfat", content: content, trigger: trigger)
        PendingNotificationRequests.add(request)
    }

    // MARK: - Convenience: Reschedule Data-Dependent Notifications

    func rescheduleDataDependentNotifications(foodStore: FoodStore, weightStore: WeightStore, bodyFatStore: BodyFatStore, profile: UserProfile) {
        let streakEnabled = UserDefaults.standard.object(forKey: "streakReminderEnabled") as? Bool ?? true
        let streakHour = UserDefaults.standard.object(forKey: "streakReminderHour") as? Int ?? 21
        let streakMinute = UserDefaults.standard.object(forKey: "streakReminderMinute") as? Int ?? 0

        let summaryEnabled = UserDefaults.standard.object(forKey: "dailySummaryEnabled") as? Bool ?? true
        let summaryHour = UserDefaults.standard.object(forKey: "dailySummaryHour") as? Int ?? 20
        let summaryMinute = UserDefaults.standard.object(forKey: "dailySummaryMinute") as? Int ?? 0

        let weightLogEnabled = UserDefaults.standard.object(forKey: "weightLogReminderEnabled") as? Bool ?? true
        let weightLogHour = UserDefaults.standard.object(forKey: "weightLogReminderHour") as? Int ?? 8
        let weightLogMinute = UserDefaults.standard.object(forKey: "weightLogReminderMinute") as? Int ?? 0

        // Body-fat opt-in default OFF — most users don't measure daily, so
        // a daily ping would feel noisy. Users with a daily smart-scale
        // routine can flip it on in Settings → Tracking & Notifications.
        let bodyFatLogEnabled = UserDefaults.standard.object(forKey: "bodyFatLogReminderEnabled") as? Bool ?? false
        let bodyFatLogHour = UserDefaults.standard.object(forKey: "bodyFatLogReminderHour") as? Int ?? 8
        let bodyFatLogMinute = UserDefaults.standard.object(forKey: "bodyFatLogReminderMinute") as? Int ?? 0

        let hasLoggedToday = !foodStore.todayEntries.isEmpty
        let currentStreak = computeCurrentStreak(foodStore: foodStore)

        let calendar = Calendar.current
        let hasLoggedWeightToday = weightStore.entries.contains { calendar.isDateInToday($0.date) }
        let hasLoggedBodyFatToday = bodyFatStore.entries.contains { calendar.isDateInToday($0.date) }

        scheduleStreakReminder(
            enabled: streakEnabled,
            hour: streakHour, minute: streakMinute,
            hasLoggedToday: hasLoggedToday,
            currentStreak: currentStreak
        )

        scheduleDailySummary(
            enabled: summaryEnabled,
            hour: summaryHour, minute: summaryMinute,
            todayCalories: foodStore.todayCalories,
            calorieGoal: profile.effectiveCalories
        )

        scheduleWeightLogReminder(
            enabled: weightLogEnabled,
            hour: weightLogHour, minute: weightLogMinute,
            hasLoggedWeightToday: hasLoggedWeightToday
        )

        scheduleBodyFatLogReminder(
            enabled: bodyFatLogEnabled,
            hour: bodyFatLogHour, minute: bodyFatLogMinute,
            hasLoggedBodyFatToday: hasLoggedBodyFatToday
        )
    }

    // MARK: - App Update Available (one-shot, de-duped per version)

    /// Identifier used so the tap handler can recognize an update notification.
    static let appUpdateNotificationID = "app.update"

    /// Fire a local notification telling the user a new App Store version is out. Tapping it opens
    /// the store (handled in AppDelegate). Gated by the "App Updates" toggle (default on), only when
    /// notifications are authorized, and de-duped so a given version notifies at most once even
    /// though the update check runs on every launch.
    func notifyUpdateAvailable(version: String, url: URL) async {
        let enabled = UserDefaults.standard.object(forKey: "appUpdateNotificationsEnabled") as? Bool ?? true
        guard enabled else { return }
        guard UserDefaults.standard.string(forKey: "lastNotifiedAppUpdateVersion") != version else { return }

        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard settings.authorizationStatus == .authorized else { return }

        let content = UNMutableNotificationContent()
        content.title = String(localized: "Update Available", comment: "Title of the notification that a new App Store version is out.")
        content.body = String(localized: "Fud AI \(version) is ready. Tap to update.", comment: "Body of the app update notification. Fud AI is the app name; the argument is the new version number, such as 7.2.")
        content.sound = .default
        content.userInfo = ["updateURL": url.absoluteString]

        let request = UNNotificationRequest(identifier: Self.appUpdateNotificationID, content: content, trigger: nil)
        do {
            try await PendingNotificationRequests.addAfterPendingChanges(request)
            UserDefaults.standard.set(version, forKey: "lastNotifiedAppUpdateVersion")
        } catch {
            // Best-effort; nothing to recover.
        }
    }

    // MARK: - Product Hunt Launch (one-shot, absolute time)

    /// Identifier used so the delegate can show the launch banner in the foreground.
    static let productHuntLaunchNotificationID = "producthunt.launch"
    /// Bumped when the launch day moves so a phone that already armed the old date schedules again.
    static let productHuntLaunchScheduledKey = "productHuntLaunchNotificationScheduled.2026-09-29"
    nonisolated static let productHuntLaunchTimeZone = TimeZone(identifier: "America/Los_Angeles")!

    /// Fud AI goes live on Product Hunt on Sept 29, 2026 at 12:01 AM Pacific.
    nonisolated static var productHuntLaunchDate: Date {
        var components = DateComponents()
        components.year = 2026
        components.month = 9
        components.day = 29
        components.hour = 0
        components.minute = 1
        components.timeZone = productHuntLaunchTimeZone
        return Calendar(identifier: .gregorian).date(from: components)!
    }

    nonisolated enum ProductHuntLaunchPlan: Equatable {
        /// Launch day is over — a late reminder would just be noise.
        case skip
        /// The user updated during launch day; fire right away so they can still vote.
        case fireNow
        case schedule(Date)
    }

    /// Pure decision so the window logic is testable: fire at launch, fire immediately if we
    /// are already inside the 24h launch day, and skip entirely once that day has passed.
    nonisolated static func productHuntLaunchPlan(now: Date, launch: Date = productHuntLaunchDate) -> ProductHuntLaunchPlan {
        let launchDayEnd = launch.addingTimeInterval(24 * 60 * 60)
        if now >= launchDayEnd { return .skip }
        if now >= launch { return .fireNow }
        return .schedule(launch)
    }

    /// Arms the one-shot launch reminder exactly once. Re-run on every launch: cheap when the
    /// flag is set, and retried on later launches while notifications are not yet authorized
    /// (asks for permission first if the user has never been asked).
    func scheduleProductHuntLaunchReminderIfNeeded() async {
        guard !UserDefaults.standard.bool(forKey: Self.productHuntLaunchScheduledKey) else { return }
        // Respect the in-app Notifications master toggle. Leave the scheduled flag clear so
        // enabling notifications later (before launch day ends) can still arm the reminder.
        guard UserDefaults.standard.bool(forKey: "notificationsEnabled") else { return }

        let plan = Self.productHuntLaunchPlan(now: .now)
        if plan == .skip {
            UserDefaults.standard.set(true, forKey: Self.productHuntLaunchScheduledKey)
            return
        }

        let center = UNUserNotificationCenter.current()
        var settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined {
            _ = await requestAuthorization()
            settings = await center.notificationSettings()
        }
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else {
            // Ask once: if the user denied system permission, stop retrying every launch.
            if settings.authorizationStatus == .denied {
                UserDefaults.standard.set(true, forKey: Self.productHuntLaunchScheduledKey)
            }
            return
        }

        let content = UNMutableNotificationContent()
        content.title = String(localized: "Fud AI is live on Product Hunt 🚀", comment: "Title of the Product Hunt launch day notification. Fud AI and Product Hunt are names; keep them in English.")
        content.body = String(localized: "We just launched! Tap to vote and help more people find Fud AI.", comment: "Body of the Product Hunt launch day notification. Fud AI is the app name.")
        content.sound = .default
        content.userInfo = ["openURL": FudAILinks.productHunt.absoluteString]

        let trigger: UNNotificationTrigger?
        switch plan {
        case .schedule(let date):
            // Only the wall-clock fields plus the launch time zone; extra components
            // (weekday, nanosecond…) would make the calendar trigger never match.
            let parts = Calendar(identifier: .gregorian).dateComponents(in: Self.productHuntLaunchTimeZone, from: date)
            var components = DateComponents()
            components.timeZone = Self.productHuntLaunchTimeZone
            components.year = parts.year
            components.month = parts.month
            components.day = parts.day
            components.hour = parts.hour
            components.minute = parts.minute
            trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        case .fireNow, .skip:
            trigger = nil
        }

        let request = UNNotificationRequest(identifier: Self.productHuntLaunchNotificationID, content: content, trigger: trigger)
        do {
            try await PendingNotificationRequests.addAfterPendingChanges(request)
            UserDefaults.standard.set(true, forKey: Self.productHuntLaunchScheduledKey)
        } catch {
            // Best-effort; retried on the next launch.
        }
    }

    // MARK: - Cancel All

    func cancelAllNotifications() {
        UserDefaults.standard.removeObject(forKey: "hosted.trialReminderExpiry")
        PendingNotificationRequests.removeAll()
    }

    // MARK: - Streak Computation

    private func computeCurrentStreak(foodStore: FoodStore) -> Int {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)
        var count = 0
        var day = today
        while true {
            let dayEntries = foodStore.entries(for: day)
            if dayEntries.isEmpty { break }
            count += 1
            guard let prev = calendar.date(byAdding: .day, value: -1, to: day) else { break }
            day = prev
        }
        return count
    }
}
