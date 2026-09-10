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
    ]

    static func include(_ key: String) -> Bool {
        if key == "healthKitEnabled" { return true }
        if excludedKeys.contains(key) { return false }
        if key.hasPrefix("healthKit") { return false }
        if key.hasPrefix("Apple") || key.hasPrefix("NS") || key.hasPrefix("com.apple") { return false }
        if key.hasPrefix("AK") { return false }
        return true
    }

    static func safePhotoName(_ name: String) -> String? {
        let base = (name as NSString).lastPathComponent
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-"))
        guard base.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        let ext = (base as NSString).pathExtension.lowercased()
        guard ["jpg", "jpeg", "png", "webp"].contains(ext) else { return nil }
        return base
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

enum CloudBackupArchive {
    static func contentHash(values: [String: CloudBackupValue], photos: [String: Data]) -> String {
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
        for name in photos.keys.sorted() {
            canonical += "photo:\(name):\(photos[name]?.count ?? 0)\n"
        }
        return sha256(Data(canonical.utf8))
    }

    static func pack(
        values: [String: CloudBackupValue],
        photos: [String: Data],
        exportedAt: String,
        appVersion: String
    ) throws -> Data {
        let filtered = values.filter { CloudBackupPolicy.include($0.key) && !CloudBackupPolicy.excludedKeys.contains($0.key) }
        let safePhotos = Dictionary(uniqueKeysWithValues: photos.compactMap { name, data in
            CloudBackupPolicy.safePhotoName(name).map { ($0, data) }
        })
        let document = CloudBackupDocument(
            format: CloudBackupPolicy.format,
            formatVersion: CloudBackupPolicy.version,
            exportedAt: exportedAt,
            appVersion: appVersion,
            platform: "ios",
            contentSha256: contentHash(values: filtered, photos: safePhotos),
            payload: CloudBackupPayload(values: filtered)
        )
        let payload = try JSONEncoder().encode(document)
        var files: [(String, Data)] = [(CloudBackupPolicy.payloadName, payload)]
        for (name, data) in safePhotos.sorted(by: { $0.key < $1.key }) {
            files.append((CloudBackupPolicy.photosDirectory + name, data))
        }
        return CloudBackupZip.pack(files: files)
    }

    static func unpack(_ data: Data) throws -> (CloudBackupDocument, [String: Data]) {
        let files = try CloudBackupZip.unpack(data)
        guard let payload = files[CloudBackupPolicy.payloadName] else {
            throw CloudBackupError.missingPayload
        }
        let document = try JSONDecoder().decode(CloudBackupDocument.self, from: payload)
        guard document.format == CloudBackupPolicy.format else { throw CloudBackupError.invalidFormat }
        guard document.formatVersion <= CloudBackupPolicy.version else { throw CloudBackupError.needsNewerApp }
        var photos: [String: Data] = [:]
        for (name, bytes) in files where name.hasPrefix(CloudBackupPolicy.photosDirectory) {
            if let safe = CloudBackupPolicy.safePhotoName(name) {
                photos[safe] = bytes
            }
        }
        return (document, photos)
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
        case .missingPayload, .invalidFormat: return "This is not a Fud AI backup."
        case .needsNewerApp: return "This backup needs a newer Fud AI."
        case .iCloudUnavailable: return "Sign into iCloud in iOS Settings first."
        case .noBackup: return "No iCloud backup found."
        }
    }
}

/// Uncompressed ZIP (store method) so we don't need a third-party zip library.
enum CloudBackupZip {
    static func pack(files: [(String, Data)]) -> Data {
        var locals = Data()
        var central = Data()
        var offset: UInt32 = 0
        for (name, data) in files {
            let nameData = Data(name.utf8)
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
            local.append(data)
            locals.append(local)

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
            dir.append(contentsOf: u32(offset))
            dir.append(nameData)
            central.append(dir)
            offset += UInt32(local.count)
        }
        var end = Data()
        end.append(contentsOf: u32(0x06054b50))
        end.append(contentsOf: u16(0))
        end.append(contentsOf: u16(0))
        end.append(contentsOf: u16(UInt16(files.count)))
        end.append(contentsOf: u16(UInt16(files.count)))
        end.append(contentsOf: u32(UInt32(central.count)))
        end.append(contentsOf: u32(offset))
        end.append(contentsOf: u16(0))
        var out = Data()
        out.append(locals)
        out.append(central)
        out.append(end)
        return out
    }

    static func unpack(_ data: Data) throws -> [String: Data] {
        var result: [String: Data] = [:]
        var i = 0
        while i + 30 <= data.count {
            let sig = readU32(data, i)
            if sig == 0x02014b50 || sig == 0x06054b50 { break }
            guard sig == 0x04034b50 else { break }
            let nameLen = Int(readU16(data, i + 26))
            let extraLen = Int(readU16(data, i + 28))
            let compact = Int(readU32(data, i + 18))
            let nameStart = i + 30
            let nameEnd = nameStart + nameLen
            guard nameEnd + extraLen + compact <= data.count else { break }
            let name = String(data: data.subdata(in: nameStart..<nameEnd), encoding: .utf8) ?? ""
            let dataStart = nameEnd + extraLen
            result[name] = data.subdata(in: dataStart..<(dataStart + compact))
            i = dataStart + compact
        }
        return result
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
