import SwiftUI

/// Recap of the most recent completed week at the top of Progress (#431),
/// compared with the week before it. The arrows browse earlier weeks.
struct WeeklyRecapCard: View {
    @Environment(FoodStore.self) private var foodStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(StrengthWorkoutStore.self) private var strengthWorkoutStore
    @AppStorage("weightUnit") private var weightUnitRaw = "lbs"
    @AppStorage("weekStartsOnMonday") private var weekStartsOnMonday = true
    @AppStorage(EnergyUnit.storageKey) private var energyUnit = EnergyUnit.defaultUnit
    @AppStorage("displayCalorieFloor") private var calorieFloor = 0
    @AppStorage("displayCalorieCeiling") private var calorieCeiling = 0
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.scenePhase) private var scenePhase
    /// Weeks stepped back from the most recent completed one; session-only.
    @State private var weekOffset = 0

    private var recap: WeeklyRecap {
        // Weeks follow Settings > Week Starts On, like the Home and Workouts week strips.
        let calendar = WeeklyRecap.calendar(weekStartsOnMonday: weekStartsOnMonday)
        let now = Date.now
        // Both compared weeks: the one before the recapped week through its last day.
        let comparedStart = WeeklyRecap.weekStart(offset: weekOffset + 1, now: now, calendar: calendar)
        let recappedStart = WeeklyRecap.weekStart(offset: weekOffset, now: now, calendar: calendar)
        let recappedEnd = calendar.date(byAdding: .day, value: 6, to: recappedStart) ?? recappedStart
        let profile = profileStore.profile
        return WeeklyRecap.compute(
            offset: weekOffset,
            now: now,
            calendar: calendar,
            foods: foodStore.entries,
            workouts: WeeklyRecap.workoutDays(
                in: comparedStart...recappedEnd,
                dayPlans: strengthWorkoutStore.dayPlans,
                sessions: strengthWorkoutStore.completedSessions,
                calendar: calendar
            ),
            weights: weightStore.entries,
            calorieGoal: profile.effectiveCalories,
            proteinGoal: profile.effectiveProtein
        )
    }

    var body: some View {
        // Reading the scene phase recomputes on return to the app, so a week that
        // ended while it was in the background rolls the recap over.
        let _ = scenePhase
        let recap = recap
        VStack(alignment: .leading, spacing: 12) {
            Label {
                Text("Weekly Recap")
            } icon: {
                Image(systemName: "sparkles")
                    .foregroundStyle(AppColors.calorie)
            }
            .font(.system(.headline, design: .rounded, weight: .semibold))

            ProgressPeriodNavigator(
                label: TimeRange.periodLabel(for: recap.days),
                canGoForward: weekOffset > 0,
                onPrevious: { weekOffset += 1 },
                onNext: { weekOffset = max(0, weekOffset - 1) },
                previousLabel: "Previous week",
                nextLabel: "Next week",
                isEmbedded: true
            )

            if recap.current.hasActivity {
                stats(recap.current, previous: recap.previous)
                if calorieFloor > 0 || calorieCeiling > 0 {
                    let bounds = [calorieFloor, calorieCeiling].map { $0 > 0 ? energyUnit.format(kcal: $0) : "—" }
                    LabeledContent("Optional Calorie Range") {
                        Text(verbatim: bounds.joined(separator: " – "))
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }

                if let change = recap.current.weightChangeKg {
                    HStack {
                        Label("Weight change", systemImage: "scalemass.fill")
                            .font(.system(.subheadline, design: .rounded, weight: .medium))
                        Spacer()
                        Text(formattedWeightChange(change))
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .monospacedDigit()
                    }
                    .accessibilityElement(children: .combine)
                }

                if !recap.highlights.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(recap.highlights, id: \.self) { highlight in
                            Label {
                                Text(highlight.text)
                                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                                    .fixedSize(horizontal: false, vertical: true)
                            } icon: {
                                Image(systemName: highlight.icon)
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                    }
                }

                Text("Changes are compared with the previous week.")
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
            } else {
                ProgressMetricEmptyState("Nothing logged this week")
            }
        }
        .padding()
        .progressCardStyle()
    }

    private func stats(_ current: WeeklyRecapWeekStats, previous: WeeklyRecapWeekStats) -> some View {
        let columns = dynamicTypeSize.isAccessibilitySize ? 1 : 3
        // Energy shows in the chosen unit (#520); the comparisons stay kcal.
        let energy = WeeklyRecap.energyFigures(
            current: current,
            previous: previous,
            calorieGoal: profileStore.profile.effectiveCalories,
            unit: energyUnit
        )
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: columns), spacing: 8) {
            WeeklyRecapStatTile(
                value: dayCount(current.loggedDays),
                label: "Days logged",
                change: current.loggedDays - previous.loggedDays
            )
            WeeklyRecapStatTile(
                value: dayCount(current.calorieTargetDays),
                label: "Calories on target",
                change: current.calorieTargetDays - previous.calorieTargetDays
            )
            WeeklyRecapStatTile(
                value: dayCount(current.proteinGoalDays),
                label: "Protein goal reached",
                change: current.proteinGoalDays - previous.proteinGoalDays
            )
            WeeklyRecapStatTile(
                // kcal keeps the bare number; kJ names its unit so "Avg calories" isn't misread.
                value: energyUnit == .kj
                    ? (current.averageCalories.map { energyUnit.format(kcal: $0) } ?? "–")
                    : (energy.averageCalories ?? "–"),
                label: "Avg calories",
                caption: String(localized: "Target \(energy.calorieGoal)")
            )
            WeeklyRecapStatTile(
                value: current.workouts.formatted(),
                label: "Workouts",
                change: current.workouts - previous.workouts
            )
            WeeklyRecapStatTile(
                value: energy.activeBurn,
                label: "Active burn",
                change: energy.activeBurnChange
            )
        }
    }

    /// "5/7", with both numbers in the locale's digits.
    private func dayCount(_ days: Int) -> String {
        "\(days.formatted())/\(7.formatted())"
    }

    /// Same rounding as Android: a change under 0.05 shows as 0.0, never -0.0.
    private func formattedWeightChange(_ kg: Double) -> String {
        let useMetric = weightUnitRaw == "kg"
        let display = useMetric ? kg : kg * 2.20462
        let rounded = abs(display) < 0.05 ? 0 : display
        let sign = rounded > 0 ? "+" : ""
        return String(format: "%@%.1f %@", sign, rounded, useMetric ? "kg" : "lbs")
    }
}

private struct WeeklyRecapStatTile: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let value: String
    let label: LocalizedStringKey
    /// The secondary line: a change vs the previous week, or a plain caption.
    private let detail: String
    private let isPositiveChange: Bool

    init(value: String, label: LocalizedStringKey, change: Int) {
        self.value = value
        self.label = label
        detail = WeeklyRecap.signedChange(change)
        isPositiveChange = change > 0
    }

    init(value: String, label: LocalizedStringKey, caption: String) {
        self.value = value
        self.label = label
        detail = caption
        isPositiveChange = false
    }

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(.subheadline, design: .rounded, weight: .bold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            // Two lines reserved in every tile keep the grid's rows even.
            Text(label)
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                .minimumScaleFactor(0.8)
            Text(detail)
                .font(.system(.caption2, design: .rounded, weight: .semibold))
                .foregroundStyle(isPositiveChange ? AppColors.calorie : Color.secondary)
                .monospacedDigit()
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 4)
        .padding(.vertical, 8)
        .background(
            AppColors.calorie.opacity(0.055),
            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
        )
        .accessibilityElement(children: .combine)
    }
}

extension WeeklyRecapHighlight {
    var text: String {
        switch self {
        case .loggedEveryDay:
            String(localized: "Logged every day")
        case .loggingStreak(let days):
            String(localized: "Best streak: \(days) days in a row")
        case .moreWorkouts(let count):
            String(localized: "+\(count) workouts vs previous week")
        case .moreCalorieTargetDays(let count):
            String(localized: "+\(count) days on calorie target vs previous week")
        case .moreProteinGoalDays(let count):
            String(localized: "+\(count) protein goal days vs previous week")
        case .averageCaloriesOnTarget:
            String(localized: "Average calories on target")
        case .moreLoggedDays(let count):
            String(localized: "+\(count) days logged vs previous week")
        }
    }

    var icon: String {
        switch self {
        case .loggedEveryDay: "checkmark.seal.fill"
        case .loggingStreak: "flame.fill"
        case .moreWorkouts: "dumbbell.fill"
        case .moreCalorieTargetDays: "target"
        case .moreProteinGoalDays: "bolt.fill"
        case .averageCaloriesOnTarget: "scope"
        case .moreLoggedDays: "calendar.badge.checkmark"
        }
    }
}
