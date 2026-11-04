import SwiftUI

// MARK: - Profile Header Section

struct ProfileHeaderSection: View {
    let profile: UserProfile

    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: AppColors.calorieGradient,
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 80, height: 80)
                    .shadow(color: AppColors.calorie.opacity(0.3), radius: 8, y: 4)

                Text(profile.initials)
                    .font(.system(size: 32, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
            }

            Text(profile.displayName)
                .font(.system(.title2, design: .rounded, weight: .bold))

            Text("\(profile.effectiveCalories) kcal / day")
                .font(.system(.subheadline, design: .rounded, weight: .medium))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
    }
}

// MARK: - Profile Info Row

struct ProfileInfoRow: View {
    let icon: String
    let label: String
    let value: String
    var action: (() -> Void)? = nil

    var body: some View {
        Button {
            action?()
        } label: {
            HStack {
                Label {
                    Text(LocalizedDisplayText.text(label))
                } icon: {
                    Image(systemName: icon)
                        .foregroundStyle(AppColors.calorie)
                }
                Spacer()
                Text(value)
                    .foregroundStyle(.secondary)
                if action != nil {
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(action == nil)
    }
}

// MARK: - Height Picker Sheet

struct HeightPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("heightUnit") private var heightUnitRaw = "ftin"
    let currentHeightCm: Double
    let onSave: (Double) -> Void

    @State private var feet: Int
    @State private var inches: Int
    @State private var cm: Int

    init(currentHeightCm: Double, onSave: @escaping (Double) -> Void) {
        self.currentHeightCm = currentHeightCm
        self.onSave = onSave
        // Round to the nearest inch — truncating shows 5'6" for a 170 cm / 5'7" pick.
        let totalInches = Int((currentHeightCm / 2.54).rounded())
        _cm = State(initialValue: Int(currentHeightCm.rounded()))
        _feet = State(initialValue: totalInches / 12)
        _inches = State(initialValue: totalInches % 12)
    }

    private var useMetric: Bool { heightUnitRaw == "cm" }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Height")
                    .font(.system(.title2, design: .rounded, weight: .bold))

                Picker("Unit", selection: $heightUnitRaw) {
                    Text("cm").tag("cm")
                    Text("ft / in").tag("ftin")
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 24)
                .onChange(of: heightUnitRaw) { _, newValue in
                    // Convert the currently selected value so toggling mid-edit keeps it,
                    // clamped into the destination wheel's rows (100...250 cm / 3'0"...8'11")
                    // so the selection never lands on a tag the wheel doesn't offer.
                    if newValue == "cm" {
                        cm = min(250, max(100, Int((Double(feet) * 30.48 + Double(inches) * 2.54).rounded())))
                    } else {
                        let totalInches = min(107, max(36, Int((Double(cm) / 2.54).rounded())))
                        feet = totalInches / 12
                        inches = totalInches % 12
                    }
                }

                if useMetric {
                    HStack(spacing: 0) {
                        Picker("cm", selection: $cm) {
                            ForEach(100...250, id: \.self) { n in
                                Text("\(n)").tag(n)
                                    .font(.system(.title2, design: .rounded, weight: .medium))
                            }
                        }
                        .pickerStyle(.wheel)
                        .frame(width: 100)
                        .clipped()

                        Text("cm")
                            .font(.system(.title3, design: .rounded))
                            .foregroundStyle(.secondary)
                            .padding(.leading, 4)
                    }
                } else {
                    HStack(spacing: 0) {
                        Picker("Feet", selection: $feet) {
                            ForEach(3...8, id: \.self) { n in
                                Text("\(n)").tag(n)
                                    .font(.system(.title2, design: .rounded, weight: .medium))
                            }
                        }
                        .pickerStyle(.wheel)
                        .frame(width: 80)
                        .clipped()

                        Text("ft")
                            .font(.system(.title3, design: .rounded))
                            .foregroundStyle(.secondary)

                        Picker("Inches", selection: $inches) {
                            ForEach(0...11, id: \.self) { n in
                                Text("\(n)").tag(n)
                                    .font(.system(.title2, design: .rounded, weight: .medium))
                            }
                        }
                        .pickerStyle(.wheel)
                        .frame(width: 80)
                        .clipped()

                        Text("in")
                            .font(.system(.title3, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }

                Button {
                    let heightCm: Double
                    if useMetric {
                        heightCm = Double(cm)
                    } else {
                        heightCm = Double(feet) * 30.48 + Double(inches) * 2.54
                    }
                    onSave(heightCm)
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

// MARK: - Weight Picker Sheet

struct WeightPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("weightUnit") private var weightUnitRaw = "lbs"
    let currentWeightKg: Double
    let onSave: (Double) -> Void

    @State private var wholeNumber: Int
    @State private var decimal: Int

    init(currentWeightKg: Double, onSave: @escaping (Double) -> Void) {
        self.currentWeightKg = currentWeightKg
        self.onSave = onSave
        // Respect the stored preference at the time the sheet is created.
        let metric = UserDefaults.standard.string(forKey: "weightUnit") == "kg"
        let displayValue = metric ? currentWeightKg : currentWeightKg * 2.20462
        let whole = Int(displayValue)
        let dec = min(9, max(0, Int((displayValue - Double(whole)) * 10 + 0.5)))
        _wholeNumber = State(initialValue: whole)
        _decimal = State(initialValue: dec)
    }

    private var useMetric: Bool { weightUnitRaw == "kg" }
    private var label: String { useMetric ? "kg" : "lbs" }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text(LocalizedDisplayText.text("Weight"))
                    .font(.system(.title2, design: .rounded, weight: .bold))

                Picker("Unit", selection: $weightUnitRaw) {
                    Text("kg").tag("kg")
                    Text("lbs").tag("lbs")
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 24)
                .onChange(of: weightUnitRaw) { _, newValue in
                    // Convert the currently selected value so toggling mid-edit keeps it,
                    // clamped into the destination wheel's rows (30...300 kg / 50...500 lbs)
                    // so the selection never lands on a tag the wheel doesn't offer.
                    let value = Double(wholeNumber) + Double(decimal) / 10.0
                    let converted = newValue == "kg" ? value / 2.20462 : value * 2.20462
                    let bounds = newValue == "kg" ? 30.0...300.0 : 50.0...500.0
                    let clamped = min(bounds.upperBound, max(bounds.lowerBound, converted))
                    let whole = Int(clamped)
                    wholeNumber = whole
                    decimal = min(9, max(0, Int((clamped - Double(whole)) * 10 + 0.5)))
                }

                HStack(spacing: 0) {
                    Picker("Whole", selection: $wholeNumber) {
                        ForEach(useMetric ? 30...300 : 50...500, id: \.self) { num in
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

                    Text(label)
                        .font(.system(.title3, design: .rounded))
                        .foregroundStyle(.secondary)
                        .padding(.leading, 4)
                }

                Button {
                    let value = Double(wholeNumber) + Double(decimal) / 10.0
                    let weightKg = useMetric ? value : value / 2.20462
                    onSave(weightKg)
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

// MARK: - Body Fat Picker Sheet

struct BodyFatPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let currentPercentage: Double?
    let onSave: (Double?) -> Void

    @State private var percentage: Int

    init(currentPercentage: Double?, onSave: @escaping (Double?) -> Void) {
        self.currentPercentage = currentPercentage
        self.onSave = onSave
        _percentage = State(initialValue: Int((currentPercentage ?? 0.2) * 100))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Body Fat %")
                    .font(.system(.title2, design: .rounded, weight: .bold))

                HStack(spacing: 0) {
                    Picker("Percentage", selection: $percentage) {
                        ForEach(3...60, id: \.self) { n in
                            Text("\(n)").tag(n)
                                .font(.system(.title2, design: .rounded, weight: .medium))
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 100)
                    .clipped()

                    Text("%")
                        .font(.system(.title3, design: .rounded))
                        .foregroundStyle(.secondary)
                        .padding(.leading, 4)
                }

                Button {
                    onSave(Double(percentage) / 100.0)
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

                Button {
                    onSave(nil)
                    dismiss()
                } label: {
                    Text("Remove Body Fat %")
                        .font(.system(.subheadline, design: .rounded, weight: .medium))
                        .foregroundStyle(.red)
                }

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

// MARK: - Goal Body Fat Picker Sheet

/// Same wheel + Save UX as BodyFatPickerSheet but the title and the destructive
/// button text are framed as a "goal" — which is a separate, optional, display-
/// only number that lives alongside `bodyFatPercentage` on UserProfile.
struct GoalBodyFatPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let currentGoal: Double?
    let currentBodyFat: Double?
    let onSave: (Double?) -> Void

    @State private var percentage: Int

    init(currentGoal: Double?, currentBodyFat: Double?, onSave: @escaping (Double?) -> Void) {
        self.currentGoal = currentGoal
        self.currentBodyFat = currentBodyFat
        self.onSave = onSave
        // Seed from the existing goal, falling back to the user's current
        // body-fat value so the wheel lands somewhere sensible on first open.
        let seed = currentGoal ?? currentBodyFat ?? 0.15
        _percentage = State(initialValue: Int(seed * 100))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Text("Body Fat Goal")
                    .font(.system(.title2, design: .rounded, weight: .bold))

                if let bf = currentBodyFat {
                    Text("Currently \(Int(bf * 100))%")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                HStack(spacing: 0) {
                    Picker("Percentage", selection: $percentage) {
                        ForEach(3...60, id: \.self) { n in
                            Text("\(n)").tag(n)
                                .font(.system(.title2, design: .rounded, weight: .medium))
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 100)
                    .clipped()

                    Text("%")
                        .font(.system(.title3, design: .rounded))
                        .foregroundStyle(.secondary)
                        .padding(.leading, 4)
                }

                Button {
                    onSave(Double(percentage) / 100.0)
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

                Button {
                    onSave(nil)
                    dismiss()
                } label: {
                    Text("Remove Goal")
                        .font(.system(.subheadline, design: .rounded, weight: .medium))
                        .foregroundStyle(.red)
                }

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

// MARK: - Activity Level Selection View

struct ActivityLevelSelectionView: View {
    @Binding var selected: ActivityLevel
    let onSave: () -> Void

    var body: some View {
        List {
            ForEach(ActivityLevel.allCases, id: \.self) { level in
                Button {
                    selected = level
                    onSave()
                } label: {
                    HStack(spacing: 14) {
                        Image(systemName: level.icon)
                            .font(.title2)
                            .foregroundStyle(AppColors.calorie)
                            .frame(width: 32)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(level.displayName)
                                .font(.system(.body, design: .rounded, weight: .medium))
                                .foregroundStyle(.primary)
                            Text(level.subtitle)
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        if level == selected {
                            Image(systemName: "checkmark")
                                .foregroundStyle(AppColors.calorie)
                                .fontWeight(.semibold)
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Activity Level")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Weight Goal Selection View

struct WeightGoalSelectionView: View {
    @Binding var selected: WeightGoal
    let onSave: () -> Void

    var body: some View {
        List {
            ForEach(WeightGoal.allCases, id: \.self) { goal in
                Button {
                    selected = goal
                    onSave()
                } label: {
                    HStack(spacing: 14) {
                        Image(systemName: goal.icon)
                            .font(.title2)
                            .foregroundStyle(AppColors.calorie)
                            .frame(width: 32)

                        Text(goal.displayName)
                            .font(.system(.body, design: .rounded, weight: .medium))
                            .foregroundStyle(.primary)

                        Spacer()

                        if goal == selected {
                            Image(systemName: "checkmark")
                                .foregroundStyle(AppColors.calorie)
                                .fontWeight(.semibold)
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Weight Goal")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Gender Selection View

struct GenderSelectionView: View {
    @Binding var selected: Gender
    let onSave: () -> Void

    var body: some View {
        List {
            ForEach(Gender.allCases, id: \.self) { gender in
                Button {
                    selected = gender
                    onSave()
                } label: {
                    HStack(spacing: 14) {
                        Image(systemName: gender.icon)
                            .font(.title2)
                            .foregroundStyle(AppColors.calorie)
                            .frame(width: 32)

                        Text(gender.displayName)
                            .font(.system(.body, design: .rounded, weight: .medium))
                            .foregroundStyle(.primary)

                        Spacer()

                        if gender == selected {
                            Image(systemName: "checkmark")
                                .foregroundStyle(AppColors.calorie)
                                .fontWeight(.semibold)
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Gender")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Goal Speed Selection View

struct GoalSpeedSelectionView: View {
    @Binding var selected: Double?
    let goal: WeightGoal
    let onSave: () -> Void
    @AppStorage("weightUnit") private var weightUnitRaw = "lbs"

    private var options: [(label: String, subtitle: String, value: Double)] {
        let unit = goal == .lose ? "loss" : "gain"
        let useMetric = weightUnitRaw == "kg"
        return [
            ("Slow", "\(WeightDisplayFormatter.weeklyChange(kilograms: 0.25, useMetric: useMetric)) \(unit)", 0.25),
            ("Recommended", "\(WeightDisplayFormatter.weeklyChange(kilograms: 0.5, useMetric: useMetric)) \(unit)", 0.5),
            ("Fast", "\(WeightDisplayFormatter.weeklyChange(kilograms: 1.0, useMetric: useMetric)) \(unit)", 1.0),
        ]
    }

    var body: some View {
        List {
            ForEach(options, id: \.value) { option in
                Button {
                    selected = option.value
                    onSave()
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(option.label)
                                .font(.system(.body, design: .rounded, weight: .medium))
                                .foregroundStyle(.primary)
                            Text(option.subtitle)
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        if selected == option.value {
                            Image(systemName: "checkmark")
                                .foregroundStyle(AppColors.calorie)
                                .fontWeight(.semibold)
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Weekly Change")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Meal Time Settings

struct MealTimeSettingsView: View {
    @AppStorage(MealScheduleSettings.breakfastStartKey)
    private var breakfastStartMinutes = MealSchedule.defaults.breakfastStartMinutes
    @AppStorage(MealScheduleSettings.lunchStartKey)
    private var lunchStartMinutes = MealSchedule.defaults.lunchStartMinutes
    @AppStorage(MealScheduleSettings.dinnerStartKey)
    private var dinnerStartMinutes = MealSchedule.defaults.dinnerStartMinutes
    @AppStorage(MealScheduleSettings.snackStartKey)
    private var snackStartMinutes = MealSchedule.defaults.snackStartMinutes

    var body: some View {
        List {
            Section {
                mealTimePicker(
                    title: "Breakfast starts",
                    icon: "sunrise.fill",
                    minutes: $breakfastStartMinutes,
                    allowedMinutes: validRange(0, lunchStartMinutes - 15)
                )
                mealTimePicker(
                    title: "Lunch starts",
                    icon: "sun.max.fill",
                    minutes: $lunchStartMinutes,
                    allowedMinutes: validRange(breakfastStartMinutes + 15, dinnerStartMinutes - 15)
                )
                mealTimePicker(
                    title: "Dinner starts",
                    icon: "moon.fill",
                    minutes: $dinnerStartMinutes,
                    allowedMinutes: validRange(lunchStartMinutes + 15, snackStartMinutes - 15)
                )
                mealTimePicker(
                    title: "Late snack starts",
                    icon: "cup.and.saucer.fill",
                    minutes: $snackStartMinutes,
                    allowedMinutes: validRange(dinnerStartMinutes + 15, 1439)
                )
            } header: {
                Text("Automatic Meal Selection")
            } footer: {
                Text("Each meal continues until the next one starts. Late Snack continues overnight until Breakfast starts. You can still change the meal manually before logging.")
            }
            .listRowBackground(AppColors.appCard)

            Section {
                Button("Restore Default Times") {
                    let defaults = MealSchedule.defaults
                    breakfastStartMinutes = defaults.breakfastStartMinutes
                    lunchStartMinutes = defaults.lunchStartMinutes
                    dinnerStartMinutes = defaults.dinnerStartMinutes
                    snackStartMinutes = defaults.snackStartMinutes
                }
                .foregroundStyle(AppColors.calorie)
            }
            .listRowBackground(AppColors.appCard)
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Meal Times")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: repairInvalidScheduleIfNeeded)
    }

    @ViewBuilder
    private func mealTimePicker(
        title: String,
        icon: String,
        minutes: Binding<Int>,
        allowedMinutes: ClosedRange<Int>
    ) -> some View {
        DatePicker(
            selection: dateBinding(minutes),
            in: dateRange(allowedMinutes),
            displayedComponents: .hourAndMinute
        ) {
            Label {
                Text(title)
            } icon: {
                Image(systemName: icon)
                    .foregroundStyle(AppColors.calorie)
            }
        }
        .tint(AppColors.calorie)
    }

    private func dateBinding(_ minutes: Binding<Int>) -> Binding<Date> {
        Binding(
            get: { date(for: minutes.wrappedValue) },
            set: { newDate in
                let components = Calendar.current.dateComponents([.hour, .minute], from: newDate)
                minutes.wrappedValue = (components.hour ?? 0) * 60 + (components.minute ?? 0)
            }
        )
    }

    private func dateRange(_ minutes: ClosedRange<Int>) -> ClosedRange<Date> {
        date(for: minutes.lowerBound)...date(for: minutes.upperBound)
    }

    private func date(for minutes: Int) -> Date {
        Calendar.current.date(
            byAdding: .minute,
            value: Swift.min(Swift.max(minutes, 0), 1439),
            to: Calendar.current.startOfDay(for: .now)
        ) ?? .now
    }

    private func validRange(_ proposedLowerBound: Int, _ proposedUpperBound: Int) -> ClosedRange<Int> {
        let lowerBound = Swift.min(Swift.max(proposedLowerBound, 0), 1439)
        let upperBound = Swift.max(lowerBound, Swift.min(Swift.max(proposedUpperBound, 0), 1439))
        return lowerBound...upperBound
    }

    private func repairInvalidScheduleIfNeeded() {
        let stored = MealSchedule(
            breakfastStartMinutes: breakfastStartMinutes,
            lunchStartMinutes: lunchStartMinutes,
            dinnerStartMinutes: dinnerStartMinutes,
            snackStartMinutes: snackStartMinutes
        )
        guard !stored.isValid else { return }
        let defaults = MealSchedule.defaults
        breakfastStartMinutes = defaults.breakfastStartMinutes
        lunchStartMinutes = defaults.lunchStartMinutes
        dinnerStartMinutes = defaults.dinnerStartMinutes
        snackStartMinutes = defaults.snackStartMinutes
    }
}

// MARK: - Nutrition Override Row

struct NutritionOverrideRow: View {
    let label: String
    let icon: String
    let color: Color
    let computedValue: Int
    @Binding var customValue: Int?

    @State private var isCustom: Bool = false
    @State private var stepperValue: Int = 0

    var body: some View {
        VStack(spacing: 8) {
            Toggle(isOn: $isCustom) {
                Label(LocalizedDisplayText.text(label), systemImage: icon)
            }
            .onChange(of: isCustom) { _, newValue in
                if newValue {
                    stepperValue = customValue ?? computedValue
                    customValue = stepperValue
                } else {
                    customValue = nil
                }
            }

            if isCustom {
                Stepper(
                    "\(stepperValue)\(label == "Calories" ? " kcal" : "g")",
                    value: $stepperValue,
                    in: label == "Calories" ? 800...6000 : 0...500,
                    step: label == "Calories" ? 50 : 5
                )
                .onChange(of: stepperValue) { _, newValue in
                    customValue = newValue
                }
            }
        }
        .onAppear {
            isCustom = customValue != nil
            stepperValue = customValue ?? computedValue
        }
    }
}

// MARK: - Nutrition Summary Row

struct NutritionSummaryRow: View {
    let profile: UserProfile

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("BMR")
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                Spacer()
                Text("\(Int(profile.bmr)) kcal")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            HStack {
                Text("TDEE")
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                Spacer()
                Text("\(Int(profile.tdee)) kcal")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            if profile.goal != .maintain {
                HStack {
                    Text("Adjustment")
                        .font(.system(.subheadline, design: .rounded, weight: .medium))
                    Spacer()
                    Text("\(profile.calorieAdjustment > 0 ? "+" : "")\(profile.calorieAdjustment) kcal")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            HStack {
                Text("Daily Target")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Spacer()
                Text("\(profile.effectiveCalories) kcal")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
            }
        }
    }
}

// MARK: - Nutrition Picker Sheet

struct NutritionPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let label: String
    let unit: String
    let currentValue: Int
    let range: ClosedRange<Int>
    let step: Int
    let allowsCustomValue: Bool
    let guidanceUpperLimit: Int?
    let customValueDetail: ((Int) -> String?)?
    let onSave: (Int) -> Void
    /// Optional callback to revert this macro to auto-balanced (custom value cleared).
    /// When provided, a button labeled `resetLabel` appears in the sheet.
    var onResetToAuto: (() -> Void)? = nil
    /// Label for the reset button (defaults to the macro "Reset to Auto-balance" wording).
    var resetLabel: String = "Reset to Auto-balance"
    /// Optional live wheel-selection reporter, for hosts that need the current
    /// value before Save (e.g. to convert it when a unit switcher flips).
    var onValueChange: ((Int) -> Void)? = nil

    @State private var selectedValue: Int
    @State private var isEnteringCustomValue: Bool
    @State private var customValueText: String
    @State private var customFocusRequest = 0

    init(
        label: String,
        unit: String,
        currentValue: Int,
        range: ClosedRange<Int>,
        step: Int,
        allowsCustomValue: Bool = false,
        guidanceUpperLimit: Int? = nil,
        customValueDetail: ((Int) -> String?)? = nil,
        onSave: @escaping (Int) -> Void,
        onResetToAuto: (() -> Void)? = nil,
        resetLabel: String = "Reset to Auto-balance",
        onValueChange: ((Int) -> Void)? = nil
    ) {
        self.label = label
        self.unit = unit
        self.currentValue = currentValue
        self.range = range
        self.step = step
        self.allowsCustomValue = allowsCustomValue
        self.guidanceUpperLimit = guidanceUpperLimit
        self.customValueDetail = customValueDetail
        self.onSave = onSave
        self.onResetToAuto = onResetToAuto
        self.resetLabel = resetLabel
        self.onValueChange = onValueChange
        // Snap to nearest step and clamp into range so the wheel opens at the current value.
        let snapped = (currentValue / step) * step
        let clamped = min(max(snapped, range.lowerBound), range.upperBound)
        _selectedValue = State(initialValue: clamped)
        let isPresetValue = range.contains(currentValue)
            && (currentValue - range.lowerBound).isMultiple(of: step)
        _isEnteringCustomValue = State(initialValue: allowsCustomValue && !isPresetValue)
        _customValueText = State(initialValue: String(currentValue))
    }

    private var customValue: Int? {
        guard let value = Int(customValueText),
              (0...OptionalNutrientGoals.maximumCustomGoal).contains(value)
        else { return nil }
        return value
    }

    private var valueToSave: Int? {
        isEnteringCustomValue ? customValue : selectedValue
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text(LocalizedDisplayText.text(label))
                    .font(.system(.title2, design: .rounded, weight: .bold))

                if isEnteringCustomValue {
                    VStack(spacing: 10) {
                        HStack(spacing: 10) {
                            EndEditingDecimalTextField(
                                text: Binding(
                                    get: { customValueText },
                                    set: { newValue in
                                        customValueText = String(newValue.filter(\.isNumber).prefix(7))
                                    }
                                ),
                                focusRequest: customFocusRequest,
                                onEditingChanged: { _ in },
                                keyboardType: .numberPad,
                                placeholder: "0",
                                accessibilityLabel: "Custom \(label) goal"
                            )
                            .frame(height: 28)

                            Text(unit)
                                .font(.system(.title3, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, 18)
                        .padding(.vertical, 14)
                        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))

                        if let customValue, let detail = customValueDetail?(customValue) {
                            Text(detail)
                                .font(.system(.subheadline, design: .rounded, weight: .medium))
                                .foregroundStyle(.secondary)
                        }

                        if let customValue, let upperLimit = guidanceUpperLimit, customValue > upperLimit {
                            Text("Above the general adult upper intake level. Use only if recommended by a healthcare professional.")
                                .font(.system(.caption, design: .rounded, weight: .medium))
                                .foregroundStyle(.orange)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 24)
                        } else if customValue == nil {
                            Text("Enter a whole number from 0 to 999,999.")
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 24)
                } else {
                    HStack(spacing: 0) {
                        Picker(LocalizedDisplayText.text(label), selection: $selectedValue) {
                            ForEach(Array(stride(from: range.lowerBound, through: range.upperBound, by: step)), id: \.self) { value in
                                Text("\(value)").tag(value)
                                    .font(.system(.title2, design: .rounded, weight: .medium))
                            }
                        }
                        .pickerStyle(.wheel)
                        .frame(width: 120)
                        .clipped()
                        .onChange(of: selectedValue) { _, newValue in
                            onValueChange?(newValue)
                        }

                        Text(unit)
                            .font(.system(.title3, design: .rounded))
                            .foregroundStyle(.secondary)
                            .padding(.leading, 4)
                    }
                }

                if allowsCustomValue {
                    Button(isEnteringCustomValue ? "Use preset wheel" : "Custom amount") {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            isEnteringCustomValue.toggle()
                            if isEnteringCustomValue {
                                customValueText = String(selectedValue)
                                customFocusRequest += 1
                            }
                        }
                    }
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                }

                Button {
                    guard let valueToSave else { return }
                    onSave(valueToSave)
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
                .disabled(valueToSave == nil)
                .opacity(valueToSave == nil ? 0.45 : 1)

                if let resetAction = onResetToAuto {
                    Button {
                        resetAction()
                        dismiss()
                    } label: {
                        Text(resetLabel)
                            .font(.system(.subheadline, design: .rounded, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.top, 4)
                }

                Spacer()
            }
            .padding(.top, 24)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Notification Settings Sections

/// The notification sections of Settings → Tracking & Notifications, shown below
/// the tracking rows inside ProfileView's list (#432).
struct NotificationSettingsSections: View {
    @Environment(NotificationManager.self) private var notificationManager
    @Environment(FastingStore.self) private var fastingStore
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false

    @AppStorage("breakfastReminderEnabled") private var breakfastEnabled = true
    @AppStorage("breakfastReminderHour") private var breakfastHour = 8
    @AppStorage("breakfastReminderMinute") private var breakfastMinute = 0

    @AppStorage("lunchReminderEnabled") private var lunchEnabled = true
    @AppStorage("lunchReminderHour") private var lunchHour = 12
    @AppStorage("lunchReminderMinute") private var lunchMinute = 0

    @AppStorage("dinnerReminderEnabled") private var dinnerEnabled = true
    @AppStorage("dinnerReminderHour") private var dinnerHour = 19
    @AppStorage("dinnerReminderMinute") private var dinnerMinute = 0

    @AppStorage("streakReminderEnabled") private var streakEnabled = true
    @AppStorage("streakReminderHour") private var streakHour = 21
    @AppStorage("streakReminderMinute") private var streakMinute = 0

    @AppStorage("dailySummaryEnabled") private var summaryEnabled = true
    @AppStorage("dailySummaryHour") private var summaryHour = 20
    @AppStorage("dailySummaryMinute") private var summaryMinute = 0

    @AppStorage("weightLogReminderEnabled") private var weightLogEnabled = true
    @AppStorage("weightLogReminderHour") private var weightLogHour = 8
    @AppStorage("weightLogReminderMinute") private var weightLogMinute = 0

    @AppStorage("bodyFatLogReminderEnabled") private var bodyFatLogEnabled = false
    @AppStorage("bodyFatLogReminderHour") private var bodyFatLogHour = 8
    @AppStorage("bodyFatLogReminderMinute") private var bodyFatLogMinute = 0

    @AppStorage("appUpdateNotificationsEnabled") private var appUpdatesEnabled = true
    @AppStorage(WaterSettings.enabledKey) private var waterTrackingEnabled = false
    @AppStorage(WaterSettings.reminderEnabledKey) private var waterReminderEnabled = false
    @AppStorage(WaterSettings.reminderHourKey) private var waterReminderHour = 14
    @AppStorage(WaterSettings.reminderMinuteKey) private var waterReminderMinute = 0
    @AppStorage(FastingSettings.enabledKey) private var fastingTrackingEnabled = false
    @AppStorage(FastingSettings.notificationEnabledKey) private var fastingGoalNotificationEnabled = true

    var body: some View {
        // Master toggle
        Section {
            Toggle(isOn: $notificationsEnabled) {
                Label {
                    Text("Notifications")
                } icon: {
                    Image(systemName: "bell.fill")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .tint(AppColors.calorie)
            .accessibilityIdentifier("settings.notifications.enabled")
            .task {
                await notificationManager.refreshAuthorizationStatus()
            }
            .onChange(of: notificationsEnabled) { _, enabled in
                if enabled {
                    Task {
                        let granted = await notificationManager.requestAuthorization()
                        if !granted {
                            notificationsEnabled = false
                        } else {
                            applyMealReminders()
                            applyWaterReminder()
                            applyFastingGoalNotification()
                            WorkoutRestTimerController.shared.rescheduleNotification()
                            await notificationManager.scheduleProductHuntLaunchReminderIfNeeded()
                            await RevenueCatManager.shared.refreshCustomerInfo()
                        }
                    }
                } else {
                    notificationManager.cancelAllNotifications()
                }
            }
        } footer: {
            if notificationManager.authorizationStatus == .denied {
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Text("Notifications are disabled in system settings. Tap to open Settings.")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(AppColors.calorie)
                }
            }
        }
        .listRowBackground(AppColors.appCard)

        if notificationsEnabled {
            // Meal Reminders
            Section("Meal Reminders") {
                NotificationTimeRow(
                    label: "Breakfast",
                    icon: "sunrise.fill",
                    isEnabled: $breakfastEnabled,
                    hour: $breakfastHour,
                    minute: $breakfastMinute
                )
                .onChange(of: breakfastEnabled) { _, _ in applyMealReminders() }
                .onChange(of: breakfastHour) { _, _ in applyMealReminders() }
                .onChange(of: breakfastMinute) { _, _ in applyMealReminders() }

                NotificationTimeRow(
                    label: "Lunch",
                    icon: "sun.max.fill",
                    isEnabled: $lunchEnabled,
                    hour: $lunchHour,
                    minute: $lunchMinute
                )
                .onChange(of: lunchEnabled) { _, _ in applyMealReminders() }
                .onChange(of: lunchHour) { _, _ in applyMealReminders() }
                .onChange(of: lunchMinute) { _, _ in applyMealReminders() }

                NotificationTimeRow(
                    label: "Dinner",
                    icon: "moon.fill",
                    isEnabled: $dinnerEnabled,
                    hour: $dinnerHour,
                    minute: $dinnerMinute
                )
                .onChange(of: dinnerEnabled) { _, _ in applyMealReminders() }
                .onChange(of: dinnerHour) { _, _ in applyMealReminders() }
                .onChange(of: dinnerMinute) { _, _ in applyMealReminders() }
            }
            .listRowBackground(AppColors.appCard)

            if waterTrackingEnabled {
                Section("Water") {
                    NotificationTimeRow(
                        label: "Water Reminder",
                        icon: "drop.fill",
                        isEnabled: $waterReminderEnabled,
                        hour: $waterReminderHour,
                        minute: $waterReminderMinute
                    )
                    .onChange(of: waterReminderEnabled) { _, _ in applyWaterReminder() }
                    .onChange(of: waterReminderHour) { _, _ in applyWaterReminder() }
                    .onChange(of: waterReminderMinute) { _, _ in applyWaterReminder() }
                }
                .listRowBackground(AppColors.appCard)
            }

            if fastingTrackingEnabled {
                Section {
                    Toggle(isOn: $fastingGoalNotificationEnabled) {
                        Label {
                            Text("Goal Reached")
                        } icon: {
                            Image(systemName: "timer")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(AppColors.calorie)
                    .onChange(of: fastingGoalNotificationEnabled) { _, _ in
                        applyFastingGoalNotification()
                    }
                } header: {
                    Text("Fasting")
                } footer: {
                    Text("Notifies you once when the active fast reaches its goal. The timer continues until you end it.")
                        .font(.system(.caption, design: .rounded))
                }
                .listRowBackground(AppColors.appCard)
            }

            // Smart Notifications
            Section {
                NotificationTimeRow(
                    label: "Streak Reminder",
                    icon: "flame.fill",
                    isEnabled: $streakEnabled,
                    hour: $streakHour,
                    minute: $streakMinute
                )

                NotificationTimeRow(
                    label: "Daily Summary",
                    icon: "chart.bar.fill",
                    isEnabled: $summaryEnabled,
                    hour: $summaryHour,
                    minute: $summaryMinute
                )

                NotificationTimeRow(
                    label: "Log Weight",
                    icon: "scalemass.fill",
                    isEnabled: $weightLogEnabled,
                    hour: $weightLogHour,
                    minute: $weightLogMinute
                )

                NotificationTimeRow(
                    label: "Log Body Fat",
                    icon: "percent",
                    isEnabled: $bodyFatLogEnabled,
                    hour: $bodyFatLogHour,
                    minute: $bodyFatLogMinute
                )
            } header: {
                Text("Smart Notifications")
            } footer: {
                Text("All four reminders are smart — they skip firing on days you've already logged. Body fat default is off since most users don't measure daily.")
                    .font(.system(.caption, design: .rounded))
            }
            .listRowBackground(AppColors.appCard)

            // App Updates
            Section {
                Toggle(isOn: $appUpdatesEnabled) {
                    Label {
                        Text("App Updates")
                    } icon: {
                        Image(systemName: "arrow.down.circle.fill")
                            .foregroundStyle(AppColors.calorie)
                    }
                }
                .tint(AppColors.calorie)
            } header: {
                Text("App")
            } footer: {
                Text("Get notified when a new version is available. Tap the notification to open the App Store.")
                    .font(.system(.caption, design: .rounded))
            }
            .listRowBackground(AppColors.appCard)
        }
    }

    private func applyMealReminders() {
        notificationManager.scheduleMealReminders(
            breakfastEnabled: breakfastEnabled, breakfastHour: breakfastHour, breakfastMinute: breakfastMinute,
            lunchEnabled: lunchEnabled, lunchHour: lunchHour, lunchMinute: lunchMinute,
            dinnerEnabled: dinnerEnabled, dinnerHour: dinnerHour, dinnerMinute: dinnerMinute
        )
    }

    private func applyWaterReminder() {
        notificationManager.scheduleWaterReminder(
            enabled: notificationsEnabled && waterTrackingEnabled && waterReminderEnabled,
            hour: waterReminderHour,
            minute: waterReminderMinute
        )
    }

    private func applyFastingGoalNotification() {
        notificationManager.scheduleFastingGoal(
            enabled: notificationsEnabled && fastingTrackingEnabled && fastingGoalNotificationEnabled,
            session: fastingStore.activeSession
        )
    }
}

// MARK: - Notification Time Row

struct NotificationTimeRow: View {
    let label: String
    let icon: String
    @Binding var isEnabled: Bool
    @Binding var hour: Int
    @Binding var minute: Int

    private var timeDate: Binding<Date> {
        Binding(
            get: {
                Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: Date()) ?? Date()
            },
            set: { newDate in
                let components = Calendar.current.dateComponents([.hour, .minute], from: newDate)
                hour = components.hour ?? hour
                minute = components.minute ?? minute
            }
        )
    }

    var body: some View {
        VStack(spacing: 8) {
            Toggle(isOn: $isEnabled) {
                Label {
                    Text(LocalizedDisplayText.text(label))
                } icon: {
                    Image(systemName: icon)
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .tint(AppColors.calorie)

            if isEnabled {
                DatePicker(
                    "Time",
                    selection: timeDate,
                    displayedComponents: .hourAndMinute
                )
                .datePickerStyle(.compact)
                .labelsHidden()
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
    }
}

// MARK: - Coming Soon Row

struct ComingSoonRow: View {
    let icon: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Label {
                    Text(LocalizedDisplayText.text(label))
                } icon: {
                    Image(systemName: icon)
                        .foregroundStyle(AppColors.calorie)
                }
                Spacer()
                Text("Coming Soon")
                    .font(.system(.caption, design: .rounded, weight: .medium))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(AppColors.calorie.opacity(0.12))
                    .foregroundStyle(AppColors.calorie)
                    .clipShape(Capsule())
            }
        }
        .buttonStyle(.plain)
    }
}
