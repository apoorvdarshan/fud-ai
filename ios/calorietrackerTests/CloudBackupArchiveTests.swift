import Foundation
import Testing
@testable import calorietracker

struct CloudBackupArchiveTests {
    @Test func roundTripKeepsFoodEntryIDsAndExcludesHealthTokens() throws {
        let id = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        let values: [String: CloudBackupValue] = [
            "foodEntries": .string("[{\"id\":\"\(id.uuidString)\"}]"),
            "healthKitFoodRecoveryDone": .bool(false),
            "healthKitNutritionBackfillVersion": .int(8),
        ]
        let photo = Data([1, 2, 3, 4])
        let zip = try CloudBackupArchive.pack(
            values: values,
            photos: ["\(id.uuidString).jpg": photo],
            exportedAt: "2026-09-10T12:00:00Z",
            appVersion: "7.0"
        )
        let (document, photos) = try CloudBackupArchive.unpack(zip)
        #expect(document.format == CloudBackupPolicy.format)
        #expect(document.payload.values["foodEntries"]?.s?.contains(id.uuidString) == true)
        #expect(document.payload.values["healthKitFoodRecoveryDone"] == nil)
        #expect(document.payload.values["healthKitNutritionBackfillVersion"] == nil)
        #expect(photos["\(id.uuidString).jpg"] == photo)
    }

    @Test func newerFormatFailsClosed() throws {
        let zip = try CloudBackupArchive.pack(
            values: ["useMetric": .bool(true)],
            photos: [:],
            exportedAt: "2026-09-10T12:00:00Z",
            appVersion: "7.0"
        )
        var files = try CloudBackupZip.unpack(zip)
        var document = try JSONDecoder().decode(CloudBackupDocument.self, from: files[CloudBackupPolicy.payloadName]!)
        document.formatVersion = CloudBackupPolicy.version + 1
        files[CloudBackupPolicy.payloadName] = try JSONEncoder().encode(document)
        let tampered = CloudBackupZip.pack(files: files.map { ($0.key, $0.value) })
        do {
            _ = try CloudBackupArchive.unpack(tampered)
            Issue.record("expected newer-format backup to fail")
        } catch CloudBackupError.needsNewerApp {
        } catch {
            Issue.record("wrong error \(error)")
        }
    }

    @Test func contentHashIsStableUntilPayloadChanges() {
        let values = ["foodEntries": CloudBackupValue.string("[]")]
        #expect(CloudBackupArchive.contentHash(values: values, photos: [:]) == CloudBackupArchive.contentHash(values: values, photos: [:]))
        let changed = ["foodEntries": CloudBackupValue.string("[1]")]
        #expect(CloudBackupArchive.contentHash(values: values, photos: [:]) != CloudBackupArchive.contentHash(values: changed, photos: [:]))
    }
}
