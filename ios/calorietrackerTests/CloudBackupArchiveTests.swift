import Foundation
import Testing
@testable import calorietracker

@MainActor
struct CloudBackupArchiveTests {
    @Test func roundTripKeepsFoodEntryIDsAndExcludesHealthTokens() throws {
        try withTemporaryDirectory { dir in
            let id = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
            let values: [String: CloudBackupValue] = [
                "foodEntries": .string("[{\"id\":\"\(id.uuidString)\"}]"),
                "healthKitFoodRecoveryDone": .bool(false),
                "healthKitNutritionBackfillVersion": .int(8),
            ]
            let photo = Data([1, 2, 3, 4])
            let photos = try writePhotos(["\(id.uuidString).jpg": photo], in: dir)
            let zip = dir.appendingPathComponent("backup.zip")
            try CloudBackupArchive.pack(to: zip, values: values, photos: photos, exportedAt: exportedAt, appVersion: appVersion)

            let archive = try CloudBackupArchive.Reader(url: zip)
            let document = archive.document
            #expect(document.format == CloudBackupPolicy.format)
            #expect(document.payload.values["foodEntries"]?.s?.contains(id.uuidString) == true)
            #expect(document.payload.values["healthKitFoodRecoveryDone"] == nil)
            #expect(document.payload.values["healthKitNutritionBackfillVersion"] == nil)
            #expect(try restoredPhotos(archive)["\(id.uuidString).jpg"] == photo)
        }
    }

    @Test func deviceBoundCredentialRecordsStayOutOfBackups() {
        let values: [String: CloudBackupValue] = [
            "aiCredentialProfiles_v1": .data(Data([1, 2, 3])),
            "aiCredentialProfiles_v1_corrupt_backup": .data(Data([4])),
            "aiCredentialMigrationVersion": .int(1),
            "aiRouteCredential_primary": .string("profile-id"),
            "aiRouteCredential_textFallback": .string("__unconfigured__:OpenRouter"),
            "foodEntries": .string("[]"),
        ]

        #expect(!CloudBackupPolicy.include("aiCredentialProfiles_v1"))
        #expect(!CloudBackupPolicy.include("aiCredentialProfiles_v1_corrupt_backup"))
        #expect(!CloudBackupPolicy.include("aiCredentialMigrationVersion"))
        #expect(!CloudBackupPolicy.include("aiRouteCredential_primary"))
        #expect(CloudBackupPolicy.include("foodEntries"))

        let filtered = values.filter { CloudBackupPolicy.include($0.key) }
        #expect(filtered.keys.sorted() == ["foodEntries"])
    }

    @Test func newerFormatFailsClosed() throws {
        try withTemporaryDirectory { dir in
            let packed = dir.appendingPathComponent("packed.zip")
            try CloudBackupArchive.pack(to: packed, values: ["useMetric": .bool(true)], photos: [:], exportedAt: exportedAt, appVersion: appVersion)
            var document = try CloudBackupArchive.Reader(url: packed).document
            document.formatVersion = CloudBackupPolicy.version + 1
            let tampered = dir.appendingPathComponent("tampered.zip")
            var zip = try CloudBackupZip.Writer(url: tampered)
            try zip.add(name: CloudBackupPolicy.payloadName, data: try JSONEncoder().encode(document))
            try zip.finish()
            zip.close()
            do {
                _ = try CloudBackupArchive.Reader(url: tampered)
                Issue.record("expected newer-format backup to fail")
            } catch CloudBackupError.needsNewerApp {
            } catch {
                Issue.record("wrong error \(error)")
            }
        }
    }

    @Test func contentHashIsStableUntilPayloadChanges() {
        let values = ["foodEntries": CloudBackupValue.string("[]")]
        #expect(CloudBackupArchive.contentHash(values: values, photos: [:]) == CloudBackupArchive.contentHash(values: values, photos: [:]))
        let changed = ["foodEntries": CloudBackupValue.string("[1]")]
        #expect(CloudBackupArchive.contentHash(values: values, photos: [:]) != CloudBackupArchive.contentHash(values: changed, photos: [:]))
    }

    @Test func contentHashFromFileSizesMatchesTheLegacyHash() {
        let photos = ["b.jpg": Data(repeating: 7, count: 1_234), "a.png": Data(), "c.webp": Data([1, 2])]
        #expect(
            CloudBackupArchive.contentHash(values: sampleValues, photoSizes: photos.mapValues(\.count))
                == LegacyCloudBackupArchive.contentHash(values: sampleValues, photos: photos)
        )
    }

    @Test func zipWriterMatchesTheLegacyInMemoryZipByteForByte() throws {
        try withTemporaryDirectory { dir in
            let files: [(String, Data)] = [
                (CloudBackupPolicy.payloadName, Data("{\"format\":\"fudai-cloud-backup\"}".utf8)),
                ("photos/a.jpg", Data((0..<40_000).map { UInt8(truncatingIfNeeded: $0 &* 31) })),
                ("photos/b.png", Data()),
                ("photos/c.webp", Data([0x50, 0x4b, 0x03, 0x04])),
            ]
            let url = dir.appendingPathComponent("streamed.zip")
            var zip = try CloudBackupZip.Writer(url: url)
            for (name, data) in files {
                try zip.add(name: name, data: data)
            }
            try zip.finish()
            zip.close()
            #expect(try Data(contentsOf: url) == LegacyCloudBackupZip.pack(files: files))
        }
    }

    @Test func packedArchiveMatchesTheLegacyPack() throws {
        try withTemporaryDirectory { dir in
            let originals = [
                "b-second.jpg": Data((0..<30_000).map { UInt8(truncatingIfNeeded: $0 &* 7) }),
                "a-first.png": Data(repeating: 3, count: 10_000),
                "notes.txt": Data([9, 9, 9]),
                "c.webp": Data(),
            ]
            let zip = dir.appendingPathComponent("streamed.zip")
            try CloudBackupArchive.pack(to: zip, values: sampleValues, photos: try writePhotos(originals, in: dir), exportedAt: exportedAt, appVersion: appVersion)

            let streamed = try LegacyCloudBackupZip.unpack(Data(contentsOf: zip))
            let legacy = try LegacyCloudBackupZip.unpack(
                LegacyCloudBackupArchive.pack(values: sampleValues, photos: originals, exportedAt: exportedAt, appVersion: appVersion)
            )
            #expect(Set(streamed.keys) == Set(legacy.keys))
            for (name, data) in legacy where name != CloudBackupPolicy.payloadName {
                #expect(streamed[name] == data, "\(name)")
            }
            let decoder = JSONDecoder()
            let streamedDocument = try decoder.decode(CloudBackupDocument.self, from: try #require(streamed[CloudBackupPolicy.payloadName]))
            let legacyDocument = try decoder.decode(CloudBackupDocument.self, from: try #require(legacy[CloudBackupPolicy.payloadName]))
            #expect(streamedDocument.contentSha256 == legacyDocument.contentSha256)
            #expect(streamedDocument.exportedAt == legacyDocument.exportedAt)
            #expect(streamedDocument.platform == legacyDocument.platform)
            #expect(Set(streamedDocument.payload.values.keys) == Set(legacyDocument.payload.values.keys))
        }
    }

    @Test func readsArchivesWrittenByTheLegacyPack() throws {
        try withTemporaryDirectory { dir in
            let originals = ["meal.jpg": Data([4, 8, 15, 16, 23, 42]), "snack.png": Data(repeating: 1, count: 5_000)]
            let bytes = try LegacyCloudBackupArchive.pack(values: sampleValues, photos: originals, exportedAt: exportedAt, appVersion: appVersion)
            let url = dir.appendingPathComponent("legacy.zip")
            try bytes.write(to: url)

            let archive = try CloudBackupArchive.Reader(url: url)
            let (legacyDocument, legacyPhotos) = try LegacyCloudBackupArchive.unpack(bytes)
            #expect(archive.document.contentSha256 == legacyDocument.contentSha256)
            #expect(archive.document.exportedAt == legacyDocument.exportedAt)
            #expect(try restoredPhotos(archive) == legacyPhotos)
        }
    }

    @Test func manyPhotosRoundTripOneAtATime() throws {
        try withTemporaryDirectory { dir in
            var originals: [String: Data] = [:]
            for index in 0..<300 {
                let count = (index * 7_919) % 24_000 + 1
                originals["\(UUID().uuidString).jpg"] = Data((0..<count).map { UInt8(truncatingIfNeeded: $0 &+ index) })
            }
            let photos = try writePhotos(originals, in: dir)
            let zip = dir.appendingPathComponent("backup.zip")
            try CloudBackupArchive.pack(to: zip, values: sampleValues, photos: photos, exportedAt: exportedAt, appVersion: appVersion)

            let archive = try CloudBackupArchive.Reader(url: zip)
            let filtered = sampleValues.filter { CloudBackupPolicy.include($0.key) }
            #expect(archive.document.contentSha256 == LegacyCloudBackupArchive.contentHash(values: filtered, photos: originals))
            #expect(try restoredPhotos(archive) == originals)
        }
    }

    @Test func photoDeletedAfterTheSnapshotFailsTheBackup() throws {
        try withTemporaryDirectory { dir in
            let photos = try writePhotos(["kept.jpg": Data([1, 2]), "deleted.jpg": Data([3, 4])], in: dir)
            try FileManager.default.removeItem(at: try #require(photos["deleted.jpg"]).url)
            let zip = dir.appendingPathComponent("backup.zip")
            // The hash already lists deleted.jpg; saving a backup without it would
            // mark it as backed up, so the backup fails and is retried instead.
            #expect(throws: (any Error).self) {
                try CloudBackupArchive.pack(to: zip, values: sampleValues, photos: photos, exportedAt: exportedAt, appVersion: appVersion)
            }
        }
    }

    @Test func onlyPlainPhotoFileNamesAreSafe() {
        let uuid = "11111111-2222-3333-4444-555555555555"
        for name in ["meal.jpg", "MEAL.JPEG", "a.png", "b.webp", "\(uuid).jpg", "\(uuid)-2.jpg", "exercise_\(uuid).jpg"] {
            #expect(CloudBackupPolicy.safePhotoName(name) == name, "\(name)")
        }
        let rejected = [
            "../secret.jpg", "../../secret.jpg", "photos/meal.jpg", "/etc/meal.jpg", "/meal.jpg",
            "..\\secret.jpg", "C:\\meal.jpg", "a\\b.jpg", "a..b.jpg", "..jpg", ".hidden.jpg",
            "notes.txt", "meal", "meal.jpg.exe", "", ".", "..", "me al.jpg", "méal.jpg", "meal\u{0}.jpg",
            "٣.jpg", String(repeating: "a", count: CloudBackupPolicy.maxPhotoNameLength) + ".jpg",
        ]
        for name in rejected {
            #expect(CloudBackupPolicy.safePhotoName(name) == nil, "\(name)")
        }
        let longest = String(repeating: "a", count: CloudBackupPolicy.maxPhotoNameLength - 4) + ".jpg"
        #expect(CloudBackupPolicy.safePhotoName(longest) == longest)
    }

    @Test func onlyPhotosFolderEntriesWithPlainNamesAreRestorable() {
        #expect(CloudBackupPolicy.restorablePhotoName("photos/meal.jpg") == "meal.jpg")
        let skipped = [
            "meal.jpg", "other/meal.jpg", "/photos/meal.jpg", "photos/", "photos/../meal.jpg",
            "photos/../../meal.jpg", "photos/sub/meal.jpg", "photos//meal.jpg", "photos/..\\meal.jpg",
            "photos/.meal.jpg", "photos/notes.txt", "../photos/meal.jpg", "backup.json",
        ]
        for entry in skipped {
            #expect(CloudBackupPolicy.restorablePhotoName(entry) == nil, "\(entry)")
        }
    }

    @Test func entriesThatWouldEscapeThePhotoFolderAreSkipped() throws {
        try withTemporaryDirectory { dir in
            let packed = dir.appendingPathComponent("packed.zip")
            try CloudBackupArchive.pack(to: packed, values: sampleValues, photos: [:], exportedAt: exportedAt, appVersion: appVersion)
            let document = try CloudBackupArchive.Reader(url: packed).document
            let crafted = dir.appendingPathComponent("crafted.zip")
            var zip = try CloudBackupZip.Writer(url: crafted)
            try zip.add(name: CloudBackupPolicy.payloadName, data: try JSONEncoder().encode(document))
            let hostile = [
                "photos/../../evil.jpg", "photos/../evil.jpg", "photos/sub/evil.jpg", "photos//evil.jpg",
                "photos/..\\evil.jpg", "/photos/evil.jpg", "../photos/evil.jpg", "photos/.evil.jpg",
                "photos/" + String(repeating: "e", count: 200) + ".jpg",
            ]
            for name in hostile {
                try zip.add(name: name, data: Data(repeating: 9, count: 64))
            }
            try zip.add(name: "photos/ok.jpg", data: Data([1, 2, 3]))
            try zip.finish()
            zip.close()

            #expect(try restoredPhotos(CloudBackupArchive.Reader(url: crafted)) == ["ok.jpg": Data([1, 2, 3])])
        }
    }

    @Test func stagingRefusesNamesThatLeaveTheStagingFolder() throws {
        try withTemporaryDirectory { dir in
            let store = FoodImageStore(baseURL: dir.appendingPathComponent("app", isDirectory: true))
            let staging = try store.makeRestoreStaging()
            defer { staging.discard() }
            let names = [
                "../escape.jpg", "../../escape.jpg", "../fudai-food-images/escape.jpg", "sub/escape.jpg",
                dir.appendingPathComponent("escape.jpg").path, "..\\escape.jpg", ".escape.jpg", "escape.txt", "",
            ]
            for name in names {
                #expect(throws: (any Error).self, "\(name)") {
                    try store.stage(Data([1]), filename: name, in: staging)
                }
            }
            let written = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil)?
                .compactMap { $0 as? URL }
                .filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true } ?? []
            #expect(written.isEmpty, "\(written)")
            #expect(staging.names.isEmpty)
        }
    }

    @Test func stagingRefusesANameThatIsASymbolicLinkOutOfTheFolder() throws {
        try withTemporaryDirectory { dir in
            let store = FoodImageStore(baseURL: dir.appendingPathComponent("app", isDirectory: true))
            let outside = dir.appendingPathComponent("outside.jpg")
            try Data([1, 2, 3]).write(to: outside)
            let staging = try store.makeRestoreStaging()
            defer { staging.discard() }
            try FileManager.default.createSymbolicLink(
                at: staging.folder.appendingPathComponent("link.jpg"),
                withDestinationURL: outside
            )

            #expect(throws: (any Error).self) {
                try store.stage(Data([9, 9]), filename: "link.jpg", in: staging)
            }
            #expect(try Data(contentsOf: outside) == Data([1, 2, 3]))
        }
    }

    @Test func restoreReplacesThePhotosOnlyAfterEveryOneIsStaged() throws {
        try withTemporaryDirectory { dir in
            let store = FoodImageStore(baseURL: dir.appendingPathComponent("app", isDirectory: true))
            #expect(store.store(data: Data([7]), for: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!) != nil)
            let originals = ["a.jpg": Data([1, 2]), "b.jpg": Data([3])]
            let zip = dir.appendingPathComponent("backup.zip")
            try CloudBackupArchive.pack(to: zip, values: sampleValues, photos: try writePhotos(originals, in: dir), exportedAt: exportedAt, appVersion: appVersion)

            var photosWhenValuesApplied: Set<String> = []
            try CloudBackupService.restorePhotos(from: CloudBackupArchive.Reader(url: zip), into: store) {
                photosWhenValuesApplied = Set(store.filenames())
            }

            // The old photo is still there while the values are written, then goes.
            #expect(photosWhenValuesApplied == ["a.jpg", "b.jpg", "00000000-0000-0000-0000-000000000001.jpg"])
            #expect(Set(store.filenames()) == ["a.jpg", "b.jpg"])
            #expect(store.load(filename: "a.jpg") == Data([1, 2]))
            #expect(store.load(filename: "b.jpg") == Data([3]))
        }
    }

    @Test func compressPhotosReplacesNothingWhileARestoreMovesPhotosIn() throws {
        try withTemporaryDirectory { dir in
            let store = FoodImageStore(baseURL: dir.appendingPathComponent("app", isDirectory: true))
            let originals = ["a.jpg": Data([1, 2]), "b.jpg": Data([3])]
            let zip = dir.appendingPathComponent("backup.zip")
            try CloudBackupArchive.pack(to: zip, values: sampleValues, photos: try writePhotos(originals, in: dir), exportedAt: exportedAt, appVersion: appVersion)
            let folder = try #require(store.photoFolderURL)

            var duringRestore = StoredPhotoCompressor.Result()
            try CloudBackupService.restorePhotos(from: CloudBackupArchive.Reader(url: zip), into: store) {
                duringRestore = StoredPhotoCompressor.compressPhotos(in: folder)
            }

            // Both photos were reached and left alone; outside a restore they are examined.
            #expect(duringRestore == StoredPhotoCompressor.Result(failedCount: 2))
            #expect(!StoredPhotoCompressor.isRestoring)
            #expect(StoredPhotoCompressor.compressPhotos(in: folder) == StoredPhotoCompressor.Result(unreadableCount: 2))
            #expect(store.load(filename: "a.jpg") == Data([1, 2]))
            #expect(store.load(filename: "b.jpg") == Data([3]))
        }
    }

    @Test func anArchiveReadFailureLeavesThePhotosAndValuesUntouched() throws {
        try withTemporaryDirectory { dir in
            let store = FoodImageStore(baseURL: dir.appendingPathComponent("app", isDirectory: true))
            let oldID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
            #expect(store.store(data: Data([7]), for: oldID) != nil)
            let originals = [
                "a.jpg": Data(repeating: 1, count: 4_096),
                "b.jpg": Data(repeating: 2, count: 4_096),
                "c.jpg": Data(repeating: 3, count: 4_096),
            ]
            let zip = dir.appendingPathComponent("backup.zip")
            try CloudBackupArchive.pack(to: zip, values: sampleValues, photos: try writePhotos(originals, in: dir), exportedAt: exportedAt, appVersion: appVersion)
            let archive = try CloudBackupArchive.Reader(url: zip)
            // The archive was scanned, then stops being readable partway.
            let size = try #require(try FileManager.default.attributesOfItem(atPath: zip.path)[.size] as? Int)
            let handle = try FileHandle(forWritingTo: zip)
            try handle.truncate(atOffset: UInt64(size / 2))
            try handle.close()

            var appliedValues = false
            #expect(throws: (any Error).self) {
                try CloudBackupService.restorePhotos(from: archive, into: store) { appliedValues = true }
            }

            #expect(!appliedValues)
            #expect(store.filenames() == ["\(oldID.uuidString).jpg"])
            #expect(store.load(filename: "\(oldID.uuidString).jpg") == Data([7]))
            // The staged photos are discarded with the failed restore.
            #expect(!FileManager.default.fileExists(atPath: dir.appendingPathComponent("app/fudai-food-images-restore").path))
        }
    }

    @Test func abandonedBackupArchivesAreRemovedButActiveOnesKept() throws {
        try withTemporaryDirectory { dir in
            let names = ["fudai-backup-abandoned.zip", "fudai-backup-active.zip", "fudai-backup.zip", "other.zip", "fudai-backup-notes.txt"]
            for name in names {
                try Data([1]).write(to: dir.appendingPathComponent(name))
            }
            CloudBackupService.removeAbandonedArchives(in: dir, keeping: ["fudai-backup-active.zip"])
            let left = Set(try FileManager.default.contentsOfDirectory(atPath: dir.path))
            #expect(left == ["fudai-backup-active.zip", "other.zip", "fudai-backup-notes.txt"])
        }
    }

    @Test func zipWriterRefusesMoreEntriesThanTheFormatCanCount() throws {
        try withTemporaryDirectory { dir in
            var zip = try CloudBackupZip.Writer(url: dir.appendingPathComponent("full.zip"))
            defer { zip.close() }
            for index in 0..<Int(UInt16.max) {
                try zip.add(name: "photos/\(index).jpg", data: Data())
            }
            do {
                try zip.add(name: "photos/one-too-many.jpg", data: Data())
                Issue.record("expected the entry past the 16-bit count to be refused")
            } catch is CocoaError {
            } catch {
                Issue.record("wrong error \(error)")
            }
        }
    }

    // MARK: - Helpers

    private let exportedAt = "2026-09-10T12:00:00Z"
    private let appVersion = "7.0"

    private var sampleValues: [String: CloudBackupValue] {
        [
            "foodEntries": .string("[{\"id\":\"a\",\"name\":\"Rice\"}]"),
            "useMetric": .bool(true),
            "goalCalories": .int(2100),
            "favoriteFoods": .stringArray(["oats", "eggs"]),
            "widgetSnapshot": .data(Data([1, 2, 3])),
            "healthKitFoodRecoveryDone": .bool(true),
        ]
    }

    private func withTemporaryDirectory(_ body: (URL) throws -> Void) throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("CloudBackupArchiveTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        try body(dir)
    }

    private func writePhotos(_ photos: [String: Data], in dir: URL) throws -> [String: CloudBackupPhotoFile] {
        let photoDir = dir.appendingPathComponent("photos-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: photoDir, withIntermediateDirectories: true)
        var files: [String: CloudBackupPhotoFile] = [:]
        for (name, data) in photos {
            let url = photoDir.appendingPathComponent(name)
            try data.write(to: url)
            files[name] = CloudBackupPhotoFile(url: url, size: data.count)
        }
        return files
    }

    private func restoredPhotos(_ archive: CloudBackupArchive.Reader) throws -> [String: Data] {
        var photos: [String: Data] = [:]
        try archive.forEachPhoto { name, data in photos[name] = data }
        return photos
    }
}

// The pre-streaming in-memory implementation, kept verbatim as the compatibility reference.
@MainActor
private enum LegacyCloudBackupArchive {
    static func legacySafePhotoName(_ name: String) -> String? {
        let base = (name as NSString).lastPathComponent
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-"))
        guard base.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        let ext = (base as NSString).pathExtension.lowercased()
        guard ["jpg", "jpeg", "png", "webp"].contains(ext) else { return nil }
        return base
    }

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
        return CloudBackupArchive.sha256(Data(canonical.utf8))
    }

    static func pack(
        values: [String: CloudBackupValue],
        photos: [String: Data],
        exportedAt: String,
        appVersion: String
    ) throws -> Data {
        let filtered = values.filter { CloudBackupPolicy.include($0.key) && !CloudBackupPolicy.excludedKeys.contains($0.key) }
        let safePhotos = Dictionary(uniqueKeysWithValues: photos.compactMap { name, data in
            legacySafePhotoName(name).map { ($0, data) }
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
        return LegacyCloudBackupZip.pack(files: files)
    }

    static func unpack(_ data: Data) throws -> (CloudBackupDocument, [String: Data]) {
        let files = try LegacyCloudBackupZip.unpack(data)
        guard let payload = files[CloudBackupPolicy.payloadName] else {
            throw CloudBackupError.missingPayload
        }
        let document = try JSONDecoder().decode(CloudBackupDocument.self, from: payload)
        guard document.format == CloudBackupPolicy.format else { throw CloudBackupError.invalidFormat }
        guard document.formatVersion <= CloudBackupPolicy.version else { throw CloudBackupError.needsNewerApp }
        var photos: [String: Data] = [:]
        for (name, bytes) in files where name.hasPrefix(CloudBackupPolicy.photosDirectory) {
            if let safe = legacySafePhotoName(name) {
                photos[safe] = bytes
            }
        }
        return (document, photos)
    }
}

private enum LegacyCloudBackupZip {
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
