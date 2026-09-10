import Foundation

/// Disk-backed image store for `FoodEntry` photos.
///
/// Photos used to be stored inline as base64 `Data` inside the `foodEntries`
/// JSON blob in `UserDefaults`. That breaks past ~15-20 photos because iOS
/// silently drops any UserDefaults write >= 4 MiB — `saveEntries()` would
/// appear to succeed while the last-successful snapshot was actually locked
/// in place (phantom adds/deletes on relaunch).
///
/// Now images live as individual JPEGs under
/// `Application Support/fudai-food-images/<uuid>.jpg`, and `FoodEntry`
/// persists only the filename. The encoded entry JSON is tiny — a few
/// hundred bytes per entry — so UserDefaults stays well under its cap.
struct FoodImageStore {
    static let shared = FoodImageStore()

    private let folderName = "fudai-food-images"

    private var folderURL: URL? {
        guard let base = try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ) else { return nil }
        let url = base.appendingPathComponent(folderName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
        return url
    }

    /// Writes `data` to disk under a stable filename derived from `id`.
    /// Returns the filename (not full path) on success.
    @discardableResult
    func store(data: Data, for id: UUID) -> String? {
        store(data: data, filename: "\(id.uuidString).jpg")
    }

    /// Writes an additional image for the same entry without overwriting the
    /// primary `<uuid>.jpg` file.
    @discardableResult
    func store(data: Data, for id: UUID, index: Int) -> String? {
        store(data: data, filename: "\(id.uuidString)-\(index).jpg")
    }

    private func store(data: Data, filename: String) -> String? {
        guard let folderURL else { return nil }
        let url = folderURL.appendingPathComponent(filename)
        do {
            try data.write(to: url, options: .atomic)
            return filename
        } catch {
            return nil
        }
    }

    func filenames() -> [String] {
        guard let folderURL else { return [] }
        return (try? FileManager.default.contentsOfDirectory(atPath: folderURL.path)) ?? []
    }

    @discardableResult
    func restore(data: Data, filename: String) -> String? {
        guard let safe = CloudBackupPolicy.safePhotoName(filename) else { return nil }
        return store(data: data, filename: safe)
    }

    /// Reads the bytes at `filename` (not a full path), or nil if missing.
    func load(filename: String) -> Data? {
        guard let folderURL else { return nil }
        let url = folderURL.appendingPathComponent(filename)
        return try? Data(contentsOf: url)
    }

    /// Best-effort delete. Silent no-op if the file is already gone.
    func delete(filename: String) {
        guard let folderURL else { return }
        let url = folderURL.appendingPathComponent(filename)
        try? FileManager.default.removeItem(at: url)
    }

    /// Wipes the entire image folder (used by Delete All Data).
    func deleteAll() {
        guard let folderURL else { return }
        try? FileManager.default.removeItem(at: folderURL)
    }
}
