import Foundation
import UIKit
import UserNotifications

/// Owns the one rest countdown that runs after a logged strength set. It lives
/// for the whole app session, so switching tabs or days keeps the countdown, and
/// the running rest is saved, so a relaunch brings it back.
///
/// Every rest is announced exactly once. One that ends while Fud AI is open is
/// marked with a haptic and its notification is withdrawn when haptics are enabled.
/// Any other rest is announced
/// by its local notification (only when the app's notifications are on), which
/// AppDelegate also presents if it arrives once the app is open again
/// (`presentsNotificationInForeground`).
@Observable
final class WorkoutRestTimerController {
    static let shared = WorkoutRestTimerController()
    static let notificationID = "workout.rest.complete"
    /// The notification's rest end, in seconds since 1970.
    static let endsAtUserInfoKey = "restEndsAt"
    static let storageKey = "workoutRestTimer.running"

    private(set) var timer: WorkoutRestTimer?
    @ObservationIgnored private var completionTask: Task<Void, Never>?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let isAppActive: () -> Bool
    /// Sets whose rest already started; filling their reps in again is a correction.
    @ObservationIgnored private var restedSetIDs: Set<UUID> = []
    /// The end of the last rest that finished without its haptic.
    @ObservationIgnored private var unannouncedEnd: Date?

    init(
        defaults: UserDefaults = .standard,
        isAppActive: @escaping () -> Bool = { UIApplication.shared.applicationState == .active },
        now: Date = .now
    ) {
        self.defaults = defaults
        self.isAppActive = isAppActive
        restore(now: now)
    }

    func start(exerciseID: UUID, setID: UUID, exerciseName: String, durationSeconds: Int, now: Date = .now) {
        guard let next = WorkoutRestTimer(
            exerciseID: exerciseID,
            setID: setID,
            exerciseName: exerciseName,
            durationSeconds: durationSeconds,
            now: now
        ) else { return }
        restedSetIDs.insert(setID)
        replace(with: next)
    }

    /// Whether a rest has already started after this set, in this run or before a relaunch.
    func hasRested(setID: UUID) -> Bool {
        restedSetIDs.contains(setID)
    }

    /// +15 s.
    func extend(now: Date = .now) {
        guard var current = timer else { return }
        current.extend(now: now)
        replace(with: current)
    }

    func skip() {
        completionTask?.cancel()
        completionTask = nil
        setTimer(nil)
        PendingNotificationRequests.remove([Self.notificationID])
    }

    /// Arms the notification again for a running rest, for when notifications
    /// are switched back on (switching them off removed every pending request).
    func rescheduleNotification() {
        guard let timer else { return }
        scheduleNotification(for: timer)
    }

    /// Whether a rest notification that arrives while Fud AI is open should show.
    /// Only for a rest that ended without its haptic: one still counting down, or
    /// one just skipped or extended, gets the haptic or nothing.
    func presentsNotificationInForeground(userInfo: [AnyHashable: Any]) -> Bool {
        let value = userInfo[Self.endsAtUserInfoKey]
        guard let endsAt = (value as? Double) ?? (value as? NSNumber)?.doubleValue else { return false }
        if let unannouncedEnd, abs(endsAt - unannouncedEnd.timeIntervalSince1970) < 0.001 {
            return true
        }
        // The notification can arrive just before the countdown task finishes.
        return !AppHaptics.isEnabled(in: defaults)
            && timer.map { abs(endsAt - $0.endsAt.timeIntervalSince1970) < 0.001 } == true
    }

    /// Brings back a rest saved by an earlier run of the app.
    private func restore(now: Date) {
        guard let data = defaults.data(forKey: Self.storageKey),
              let saved = try? JSONDecoder().decode(WorkoutRestTimer.self, from: data)
        else { return }
        restedSetIDs.insert(saved.setID)
        if saved.isFinished(at: now) {
            // It ended while the app was not running, so its notification announces it.
            unannouncedEnd = saved.endsAt
            setTimer(nil)
        } else {
            // Its notification is still pending; only the countdown comes back.
            timer = saved
            waitForEnd(of: saved)
        }
    }

    private func replace(with next: WorkoutRestTimer) {
        setTimer(next)
        scheduleNotification(for: next)
        waitForEnd(of: next)
    }

    private func waitForEnd(of next: WorkoutRestTimer) {
        completionTask?.cancel()
        let endsAt = next.endsAt
        completionTask = Task { [weak self] in
            // Waits on the wall clock the countdown shows, in steps of at most a
            // second, so a clock change during rest cannot leave it finished on
            // screen but still running.
            while true {
                let delay = endsAt.timeIntervalSinceNow
                guard delay > 0 else { break }
                try? await Task.sleep(for: .seconds(min(delay, 1)))
                guard !Task.isCancelled else { return }
            }
            self?.finish(expected: next)
        }
    }

    /// Runs when the countdown reaches zero, or as soon as a suspended app
    /// resumes after it did.
    private func finish(expected: WorkoutRestTimer, now: Date = .now) {
        guard timer == expected, expected.isFinished(at: now) else { return }
        if isAppActive(), expected.shouldAlertInApp(at: now), AppHaptics.isEnabled(in: defaults) {
            // Ended in front of the user: the haptic is the alert.
            PendingNotificationRequests.remove([Self.notificationID])
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        } else {
            // When no haptic will play, leave the scheduled notification intact.
            unannouncedEnd = expected.endsAt
        }
        setTimer(nil)
        completionTask = nil
    }

    private func setTimer(_ next: WorkoutRestTimer?) {
        timer = next
        if let next, let data = try? JSONEncoder().encode(next) {
            defaults.set(data, forKey: Self.storageKey)
        } else {
            defaults.removeObject(forKey: Self.storageKey)
        }
    }

    private func scheduleNotification(for timer: WorkoutRestTimer) {
        PendingNotificationRequests.remove([Self.notificationID])
        guard defaults.bool(forKey: "notificationsEnabled") else { return }

        let content = UNMutableNotificationContent()
        content.title = String(localized: "Rest Complete", comment: "Title of the notification sent when a workout rest countdown ends.")
        content.body = String(
            localized: "Time for your next set of \(timer.exerciseName).",
            comment: "Body of the rest timer notification. The argument is the exercise name, such as Bench Press."
        )
        content.sound = .default
        content.userInfo = [Self.endsAtUserInfoKey: timer.endsAt.timeIntervalSince1970]
        let message = content.copy() as! UNNotificationContent
        let endsAt = timer.endsAt
        let identifier = Self.notificationID

        // Measured when the request is added, so a slow earlier change cannot
        // push the alert past the end of the rest.
        PendingNotificationRequests.addWhenQueued {
            let interval = endsAt.timeIntervalSinceNow
            guard interval > 1 else { return nil }
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
            return UNNotificationRequest(identifier: identifier, content: message, trigger: trigger)
        }
    }
}
