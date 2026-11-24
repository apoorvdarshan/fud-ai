import SwiftUI

/// Space used on this device, split the way Settings › Storage shows it.
nonisolated struct AppStorageUsage: Equatable, Sendable {
    var photoBytes: Int64
    /// Regenerable files only: photo thumbnails and downloaded workout frames.
    var cacheBytes: Int64
    var modelBytes: Int64

    /// Allocated size of every regular file under `directory` (0 when missing). Hidden files
    /// count only when `includingHiddenFiles` is set.
    static func directorySize(at directory: URL?, includingHiddenFiles: Bool = false) -> Int64 {
        guard let directory else { return 0 }
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .totalFileAllocatedSizeKey, .fileAllocatedSizeKey, .fileSizeKey]
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: Array(keys),
            options: includingHiddenFiles ? [] : [.skipsHiddenFiles]
        ) else {
            return 0
        }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            guard let values = try? url.resourceValues(forKeys: keys), values.isRegularFile == true else { continue }
            total += Int64(values.totalFileAllocatedSize ?? values.fileAllocatedSize ?? values.fileSize ?? 0)
        }
        return total
    }

    /// Size of the on-device model folders as they are on disk, hidden files included. The
    /// model managers' installed sizes only change when a download ends, so measuring is what
    /// counts a download in progress: Gemma's `.partial` file and WhisperKit's `.incomplete`
    /// files under a hidden `.cache` folder.
    static func modelBytes(in folders: [URL]) -> Int64 {
        folders.reduce(0) { $0 + directorySize(at: $1, includingHiddenFiles: true) }
    }
}

/// Measures storage and runs the two user-started cleanups. App-lifetime, so leaving Settings
/// never stops a compression half way (each photo is swapped atomically, so stopping would be
/// safe, just incomplete). Nothing here runs automatically or deletes a referenced photo.
@Observable
final class StorageMaintenance {
    static let shared = StorageMaintenance()

    private(set) var usage: AppStorageUsage?
    private(set) var isCompressing = false
    private(set) var compressedSoFar = 0
    private(set) var compressionTotal = 0
    private(set) var isClearingCache = false
    var compressionResult: StoredPhotoCompressor.Result?

    var isBusy: Bool { isCompressing || isClearingCache }

    func refresh() async {
        let photoFolder = FoodImageStore.shared.photoFolderURL
        let thumbnailFolder = FoodImageStore.shared.thumbnailCacheFolderURL
        let frameFolder = WorkoutFrameStore.defaultCacheDirectory()
        let modelFolders = [
            Gemma4LocalModelManager.shared.storageDirectory,
            Gemma4LocalModelManager.e4b.storageDirectory,
            WhisperBaseModelManager.shared.storageDirectory,
            LocalSpeechModelManager.parakeet.storageDirectory,
            LocalSpeechModelManager.qwen.storageDirectory,
            LocalSpeechModelManager.lfm3.storageDirectory
        ]
        usage = await Task.detached(priority: .utility) {
            AppStorageUsage(
                photoBytes: AppStorageUsage.directorySize(at: photoFolder),
                cacheBytes: AppStorageUsage.directorySize(at: thumbnailFolder)
                    + AppStorageUsage.directorySize(at: frameFolder),
                modelBytes: AppStorageUsage.modelBytes(in: modelFolders)
            )
        }.value
    }

    /// Downscales existing photos over the stored-size cap. Call only after the user confirms.
    func compressPhotos() async {
        guard !isBusy, let folder = FoodImageStore.shared.photoFolderURL else { return }
        isCompressing = true
        compressedSoFar = 0
        compressionTotal = 0
        compressionResult = nil
        let (updates, continuation) = AsyncStream.makeStream(of: (Int, Int).self)
        let work = Task.detached(priority: .utility) {
            defer { continuation.finish() }
            return StoredPhotoCompressor.compressPhotos(in: folder) { done, total in
                continuation.yield((done, total))
            }
        }
        for await (done, total) in updates {
            compressedSoFar = done
            compressionTotal = total
        }
        let result = await work.value
        isCompressing = false
        compressionResult = result
        await refresh()
    }

    /// Deletes thumbnails and downloaded workout frames; both are rebuilt on demand.
    /// Neither deletion runs on the main actor.
    func clearCache() async {
        guard !isBusy else { return }
        isClearingCache = true
        await FoodImageStore.shared.clearThumbnailCache()
        await WorkoutFrameStore.shared.clearCache()
        isClearingCache = false
        await refresh()
    }
}

/// Settings › Data Management › Storage (mirrors Android StorageSettingsCard): space used by
/// photos, on-device models and regenerable caches, plus Compress Photos (confirmed, never
/// automatic) and Clear Cache (thumbnails and workout frames only).
struct StorageSettingsSection: View {
    @State private var storage = StorageMaintenance.shared
    @State private var showCompressConfirm = false

    var body: some View {
        Section {
            usageRow("Saved Photos", systemImage: "photo.on.rectangle", bytes: storage.usage?.photoBytes)
            usageRow("On-Device Models", systemImage: "cpu", bytes: storage.usage?.modelBytes)
            usageRow("Cache", systemImage: "tray.full", bytes: storage.usage?.cacheBytes)

            Button {
                showCompressConfirm = true
            } label: {
                Label {
                    HStack {
                        if storage.isCompressing, storage.compressionTotal > 0 {
                            Text("Compressing photos… \(storage.compressedSoFar)/\(storage.compressionTotal)")
                        } else {
                            Text("Compress Photos")
                        }
                        Spacer()
                        if storage.isCompressing {
                            ProgressView()
                        }
                    }
                } icon: {
                    Image(systemName: "arrow.down.right.and.arrow.up.left")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .tint(.primary)
            .disabled(storage.isBusy)

            Button {
                Task { await storage.clearCache() }
            } label: {
                Label {
                    HStack {
                        Text("Clear Cache")
                        Spacer()
                        if storage.isClearingCache {
                            ProgressView()
                        }
                    }
                } icon: {
                    Image(systemName: "xmark.bin")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .tint(.primary)
            .disabled(storage.isBusy)
        } header: {
            Text("Storage")
        } footer: {
            Text("Compress Photos permanently resizes photos larger than 2048 px. Clear Cache removes thumbnails and downloaded workout images, which are recreated when needed. Delete on-device models from their AI or speech settings.")
        }
        .listRowBackground(AppColors.appCard)
        .task { await storage.refresh() }
        .alert("Compress Photos?", isPresented: $showCompressConfirm) {
            Button("Compress", role: .destructive) {
                Task { await storage.compressPhotos() }
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Photos larger than 2048 pixels on their longest side will be permanently resized to 2048 pixels and saved as JPEG. Smaller photos stay unchanged. This can’t be undone.")
        }
        .alert(compressionTitle, isPresented: Binding(
            get: { storage.compressionResult != nil },
            set: { if !$0 { storage.compressionResult = nil } }
        )) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(storage.compressionResult.map { Self.compressionMessage(for: $0) } ?? "")
        }
    }

    /// "Photos Compressed" only when a photo actually got smaller; otherwise the neutral action name.
    private var compressionTitle: Text {
        (storage.compressionResult?.compressedCount ?? 0) > 0 ? Text("Photos Compressed") : Text("Compress Photos")
    }

    /// Says what happened: space freed, nothing to do, or which photos were left unchanged and why.
    static func compressionMessage(for result: StoredPhotoCompressor.Result) -> String {
        var parts: [String] = []
        if result.compressedCount > 0 {
            let freed = formatted(result.bytesSaved)
            parts.append(String(localized: "Freed \(freed)."))
        } else if result.unreadableCount == 0, result.failedCount == 0 {
            parts.append(String(localized: "No photos needed compressing."))
        }
        if result.unreadableCount > 0 {
            parts.append(String(localized: "Some photos couldn’t be read and were left unchanged."))
        }
        if result.failedCount > 0 {
            parts.append(String(localized: "Some photos couldn’t be compressed and were left unchanged. Make sure your device has free space, then try again."))
        }
        return parts.joined(separator: " ")
    }

    private func usageRow(_ title: LocalizedStringKey, systemImage: String, bytes: Int64?) -> some View {
        Label {
            HStack {
                Text(title)
                Spacer()
                if let bytes {
                    Text(Self.formatted(bytes))
                        .foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }
            }
        } icon: {
            Image(systemName: systemImage)
                .foregroundStyle(AppColors.calorie)
        }
    }

    private static func formatted(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}
