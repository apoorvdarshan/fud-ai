import Charts
import SwiftUI

enum ProgressHistoryCountText {
    static func localized(_ count: Int) -> String {
        String(localized: "\(count) entry · tap to view or delete")
    }
}

enum ProgressMetric: String, CaseIterable, Identifiable, Equatable {
    case weight
    case bodyFat
    case workouts
    case heartRate

    var id: String { rawValue }

    var title: String {
        switch self {
        case .weight: String(localized: "Weight")
        case .bodyFat: String(localized: "Body Fat")
        case .workouts: String(localized: "Workouts")
        case .heartRate: String(localized: "Pulse")
        }
    }

    var icon: String {
        switch self {
        case .weight: "scalemass.fill"
        case .bodyFat: "percent"
        case .workouts: "dumbbell.fill"
        case .heartRate: "heart.fill"
        }
    }

    static func available(bodyFatAvailable: Bool, workoutBurnAvailable: Bool) -> [ProgressMetric] {
        var metrics: [ProgressMetric] = [.weight]
        if bodyFatAvailable { metrics.append(.bodyFat) }
        if workoutBurnAvailable { metrics.append(.workouts) }
        metrics.append(.heartRate)
        return metrics
    }
}

struct ProgressOverviewModeSelector: View {
    @Binding var selection: ProgressOverviewMode

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 6) {
                    ForEach(ProgressOverviewMode.allCases) { mode in
                        Button {
                            withAnimation(.snappy) { selection = mode }
                        } label: {
                            Label(mode.title, systemImage: mode.icon)
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                .foregroundStyle(selection == mode ? Color.white : Color.secondary)
                                .lineLimit(1)
                                .padding(.horizontal, 14)
                                .frame(minHeight: 44)
                                .background {
                                    if selection == mode {
                                        Capsule().fill(
                                            LinearGradient(
                                                colors: AppColors.calorieGradient,
                                                startPoint: .leading,
                                                endPoint: .trailing
                                            )
                                        )
                                    } else {
                                        Capsule().fill(Color.clear)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selection == mode ? .isSelected : [])
                        .id(mode)
                    }
                }
                .padding(4)
            }
            .scrollIndicators(.hidden)
            .background(AppColors.appCard, in: Capsule())
            .overlay {
                Capsule().stroke(AppColors.calorie.opacity(0.12), lineWidth: 0.75)
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(WeeklyChallengeL10n.text("Progress view"))
            .onChange(of: selection) { _, selected in
                withAnimation(.snappy) {
                    proxy.scrollTo(selected, anchor: .center)
                }
            }
        }
    }
}

struct ProgressMetricSelector: View {
    let metrics: [ProgressMetric]
    @Binding var selection: ProgressMetric

    var body: some View {
        Picker("Progress metric", selection: $selection) {
            ForEach(metrics) { metric in
                Text(metric.title).tag(metric)
            }
        }
        .pickerStyle(.segmented)
        .tint(AppColors.calorie)
        .accessibilityLabel("Progress metric")
        .onChange(of: metrics) { _, available in
            if !available.contains(selection), let first = available.first {
                selection = first
            }
        }
    }
}

struct WorkoutBurnDay: Identifiable, Equatable {
    let date: Date
    let calories: Int
    var id: Date { date }
}

enum WorkoutBurnAggregation {
    static let reliableCalories = 1...5_000

    static func isReliable(_ calories: Int?) -> Bool {
        guard let calories else { return false }
        return reliableCalories.contains(calories)
    }

    /// The burn calculator owns one estimate per diary day. Older data or a
    /// restore race can still contain duplicates, so choose the newest/highest
    /// sync-version record instead of summing and overstating the workout.
    static func daily(
        sessions: [StrengthWorkoutSession],
        in range: ClosedRange<Date>,
        calendar: Calendar = .current
    ) -> [WorkoutBurnDay] {
        var preferredByDay: [String: StrengthWorkoutSession] = [:]
        for session in sessions {
            guard isReliable(session.caloriesBurned) else { continue }
            let day = calendar.startOfDay(for: session.calendarDiaryDate)
            guard range.contains(day) else { continue }
            let key = session.stableDiaryDateKey
            if let current = preferredByDay[key], !shouldPrefer(session, over: current) { continue }
            preferredByDay[key] = session
        }
        return preferredByDay.values.compactMap { session in
            guard let calories = session.caloriesBurned else { return nil }
            return WorkoutBurnDay(
                date: calendar.startOfDay(for: session.calendarDiaryDate),
                calories: calories
            )
        }
        .sorted { $0.date < $1.date }
    }

    private static func shouldPrefer(
        _ candidate: StrengthWorkoutSession,
        over current: StrengthWorkoutSession
    ) -> Bool {
        let candidateVersion = candidate.healthSyncVersion ?? 0
        let currentVersion = current.healthSyncVersion ?? 0
        if candidateVersion != currentVersion { return candidateVersion > currentVersion }
        return candidate.completedAt > current.completedAt
    }
}

struct WorkoutBurnChartSection: View {
    let sessions: [StrengthWorkoutSession]
    let dateRange: ClosedRange<Date>

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var days: [WorkoutBurnDay] {
        WorkoutBurnAggregation.daily(sessions: sessions, in: dateRange)
    }

    private var total: Int { days.reduce(0) { $0 + $1.calories } }
    private var average: Int { days.isEmpty ? 0 : Int((Double(total) / Double(days.count)).rounded()) }
    private var latest: Int { days.last?.calories ?? 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Calculated Workout Burn", systemImage: "flame.fill")
                .font(.system(.headline, design: .rounded, weight: .semibold))

            if days.isEmpty {
                ProgressMetricEmptyState("No calculated workout burn in this range")
            } else {
                workoutStatBadges

                Chart(days) { day in
                    BarMark(
                        x: .value("Date", day.date, unit: .day),
                        y: .value("Calculated calories burned", day.calories)
                    )
                    .foregroundStyle(
                        LinearGradient(
                            colors: AppColors.calorieGradient,
                            startPoint: .bottom,
                            endPoint: .top
                        )
                    )
                    .clipShape(.rect(cornerRadius: 4))
                }
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 5)) { _ in
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6, dash: [3, 4]))
                            .foregroundStyle(Color.primary.opacity(0.11))
                        AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                            .foregroundStyle(Color.secondary)
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .trailing, values: .automatic(desiredCount: 5)) {
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6))
                            .foregroundStyle(Color.primary.opacity(0.10))
                        AxisValueLabel()
                            .foregroundStyle(Color.secondary)
                    }
                }
                .chartPlotStyle { plotArea in
                    plotArea.background(
                        AppColors.calorie.opacity(0.025),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
                }
                .frame(height: 190)
            }

            Text("Only workout burns calculated in Fud AI are shown. Workouts without a burn estimate are not included.")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .padding()
        .progressMetricCardStyle()
    }

    @ViewBuilder
    private var workoutStatBadges: some View {
        if dynamicTypeSize.isAccessibilitySize {
            LazyVGrid(
                columns: [GridItem(.flexible()), GridItem(.flexible())],
                spacing: 8
            ) {
                workoutStatBadgeContent
            }
        } else {
            HStack(spacing: 8) {
                workoutStatBadgeContent
            }
        }
    }

    @ViewBuilder
    private var workoutStatBadgeContent: some View {
        StatBadge(label: "Total", value: "\(total.formatted()) kcal")
        StatBadge(label: "Average", value: "\(average.formatted()) kcal")
        StatBadge(label: "Latest", value: "\(latest.formatted()) kcal")
        StatBadge(label: "Days", value: days.count.formatted())
    }
}

struct HeartRateChartSection: View {
    let entries: [HeartRateEntry]
    let onMeasure: () -> Void
    let onLogManual: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var sortedEntries: [HeartRateEntry] { entries.sorted { $0.date < $1.date } }
    private var latest: HeartRateEntry? { sortedEntries.last }
    private var average: Int {
        guard !entries.isEmpty else { return 0 }
        return Int((Double(entries.reduce(0) { $0 + $1.bpm }) / Double(entries.count)).rounded())
    }
    private var minimum: Int { entries.map(\.bpm).min() ?? 0 }
    private var maximum: Int { entries.map(\.bpm).max() ?? 0 }
    private var xAxisStyle: HeartRateChartAxisStyle {
        HeartRateChartAxisStyle.resolve(dates: sortedEntries.map(\.date))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Heart Rate", systemImage: "heart.fill")
                .font(.system(.headline, design: .rounded, weight: .semibold))

            if entries.isEmpty {
                ProgressMetricEmptyState("Measure or log your first heart rate to see a trend")
            } else {
                heartRateStatBadges

                Chart {
                    ForEach(sortedEntries) { entry in
                        LineMark(
                            x: .value("Measurement time", entry.date),
                            y: .value("Heart rate in beats per minute", entry.bpm)
                        )
                        .foregroundStyle(
                            LinearGradient(
                                colors: AppColors.calorieGradient,
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .interpolationMethod(.monotone)

                        if sortedEntries.count <= 60 {
                            PointMark(
                                x: .value("Measurement time", entry.date),
                                y: .value("Heart rate in beats per minute", entry.bpm)
                            )
                            .foregroundStyle(AppColors.calorie)
                            .symbolSize(34)
                        }
                    }

                    RuleMark(y: .value("Average heart rate", average))
                        .foregroundStyle(AppColors.calorie.opacity(0.35))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [5, 4]))
                }
                .chartYScale(domain: heartRateDomain)
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: xAxisStyle.desiredCount)) { _ in
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6, dash: [3, 4]))
                            .foregroundStyle(Color.primary.opacity(0.11))
                        AxisValueLabel(format: xAxisStyle.formatStyle)
                            .foregroundStyle(Color.secondary)
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .trailing, values: .automatic(desiredCount: 5)) {
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.6))
                            .foregroundStyle(Color.primary.opacity(0.10))
                        AxisValueLabel()
                            .foregroundStyle(Color.secondary)
                    }
                }
                .chartPlotStyle { plotArea in
                    plotArea.background(
                        AppColors.calorie.opacity(0.025),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
                }
                .frame(height: 190)
            }

            if dynamicTypeSize.isAccessibilitySize {
                VStack(spacing: 10) { heartRateActionButtons }
                    .controlSize(.large)
            } else {
                HStack(spacing: 10) { heartRateActionButtons }
                    .controlSize(.large)
            }

            Text("Camera heart-rate estimates are for general wellness only. Fud AI is not a medical device, and measurements may be inaccurate. Do not use them for diagnosis or emergencies.")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .padding()
        .progressMetricCardStyle()
    }

    @ViewBuilder
    private var heartRateStatBadges: some View {
        if dynamicTypeSize.isAccessibilitySize {
            LazyVGrid(
                columns: [GridItem(.flexible()), GridItem(.flexible())],
                spacing: 8
            ) {
                heartRateStatBadgeContent
            }
        } else {
            HStack(spacing: 8) {
                heartRateStatBadgeContent
            }
        }
    }

    @ViewBuilder
    private var heartRateStatBadgeContent: some View {
        StatBadge(label: "Latest", value: "\((latest?.bpm ?? 0).formatted()) bpm")
        StatBadge(label: "Average", value: "\(average.formatted()) bpm")
        StatBadge(label: "Minimum", value: "\(minimum.formatted()) bpm")
        StatBadge(label: "Maximum", value: "\(maximum.formatted()) bpm")
    }

    @ViewBuilder
    private var heartRateActionButtons: some View {
        Button(action: onMeasure) {
            Label("Measure Heart Rate", systemImage: "camera.fill")
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        .tint(AppColors.calorie)

        Button(action: onLogManual) {
            Label("Log Manually", systemImage: "square.and.pencil")
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .tint(AppColors.calorie)
    }

    private var heartRateDomain: ClosedRange<Int> {
        guard let minValue = entries.map(\.bpm).min(), let maxValue = entries.map(\.bpm).max() else {
            return 40...180
        }
        let padding = max(5, Int((Double(maxValue - minValue) * 0.15).rounded(.up)))
        let lower = max(HeartRateStore.validBPMRange.lowerBound, minValue - padding)
        let upper = min(HeartRateStore.validBPMRange.upperBound, maxValue + padding)
        return lower...upper
    }
}

enum HeartRateChartAxisStyle: Equatable {
    case timeWithSeconds
    case time
    case date

    static func resolve(
        dates: [Date],
        calendar: Calendar = .current
    ) -> HeartRateChartAxisStyle {
        guard let first = dates.min(), let last = dates.max() else { return .date }
        guard calendar.isDate(first, inSameDayAs: last) else { return .date }

        // Automatic chart ticks can be only seconds apart for back-to-back
        // readings. Including seconds keeps every visible label distinct.
        if dates.count > 1, last.timeIntervalSince(first) < 10 * 60 {
            return .timeWithSeconds
        }
        return .time
    }

    var desiredCount: Int {
        switch self {
        case .timeWithSeconds: 3
        case .time: 4
        case .date: 5
        }
    }

    var formatStyle: Date.FormatStyle {
        switch self {
        case .timeWithSeconds:
            Date.FormatStyle(date: .omitted, time: .standard)
        case .time:
            Date.FormatStyle(date: .omitted, time: .shortened)
        case .date:
            Date.FormatStyle(date: .abbreviated, time: .omitted)
        }
    }
}

struct HeartRateHistoryLink: View {
    let totalCount: Int
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(systemName: "heart.text.square.fill")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Heart Rate History")
                        .font(.system(.body, design: .rounded, weight: .medium))
                        .foregroundStyle(.primary)
                    Text(ProgressHistoryCountText.localized(totalCount))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 14)
            .background(AppColors.appCard)
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(AppColors.calorie.opacity(0.09), lineWidth: 0.75)
            }
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens heart rate history")
    }
}

struct HeartRateHistoryView: View {
    let entries: [HeartRateEntry]
    let onDelete: (HeartRateEntry) -> Bool

    @Environment(\.dismiss) private var dismiss
    @State private var pendingDeletion: HeartRateEntry?
    @State private var visibleEntries: [HeartRateEntry] = []
    @State private var showDeleteFailed = false

    var body: some View {
        NavigationStack {
            List {
                ForEach(visibleEntries) { entry in
                    HStack(spacing: 12) {
                        Image(systemName: entry.source == .camera ? "camera.fill" : "square.and.pencil")
                            .foregroundStyle(AppColors.calorie)
                            .frame(width: 24)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(entry.bpm.formatted()) bpm")
                                .font(.system(.body, design: .rounded, weight: .medium))
                            Text(entry.date.formatted(date: .abbreviated, time: .shortened))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(entry.source.displayName)
                            if let quality = entry.quality {
                                Text("Quality \(Int((quality * 100).rounded()))%")
                            }
                        }
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) { pendingDeletion = entry } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Heart Rate History")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { visibleEntries = entries.sorted { $0.date > $1.date } }
        .alert("Delete Heart Rate Entry", isPresented: Binding(
            get: { pendingDeletion != nil },
            set: { if !$0 { pendingDeletion = nil } }
        )) {
            Button("Cancel", role: .cancel) { pendingDeletion = nil }
            Button("Delete", role: .destructive) {
                if let entry = pendingDeletion {
                    if onDelete(entry) {
                        visibleEntries.removeAll { $0.id == entry.id }
                    } else {
                        showDeleteFailed = true
                    }
                }
                pendingDeletion = nil
            }
        } message: {
            if let entry = pendingDeletion {
                Text("Remove the \(entry.bpm.formatted()) bpm reading from \(entry.date.formatted(date: .abbreviated, time: .shortened))?")
            }
        }
        .alert("Heart Rate", isPresented: $showDeleteFailed) {
            Button("OK", role: .cancel) { }
        } message: {
            Text("Your existing heart-rate history could not be read, so Fud AI left it untouched. Use Delete All Data in Settings only if you want to discard it, then try again.")
        }
    }
}

struct LogHeartRateSheet: View {
    let initialBPM: Int
    let onSave: (Int, Date) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var bpm: Int
    @State private var date = Date.now

    init(initialBPM: Int, onSave: @escaping (Int, Date) -> Void) {
        self.initialBPM = initialBPM
        self.onSave = onSave
        _bpm = State(initialValue: min(max(initialBPM, HeartRateStore.validBPMRange.lowerBound), HeartRateStore.validBPMRange.upperBound))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Heart Rate") {
                    Picker("Beats per minute", selection: $bpm) {
                        ForEach(HeartRateStore.validBPMRange, id: \.self) { value in
                            Text("\(value) bpm").tag(value)
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(height: 150)

                    DatePicker("Date and Time", selection: $date, in: ...Date.now)
                }

                Section {
                    Text("Manual heart-rate entries are for personal tracking and general wellness only, not diagnosis or emergencies.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Log Heart Rate")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(bpm, date)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct ProgressMetricEmptyState: View {
    let message: LocalizedStringKey

    init(_ message: LocalizedStringKey) {
        self.message = message
    }

    var body: some View {
        Text(message)
            .font(.system(.subheadline, design: .rounded))
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 80)
    }
}

private struct ProgressMetricCardStyle: ViewModifier {
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        content
            .background(AppColors.appCard)
            .overlay { shape.stroke(AppColors.calorie.opacity(0.09), lineWidth: 0.75) }
            .compositingGroup()
            .clipShape(shape)
    }
}

private extension View {
    func progressMetricCardStyle() -> some View {
        modifier(ProgressMetricCardStyle())
    }
}
