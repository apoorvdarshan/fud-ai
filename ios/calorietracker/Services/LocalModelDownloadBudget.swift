import Foundation

/// Shared admission control for opt-in model downloads on app storage. Hold the
/// full reservation until verification finishes; this deliberately errs on the
/// side of preserving space if another download is already writing to disk.
@MainActor
final class LocalModelDownloadBudget {
    static let shared = LocalModelDownloadBudget()
    private var reservations: [UUID: Int64] = [:]

    func unreservedBytes(availableBytes: Int64) -> Int64 {
        reservations.values.reduce(max(availableBytes, 0)) { available, reserved in
            max(available - reserved, 0)
        }
    }

    func reserve(requiredBytes: Int64, availableBytes: Int64) -> UUID? {
        guard requiredBytes > 0, requiredBytes <= unreservedBytes(availableBytes: availableBytes) else { return nil }
        let id = UUID()
        reservations[id] = requiredBytes
        return id
    }

    func release(_ id: UUID) {
        reservations.removeValue(forKey: id)
    }
}
