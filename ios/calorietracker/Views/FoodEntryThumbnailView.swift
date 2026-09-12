import SwiftUI
import UIKit

/// Downsampled food photo for list rows — mirrors Android `loadThumbnail`.
struct FoodEntryThumbnailView: View {
    let filename: String?
    var legacyData: Data? = nil
    var additionalCount: Int = 0
    var size: CGFloat = 56
    var maxPixelSize: Int = FoodImageStore.thumbnailMaxDimension
    var showsAdditionalBadge = true

    @State private var image: UIImage?

    private var hasPhoto: Bool {
        filename != nil || legacyData != nil
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(AppColors.calorie.opacity(0.15), lineWidth: 1)
                    )
                    .overlay(alignment: .bottomTrailing) {
                        if showsAdditionalBadge, additionalCount > 0 {
                            Text("+\(additionalCount)")
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(.black.opacity(0.65), in: Capsule())
                                .padding(4)
                        }
                    }
            } else if hasPhoto {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .frame(width: size, height: size)
            }
        }
        .task(id: loadTaskID) {
            image = nil
            if let legacyData, filename == nil {
                image = UIImage(data: legacyData)
                return
            }
            guard let filename else { return }
            let loaded = await Task.detached(priority: .utility) {
                FoodImageStore.shared.loadThumbnail(filename: filename, maxPixelSize: maxPixelSize)
            }.value
            guard !Task.isCancelled else { return }
            image = loaded
        }
    }

    private var loadTaskID: String {
        "\(filename ?? "")|\(legacyData?.count ?? 0)|\(maxPixelSize)"
    }
}

enum FoodEntryPhotoLoader {
    static func viewerImages(for entry: FoodEntry) async -> [UIImage] {
        await Task.detached(priority: .userInitiated) {
            entry.allImageFilenames.compactMap { filename in
                FoodImageStore.shared.loadForViewer(filename: filename)
            }
        }.value
    }
}
