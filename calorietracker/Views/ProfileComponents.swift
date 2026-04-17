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
                    Text(label)
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
    let useMetric: Bool
    @State private var feet: Int = 5
    @State private var inches: Int = 9
    @State private var cm: Int = 175
    let currentHeightCm: Double
    let onSave: (Double) -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Height")
                    .font(.system(.title2, design: .rounded, weight: .bold))

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
        .onAppear {
            cm = Int(currentHeightCm)
            let totalInches = currentHeightCm / 2.54
            feet = Int(totalInches) / 12
            inches = Int(totalInches) % 12
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Weight Picker Sheet

struct WeightPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let useMetric: Bool
    @State private var wholeNumber: Int = 154
    @State private var decimal: Int = 0
    let currentWeightKg: Double
    let onSave: (Double) -> Void

    private var label: String { useMetric ? "kg" : "lbs" }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Weight")
                    .font(.system(.title2, design: .rounded, weight: .bold))

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
        .onAppear {
            let displayValue = useMetric ? currentWeightKg : currentWeightKg * 2.20462
            wholeNumber = Int(displayValue)
            decimal = Int((displayValue - Double(Int(displayValue))) * 10 + 0.5)
            if decimal >= 10 { decimal = 9 }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Body Fat Picker Sheet

struct BodyFatPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var percentage: Int = 20
    let currentPercentage: Double?
    let onSave: (Double?) -> Void

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
        .onAppear {
            if let pct = currentPercentage {
                percentage = Int(pct * 100)
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

    private var options: [(label: String, subtitle: String, value: Double)] {
        let unit = goal == .lose ? "loss" : "gain"
        return [
            ("Slow", "0.25 kg/week \(unit)", 0.25),
            ("Recommended", "0.5 kg/week \(unit)", 0.5),
            ("Fast", "1.0 kg/week \(unit)", 1.0),
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
                Label(label, systemImage: icon)
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
    let onSave: (Int) -> Void
    /// Optional callback to revert this macro to auto-balanced (custom value cleared).
    /// When provided, a "Reset to Auto" button appears in the sheet.
    var onResetToAuto: (() -> Void)? = nil

    @State private var selectedValue: Int = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text(label)
                    .font(.system(.title2, design: .rounded, weight: .bold))

                HStack(spacing: 0) {
                    Picker(label, selection: $selectedValue) {
                        ForEach(Array(stride(from: range.lowerBound, through: range.upperBound, by: step)), id: \.self) { value in
                            Text("\(value)").tag(value)
                                .font(.system(.title2, design: .rounded, weight: .medium))
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 120)
                    .clipped()

                    Text(unit)
                        .font(.system(.title3, design: .rounded))
                        .foregroundStyle(.secondary)
                        .padding(.leading, 4)
                }

                Button {
                    onSave(selectedValue)
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

                if let resetAction = onResetToAuto {
                    Button {
                        resetAction()
                        dismiss()
                    } label: {
                        Text("Reset to Auto-balance")
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
        .onAppear {
            // Snap to nearest step value
            let snapped = (currentValue / step) * step
            selectedValue = min(max(snapped, range.lowerBound), range.upperBound)
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Notification Settings View

struct NotificationSettingsView: View {
    @Environment(NotificationManager.self) private var notificationManager
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

    var body: some View {
        List {
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
                .onChange(of: notificationsEnabled) { _, enabled in
                    if enabled {
                        Task {
                            let granted = await notificationManager.requestAuthorization()
                            if !granted {
                                notificationsEnabled = false
                            } else {
                                applyMealReminders()
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
                } header: {
                    Text("Smart Notifications")
                } footer: {
                    Text("Streak and summary notifications update automatically based on your logged food.")
                        .font(.system(.caption, design: .rounded))
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Notifications")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await notificationManager.refreshAuthorizationStatus()
        }
    }

    private func applyMealReminders() {
        notificationManager.scheduleMealReminders(
            breakfastEnabled: breakfastEnabled, breakfastHour: breakfastHour, breakfastMinute: breakfastMinute,
            lunchEnabled: lunchEnabled, lunchHour: lunchHour, lunchMinute: lunchMinute,
            dinnerEnabled: dinnerEnabled, dinnerHour: dinnerHour, dinnerMinute: dinnerMinute
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
                    Text(label)
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
                    Text(label)
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
