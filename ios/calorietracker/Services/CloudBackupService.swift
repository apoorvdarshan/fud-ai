import CloudKit
import Foundation
import Observation
import os

@Observable
final class CloudBackupService {
    static let enabledKey = "cloudBackupEnabled"
    static let lastAtKey = "cloudBackupLastAt"
    static let lastHashKey = "cloudBackupLastHash"
    static let smokeTestLaunchArgument = "-fudai.cloudBackup.smokeTest"
    static let smokeTestRecordName = "smoke-test"

    private static var didRunSmokeTestThisLaunch = false
    private static let smokeLogger = Logger(subsystem: "com.apoorvdarshan.calorietracker", category: "CloudBackupSmoke")

    private let recordType = "FudAIBackup"
    private let recordName = "current"
    private let assetField = "backupAsset"
    private let shaField = "contentSha256"

    var enabled: Bool {
        didSet { UserDefaults.standard.set(enabled, forKey: Self.enabledKey) }
    }
    var lastAt: String?
    var busy = false
    var hasCloudBackup = false
    var errorMessage: String?

    private let defaults: UserDefaults
    private var container: CKContainer { CKContainer.default() }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.enabled = defaults.bool(forKey: Self.enabledKey)
        self.lastAt = defaults.string(forKey: Self.lastAtKey)
    }

    func snapshotValues() -> [String: CloudBackupValue] {
        var out: [String: CloudBackupValue] = [:]
        for (key, raw) in defaults.dictionaryRepresentation() {
            guard CloudBackupPolicy.include(key) else { continue }
            if let data = raw as? Data {
                out[key] = .data(data)
            } else if let strings = raw as? [String] {
                out[key] = .stringArray(strings)
            } else if let string = raw as? String {
                out[key] = .string(string)
            } else if let number = raw as? NSNumber {
                if CFGetTypeID(number) == CFBooleanGetTypeID() {
                    out[key] = .bool(number.boolValue)
                } else {
                    out[key] = .int(number.intValue)
                }
            }
        }
        return out
    }

    func applyValues(_ values: [String: CloudBackupValue]) {
        let restoredKeys = Set(values.keys)
        for key in defaults.dictionaryRepresentation().keys
        where CloudBackupPolicy.include(key) && !restoredKeys.contains(key) {
            defaults.removeObject(forKey: key)
        }
        for (key, value) in values {
            guard CloudBackupPolicy.include(key) else { continue }
            switch value.t {
            case "b":
                if let b = value.b { defaults.set(b, forKey: key) }
            case "i":
                if let i = value.i { defaults.set(i, forKey: key) }
            case "s":
                if let s = value.s { defaults.set(s, forKey: key) }
            case "d":
                if let d = value.d, let data = Data(base64Encoded: d) {
                    defaults.set(data, forKey: key)
                }
            case "ss":
                if let ss = value.ss { defaults.set(ss, forKey: key) }
            default:
                continue
            }
        }
        defaults.set(true, forKey: "healthKitFoodRecoveryDone")
        defaults.set(true, forKey: Self.enabledKey)
        enabled = true
    }

    func snapshotPhotos() -> [String: Data] {
        var photos: [String: Data] = [:]
        for name in FoodImageStore.shared.filenames() {
            guard let safe = CloudBackupPolicy.safePhotoName(name),
                  let data = FoodImageStore.shared.load(filename: safe)
            else { continue }
            photos[safe] = data
        }
        return photos
    }

    func restorePhotos(_ photos: [String: Data]) {
        FoodImageStore.shared.deleteAll()
        for (name, data) in photos {
            _ = FoodImageStore.shared.restore(data: data, filename: name)
        }
    }

    func checkAccount() async throws {
        let status = try await container.accountStatus()
        guard status == .available else { throw CloudBackupError.iCloudUnavailable }
    }

    func refreshCloudPresence() async {
        do {
            try await checkAccount()
            hasCloudBackup = try await fetchRecord() != nil
        } catch {
            hasCloudBackup = false
        }
    }

    func backupNow(skipIfUnchanged: Bool = false) async throws {
        busy = true
        defer { busy = false }
        try await checkAccount()
        let values = snapshotValues()
        let photos = snapshotPhotos()
        let hash = CloudBackupArchive.contentHash(values: values, photos: photos)
        if skipIfUnchanged, hash == defaults.string(forKey: Self.lastHashKey) { return }
        let zip = try CloudBackupArchive.pack(
            values: values,
            photos: photos,
            exportedAt: ISO8601DateFormatter().string(from: Date()),
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        )
        try await upload(zip: zip, hash: hash)
        let now = ISO8601DateFormatter().string(from: Date())
        defaults.set(now, forKey: Self.lastAtKey)
        defaults.set(hash, forKey: Self.lastHashKey)
        lastAt = now
        enabled = true
        hasCloudBackup = true
    }

    func restoreNow() async throws {
        busy = true
        defer { busy = false }
        try await checkAccount()
        guard let record = try await fetchRecord(),
              let asset = record[assetField] as? CKAsset,
              let url = asset.fileURL
        else { throw CloudBackupError.noBackup }
        let zip = try Data(contentsOf: url)
        let (document, photos) = try CloudBackupArchive.unpack(zip)
        restorePhotos(photos)
        applyValues(document.payload.values)
        defaults.set(document.contentSha256, forKey: Self.lastHashKey)
        defaults.set(document.exportedAt, forKey: Self.lastAtKey)
        lastAt = document.exportedAt
        NotificationCenter.default.post(name: .cloudBackupDidRestore, object: nil)
    }

    func deleteCloudBackup() async throws {
        busy = true
        defer { busy = false }
        try await deleteCloudRecord(named: recordName)
    }

    func autoBackupIfNeeded() async {
        guard enabled else { return }
        guard isUnmetered() else { return }
        if let last = lastAt,
           let date = ISO8601DateFormatter().date(from: last),
           Date().timeIntervalSince(date) < CloudBackupPolicy.minAutoBackupInterval {
            return
        }
        try? await backupNow(skipIfUnchanged: true)
    }

    /// Release-safe CloudKit integration check: upload → download → delete.
    /// Logs `FudAICloudBackupSmokeTest: PASS` or `FAIL: <reason>` to stdout and os_log.
    func runSmokeTestIfRequested() async {
        guard CommandLine.arguments.contains(Self.smokeTestLaunchArgument) else { return }
        guard !Self.didRunSmokeTestThisLaunch else { return }
        Self.didRunSmokeTestThisLaunch = true
        await runSmokeTest()
    }

    func runSmokeTest() async {
        var smokeUploaded = false

        do {
            try await checkAccount()
            let values = snapshotValues()
            let photos = snapshotPhotos()
            let hash = CloudBackupArchive.contentHash(values: values, photos: photos)
            let zip = try CloudBackupArchive.pack(
                values: values,
                photos: photos,
                exportedAt: ISO8601DateFormatter().string(from: Date()),
                appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
            )
            try await upload(zip: zip, hash: hash, toRecord: Self.smokeTestRecordName)
            smokeUploaded = true

            guard let record = try await fetchRecord(named: Self.smokeTestRecordName) else {
                logSmokeTestFailure("backup record missing after upload")
                return
            }
            try validateDownloadedBackup(record: record, expectedHash: hash)

            try await deleteCloudRecord(named: Self.smokeTestRecordName)
            smokeUploaded = false

            guard try await fetchRecord(named: Self.smokeTestRecordName) == nil else {
                logSmokeTestFailure("record still present after delete")
                return
            }
            logSmokeTestPass()
        } catch {
            logSmokeTestFailure(error.localizedDescription)
        }

        if smokeUploaded {
            try? await deleteCloudRecord(named: Self.smokeTestRecordName)
        }
    }

    private func logSmokeTestPass() {
        let line = "FudAICloudBackupSmokeTest: PASS"
        print(line)
        Self.smokeLogger.info("\(line, privacy: .public)")
    }

    private func logSmokeTestFailure(_ reason: String) {
        let line = "FudAICloudBackupSmokeTest: FAIL: \(reason)"
        print(line)
        Self.smokeLogger.error("\(line, privacy: .public)")
    }

    private func upload(zip: Data, hash: String, toRecord named: String? = nil) async throws {
        let name = named ?? recordName
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent("fudai-backup.zip")
        try zip.write(to: temp, options: .atomic)
        let id = CKRecord.ID(recordName: name)
        let record = (try? await fetchRecord(named: name)) ?? CKRecord(recordType: recordType, recordID: id)
        record[assetField] = CKAsset(fileURL: temp)
        record[shaField] = hash as CKRecordValue
        _ = try await container.privateCloudDatabase.save(record)
    }

    private func fetchRecord(named name: String? = nil) async throws -> CKRecord? {
        let id = CKRecord.ID(recordName: name ?? recordName)
        do {
            return try await container.privateCloudDatabase.record(for: id)
        } catch let error as CKError where error.code == .unknownItem {
            return nil
        }
    }

    private func deleteCloudRecord(named name: String) async throws {
        try await checkAccount()
        let id = CKRecord.ID(recordName: name)
        do {
            try await container.privateCloudDatabase.deleteRecord(withID: id)
        } catch let error as CKError where error.code == .unknownItem {
            // already gone
        }
        guard name == recordName else { return }
        hasCloudBackup = false
        defaults.removeObject(forKey: Self.lastAtKey)
        defaults.removeObject(forKey: Self.lastHashKey)
        lastAt = nil
    }

    private func validateDownloadedBackup(record: CKRecord, expectedHash: String) throws {
        guard let asset = record[assetField] as? CKAsset,
              let url = asset.fileURL
        else { throw CloudBackupError.noBackup }
        let zip = try Data(contentsOf: url)
        let (document, _) = try CloudBackupArchive.unpack(zip)
        guard document.contentSha256 == expectedHash else { throw CloudBackupError.invalidFormat }
        if let recordSha = record[shaField] as? String, recordSha != expectedHash {
            throw CloudBackupError.invalidFormat
        }
    }

    private func isUnmetered() -> Bool { true }
}

extension Notification.Name {
    static let cloudBackupDidRestore = Notification.Name("ai.fud.cloudBackupDidRestore")
}
