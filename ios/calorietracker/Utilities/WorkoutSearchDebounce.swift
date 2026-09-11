import Foundation

enum WorkoutSearchDebounce {
    /// Delay before applying a typed search query to the exercise filter.
    static let searchDelayNanoseconds: UInt64 = 175_000_000
    /// Delay before persisting filter state (including search text) to disk.
    static let persistDelayNanoseconds: UInt64 = 400_000_000

    @MainActor
    static func schedule(
        task: inout Task<Void, Never>?,
        delayNanoseconds: UInt64 = searchDelayNanoseconds,
        action: @escaping @MainActor () -> Void
    ) {
        task?.cancel()
        task = Task {
            try? await Task.sleep(nanoseconds: delayNanoseconds)
            guard !Task.isCancelled else { return }
            action()
        }
    }
}
