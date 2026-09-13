import Foundation
import os

/// Outcome of reading a JSON blob out of `UserDefaults`.
///
/// The whole point of this type is to keep "the key was never written" and
/// "the key holds bytes we cannot read" apart. Collapsing both into an empty
/// array is how a single bad byte used to erase years of diary history: the
/// store loaded `[]`, the next `addEntry` saved `[newEntry]`, and the original
/// blob was gone.
enum PersistedBlobLoad<Value> {
    /// Nothing has ever been stored under this key — a genuinely empty store.
    case missing
    /// Decoded successfully. `droppedElements` is non-zero when individual
    /// rows were unreadable and skipped; the raw blob was backed up first so
    /// those rows are still recoverable.
    case decoded(Value, droppedElements: Int)
    /// Nothing usable could be decoded. The raw blob has been (or will be)
    /// copied aside and the guard refuses to overwrite it until that copy exists.
    case corrupt
}

/// Guards one `UserDefaults` JSON blob against the decode-failure-wipes-data
/// bug class.
///
/// * Element-wise decoding: a list with one unreadable row loses that row, not
///   the whole list.
/// * Corrupt or partially readable blobs are copied to
///   `<backupDirectory>/<key>.corrupt-<unix timestamp>.json` before anything
///   is written over them.
/// * If the backup could not be written, every `save`/`remove` is refused
///   (returns `false`) so the unreadable bytes stay in place for a later
///   recovery attempt instead of being replaced by a fresh empty list.
/// * Before every `save`/`remove` the live `UserDefaults` value is re-read.
///   If something else (widget, extension, restore) replaced it since the
///   guard last looked, the new bytes are decode-checked and quarantined the
///   same way, so a corrupt blob written behind the store's back is never
///   overwritten on the strength of a stale "all good" from the last load.
/// * A `save`/`remove` issued before any load is treated the same way: if the
///   key already holds bytes nobody has decoded, they are copied aside first
///   (and the write refused if that copy fails) instead of being replaced
///   blind.
final class PersistedBlobGuard {
    struct CorruptBackup: Equatable {
        let key: String
        let url: URL
        let date: Date
    }

    let key: String
    private let defaults: UserDefaults
    private let backupDirectory: URL
    private let logger = Logger(subsystem: "ai.fud.calorietracker", category: "Persistence")

    /// Raw bytes that failed to decode and have not been copied aside yet.
    /// Non-nil means writes are blocked.
    private var pendingCorruptBlob: Data?
    /// Hashes of blobs already backed up in this process, so repeated reloads
    /// of the same corrupt bytes don't spawn a new backup file each time.
    private var backedUpBlobHashes: Set<Int> = []
    private(set) var backups: [CorruptBackup] = []

    /// Raw bytes as of the guard's last load/save/remove (`nil` = key absent).
    /// Any difference at write time means an external writer touched the key.
    private var lastObservedBlob: Data?
    private var hasObservedBlob = false
    /// Decode check for the type this key holds, captured on load so an
    /// externally written blob can be validated before it is replaced.
    private var isFullyReadable: ((Data) -> Bool)?

    init(defaults: UserDefaults, key: String, backupDirectory: URL? = nil) {
        self.defaults = defaults
        self.key = key
        self.backupDirectory = backupDirectory ?? Self.defaultBackupDirectory()
    }

    /// True while a corrupt blob sits in `defaults` without a backup copy.
    /// Callers should refuse mutations rather than let them silently vanish.
    var isWriteBlocked: Bool { pendingCorruptBlob != nil }

    static func defaultBackupDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("CorruptDataBackups", isDirectory: true)
    }

    // MARK: - Loading

    /// Decodes a JSON array. Falls back to per-element decoding so a single bad
    /// row is dropped rather than failing the whole list.
    func loadList<Element: Decodable>(
        _ type: Element.Type,
        decoder: JSONDecoder = JSONDecoder()
    ) -> PersistedBlobLoad<[Element]> {
        isFullyReadable = { (try? decoder.decode([Element].self, from: $0)) != nil }
        guard let raw = observeCurrentBlob() else { return .missing }
        guard case .data(let data) = raw else {
            quarantine(raw.bytes, reason: "value under '\(key)' is not Data")
            return .corrupt
        }

        if let decoded = try? decoder.decode([Element].self, from: data) {
            pendingCorruptBlob = nil
            return .decoded(decoded, droppedElements: 0)
        }

        if let lenient = Self.decodeListLeniently(Element.self, from: data, decoder: decoder) {
            quarantine(data, reason: "\(lenient.dropped) of \(lenient.items.count + lenient.dropped) rows under '\(key)' were unreadable")
            return .decoded(lenient.items, droppedElements: lenient.dropped)
        }

        quarantine(data, reason: "blob under '\(key)' could not be decoded")
        return .corrupt
    }

    /// Decodes a single JSON value (e.g. a profile or a versioned state struct).
    func loadValue<Value: Decodable>(
        _ type: Value.Type,
        decoder: JSONDecoder = JSONDecoder()
    ) -> PersistedBlobLoad<Value> {
        isFullyReadable = { (try? decoder.decode(Value.self, from: $0)) != nil }
        guard let raw = observeCurrentBlob() else { return .missing }
        guard case .data(let data) = raw else {
            quarantine(raw.bytes, reason: "value under '\(key)' is not Data")
            return .corrupt
        }
        if let decoded = try? decoder.decode(Value.self, from: data) {
            pendingCorruptBlob = nil
            return .decoded(decoded, droppedElements: 0)
        }
        quarantine(data, reason: "blob under '\(key)' could not be decoded")
        return .corrupt
    }

    /// Element-wise decode of a JSON array: rows that fail to decode are
    /// dropped instead of failing the whole list. Returns `nil` when the data
    /// is not an array or no row could be recovered. Pure — nothing is backed
    /// up or quarantined — so it is safe for one-off reads outside a store.
    static func decodeListLeniently<Element: Decodable>(
        _ type: Element.Type,
        from data: Data,
        decoder: JSONDecoder = JSONDecoder()
    ) -> (items: [Element], dropped: Int)? {
        if let decoded = try? decoder.decode([Element].self, from: data) {
            return (decoded, 0)
        }
        guard let lenient = try? decoder.decode([LenientElement<Element>].self, from: data) else { return nil }
        let recovered = lenient.compactMap(\.value)
        guard !recovered.isEmpty else { return nil }
        return (recovered, lenient.count - recovered.count)
    }

    /// Treats whatever is currently stored as unreadable (e.g. a persisted
    /// schema version this build does not understand) so it is backed up and
    /// protected from being overwritten until the copy exists.
    func quarantineCurrentBlob(reason: String) {
        guard let raw = observeCurrentBlob() else { return }
        quarantine(raw.bytes, reason: reason)
    }

    // MARK: - Writing

    /// Encodes and stores `value`. Returns `false` — without touching
    /// `defaults` — when encoding fails or a corrupt blob has not been backed
    /// up yet.
    @discardableResult
    func save<Value: Encodable>(_ value: Value, encoder: JSONEncoder = JSONEncoder()) -> Bool {
        guard ensureCurrentBlobIsSafeToReplace() else { return false }
        guard let data = try? encoder.encode(value) else {
            logger.error("Refusing to persist '\(self.key, privacy: .public)': encoding failed")
            return false
        }
        defaults.set(data, forKey: key)
        lastObservedBlob = data
        hasObservedBlob = true
        return true
    }

    /// Removes the key. Refused (returns `false`) while a corrupt blob is
    /// still waiting for its backup copy.
    @discardableResult
    func remove() -> Bool {
        guard ensureCurrentBlobIsSafeToReplace() else { return false }
        defaults.removeObject(forKey: key)
        lastObservedBlob = nil
        hasObservedBlob = true
        return true
    }

    // MARK: - Internals

    private enum RawBlob {
        case data(Data)
        case other(Any)

        var bytes: Data {
            switch self {
            case .data(let data): return data
            case .other(let value): return Data(String(describing: value).utf8)
            }
        }
    }

    private func rawBlob() -> RawBlob? {
        guard let object = defaults.object(forKey: key) else { return nil }
        if let data = object as? Data { return .data(data) }
        return .other(object)
    }

    /// Reads the live value and records it as the baseline for external-change detection.
    private func observeCurrentBlob() -> RawBlob? {
        let raw = rawBlob()
        lastObservedBlob = raw?.bytes
        hasObservedBlob = true
        return raw
    }

    /// Re-reads `defaults` so a blob written by another process since the
    /// last load is decode-checked (and quarantined if unreadable) before it
    /// is overwritten, then makes sure any quarantined bytes have a backup.
    private func ensureCurrentBlobIsSafeToReplace() -> Bool {
        reconcileExternalChange()
        return ensureCorruptBlobIsBackedUp()
    }

    private func reconcileExternalChange() {
        let current = rawBlob()
        let currentBytes = current?.bytes
        if hasObservedBlob, currentBytes == lastObservedBlob { return }
        lastObservedBlob = currentBytes
        hasObservedBlob = true
        switch current {
        case .none:
            break
        case .some(.other):
            quarantine(currentBytes ?? Data(), reason: "value under '\(key)' was externally replaced with non-Data")
        case .some(.data(let data)):
            guard let isFullyReadable else {
                // Never loaded through this guard, so there is no decoder to
                // vouch for the bytes. Copy them aside rather than blindly
                // overwrite what might be the only copy of the user's data.
                quarantine(data, reason: "blob under '\(key)' would be replaced without ever having been decoded")
                return
            }
            if !isFullyReadable(data) {
                quarantine(data, reason: "blob under '\(key)' was externally replaced with unreadable data")
            }
        }
    }

    private func quarantine(_ data: Data, reason: String) {
        logger.error("Preserving unreadable data for '\(self.key, privacy: .public)': \(reason, privacy: .public)")
        if backedUpBlobHashes.contains(data.hashValue) {
            pendingCorruptBlob = nil
            return
        }
        pendingCorruptBlob = data
        _ = ensureCorruptBlobIsBackedUp()
    }

    private func ensureCorruptBlobIsBackedUp() -> Bool {
        guard let blob = pendingCorruptBlob else { return true }
        guard let url = writeBackup(blob) else {
            logger.fault("Could not back up corrupt blob for '\(self.key, privacy: .public)'; refusing writes")
            return false
        }
        backedUpBlobHashes.insert(blob.hashValue)
        backups.append(CorruptBackup(key: key, url: url, date: .now))
        pendingCorruptBlob = nil
        logger.notice("Backed up corrupt blob for '\(self.key, privacy: .public)' to \(url.lastPathComponent, privacy: .public)")
        return true
    }

    private func writeBackup(_ blob: Data) -> URL? {
        let fileManager = FileManager.default
        do {
            try fileManager.createDirectory(at: backupDirectory, withIntermediateDirectories: true)
            let stamp = Int(Date.now.timeIntervalSince1970 * 1000)
            var url = backupDirectory.appendingPathComponent("\(key).corrupt-\(stamp).json")
            var attempt = 0
            while fileManager.fileExists(atPath: url.path) {
                attempt += 1
                url = backupDirectory.appendingPathComponent("\(key).corrupt-\(stamp)-\(attempt).json")
            }
            try blob.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }
}

/// Wraps an element so a decode failure yields `nil` instead of aborting the
/// surrounding array decode.
private struct LenientElement<Element: Decodable>: Decodable {
    let value: Element?

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        value = try? container.decode(Element.self)
    }
}
