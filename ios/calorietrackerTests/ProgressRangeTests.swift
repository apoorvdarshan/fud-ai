import CoreGraphics
import Foundation
import Testing
@testable import calorietracker

struct ProgressRangeTests {
    @Test func dayRangeStepsBackWholeContiguousWindows() throws {
        let calendar = utcCalendar
        let today = try date(2026, 10, 17, hour: 15, calendar: calendar)

        let current = TimeRange.week.dayRange(offset: 0, endingAt: today, calendar: calendar)
        let previous = TimeRange.week.dayRange(offset: 1, endingAt: today, calendar: calendar)
        let twoBack = TimeRange.week.dayRange(offset: 2, endingAt: today, calendar: calendar)

        let expectedCurrent = try date(2026, 10, 11, calendar: calendar)...date(2026, 10, 17, calendar: calendar)
        let expectedPrevious = try date(2026, 10, 4, calendar: calendar)...date(2026, 10, 10, calendar: calendar)
        let expectedTwoBackEnd = try date(2026, 10, 3, calendar: calendar)
        #expect(current == expectedCurrent)
        #expect(previous == expectedPrevious)
        #expect(twoBack.upperBound == expectedTwoBackEnd)
    }

    @Test func everyRangeCoversItsDayCountAtAnyOffset() {
        let calendar = utcCalendar
        let today = Date(timeIntervalSince1970: 1_792_000_000)
        for range in TimeRange.allCases {
            for offset in 0...2 {
                let days = range.dayRange(offset: offset, endingAt: today, calendar: calendar)
                let span = calendar.dateComponents([.day], from: days.lowerBound, to: days.upperBound).day
                #expect(span == range.days - 1)
            }
        }
    }

    @Test func timestampRangeIncludesReadingsLoggedLaterOnTheLastDay() throws {
        let calendar = utcCalendar
        let now = try date(2026, 10, 17, hour: 8, calendar: calendar)
        let laterToday = try date(2026, 10, 17, hour: 23, minute: 30, calendar: calendar)
        let tomorrow = try date(2026, 10, 18, calendar: calendar)

        let range = TimeRange.week.timestampRange(endingAt: now, calendar: calendar)

        let expectedStart = try date(2026, 10, 11, calendar: calendar)
        #expect(range.contains(now))
        #expect(range.contains(laterToday))
        #expect(!range.contains(tomorrow))
        #expect(range.lowerBound == expectedStart)
    }

    @Test func periodNavigationIsOnlyUnavailableForAllTime() {
        #expect(TimeRange.allCases.filter { !$0.supportsPeriodNavigation } == [.allTime])
    }

    @Test func periodLabelShowsTheYearOnlyOutsideTheCurrentYear() throws {
        let calendar = utcCalendar
        let now = try date(2026, 6, 20, hour: 12, calendar: calendar)
        let thisYear = TimeRange.week.dayRange(endingAt: now, calendar: calendar)
        let lastYearEnd = try date(2025, 6, 20, hour: 12, calendar: calendar)
        let lastYear = TimeRange.week.dayRange(endingAt: lastYearEnd, calendar: calendar)

        #expect(!TimeRange.periodLabel(for: thisYear, now: now, calendar: calendar).contains("2026"))
        #expect(TimeRange.periodLabel(for: lastYear, now: now, calendar: calendar).contains("2025"))
    }

    @Test func foodStatsOnlyCountDaysInsideThePastWindow() throws {
        let calendar = utcCalendar
        let endDay = try date(2026, 10, 10, calendar: calendar)
        let entries = try [
            food(calories: 2_000, protein: 100, at: date(2026, 10, 4, hour: 9, calendar: calendar)),
            food(calories: 1_000, protein: 50, at: date(2026, 10, 10, hour: 21, calendar: calendar)),
            // Outside the window on either side.
            food(calories: 3_000, protein: 300, at: date(2026, 10, 3, hour: 12, calendar: calendar)),
            food(calories: 3_000, protein: 300, at: date(2026, 10, 11, hour: 1, calendar: calendar)),
        ]

        let stats = ProgressFoodRangeStats.compute(
            entries: entries,
            dayCount: 7,
            endDay: endDay,
            profile: .default,
            optionalGoals: OptionalNutrientGoals(),
            calendar: calendar
        )

        let expectedDays = try [date(2026, 10, 4, calendar: calendar), date(2026, 10, 10, calendar: calendar)]
        #expect(stats.dailyCalories.map(\.calories) == [2_000, 1_000])
        #expect(stats.dailyCalories.map(\.date) == expectedDays)
        #expect(stats.avgProtein == 75)
    }

    @Test func nutritionIncludesTodayOnlyInTheCurrentWindow() throws {
        let calendar = utcCalendar
        let now = try date(2026, 10, 17, hour: 9, calendar: calendar)
        let entries = try [
            food(calories: 2_000, protein: 120, at: date(2026, 10, 15, hour: 13, calendar: calendar)),
            food(calories: 2_200, protein: 140, at: date(2026, 10, 16, hour: 19, calendar: calendar)),
            // Breakfast only so far today.
            food(calories: 400, protein: 20, at: date(2026, 10, 17, hour: 8, calendar: calendar)),
            food(calories: 1_800, protein: 100, at: date(2026, 10, 10, hour: 20, calendar: calendar)),
        ]

        func stats(offset: Int) -> ProgressFoodRangeStats {
            ProgressFoodRangeStats.compute(
                entries: entries,
                dayCount: TimeRange.week.days,
                endDay: TimeRange.week.dayRange(offset: offset, endingAt: now, calendar: calendar).upperBound,
                profile: .default,
                optionalGoals: OptionalNutrientGoals(),
                calendar: calendar
            )
        }

        let current = stats(offset: 0)
        let previous = stats(offset: 1)

        #expect(current.dailyCalories.map(\.calories) == [2_000, 2_200, 400])
        #expect(current.avgProtein == 280.0 / 3)
        #expect(previous.dailyCalories.map(\.calories) == [1_800])
        #expect(previous.avgProtein == 100)
    }

    @Test func onlyClearlyHorizontalDragsScrubTheCalorieChart() {
        #expect(CalorieChartSection.isClearlyHorizontal(CGSize(width: 20, height: 5)))
        #expect(CalorieChartSection.isClearlyHorizontal(CGSize(width: -20, height: 4)))
        // Diagonal and vertical drags are the page scroll: they must not move or
        // clear a value pinned by tap.
        #expect(!CalorieChartSection.isClearlyHorizontal(CGSize(width: 10, height: 9)))
        #expect(!CalorieChartSection.isClearlyHorizontal(CGSize(width: 3, height: -30)))
        #expect(!CalorieChartSection.isClearlyHorizontal(CGSize(width: 0, height: 6)))
    }

    private var utcCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private func date(
        _ year: Int,
        _ month: Int,
        _ day: Int,
        hour: Int = 0,
        minute: Int = 0,
        calendar: Calendar
    ) throws -> Date {
        try #require(calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute)))
    }

    private func food(calories: Int, protein: Double, at timestamp: Date) -> FoodEntry {
        FoodEntry(
            name: "Meal",
            calories: calories,
            protein: protein,
            carbs: 0,
            fat: 0,
            timestamp: timestamp,
            source: .manual
        )
    }
}
