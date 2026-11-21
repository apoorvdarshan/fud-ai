import SwiftUI
import HealthKit

/// Logic behind onboarding's welcome-back screen for people who used Fud AI
/// before (a reinstall or a data wipe).
enum ReturningUser {
    /// Set just before a welcome-back iCloud restore and read once by the
    /// app's onboarding-completion hook, so the restored settings are kept
    /// instead of being overwritten with new-install defaults.
    static var onboardingCompletedByRestore = false

    /// Read once per launch: a BYOK key left in the Keychain by an earlier install.
    private static let launchHadSavedAPIKey: Bool = AIProvider.allCases.contains {
        $0.requiresAPIKey && AIProviderSettings.apiKey(for: $0) != nil
    }

    /// Whether onboarding opens on the welcome-back screen. Test launches never
    /// read the Keychain; they ask for the screen with `--ui-testing-returning`.
    static var opensWelcomeBack: Bool {
        opensWelcomeBack(
            isRunningTests: SimulatorTestEnvironment.isRunningTests,
            forcedByTest: SimulatorTestEnvironment.forcesReturningUserWelcome,
            hasSavedAPIKey: { launchHadSavedAPIKey }
        )
    }

    static func opensWelcomeBack(isRunningTests: Bool, forcedByTest: Bool, hasSavedAPIKey: () -> Bool) -> Bool {
        isRunningTests ? forcedByTest : hasSavedAPIKey()
    }

    // MARK: - Apple Health import

    /// Profile values read back from Apple Health, before clamping to the wizard's wheels.
    struct HealthPrefill: Equatable {
        var gender: Gender?
        var birthday: Date?
        var heightCm: Double?
        var weightKg: Double?
        var bodyFatFraction: Double?
    }

    struct HealthImport: Equatable {
        var summary: String
        var prefill: HealthPrefill
    }

    struct HealthImportCounts: Equatable {
        var meals = 0
        var weighIns = 0
        var bodyFatReadings = 0
        var workouts = 0

        /// What an import added, given the store counts before and after it.
        static func added(from before: HealthImportCounts, to after: HealthImportCounts) -> HealthImportCounts {
            HealthImportCounts(
                meals: max(0, after.meals - before.meals),
                weighIns: max(0, after.weighIns - before.weighIns),
                bodyFatReadings: max(0, after.bodyFatReadings - before.bodyFatReadings),
                workouts: max(0, after.workouts - before.workouts)
            )
        }
    }

    /// "Imported 120 meals and 45 weigh-ins.", or nil when nothing came back.
    static func importSummary(
        _ counts: HealthImportCounts,
        bundle: Bundle = .main,
        locale: Locale = .current
    ) -> String? {
        var parts: [String] = []
        if counts.meals > 0 {
            parts.append(String(localized: "\(counts.meals) meals", bundle: bundle, locale: locale,
                                comment: "Part of the Apple Health import summary: food log entries brought back."))
        }
        if counts.weighIns > 0 {
            parts.append(String(localized: "\(counts.weighIns) weigh-ins", bundle: bundle, locale: locale,
                                comment: "Part of the Apple Health import summary: weight entries brought back."))
        }
        if counts.bodyFatReadings > 0 {
            parts.append(String(localized: "\(counts.bodyFatReadings) body-fat readings", bundle: bundle, locale: locale,
                                comment: "Part of the Apple Health import summary: body-fat entries brought back."))
        }
        if counts.workouts > 0 {
            parts.append(String(localized: "calories for \(counts.workouts) workouts", bundle: bundle, locale: locale,
                                comment: "Part of the Apple Health import summary: workout calorie burns brought back."))
        }
        guard !parts.isEmpty else { return nil }
        let formatter = ListFormatter()
        formatter.locale = locale
        let list = formatter.string(from: parts) ?? parts.joined(separator: ", ")
        return String(localized: "Imported \(list).", bundle: bundle, locale: locale,
                      comment: "Apple Health import summary. The argument is a list such as “120 meals and 45 weigh-ins”.")
    }

    /// Progress markers that stop the Apple Health imports from running twice. A
    /// restore marks the meal import done, since the backup brought the meals.
    /// Clearing them lets a welcome-back import add what reached Health after
    /// the backup; every import skips what the stores already hold.
    static let healthImportMarkers = [
        "healthKitFoodRecoveryDone", "healthKitWeightBackfillVersion", "healthKitBodyFatBackfillVersion",
    ]

    static func reopenHealthImports(defaults: UserDefaults = .standard) {
        for key in healthImportMarkers { defaults.removeObject(forKey: key) }
    }

    // MARK: - Wizard wheels

    struct HeightWheels: Equatable {
        var cm: Int
        var feet: Int
        var inches: Int
    }

    struct WeightWheels: Equatable {
        var kgWhole: Int
        var kgTenth: Int
        var lbsWhole: Int
        var lbsTenth: Int
    }

    /// Both height layouts, clamped to the wheels: 100–250 cm and 3 ft 0 in – 8 ft 11 in.
    static func heightWheels(cm: Double) -> HeightWheels? {
        guard cm.isFinite, cm > 0 else { return nil }
        let wholeCm = Int(min(max(cm, 100), 250).rounded())
        let totalInches = Int(min(max(cm / 2.54, 36), 107).rounded())
        return HeightWheels(cm: wholeCm, feet: totalInches / 12, inches: totalInches % 12)
    }

    /// Both weight units as whole + tenth wheels, clamped to 30.0–250.9 kg and 60.0–500.9 lbs.
    static func weightWheels(kg: Double) -> WeightWheels? {
        guard kg.isFinite, kg > 0 else { return nil }
        let kgTenths = Int((min(max(kg, 30), 250.9) * 10).rounded())
        let lbsTenths = Int((min(max(kg / 0.453592, 60), 500.9) * 10).rounded())
        return WeightWheels(
            kgWhole: kgTenths / 10, kgTenth: kgTenths % 10,
            lbsWhole: lbsTenths / 10, lbsTenth: lbsTenths % 10
        )
    }

    /// Health stores body fat as a 0–1 fraction; the wheel is whole percents 3–60.
    static func bodyFatWheel(fraction: Double) -> Int? {
        guard fraction.isFinite, fraction > 0 else { return nil }
        return Int(min(max(fraction * 100, 3), 60).rounded())
    }

    /// The birthday picker stops at today.
    static func birthdayWheel(_ date: Date, now: Date = Date()) -> Date {
        min(date, now)
    }

    /// Only male and female map to the wizard; "other" and "not set" keep its choice.
    static func gender(for sex: HKBiologicalSex?) -> Gender? {
        switch sex {
        case .male?: .male
        case .female?: .female
        default: nil
        }
    }
}


/// Onboarding's first screen for returning users. They pick what to bring back
/// (an iCloud backup, what Fud AI saved to Apple Health, or both) and Continue
/// runs the picks in order: the restore first, since it replaces this phone's
/// data, then the Health import, which only adds what the restore didn't bring.
/// Nothing reaches iCloud or Health until the user picks a source.
struct ReturningUserView: View {
    enum Source: Hashable {
        case iCloud, health
    }

    /// Result line of a Health import made earlier in this onboarding.
    let healthImportSummary: String?
    @Binding var hasCompletedOnboarding: Bool
    let onHealthImported: (ReturningUser.HealthImport) -> Void
    /// Continues with the normal setup steps.
    let onStartSetup: () -> Void

    @Environment(CloudBackupService.self) private var backup
    @Environment(HealthKitManager.self) private var healthKitManager
    @Environment(FoodStore.self) private var foodStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(StrengthWorkoutStore.self) private var strengthWorkoutStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Phase {
        case choosing, running, finished
    }

    private enum BackupCheck: Equatable {
        case unchecked, checking, found(Date?), unavailable(String)
    }

    private enum StepState: Equatable {
        case working, done(String), failed(String)
    }

    private enum SecondaryAction {
        case startFresh, tryAgain
    }

    @State private var phase: Phase
    @State private var selection: Set<Source>
    @State private var steps: [Source: StepState]
    @State private var backupCheck: BackupCheck = .unchecked
    /// Set once a restore in this visit succeeds: whether its backup had finished
    /// setup. Kept across Try again, so retrying only Health can't lose it.
    @State private var restoredBackup: Bool?
    @State private var restoredFinishedSetup = false
    @State private var restoreMissedSetup = false
    @State private var appeared = false

    init(
        healthImportSummary: String?,
        hasCompletedOnboarding: Binding<Bool>,
        onHealthImported: @escaping (ReturningUser.HealthImport) -> Void,
        onStartSetup: @escaping () -> Void
    ) {
        self.healthImportSummary = healthImportSummary
        _hasCompletedOnboarding = hasCompletedOnboarding
        self.onHealthImported = onHealthImported
        self.onStartSetup = onStartSetup
        // Back from the setup steps after an import: show its result instead
        // of offering the same import again.
        if let healthImportSummary {
            _phase = State(initialValue: .finished)
            _selection = State(initialValue: [.health])
            _steps = State(initialValue: [.health: .done(healthImportSummary)])
        } else {
            _phase = State(initialValue: .choosing)
            _selection = State(initialValue: [])
            _steps = State(initialValue: [:])
        }
    }

    private var spring: Animation {
        reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.42, dampingFraction: 0.8)
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 28) {
                    hero

                    VStack(spacing: 14) {
                        sourceCard(.iCloud)
                            .entrance(appeared, delay: 0.08, reduceMotion: reduceMotion)
                        sourceCard(.health)
                            .entrance(appeared, delay: 0.14, reduceMotion: reduceMotion)
                    }

                    orderNote
                }
                .padding(.horizontal, 24)
                .padding(.top, 40)
                .padding(.bottom, 20)
            }
            .scrollBounceBehavior(.basedOnSize)

            bottomButtons
                .entrance(appeared, delay: 0.2, reduceMotion: reduceMotion)
        }
        .animation(spring, value: selection)
        .animation(spring, value: phase)
        .animation(spring, value: steps)
        .animation(spring, value: backupCheck)
        .sensoryFeedback(.selection, trigger: selection)
        .sensoryFeedback(trigger: phase) { _, new in new == .finished && anyDone ? .success : nil }
        .onAppear { appeared = true }
    }

    // MARK: - Hero

    private var hero: some View {
        VStack(spacing: 16) {
            Image("onboardingLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 96, height: 96)
                .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("Welcome back")
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text(subtitle)
                    .font(.system(.callout, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
        }
        .entrance(appeared, delay: 0, reduceMotion: reduceMotion)
    }

    private var subtitle: String {
        switch phase {
        case .choosing:
            String(localized: "Pick what to bring back. You can choose both.")
        case .running:
            String(localized: "Bringing your data back…")
        case .finished:
            if restoredFinishedSetup {
                String(localized: "You're all set. Everything is back where you left it.")
            } else if restoreMissedSetup {
                String(localized: "This backup doesn't include a finished setup, so let's finish it now.")
            } else if anyDone {
                String(localized: "A short setup and you're in.")
            } else {
                String(localized: "Nothing came back this time. Try again or start fresh.")
            }
        }
    }

    // MARK: - Source cards

    private func sourceCard(_ source: Source) -> some View {
        let isSelected = selection.contains(source)
        let status = status(for: source)
        return Button { toggle(source) } label: {
            HStack(spacing: 16) {
                Image(systemName: source.symbol)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 52, height: 52)
                    .background(source.gradient, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                VStack(alignment: .leading, spacing: 5) {
                    Text(source.title)
                        .font(.system(.headline, design: .rounded, weight: .semibold))
                        .foregroundStyle(.primary)
                    Text(source.detail)
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let status {
                        statusLine(status)
                            .transition(.opacity.combined(with: .move(edge: .top)))
                    }
                }
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)

                indicator(for: source)
                    .frame(width: 28, height: 28)
            }
            .padding(16)
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))
            .overlay {
                RoundedRectangle(cornerRadius: 16)
                    .strokeBorder(
                        isSelected ? AppColors.calorie : Color.primary.opacity(0.06),
                        lineWidth: isSelected ? 2 : 1
                    )
            }
            .opacity(phase != .choosing && !isSelected && steps[source] == nil ? 0.4 : 1)
        }
        .buttonStyle(PressableCardStyle(reduceMotion: reduceMotion))
        .disabled(phase != .choosing)
        .accessibilityLabel(Text(source.title))
        .accessibilityValue(Text(status?.text ?? ""))
        .accessibilityHint(Text(source.detail))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private struct Status {
        var text: String
        var symbol: String?
        var tint: Color
        var showsProgress = false
    }

    private func status(for source: Source) -> Status? {
        switch steps[source] {
        case .working?:
            let text = source == .iCloud
                ? String(localized: "Restoring…")
                : String(localized: "Importing from Apple Health…")
            return Status(text: text, tint: .secondary, showsProgress: true)
        case .done(let text)?:
            return Status(text: text, symbol: "checkmark", tint: .green)
        case .failed(let text)?:
            return Status(text: text, symbol: "exclamationmark.triangle.fill", tint: .orange)
        case nil:
            break
        }
        guard source == .iCloud else { return nil }
        switch backupCheck {
        case .unchecked:
            return nil
        case .checking:
            return Status(text: String(localized: "Checking iCloud…"), tint: .secondary, showsProgress: true)
        case .found(let date?):
            let when = date.formatted(date: .abbreviated, time: .shortened)
            return Status(
                text: String(localized: "Backup from \(when)",
                             comment: "Shown under Restore from iCloud once a backup is found. The argument is its date and time."),
                symbol: "clock.arrow.circlepath", tint: AppColors.calorie
            )
        case .found(nil):
            return Status(text: String(localized: "Backup found"), symbol: "checkmark.icloud", tint: AppColors.calorie)
        case .unavailable(let text):
            return Status(text: text, symbol: "exclamationmark.triangle.fill", tint: .orange)
        }
    }

    private func statusLine(_ status: Status) -> some View {
        HStack(alignment: .center, spacing: 6) {
            if status.showsProgress {
                ProgressView().controlSize(.mini)
            } else if let symbol = status.symbol {
                Image(systemName: symbol).font(.caption.weight(.bold))
            }
            Text(status.text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.system(.footnote, design: .rounded, weight: .semibold))
        .foregroundStyle(status.tint)
        .padding(.top, 4)
    }

    @ViewBuilder
    private func indicator(for source: Source) -> some View {
        switch steps[source] {
        case .working?:
            ProgressView()
        case .done?:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 26, weight: .semibold))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, .green)
                .transition(.scale.combined(with: .opacity))
        case .failed?:
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 26, weight: .semibold))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, .orange)
                .transition(.scale.combined(with: .opacity))
        case nil:
            let isSelected = selection.contains(source)
            ZStack {
                Circle()
                    .strokeBorder(Color.primary.opacity(isSelected ? 0 : 0.25), lineWidth: 2)
                Circle()
                    .fill(AppColors.calorie)
                    .scaleEffect(isSelected ? 1 : 0.3)
                    .opacity(isSelected ? 1 : 0)
                Image(systemName: "checkmark")
                    .font(.system(size: 12, weight: .heavy))
                    .foregroundStyle(.white)
                    .scaleEffect(isSelected ? 1 : 0.3)
                    .opacity(isSelected ? 1 : 0)
            }
            .opacity(phase == .choosing ? 1 : 0)
        }
    }

    /// Explains the order once both sources are picked.
    @ViewBuilder
    private var orderNote: some View {
        if phase == .choosing && selection.count == 2 {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "arrow.triangle.merge")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                Text("iCloud restores first, then Apple Health adds only what's missing. Nothing gets overwritten.")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.calorie.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
            .accessibilityElement(children: .combine)
            .transition(.opacity.combined(with: .scale(scale: 0.96)))
        }
    }

    // MARK: - Buttons

    private var bottomButtons: some View {
        VStack(spacing: 6) {
            Button(action: primaryAction) {
                ZStack {
                    if phase == .running {
                        ProgressView().tint(.white)
                    } else {
                        Text(primaryTitle)
                    }
                }
                .font(.system(.headline, design: .rounded, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 22)
                .padding(.vertical, 16)
                .background(LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing))
                .foregroundStyle(.white)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .opacity(phase == .choosing && !canContinue ? 0.4 : 1)
            }
            .disabled(phase == .running || (phase == .choosing && !canContinue))
            .accessibilityLabel(Text(phase == .running ? String(localized: "Bringing your data back…") : primaryTitle))

            let secondary = secondaryAction
            Button {
                switch secondary {
                case .tryAgain?: tryAgain()
                case .startFresh?, nil: onStartSetup()
                }
            } label: {
                Text(secondary == .tryAgain ? String(localized: "Try again") : String(localized: "Start fresh"))
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            // Kept in place while hidden so the main button doesn't jump.
            .opacity(secondary == nil ? 0 : 1)
            .disabled(secondary == nil)
            .accessibilityHidden(secondary == nil)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 26)
    }

    private var canContinue: Bool {
        !selection.isEmpty && !(selection.contains(.iCloud) && backupCheck == .checking)
    }

    private var anyDone: Bool {
        steps.values.contains { if case .done = $0 { true } else { false } }
    }

    private var anyFailed: Bool {
        steps.values.contains { if case .failed = $0 { true } else { false } }
    }

    private var primaryTitle: String {
        switch phase {
        case .choosing, .running: String(localized: "Continue")
        case .finished:
            if restoredFinishedSetup {
                String(localized: "Open Fud AI")
            } else if anyDone {
                String(localized: "Continue setup")
            } else {
                String(localized: "Try again")
            }
        }
    }

    private var secondaryAction: SecondaryAction? {
        switch phase {
        case .choosing: .startFresh
        case .running: nil
        case .finished: anyDone ? (anyFailed ? .tryAgain : nil) : .startFresh
        }
    }

    // MARK: - Actions

    private func primaryAction() {
        switch phase {
        case .choosing:
            Task { await run() }
        case .running:
            break
        case .finished:
            if restoredFinishedSetup {
                // The restore held hasCompletedOnboarding back; opening the app
                // now keeps the backup's settings instead of new-install defaults.
                ReturningUser.onboardingCompletedByRestore = true
                hasCompletedOnboarding = true
            } else if anyDone {
                onStartSetup()
            } else {
                tryAgain()
            }
        }
    }

    private func toggle(_ source: Source) {
        guard phase == .choosing else { return }
        // A step that already worked stays done; Try again reruns only what failed.
        if case .done? = steps[source] { return }
        if selection.remove(source) != nil { return }
        selection.insert(source)
        if source == .iCloud { Task { await checkBackup() } }
    }

    /// Back to picking with only the failed steps picked. A restore replaces what an
    /// earlier Health import added, so retrying the restore reruns Health too.
    private func tryAgain() {
        var retry = Set(steps.compactMap { source, state in
            if case .failed = state { source } else { nil }
        })
        if retry.contains(.iCloud), case .done? = steps[.health] { retry.insert(.health) }
        steps = steps.filter { !retry.contains($0.key) }
        selection = retry
        restoredFinishedSetup = false
        restoreMissedSetup = false
        phase = .choosing
    }

    private func announce(_ text: String) {
        AccessibilityNotification.Announcement(text).post()
    }

    /// Looks for a backup as soon as iCloud is picked, so its date shows before
    /// anything is restored. A missing backup unpicks the card.
    private func checkBackup() async {
        switch backupCheck {
        case .checking, .found: return
        case .unchecked, .unavailable: break
        }
        backupCheck = .checking
        switch await backup.probeForBackup() {
        case .found(let date):
            backupCheck = .found(date)
        case .notFound:
            backupCheck = .unavailable(String(localized: "No iCloud backup found for this Apple ID."))
            selection.remove(.iCloud)
        case .couldNotCheck(let text):
            backupCheck = .unavailable(text)
            selection.remove(.iCloud)
        }
        if let text = status(for: .iCloud)?.text { announce(text) }
    }

    private func run() async {
        let plan = selection
        guard !plan.isEmpty else { return }
        phase = .running
        steps = steps.filter { !plan.contains($0.key) }
        announce(String(localized: "Bringing your data back…"))

        var restored = restoredBackup != nil
        var finishedSetup = restoredBackup ?? false
        if plan.contains(.iCloud) {
            steps[.iCloud] = .working
            do {
                let backupFinishedSetup = try await backup.restoreNow(holdingOnboarding: true)
                restored = true
                // These ran at launch on the empty defaults; the backup brings its own markers.
                UnitPreferenceMigration.runIfNeeded()
                AIProviderSettings.migrateLegacyGeminiModelsIfNeeded()
                SpeechSettings.migrateMatchingPrimaryProviderIfNeeded()
                AIProviderSettings.migrateFallbackBaseURLsIfNeeded()
                // A backup taken mid-setup, after a wipe, or by a newer app has
                // no usable profile, so it must not skip setup.
                finishedSetup = backupFinishedSetup && UserProfile.load() != nil
                restoredBackup = finishedSetup
                steps[.iCloud] = .done(restoredText)
            } catch {
                steps[.iCloud] = .failed(CloudBackupService.userMessage(for: error))
            }
        }

        if plan.contains(.health) {
            steps[.health] = .working
            // The restore marked the meal import done, but Health can hold
            // meals and readings saved after the backup.
            if restored { ReturningUser.reopenHealthImports() }
            switch await importFromHealth(afterRestore: restored) {
            case .success(let result):
                steps[.health] = .done(result.summary)
                if !finishedSetup { onHealthImported(result) }
            case .failure(let failure):
                steps[.health] = .failed(failure.message)
            }
        }

        restoredFinishedSetup = finishedSetup
        restoreMissedSetup = restored && !finishedSetup
        phase = .finished
        announce(subtitle)
    }

    private var restoredText: String {
        guard case .found(let date?) = backupCheck else { return String(localized: "Backup restored.") }
        let when = date.formatted(date: .abbreviated, time: .shortened)
        return String(localized: "Restored your backup from \(when).",
                      comment: "Shown once an iCloud backup is restored. The argument is the backup's date and time.")
    }

    private var storeCounts: ReturningUser.HealthImportCounts {
        ReturningUser.HealthImportCounts(
            meals: foodStore.entries.count,
            weighIns: weightStore.entries.count,
            bodyFatReadings: bodyFatStore.entries.count,
            workouts: strengthWorkoutStore.workoutBurnSessions.count
        )
    }

    private struct HealthImportFailure: Error {
        let message: String
    }

    /// After a restore, Fud AI's own weight and body-fat readings come back too
    /// when the restored history lacks their ids (saved after the backup).
    private func importFromHealth(afterRestore: Bool) async -> Result<ReturningUser.HealthImport, HealthImportFailure> {
        guard await healthKitManager.requestAuthorization() else {
            return .failure(HealthImportFailure(
                message: String(localized: "Couldn't connect to Apple Health. You can try again later in Settings.")
            ))
        }
        // The same switch onboarding's Apple Health step sets; it keeps Health in sync afterwards.
        UserDefaults.standard.set(true, forKey: "healthKitEnabled")

        let before = storeCounts
        // Each import skips what the stores already hold: meals and Fud AI's own
        // readings by id, other apps' readings by day and value. Without a
        // restore, Fud AI's own readings only come back into an empty history,
        // and onboarding seeds a first entry when it finishes.
        let imports: [Task<Void, Never>?] = [
            healthKitManager.restoreFoodEntriesFromHealthKitIfNeeded(
                existingIDs: { [foodStore] in Set(foodStore.entries.map(\.id)).union(foodStore.locallyDeletedIDs) },
                importBatch: { [foodStore] entries in foodStore.mergeWithCloudEntries(entries) }
            ),
            healthKitManager.backfillWeightFromHealthKitIfNeeded { [weightStore] samples in
                weightStore.importHealthSamples(samples, restoreOwnSamplesIfEmpty: true, addingMissingOwnSamples: afterRestore)
            },
            healthKitManager.backfillBodyFatFromHealthKitIfNeeded(
                addingMissingOwnSamples: afterRestore,
                existing: { [bodyFatStore] in bodyFatStore.entries },
                importBatch: { [bodyFatStore] entries in bodyFatStore.importExternalEntries(entries) }
            ),
            healthKitManager.synchronizeWorkoutBurnsWithHealthKit(
                existing: { [strengthWorkoutStore] in strengthWorkoutStore.workoutBurnSessions },
                mergeBatch: { [strengthWorkoutStore] sessions in
                    strengthWorkoutStore.importWorkoutBurnSessions(sessions)
                }
            ),
        ]
        for task in imports { await task?.value }
        // A failed read would otherwise look like an empty import. Imports are
        // deduplicated, so Try again is safe. Workout burns aren't checked: they
        // sync again every time the app opens.
        guard healthKitManager.finishedHistoryImports else {
            return .failure(HealthImportFailure(
                message: String(localized: "Couldn't read everything from Apple Health. Try again.")
            ))
        }
        let added = ReturningUser.HealthImportCounts.added(from: before, to: storeCounts)

        let latest = await healthKitManager.fetchLatestBodyMeasurements()
        let prefill = ReturningUser.HealthPrefill(
            gender: ReturningUser.gender(for: latest.sex),
            birthday: latest.dob,
            heightCm: latest.height,
            weightKg: latest.weight,
            bodyFatFraction: latest.bodyFat
        )
        let summary = ReturningUser.importSummary(added)
            ?? String(localized: "Nothing to import from Apple Health yet.")
        return .success(ReturningUser.HealthImport(summary: summary, prefill: prefill))
    }
}

private extension ReturningUserView.Source {
    var title: String {
        switch self {
        case .iCloud: String(localized: "Restore from iCloud")
        case .health: String(localized: "Import from Apple Health")
        }
    }

    var detail: String {
        switch self {
        case .iCloud: String(localized: "Diary, photos, workouts, and settings from your last backup.")
        case .health: String(localized: "Meals, weight, body fat, and workout calories Fud AI saved to Health.")
        }
    }

    var symbol: String {
        switch self {
        case .iCloud: "icloud.and.arrow.down.fill"
        case .health: "heart.fill"
        }
    }

    /// iCloud blue and Health pink, the colors people know these services by.
    var colors: [Color] {
        switch self {
        case .iCloud: [Color(red: 0.38, green: 0.70, blue: 1.0), Color(red: 0.10, green: 0.45, blue: 0.96)]
        case .health: [Color(red: 1.0, green: 0.45, blue: 0.52), Color(red: 0.96, green: 0.16, blue: 0.33)]
        }
    }

    var gradient: LinearGradient {
        LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// Cards sink slightly while pressed.
private struct PressableCardStyle: ButtonStyle {
    let reduceMotion: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

private extension View {
    /// The screen's one entrance: each part rises in shortly after the last.
    func entrance(_ shown: Bool, delay: Double, reduceMotion: Bool) -> some View {
        opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 18)
            .animation(
                reduceMotion ? .easeOut(duration: 0.2) : .spring(response: 0.6, dampingFraction: 0.85).delay(delay),
                value: shown
            )
    }
}
