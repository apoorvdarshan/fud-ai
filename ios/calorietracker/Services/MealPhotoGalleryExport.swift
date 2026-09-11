import Photos
import UIKit

enum MealPhotoSettings {
    static let saveToGalleryKey = "saveMealPhotosToGallery"

    static var saveToGallery: Bool {
        UserDefaults.standard.bool(forKey: saveToGalleryKey)
    }
}

enum MealPhotoGalleryExport {
    static func saveToGalleryIfEnabled(data: Data) {
        guard MealPhotoSettings.saveToGallery else { return }
        guard let image = UIImage(data: data) else { return }
        Task { await saveToPhotoLibrary(image) }
    }

    @MainActor
    private static func saveToPhotoLibrary(_ image: UIImage) async {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return }

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            PHPhotoLibrary.shared().performChanges({
                PHAssetCreationRequest.creationRequestForAsset(from: image)
            }) { _, _ in
                continuation.resume()
            }
        }
    }
}
