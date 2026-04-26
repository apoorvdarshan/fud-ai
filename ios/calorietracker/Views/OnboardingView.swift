import SwiftUI
import HealthKit
import StoreKit

struct OnboardingView: View {
    @Binding var hasCompletedOnboarding: Bool
    @Environment(NotificationManager.self) private var notificationManager
    @Environment(FoodStore.self) private var foodStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(HealthKitManager.self) private var healthKitManager

    @State private var step = 0
    @State private var gender: Gender = .male
    @State private var birthday: Date = Calendar.current.date(byAdding: .year, value: -25, to: Date()) ?? Date()
    @AppStorage("useMetric") private var useMetric = false
    @State private var isMetric = false
    @State private var heightFeet = 5
    @State private var heightInches = 9
    @State private var heightCm = 175
    // Weights are split into whole + tenth so the SwiftUI wheel picker can stay
    // Int-tagged (fractional tags don't pair cleanly with Picker) while users
    // still get 0.1-precision selection. Combine via `Double(whole) + Double(tenth) / 10.0`.
    @State private var weightLbsWhole = 154
    @State private var weightLbsTenth = 0
    @State private var weightKgWhole = 70
    @State private var weightKgTenth = 0
    @State private var activityLevel: ActivityLevel = .moderate
    @State private var goal: WeightGoal = .maintain
    @State private var targetWeightLbsWhole = 154
    @State private var targetWeightLbsTenth = 0
    @State private var targetWeightKgWhole = 70
    @State private var targetWeightKgTenth = 0
    @State private var goalSpeed = 1
    @State private var knowsBodyFat = false
    @State private var bodyFatPercentage = 20
    /// Optional target body-fat % (whole number, 3–60). Nil means "skip" — the
    /// user opted out, or hasn't entered a current body fat (the goal field
    /// only appears when knowsBodyFat is true).
    @State private var goalBodyFatPercentInt: Int? = nil
    @State private var editedCalories: Int?
    @State private var editedProtein: Int?
    @State private var editedFat: Int?
    @State private var editedCarbs: Int?
    @State private var editingField: EditableField?

    private enum EditableField: String, Identifiable {
        case calories, protein, fat, carbs
        var id: String { rawValue }
    }

    private let totalSteps = 15 // 0-14

    /// Combine the whole + tenth wheel selections into a single Double.
    private func combine(_ whole: Int, _ tenth: Int) -> Double { Double(whole) + Double(tenth) / 10.0 }

    private var weightKg: Double { combine(weightKgWhole, weightKgTenth) }
    private var weightLbs: Double { combine(weightLbsWhole, weightLbsTenth) }
    private var targetWeightKg: Double { combine(targetWeightKgWhole, targetWeightKgTenth) }
    private var targetWeightLbs: Double { combine(targetWeightLbsWhole, targetWeightLbsTenth) }

    private var profile: UserProfile {
        let cm: Double
        let kg: Double
        if isMetric {
            cm = Double(heightCm)
            kg = weightKg
        } else {
            cm = Double(heightFeet) * 30.48 + Double(heightInches) * 2.54
            kg = weightLbs * 0.453592
        }
        let targetKg: Double? = goal == .maintain ? nil : (isMetric ? targetWeightKg : targetWeightLbs * 0.453592)
        return UserProfile(
            gender: gender,
            birthday: birthday,
            heightCm: cm,
            weightKg: kg,
            activityLevel: activityLevel,
            goal: goal,
            bodyFatPercentage: knowsBodyFat ? Double(bodyFatPercentage) / 100.0 : nil,
            goalBodyFatPercentage: knowsBodyFat ? goalBodyFatPercentInt.map { Double($0) / 100.0 } : nil,
            weeklyChangeKg: goal == .maintain ? nil : weeklyChangeKg,
            goalWeightKg: targetKg
        )
    }

    var body: some View {
        VStack(spacing: 0) {
                if step > 0 && step < totalSteps - 1 {
                    HStack(spacing: 16) {
                        Button {
                            withAnimation(.snappy) { step -= 1 }
                        } label: {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(.primary)
                        }

                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule()
                                    .fill(Color.primary.opacity(0.08))
                                Capsule()
                                    .fill(Color.primary)
                                    .frame(width: geo.size.width * CGFloat(step) / CGFloat(totalSteps - 1))
                                    .animation(.snappy, value: step)
                            }
                        }
                        .frame(height: 4)
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
                    .padding(.bottom, 8)
                }

                ZStack {
                    switch step {
                    case 0: welcomeStep
                    case 1: genderStep
                    case 2: birthdayStep
                    case 3: heightWeightStep
                    case 4: bodyFatStep
                    case 5: activityStep
                    case 6: goalStep
                    case 7: desiredWeightStep
                    case 8: goalSpeedStep
                    case 9: notificationsStep
                    case 10: appleHealthStep
                    case 11: aiProviderStep
                    case 12: buildingPlanStep
                    case 13: planReadyStep
                    case 14: reviewStep
                    default: EmptyView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.asymmetric(
                    insertion: .move(edge: .trailing).combined(with: .opacity),
                    removal: .move(edge: .leading).combined(with: .opacity)
                ))
                .animation(.snappy, value: step)
            }
    }

    // MARK: - Continue Button

    private func continueButton(_ title: String = "Continue", action: @escaping () -> Void = {}) -> some View {
        Button {
            action()
            withAnimation(.snappy) { step += 1 }
        } label: {
            Text(title)
                .font(.system(.body, design: .rounded, weight: .semibold))
                .foregroundStyle(Color(.systemBackground))
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background(Color.primary, in: Capsule())
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 36)
    }

    // MARK: - 0: Welcome

    private var welcomeStep: some View {
        VStack(spacing: 0) {
            Spacer()
            VStack(spacing: 20) {
                Image("onboardingLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 120, height: 120)

                VStack(spacing: 8) {
                    Text("Eat Smart,")
                        .font(.system(size: 32, weight: .bold, design: .rounded))
                    Text("Live Better")
                        .font(.system(size: 32, weight: .bold, design: .rounded))
                        .foregroundStyle(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                        )
                }
                Text("Just snap, track, and thrive.\nYour nutrition, simplified.")
                    .font(.system(.callout, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            Spacer()

            Button {
                withAnimation(.snappy) { step += 1 }
            } label: {
                Text("Get Started")
                    .font(.system(.headline, design: .rounded, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(
                        LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                    )
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 36)
        }
    }

    // MARK: - 1: Gender

    private var genderStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "What's your gender?", subtitle: "This helps us calculate your metabolism")
            Spacer()
            VStack(spacing: 12) {
                ForEach(Gender.allCases, id: \.self) { g in
                    selectionCard(icon: g.icon, title: g.displayName, isSelected: gender == g) {
                        withAnimation(.spring(response: 0.3)) { gender = g }
                    }
                }
            }
            .padding(.horizontal, 24)
            Spacer()
            continueButton()
        }
    }

    // MARK: - 2: Birthday

    private var birthdayStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "When's your birthday?", subtitle: "Used to calculate your daily needs")
            Spacer()
            DatePicker("Birthday", selection: $birthday, in: ...Date(), displayedComponents: .date)
                .datePickerStyle(.wheel)
                .labelsHidden()
                .padding(.horizontal, 24)
            Spacer()
            continueButton()
        }
    }

    // MARK: - 3: Height & Weight

    private var heightWeightStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "Height & Weight", subtitle: "We'll keep this private")
            Picker("Unit", selection: $isMetric) {
                Text("Imperial").tag(false)
                Text("Metric").tag(true)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 24)
            .padding(.top, 16)
            .onChange(of: isMetric) { _, newValue in useMetric = newValue }
            Spacer()
            if isMetric {
                HStack(spacing: 4) {
                    VStack(spacing: 4) {
                        Text("Height").font(.system(.caption, design: .rounded, weight: .medium)).foregroundStyle(.secondary)
                        Picker("cm", selection: $heightCm) {
                            ForEach(100...250, id: \.self) { cm in Text("\(cm) cm").tag(cm) }
                        }.pickerStyle(.wheel)
                    }
                    VStack(spacing: 4) {
                        Text("Weight").font(.system(.caption, design: .rounded, weight: .medium)).foregroundStyle(.secondary)
                        decimalWeightWheel(whole: $weightKgWhole, tenth: $weightKgTenth, range: 30...250, unit: "kg")
                    }
                }.padding(.horizontal, 16)
            } else {
                HStack(spacing: 4) {
                    VStack(spacing: 4) {
                        Text("Feet").font(.system(.caption, design: .rounded, weight: .medium)).foregroundStyle(.secondary)
                        Picker("ft", selection: $heightFeet) {
                            ForEach(3...8, id: \.self) { ft in Text("\(ft) ft").tag(ft) }
                        }.pickerStyle(.wheel)
                    }
                    VStack(spacing: 4) {
                        Text("Inches").font(.system(.caption, design: .rounded, weight: .medium)).foregroundStyle(.secondary)
                        Picker("in", selection: $heightInches) {
                            ForEach(0...11, id: \.self) { inch in Text("\(inch) in").tag(inch) }
                        }.pickerStyle(.wheel)
                    }
                    VStack(spacing: 4) {
                        Text("Weight").font(.system(.caption, design: .rounded, weight: .medium)).foregroundStyle(.secondary)
                        decimalWeightWheel(whole: $weightLbsWhole, tenth: $weightLbsTenth, range: 60...500, unit: "lbs")
                    }
                }.padding(.horizontal, 16)
            }
            Spacer()
            continueButton()
        }
    }

    // MARK: - 4: Body Fat

    private var bodyFatStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "Do you know your\nbody fat %?", subtitle: "Helps us calculate your metabolism more accurately")
            Spacer()
            VStack(spacing: 12) {
                selectionCard(icon: "checkmark.circle", title: "Yes", isSelected: knowsBodyFat) {
                    withAnimation(.spring(response: 0.3)) { knowsBodyFat = true }
                }
                selectionCard(icon: "xmark.circle", title: "No", isSelected: !knowsBodyFat) {
                    withAnimation(.spring(response: 0.3)) { knowsBodyFat = false }
                }
            }
            .padding(.horizontal, 24)
            if knowsBodyFat {
                ScrollView {
                    VStack(spacing: 16) {
                        VStack(spacing: 4) {
                            Text("Current")
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 24)
                            Picker("Body Fat %", selection: $bodyFatPercentage) {
                                ForEach(3...60, id: \.self) { pct in Text("\(pct)%").tag(pct) }
                            }
                            .pickerStyle(.wheel)
                            .frame(height: 130)
                            .padding(.horizontal, 24)
                            Text("Common ranges: Men 10–25%, Women 18–35%")
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity)
                        }

                        // Optional goal sub-section. Skip is the default — keeping it
                        // off-by-default avoids surprising users who don't have a
                        // body-recomp goal in mind. Goal body fat % is display-only
                        // (drives the Progress tab chart line) — it does NOT
                        // participate in BMR / TDEE / macro math.
                        VStack(spacing: 4) {
                            HStack {
                                Text("Goal (optional)")
                                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                    .foregroundStyle(.secondary)
                                Spacer()
                                Toggle("", isOn: Binding(
                                    get: { goalBodyFatPercentInt != nil },
                                    set: { isOn in
                                        // Default the goal to the current value
                                        // when toggled on — gives the user a sane
                                        // starting point to scroll up/down from.
                                        goalBodyFatPercentInt = isOn ? bodyFatPercentage : nil
                                    }
                                ))
                                .labelsHidden()
                                .tint(AppColors.calorie)
                            }
                            .padding(.horizontal, 24)

                            if let _ = goalBodyFatPercentInt {
                                Picker("Goal Body Fat %", selection: Binding(
                                    get: { goalBodyFatPercentInt ?? bodyFatPercentage },
                                    set: { goalBodyFatPercentInt = $0 }
                                )) {
                                    ForEach(3...60, id: \.self) { pct in Text("\(pct)%").tag(pct) }
                                }
                                .pickerStyle(.wheel)
                                .frame(height: 110)
                                .padding(.horizontal, 24)
                            } else {
                                Text("You can set this later in Settings.")
                                    .font(.system(.caption, design: .rounded))
                                    .foregroundStyle(.tertiary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 24)
                                    .padding(.top, 4)
                            }
                        }
                    }
                    .padding(.vertical, 8)
                }
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "function")
                        .font(.system(size: 28))
                        .foregroundStyle(.secondary)
                    Text("No worries! We'll use a standard formula\nbased on your height, weight, and age.")
                        .font(.system(.callout, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.top, 24)
                .frame(maxWidth: .infinity)
            }
            Spacer()
            continueButton()
        }
    }

    // MARK: - 5: Activity Level

    private var activityStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "How active are you?", subtitle: "Your typical week")
            ScrollView {
                VStack(spacing: 12) {
                    ForEach(ActivityLevel.allCases, id: \.self) { level in
                        selectionCard(icon: level.icon, title: level.displayName, subtitle: level.subtitle, isSelected: activityLevel == level) {
                            withAnimation(.spring(response: 0.3)) { activityLevel = level }
                        }
                    }
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
            }
            continueButton()
        }
    }

    // MARK: - 6: Goal

    private var goalStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "What's your goal?", subtitle: "You can change this anytime")
            Spacer()
            VStack(spacing: 12) {
                ForEach(WeightGoal.allCases, id: \.self) { g in
                    selectionCard(icon: g.icon, title: g.displayName, isSelected: goal == g) {
                        withAnimation(.spring(response: 0.3)) { goal = g }
                    }
                }
            }
            .padding(.horizontal, 24)
            Spacer()
            continueButton {
                // Seed the desired-weight wheels from the current weight + a
                // direction-appropriate offset. Whole-number offsets (5/10) are
                // fine — the user can fine-tune the tenth wheel in the next step.
                let lbsDelta = goal == .lose ? -10 : (goal == .gain ? 10 : 0)
                let kgDelta  = goal == .lose ? -5  : (goal == .gain ? 5  : 0)
                let newLbsWhole = max(60, weightLbsWhole + lbsDelta)
                let newKgWhole  = max(30, weightKgWhole + kgDelta)
                targetWeightLbsWhole = newLbsWhole
                targetWeightLbsTenth = weightLbsTenth
                targetWeightKgWhole  = newKgWhole
                targetWeightKgTenth  = weightKgTenth
            }
        }
    }

    // MARK: - 7: Desired Weight

    private var weightUnit: String { isMetric ? "kg" : "lbs" }

    private var weightDiffKg: Double {
        let currentKg = isMetric ? weightKg : weightLbs * 0.453592
        let targetKg = isMetric ? targetWeightKg : targetWeightLbs * 0.453592
        return abs(targetKg - currentKg)
    }

    private var desiredWeightStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "What's your\ndesired weight?", subtitle: goal.displayName)
            Spacer()
            if isMetric {
                decimalWeightWheel(whole: $targetWeightKgWhole, tenth: $targetWeightKgTenth, range: 30...250, unit: "kg")
                    .frame(height: 150).padding(.horizontal, 24)
            } else {
                decimalWeightWheel(whole: $targetWeightLbsWhole, tenth: $targetWeightLbsTenth, range: 60...500, unit: "lbs")
                    .frame(height: 150).padding(.horizontal, 24)
            }
            Spacer()
            continueButton()
        }
    }

    /// Reusable iOS-26-style two-wheel decimal picker for body weight (whole +
    /// tenth + unit suffix). Keeps the wheel selections Int-tagged — Picker
    /// doesn't pair cleanly with Double tags — and the parent computes the
    /// combined Double via `combine(_:_:)`.
    private func decimalWeightWheel(whole: Binding<Int>, tenth: Binding<Int>, range: ClosedRange<Int>, unit: String) -> some View {
        HStack(spacing: 0) {
            Picker("whole", selection: whole) {
                ForEach(range, id: \.self) { n in Text("\(n)").tag(n) }
            }
            .pickerStyle(.wheel)
            .frame(maxWidth: .infinity)
            .clipped()

            Text(".")
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .offset(y: -1)
                .foregroundStyle(.secondary)

            Picker("tenth", selection: tenth) {
                ForEach(0...9, id: \.self) { n in Text("\(n)").tag(n) }
            }
            .pickerStyle(.wheel)
            .frame(width: 56)
            .clipped()

            Text(unit)
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
                .padding(.leading, 4)
        }
    }

    // MARK: - 8: Goal Speed

    private var weeklyChangeKg: Double {
        switch goalSpeed { case 0: 0.25; case 2: 1.0; default: 0.5 }
    }

    private var estimatedDays: Int {
        guard weightDiffKg > 0 else { return 0 }
        return Int(weightDiffKg / weeklyChangeKg * 7)
    }

    private var goalSpeedStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(
                title: goal == .maintain ? "Your pace" : "How fast do you want\nto reach your goal?",
                subtitle: goal == .maintain ? "We'll set a balanced plan" : "\(goal == .lose ? "Weight loss" : "Weight gain") speed per week"
            )
            if goal == .maintain {
                Spacer()
                VStack(spacing: 12) {
                    Image(systemName: "checkmark.seal.fill")
                        .font(.system(size: 48)).foregroundStyle(AppColors.protein)
                    Text("Balanced pace set")
                        .font(.system(.title3, design: .rounded, weight: .semibold))
                    Text("We'll keep your calories steady\nto maintain your current weight.")
                        .font(.system(.callout, design: .rounded)).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }.frame(maxWidth: .infinity)
                Spacer()
            } else {
                Spacer()
                VStack(spacing: 24) {
                    VStack(spacing: 4) {
                        Text(String(format: "%.1f %@", weeklyChangeKg * (isMetric ? 1 : 2.205), weightUnit))
                            .font(.system(size: 40, weight: .bold, design: .rounded))
                            .contentTransition(.numericText()).animation(.snappy, value: goalSpeed)
                        Text("per week").font(.system(.callout, design: .rounded)).foregroundStyle(.secondary)
                    }
                    HStack(spacing: 0) {
                        VStack(spacing: 6) {
                            Image(systemName: "tortoise.fill").font(.system(size: 24))
                                .foregroundStyle(goalSpeed == 0 ? AppColors.calorie : Color.secondary.opacity(0.4))
                            Text("Slow").font(.system(.caption, design: .rounded, weight: .medium))
                                .foregroundStyle(goalSpeed == 0 ? AppColors.calorie : .secondary)
                        }.frame(maxWidth: .infinity)
                        VStack(spacing: 6) {
                            Image(systemName: "hare.fill").font(.system(size: 24))
                                .foregroundStyle(goalSpeed == 1 ? AppColors.calorie : Color.secondary.opacity(0.4))
                            Text("Recommended").font(.system(.caption, design: .rounded, weight: .medium))
                                .foregroundStyle(goalSpeed == 1 ? AppColors.calorie : .secondary)
                        }.frame(maxWidth: .infinity)
                        VStack(spacing: 6) {
                            Image(systemName: "bolt.fill").font(.system(size: 24))
                                .foregroundStyle(goalSpeed == 2 ? AppColors.calorie : Color.secondary.opacity(0.4))
                            Text("Fast").font(.system(.caption, design: .rounded, weight: .medium))
                                .foregroundStyle(goalSpeed == 2 ? AppColors.calorie : .secondary)
                        }.frame(maxWidth: .infinity)
                    }.padding(.horizontal, 24)
                    Slider(value: Binding(
                        get: { Double(goalSpeed) },
                        set: { goalSpeed = Int($0.rounded()) }
                    ), in: 0...2, step: 1).tint(AppColors.calorie).padding(.horizontal, 40)
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 0) {
                            Text("You'll reach your goal in ")
                                .font(.system(.subheadline, design: .rounded, weight: .medium))
                            Text("\(estimatedDays) days")
                                .font(.system(.subheadline, design: .rounded, weight: .bold))
                                .foregroundStyle(AppColors.calorie)
                        }
                        Text(goalSpeed == 1 ? "The most balanced pace, motivating and sustainable."
                             : goalSpeed == 0 ? "Gentle and sustainable. Great for long-term habits."
                             : "Aggressive but doable. Requires strong discipline.")
                            .font(.system(.caption, design: .rounded)).foregroundStyle(.secondary)
                    }
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading)
                    .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 14))
                    .padding(.horizontal, 24)
                }
                Spacer()
            }
            continueButton { profile.save() }
        }
    }

    // MARK: - 9: Notifications

    @AppStorage("notificationsEnabled") private var notificationsEnabled = false

    private var notificationsStep: some View {
        VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 24) {
                Image(systemName: "bell.badge.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(AppColors.calorie)

                Text("Be reminded to\nlog meals")
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .multilineTextAlignment(.center)

                Text("Get gentle reminders at meal times\nso you never forget to track.")
                    .font(.system(.callout, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                VStack(spacing: 12) {
                    Text("Fud AI would like to send you Notifications")
                        .font(.system(.subheadline, design: .rounded, weight: .medium))
                        .multilineTextAlignment(.center)
                    Divider()
                    HStack {
                        Button {
                            notificationsEnabled = false
                            withAnimation(.snappy) { step += 1 }
                        } label: {
                            Text("Don't Allow")
                                .font(.system(.subheadline, design: .rounded, weight: .medium))
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity)
                        }
                        Divider().frame(height: 30)
                        Button {
                            Task {
                                let granted = await notificationManager.requestAuthorization()
                                notificationsEnabled = granted
                                if granted {
                                    notificationManager.scheduleMealReminders(
                                        breakfastEnabled: true, breakfastHour: 8, breakfastMinute: 0,
                                        lunchEnabled: true, lunchHour: 12, lunchMinute: 0,
                                        dinnerEnabled: true, dinnerHour: 19, dinnerMinute: 0
                                    )
                                }
                                withAnimation(.snappy) { step += 1 }
                            }
                        } label: {
                            Text("Allow")
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                .foregroundStyle(.primary)
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
                .padding(16)
                .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 24)
            }

            Spacer()

            Button {
                notificationsEnabled = false
                withAnimation(.snappy) { step += 1 }
            } label: {
                Text("Skip")
                    .font(.system(.body, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
            }
            .padding(.bottom, 36)
        }
    }

    // MARK: - 10: Apple Health

    private var appleHealthStep: some View {
        VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 20) {
                ZStack {
                    Circle()
                        .fill(Color.secondary.opacity(0.06))
                        .frame(width: 120, height: 120)

                    Image(systemName: "heart.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(
                            LinearGradient(colors: [.pink, .red], startPoint: .topLeading, endPoint: .bottomTrailing)
                        )
                }

                VStack(spacing: 8) {
                    Text("Connect to\nApple Health")
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .multilineTextAlignment(.center)

                    Text("Keep your nutrition and body\nmeasurements in sync automatically.")
                        .font(.system(.callout, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                // Feature list
                VStack(alignment: .leading, spacing: 12) {
                    healthFeatureRow(icon: "fork.knife", label: "Nutrition Data")
                    healthFeatureRow(icon: "scalemass.fill", label: "Weight Sync")
                    healthFeatureRow(icon: "figure.stand", label: "Body Measurements")
                }
                .padding(.horizontal, 40)
            }

            Spacer()

            VStack(spacing: 12) {
                Button {
                    Task {
                        let authorized = await healthKitManager.requestAuthorization()
                        if authorized {
                            UserDefaults.standard.set(true, forKey: "healthKitEnabled")

                            // Write current profile data to Health
                            let p = profile
                            healthKitManager.writeWeight(kg: p.weightKg, date: .now)
                            healthKitManager.writeHeight(cm: p.heightCm)
                            if let bf = p.bodyFatPercentage {
                                healthKitManager.writeBodyFat(fraction: bf)
                            }

                            // Read Health data back into profile
                            let measurements = await healthKitManager.fetchLatestBodyMeasurements()
                            if let dob = measurements.dob {
                                birthday = dob
                            }
                            if let sex = measurements.sex {
                                switch sex {
                                case .male: gender = .male
                                case .female: gender = .female
                                default: break
                                }
                            }
                        }
                        withAnimation(.snappy) { step += 1 }
                    }
                } label: {
                    Text("Connect")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                        .background(AppColors.calorie, in: RoundedRectangle(cornerRadius: 16))
                }
                .padding(.horizontal, 24)

                Button {
                    withAnimation(.snappy) { step += 1 }
                } label: {
                    Text("Skip")
                        .font(.system(.body, design: .rounded, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                .padding(.bottom, 20)
            }
        }
    }

    // MARK: - 11: AI Provider Setup

    private var aiProviderStep: some View {
        VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 20) {
                ZStack {
                    Circle()
                        .fill(.ultraThinMaterial)
                        .frame(width: 120, height: 120)

                    Image(systemName: "sparkles")
                        .font(.system(size: 48))
                        .foregroundStyle(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                        )
                }

                VStack(spacing: 8) {
                    Text("Bring Your Own AI")
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .multilineTextAlignment(.center)

                    Text("Fud AI needs an AI provider key to\nanalyze your food. You bring your own.")
                        .font(.system(.callout, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                // Recommended provider card (Liquid Glass)
                VStack(spacing: 12) {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(.ultraThinMaterial)
                                .frame(width: 44, height: 44)
                            Image(systemName: "star.fill")
                                .font(.system(size: 18))
                                .foregroundStyle(AppColors.calorie)
                        }

                        VStack(alignment: .leading, spacing: 2) {
                            Text("Recommended: Google Gemini")
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            Text("Free tier available, fast & accurate")
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }

                        Spacer(minLength: 0)
                    }
                    .padding(14)
                    .background(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(.ultraThinMaterial)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .strokeBorder(AppColors.calorie.opacity(0.25), lineWidth: 1)
                    )

                    // Steps
                    VStack(alignment: .leading, spacing: 10) {
                        aiSetupRow(number: "1", text: "Get a free key at aistudio.google.com/apikey")
                        aiSetupRow(number: "2", text: "Open Settings → AI Provider")
                        aiSetupRow(number: "3", text: "Paste your key — done")
                    }
                    .padding(14)
                    .background(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(.ultraThinMaterial)
                    )
                }
                .padding(.horizontal, 24)

                Text("8 providers supported. Your key stays\non this device, encrypted in iOS Keychain.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center)
            }

            Spacer()

            Button {
                withAnimation(.snappy) { step += 1 }
            } label: {
                Text("Got It")
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 54)
                    .background(
                        LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing),
                        in: RoundedRectangle(cornerRadius: 16)
                    )
                    .shadow(color: AppColors.calorie.opacity(0.3), radius: 8, y: 4)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 36)
        }
    }

    private func aiSetupRow(number: String, text: String) -> some View {
        HStack(spacing: 12) {
            Text(number)
                .font(.system(.caption, design: .rounded, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(AppColors.calorie, in: Circle())
            Text(text)
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.primary)
            Spacer(minLength: 0)
        }
    }

    // MARK: - 14: Review

    private var reviewStep: some View {
        VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 24) {
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(colors: [Color.pink.opacity(0.1), Color.yellow.opacity(0.1)], startPoint: .topLeading, endPoint: .bottomTrailing)
                        )
                        .frame(width: 160, height: 160)
                    Image(systemName: "star.fill")
                        .font(.system(size: 64))
                        .foregroundStyle(AppColors.calorie)
                }

                VStack(spacing: 8) {
                    Text("Enjoying fud so far?")
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .multilineTextAlignment(.center)
                    Text("A quick rating helps us grow\nand build more features for you!")
                        .font(.system(.callout, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }

            Spacer()

            Button {
                requestNativeReview()
                hasCompletedOnboarding = true
            } label: {
                Text("Rate fud")
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 54)
                    .background(
                        LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing),
                        in: RoundedRectangle(cornerRadius: 16)
                    )
                    .shadow(color: AppColors.calorie.opacity(0.3), radius: 8, y: 4)
            }
            .padding(.horizontal, 24)

            Button {
                hasCompletedOnboarding = true
            } label: {
                Text("Maybe Later")
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 12)
            .padding(.bottom, 36)
        }
    }

    // MARK: - 12: Building Plan

    private var buildingPlanStep: some View {
        BuildingPlanStepView(profile: profile) {
            withAnimation(.snappy) { step += 1 }
        }
    }

    // MARK: - 13: Plan Ready

    private var planCalories: Int { editedCalories ?? profile.dailyCalories }
    private var planProtein: Int { editedProtein ?? profile.proteinGoal }
    private var planFat: Int { editedFat ?? profile.fatGoal }
    private var planCarbs: Int { editedCarbs ?? profile.carbsGoal }

    private func initPlanValues() {
        if editedCalories == nil && editedProtein == nil && editedFat == nil && editedCarbs == nil {
            editedCalories = profile.dailyCalories
            editedProtein = profile.proteinGoal
            editedFat = profile.fatGoal
            editedCarbs = profile.carbsGoal
        }
    }

    private var planReadyStep: some View {
        VStack(spacing: 0) {
            stepHeader(title: "Your Plan", subtitle: "Tap any value to adjust")

            ScrollView {
                VStack(spacing: 20) {
                    // Calorie display - tappable
                    Button {
                        withAnimation(.snappy) {
                            editingField = editingField == .calories ? nil : .calories
                        }
                    } label: {
                        VStack(spacing: 4) {
                            Text("\(planCalories)")
                                .font(.system(size: 64, weight: .bold, design: .rounded))
                                .foregroundStyle(
                                    LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                                )
                                .contentTransition(.numericText())
                                .animation(.snappy, value: planCalories)
                            HStack(spacing: 4) {
                                Text("daily calories")
                                    .font(.system(.callout, design: .rounded, weight: .medium))
                                    .foregroundStyle(.secondary)
                                Image(systemName: "pencil.circle.fill")
                                    .font(.system(size: 14))
                                    .foregroundStyle(.tertiary)
                            }
                        }
                    }
                    .buttonStyle(.plain)

                    if editingField == .calories {
                        Picker("Calories", selection: Binding(
                            get: { planCalories },
                            set: { newCal in
                                editedCalories = newCal
                                editedCarbs = max(0, (newCal - planProtein * 4 - planFat * 9) / 4)
                            }
                        )) {
                            ForEach(Array(stride(from: 800, through: 5000, by: 10)), id: \.self) { cal in
                                Text("\(cal) cal").tag(cal)
                            }
                        }
                        .pickerStyle(.wheel)
                        .frame(height: 150)
                        .padding(.horizontal, 24)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    }

                    // Macro cards - tappable
                    HStack(spacing: 12) {
                        editableMacroCard(label: "Protein", value: planProtein, unit: "g", gradientColors: AppColors.proteinGradient, field: .protein)
                        editableMacroCard(label: "Carbs", value: planCarbs, unit: "g", gradientColors: AppColors.carbsGradient, field: .carbs)
                        editableMacroCard(label: "Fat", value: planFat, unit: "g", gradientColors: AppColors.fatGradient, field: .fat)
                    }
                    .padding(.horizontal, 24)

                    if editingField == .protein {
                        Picker("Protein", selection: Binding(
                            get: { planProtein },
                            set: { newProtein in
                                editedProtein = newProtein
                                editedCarbs = max(0, (planCalories - newProtein * 4 - planFat * 9) / 4)
                            }
                        )) {
                            ForEach(20...300, id: \.self) { g in Text("\(g) g").tag(g) }
                        }
                        .pickerStyle(.wheel)
                        .frame(height: 150)
                        .padding(.horizontal, 24)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    }

                    if editingField == .carbs {
                        Picker("Carbs", selection: Binding(
                            get: { planCarbs },
                            set: { newCarbs in
                                editedCarbs = newCarbs
                                editedCalories = newCarbs * 4 + planProtein * 4 + planFat * 9
                            }
                        )) {
                            ForEach(0...500, id: \.self) { g in Text("\(g) g").tag(g) }
                        }
                        .pickerStyle(.wheel)
                        .frame(height: 150)
                        .padding(.horizontal, 24)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    }

                    if editingField == .fat {
                        Picker("Fat", selection: Binding(
                            get: { planFat },
                            set: { newFat in
                                editedFat = newFat
                                editedCarbs = max(0, (planCalories - planProtein * 4 - newFat * 9) / 4)
                            }
                        )) {
                            ForEach(10...200, id: \.self) { g in Text("\(g) g").tag(g) }
                        }
                        .pickerStyle(.wheel)
                        .frame(height: 150)
                        .padding(.horizontal, 24)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    }

                    if planCalories < 1200 {
                        HStack(spacing: 10) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.orange)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Please consult with a doctor")
                                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                Text("The minimum recommendation is 1,200 calories per day.")
                                    .font(.system(.caption, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.orange.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 24)
                    }
                }
                .padding(.top, 16)
                .padding(.bottom, 100)
            }

            continueButton("Let's get started!") {
                var editedProfile = profile
                editedProfile.customCalories = editedCalories
                editedProfile.customProtein = editedProtein
                editedProfile.customFat = editedFat
                editedProfile.customCarbs = editedCarbs
                editedProfile.autoBalanceMacro = .carbs
                editedProfile.save()
            }
        }
        .onAppear { initPlanValues() }
    }

    private func editableMacroCard(label: String, value: Int, unit: String, gradientColors: [Color], field: EditableField) -> some View {
        Button {
            withAnimation(.snappy) {
                editingField = editingField == field ? nil : field
            }
        } label: {
            VStack(spacing: 6) {
                Text(label)
                    .font(.system(.caption, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
                HStack(spacing: 2) {
                    Text("\(value)")
                        .font(.system(.title2, design: .rounded, weight: .bold))
                        .foregroundStyle(
                            LinearGradient(colors: gradientColors, startPoint: .topLeading, endPoint: .bottomTrailing)
                        )
                        .contentTransition(.numericText())
                        .animation(.snappy, value: value)
                    Text(unit)
                        .font(.system(.caption, design: .rounded, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                Image(systemName: "pencil.circle.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .strokeBorder(editingField == field ? gradientColors.first ?? .clear : .clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
    }

    private func stepHeader(title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 28, weight: .bold, design: .rounded))
            if !subtitle.isEmpty {
                Text(subtitle).font(.system(.callout, design: .rounded)).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24).padding(.top, 24)
    }

    private func selectionCard(icon: String, title: String, subtitle: String? = nil, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon).font(.system(size: 22))
                    .foregroundStyle(isSelected ? Color.primary : .secondary).frame(width: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(.body, design: .rounded, weight: .semibold)).foregroundStyle(.primary)
                    if let subtitle {
                        Text(subtitle).font(.system(.caption, design: .rounded)).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle").font(.system(size: 22))
                    .foregroundStyle(isSelected ? Color.primary : Color.secondary.opacity(0.3))
            }
            .padding(16)
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(isSelected ? Color.primary : Color.clear, lineWidth: 2))
        }.buttonStyle(.plain)
    }

    private func healthFeatureRow(icon: String, label: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 18)).foregroundStyle(.secondary).frame(width: 28)
            Text(label).font(.system(.body, design: .rounded)).foregroundStyle(.primary)
        }
    }

    private func requestNativeReview() {
        if let scene = UIApplication.shared.connectedScenes
            .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene {
            AppStore.requestReview(in: scene)
        }
    }
}

// MARK: - Building Plan Step (enhanced with percentage + checklist)

struct BuildingPlanStepView: View {
    let profile: UserProfile
    let onComplete: () -> Void

    @State private var progress: Double = 0
    @State private var percent = 0
    @State private var checkItem = 0

    private let items = [
        ("Calories", "flame.fill"),
        ("Carbs", "leaf.fill"),
        ("Protein", "fish.fill"),
        ("Fats", "drop.fill"),
        ("Health Score", "heart.fill")
    ]

    var body: some View {
        VStack(spacing: 32) {
            Spacer()

            VStack(spacing: 8) {
                Text("\(percent)%")
                    .font(.system(size: 56, weight: .bold, design: .rounded))
                    .contentTransition(.numericText())
                    .animation(.easeInOut(duration: 0.3), value: percent)

                Text("We're setting everything\nup for you")
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .multilineTextAlignment(.center)
            }

            // Progress bar
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.primary.opacity(0.08))
                    Capsule()
                        .fill(
                            // Mono-pink to match the rest of the brand surface
                            // (macro rings, home + button, PlanReady calorie
                            // number) — earlier 3-stop gradient ended in blue
                            // and read as off-brand against the otherwise
                            // pink-only palette.
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                        )
                        .frame(width: geo.size.width * progress)
                        .animation(.easeInOut(duration: 0.4), value: progress)
                }
            }
            .frame(height: 10)
            .padding(.horizontal, 40)

            Text("Finalizing results...")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)

            // Checklist
            VStack(alignment: .leading, spacing: 14) {
                Text("Daily recommendation for")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                ForEach(0..<items.count, id: \.self) { index in
                    HStack(spacing: 10) {
                        Text("\u{2022}")
                            .foregroundStyle(.secondary)
                        Text(items[index].0)
                            .font(.system(.body, design: .rounded))
                        Spacer()
                        if index < checkItem {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 20))
                                .foregroundStyle(.primary)
                                .transition(.scale.combined(with: .opacity))
                        }
                    }
                    .animation(.spring(response: 0.4), value: checkItem)
                }
            }
            .padding(.horizontal, 40)

            Spacer()
        }
        .onAppear { startAnimation() }
    }

    private func startAnimation() {
        // 5 items over ~4 seconds
        for i in 0..<5 {
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 0.7) {
                withAnimation { checkItem = i + 1 }
                percent = [20, 40, 60, 80, 100][i]
                progress = Double(i + 1) / 5.0
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) {
            onComplete()
        }
    }
}
