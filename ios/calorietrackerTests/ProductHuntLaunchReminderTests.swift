import Foundation
import Testing
@testable import calorietracker

struct ProductHuntLaunchReminderTests {
    private let launch = NotificationManager.productHuntLaunchDate

    @Test func launchDateIsMidnightPacificOnSept27() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = NotificationManager.productHuntLaunchTimeZone
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: launch)
        #expect(parts.year == 2026)
        #expect(parts.month == 9)
        #expect(parts.day == 27)
        #expect(parts.hour == 0)
        #expect(parts.minute == 1)
    }

    @Test func beforeLaunchSchedulesForLaunchMoment() {
        let now = launch.addingTimeInterval(-7 * 24 * 60 * 60)
        #expect(NotificationManager.productHuntLaunchPlan(now: now, launch: launch) == .schedule(launch))
    }

    @Test func duringLaunchDayFiresImmediately() {
        let now = launch.addingTimeInterval(6 * 60 * 60)
        #expect(NotificationManager.productHuntLaunchPlan(now: now, launch: launch) == .fireNow)
    }

    @Test func afterLaunchDaySkips() {
        let now = launch.addingTimeInterval(25 * 60 * 60)
        #expect(NotificationManager.productHuntLaunchPlan(now: now, launch: launch) == .skip)
    }
}
