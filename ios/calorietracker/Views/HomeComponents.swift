import SwiftUI

// MARK: - Week Day Selector

struct WeekEnergyStrip: View {
    @Binding var selectedDate: Date
    let caloriesForDate: (Date) -> Int
    let calorieGoal: Int
    @AppStorage("weekStartsOnMonday") private var weekStartsOnMonday = true
    /// Two-way scroll position (the visible week's index). Driven programmatically when the selected
    /// day moves to another week, and updated by the user's own paging.
    @State private var scrolledWeek: Int?

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

    /// Start of the week (respecting the Mon/Sun setting) containing `date`.
    private func weekStart(for date: Date) -> Date {
        let cal = calendar
        let day = cal.startOfDay(for: date)
        let weekday = cal.component(.weekday, from: day)
        let daysBack = (weekday - cal.firstWeekday + 7) % 7
        return cal.date(byAdding: .day, value: -daysBack, to: day)!
    }

    private func weekIndex(for date: Date) -> Int {
        let cal = calendar
        // Count days between the two WEEK-STARTS (an exact multiple of 7, possibly negative) and
        // divide by 7. Diffing against a mid-week date with `.weekOfYear` truncates and would keep
        // returning the current week for any day in an adjacent week less than 7 days away.
        let days = cal.dateComponents([.day], from: weekStart(for: .now), to: weekStart(for: date)).day ?? 0
        return Self.currentWeekIndex + Int((Double(days) / 7.0).rounded())
    }

    var body: some View {
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
        // Start anchored at the most recent week so the strip shows the current week before the
        // first layout pass; `scrolledWeek` then takes over for programmatic + user paging.
        .defaultScrollAnchor(.trailing)
        .scrollPosition(id: $scrolledWeek)
        .onAppear {
            if scrolledWeek == nil {
                scrolledWeek = weekIndex(for: selectedDate)
            }
        }
        .onChange(of: weekStartsOnMonday) { _, _ in
            scrolledWeek = Self.currentWeekIndex
        }
        // Follow the selected day when it moves to a different week (e.g. the Home swipe steps
        // across a week boundary), so the strip always shows the highlighted day.
        .onChange(of: selectedDate) { _, newValue in
            let target = weekIndex(for: newValue)
            if scrolledWeek != target {
                withAnimation(.snappy) { scrolledWeek = target }
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
    case caffeine
    case sodium
    case potassium
    case transFat
    case calcium
    case iron
    case magnesium
    case zinc
    case vitaminA
    case vitaminC
    case vitaminD
    case vitaminB12
    case vitaminE
    case vitaminK
    case folate
    case omega3
    case creatine
    case betaAlanine
    case lCitrulline
    case lCarnitine
    case lArginine
    case taurine
    case betaine
    case hmb

    static let storageKey = "homeTopNutrients"
    static let defaultSelection: [HomeTopNutrient] = [.protein, .carbs, .fat, .fiber]

    var id: String { rawValue }

    var optionalNutrient: OptionalNutrient? {
        switch self {
        case .fiber: .fiber
        case .sugar: .sugar
        case .addedSugar: .addedSugar
        case .saturatedFat: .saturatedFat
        case .cholesterol: .cholesterol
        case .caffeine: .caffeine
        case .creatine: .creatine
        case .betaAlanine: .betaAlanine
        case .lCitrulline: .lCitrulline
        case .lCarnitine: .lCarnitine
        case .lArginine: .lArginine
        case .taurine: .taurine
        case .betaine: .betaine
        case .hmb: .hmb
        case .sodium: .sodium
        case .potassium: .potassium
        case .transFat: .transFat
        case .calcium: .calcium
        case .iron: .iron
        case .magnesium: .magnesium
        case .zinc: .zinc
        case .vitaminA: .vitaminA
        case .vitaminC: .vitaminC
        case .vitaminD: .vitaminD
        case .vitaminB12: .vitaminB12
        case .vitaminE: .vitaminE
        case .vitaminK: .vitaminK
        case .folate: .folate
        case .omega3: .omega3
        case .protein, .carbs, .fat: nil
        }
    }

    var displayName: String {
        if let optionalNutrient {
            return optionalNutrient.shortDisplayName
        }

        switch self {
        case .protein: return LocalizedDisplayText.text("Protein", polish: "Białko")
        case .carbs: return LocalizedDisplayText.text("Carbs", polish: "Węglowodany")
        case .fat: return LocalizedDisplayText.text("Fat", polish: "Tłuszcz")
        case .fiber, .sugar, .addedSugar, .saturatedFat, .cholesterol, .caffeine, .sodium, .potassium,
             .transFat, .calcium, .iron, .magnesium, .zinc, .vitaminA, .vitaminC, .vitaminD,
             .vitaminB12, .vitaminE, .vitaminK, .folate, .omega3, .creatine, .betaAlanine,
             .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb:
            return optionalNutrient?.shortDisplayName ?? rawValue
        }
    }

    var unit: String {
        if let optionalNutrient {
            return optionalNutrient.unit
        }

        switch self {
        case .protein, .carbs, .fat: return "g"
        case .fiber, .sugar, .addedSugar, .saturatedFat, .cholesterol, .caffeine, .sodium, .potassium,
             .transFat, .calcium, .iron, .magnesium, .zinc, .vitaminA, .vitaminC, .vitaminD,
             .vitaminB12, .vitaminE, .vitaminK, .folate, .omega3, .creatine, .betaAlanine,
             .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb:
            return optionalNutrient?.unit ?? "g"
        }
    }

    var iconName: String {
        if let optionalNutrient {
            return optionalNutrient.iconName
        }

        switch self {
        case .protein: return "fork.knife"
        case .carbs: return "leaf"
        case .fat: return "drop.fill"
        case .fiber, .sugar, .addedSugar, .saturatedFat, .cholesterol, .caffeine, .sodium, .potassium,
             .transFat, .calcium, .iron, .magnesium, .zinc, .vitaminA, .vitaminC, .vitaminD,
             .vitaminB12, .vitaminE, .vitaminK, .folate, .omega3, .creatine, .betaAlanine,
             .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb:
            return optionalNutrient?.iconName ?? "circle"
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
        total(in: foodStore.entries(for: date))
    }

    /// Sum this nutrient across an already-fetched day (or range) of entries.
    /// Prefer this in Progress aggregates so we don't re-filter FoodStore per nutrient.
    func total(in entries: [FoodEntry]) -> Double {
        switch self {
        case .protein: entries.reduce(0) { $0 + $1.protein }
        case .carbs: entries.reduce(0) { $0 + $1.carbs }
        case .fat: entries.reduce(0) { $0 + $1.fat }
        case .fiber: entries.reduce(0) { $0 + ($1.fiber ?? 0) }
        case .sugar: entries.reduce(0) { $0 + ($1.sugar ?? 0) }
        case .addedSugar: entries.reduce(0) { $0 + ($1.addedSugar ?? 0) }
        case .saturatedFat: entries.reduce(0) { $0 + ($1.saturatedFat ?? 0) }
        case .cholesterol: entries.reduce(0) { $0 + ($1.cholesterol ?? 0) }
        case .caffeine: entries.reduce(0) { $0 + ($1.caffeine ?? 0) }
        case .creatine: entries.reduce(0) { $0 + ($1.supplementalNutrients[SupplementalNutrient.creatine.rawValue] ?? 0) }
        case .betaAlanine: entries.reduce(0) { $0 + ($1.supplementalNutrients[SupplementalNutrient.betaAlanine.rawValue] ?? 0) }
        case .lCitrulline: entries.reduce(0) { $0 + ($1.supplementalNutrients[SupplementalNutrient.lCitrulline.rawValue] ?? 0) }
        case .lCarnitine: entries.reduce(0) { $0 + ($1.supplementalNutrients[SupplementalNutrient.lCarnitine.rawValue] ?? 0) }
        case .lArginine: entries.reduce(0) { $0 + ($1.supplementalNutrients[SupplementalNutrient.lArginine.rawValue] ?? 0) }
        case .taurine: entries.reduce(0) { $0 + ($1.supplementalNutrients[SupplementalNutrient.taurine.rawValue] ?? 0) }
        case .betaine: entries.reduce(0) { $0 + ($1.supplementalNutrients[SupplementalNutrient.betaine.rawValue] ?? 0) }
        case .hmb: entries.reduce(0) { $0 + ($1.supplementalNutrients[SupplementalNutrient.hmb.rawValue] ?? 0) }
        case .sodium: entries.reduce(0) { $0 + ($1.sodium ?? 0) }
        case .potassium: entries.reduce(0) { $0 + ($1.potassium ?? 0) }
        case .transFat: entries.reduce(0) { $0 + ($1.transFat ?? 0) }
        case .calcium: entries.reduce(0) { $0 + ($1.calcium ?? 0) }
        case .iron: entries.reduce(0) { $0 + ($1.iron ?? 0) }
        case .magnesium: entries.reduce(0) { $0 + ($1.magnesium ?? 0) }
        case .zinc: entries.reduce(0) { $0 + ($1.zinc ?? 0) }
        case .vitaminA: entries.reduce(0) { $0 + ($1.vitaminA ?? 0) }
        case .vitaminC: entries.reduce(0) { $0 + ($1.vitaminC ?? 0) }
        case .vitaminD: entries.reduce(0) { $0 + ($1.vitaminD ?? 0) }
        case .vitaminB12: entries.reduce(0) { $0 + ($1.vitaminB12 ?? 0) }
        case .vitaminE: entries.reduce(0) { $0 + ($1.vitaminE ?? 0) }
        case .vitaminK: entries.reduce(0) { $0 + ($1.vitaminK ?? 0) }
        case .folate: entries.reduce(0) { $0 + ($1.folate ?? 0) }
        case .omega3: entries.reduce(0) { $0 + ($1.omega3 ?? 0) }
        }
    }

    func goal(for profile: UserProfile, optionalGoals: OptionalNutrientGoals = .current) -> Double {
        switch self {
        case .protein: return Double(profile.effectiveProtein)
        case .carbs: return Double(profile.effectiveCarbs)
        case .fat: return Double(profile.effectiveFat)
        case .fiber, .sugar, .addedSugar, .saturatedFat, .cholesterol, .caffeine, .sodium, .potassium,
             .transFat, .calcium, .iron, .magnesium, .zinc, .vitaminA, .vitaminC, .vitaminD,
             .vitaminB12, .vitaminE, .vitaminK, .folate, .omega3, .creatine, .betaAlanine,
             .lCitrulline, .lCarnitine, .lArginine, .taurine, .betaine, .hmb:
            guard let optionalNutrient else { return 0 }
            return Double(optionalGoals.goal(for: optionalNutrient))
        }
    }

    static func selection(from rawValue: String) -> [HomeTopNutrient] {
        let parsed = rawValue
            .split(separator: ",")
            .compactMap { HomeTopNutrient(rawValue: String($0)) }

        var selection: [HomeTopNutrient] = []
        for nutrient in parsed {
            guard !selection.contains(nutrient) else { continue }
            selection.append(nutrient)
            if selection.count == 4 { break }
        }
        return selection.isEmpty ? defaultSelection : selection
    }

    static func storageValue(for nutrients: [HomeTopNutrient]) -> String {
        nutrients
            .prefix(4)
            .map(\.rawValue)
            .joined(separator: ",")
    }
}

struct HomeNutrientPickerSheet: View {
    let waterTrackingEnabled: Bool
    @Binding var selectionRawValue: String
    @Environment(\.dismiss) private var dismiss
    @State private var draftSelection: [HomeTopNutrient]
    @State private var hiddenFourthNutrient: HomeTopNutrient?

    init(selectionRawValue: Binding<String>, waterTrackingEnabled: Bool) {
        let storedSelection = HomeTopNutrient.selection(from: selectionRawValue.wrappedValue)
        let visibleCount = waterTrackingEnabled ? 3 : 4
        self.waterTrackingEnabled = waterTrackingEnabled
        _selectionRawValue = selectionRawValue
        _draftSelection = State(initialValue: Array(storedSelection.prefix(visibleCount)))
        _hiddenFourthNutrient = State(
            initialValue: waterTrackingEnabled ? storedSelection.dropFirst(3).first : nil
        )
    }

    private var selectionLimit: Int { waterTrackingEnabled ? 3 : 4 }

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

                    if waterTrackingEnabled {
                        HStack(spacing: 12) {
                            Label("Water", systemImage: "drop.fill")
                                .foregroundStyle(AppColors.calorie)
                            Spacer()
                            Text("\(draftSelection.count + 1)")
                                .font(.system(.caption, design: .rounded, weight: .semibold))
                                .foregroundStyle(.secondary)
                            Image(systemName: "lock.fill")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel("Water, shown after selected nutrients")
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
                    if waterTrackingEnabled {
                        Text("Choose 1–3 Nutrients")
                    } else {
                        Text("Choose 1–4 Nutrients")
                    }
                } footer: {
                    if waterTrackingEnabled {
                        Text("Pick one to three nutrients. Water is shown after them while tracking is enabled.")
                    } else {
                        Text("Pick one to four nutrients for the Home summary row.")
                    }
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
                        draftSelection = Array(HomeTopNutrient.defaultSelection.prefix(selectionLimit))
                        hiddenFourthNutrient = waterTrackingEnabled ? HomeTopNutrient.defaultSelection.last : nil
                    }
                    .tint(AppColors.calorie)
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        selectionRawValue = HomeTopNutrient.storageValue(for: persistedSelection)
                        dismiss()
                    }
                    .tint(AppColors.calorie)
                    .disabled(draftSelection.isEmpty || draftSelection.count > selectionLimit)
                }
            }
        }
    }

    private func toggle(_ nutrient: HomeTopNutrient) {
        if let index = draftSelection.firstIndex(of: nutrient) {
            guard draftSelection.count > 1 else { return }
            draftSelection.remove(at: index)
        } else if draftSelection.count < selectionLimit {
            draftSelection.append(nutrient)
        } else {
            draftSelection.removeLast()
            draftSelection.append(nutrient)
        }
    }

    private var persistedSelection: [HomeTopNutrient] {
        // Preserve an existing fourth choice only while all three visible slots remain occupied.
        guard waterTrackingEnabled, draftSelection.count == 3,
              let hidden = hiddenFourthNutrient, !draftSelection.contains(hidden)
        else { return draftSelection }
        return draftSelection + [hidden]
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

            Text(LocalizedDisplayText.text(label))
                .font(.system(.caption, design: .rounded, weight: .medium))
                .foregroundStyle(.secondary)

            Text("\(formatted(max(goal - current, 0)))\(unit) left")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity)
    }

    private func formatted(_ value: Double) -> String {
        if unit == "kcal" {
            return "\(Int(value.rounded()))"
        }
        return MacroValueFormatter.string(value)
    }
}

/// Semicircle speedometer-style gauge for total calories — segmented (dashed) accent arc with the
/// calorie count, "Calories" label, and remaining read out in the center. Flat, no card.
struct CalorieGauge: View {
    let eaten: Int
    let goal: Int
    /// Secondary display-only burn/deficit line when Health energy is available.
    var burnLine: String? = nil
    /// Increments when the app is opened; drives the fill-from-zero reveal.
    var launchFillEpoch: Int = 0

    // Keep the calorie dome as Home's signature, but at a tighter proportion so the first
    // meal enters the initial viewport sooner. Android uses the same 240 / 14 geometry.
    private let diameter: CGFloat = 240
    private let lineWidth: CGFloat = 14

    @State private var shownProgress: Double = 0
    @State private var lastEpoch = 0
    // The arc reads its colors from static AppColors accessors and none of the
    // stored inputs change with the theme, so SwiftUI skips this view on theme
    // switches. Observing the setting re-renders it in place (state intact).
    @AppStorage(AppThemeColor.storageKey) private var appThemeColorRaw = AppThemeColor.defaultColor.rawValue

    private var progress: Double {
        goal > 0 ? min(Double(eaten) / Double(goal), 1.0) : 0
    }

    private var statusText: String {
        guard goal > 0 else { return "No goal" }
        if eaten < goal { return "\((goal - eaten).formatted()) left" }
        if eaten > goal { return "\((eaten - goal).formatted()) over" }
        return "Goal reached"
    }

    private var dashedStroke: StrokeStyle {
        StrokeStyle(lineWidth: lineWidth, lineCap: .butt, dash: [4, 6])
    }

    var body: some View {
        ZStack {
            // Top-semicircle track (9 o'clock -> 12 -> 3 o'clock). The .padding keeps the stroke
            // inside the frame so the arc ends aren't clipped flat on each side.
            Circle()
                .trim(from: 0.5, to: 1.0)
                .stroke(AppColors.calorie.opacity(0.12), style: dashedStroke)
                .padding(lineWidth / 2)

            // Progress sweep — driven by shownProgress so it fills from zero on app open.
            Circle()
                .trim(from: 0.5, to: 0.5 + 0.5 * shownProgress)
                .stroke(
                    LinearGradient(colors: AppColors.calorieGradient,
                                   startPoint: .leading, endPoint: .trailing),
                    style: dashedStroke
                )
                .padding(lineWidth / 2)
                .shadow(color: AppColors.calorie.opacity(0.35), radius: 6, y: 2)
                // Implicit animation on the trim — the reliable way to animate a
                // Shape's .trim (withAnimation from an async block does not take here).
                .animation(.spring(response: 0.9, dampingFraction: 0.85), value: shownProgress)

            // Readout, lifted up into the dome so nothing is cropped at the bottom.
            VStack(spacing: 2) {
                Text("Calories")
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .textCase(.uppercase)
                    .tracking(0.5)
                    .foregroundStyle(.secondary)

                Text(eaten.formatted())
                    .font(.system(size: 50, weight: .bold, design: .rounded))
                    .foregroundStyle(
                        LinearGradient(colors: AppColors.calorieGradient,
                                       startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    .contentTransition(.numericText())
                    .animation(.snappy, value: eaten)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)

                HStack(spacing: 5) {
                    Image(systemName: "flame.fill")
                        .font(.system(size: 11, weight: .semibold))
                    Text(statusText)
                        .font(.system(.footnote, design: .rounded, weight: .semibold))
                }
                .foregroundStyle(AppColors.calorie)

                if let burnLine {
                    Text(burnLine)
                        .font(.system(.caption2, design: .rounded, weight: .medium))
                        .foregroundStyle(.tertiary)
                }
            }
            .offset(y: -diameter * 0.14)
        }
        .frame(width: diameter, height: diameter)
        .frame(height: diameter * 0.58, alignment: .top)
        .clipped()
        .onAppear {
            if lastEpoch != launchFillEpoch { playLaunchFill() } else { shownProgress = progress }
        }
        .onChange(of: launchFillEpoch) { _, _ in playLaunchFill() }
        .onChange(of: progress) { _, newValue in shownProgress = newValue }
    }

    /// Snap to empty, then let the arc's implicit .animation spring it up to the
    /// real value on the next runloop tick (so the 0 frame renders first).
    private func playLaunchFill() {
        lastEpoch = launchFillEpoch
        var reset = Transaction()
        reset.disablesAnimations = true
        withTransaction(reset) { shownProgress = 0 }
        DispatchQueue.main.async { shownProgress = progress }
    }
}

/// A single macro shown as a vertical fill bar (rounded tube) that fills bottom-up toward the goal.
/// Value above, name + goal beneath.
struct MacroVerticalBar: View {
    let label: String
    let current: Double
    let goal: Double
    let unit: String
    let gradient: [Color]
    /// Increments when the app is opened; drives the fill-from-zero reveal.
    var launchFillEpoch: Int = 0

    private let barWidth: CGFloat = 16
    private let barHeight: CGFloat = 64

    @State private var shownProgress: CGFloat = 0
    @State private var lastEpoch = 0

    private var progress: CGFloat {
        goal > 0 ? CGFloat(min(current / goal, 1.0)) : 0
    }

    private var statusText: String {
        guard goal > 0 else { return "No goal" }
        let difference = goal - current
        if abs(difference) < 0.0001 { return "Goal reached" }
        let amount = MacroValueFormatter.string(abs(difference))
        return difference > 0 ? "\(amount)\(unit) left" : "\(amount)\(unit) over"
    }

    var body: some View {
        VStack(spacing: 8) {
            Text(MacroValueFormatter.string(current))
                .font(.system(.title3, design: .rounded, weight: .bold))
                .foregroundStyle(
                    LinearGradient(colors: gradient, startPoint: .top, endPoint: .bottom)
                )
                .contentTransition(.numericText())
                .animation(.snappy, value: current)
                .lineLimit(1)
                .minimumScaleFactor(0.6)

            ZStack(alignment: .bottom) {
                Capsule()
                    .fill(AppColors.calorie.opacity(0.12))
                    .frame(width: barWidth, height: barHeight)

                Capsule()
                    .fill(LinearGradient(colors: gradient, startPoint: .bottom, endPoint: .top))
                    .frame(width: barWidth, height: max(barWidth, barHeight * shownProgress))
                    .shadow(color: (gradient.first ?? AppColors.calorie).opacity(0.4), radius: 5)
            }

            VStack(spacing: 1) {
                Text(LocalizedDisplayText.text(label))
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .foregroundStyle(.primary)
                Text(statusText)
                    .font(.system(.caption2, design: .rounded, weight: .medium))
                    .foregroundStyle(current > goal && goal > 0 ? AppColors.calorie : .secondary)
            }
            .lineLimit(1)
            .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity)
        .onAppear {
            if lastEpoch != launchFillEpoch { playLaunchFill() } else { shownProgress = progress }
        }
        .onChange(of: launchFillEpoch) { _, _ in playLaunchFill() }
        .onChange(of: progress) { _, newValue in
            withAnimation(.spring(response: 0.8, dampingFraction: 0.78)) { shownProgress = newValue }
        }
    }

    private func playLaunchFill() {
        lastEpoch = launchFillEpoch
        shownProgress = 0
        DispatchQueue.main.async {
            withAnimation(.spring(response: 0.9, dampingFraction: 0.85)) { shownProgress = progress }
        }
    }
}
