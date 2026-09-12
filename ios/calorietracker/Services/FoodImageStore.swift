import Foundation
import ImageIO
import UIKit

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
    static let thumbnailMaxDimension = 320
    static let viewerMaxDimension = 2048

    private let folderName = "fudai-food-images"
    private let thumbnailFolderName = "fudai-food-thumbnails-v2"

    private let thumbnailCache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 64
        cache.totalCostLimit = 12 * 1_024 * 1_024
        return cache
    }()

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

    private var thumbnailFolderURL: URL? {
        guard let base = try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ) else { return nil }
        let url = base.appendingPathComponent(thumbnailFolderName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
        // Legacy thumbnails lost EXIF orientation during compression.
        let legacyURL = base.appendingPathComponent("fudai-food-thumbnails", isDirectory: true)
        if FileManager.default.fileExists(atPath: legacyURL.path) {
            try? FileManager.default.removeItem(at: legacyURL)
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
            if let thumbnail = decodeImage(at: url, maxPixelSize: Self.thumbnailMaxDimension) {
                writeThumbnail(thumbnail, filename: filename)
            }
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

    /// Bounded decode for list rows — avoids full-resolution JPEG decode on Home.
    func loadThumbnail(filename: String, maxPixelSize: Int = thumbnailMaxDimension) -> UIImage? {
        let cacheKey = "\(filename):\(maxPixelSize)" as NSString
        if let cached = thumbnailCache.object(forKey: cacheKey) {
            return cached
        }

        if let thumbFolderURL = thumbnailFolderURL {
            let thumbURL = thumbFolderURL.appendingPathComponent(filename)
            if FileManager.default.fileExists(atPath: thumbURL.path),
               let image = decodeImage(at: thumbURL, maxPixelSize: maxPixelSize) {
                thumbnailCache.setObject(image, forKey: cacheKey, cost: image.estimatedMemoryCost)
                return image
            }
        }

        guard let sourceURL = fileURL(for: filename),
              let image = decodeImage(at: sourceURL, maxPixelSize: maxPixelSize) else {
            return nil
        }

        writeThumbnail(image, filename: filename)
        thumbnailCache.setObject(image, forKey: cacheKey, cost: image.estimatedMemoryCost)
        return image
    }

    /// Bounded decode for full-screen viewer — original bytes stay untouched on disk.
    func loadForViewer(filename: String, maxPixelSize: Int = viewerMaxDimension) -> UIImage? {
        guard let sourceURL = fileURL(for: filename) else { return nil }
        return decodeImage(at: sourceURL, maxPixelSize: maxPixelSize)
    }

    /// On-disk URL for a stored filename, when the file exists.
    func fileURL(for filename: String) -> URL? {
        guard let folderURL else { return nil }
        let url = folderURL.appendingPathComponent(filename)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Stores a user exercise photo under a fresh filename (never overwrites diary snapshots).
    @discardableResult
    func storeExercisePhoto(data: Data) -> String? {
        store(data: data, filename: UserExercise.newPhotoFilename())
    }

    /// Best-effort delete. Silent no-op if the file is already gone.
    func delete(filename: String) {
        guard let folderURL else { return }
        let url = folderURL.appendingPathComponent(filename)
        try? FileManager.default.removeItem(at: url)
        if let thumbFolderURL = thumbnailFolderURL {
            let thumbURL = thumbFolderURL.appendingPathComponent(filename)
            try? FileManager.default.removeItem(at: thumbURL)
        }
        evictThumbnailCache(for: filename)
    }

    /// Wipes the entire image folder (used by Delete All Data).
    func deleteAll() {
        guard let folderURL else { return }
        try? FileManager.default.removeItem(at: folderURL)
        if let thumbFolderURL = thumbnailFolderURL {
            try? FileManager.default.removeItem(at: thumbFolderURL)
        }
        thumbnailCache.removeAllObjects()
    }

    private func writeThumbnail(_ image: UIImage, filename: String) {
        guard let thumbFolderURL, let data = image.jpegData(compressionQuality: 0.76) else { return }
        let url = thumbFolderURL.appendingPathComponent(filename)
        try? data.write(to: url, options: .atomic)
    }

    private func evictThumbnailCache(for filename: String) {
        thumbnailCache.removeObject(forKey: "\(filename):\(Self.thumbnailMaxDimension)" as NSString)
    }

    private func decodeImage(at url: URL, maxPixelSize: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
            return UIImage(contentsOfFile: url.path)
        }

        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            kCGImageSourceCreateThumbnailWithTransform: true
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return UIImage(contentsOfFile: url.path)
        }
        return UIImage(cgImage: cgImage)
    }
}

private extension UIImage {
    var estimatedMemoryCost: Int {
        let pixelWidth = max(Int(size.width * scale), 1)
        let pixelHeight = max(Int(size.height * scale), 1)
        return pixelWidth * pixelHeight * 4
    }
}
