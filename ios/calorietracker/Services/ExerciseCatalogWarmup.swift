import Foundation

/// Preloads the bundled workout catalog off the main thread so the first Workouts
/// tab open does not hitch on JSON decode + image lookup table construction.
enum ExerciseCatalogWarmup {
    private static let lock = NSLock()
    private static var didStart = false

    static func startIfNeeded() {
        lock.lock()
        defer { lock.unlock() }
        guard !didStart else { return }
        didStart = true

        Task.detached(priority: .utility) {
            _ = ExerciseLibraryService.shared
            FreeExerciseDBAssetResolver.warmImageLookup()
            FreeExerciseDBAssetResolver.warmVisualManifest()
        }
    }
}
