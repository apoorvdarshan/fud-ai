import SwiftUI
import Charts

// MARK: - Time Range

enum TimeRange: String, CaseIterable {
    case week = "1W"
    case month = "1M"
    case threeMonths = "3M"
    case sixMonths = "6M"
    case year = "1Y"
    case allTime = "All"

    var days: Int {
        switch self {
        case .week: 7
        case .month: 30
        case .threeMonths: 90
        case .sixMonths: 180
        case .year: 365
        case .allTime: 3650
        }
    }

    func dateRange() -> ClosedRange<Date> {
        let calendar = Calendar.current
        let end = calendar.startOfDay(for: .now).addingTimeInterval(86399)
        let start = calendar.date(byAdding: .day, value: -(days - 1), to: calendar.startOfDay(for: .now))!
        return start...end
    }
}

// MARK: - Weight Chart Section

struct WeightChartSection: View {
    let weightEntries: [WeightEntry]
    let goalWeightKg: Double?
    let currentWeightKg: Double?
    let onLogWeight: () -> Void
    @AppStorage("useMetric") private var useMetric = false

    private func displayWeight(_ kg: Double) -> Double {
        useMetric ? kg : kg * 2.20462
    }

    private var unit: String { useMetric ? "kg" : "lbs" }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Weight")
                    .font(.system(.headline, design: .rounded, weight: .semibold))
                Spacer()
                Button(action: onLogWeight) {
                    Label("Log Weight", systemImage: "plus.circle.fill")
                        .font(.system(.subheadline, design: .rounded, weight: .medium))
                        .foregroundStyle(AppColors.calorie)
                }
            }

            if weightEntries.isEmpty {
                emptyState("Log your first weight to see trends")
            } else {
                // Current / Goal row
                HStack(spacing: 16) {
                    if let current = currentWeightKg {
                        StatBadge(label: "Current", value: String(format: "%.1f %@", displayWeight(current), unit))
                    }
                    if let goal = goalWeightKg {
                        StatBadge(label: "Goal", value: String(format: "%.1f %@", displayWeight(goal), unit))
                    }
                }

                Chart {
                    ForEach(weightEntries) { entry in
                        LineMark(
                            x: .value("Date", entry.date, unit: .day),
                            y: .value("Weight", displayWeight(entry.weightKg))
                        )
                        .foregroundStyle(AppColors.calorie)
                        .interpolationMethod(.catmullRom)
                        .lineStyle(StrokeStyle(lineWidth: 2))

                        PointMark(
                            x: .value("Date", entry.date, unit: .day),
                            y: .value("Weight", displayWeight(entry.weightKg))
                        )
                        .foregroundStyle(AppColors.calorie)
                        .symbolSize(30)
                    }

                    if let goalKg = goalWeightKg {
                        RuleMark(y: .value("Goal", displayWeight(goalKg)))
                            .foregroundStyle(.green.opacity(0.7))
                            .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                    }
                }
                .chartYScale(domain: weightYDomain)
                .chartXAxis {
                    AxisMarks(values: .stride(by: .day, count: xAxisStride)) { _ in
                        AxisGridLine()
                        AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                    }
                }
                .frame(height: 180)
                .clipped()
            }
        }
        .padding()
        .background(AppColors.appCard)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var xAxisStride: Int {
        let count = weightEntries.count
        if count <= 7 { return 1 }
        if count <= 30 { return 5 }
        if count <= 90 { return 14 }
        if count <= 180 { return 30 }
        return 60
    }

    private var weightYDomain: ClosedRange<Double> {
        var weights = weightEntries.map { displayWeight($0.weightKg) }
        if let goalKg = goalWeightKg { weights.append(displayWeight(goalKg)) }
        guard let minW = weights.min(), let maxW = weights.max() else { return 0...200 }
        let padding = max((maxW - minW) * 0.15, 2)
        return (minW - padding)...(maxW + padding)
    }
}

// MARK: - Calorie Chart Section

struct CalorieChartSection: View {
    let dailyCalories: [(date: Date, calories: Int)]
    let calorieGoal: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Calories")
                    .font(.system(.headline, design: .rounded, weight: .semibold))
                Spacer()
                if !dailyCalories.isEmpty {
                    let avg = dailyCalories.reduce(0) { $0 + $1.calories } / max(dailyCalories.count, 1)
                    Text("Avg: \(avg) kcal")
                        .font(.system(.subheadline, design: .rounded, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }

            if dailyCalories.isEmpty {
                emptyState("No food logged yet")
            } else {
                Chart {
                    ForEach(dailyCalories, id: \.date) { item in
                        BarMark(
                            x: .value("Date", item.date, unit: .day),
                            y: .value("Calories", item.calories)
                        )
                        .foregroundStyle(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .bottom, endPoint: .top)
                        )
                        .cornerRadius(4)
                    }

                    RuleMark(y: .value("Goal", calorieGoal))
                        .foregroundStyle(AppColors.calorie.opacity(0.6))
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                }
                .chartXAxis {
                    AxisMarks(values: .stride(by: .day, count: calorieXStride)) { _ in
                        AxisGridLine()
                        AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                    }
                }
                .frame(height: 180)
            }
        }
        .padding()
        .background(AppColors.appCard)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var calorieXStride: Int {
        let count = dailyCalories.count
        if count <= 7 { return 1 }
        if count <= 30 { return 5 }
        if count <= 90 { return 14 }
        if count <= 180 { return 30 }
        return 60
    }
}

// MARK: - Macro Averages Section

struct MacroAveragesSection: View {
    let avgProtein: Int
    let avgCarbs: Int
    let avgFat: Int
    let proteinGoal: Int
    let carbsGoal: Int
    let fatGoal: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Macro Averages")
                .font(.system(.headline, design: .rounded, weight: .semibold))

            MacroProgressRow(label: "Protein", current: avgProtein, goal: proteinGoal, color: AppColors.protein, gradientColors: AppColors.proteinGradient)
            MacroProgressRow(label: "Carbs", current: avgCarbs, goal: carbsGoal, color: AppColors.carbs, gradientColors: AppColors.carbsGradient)
            MacroProgressRow(label: "Fat", current: avgFat, goal: fatGoal, color: AppColors.fat, gradientColors: AppColors.fatGradient)
        }
        .padding()
        .background(AppColors.appCard)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct MacroProgressRow: View {
    let label: String
    let current: Int
    let goal: Int
    let color: Color
    let gradientColors: [Color]

    private var progress: Double {
        goal > 0 ? min(Double(current) / Double(goal), 1.0) : 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(label)
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                Spacer()
                Text("\(current)g / \(goal)g")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(color.opacity(0.12))

                    Capsule()
                        .fill(LinearGradient(colors: gradientColors, startPoint: .leading, endPoint: .trailing))
                        .frame(width: max(6, geo.size.width * progress))
                        .shadow(color: color.opacity(0.3), radius: 4, y: 2)
                }
            }
            .frame(height: 8)
        }
    }
}

// MARK: - Stats Section

struct StatsSection: View {
    let streak: Int
    let daysOnTarget: Int
    let totalEntries: Int
    let bestStreak: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Streaks & Stats")
                .font(.system(.headline, design: .rounded, weight: .semibold))

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                StatTile(icon: "flame.fill", label: "Current Streak", value: "\(streak) days", color: AppColors.calorie)
                StatTile(icon: "trophy.fill", label: "Best Streak", value: "\(bestStreak) days", color: AppColors.carbs)
                StatTile(icon: "target", label: "Days on Target", value: "\(daysOnTarget)", color: AppColors.protein)
                StatTile(icon: "fork.knife", label: "Total Entries", value: "\(totalEntries)", color: AppColors.fat)
            }
        }
        .padding()
        .background(AppColors.appCard)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct StatTile: View {
    let icon: String
    let label: String
    let value: String
    let color: Color

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(color)

            Text(value)
                .font(.system(.title3, design: .rounded, weight: .bold))

            Text(label)
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(color.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct StatBadge: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
            Text(label)
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Log Weight Sheet

struct LogWeightSheet: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("useMetric") private var useMetric = false
    let currentWeightKg: Double
    let onSave: (Double) -> Void

    @State private var wholeNumber: Int
    @State private var decimal: Int

    init(currentWeightKg: Double, onSave: @escaping (Double) -> Void) {
        self.currentWeightKg = currentWeightKg
        self.onSave = onSave
        // Respect @AppStorage at the time the sheet is created.
        let metric = UserDefaults.standard.bool(forKey: "useMetric")
        let displayValue = metric ? currentWeightKg : currentWeightKg * 2.20462
        let whole = Int(displayValue)
        let dec = min(9, max(0, Int((displayValue - Double(whole)) * 10 + 0.5)))
        _wholeNumber = State(initialValue: whole)
        _decimal = State(initialValue: dec)
    }

    private var selectedValue: Double {
        Double(wholeNumber) + Double(decimal) / 10.0
    }

    private var selectedKg: Double {
        useMetric ? selectedValue : selectedValue / 2.20462
    }

    private var unit: String { useMetric ? "kg" : "lbs" }
    private var wholeRange: ClosedRange<Int> { useMetric ? 20...250 : 50...500 }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Log Weight")
                    .font(.system(.title2, design: .rounded, weight: .bold))

                // Scroll wheel pickers
                HStack(spacing: 0) {
                    Picker("Whole", selection: $wholeNumber) {
                        ForEach(wholeRange, id: \.self) { num in
                            Text("\(num)").tag(num)
                                .font(.system(.title2, design: .rounded, weight: .medium))
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 100)
                    .clipped()

                    Text(".")
                        .font(.system(size: 36, weight: .bold, design: .rounded))
                        .offset(y: -1)

                    Picker("Decimal", selection: $decimal) {
                        ForEach(0...9, id: \.self) { num in
                            Text("\(num)").tag(num)
                                .font(.system(.title2, design: .rounded, weight: .medium))
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 70)
                    .clipped()

                    Text(unit)
                        .font(.system(.title3, design: .rounded))
                        .foregroundStyle(.secondary)
                        .padding(.leading, 4)
                }

                Button {
                    onSave(selectedKg)
                    dismiss()
                } label: {
                    Text("Save")
                        .font(.system(.headline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                        )
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .padding(.horizontal, 24)

                Spacer()
            }
            .padding(.top, 24)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Weight History Link (tap to open full list)

struct WeightHistoryLink: View {
    let totalCount: Int
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(systemName: "list.bullet.rectangle")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 28, height: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Weight History")
                        .font(.system(.body, design: .rounded, weight: .medium))
                        .foregroundStyle(.primary)
                    Text("\(totalCount) \(totalCount == 1 ? "entry" : "entries") · tap to view or delete")
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
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - All Weight History (full-screen sheet)

struct AllWeightHistoryView: View {
    let entries: [WeightEntry]
    let useMetric: Bool
    let onDelete: (WeightEntry) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var pendingDeletion: WeightEntry?
    // Local mirror so the list updates immediately after deletion without needing the parent to re-bind.
    @State private var visibleEntries: [WeightEntry] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(visibleEntries) { entry in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(displayWeight(entry.weightKg, useMetric: useMetric))
                                .font(.system(.body, design: .rounded, weight: .medium))
                            Text(weightHistoryFormatter.string(from: entry.date))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            pendingDeletion = entry
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Weight History")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { visibleEntries = entries }
        .alert("Delete Weight Entry", isPresented: Binding(
            get: { pendingDeletion != nil },
            set: { if !$0 { pendingDeletion = nil } }
        )) {
            Button("Cancel", role: .cancel) { pendingDeletion = nil }
            Button("Delete", role: .destructive) {
                if let entry = pendingDeletion {
                    visibleEntries.removeAll { $0.id == entry.id }
                    onDelete(entry)
                }
                pendingDeletion = nil
            }
        } message: {
            if let entry = pendingDeletion {
                Text("Remove \(weightHistoryFormatter.string(from: entry.date))'s entry of \(displayWeight(entry.weightKg, useMetric: useMetric))? This also deletes the matching sample from Apple Health.")
            }
        }
    }
}

private let weightHistoryFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "MMM d, yyyy"
    return f
}()

private func displayWeight(_ kg: Double, useMetric: Bool) -> String {
    if useMetric {
        return String(format: "%.1f kg", kg)
    }
    let lbs = kg * 2.20462
    return String(format: "%.1f lb", lbs)
}

// MARK: - Helpers

private func emptyState(_ message: String) -> some View {
    Text(message)
        .font(.system(.subheadline, design: .rounded))
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, minHeight: 80)
}
