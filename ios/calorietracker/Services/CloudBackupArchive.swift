import Foundation
import CryptoKit

enum CloudBackupPolicy {
    static let format = "fudai-cloud-backup"
    static let version = 1
    static let payloadName = "backup.json"
    static let photosDirectory = "photos/"
    static let minAutoBackupInterval: TimeInterval = 15 * 60

    static let excludedKeys: Set<String> = [
        "healthKitFoodRecoveryDone",
        "healthKitNutritionBackfillVersion",
        "healthKitWeightBackfillVersion",
        "healthKitBodyFatBackfillVersion",
        "healthKitWorkoutBurnDeletionTombstones",
        "healthKitTypesVersion",
        "healthKitAuthVersion",
        "lastNotifiedAppUpdateVersion",
        "weeklyChallengeBearerToken",
        // Local analysis timing history (#635). It is measured on this device only and
        // must not leave it, so it is never part of a cloud backup.
        "aiAnalysisDurations",
    ]

    static func include(_ key: String) -> Bool {
        if key == "healthKitEnabled" { return true }
        if excludedKeys.contains(key) { return false }
        if key.hasPrefix("healthKit") { return false }
        if key.hasPrefix("Apple") || key.hasPrefix("NS") || key.hasPrefix("com.apple") { return false }
        if key.hasPrefix("AK") { return false }
        return true
    }

    /// Longest photo file name a backup carries; real ones are a UUID and an extension.
    nonisolated static let maxPhotoNameLength = 128

    nonisolated private static let photoNameCharacters = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-"
    )

    /// `name` if it is a plain photo file name, else nil: ASCII letters, digits,
    /// ".", "_" and "-" only, a photo extension, at most `maxPhotoNameLength`
    /// characters, no leading dot and no "..". A name with a directory part,
    /// relative or absolute, is rejected rather than shortened. Nonisolated so
    /// Compress Photos can apply the same rule off the main actor.
    nonisolated static func safePhotoName(_ name: String) -> String? {
        guard !name.isEmpty,
              name.utf8.count <= maxPhotoNameLength,
              !name.hasPrefix("."),
              !name.contains(".."),
              name.unicodeScalars.allSatisfy({ photoNameCharacters.contains($0) })
        else { return nil }
        let ext = (name as NSString).pathExtension.lowercased()
        guard ["jpg", "jpeg", "png", "webp"].contains(ext),
              (name as NSString).lastPathComponent == name
        else { return nil }
        return name
    }

    /// The file name a restore writes for archive entry `entryName`, or nil if
    /// the entry must be skipped. Only `photos/<plain photo name>` qualifies,
    /// so an entry name can never point a restore outside the photo folder.
    static func restorablePhotoName(_ entryName: String) -> String? {
        guard entryName.hasPrefix(photosDirectory) else { return nil }
        return safePhotoName(String(entryName.dropFirst(photosDirectory.count)))
    }
}

struct CloudBackupDocument: Codable {
    var format: String
    var formatVersion: Int
    var exportedAt: String
    var appVersion: String
    var platform: String
    var contentSha256: String
    var payload: CloudBackupPayload

    enum CodingKeys: String, CodingKey {
        case format
        case formatVersion = "format_version"
        case exportedAt = "exported_at"
        case appVersion = "app_version"
        case platform
        case contentSha256 = "content_sha256"
        case payload
    }
}

struct CloudBackupPayload: Codable {
    var values: [String: CloudBackupValue]
}

struct CloudBackupValue: Codable {
    var t: String
    var b: Bool? = nil
    var i: Int? = nil
    var s: String? = nil
    var d: String? = nil
    var ss: [String]? = nil

    static func bool(_ v: Bool) -> CloudBackupValue { CloudBackupValue(t: "b", b: v) }
    static func int(_ v: Int) -> CloudBackupValue { CloudBackupValue(t: "i", i: v) }
    static func string(_ v: String) -> CloudBackupValue { CloudBackupValue(t: "s", s: v) }
    static func data(_ v: Data) -> CloudBackupValue { CloudBackupValue(t: "d", d: v.base64EncodedString()) }
    static func stringArray(_ v: [String]) -> CloudBackupValue { CloudBackupValue(t: "ss", ss: v) }
}

/// A photo on disk. Only its size is read up front; its bytes are loaded one
/// photo at a time while the archive is written.
struct CloudBackupPhotoFile {
    var url: URL
    var size: Int
}

enum CloudBackupArchive {
    static func contentHash(values: [String: CloudBackupValue], photos: [String: Data]) -> String {
        contentHash(values: values, photoSizes: photos.mapValues(\.count))
    }

    /// Photos count by name and byte size only, so the hash never needs photo bytes.
    static func contentHash(values: [String: CloudBackupValue], photoSizes: [String: Int]) -> String {
        var canonical = ""
        for key in values.keys.sorted() {
            guard let value = values[key] else { continue }
            canonical += "\(key)="
            switch value.t {
            case "b": canonical += "b:\(value.b.map { $0 ? "true" : "false" } ?? "")"
            case "i": canonical += "i:\(value.i.map(String.init) ?? "")"
            case "s": canonical += "s:\(value.s ?? "")"
            case "d": canonical += "d:\(value.d ?? "")"
            case "ss": canonical += "ss:\((value.ss ?? []).sorted().joined(separator: ","))"
            default: canonical += value.t
            }
            canonical += "\n"
        }
        for name in photoSizes.keys.sorted() {
            canonical += "photo:\(name):\(photoSizes[name] ?? 0)\n"
        }
        return sha256(Data(canonical.utf8))
    }

    /// Writes the backup zip to `destination`: backup.json, then photos/<name>
    /// sorted by name, loading one photo at a time so large photo libraries
    /// never sit in memory together.
    static func pack(
        to destination: URL,
        values: [String: CloudBackupValue],
        photos: [String: CloudBackupPhotoFile],
        exportedAt: String,
        appVersion: String
    ) throws {
        let filtered = values.filter { CloudBackupPolicy.include($0.key) && !CloudBackupPolicy.excludedKeys.contains($0.key) }
        let safePhotos = Dictionary(photos.compactMap { name, file in
            CloudBackupPolicy.safePhotoName(name).map { ($0, file) }
        }, uniquingKeysWith: { first, _ in first })
        let document = CloudBackupDocument(
            format: CloudBackupPolicy.format,
            formatVersion: CloudBackupPolicy.version,
            exportedAt: exportedAt,
            appVersion: appVersion,
            platform: "ios",
            contentSha256: contentHash(values: filtered, photoSizes: safePhotos.mapValues(\.size)),
            payload: CloudBackupPayload(values: filtered)
        )
        let payload = try JSONEncoder().encode(document)
        var zip = try CloudBackupZip.Writer(url: destination)
        defer { zip.close() }
        try zip.add(name: CloudBackupPolicy.payloadName, data: payload)
        for (name, file) in safePhotos.sorted(by: { $0.key < $1.key }) {
            try autoreleasepool {
                // A snapshotted photo that can no longer be read fails the backup.
                // The hash already lists it, so a backup saved without it would
                // count as unchanged and never be retried.
                let data = try Data(contentsOf: file.url)
                try zip.add(name: CloudBackupPolicy.photosDirectory + name, data: data)
            }
        }
        try zip.finish()
    }

    /// Reads a backup archive from disk. backup.json is validated up front;
    /// photos are loaded one at a time by `forEachPhoto`.
    final class Reader {
        let document: CloudBackupDocument
        private let zip: CloudBackupZip.Reader

        init(url: URL) throws {
            zip = try CloudBackupZip.Reader(url: url)
            guard let payload = try zip.data(named: CloudBackupPolicy.payloadName) else {
                throw CloudBackupError.missingPayload
            }
            document = try JSONDecoder().decode(CloudBackupDocument.self, from: payload)
            guard document.format == CloudBackupPolicy.format else { throw CloudBackupError.invalidFormat }
            guard document.formatVersion <= CloudBackupPolicy.version else { throw CloudBackupError.needsNewerApp }
        }

        /// Hands each restorable photo to `body` under its plain file name;
        /// entries `CloudBackupPolicy.restorablePhotoName` rejects are skipped.
        func forEachPhoto(_ body: (_ name: String, _ data: Data) throws -> Void) throws {
            for name in zip.names {
                guard let safe = CloudBackupPolicy.restorablePhotoName(name) else { continue }
                // Drain per photo: a long restore loop on the main thread otherwise
                // keeps every photo's autoreleased buffers alive until it ends.
                try autoreleasepool {
                    guard let data = try zip.data(named: name) else { return }
                    try body(safe, data)
                }
            }
        }
    }

    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

enum CloudBackupError: LocalizedError, Equatable {
    case missingPayload
    case invalidFormat
    case needsNewerApp
    case iCloudUnavailable
    case noBackup

    var errorDescription: String? {
        switch self {
        case .missingPayload, .invalidFormat:
            return String(localized: "This is not a Fud AI backup.", comment: "iCloud restore error when the file isn't a Fud AI backup. Fud AI is the app name.")
        case .needsNewerApp:
            return String(localized: "This backup needs a newer Fud AI.", comment: "iCloud restore error when the backup was made by a newer app version. Fud AI is the app name.")
        case .iCloudUnavailable:
            return String(localized: "Sign into iCloud in iOS Settings first.", comment: "iCloud backup error when no iCloud account is signed in on the device.")
        case .noBackup:
            return String(localized: "No iCloud backup found.", comment: "iCloud restore error when there is no backup to restore.")
        }
    }
}

/// Uncompressed ZIP (store method) so we don't need a third-party zip library.
/// Entries stream to and from a file one at a time instead of building the
/// whole archive in memory; the bytes on disk match the original layout.
enum CloudBackupZip {
    struct Writer {
        private let handle: FileHandle
        private var central = Data()
        private var offset = 0
        private var count = 0

        init(url: URL) throws {
            guard FileManager.default.createFile(atPath: url.path, contents: nil) else {
                throw CocoaError(.fileWriteUnknown)
            }
            handle = try FileHandle(forWritingTo: url)
        }

        mutating func add(name: String, data: Data) throws {
            let nameData = Data(name.utf8)
            let localCount = 30 + nameData.count + data.count
            // No ZIP64 records in this format: fail instead of overflowing its
            // 16-bit entry count or 32-bit sizes and offsets.
            guard count < Int(UInt16.max),
                  nameData.count <= Int(UInt16.max),
                  offset + localCount <= Int(UInt32.max)
            else { throw CocoaError(.fileWriteUnknown) }
            let crc = crc32(data)
            var local = Data()
            local.append(contentsOf: u32(0x04034b50))
            local.append(contentsOf: u16(20))
            local.append(contentsOf: u16(0))
            local.append(contentsOf: u16(0))
            local.append(contentsOf: u16(0))
            local.append(contentsOf: u16(0))
            local.append(contentsOf: u32(crc))
            local.append(contentsOf: u32(UInt32(data.count)))
            local.append(contentsOf: u32(UInt32(data.count)))
            local.append(contentsOf: u16(UInt16(nameData.count)))
            local.append(contentsOf: u16(0))
            local.append(nameData)
            try handle.write(contentsOf: local)
            try handle.write(contentsOf: data)

            var dir = Data()
            dir.append(contentsOf: u32(0x02014b50))
            dir.append(contentsOf: u16(20))
            dir.append(contentsOf: u16(20))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u32(crc))
            dir.append(contentsOf: u32(UInt32(data.count)))
            dir.append(contentsOf: u32(UInt32(data.count)))
            dir.append(contentsOf: u16(UInt16(nameData.count)))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u32(0))
            dir.append(contentsOf: u32(UInt32(offset)))
            dir.append(nameData)
            central.append(dir)
            offset += localCount
            count += 1
        }

        /// Writes the central directory and end record. `close()` still has to run.
        func finish() throws {
            guard central.count <= Int(UInt32.max) else { throw CocoaError(.fileWriteUnknown) }
            var end = Data()
            end.append(contentsOf: u32(0x06054b50))
            end.append(contentsOf: u16(0))
            end.append(contentsOf: u16(0))
            end.append(contentsOf: u16(UInt16(count)))
            end.append(contentsOf: u16(UInt16(count)))
            end.append(contentsOf: u32(UInt32(central.count)))
            end.append(contentsOf: u32(UInt32(offset)))
            end.append(contentsOf: u16(0))
            try handle.write(contentsOf: central)
            try handle.write(contentsOf: end)
        }

        func close() {
            try? handle.close()
        }
    }

    /// Scans local headers the way the original in-memory unpack did, keeping
    /// only offsets; `data(named:)` then reads one entry at a time.
    final class Reader {
        private struct Entry {
            var offset: Int
            var count: Int
        }

        private let handle: FileHandle
        private var entries: [String: Entry] = [:]
        /// Entry names in archive order, each listed once (a later duplicate wins, as before).
        private(set) var names: [String] = []

        init(url: URL) throws {
            handle = try FileHandle(forReadingFrom: url)
            let size = Int(try handle.seekToEnd())
            var i = 0
            while i + 30 <= size {
                let header = try read(at: i, count: 30)
                guard header.count == 30 else { break }
                let sig = readU32(header, 0)
                if sig == 0x02014b50 || sig == 0x06054b50 { break }
                guard sig == 0x04034b50 else { break }
                let nameLen = Int(readU16(header, 26))
                let extraLen = Int(readU16(header, 28))
                let compact = Int(readU32(header, 18))
                let nameStart = i + 30
                let nameEnd = nameStart + nameLen
                guard nameEnd + extraLen + compact <= size else { break }
                let name = String(data: try read(at: nameStart, count: nameLen), encoding: .utf8) ?? ""
                let dataStart = nameEnd + extraLen
                if entries[name] == nil { names.append(name) }
                entries[name] = Entry(offset: dataStart, count: compact)
                i = dataStart + compact
            }
        }

        deinit {
            try? handle.close()
        }

        func data(named name: String) throws -> Data? {
            guard let entry = entries[name] else { return nil }
            let data = try read(at: entry.offset, count: entry.count)
            guard data.count == entry.count else { throw CocoaError(.fileReadCorruptFile) }
            return data
        }

        private func read(at offset: Int, count: Int) throws -> Data {
            guard count > 0 else { return Data() }
            try handle.seek(toOffset: UInt64(offset))
            return try handle.read(upToCount: count) ?? Data()
        }
    }

    private static func u16(_ v: UInt16) -> [UInt8] {
        [UInt8(v & 0xff), UInt8((v >> 8) & 0xff)]
    }

    private static func u32(_ v: UInt32) -> [UInt8] {
        [UInt8(v & 0xff), UInt8((v >> 8) & 0xff), UInt8((v >> 16) & 0xff), UInt8((v >> 24) & 0xff)]
    }

    private static func readU16(_ data: Data, _ i: Int) -> UInt16 {
        UInt16(data[i]) | UInt16(data[i + 1]) << 8
    }

    private static func readU32(_ data: Data, _ i: Int) -> UInt32 {
        UInt32(data[i]) | UInt32(data[i + 1]) << 8 | UInt32(data[i + 2]) << 16 | UInt32(data[i + 3]) << 24
    }

    private static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in data {
            let idx = Int((crc ^ UInt32(byte)) & 0xff)
            crc = (crc >> 8) ^ crcTable[idx]
        }
        return crc ^ 0xffffffff
    }

    private static let crcTable: [UInt32] = {
        (0..<256).map { i -> UInt32 in
            var c = UInt32(i)
            for _ in 0..<8 {
                c = (c & 1) == 1 ? (0xedb88320 ^ (c >> 1)) : (c >> 1)
            }
            return c
        }
    }()
}
