import SwiftUI

struct OnboardingView: View {
    @Binding var hasCompletedOnboarding: Bool

    @State private var step = 0
    @State private var gender: Gender = .male
    @State private var birthday: Date = Calendar.current.date(byAdding: .year, value: -25, to: Date()) ?? Date()
    @State private var isMetric = false
    @State private var heightFeet = 5
    @State private var heightInches = 9
    @State private var heightCm = 175
    @State private var weightLbs = 154
    @State private var weightKg = 70
    @State private var activityLevel: ActivityLevel = .moderate
    @State private var goal: WeightGoal = .maintain
    @State private var selectedPlan: PaywallPlan = .yearly
    @State private var referralSource: String?

    private let totalSteps = 10 // 0-9

    private var profile: UserProfile {
        let cm: Double
        let kg: Double
        if isMetric {
            cm = Double(heightCm)
            kg = Double(weightKg)
        } else {
            cm = Double(heightFeet) * 30.48 + Double(heightInches) * 2.54
            kg = Double(weightLbs) * 0.453592
        }
        return UserProfile(
            gender: gender,
            birthday: birthday,
            heightCm: cm,
            weightKg: kg,
            activityLevel: activityLevel,
            goal: goal
        )
    }

    var body: some View {
        ZStack {
            AppColors.appBackground
                .ignoresSafeArea()

            VStack(spacing: 0) {
                // Top bar: back + progress
                if step > 0 && step < 9 {
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

                // Step content
                ZStack {
                    switch step {
                    case 0: welcomeStep
                    case 1: genderStep
                    case 2: birthdayStep
                    case 3: heightWeightStep
                    case 4: activityStep
                    case 5: goalStep
                    case 6: referralStep
                    case 7: buildingPlanStep
                    case 8: planReadyStep
                    case 9: paywallStep
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
    }

    // MARK: - Continue Button

    private func continueButton(_ title: String = "Continue", action: @escaping () -> Void = {}) -> some View {
        Button {
            action()
            withAnimation(.snappy) { step += 1 }
        } label: {
            Text(title)
                .font(.system(.body, design: .rounded, weight: .semibold))
                .foregroundStyle(AppColors.appBackground)
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background(Color.primary, in: Capsule())
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 36)
    }

    // MARK: - Step 0: Welcome

    private var welcomeStep: some View {
        VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 20) {
                Image("onboardingLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 120, height: 120)

                VStack(spacing: 8) {
                    Text("Calorie Tracking")
                        .font(.system(size: 32, weight: .bold, design: .rounded))

                    Text("Made Easy")
                        .font(.system(size: 32, weight: .bold, design: .rounded))
                        .foregroundStyle(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                        )
                }

                Text("Snap a photo, get the nutrition.\nNo searching. No guessing.")
                    .font(.system(.callout, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            Spacer()

            continueButton("Get Started")
        }
    }

    // MARK: - Step 1: Gender

    private var genderStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "What's your gender?", subtitle: "This helps us calculate your metabolism")

            Spacer()

            VStack(spacing: 12) {
                ForEach(Gender.allCases, id: \.self) { g in
                    selectionCard(
                        icon: g.icon,
                        title: g.displayName,
                        isSelected: gender == g
                    ) {
                        withAnimation(.spring(response: 0.3)) { gender = g }
                    }
                }
            }
            .padding(.horizontal, 24)

            Spacer()

            continueButton()
        }
    }

    // MARK: - Step 2: Birthday

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

    // MARK: - Step 3: Height & Weight

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

            Spacer()

            if isMetric {
                HStack(spacing: 0) {
                    VStack(spacing: 4) {
                        Text("Height")
                            .font(.system(.caption, design: .rounded, weight: .medium))
                            .foregroundStyle(.secondary)
                        Picker("cm", selection: $heightCm) {
                            ForEach(100...250, id: \.self) { cm in
                                Text("\(cm) cm").tag(cm)
                            }
                        }
                        .pickerStyle(.wheel)
                    }

                    VStack(spacing: 4) {
                        Text("Weight")
                            .font(.system(.caption, design: .rounded, weight: .medium))
                            .foregroundStyle(.secondary)
                        Picker("kg", selection: $weightKg) {
                            ForEach(30...250, id: \.self) { kg in
                                Text("\(kg) kg").tag(kg)
                            }
                        }
                        .pickerStyle(.wheel)
                    }
                }
                .padding(.horizontal, 24)
            } else {
                HStack(spacing: 0) {
                    VStack(spacing: 4) {
                        Text("Feet")
                            .font(.system(.caption, design: .rounded, weight: .medium))
                            .foregroundStyle(.secondary)
                        Picker("ft", selection: $heightFeet) {
                            ForEach(3...8, id: \.self) { ft in
                                Text("\(ft) ft").tag(ft)
                            }
                        }
                        .pickerStyle(.wheel)
                    }

                    VStack(spacing: 4) {
                        Text("Inches")
                            .font(.system(.caption, design: .rounded, weight: .medium))
                            .foregroundStyle(.secondary)
                        Picker("in", selection: $heightInches) {
                            ForEach(0...11, id: \.self) { inch in
                                Text("\(inch) in").tag(inch)
                            }
                        }
                        .pickerStyle(.wheel)
                    }

                    VStack(spacing: 4) {
                        Text("Weight")
                            .font(.system(.caption, design: .rounded, weight: .medium))
                            .foregroundStyle(.secondary)
                        Picker("lbs", selection: $weightLbs) {
                            ForEach(60...500, id: \.self) { lb in
                                Text("\(lb) lbs").tag(lb)
                            }
                        }
                        .pickerStyle(.wheel)
                    }
                }
                .padding(.horizontal, 24)
            }

            Spacer()

            continueButton()
        }
    }

    // MARK: - Step 4: Activity Level

    private var activityStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "How active are you?", subtitle: "Your typical week")

            Spacer()

            VStack(spacing: 12) {
                ForEach(ActivityLevel.allCases, id: \.self) { level in
                    selectionCard(
                        icon: level.icon,
                        title: level.displayName,
                        subtitle: level.subtitle,
                        isSelected: activityLevel == level
                    ) {
                        withAnimation(.spring(response: 0.3)) { activityLevel = level }
                    }
                }
            }
            .padding(.horizontal, 24)

            Spacer()

            continueButton()
        }
    }

    // MARK: - Step 5: Goal

    private var goalStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "What's your goal?", subtitle: "You can change this anytime")

            Spacer()

            VStack(spacing: 12) {
                ForEach(WeightGoal.allCases, id: \.self) { g in
                    selectionCard(
                        icon: g.icon,
                        title: g.displayName,
                        isSelected: goal == g
                    ) {
                        withAnimation(.spring(response: 0.3)) { goal = g }
                    }
                }
            }
            .padding(.horizontal, 24)

            Spacer()

            continueButton {
                profile.save()
            }
        }
    }

    // MARK: - Step 6: Referral Source

    private let referralOptions: [(icon: String, label: String)] = [
        ("bubble.left.and.bubble.right.fill", "Social Media"),
        ("magnifyingglass", "Search Engine"),
        ("person.2.fill", "Friend or Family"),
        ("app.badge.fill", "App Store"),
        ("play.rectangle.fill", "YouTube"),
        ("ellipsis.circle.fill", "Other")
    ]

    private var referralStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            stepHeader(title: "How did you\nfind us?", subtitle: "This helps us grow")

            Spacer()

            VStack(spacing: 10) {
                ForEach(referralOptions, id: \.label) { option in
                    Button {
                        withAnimation(.spring(response: 0.3)) { referralSource = option.label }
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: option.icon)
                                .font(.system(size: 18))
                                .foregroundStyle(referralSource == option.label ? Color.primary : .secondary)
                                .frame(width: 36)

                            Text(option.label)
                                .font(.system(.body, design: .rounded, weight: .medium))
                                .foregroundStyle(.primary)

                            Spacer()
                        }
                        .padding(14)
                        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 14))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .strokeBorder(referralSource == option.label ? Color.primary : Color.clear, lineWidth: 2)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 24)

            Spacer()

            continueButton()
        }
    }

    // MARK: - Step 7: Building Plan

    private var buildingPlanStep: some View {
        BuildingPlanStepView {
            withAnimation(.snappy) { step += 1 }
        }
    }

    // MARK: - Step 7: Plan Ready

    private var planReadyStep: some View {
        VStack(spacing: 0) {
            stepHeader(title: "Your Plan", subtitle: "Based on your profile")

            Spacer()

            VStack(spacing: 24) {
                // Big calorie number
                VStack(spacing: 4) {
                    Text("\(profile.dailyCalories)")
                        .font(.system(size: 64, weight: .bold, design: .rounded))
                        .foregroundStyle(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                        )

                    Text("daily calories")
                        .font(.system(.callout, design: .rounded, weight: .medium))
                        .foregroundStyle(.secondary)
                }

                // Macro bars (matching home page style)
                HStack(spacing: 20) {
                    MacroCard(label: "Protein", current: profile.proteinGoal, goal: profile.proteinGoal, gradientColors: AppColors.proteinGradient)
                    MacroCard(label: "Carbs", current: profile.carbsGoal, goal: profile.carbsGoal, gradientColors: AppColors.carbsGradient)
                    MacroCard(label: "Fat", current: profile.fatGoal, goal: profile.fatGoal, gradientColors: AppColors.fatGradient)
                }
                .padding(.horizontal, 24)
            }

            Spacer()

            continueButton("Let's get started!")
        }
    }

    // MARK: - Step 8: Paywall

    private var paywallStep: some View {
        VStack(spacing: 0) {
            // X dismiss button
            HStack {
                Spacer()
                Button {
                    hasCompletedOnboarding = true
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .background(Color.primary.opacity(0.06), in: Circle())
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)

            Spacer()

            VStack(spacing: 8) {
                Image(systemName: "star.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(
                        LinearGradient(colors: [.yellow, .orange], startPoint: .topLeading, endPoint: .bottomTrailing)
                    )

                Text("Unlock Premium")
                    .font(.system(size: 28, weight: .bold, design: .rounded))

                Text("Unlimited scans, detailed insights,\nand personalized plans")
                    .font(.system(.callout, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            Spacer()

            // Plan cards
            VStack(spacing: 12) {
                paywallPlanCard(
                    plan: .yearly,
                    title: "Yearly",
                    price: formatPrice(39.99),
                    detail: "\(formatPrice(3.33))/mo",
                    badge: "Best Value"
                )

                paywallPlanCard(
                    plan: .weekly,
                    title: "Weekly",
                    price: formatPrice(4.99),
                    detail: "per week",
                    badge: nil
                )
            }
            .padding(.horizontal, 24)

            Spacer()

            // CTA
            Button {
                hasCompletedOnboarding = true
            } label: {
                Text("Start Free Trial")
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.appBackground)
                    .frame(maxWidth: .infinity)
                    .frame(height: 54)
                    .background(Color.primary, in: Capsule())
            }
            .padding(.horizontal, 24)

            // Restore + footer
            VStack(spacing: 8) {
                Button("Restore Purchases") {
                    hasCompletedOnboarding = true
                }
                .font(.system(.footnote, design: .rounded, weight: .medium))
                .foregroundStyle(.secondary)

                Text("No Commitment \u{2022} Cancel Anytime")
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.tertiary)
            }
            .padding(.top, 12)
            .padding(.bottom, 36)
        }
    }

    // MARK: - Helpers

    private func stepHeader(title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 28, weight: .bold, design: .rounded))

            Text(subtitle)
                .font(.system(.callout, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
        .padding(.top, 24)
    }

    private func selectionCard(icon: String, title: String, subtitle: String? = nil, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.system(size: 22))
                    .foregroundStyle(isSelected ? Color.primary : .secondary)
                    .frame(width: 40)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(.primary)

                    if let subtitle {
                        Text(subtitle)
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(isSelected ? Color.primary : Color.secondary.opacity(0.3))
            }
            .padding(16)
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .strokeBorder(isSelected ? Color.primary : Color.clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
    }

    private func formatPrice(_ amount: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = Locale.current
        return formatter.string(from: NSNumber(value: amount)) ?? "$\(String(format: "%.2f", amount))"
    }

    private func paywallPlanCard(plan: PaywallPlan, title: String, price: String, detail: String, badge: String?) -> some View {
        Button {
            withAnimation(.spring(response: 0.3)) { selectedPlan = plan }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    if let badge {
                        Text(badge)
                            .font(.system(.caption2, design: .rounded, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(
                                LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing),
                                in: Capsule()
                            )
                    }

                    Text(title)
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(.primary)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 2) {
                    Text(price)
                        .font(.system(.body, design: .rounded, weight: .bold))
                        .foregroundStyle(.primary)

                    Text(detail)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                Image(systemName: selectedPlan == plan ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(selectedPlan == plan ? Color.primary : Color.secondary.opacity(0.3))
                    .padding(.leading, 8)
            }
            .padding(16)
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .strokeBorder(selectedPlan == plan ? Color.primary : Color.clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Paywall Plan Enum

enum PaywallPlan {
    case yearly, weekly
}

// MARK: - Building Plan Step (separate view for timer)

struct BuildingPlanStepView: View {
    let onComplete: () -> Void

    @State private var progress: Double = 0
    @State private var checkItem = 0

    private let items = [
        "Calculating BMR...",
        "Setting macro targets...",
        "Personalizing plan..."
    ]

    var body: some View {
        VStack(spacing: 40) {
            Spacer()

            VStack(spacing: 24) {
                Text("Building Your Plan")
                    .font(.system(size: 28, weight: .bold, design: .rounded))

                // Progress bar
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color.primary.opacity(0.08))

                        Capsule()
                            .fill(
                                LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                            )
                            .frame(width: geo.size.width * progress)
                            .animation(.easeInOut(duration: 0.5), value: progress)
                    }
                }
                .frame(height: 8)
                .padding(.horizontal, 48)
            }

            // Checklist
            VStack(alignment: .leading, spacing: 16) {
                ForEach(0..<items.count, id: \.self) { index in
                    HStack(spacing: 12) {
                        Image(systemName: index < checkItem ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 20))
                            .foregroundStyle(index < checkItem ? Color.primary : .secondary.opacity(0.3))

                        Text(items[index])
                            .font(.system(.body, design: .rounded))
                            .foregroundStyle(index <= checkItem ? .primary : .secondary)
                    }
                    .opacity(index <= checkItem ? 1 : 0.3)
                    .animation(.easeInOut(duration: 0.4), value: checkItem)
                }
            }
            .padding(.horizontal, 48)

            Spacer()
            Spacer()
        }
        .onAppear {
            // Animate progress and checklist over ~3 seconds
            withAnimation(.easeInOut(duration: 0.8)) { progress = 0.33 }

            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                checkItem = 1
                withAnimation(.easeInOut(duration: 0.8)) { progress = 0.66 }
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) {
                checkItem = 2
                withAnimation(.easeInOut(duration: 0.8)) { progress = 1.0 }
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 2.8) {
                checkItem = 3
                onComplete()
            }
        }
    }
}
