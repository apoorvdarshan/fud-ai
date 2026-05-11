import SwiftUI

// MARK: - Week Day Selector

struct WeekEnergyStrip: View {
    @Binding var selectedDate: Date
    let caloriesForDate: (Date) -> Int
    let calorieGoal: Int
    @AppStorage("weekStartsOnMonday") private var weekStartsOnMonday = false
    @State private var hasScrolledToInitial = false

    private static let totalWeeks = 53 // ~1 year of history
    private static let currentWeekIndex = totalWeeks - 1

    private var calendar: Calendar {
        var cal = Calendar.current
        cal.firstWeekday = weekStartsOnMonday ? 2 : 1 // 2 = Monday, 1 = Sunday
        return cal
    }

    private func weekDates(for weekOffset: Int) -> [Date] {
        let cal = calendar
        let today = cal.startOfDay(for: .now)
        // Find start of current week
        let weekday = cal.component(.weekday, from: today)
        let firstWeekday = cal.firstWeekday
        let daysBack = (weekday - firstWeekday + 7) % 7
        let startOfCurrentWeek = cal.date(byAdding: .day, value: -daysBack, to: today)!
        // Offset to the requested week
        let offset = weekOffset - Self.currentWeekIndex
        let startOfWeek = cal.date(byAdding: .weekOfYear, value: offset, to: startOfCurrentWeek)!
        return (0..<7).map { cal.date(byAdding: .day, value: $0, to: startOfWeek)! }
    }

    private func weekIndex(for date: Date) -> Int {
        let cal = calendar
        let today = cal.startOfDay(for: .now)
        let weekday = cal.component(.weekday, from: today)
        let firstWeekday = cal.firstWeekday
        let daysBack = (weekday - firstWeekday + 7) % 7
        let startOfCurrentWeek = cal.date(byAdding: .day, value: -daysBack, to: today)!
        let components = cal.dateComponents([.weekOfYear], from: startOfCurrentWeek, to: cal.startOfDay(for: date))
        let weekDiff = components.weekOfYear ?? 0
        return Self.currentWeekIndex + weekDiff
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 0) {
                    ForEach(0..<Self.totalWeeks, id: \.self) { weekIndex in
                        weekRow(for: weekIndex)
                            .containerRelativeFrame(.horizontal)
                            .id(weekIndex)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .onAppear {
                guard !hasScrolledToInitial else { return }
                hasScrolledToInitial = true
                let targetWeek = weekIndex(for: selectedDate)
                proxy.scrollTo(targetWeek, anchor: .trailing)
            }
            .onChange(of: weekStartsOnMonday) { _, _ in
                proxy.scrollTo(Self.currentWeekIndex, anchor: .trailing)
            }
        }
    }

    private func weekRow(for weekIndex: Int) -> some View {
        let dates = weekDates(for: weekIndex)
        return HStack(spacing: 0) {
            ForEach(0..<7, id: \.self) { index in
                dayTile(for: dates[index])
            }
        }
    }

    private func dayTile(for date: Date) -> some View {
        let cal = Calendar.current
        let isSelected = cal.isDate(date, inSameDayAs: selectedDate)
        let isToday = cal.isDateInToday(date)

        return Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            withAnimation(.snappy(duration: 0.3)) {
                selectedDate = date
            }
        } label: {
            VStack(spacing: 6) {
                Text(date.formatted(.dateTime.weekday(.narrow)))
                    .font(.system(.caption2, design: .rounded, weight: .medium))
                    .foregroundStyle(isSelected ? AppColors.calorie : Color.secondary.opacity(0.6))

                Text(date.formatted(.dateTime.day()))
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .foregroundStyle(isSelected ? .white : (isToday ? AppColors.calorie : .primary))
                    .frame(width: 36, height: 36)
                    .background {
                        if isSelected {
                            Circle()
                                .fill(LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing))
                                .shadow(color: AppColors.calorie.opacity(0.35), radius: 6, y: 3)
                        } else if isToday {
                            Circle()
                                .strokeBorder(AppColors.calorie.opacity(0.35), lineWidth: 1.5)
                        }
                    }
            }
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Home Nutrient Cards

enum HomeTopNutrient: String, CaseIterable, Identifiable {
    case protein
    case carbs
    case fat
    case fiber
    case sugar
    case addedSugar
    case saturatedFat
    case cholesterol
    case sodium
    case potassium

    static let storageKey = "homeTopNutrients"
    static let defaultSelection: [HomeTopNutrient] = [.protein, .carbs, .fat]

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .protein: "Protein"
        case .carbs: "Carbs"
        case .fat: "Fat"
        case .fiber: "Fiber"
        case .sugar: "Sugar"
        case .addedSugar: "Added Sugar"
        case .saturatedFat: "Sat Fat"
        case .cholesterol: "Cholesterol"
        case .sodium: "Sodium"
        case .potassium: "Potassium"
        }
    }

    var unit: String {
        switch self {
        case .cholesterol, .sodium, .potassium: "mg"
        default: "g"
        }
    }

    var iconName: String {
        switch self {
        case .protein: "fork.knife"
        case .carbs: "leaf"
        case .fat: "drop.fill"
        case .fiber: "leaf.fill"
        case .sugar: "cube.fill"
        case .addedSugar: "plus.circle.fill"
        case .saturatedFat: "circle.lefthalf.filled"
        case .cholesterol: "heart.fill"
        case .sodium: "circle.grid.2x2.fill"
        case .potassium: "bolt.fill"
        }
    }

    var gradientColors: [Color] {
        switch self {
        case .protein:
            AppColors.proteinGradient
        case .carbs:
            AppColors.carbsGradient
        case .fat:
            AppColors.fatGradient
        default:
            AppColors.calorieGradient
        }
    }

    func value(from foodStore: FoodStore, on date: Date) -> Double {
        switch self {
        case .protein: Double(foodStore.protein(for: date))
        case .carbs: Double(foodStore.carbs(for: date))
        case .fat: Double(foodStore.fat(for: date))
        case .fiber: foodStore.fiber(for: date)
        case .sugar: foodStore.sugar(for: date)
        case .addedSugar: foodStore.addedSugar(for: date)
        case .saturatedFat: foodStore.saturatedFat(for: date)
        case .cholesterol: foodStore.cholesterol(for: date)
        case .sodium: foodStore.sodium(for: date)
        case .potassium: foodStore.potassium(for: date)
        }
    }

    func goal(for profile: UserProfile) -> Double {
        switch self {
        case .protein: Double(profile.effectiveProtein)
        case .carbs: Double(profile.effectiveCarbs)
        case .fat: Double(profile.effectiveFat)
        case .fiber: 30
        case .sugar: 50
        case .addedSugar: 25
        case .saturatedFat: 20
        case .cholesterol: 300
        case .sodium: 2_300
        case .potassium: 3_500
        }
    }

    static func selection(from rawValue: String) -> [HomeTopNutrient] {
        let parsed = rawValue
            .split(separator: ",")
            .compactMap { HomeTopNutrient(rawValue: String($0)) }

        var selection: [HomeTopNutrient] = []
        for nutrient in parsed + defaultSelection {
            guard !selection.contains(nutrient) else { continue }
            selection.append(nutrient)
            if selection.count == 3 { break }
        }
        return selection
    }

    static func storageValue(for nutrients: [HomeTopNutrient]) -> String {
        nutrients
            .prefix(3)
            .map(\.rawValue)
            .joined(separator: ",")
    }
}

struct HomeNutrientPickerSheet: View {
    @Binding var selectionRawValue: String
    @Environment(\.dismiss) private var dismiss
    @State private var draftSelection: [HomeTopNutrient]

    init(selectionRawValue: Binding<String>) {
        _selectionRawValue = selectionRawValue
        _draftSelection = State(initialValue: HomeTopNutrient.selection(from: selectionRawValue.wrappedValue))
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Shown on Home") {
                    ForEach(Array(draftSelection.enumerated()), id: \.element.id) { index, nutrient in
                        HStack(spacing: 12) {
                            Label(nutrient.displayName, systemImage: nutrient.iconName)
                                .foregroundStyle(.primary)
                            Spacer()
                            Text("\(index + 1)")
                                .font(.system(.caption, design: .rounded, weight: .semibold))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    ForEach(HomeTopNutrient.allCases) { nutrient in
                        Button {
                            toggle(nutrient)
                        } label: {
                            HStack(spacing: 12) {
                                Label(nutrient.displayName, systemImage: nutrient.iconName)
                                    .foregroundStyle(.primary)
                                Spacer()
                                if draftSelection.contains(nutrient) {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(AppColors.calorie)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text("Choose 3 Nutrients")
                } footer: {
                    Text("Pick exactly three nutrients for the Home summary row.")
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Home Nutrients")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }

                ToolbarItem(placement: .topBarLeading) {
                    Button("Reset") {
                        draftSelection = HomeTopNutrient.defaultSelection
                    }
                    .tint(AppColors.calorie)
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        selectionRawValue = HomeTopNutrient.storageValue(for: draftSelection)
                        dismiss()
                    }
                    .tint(AppColors.calorie)
                    .disabled(draftSelection.count != 3)
                }
            }
        }
    }

    private func toggle(_ nutrient: HomeTopNutrient) {
        if let index = draftSelection.firstIndex(of: nutrient) {
            draftSelection.remove(at: index)
        } else if draftSelection.count < 3 {
            draftSelection.append(nutrient)
        } else {
            draftSelection.removeLast()
            draftSelection.append(nutrient)
        }
    }
}

// MARK: - Macro Card

struct MacroCard: View {
    let label: String
    let current: Double
    let goal: Double
    let unit: String
    let gradientColors: [Color]

    init(label: String, current: Int, goal: Int, gradientColors: [Color]) {
        self.label = label
        self.current = Double(current)
        self.goal = Double(goal)
        self.unit = "g"
        self.gradientColors = gradientColors
    }

    init(label: String, current: Double, goal: Double, unit: String, gradientColors: [Color]) {
        self.label = label
        self.current = current
        self.goal = goal
        self.unit = unit
        self.gradientColors = gradientColors
    }

    private var progress: Double {
        goal > 0 ? min(current / goal, 1.0) : 0
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack(alignment: .lastTextBaseline, spacing: 2) {
                Text(formatted(current))
                    .font(.system(.title, design: .rounded, weight: .bold))
                    .foregroundStyle(gradientColors.first ?? .primary)
                Text("/\(formatted(goal))\(unit)")
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
            }
            .lineLimit(1)
            .minimumScaleFactor(0.5)

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(gradientColors.first?.opacity(0.12) ?? Color.gray.opacity(0.12))

                    Capsule()
                        .fill(LinearGradient(colors: gradientColors, startPoint: .leading, endPoint: .trailing))
                        .frame(width: max(6, geo.size.width * progress))
                        .shadow(color: (gradientColors.first ?? .clear).opacity(0.3), radius: 4, y: 2)
                        .animation(.spring(response: 0.8, dampingFraction: 0.75), value: progress)
                }
            }
            .frame(height: 6)

            Text(label)
                .font(.system(.caption, design: .rounded, weight: .medium))
                .foregroundStyle(.secondary)

            Text("\(formatted(max(goal - current, 0)))\(unit) left")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity)
    }

    private func formatted(_ value: Double) -> String {
        if value >= 100 || value.rounded() == value {
            return "\(Int(value.rounded()))"
        }
        return String(format: "%.1f", value)
    }
}
