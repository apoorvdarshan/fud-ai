import Foundation

/// Isolates simulator test launches from background account/network work.
/// These switches cannot reset data or alter startup on a device or Release build.
enum SimulatorTestEnvironment {
    static var isRunningTests: Bool {
        #if DEBUG && targetEnvironment(simulator)
        let environment = ProcessInfo.processInfo.environment
        return CommandLine.arguments.contains("--ui-testing")
            || environment["FUDAI_TESTING"] == "1"
            || environment["XCTestConfigurationFilePath"] != nil
            || environment["XCTestBundlePath"] != nil
            || environment["XCTestSessionIdentifier"] != nil
            || NSClassFromString("XCTestCase") != nil
        #else
        return false
        #endif
    }

    /// `--ui-testing-returning` opens onboarding on the welcome-back screen,
    /// which tests never reach otherwise (they skip the Keychain check).
    static var forcesReturningUserWelcome: Bool {
        #if DEBUG && targetEnvironment(simulator)
        return CommandLine.arguments.contains("--ui-testing-returning")
        #else
        return false
        #endif
    }

    /// Called before App storage properties initialize. Static initialization
    /// ensures a subsequent App value cannot erase state created by a test.
    static func prepare() {
        #if DEBUG && targetEnvironment(simulator)
        _ = preparation
        #endif
    }

    #if DEBUG && targetEnvironment(simulator)
    private static let preparation: Void = {
        guard CommandLine.arguments.contains("--ui-testing") else { return }
        let defaults = UserDefaults.standard
        if CommandLine.arguments.contains("--ui-testing-reset") {
            // Never touch release defaults, shared containers, files, or Keychain.
            let identifier = "com.apoorvdarshan.calorietracker.debug"
            precondition(Bundle.main.bundleIdentifier == identifier,
                         "UI test reset requires the dedicated Debug app")
            defaults.removePersistentDomain(forName: identifier)
        }

        // UI tests exercise local app flows without borrowing a developer's
        // Health, notification, cloud, or paid account configuration.
        defaults.set(false, forKey: "healthKitEnabled")
        defaults.set(false, forKey: "notificationsEnabled")
        defaults.set(false, forKey: CloudBackupService.enabledKey)
        defaults.set(false, forKey: AdaptiveGoalSettings.enabledKey)
        defaults.set(AIMode.byok.rawValue, forKey: "aiAccessMode")

        if CommandLine.arguments.contains("--ui-testing-onboarded") {
            if UserProfile.load() == nil {
                UserProfile.default.save()
            }
            defaults.set(true, forKey: "hasCompletedOnboarding")
        }
        if CommandLine.arguments.contains("--ui-testing-workout-set") {
            let item = ExerciseLibraryItem(id: "ui-test-bench", name: "Bench press", category: "strength")
            let store = StrengthWorkoutStore()
            _ = store.addPlannedExercise(StrengthPlannedExercise(item: item), on: .now)
        }
    }()
    #endif
}
