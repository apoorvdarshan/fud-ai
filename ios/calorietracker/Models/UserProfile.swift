import Foundation

enum GoalEnergyMath {
    /// Approximate energy content used consistently for both requested weekly
    /// weight change and observed weight-trend maintenance estimates.
    static let kilocaloriesPerKilogram = 7_700.0
}

// MARK: - Enums

enum Gender: String, Codable, CaseIterable {
    case male, female, other

    var displayName: String {
        switch self {
        case .male: LocalizedDisplayText.text("Male", polish: "Mężczyzna")
        case .female: LocalizedDisplayText.text("Female", polish: "Kobieta")
        case .other: LocalizedDisplayText.text("Other", polish: "Inne")
        }
    }

    var icon: String {
        switch self {
        case .male: "figure.stand"
        case .female: "figure.stand.dress"
        case .other: "figure.wave"
        }
    }
}

enum ActivityLevel: String, Codable, CaseIterable {
    case sedentary
    case light
    case moderate
    case active
    case veryActive
    case extraActive

    var displayName: String {
        switch self {
        case .sedentary: LocalizedDisplayText.text("Sedentary", polish: "Siedzący")
        case .light: LocalizedDisplayText.text("Light", polish: "Lekka")
        case .moderate: LocalizedDisplayText.text("Moderate", polish: "Umiarkowana")
        case .active: LocalizedDisplayText.text("Active", polish: "Aktywna")
        case .veryActive: LocalizedDisplayText.text("Very Active", polish: "Bardzo aktywna")
        case .extraActive: LocalizedDisplayText.text("Extra Active", polish: "Ekstremalnie aktywna")
        }
    }

    func proteinRequirementPerKg(bodyFatPercentage _: Double? = nil, extra: Double = 0.0) -> Double {
        // These activity rates are expressed as grams per kilogram of full
        // bodyweight. `bodyFatPercentage` remains in the signature for source
        // compatibility, but must not silently reinterpret them as lean-mass rates.
        proteinPerKg + extra
    }


    var subtitle: String {
        switch self {
        case .sedentary: LocalizedDisplayText.text(
            "Mostly seated at work and home; little or no planned exercise.\nApprox. step guide: under 5,000 steps/day.",
            polish: "Głównie siedząco w pracy i domu; mało lub brak planowanych ćwiczeń.\nOrientacyjnie: poniżej 5 000 kroków dziennie."
        )
        case .light: LocalizedDisplayText.text(
            "Mostly seated; light exercise or casual activity 1–3 days/week.\nApprox. step guide: 5,000–7,499 steps/day.",
            polish: "Głównie siedząco; lekki trening lub rekreacja 1–3 dni w tygodniu.\nOrientacyjnie: 5 000–7 499 kroków dziennie."
        )
        case .moderate: LocalizedDisplayText.text(
            "Regular gym, cardio, climbing, cycling, or sport 3–5 days/week.\nApprox. step guide: 7,500–9,999 steps/day.",
            polish: "Regularna siłownia, cardio, wspinaczka, rower lub sport 3–5 dni w tygodniu.\nOrientacyjnie: 7 500–9 999 kroków dziennie."
        )
        case .active: LocalizedDisplayText.text(
            "Training most days, or a job with substantial standing, movement, or lifting.\nApprox. step guide: 10,000–12,499 steps/day.",
            polish: "Trening przez większość dni lub praca z częstym staniem, ruchem albo dźwiganiem.\nOrientacyjnie: 10 000–12 499 kroków dziennie."
        )
        case .veryActive: LocalizedDisplayText.text(
            "Hard training 6–7 days/week, endurance training, or demanding physical work.\nApprox. step guide: 12,500–14,999 steps/day.",
            polish: "Ciężki trening 6–7 dni w tygodniu, trening wytrzymałościowy lub wymagająca praca fizyczna.\nOrientacyjnie: 12 500–14 999 kroków dziennie."
        )
        case .extraActive: LocalizedDisplayText.text(
            "Competitive/high-volume athlete, twice-daily training, or heavy manual work plus frequent training.\nApprox. step guide: 15,000+ steps/day.",
            polish: "Sportowiec wyczynowy, dwa treningi dziennie lub ciężka praca fizyczna plus częste treningi.\nOrientacyjnie: co najmniej 15 000 kroków dziennie."
        )
        }
    }

    var icon: String {
        switch self {
        case .sedentary: "figure.stand"
        case .light: "figure.walk"
        case .moderate: "figure.run"
        case .active: "figure.highintensity.intervaltraining"
        case .veryActive: "figure.strengthtraining.traditional"
        case .extraActive: "figure.martial.arts"
        }
    }

    var multiplier: Double {
        switch self {
        case .sedentary: 1.2
        case .light: 1.375
        case .moderate: 1.465
        case .active: 1.55
        case .veryActive: 1.725
        case .extraActive: 1.9
        }
    }

    /// g protein per kg bodyweight per activity level (ISSN 2017 / Morton et al 2018 aligned).
    var proteinPerKg: Double {
        switch self {
        case .sedentary: 0.8   // RDA floor
        case .light: 1.2
        case .moderate: 1.6    // Morton et al: point of diminishing returns for hypertrophy
        case .active: 1.8
        case .veryActive: 2.0
        case .extraActive: 2.2
        }
    }
}

enum WeightGoal: String, Codable, CaseIterable {
    case lose, maintain, gain

    var displayName: String {
        switch self {
        case .lose: LocalizedDisplayText.text("Lose Weight / Cutting", polish: "Schudnąć / Cutting")
        case .maintain: LocalizedDisplayText.text("Maintain / Recomp", polish: "Utrzymać / Recomp")
        case .gain: LocalizedDisplayText.text("Gain Weight / Bulking", polish: "Przybrać na wadze / Bulking")
        }
    }

    var icon: String {
        switch self {
        case .lose: "arrow.down.right"
        case .maintain: "equal"
        case .gain: "arrow.up.right"
        }
    }
}

enum WeightDisplayFormatter {
    private static let poundsPerKilogram = 2.20462

    static func weeklyChangeValue(kilograms: Double, useMetric: Bool) -> String {
        let value = useMetric ? kilograms : kilograms * poundsPerKilogram
        return String(format: "%.2f", value)
            .replacingOccurrences(of: "0+$", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\.$", with: "", options: .regularExpression)
    }

    static func weeklyChange(kilograms: Double, useMetric: Bool, period: String = "week") -> String {
        let unit = useMetric ? "kg" : "lbs"
        return "\(weeklyChangeValue(kilograms: kilograms, useMetric: useMetric)) \(unit)/\(period)"
    }
}

// MARK: - User Profile

struct UserProfile: Codable, Equatable {
    var name: String?
    var gender: Gender
    var birthday: Date
    var heightCm: Double
    var weightKg: Double
    var activityLevel: ActivityLevel
    var goal: WeightGoal
    var bodyFatPercentage: Double?
    /// Target body-fat fraction (0.0–1.0). Display-only — does NOT participate
    /// in BMR / TDEE / macro calculations. Only shown to users who entered a
    /// current body-fat % in onboarding (or set one later via Settings).
    var goalBodyFatPercentage: Double?
    /// User-controlled override for whether the BMR calc uses Katch-McArdle.
    /// Optional so Codable decodes pre-existing saved profiles cleanly (nil →
    /// treat as true). When false, BMR falls back to Mifflin-St Jeor even if
    /// `bodyFatPercentage` is set — escape hatch for users whose body-fat
    /// reading is stale (e.g. weight shifted but they haven't re-measured).
    /// Read everywhere via `usesBodyFatForBMR` to apply the nil default.
    var useBodyFatInBMR: Bool?
    var weeklyChangeKg: Double?
    var goalWeightKg: Double?
    var customCalories: Int?
    var customProtein: Int?
    var customFat: Int?
    var customCarbs: Int?
    var autoBalanceMacro: AutoBalanceMacro?
    /// User-entered allergen names used for local, deterministic food-label checks.
    /// Optional for backwards-compatible decoding of profiles saved before allergen analysis.
    var allergenSensitivities: [String]? = nil
    /// User lock over the calorie target. When locked, editing one macro holds this total fixed
    /// (the other unlocked macros absorb the change) instead of letting calories float to the new
    /// sum. Optional so old saves decode cleanly (nil → unlocked). Cleared by Recalculate/Adaptive.
    var caloriesLocked: Bool? = nil
    /// User locks over individual macros — at most two at once, so at least one always stays free
    /// to balance. A locked macro is never auto-adjusted during a rebalance. Optional/`nil` for
    /// back-compat (→ none locked). Cleared by Recalculate and the Adaptive auto-run.
    var lockedMacros: Set<AutoBalanceMacro>? = nil

    var displayName: String {
        if let name, !name.isEmpty { return name }
        return "User"
    }

    var initials: String {
        let parts = displayName.split(separator: " ")
        if parts.count >= 2 {
            return String(parts[0].prefix(1) + parts[1].prefix(1)).uppercased()
        }
        return String(displayName.prefix(1)).uppercased()
    }

    var age: Int {
        Calendar.current.dateComponents([.year], from: birthday, to: Date()).year ?? 25
    }

    /// Whether BMR currently uses Katch-McArdle. The current product automatically uses it
    /// whenever body fat is known.
    /// `useBodyFatInBMR` remains decode-compatible with older saved profiles but is not an
    /// editable setting and therefore must not strand users on an invisible legacy value.
    var usesBodyFatForBMR: Bool {
        bodyFatPercentage != nil
    }

    var bmr: Double {
        if usesBodyFatForBMR, let bf = bodyFatPercentage {
            // Katch-McArdle — used automatically whenever body fat is known.
            return 370 + 21.6 * (1 - bf) * weightKg
        }
        // Mifflin-St Jeor
        let base = 10 * weightKg + 6.25 * heightCm - 5 * Double(age) - 161
        switch gender {
        case .male: return base + 166
        case .female, .other: return base
        }
    }

    var tdee: Double {
        bmr * activityLevel.multiplier
    }

    var calorieAdjustment: Int {
        switch goal {
        case .maintain:
            return 0
        case .lose:
            let rate = weeklyChangeKg ?? 0.5
            return -Int(rate * GoalEnergyMath.kilocaloriesPerKilogram / 7)
        case .gain:
            let rate = weeklyChangeKg ?? 0.5
            return Int(rate * GoalEnergyMath.kilocaloriesPerKilogram / 7)
        }
    }

    var dailyCalories: Int {
        Int(tdee) + calorieAdjustment
    }

    var proteinGoal: Int {
        // +0.2 g/kg during cutting phase to preserve lean mass (Helms et al 2014).
        let cuttingBoost = goal == .lose ? 0.2 : 0.0
        let multiplier = activityLevel.proteinRequirementPerKg(bodyFatPercentage: bodyFatPercentage, extra: cuttingBoost)
        return Int(multiplier * weightKg)
    }

    var fatGoal: Int {
        Int(0.6 * weightKg)
    }

    var carbsGoal: Int {
        max(0, (dailyCalories - proteinGoal * 4 - fatGoal * 9) / 4)
    }

    var effectiveCalories: Int { customCalories ?? dailyCalories }

    /// A macro is "pinned" when its custom value is set; "auto" when nil.
    /// Auto macros split the remaining calories (after subtracting pinned macros) using
    /// their formula values as weights.
    func isPinned(_ macro: AutoBalanceMacro) -> Bool {
        customValue(macro) != nil
    }

    var pinnedCount: Int {
        AutoBalanceMacro.allCases.filter { isPinned($0) }.count
    }

    var effectiveProtein: Int {
        customProtein ?? autoMacroValue(.protein)
    }

    var effectiveCarbs: Int {
        customCarbs ?? autoMacroValue(.carbs)
    }

    var effectiveFat: Int {
        customFat ?? autoMacroValue(.fat)
    }

    private func customValue(_ macro: AutoBalanceMacro) -> Int? {
        switch macro {
        case .protein: return customProtein
        case .carbs:   return customCarbs
        case .fat:     return customFat
        }
    }

    private func formulaValue(_ macro: AutoBalanceMacro) -> Int {
        switch macro {
        case .protein: return proteinGoal
        case .carbs:   return carbsGoal
        case .fat:     return fatGoal
        }
    }

    /// Compute an auto (unpinned) macro's value: split remaining calories among auto macros
    /// using their formula values as weights, then convert kcal -> grams.
    private func autoMacroValue(_ macro: AutoBalanceMacro) -> Int {
        let pinnedKcal = AutoBalanceMacro.allCases.reduce(0) { sum, m in
            sum + (customValue(m).map { $0 * m.kcalPerGram } ?? 0)
        }
        let remaining = max(0, effectiveCalories - pinnedKcal)

        let autoMacros = AutoBalanceMacro.allCases.filter { !isPinned($0) }
        guard autoMacros.contains(macro) else { return 0 }

        // Only one auto macro: it absorbs all the remaining calories.
        if autoMacros.count == 1 {
            return remaining / macro.kcalPerGram
        }

        // Multiple auto macros: split remaining calories proportional to their formula kcal.
        let totalFormulaKcal = autoMacros.reduce(0) { $0 + formulaValue($1) * $1.kcalPerGram }
        guard totalFormulaKcal > 0 else { return formulaValue(macro) }

        let mySharedKcal = remaining * formulaValue(macro) * macro.kcalPerGram / totalFormulaKcal
        return mySharedKcal / macro.kcalPerGram
    }

    /// Stable fingerprint of the inputs that feed goal calculation. When this differs from the
    /// value captured at the last Recalculate, the UI nudges the user to recalculate. Editing a
    /// profile input no longer recomputes goals automatically, so this is how we surface "your
    /// profile changed — your goals may be stale." Must stay in sync with `goalInputsUnchanged`.
    var goalInputSignature: String {
        let parts: [String] = [
            "\(gender)",
            "\(birthday.timeIntervalSince1970)",
            "\(heightCm)",
            "\(weightKg)",
            "\(activityLevel)",
            "\(goal)",
            weeklyChangeKg.map { "\($0)" } ?? "nil",
            goalWeightKg.map { "\($0)" } ?? "nil",
            bodyFatPercentage.map { "\($0)" } ?? "nil",
            useBodyFatInBMR.map { "\($0)" } ?? "nil"
        ]
        return parts.joined(separator: "|")
    }

    // MARK: - User locks (a control layer on top of the stored custom* snapshot)

    var isCaloriesLocked: Bool { caloriesLocked ?? false }
    func isMacroLocked(_ macro: AutoBalanceMacro) -> Bool { lockedMacros?.contains(macro) ?? false }
    var lockedMacroCount: Int { lockedMacros?.count ?? 0 }

    /// Toggle a macro lock. At most two macros may be locked — at least one stays free to balance.
    /// Returns false (and changes nothing) when trying to lock a third macro.
    @discardableResult
    mutating func toggleMacroLock(_ macro: AutoBalanceMacro) -> Bool {
        var set = lockedMacros ?? []
        if set.contains(macro) {
            set.remove(macro)
        } else {
            guard set.count < 2 else { return false }
            set.insert(macro)
        }
        lockedMacros = set.isEmpty ? nil : set
        return true
    }

    mutating func toggleCaloriesLock() {
        caloriesLocked = isCaloriesLocked ? nil : true
    }

    mutating func clearLocks() {
        caloriesLocked = nil
        lockedMacros = nil
    }

    private func effectiveGrams(_ macro: AutoBalanceMacro) -> Int {
        switch macro {
        case .protein: return effectiveProtein
        case .carbs:   return effectiveCarbs
        case .fat:     return effectiveFat
        }
    }

    private mutating func setMacroGrams(_ macro: AutoBalanceMacro, _ grams: Int) {
        let clamped = max(0, grams)
        switch macro {
        case .protein: customProtein = clamped
        case .carbs:   customCarbs = clamped
        case .fat:     customFat = clamped
        }
    }

    /// Freeze the current effective values into the stored custom* fields so edits are explicit
    /// (no hidden auto-balance). Snapshots all three macros before writing any, so writing one
    /// doesn't shift another macro's auto value mid-materialization.
    private mutating func materializeGoals() {
        let p = effectiveProtein, c = effectiveCarbs, f = effectiveFat, cal = effectiveCalories
        customProtein = p; customCarbs = c; customFat = f; customCalories = cal
    }

    /// Fill `macros` so their kcal sums to `targetKcal`, split proportional to each macro's current
    /// kcal (falling back to formula weights when current is zero). The last macro absorbs the
    /// rounding remainder so the group lands on target.
    private mutating func distribute(_ targetKcal: Int, among macros: [AutoBalanceMacro]) {
        guard !macros.isEmpty else { return }
        let target = max(0, targetKcal)
        let weights = macros.map { macro -> Double in
            let current = Double(effectiveGrams(macro) * macro.kcalPerGram)
            return current > 0 ? current : Double(max(1, formulaValue(macro) * macro.kcalPerGram))
        }
        let totalWeight = weights.reduce(0, +)
        var assignedKcal = 0
        for (index, macro) in macros.enumerated() {
            if index == macros.count - 1 {
                let kcal = max(0, target - assignedKcal)
                setMacroGrams(macro, Int((Double(kcal) / Double(macro.kcalPerGram)).rounded()))
            } else {
                let share = totalWeight > 0
                    ? Double(target) * weights[index] / totalWeight
                    : Double(target) / Double(macros.count)
                let grams = Int((share / Double(macro.kcalPerGram)).rounded())
                setMacroGrams(macro, grams)
                assignedKcal += grams * macro.kcalPerGram
            }
        }
    }

    /// User edited the calorie target directly. Hold any locked macros fixed and rescale the
    /// unlocked macros to fill the new total. (Max two macros lock, so one always absorbs.)
    mutating func applyCaloriesEdit(_ newCalories: Int) {
        materializeGoals()
        let target = max(0, newCalories)
        let lockedKcal = AutoBalanceMacro.allCases
            .filter { isMacroLocked($0) }
            .reduce(0) { $0 + effectiveGrams($1) * $1.kcalPerGram }
        let unlocked = AutoBalanceMacro.allCases.filter { !isMacroLocked($0) }
        distribute(target - lockedKcal, among: unlocked)
        customCalories = target
    }

    /// User edited one macro. When calories is locked, hold the calorie total fixed and let the
    /// other unlocked macros absorb the change — returns false (changes nothing) if neither other
    /// macro can absorb (both locked). When calories is unlocked, the macro simply takes the new
    /// value and calories floats to the new sum.
    @discardableResult
    mutating func applyMacroEdit(_ macro: AutoBalanceMacro, grams newGrams: Int) -> Bool {
        materializeGoals()
        let requested = max(0, newGrams)
        if isCaloriesLocked {
            let absorbers = AutoBalanceMacro.allCases.filter { $0 != macro && !isMacroLocked($0) }
            guard !absorbers.isEmpty else { return false }
            let otherLockedKcal = AutoBalanceMacro.allCases
                .filter { $0 != macro && isMacroLocked($0) }
                .reduce(0) { $0 + effectiveGrams($1) * $1.kcalPerGram }
            let available = max(0, effectiveCalories - otherLockedKcal)
            let macroKcal = min(requested * macro.kcalPerGram, available)
            setMacroGrams(macro, macroKcal / macro.kcalPerGram)
            distribute(available - macroKcal, among: absorbers)
            // customCalories stays put — calories is locked.
            return true
        } else {
            setMacroGrams(macro, requested)
            customCalories = AutoBalanceMacro.allCases.reduce(0) { $0 + effectiveGrams($1) * $1.kcalPerGram }
            return true
        }
    }

    /// Release the calories lock and reset the total to the sum of the current macros — the honest
    /// "auto" value when calories isn't pinned. The picker's "Reset to Auto-balance" action for the
    /// calories row, mirroring the per-macro reset.
    mutating func resetCaloriesToBalance() {
        materializeGoals()
        caloriesLocked = nil
        customCalories = AutoBalanceMacro.allCases.reduce(0) { $0 + effectiveGrams($1) * $1.kcalPerGram }
    }

    /// Release a macro's lock and reset it to the balancing remainder: it absorbs whatever calories
    /// the other two macros leave, so the macros sum back to the calorie total. This is the "Reset
    /// to Auto-balance" action — turns the lock off and re-derives the value.
    mutating func resetMacroToBalance(_ macro: AutoBalanceMacro) {
        materializeGoals()
        if var set = lockedMacros {
            set.remove(macro)
            lockedMacros = set.isEmpty ? nil : set
        }
        let othersKcal = AutoBalanceMacro.allCases
            .filter { $0 != macro }
            .reduce(0) { $0 + effectiveGrams($1) * $1.kcalPerGram }
        setMacroGrams(macro, max(0, effectiveCalories - othersKcal) / macro.kcalPerGram)
    }

    /// Recompute calories from weight/activity/goal formulas and reset all three macros to auto.
    /// Clears every user lock — a fresh calculation starts unlocked.
    mutating func recalculateGoalsFromFormulas() {
        customCalories = dailyCalories
        customProtein = nil
        customFat = nil
        customCarbs = nil
        autoBalanceMacro = nil
        clearLocks()
    }

    static let `default` = UserProfile(
        name: nil,
        gender: .male,
        birthday: Calendar.current.date(byAdding: .year, value: -25, to: Date()) ?? Date(),
        heightCm: 175,
        weightKg: 70,
        activityLevel: .moderate,
        goal: .maintain,
        bodyFatPercentage: nil,
        goalBodyFatPercentage: nil,
        useBodyFatInBMR: nil,
        weeklyChangeKg: nil,
        goalWeightKg: nil,
        customCalories: nil,
        customProtein: nil,
        customFat: nil,
        customCarbs: nil,
        autoBalanceMacro: nil,
        allergenSensitivities: []
    )

    var configuredAllergenSensitivities: [String] {
        allergenSensitivities ?? []
    }

    // MARK: - Persistence

    static func load() -> UserProfile? {
        guard let data = UserDefaults.standard.data(forKey: "userProfile"),
              let profile = try? JSONDecoder().decode(UserProfile.self, from: data)
        else { return nil }
        return profile
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: "userProfile")
            NotificationCenter.default.post(name: .userProfileDidChange, object: nil)
        }
    }
}

/// Energy Burn toggle. Purely an input source: when on (and Apple Health has enough data), the
/// goal calculation anchors maintenance to the user's measured Active + Basal burn instead of the
/// formula TDEE. It owns no targets and no cadence of its own — it just flips this flag, which the
/// manual Recalculate and the Adaptive auto-run both consult. Fresh key so it starts OFF.
enum EnergyBurnSettings {
    static let enabledKey = "energyBurnEnabledV2"
}

struct AdaptiveGoalSettings {
    static let enabledKey = "adaptiveGoalsEnabled"
    private static let previousTargetsKey = "adaptiveGoalsPreviousTargets"
    private static let lastCheckDayKey = "adaptiveGoalsLastCheckDay"
    private static let daysBetweenChecks = 7

    private struct TargetSnapshot: Codable {
        var customCalories: Int?
        var customProtein: Int?
        var customFat: Int?
        var customCarbs: Int?
        var autoBalanceMacro: AutoBalanceMacro?
    }

    static var hasPreviousTargets: Bool {
        UserDefaults.standard.data(forKey: previousTargetsKey) != nil
    }

    static func savePreviousTargetsIfNeeded(from profile: UserProfile) {
        guard !hasPreviousTargets else { return }
        let snapshot = TargetSnapshot(
            customCalories: profile.customCalories,
            customProtein: profile.customProtein,
            customFat: profile.customFat,
            customCarbs: profile.customCarbs,
            autoBalanceMacro: profile.autoBalanceMacro
        )
        if let data = try? JSONEncoder().encode(snapshot) {
            UserDefaults.standard.set(data, forKey: previousTargetsKey)
        }
    }

    @discardableResult
    static func restorePreviousTargets(to profile: inout UserProfile) -> Bool {
        guard let data = UserDefaults.standard.data(forKey: previousTargetsKey),
              let snapshot = try? JSONDecoder().decode(TargetSnapshot.self, from: data) else {
            return false
        }
        profile.customCalories = snapshot.customCalories
        profile.customProtein = snapshot.customProtein
        profile.customFat = snapshot.customFat
        profile.customCarbs = snapshot.customCarbs
        profile.autoBalanceMacro = snapshot.autoBalanceMacro
        return true
    }

    static func clearPreviousTargets() {
        UserDefaults.standard.removeObject(forKey: previousTargetsKey)
    }

    static func shouldCheckThisWeek(calendar: Calendar = .current, now: Date = .now) -> Bool {
        guard let lastCheck = UserDefaults.standard.string(forKey: lastCheckDayKey),
              let lastDate = date(from: lastCheck, calendar: calendar) else {
            return true
        }

        let days = calendar.dateComponents(
            [.day],
            from: calendar.startOfDay(for: lastDate),
            to: calendar.startOfDay(for: now)
        ).day ?? daysBetweenChecks
        return days >= daysBetweenChecks
    }

    static func markCheckedToday(calendar: Calendar = .current, now: Date = .now) {
        UserDefaults.standard.set(dayKey(for: now, calendar: calendar), forKey: lastCheckDayKey)
    }

    private static func dayKey(for date: Date, calendar: Calendar) -> String {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", components.year ?? 0, components.month ?? 0, components.day ?? 0)
    }

    private static func date(from dayKey: String, calendar: Calendar) -> Date? {
        let parts = dayKey.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }
}

extension Notification.Name {
    static let userProfileDidChange = Notification.Name("userProfileDidChange")
    static let weightGoalReached = Notification.Name("weightGoalReached")
}

enum AutoBalanceMacro: String, Codable, CaseIterable, Identifiable {
    case protein, carbs, fat
    var id: String { rawValue }
    var label: String {
        switch self {
        case .protein: LocalizedDisplayText.text("Protein", polish: "Białko")
        case .carbs: LocalizedDisplayText.text("Carbs", polish: "Węglowodany")
        case .fat: LocalizedDisplayText.text("Fat", polish: "Tłuszcz")
        }
    }
    var kcalPerGram: Int { self == .fat ? 9 : 4 }
}
