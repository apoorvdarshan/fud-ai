import SwiftUI

enum HomeDiaryItem: Identifiable {
    case food(FoodEntry)
    case water(WaterEntry)
    case fasting(FastingSession)

    var id: String {
        switch self {
        case .food(let entry): "food-\(entry.id.uuidString)"
        case .water(let entry): "water-\(entry.id.uuidString)"
        case .fasting(let session): "fasting-\(session.id.uuidString)"
        }
    }

    var date: Date {
        switch self {
        case .food(let entry): entry.timestamp
        case .water(let entry): entry.date
        case .fasting(let session): session.endedAt ?? session.startedAt
        }
    }

    var meal: MealType {
        switch self {
        case .food(let entry): entry.mealType
        case .water(let entry): MealScheduleSettings.mealType(for: entry.date)
        case .fasting(let session): MealScheduleSettings.mealType(for: session.endedAt ?? session.startedAt)
        }
    }
}

struct HomeDiaryMealGroup: Identifiable {
    let id: String
    let meal: MealType
    let items: [HomeDiaryItem]

    var foodEntries: [FoodEntry] {
        items.compactMap { item in
            guard case .food(let entry) = item else { return nil }
            return entry
        }
    }

    var totalCalories: Int { foodEntries.reduce(0) { $0 + $1.calories } }
    var totalProtein: Double { foodEntries.reduce(0) { $0 + $1.protein } }
    var totalCarbs: Double { foodEntries.reduce(0) { $0 + $1.carbs } }
    var totalFat: Double { foodEntries.reduce(0) { $0 + $1.fat } }
}

func homeDiaryMealGroups(
    foodEntries: [FoodEntry],
    waterEntries: [WaterEntry],
    fastingSessions: [FastingSession] = [],
    order: FoodLogSortOrder
) -> [HomeDiaryMealGroup] {
    let items = (
        foodEntries.map(HomeDiaryItem.food)
            + waterEntries.map(HomeDiaryItem.water)
            + fastingSessions.map(HomeDiaryItem.fasting)
    ).sorted { $0.date > $1.date }

    switch order {
    case .standard:
        return MealType.allCases.compactMap { meal in
            let mealItems = items.filter { $0.meal == meal }
            guard !mealItems.isEmpty else { return nil }
            return HomeDiaryMealGroup(id: "standard-\(meal.rawValue)", meal: meal, items: mealItems)
        }
    case .latestMealsFirst:
        var groups: [HomeDiaryMealGroup] = []
        var currentMeal: MealType?
        var currentItems: [HomeDiaryItem] = []

        func appendCurrentGroup() {
            guard let meal = currentMeal, let first = currentItems.first else { return }
            groups.append(HomeDiaryMealGroup(
                id: "latest-\(meal.rawValue)-\(first.id)",
                meal: meal,
                items: currentItems
            ))
        }

        for item in items {
            if item.meal == currentMeal {
                currentItems.append(item)
            } else {
                appendCurrentGroup()
                currentMeal = item.meal
                currentItems = [item]
            }
        }
        appendCurrentGroup()
        return groups
    }
}

struct WaterLogRow: View {
    let entry: WaterEntry
    let unit: WaterUnit

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 14)
                    .fill(.ultraThinMaterial)
                Text("💧")
                    .font(.system(size: 28))
            }
            .frame(width: 56, height: 56)

            VStack(alignment: .leading, spacing: 4) {
                Text("Water")
                    .font(.system(.body, design: .rounded, weight: .semibold))
                Text(entry.date, format: .dateTime.hour().minute())
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 12)

            Text(unit.formatted(milliliters: entry.milliliters))
                .font(.system(.body, design: .rounded, weight: .semibold))
                .foregroundStyle(AppColors.calorie)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityIdentifier("water.log.row")
        .accessibilityLabel("Water, \(unit.formatted(milliliters: entry.milliliters)), logged \(entry.date.formatted(date: .omitted, time: .shortened))")
        .accessibilityHint("Swipe left to delete")
    }
}

struct WaterCustomAmountSheet: View {
    let unit: WaterUnit
    let onAdd: (Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var customAmount = ""
    @FocusState private var customFocused: Bool

    private var selectedAmountMl: Int? {
        Double(customAmount.replacingOccurrences(of: ",", with: "."))
            .flatMap { $0 > 0 ? unit.milliliters(fromDisplayedValue: $0) : nil }
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 20) {
                Text("How much water?")
                    .font(.system(.title3, design: .rounded, weight: .semibold))

                HStack {
                    TextField("Custom amount", text: $customAmount)
                        .keyboardType(unit == .milliliters ? .numberPad : .decimalPad)
                        .focused($customFocused)
                        .onChange(of: customAmount) { _, value in
                            let filtered = value.filter { $0.isNumber || (unit == .fluidOunces && ($0 == "." || $0 == ",")) }
                            let normalized = filtered.replacingOccurrences(of: ",", with: ".")
                            let pieces = normalized.split(separator: ".", omittingEmptySubsequences: false)
                            customAmount = String((pieces.count > 1 ? "\(pieces[0]).\(pieces[1].prefix(1))" : normalized).prefix(6))
                        }
                    Text(unit.symbol)
                        .foregroundStyle(.secondary)
                }
                .padding()
                .background(AppColors.appCard)
                .clipShape(RoundedRectangle(cornerRadius: 14))

                Button {
                    guard let selectedAmountMl else { return }
                    onAdd(selectedAmountMl)
                    dismiss()
                } label: {
                    Label("Add Water", systemImage: "drop.fill")
                        .font(.system(.headline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .foregroundStyle(.white)
                        .background(AppColors.calorie, in: RoundedRectangle(cornerRadius: 14))
                }
                .disabled(selectedAmountMl == nil)

                Spacer()
            }
            .padding(20)
            .background(AppColors.appBackground)
            .navigationTitle("Custom Water Amount")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { customFocused = true }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// Edits the three Home + menu water amounts. Saves when the screen closes, and only if
/// all three amounts are valid, so values half-typed while editing never reach the + menu.
struct WaterQuickAddSettingsView: View {
    let unit: WaterUnit

    @AppStorage(WaterQuickAddPresets.key) private var presetsRaw = ""
    /// Saved amounts and their text when the screen opened, so untouched fields keep
    /// their exact milliliters instead of round-tripping through fl oz.
    @State private var originalMilliliters: [Int] = []
    @State private var originalTexts: [String] = []
    @State private var texts: [String] = []

    private var resolvedMilliliters: [Int?] {
        WaterQuickAddPresets.resolvedMilliliters(
            texts: texts,
            savedTexts: originalTexts,
            savedMilliliters: originalMilliliters,
            unit: unit
        )
    }

    var body: some View {
        Form {
            Section {
                ForEach(texts.indices, id: \.self) { index in
                    HStack {
                        Image(systemName: "drop.fill")
                            .foregroundStyle(AppColors.calorie)
                        TextField(
                            "Amount \(index + 1)",
                            text: $texts[index],
                            prompt: Text(verbatim: WaterQuickAddPresets.editingText(
                                forMilliliters: WaterQuickAddPresets.defaults[index],
                                unit: unit
                            ))
                        )
                        .keyboardType(unit == .milliliters ? .numberPad : .decimalPad)
                        .accessibilityIdentifier("settings.water.quickAdd.\(index)")
                        Text(unit.symbol)
                            .foregroundStyle(.secondary)
                    }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if resolvedMilliliters.contains(where: { $0 == nil }) {
                        Text("Enter an amount up to \(unit.formatted(milliliters: WaterQuickAddPresets.validRange.upperBound)).")
                            .foregroundStyle(.red)
                    }
                    Text("Shown in the + menu for one-tap water logging.")
                }
            }

            Section {
                Button("Reset", role: .destructive) {
                    presetsRaw = ""
                    load()
                }
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("Quick Add Amounts")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: load)
        .onDisappear(perform: save)
        .onChange(of: texts) { _, newValue in
            let filtered = newValue.map { WaterQuickAddPresets.filteredInput($0, unit: unit) }
            if filtered != newValue { texts = filtered }
        }
    }

    private func save() {
        guard let values = WaterQuickAddPresets.editedPresets(
            texts: texts,
            savedTexts: originalTexts,
            savedMilliliters: originalMilliliters,
            unit: unit
        ) else { return }
        let encoded = WaterQuickAddPresets.encode(values)
        if encoded != WaterQuickAddPresets.encode(WaterQuickAddPresets.decode(presetsRaw)) {
            presetsRaw = encoded
        }
    }

    private func load() {
        let presets = WaterQuickAddPresets.decode(presetsRaw)
        let editing = presets.map { WaterQuickAddPresets.editingText(forMilliliters: $0, unit: unit) }
        originalMilliliters = presets
        originalTexts = editing
        texts = editing
    }
}

struct WaterGoalPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onSave: (Int) -> Void
    let unit: WaterUnit
    @State private var selectedGoalDisplay: Int

    init(currentGoal: Int, unit: WaterUnit, onSave: @escaping (Int) -> Void) {
        self.onSave = onSave
        self.unit = unit
        if unit == .milliliters {
            let clamped = min(10_000, max(50, currentGoal))
            _selectedGoalDisplay = State(initialValue: min(10_000, max(50, ((clamped + 25) / 50) * 50)))
        } else {
            let ounces = Int((Double(currentGoal) / WaterUnit.millilitersPerFluidOunce).rounded())
            _selectedGoalDisplay = State(initialValue: min(338, max(2, ounces)))
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Daily Water Goal")
                    .font(.system(.title2, design: .rounded, weight: .bold))

                HStack(spacing: 4) {
                    Picker(unit.title, selection: $selectedGoalDisplay) {
                        ForEach(unit == .milliliters ? Array(stride(from: 50, through: 10_000, by: 50)) : Array(2...338), id: \.self) { amount in
                            Text(amount.formatted())
                                .tag(amount)
                                .font(.system(.title2, design: .rounded, weight: .medium))
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 180, height: 190)
                    .clipped()

                    Text(unit.symbol)
                        .font(.system(.title3, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                Button {
                    onSave(unit.milliliters(fromDisplayedValue: Double(selectedGoalDisplay)))
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
