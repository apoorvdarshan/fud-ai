import Foundation

enum WorkoutTabMode: String {
    case library
    case log

    /// v2 resets the former library-first default once; later user switches persist normally.
    static let storageKey = "fudai.workouts.tab.mode.v2"
    static let defaultMode: WorkoutTabMode = .log

    static func mode(for rawValue: String) -> WorkoutTabMode {
        return WorkoutTabMode(rawValue: rawValue) ?? defaultMode
    }

    var tabIcon: String {
        switch self {
        case .library: return "dumbbell.fill"
        case .log: return "figure.strengthtraining.traditional"
        }
    }
}

enum StrengthWorkoutDate {
    static func key(for date: Date, calendar: Calendar = .current) -> String {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", components.year ?? 0, components.month ?? 0, components.day ?? 0)
    }

    static func date(for key: String, calendar: Calendar = .current) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }
}

enum StrengthWorkoutRPEScale: String, Codable, CaseIterable, Identifiable {
    case strength
    case cr10
    case borg

    var id: String { rawValue }

    var title: String {
        switch self {
        case .strength: return "Strength 1–10"
        case .cr10: return "CR10 0–10"
        case .borg: return "Borg 6–20"
        }
    }

    var subtitle: String {
        switch self {
        case .strength: return "1–10 effort, with 10 as maximum"
        case .cr10: return "0–10 perceived exertion, decimals allowed"
        case .borg: return "6–20 perceived exertion, whole numbers"
        }
    }

    var shortTitle: String {
        switch self {
        case .strength: return "1–10"
        case .cr10: return "CR10"
        case .borg: return "Borg"
        }
    }

    var inputPlaceholder: String {
        switch self {
        case .strength: return "1–10"
        case .cr10: return "0–10"
        case .borg: return "6–20"
        }
    }

    var allowsDecimalInput: Bool { self != .borg }

    var inputRange: ClosedRange<Double> {
        switch self {
        case .strength: return 1...10
        case .cr10: return 0...10
        case .borg: return 6...20
        }
    }

    /// Keeps valid in-progress input such as `7.` so decimal RPE values remain
    /// typeable. This matches the final Delts diary behavior.
    func sanitized(_ proposedValue: String, previousValue: String = "") -> String {
        let normalized = proposedValue
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".")
        guard !normalized.isEmpty else { return "" }

        var filtered = ""
        var hasDecimal = false
        var fractionalDigits = 0
        for character in normalized {
            if character.isNumber {
                if hasDecimal {
                    guard allowsDecimalInput, fractionalDigits < 1 else { continue }
                    fractionalDigits += 1
                }
                filtered.append(character)
            } else if character == ".", allowsDecimalInput, !hasDecimal, !filtered.isEmpty {
                hasDecimal = true
                filtered.append(character)
            }
        }
        guard !filtered.isEmpty else { return previousValue }

        let numericText = filtered.hasSuffix(".") ? String(filtered.dropLast()) : filtered
        guard let value = Double(numericText) else { return previousValue }
        if value > inputRange.upperBound { return String(Int(inputRange.upperBound)) }
        if value < inputRange.lowerBound, !isPossibleRangePrefix(filtered) { return previousValue }
        return filtered
    }

    private func isPossibleRangePrefix(_ value: String) -> Bool {
        let integerPrefix = value.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: true).first.map(String.init) ?? value
        guard !integerPrefix.isEmpty else { return false }
        let lower = Int(inputRange.lowerBound.rounded(.up))
        let upper = Int(inputRange.upperBound.rounded(.down))
        return (lower...upper).contains { String($0).hasPrefix(integerPrefix) }
    }
}

enum StrengthWorkoutSplit: String, Codable, CaseIterable, Identifiable {
    case pushPullLegs
    case upperLower
    case broSplit
    case arnold
    case pushPull
    case antagonistSplit
    case hybridSplit
    case fullBody
    case custom

    static let selectableCases: [StrengthWorkoutSplit] = [
        .fullBody, .upperLower, .pushPullLegs, .broSplit, .arnold,
        .pushPull, .antagonistSplit, .hybridSplit
    ]

    var id: String { rawValue }

    var title: String {
        switch self {
        case .pushPullLegs: return "Push / Pull / Legs"
        case .upperLower: return "Upper / Lower"
        case .broSplit: return "Body-part split"
        case .arnold: return "Arnold split"
        case .pushPull: return "Push / Pull"
        case .antagonistSplit: return "Antagonist split"
        case .hybridSplit: return "Hybrid split"
        case .fullBody: return "Full body"
        case .custom: return "Custom"
        }
    }
}

enum StrengthWorkoutDuration: Int, Codable, CaseIterable, Identifiable {
    case thirty = 30
    case fortyFive = 45
    case sixty = 60
    case seventyFive = 75
    case ninety = 90

    var id: Int { rawValue }
    var title: String { "\(rawValue) min" }
}

enum StrengthWorkoutIssue: String, Codable, CaseIterable, Identifiable {
    case shoulder = "Shoulder"
    case elbow = "Elbow"
    case wrist = "Wrist"
    case lowerBack = "Lower back"
    case hip = "Hip"
    case knee = "Knee"
    case ankle = "Ankle"
    case other = "Other"

    var id: String { rawValue }
}

struct StrengthWorkoutNumbers: Codable, Equatable {
    var benchPressKg: Double?
    var squatKg: Double?
    var deadliftKg: Double?
    var overheadPressKg: Double?
}

struct StrengthWorkoutPreferences: Codable, Equatable {
    var targetMuscles: Set<String> = []
    var issues: Set<StrengthWorkoutIssue> = []
    var additionalIssues = ""
    var frequencyDays = 3
    var duration: StrengthWorkoutDuration = .sixty
    var split: StrengthWorkoutSplit = .fullBody
    var customSplit = ""
    var equipment: Set<String> = []
    var rpeScale: StrengthWorkoutRPEScale = .strength
    var strength = StrengthWorkoutNumbers()

    mutating func sanitize() {
        frequencyDays = min(max(frequencyDays, 1), 7)
        additionalIssues = additionalIssues.trimmingCharacters(in: .whitespacesAndNewlines)
        customSplit = customSplit.trimmingCharacters(in: .whitespacesAndNewlines)
        if !issues.contains(.other) { additionalIssues = "" }
        if split == .custom { split = .fullBody }
        customSplit = ""
        strength.benchPressKg = Self.validLoad(strength.benchPressKg)
        strength.squatKg = Self.validLoad(strength.squatKg)
        strength.deadliftKg = Self.validLoad(strength.deadliftKg)
        strength.overheadPressKg = Self.validLoad(strength.overheadPressKg)
    }

    private static func validLoad(_ value: Double?) -> Double? {
        guard let value, value.isFinite, value > 0 else { return nil }
        return value
    }
}

struct StrengthPlannedSet: Identifiable, Codable, Equatable, Hashable {
    var id = UUID()
    var weight = ""
    /// The unit used when this load was entered. Optional for compatibility
    /// with the first diary build, before planned loads carried their unit.
    var weightUnit: String?
    var reps = ""
    var rpe = ""
    /// RPE values are meaningful only together with their selected scale.
    var rpeScale: StrengthWorkoutRPEScale?

    var hasLoggedValue: Bool {
        !weight.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !reps.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !rpe.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func blankCopy(carryingWeight: Bool) -> StrengthPlannedSet {
        StrengthPlannedSet(
            weight: carryingWeight ? weight : "",
            weightUnit: carryingWeight ? weightUnit : nil
        )
    }

    /// New sets inherit weight, unit, and reps from the set above; RPE stays blank.
    func copyingFromPrevious() -> StrengthPlannedSet {
        StrengthPlannedSet(
            weight: weight,
            weightUnit: weightUnit,
            reps: reps
        )
    }

    /// Presents a persisted load in the app-wide unit without relabeling the
    /// underlying value. Editing the field then stores the new text together
    /// with the currently selected global unit.
    func displayWeight(in targetUnit: WeightUnit) -> String {
        guard let sourceUnit = WeightUnit(rawValue: weightUnit ?? ""),
              sourceUnit != targetUnit,
              let numericWeight = Double(weight.replacingOccurrences(of: ",", with: ".")),
              numericWeight.isFinite
        else { return weight }

        let poundsPerKilogram = 2.204_622_621_8
        let converted = sourceUnit == .kg
            ? numericWeight * poundsPerKilogram
            : numericWeight / poundsPerKilogram
        var text = String(format: "%.2f", converted)
        while text.last == "0" { text.removeLast() }
        if text.last == "." { text.removeLast() }
        return text
    }
}

enum StrengthWorkoutIntensity: String, Codable, CaseIterable, Identifiable {
    case light
    case moderate
    case vigorous

    var id: String { rawValue }
    var title: String { rawValue.capitalized }
}

enum StrengthExerciseTimerAction {
    case start, pause, resume, stop, restart, discard
}

/// Persisted timestamps keep each exercise's stopwatch accurate while the app
/// is backgrounded or terminated, without depending on a UI tick to save time.
struct StrengthExerciseTimer: Codable, Equatable, Hashable {
    var accumulatedSeconds: Double = 0
    var runningSince: Date?
    var savedDurationSeconds: Double?
    var intensity: StrengthWorkoutIntensity = .moderate

    var isRunning: Bool { runningSince != nil }
    var isSaved: Bool {
        guard let savedDurationSeconds else { return false }
        return !isRunning && savedDurationSeconds.isFinite && savedDurationSeconds > 0
    }

    func elapsedSeconds(at now: Date = .now) -> Double {
        let accumulated = accumulatedSeconds.isFinite ? max(0, accumulatedSeconds) : 0
        guard let runningSince else { return accumulated }
        let elapsed = now.timeIntervalSince(runningSince)
        return accumulated + (elapsed.isFinite ? max(0, elapsed) : 0)
    }

    mutating func apply(_ action: StrengthExerciseTimerAction, at now: Date) {
        switch action {
        case .start, .resume:
            guard !isRunning else { return }
            runningSince = now
            savedDurationSeconds = nil
        case .pause:
            guard isRunning else { return }
            accumulatedSeconds = elapsedSeconds(at: now)
            runningSince = nil
        case .stop:
            accumulatedSeconds = elapsedSeconds(at: now)
            runningSince = nil
            savedDurationSeconds = accumulatedSeconds > 0 ? accumulatedSeconds : nil
        case .restart:
            accumulatedSeconds = 0
            runningSince = now
            savedDurationSeconds = nil
        case .discard:
            accumulatedSeconds = 0
            runningSince = nil
            savedDurationSeconds = nil
        }
    }
}

struct StrengthPlannedExercise: Identifiable, Codable, Equatable, Hashable {
    var id = UUID()
    let itemID: String
    var name: String
    var rawLevel: String
    var imagePaths: [String]
    var force: String
    var mechanic: String
    var category: String
    var rawEquipment: String
    var primaryMuscles: [String]
    var secondaryMuscles: [String]
    var instructions: [String]
    var sets: [StrengthPlannedSet]
    /// Optional so diaries created before exercise timers still decode.
    var timer: StrengthExerciseTimer?

    init(item: ExerciseLibraryItem) {
        itemID = item.id
        name = item.name
        rawLevel = item.rawLevel
        imagePaths = item.imagePaths
        force = item.force
        mechanic = item.mechanic
        category = item.category
        rawEquipment = item.rawEquipment
        primaryMuscles = item.primaryMuscles
        secondaryMuscles = item.secondaryMuscles
        instructions = item.instructions
        sets = [StrengthPlannedSet()]
        timer = nil
    }

    var isCardio: Bool { category.caseInsensitiveCompare("cardio") == .orderedSame }

    var libraryItem: ExerciseLibraryItem {
        ExerciseLibraryItem(
            id: itemID,
            name: name,
            rawLevel: rawLevel,
            imagePaths: imagePaths,
            force: force,
            mechanic: mechanic,
            category: category,
            rawEquipment: rawEquipment,
            primaryMuscles: primaryMuscles,
            secondaryMuscles: secondaryMuscles,
            instructions: instructions
        )
    }

    func copiedForNewDay(includeSetDetails: Bool = false) -> StrengthPlannedExercise {
        var copy = self
        copy.id = UUID()
        if includeSetDetails {
            copy.sets = sets.map { set in
                var copiedSet = set
                copiedSet.id = UUID()
                return copiedSet
            }
        } else {
            copy.sets = [StrengthPlannedSet()]
            copy.timer = nil
        }
        return copy
    }
}

struct StrengthWorkoutDayPlan: Identifiable, Codable, Equatable {
    var id: String { dateKey }
    let dateKey: String
    var exercises: [StrengthPlannedExercise] = []
}

struct StrengthCompletedSet: Identifiable, Codable, Equatable, Hashable {
    var id = UUID()
    let setNumber: Int
    let weight: String
    let weightUnit: String
    let reps: String
    let rpe: String
    /// Optional so version-one workout records continue to decode.
    var rpeScale: StrengthWorkoutRPEScale?

    var isPerformed: Bool {
        // Delts treats a set as performed once reps are entered; a load or RPE
        // by itself remains a planned/incomplete set.
        !reps.isEmpty
    }
}

struct StrengthCompletedExercise: Identifiable, Codable, Equatable, Hashable {
    var id = UUID()
    let itemID: String
    let name: String
    let targetMuscles: [String]
    let equipment: String
    let sets: [StrengthCompletedSet]
    /// Saved exercise time is included in snapshots; active/paused timers are
    /// only drafts until the user chooses Stop & save.
    var durationSeconds: Double? = nil
    var intensity: StrengthWorkoutIntensity? = nil
}

struct StrengthWorkoutSession: Identifiable, Codable, Equatable, Hashable {
    var id = UUID()
    let diaryDate: Date
    /// A stable calendar-day identity that does not move when the user changes
    /// time zones. Optional so records from the first diary build still load.
    var diaryDateKey: String? = nil
    let startedAt: Date
    let completedAt: Date
    let durationSeconds: Int
    let exercises: [StrengthCompletedExercise]
    /// A user-requested estimate for this diary day. Optional so timer-era
    /// sessions written before calorie calculation was added still decode.
    var caloriesBurned: Int? = nil
    /// Monotonically increases when the same daily estimate is recalculated.
    /// HealthKit uses this to replace the tagged active-energy sample safely.
    var healthSyncVersion: Int? = nil

    var durationMinutes: Int { max(0, Int(ceil(Double(durationSeconds) / 60))) }
    var exerciseCount: Int { exercises.count }
    var performedSetCount: Int { exercises.flatMap(\.sets).filter(\.isPerformed).count }
    var repCount: Int {
        exercises.flatMap(\.sets).reduce(0) { $0 + (Int($1.reps) ?? 0) }
    }

    var stableDiaryDateKey: String {
        diaryDateKey ?? StrengthWorkoutDate.key(for: diaryDate)
    }

    var calendarDiaryDate: Date {
        StrengthWorkoutDate.date(for: stableDiaryDateKey) ?? diaryDate
    }

    var displayTitle: String {
        let calendar = Calendar.current
        if calendar.isDateInToday(calendarDiaryDate) { return "Today Workout" }
        return "\(calendarDiaryDate.formatted(.dateTime.weekday(.wide))) Workout"
    }
}

struct StrengthWorkoutBurnEstimate: Equatable {
    let calories: Int
    let performedSetCount: Int
    let repCount: Int
}

/// Offline burn estimate used by the explicit Calculate button. Saved exercise
/// timers use duration and an activity/intensity MET estimate. Untimed strength
/// exercises retain the set-based estimate. Timed exercises are counted once,
/// and these estimates are kept out of nutrition-goal calculations.
enum StrengthWorkoutBurnEstimator {
    static func estimate(
        exercises: [StrengthPlannedExercise],
        bodyWeightKg: Double,
        defaultWeightUnit: WeightUnit,
        defaultRPEScale: StrengthWorkoutRPEScale
    ) -> StrengthWorkoutBurnEstimate? {
        let safeBodyWeight = bodyWeightKg.isFinite ? min(max(bodyWeightKg, 35), 300) : 70
        var performedSetCount = 0
        var repCount = 0
        var activeMinutes = 0.0
        var recoveryMinutes = 0.0
        var effortTotal = 0.0
        var relativeLoadTotal = 0.0
        var exercisesWithWork = 0
        var untimedSetCount = 0
        var timedCalories = 0.0

        for exercise in exercises {
            let savedSeconds = exercise.timer?.savedDurationSeconds ?? 0
            let hasSavedDuration = exercise.timer?.isSaved == true
            if hasSavedDuration {
                let met = timedMET(for: exercise, intensity: timerIntensity(for: exercise, defaultRPEScale: defaultRPEScale))
                timedCalories += met * 3.5 * safeBodyWeight / 200 * (savedSeconds / 60)
            }
            var performedInExercise = 0
            for set in exercise.sets {
                guard let rawReps = Int(set.reps), rawReps > 0 else { continue }
                let reps = min(rawReps, 100)
                performedSetCount += 1
                repCount += reps
                // Keep the diary's sets/reps statistics, but never add the
                // set-based calorie estimate on top of this exercise's timer.
                guard !hasSavedDuration, !exercise.isCardio else { continue }
                performedInExercise += 1
                untimedSetCount += 1

                // Roughly 2.5–3 seconds per controlled rep, bounded for unusual
                // logging values. Recovery is modeled separately below.
                activeMinutes += min(max(Double(reps) * 2.75 / 60, 0.30), 1.50)
                recoveryMinutes += 1.60
                effortTotal += normalizedEffort(set.rpe, scale: set.rpeScale ?? defaultRPEScale)
                relativeLoadTotal += relativeLoad(
                    set.weight,
                    unit: WeightUnit(rawValue: set.weightUnit ?? "") ?? defaultWeightUnit,
                    bodyWeightKg: safeBodyWeight
                )
            }
            if performedInExercise > 0 { exercisesWithWork += 1 }
        }

        guard untimedSetCount > 0 || timedCalories > 0 else { return nil }

        // The final set does not need a full recovery block. Add a small,
        // exercise-level allowance for setup and transitions, then enforce a
        // realistic minimum for a logged resistance-training bout.
        recoveryMinutes = max(0, recoveryMinutes - 1.60)
        let transitionMinutes = Double(exercisesWithWork) * 0.75
        let estimatedMinutes = max(4, activeMinutes + recoveryMinutes + transitionMinutes)
        let averageEffort = untimedSetCount > 0 ? effortTotal / Double(untimedSetCount) : 0
        let averageRelativeLoad = untimedSetCount > 0 ? relativeLoadTotal / Double(untimedSetCount) : 0
        let met = min(max(3.8 + (2.4 * averageEffort) + (0.5 * averageRelativeLoad), 3.5), 8.0)
        let strengthCalories = untimedSetCount > 0 ? met * 3.5 * safeBodyWeight / 200 * estimatedMinutes : 0
        let rawCalories = min(strengthCalories + timedCalories, 5_000)

        return StrengthWorkoutBurnEstimate(
            calories: min(max(Int(rawCalories.rounded()), 1), 5_000),
            performedSetCount: performedSetCount,
            repCount: repCount
        )
    }

    /// RPE drives timed effort too; missing RPE retains legacy saved effort or the moderate default.
    static func timerIntensity(for exercise: StrengthPlannedExercise, defaultRPEScale: StrengthWorkoutRPEScale) -> StrengthWorkoutIntensity {
        let efforts = exercise.sets.compactMap { set -> Double? in
            guard let value = Double(set.rpe.replacingOccurrences(of: ",", with: ".")), value.isFinite else { return nil }
            return normalizedEffort(set.rpe, scale: set.rpeScale ?? defaultRPEScale)
        }
        guard !efforts.isEmpty else { return exercise.timer?.intensity ?? .moderate }
        let effort = efforts.reduce(0, +) / Double(efforts.count)
        return effort < 0.4 ? .light : (effort >= 0.75 ? .vigorous : .moderate)
    }

    /// Representative activity values from the 2024 Adult Compendium:
    /// https://pacompendium.com/bicycling/ , /walking/ , /running/ , /sports/
    /// and /conditioning-exercise/. Intensity is an RPE-derived approximation
    /// of pace/effort, not a measured speed, power output, or energy expenditure.
    private static func timedMET(for exercise: StrengthPlannedExercise, intensity: StrengthWorkoutIntensity) -> Double {
        let values: (Double, Double, Double)
        if exercise.isCardio {
            switch exercise.itemID {
            case "Bicycling": values = (4.3, 7, 9)
            case "Bicycling_Stationary", "Recumbent_Bike": values = (3.5, 6, 10.8)
            case "Elliptical_Trainer": values = (5, 5, 9)
            case "Walking_Treadmill", "Walking_Outdoor": values = (2.8, 3.8, 4.8)
            case "Jogging_Treadmill", "Running_Treadmill", "Running_Outdoor": values = (6.5, 8.5, 10.5)
            case "Rope_Jumping": values = (8.3, 11.8, 12.3)
            case "Rowing_Stationary": values = (5, 7.3, 11)
            case "Skating": values = (7.5, 9.8, 12.3)
            case "Stairmaster", "Step_Mill": values = (4.5, 6.8, 9.3)
            case "Trail_Running_Walking": values = (3.8, 6, 9.3)
            default: values = (3.5, 5, 7.5)
            }
        } else if exercise.category.caseInsensitiveCompare("stretching") == .orderedSame {
            // Compendium 02101 describes mild stretching; it does not provide
            // distinct light/moderate/vigorous stretching values.
            values = (2.3, 2.3, 2.3)
        } else if exercise.category.caseInsensitiveCompare("plyometrics") == .orderedSame {
            values = (3.5, 5, 7.5)
        } else {
            values = (3.5, 5, 6)
        }
        switch intensity {
        case .light: return values.0
        case .moderate: return values.1
        case .vigorous: return values.2
        }
    }

    private static func normalizedEffort(_ text: String, scale: StrengthWorkoutRPEScale) -> Double {
        guard let value = Double(text.replacingOccurrences(of: ",", with: ".")) else { return 0.60 }
        let normalized: Double
        switch scale {
        case .strength:
            normalized = (value - 1) / 9
        case .cr10:
            normalized = value / 10
        case .borg:
            normalized = (value - 6) / 14
        }
        return min(max(normalized, 0), 1)
    }

    private static func relativeLoad(_ text: String, unit: WeightUnit, bodyWeightKg: Double) -> Double {
        guard let value = Double(text), value.isFinite, value > 0 else { return 0 }
        let kilograms = unit == .kg ? value : value / 2.204_622_621_8
        return min(max(kilograms / bodyWeightKg, 0), 2)
    }
}

struct StrengthWorkoutSplitGroup: Identifiable, Hashable {
    let title: String
    let muscles: Set<String>
    var id: String { title }

    static func groups(for split: StrengthWorkoutSplit, availableMuscles: [String]) -> [StrengthWorkoutSplitGroup] {
        func matching(_ candidates: [String]) -> Set<String> {
            let lowered = Dictionary(uniqueKeysWithValues: availableMuscles.map { ($0.lowercased(), $0) })
            return Set(candidates.compactMap { lowered[$0.lowercased()] })
        }

        switch split {
        case .pushPullLegs:
            return [
                .init(title: "Push", muscles: matching(["Chest", "Shoulders", "Triceps"])),
                .init(title: "Pull", muscles: matching(["Biceps", "Forearms", "Lats", "Middle Back", "Traps", "Neck"])),
                .init(title: "Legs", muscles: matching(["Abductors", "Adductors", "Calves", "Glutes", "Hamstrings", "Lower Back", "Quadriceps"])),
                .init(title: "Core", muscles: matching(["Abdominals"]))
            ]
        case .upperLower:
            return [
                .init(title: "Upper", muscles: matching(["Biceps", "Chest", "Forearms", "Lats", "Middle Back", "Neck", "Shoulders", "Traps", "Triceps"])),
                .init(title: "Lower", muscles: matching(["Abductors", "Adductors", "Calves", "Glutes", "Hamstrings", "Lower Back", "Quadriceps"])),
                .init(title: "Core", muscles: matching(["Abdominals"]))
            ]
        case .broSplit:
            return [
                .init(title: "Chest", muscles: matching(["Chest"])),
                .init(title: "Back", muscles: matching(["Lats", "Middle Back", "Lower Back", "Traps"])),
                .init(title: "Shoulders", muscles: matching(["Shoulders", "Traps"])),
                .init(title: "Arms", muscles: matching(["Biceps", "Triceps", "Forearms"])),
                .init(title: "Legs", muscles: matching(["Abductors", "Adductors", "Calves", "Glutes", "Hamstrings", "Quadriceps"])),
                .init(title: "Core", muscles: matching(["Abdominals"]))
            ]
        case .arnold:
            return [
                .init(title: "Chest + Back", muscles: matching(["Chest", "Lats", "Middle Back", "Lower Back", "Traps"])),
                .init(title: "Shoulders + Arms", muscles: matching(["Shoulders", "Biceps", "Triceps", "Forearms", "Neck"])),
                .init(title: "Legs", muscles: matching(["Abductors", "Adductors", "Calves", "Glutes", "Hamstrings", "Quadriceps"])),
                .init(title: "Core", muscles: matching(["Abdominals"]))
            ]
        case .pushPull:
            return [
                .init(title: "Push", muscles: matching(["Chest", "Shoulders", "Triceps", "Quadriceps", "Calves"])),
                .init(title: "Pull", muscles: matching(["Biceps", "Forearms", "Lats", "Middle Back", "Traps", "Glutes", "Hamstrings", "Lower Back"])),
                .init(title: "Accessory/Core", muscles: matching(["Abdominals", "Abductors", "Adductors", "Neck"]))
            ]
        case .antagonistSplit:
            return [
                .init(title: "Chest + Back", muscles: matching(["Chest", "Lats", "Middle Back", "Lower Back", "Traps"])),
                .init(title: "Biceps + Triceps", muscles: matching(["Biceps", "Triceps", "Forearms"])),
                .init(title: "Quads + Hamstrings/Glutes", muscles: matching(["Quadriceps", "Hamstrings", "Glutes"])),
                .init(title: "Shoulders + Lats/Traps", muscles: matching(["Shoulders", "Lats", "Traps"])),
                .init(title: "Core/Accessory", muscles: matching(["Abdominals", "Abductors", "Adductors", "Calves", "Neck"]))
            ]
        case .hybridSplit:
            return [
                .init(title: "Strength/Compound", muscles: matching(["Chest", "Lats", "Middle Back", "Lower Back", "Glutes", "Hamstrings", "Quadriceps", "Shoulders", "Traps"])),
                .init(title: "Accessory/Hypertrophy", muscles: matching(["Biceps", "Triceps", "Forearms", "Calves", "Abductors", "Adductors", "Abdominals", "Neck"]))
            ]
        case .fullBody, .custom:
            return []
        }
    }

    static func selectionGroups(
        for split: StrengthWorkoutSplit,
        availablePrimaryMuscles: [String],
        availableSecondaryMuscles: [String]
    ) -> [StrengthWorkoutSplitGroup] {
        let availableMuscles = Set(availablePrimaryMuscles + availableSecondaryMuscles).sorted()
        let configured = groups(for: split, availableMuscles: availableMuscles)
            .filter { !$0.muscles.isEmpty }
        if !configured.isEmpty { return configured }

        return availableMuscles.map { muscle in
            StrengthWorkoutSplitGroup(title: muscle, muscles: [muscle])
        }
    }
}

struct StrengthExerciseLiftSet: Equatable, Hashable {
    let weight: String
    let weightUnit: String
    let reps: String
}

struct StrengthExerciseLiftDay: Identifiable, Equatable, Hashable {
    var id: String { dateKey }
    let dateKey: String
    let sets: [StrengthExerciseLiftSet]
}

enum StrengthExerciseLiftHistory {
    static func normalizedName(_ name: String) -> String {
        name
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    }

    static func matches(itemID: String, name: String, candidateItemID: String, candidateName: String) -> Bool {
        if !itemID.isEmpty { return itemID == candidateItemID }
        let left = normalizedName(name)
        let right = normalizedName(candidateName)
        return !left.isEmpty && left == right
    }

    static func performedSets(from completed: [StrengthCompletedSet]) -> [StrengthExerciseLiftSet] {
        completed.filter(\.isPerformed).map {
            StrengthExerciseLiftSet(weight: $0.weight, weightUnit: $0.weightUnit, reps: $0.reps)
        }
    }

    static func formatSetLine(_ set: StrengthExerciseLiftSet, displayUnit: WeightUnit) -> String {
        guard !set.reps.isEmpty else { return "" }
        guard !set.weight.isEmpty else { return "\(set.reps) reps" }
        let planned = StrengthPlannedSet(weight: set.weight, weightUnit: set.weightUnit, reps: set.reps)
        return "\(planned.displayWeight(in: displayUnit)) \(displayUnit.rawValue) × \(set.reps)"
    }

    static func formatSummary(_ sets: [StrengthExerciseLiftSet], displayUnit: WeightUnit) -> String {
        sets.compactMap { line in
            let formatted = formatSetLine(line, displayUnit: displayUnit)
            return formatted.isEmpty ? nil : formatted
        }
        .joined(separator: ", ")
    }
}
