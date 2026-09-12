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
    @AppStorage("heightUnit") private var heightUnitRaw = "ftin"
    @AppStorage("weightUnit") private var weightUnitRaw = "lbs"
    @AppStorage("aiAnalysisConsentGiven") private var aiConsentGiven = false
    @AppStorage("acceptedTermsAndPrivacy") private var acceptedTermsAndPrivacy = false
    // Segmented Imperial | Metric control state. Seeded from the split unit prefs
    // (Metric only when both are metric) and writes BOTH prefs coherently onChange.
    @State private var isMetric = UserDefaults.standard.string(forKey: "heightUnit") == "cm"
        && UserDefaults.standard.string(forKey: "weightUnit") == "kg"
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
    @State private var showCalculationSources = false
    @State private var hasAcceptedTerms = false
    // BYOK setup captured in onboarding (step 11) so AI is ready for the plan calc.
    @State private var byokProvider: AIProvider = AIProviderSettings.selectedProvider
    @State private var byokModel: String = AIProviderSettings.selectedModel
    @State private var byokApiKey: String = AIProviderSettings.currentAPIKey ?? ""
    @State private var byokBaseURL: String = AIProviderSettings.customBaseURL(for: AIProviderSettings.selectedProvider) ?? ""
    @State private var showByokKey = false
    /// AI-computed targets from the Building Plan step; seeds the Plan Ready screen.
    @State private var aiGoal: GeminiService.GoalCalculation?

    private enum EditableField: String, Identifiable {
        case calories, protein, fat, carbs
        var id: String { rawValue }
    }

    private let totalSteps = 14 // 0-13

    /// Combine the whole + tenth wheel selections into a single Double.
    private func combine(_ whole: Int, _ tenth: Int) -> Double { Double(whole) + Double(tenth) / 10.0 }

    private var weightKg: Double { combine(weightKgWhole, weightKgTenth) }
    private var weightLbs: Double { combine(weightLbsWhole, weightLbsTenth) }
    private var targetWeightKg: Double { combine(targetWeightKgWhole, targetWeightKgTenth) }
    private var targetWeightLbs: Double { combine(targetWeightLbsWhole, targetWeightLbsTenth) }

    private var isHeightMetric: Bool { heightUnitRaw == "cm" }
    private var isWeightMetric: Bool { weightUnitRaw == "kg" }

    private var profile: UserProfile {
        let cm: Double = isHeightMetric
            ? Double(heightCm)
            : Double(heightFeet) * 30.48 + Double(heightInches) * 2.54
        let kg: Double = isWeightMetric ? weightKg : weightLbs * 0.453592
        let targetKg: Double? = goal == .maintain ? nil : (isWeightMetric ? targetWeightKg : targetWeightLbs * 0.453592)
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

                // Quick feature tour — everything is free and already unlocked.
                VStack(alignment: .leading, spacing: 12) {
                    welcomeFeatureRow(icon: "camera.fill", text: "Snap a photo — AI logs it")
                    welcomeFeatureRow(icon: "bubble.left.and.bubble.right.fill", text: "Coach that knows your data")
                    welcomeFeatureRow(icon: "dumbbell.fill", text: "870+ exercise library")
                    welcomeFeatureRow(icon: "applewatch", text: "Widgets & Apple Watch")
                }
                .padding(.top, 8)
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
            .onChange(of: isMetric) { _, newValue in
                heightUnitRaw = newValue ? "cm" : "ftin"
                weightUnitRaw = newValue ? "kg" : "lbs"
            }
            Spacer()
            // Stack height + weight as two rows so the weight picker (whole +
            // "." + tenth + unit = 4 sub-cells) gets the full screen width
            // instead of competing with feet/inches for one-third of it. The
            // 3-column imperial layout used to render the lbs whole-number
            // wheel as "..." because there wasn't enough width for 3-digit
            // values like 152 alongside the decimal column.
            // Each wheel reads its own split unit pref, so mixed configurations
            // (e.g. ft/in + kg) render correctly when re-entering onboarding.
            VStack(spacing: 8) {
                if isHeightMetric {
                    VStack(spacing: 4) {
                        Text("Height").font(.system(.caption, design: .rounded, weight: .medium)).foregroundStyle(.secondary)
                        Picker("cm", selection: $heightCm) {
                            ForEach(100...250, id: \.self) { cm in Text("\(cm) cm").tag(cm) }
                        }.pickerStyle(.wheel).frame(height: 130)
                    }
                } else {
                    HStack(spacing: 8) {
                        VStack(spacing: 4) {
                            Text("Feet").font(.system(.caption, design: .rounded, weight: .medium)).foregroundStyle(.secondary)
                            Picker("ft", selection: $heightFeet) {
                                ForEach(3...8, id: \.self) { ft in Text("\(ft) ft").tag(ft) }
                            }.pickerStyle(.wheel).frame(height: 130)
                        }
                        VStack(spacing: 4) {
                            Text("Inches").font(.system(.caption, design: .rounded, weight: .medium)).foregroundStyle(.secondary)
                            Picker("in", selection: $heightInches) {
                                ForEach(0...11, id: \.self) { inch in Text("\(inch) in").tag(inch) }
                            }.pickerStyle(.wheel).frame(height: 130)
                        }
                    }
                }
                VStack(spacing: 4) {
                    Text(LocalizedDisplayText.text("Weight")).font(.system(.caption, design: .rounded, weight: .medium)).foregroundStyle(.secondary)
                    if isWeightMetric {
                        decimalWeightWheel(whole: $weightKgWhole, tenth: $weightKgTenth, range: 30...250, unit: "kg")
                            .frame(height: 130)
                    } else {
                        decimalWeightWheel(whole: $weightLbsWhole, tenth: $weightLbsTenth, range: 60...500, unit: "lbs")
                            .frame(height: 130)
                    }
                }
            }.padding(.horizontal, 24)
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
            stepHeader(
                title: "How active are you?",
                subtitle: LocalizedDisplayText.text(
                    "Choose based on your average week, including work and exercise.",
                    polish: "Wybierz na podstawie typowego tygodnia, uwzględniając pracę i ćwiczenia."
                )
            )
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

    private var weightUnit: String { isWeightMetric ? "kg" : "lbs" }

    private var weightDiffKg: Double {
        let currentKg = isWeightMetric ? weightKg : weightLbs * 0.453592
        let targetKg = isWeightMetric ? targetWeightKg : targetWeightLbs * 0.453592
        return abs(targetKg - currentKg)
    }

    private var desiredWeightStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "What's your\ndesired weight?", subtitle: goal.displayName)
            Spacer()
            if isWeightMetric {
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
                        Text("\(WeightDisplayFormatter.weeklyChangeValue(kilograms: weeklyChangeKg, useMetric: isWeightMetric)) \(weightUnit)")
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

                    Text("Keep your nutrition, steps, and body\nmeasurements in sync automatically.")
                        .font(.system(.callout, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                // Feature list
                VStack(alignment: .leading, spacing: 12) {
                    healthFeatureRow(icon: "fork.knife", label: "Nutrition Data")
                    healthFeatureRow(icon: "scalemass.fill", label: "Weight Sync")
                    healthFeatureRow(icon: "figure.stand", label: "Body Measurements")
                    healthFeatureRow(icon: "figure.walk", label: "Daily Steps")
                    healthFeatureRow(icon: "applewatch", label: "Apple Watch Workouts")
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
                    Text("Continue")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                        .background(AppColors.calorie, in: RoundedRectangle(cornerRadius: 16))
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 20)
            }
        }
    }

    // MARK: - 11: AI Provider Setup

    private var aiProviderStep: some View {
        VStack(spacing: 0) {
            ScrollView(showsIndicators: false) {
                VStack(spacing: 18) {
                    ZStack {
                        Circle()
                            .fill(.ultraThinMaterial)
                            .frame(width: 104, height: 104)

                        Image(systemName: "sparkles")
                            .font(.system(size: 42))
                            .foregroundStyle(
                                LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                            )
                    }

                    VStack(spacing: 8) {
                        Text("Set Up Your AI")
                            .font(.system(size: 28, weight: .bold, design: .rounded))
                            .multilineTextAlignment(.center)

                        Text("Add your own AI provider key — Gemini, OpenAI, Groq, and more are supported. The app stays free.")
                            .font(.system(.callout, design: .rounded))
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }

                    byokConfigSection
                        .padding(.horizontal, 24)

                    VStack(alignment: .leading, spacing: 12) {
                        aiNoticeRow(
                            icon: "photo.fill",
                            title: "AI analysis",
                            text: "Food photos, voice transcripts, and typed meals are sent directly to your selected AI provider."
                        )
                        aiNoticeRow(
                            icon: "lock.shield.fill",
                            title: "Local data",
                            text: "Your food log, weight history, body-fat history, and BYOK API keys stay on this device."
                        )
                    }
                    .padding(16)
                    .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))
                    .padding(.horizontal, 24)

                    VStack(alignment: .leading, spacing: 12) {
                        Button {
                            hasAcceptedTerms.toggle()
                        } label: {
                            HStack(alignment: .top, spacing: 10) {
                                Image(systemName: hasAcceptedTerms ? "checkmark.square.fill" : "square")
                                    .font(.system(size: 22, weight: .semibold))
                                    .foregroundStyle(hasAcceptedTerms ? AppColors.calorie : .secondary)
                                    .frame(width: 26, height: 26)

                                Text("I accept the Terms of Service and Privacy Policy, including AI provider data sharing described above.")
                                    .font(.system(.footnote, design: .rounded, weight: .medium))
                                    .foregroundStyle(.primary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .buttonStyle(.plain)

                        HStack(spacing: 6) {
                            Link("Privacy Policy", destination: URL(string: "https://fud-ai.app/privacy.html")!)
                            Text("and")
                                .foregroundStyle(.secondary)
                            Link("Terms of Service", destination: URL(string: "https://fud-ai.app/terms.html")!)
                        }
                        .font(.system(.footnote, design: .rounded, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)
                    }
                    .padding(16)
                    .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))
                    .padding(.horizontal, 24)
                }
                .padding(.top, 24)
                .padding(.bottom, 20)
            }

            Button {
                completeAIChoiceAndAdvance()
            } label: {
                Text("Accept & Continue")
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
            .disabled(!canAdvanceAI)
            .opacity(canAdvanceAI ? 1 : 0.45)
            .padding(.horizontal, 24)
            .padding(.bottom, 36)
        }
    }

    /// Step 11 can advance when terms are accepted AND a usable AI provider is set up:
    /// a model + key (+ base URL for custom endpoints).
    private var canAdvanceAI: Bool {
        guard hasAcceptedTerms else { return false }
        let modelOK = !byokModel.trimmingCharacters(in: .whitespaces).isEmpty
        let keyOK = !byokProvider.requiresAPIKey || !byokApiKey.trimmingCharacters(in: .whitespaces).isEmpty
        let urlOK = !byokProvider.requiresCustomEndpoint || !byokBaseURL.trimmingCharacters(in: .whitespaces).isEmpty
        return modelOK && keyOK && urlOK
    }

    @ViewBuilder
    private var byokConfigSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Provider
            HStack {
                Label { Text("Provider") } icon: {
                    Image(systemName: "cpu").foregroundStyle(AppColors.calorie)
                }
                Spacer()
                Picker("", selection: $byokProvider) {
                    ForEach(AIProvider.visionProviders) { provider in
                        Text(provider.displayName).tag(provider)
                    }
                }
                .labelsHidden()
                .pickerStyle(.menu)
                .tint(.secondary)
                .onChange(of: byokProvider) { _, newProvider in
                    AIProviderSettings.selectedProvider = newProvider
                    byokModel = newProvider.defaultModel
                    AIProviderSettings.selectedModel = newProvider.defaultModel
                    byokApiKey = AIProviderSettings.apiKey(for: newProvider) ?? ""
                    byokBaseURL = AIProviderSettings.customBaseURL(for: newProvider) ?? ""
                }
            }

            Divider()

            // Model
            HStack {
                Label { Text("Model") } icon: {
                    Image(systemName: "brain").foregroundStyle(AppColors.calorie)
                }
                Spacer()
                if byokProvider.supportsCustomModelName {
                    TextField("e.g. gpt-4o-mini", text: $byokModel)
                        .textFieldStyle(.plain)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: byokModel) { _, m in AIProviderSettings.selectedModel = m }
                    if !byokProvider.models.isEmpty {
                        Menu {
                            ForEach(byokProvider.models, id: \.self) { model in
                                Button(model) { byokModel = model; AIProviderSettings.selectedModel = model }
                            }
                        } label: {
                            Image(systemName: "list.bullet.circle").foregroundStyle(AppColors.calorie)
                        }
                    }
                } else {
                    Picker("", selection: $byokModel) {
                        ForEach(byokProvider.models, id: \.self) { model in Text(model).tag(model) }
                    }
                    .labelsHidden()
                    .pickerStyle(.menu)
                    .tint(.secondary)
                    .onChange(of: byokModel) { _, m in AIProviderSettings.selectedModel = m }
                }
            }

            // API Key
            if byokProvider.requiresAPIKey {
                Divider()
                HStack {
                    Label { Text("API Key") } icon: {
                        Image(systemName: "key.fill").foregroundStyle(AppColors.calorie)
                    }
                    Spacer()
                    Group {
                        if showByokKey {
                            TextField(byokProvider.apiKeyPlaceholder, text: $byokApiKey)
                        } else {
                            SecureField(byokProvider.apiKeyPlaceholder, text: $byokApiKey)
                        }
                    }
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: byokApiKey) { _, k in
                        let t = k.trimmingCharacters(in: .whitespacesAndNewlines)
                        AIProviderSettings.setAPIKey(t.isEmpty ? nil : t, for: byokProvider)
                    }
                    Button { showByokKey.toggle() } label: {
                        Image(systemName: showByokKey ? "eye.fill" : "eye.slash.fill")
                            .foregroundStyle(.secondary).font(.system(size: 14))
                    }
                    .buttonStyle(.plain)
                }
            }

            // Base / Server URL
            if byokProvider == .ollama || byokProvider.requiresCustomEndpoint {
                Divider()
                HStack {
                    Label { Text(byokProvider.requiresCustomEndpoint ? "Base URL" : "Server URL") } icon: {
                        Image(systemName: "link").foregroundStyle(AppColors.calorie)
                    }
                    Spacer()
                    TextField(
                        byokProvider.requiresCustomEndpoint ? "https://your-endpoint.com/v1" : byokProvider.baseURL,
                        text: $byokBaseURL
                    )
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .onChange(of: byokBaseURL) { _, u in
                        let t = u.trimmingCharacters(in: .whitespacesAndNewlines)
                        AIProviderSettings.setCustomBaseURL(t.isEmpty ? nil : t, for: byokProvider)
                    }
                }
            }
        }
        .padding(16)
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))
    }

    private func completeAIChoiceAndAdvance() {
        aiConsentGiven = true
        acceptedTermsAndPrivacy = true
        withAnimation(.snappy) { step += 1 }
    }

    private func aiNoticeRow(icon: String, title: String, text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(AppColors.calorie)
                .frame(width: 24, height: 24)

            VStack(alignment: .leading, spacing: 3) {
                Text(LocalizedDisplayText.text(title))
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Text(LocalizedDisplayText.text(text))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
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

    // MARK: - 12: Building Plan

    private var buildingPlanStep: some View {
        BuildingPlanStepView(profile: profile, heightMetric: isHeightMetric, weightMetric: isWeightMetric) { result in
            aiGoal = result
            withAnimation(.snappy) { step += 1 }
        }
    }

    // MARK: - 13: Plan Ready

    private var planCalories: Int { editedCalories ?? profile.dailyCalories }
    private var planProtein: Int { editedProtein ?? profile.proteinGoal }
    private var planFat: Int { editedFat ?? profile.fatGoal }
    private var planCarbs: Int { editedCarbs ?? profile.carbsGoal }

    private func initPlanValues() {
        guard editedCalories == nil && editedProtein == nil && editedFat == nil && editedCarbs == nil else { return }
        if let g = aiGoal {
            // AI-computed plan (carbs derived as the residual to stay consistent with calories).
            editedCalories = g.calories
            editedProtein = g.protein
            editedFat = g.fat
            editedCarbs = max(0, (g.calories - g.protein * 4 - g.fat * 9) / 4)
        } else {
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
                    // Adaptive Goals is on by default for new installs — say so up front.
                    Text("Your plan auto-adjusts weekly as you log — turn off Adaptive Goals in Settings to keep it fixed.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)

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
                        Picker(LocalizedDisplayText.text("Calories"), selection: Binding(
                            get: { planCalories },
                            set: { newCal in
                                editedCalories = newCal
                                editedCarbs = max(0, (newCal - planProtein * 4 - planFat * 9) / 4)
                                markPlanEdited()
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
                        Picker(LocalizedDisplayText.text("Protein"), selection: Binding(
                            get: { planProtein },
                            set: { newProtein in
                                editedProtein = newProtein
                                editedCarbs = max(0, (planCalories - newProtein * 4 - planFat * 9) / 4)
                                markPlanEdited()
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
                        Picker(LocalizedDisplayText.text("Carbs"), selection: Binding(
                            get: { planCarbs },
                            set: { newCarbs in
                                editedCarbs = newCarbs
                                editedCalories = newCarbs * 4 + planProtein * 4 + planFat * 9
                                markPlanEdited()
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
                        Picker(LocalizedDisplayText.text("Fat"), selection: Binding(
                            get: { planFat },
                            set: { newFat in
                                editedFat = newFat
                                editedCarbs = max(0, (planCalories - planProtein * 4 - newFat * 9) / 4)
                                markPlanEdited()
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
                    // Citations link (Apple Guideline 1.4.1 — medical info needs sources)
                    Button {
                        showCalculationSources = true
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "book.fill")
                                .font(.system(size: 11))
                            Text("How is this calculated?")
                                .font(.system(.footnote, design: .rounded, weight: .medium))
                            Image(systemName: "chevron.right")
                                .font(.system(size: 10, weight: .semibold))
                        }
                        .foregroundStyle(AppColors.calorie)
                    }
                    .padding(.top, 8)
                    .padding(.horizontal, 24)
                }
                .padding(.top, 16)
                .padding(.bottom, 100)
            }

            // Final step — save the plan and enter the app directly (the old
            // post-plan rating screen was removed; onboarding rating pressure
            // is App Review rejection bait).
            Button {
                var editedProfile = profile
                editedProfile.customCalories = editedCalories
                editedProfile.customProtein = editedProtein
                editedProfile.customFat = editedFat
                editedProfile.customCarbs = editedCarbs
                editedProfile.autoBalanceMacro = .carbs
                editedProfile.save()
                SpeechSettings.setInitialProvider(matching: byokProvider)
                // Seed only new users; keep the legacy fallback and saved choices intact.
                if !hasCompletedOnboarding,
                   UserDefaults.standard.object(forKey: FoodLogSortOrder.storageKey) == nil {
                    UserDefaults.standard.set(
                        FoodLogSortOrder.latestMealsFirst.rawValue,
                        forKey: FoodLogSortOrder.storageKey
                    )
                }
                hasCompletedOnboarding = true
            } label: {
                Text("Let's get started!")
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .foregroundStyle(Color(.systemBackground))
                    .frame(maxWidth: .infinity)
                    .frame(height: 54)
                    .background(Color.primary, in: Capsule())
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 36)
        }
        .onAppear { initPlanValues() }
        .sheet(isPresented: $showCalculationSources) {
            CalculationMethodsView()
        }
    }

    private func editableMacroCard(label: String, value: Int, unit: String, gradientColors: [Color], field: EditableField) -> some View {
        Button {
            withAnimation(.snappy) {
                editingField = editingField == field ? nil : field
            }
        } label: {
            VStack(spacing: 6) {
                Text(LocalizedDisplayText.text(label))
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
            Text(LocalizedDisplayText.text(label)).font(.system(.body, design: .rounded)).foregroundStyle(.primary)
        }
    }

    /// The user hand-tuned their plan — remembered so Adaptive Goals is NOT enabled
    /// by default at completion (its weekly run would overwrite these numbers).
    private func markPlanEdited() {
        UserDefaults.standard.set(true, forKey: "onboardingPlanEdited")
    }

    private func welcomeFeatureRow(icon: String, text: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AppColors.calorie)
                .frame(width: 26)
            Text(LocalizedDisplayText.text(text))
                .font(.system(.subheadline, design: .rounded, weight: .medium))
        }
    }
}

// MARK: - Building Plan Step (enhanced with percentage + checklist)

struct BuildingPlanStepView: View {
    let profile: UserProfile
    let heightMetric: Bool
    let weightMetric: Bool
    let onComplete: (GeminiService.GoalCalculation?) -> Void

    @State private var progress: Double = 0
    @State private var percent = 0
    @State private var checkItem = 0
    @State private var aiResult: GeminiService.GoalCalculation?
    @State private var aiDone = false
    @State private var animationDone = false

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
        .onAppear {
            startAnimation()
            startAICalc()
        }
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
            animationDone = true
            finishIfReady()
        }
    }

    private func startAICalc() {
        Task {
            // New user → no logs yet, so forecast is nil; AI computes from profile + formulas.
            let result = try? await GeminiService.calculateGoals(
                profile: profile,
                heightMetric: heightMetric,
                weightMetric: weightMetric,
                countTowardHostedQuota: false
            )
            await MainActor.run {
                aiResult = result
                aiDone = true
                finishIfReady()
            }
        }
    }

    /// Advance only once BOTH the animation and the AI call have finished, so the plan reflects
    /// the AI targets (or the formula fallback when the call returns nil).
    private func finishIfReady() {
        guard animationDone, aiDone else { return }
        onComplete(aiResult)
    }
}
