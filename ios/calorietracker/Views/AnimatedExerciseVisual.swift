import ImageIO
import SwiftUI
import UIKit

struct AnimatedExerciseVisual: View {
    var muscleGroup: MuscleGroup? = nil
    var assetName: String?
    var exerciseName: String?
    var imagePaths: [String] = []
    var equipment: Equipment?
    var height: CGFloat = 170
    var fillsWidth = true
    var allowsDerivedImageLookup = true
    var animatesFrames = true
    /// When set, decoded frames are capped at this pixel dimension. Nil keeps full resolution.
    var maxPixelSize: Int? = nil
    var fallbackSystemImage = "figure.strengthtraining.traditional"
    var fallbackTitle = String(localized: "Exercise")
    @Environment(ProfileStore.self) private var profileStore
    @State private var animate = false

    var body: some View {
        let visualAsset = resolvedVisualAsset

        ZStack {
            if !visualAsset.frames.isEmpty {
                ExerciseImageView(
                    asset: visualAsset,
                    animatesFrames: animatesFrames,
                    maxPixelSize: effectiveMaxPixelSize
                )
            } else {
                fallbackVisual
            }
        }
        .frame(maxWidth: fillsWidth ? .infinity : nil)
        .frame(height: height)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color(uiColor: .separator).opacity(0.35), lineWidth: 0.5)
        )
    }

    private var effectiveMaxPixelSize: Int? {
        if let maxPixelSize {
            return maxPixelSize
        }
        // Detail heroes keep full quality; list/diary cells downsample to cell size.
        if height >= 200 {
            return nil
        }
        let scale = UIScreen.main.scale
        return max(Int(ceil(height * scale)), 1)
    }

    private var resolvedVisualAsset: ExerciseVisualAsset {
        let directAsset = FreeExerciseDBAssetResolver.preferredVisualAsset(
            for: imagePaths,
            gender: profileStore.profile.gender
        )
        if !directAsset.frames.isEmpty {
            return directAsset
        }

        guard allowsDerivedImageLookup else {
            return .jpeg(urls: [])
        }

        let namedURLs = FreeExerciseDBAssetResolver.imageURLs(
            forExerciseName: exerciseName,
            muscleGroup: muscleGroup,
            equipment: equipment
        )
        if !namedURLs.isEmpty {
            return .jpeg(urls: namedURLs)
        }

        guard allowsDerivedImageLookup, let muscleGroup else {
            return .jpeg(urls: [])
        }

        return .jpeg(
            urls: FreeExerciseDBAssetResolver.imageURLs(forMuscleGroup: muscleGroup, equipment: equipment)
        )
    }

    private var fallbackVisual: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color.workoutPanel,
                    Color.workoutCard,
                    Color.workoutAccent.opacity(animate ? 0.20 : 0.10)
                ],
                startPoint: animate ? .topLeading : .bottomLeading,
                endPoint: animate ? .bottomTrailing : .topTrailing
            )
            .animation(.easeInOut(duration: 2.6).repeatForever(autoreverses: true), value: animate)

            VStack(spacing: 12) {
                Image(systemName: muscleGroup?.icon ?? fallbackSystemImage)
                    .font(.system(size: 36, weight: .semibold))
                    .symbolEffect(.pulse, options: .repeating, value: animate)
                Text((muscleGroup?.title ?? fallbackTitle).uppercased())
                    .font(.caption.weight(.bold))
                    .tracking(1.2)
                if let equipment {
                    Text(equipment.title)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.workoutMutedText)
                }
            }
            .foregroundStyle(Color.workoutCharcoal)
        }
        .onAppear { animate = true }
    }
}

private struct ExerciseImageView: View {
    let asset: ExerciseVisualAsset
    let animatesFrames: Bool
    let maxPixelSize: Int?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var frameIndex = 0
    @State private var displayedImage: UIImage?

    private var taskID: ExerciseImageTaskID {
        ExerciseImageTaskID(
            asset: asset,
            animatesFrames: animatesFrames,
            reduceMotion: reduceMotion,
            maxPixelSize: maxPixelSize
        )
    }

    var body: some View {
        ZStack {
            // Opaque in light and dark so transparent PNG cutouts never
            // composite over scrolling content behind a sticky hero.
            Color.workoutBackground

            if let displayedImage {
                exerciseFrame(displayedImage)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
            }
        }
        .task(id: taskID) {
            displayedImage = nil
            frameIndex = initialFrameIndex
            displayedImage = await loadFrame(at: frameIndex)
            guard animatesFrames, asset.frames.count > 1, !reduceMotion else { return }

            var prefetchedIndex = (frameIndex + 1) % asset.frames.count
            var prefetchedImage = await loadFrame(at: prefetchedIndex)

            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 850_000_000)
                guard !Task.isCancelled else { return }

                if let prefetchedImage {
                    displayedImage = prefetchedImage
                    frameIndex = prefetchedIndex
                } else {
                    frameIndex = (frameIndex + 1) % asset.frames.count
                    displayedImage = await loadFrame(at: frameIndex)
                }

                prefetchedIndex = (frameIndex + 1) % asset.frames.count
                prefetchedImage = await loadFrame(at: prefetchedIndex)
            }
        }
    }

    @ViewBuilder
    private func exerciseFrame(_ image: UIImage) -> some View {
        if asset.format != .jpeg {
            // Authored SVG/PNG frames keep their full-color transparent canvas uncropped.
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
        } else {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .saturation(0.30)
                .grayscale(0.36)
                .contrast(1.10)
                .brightness(-0.05)
        }
    }

    private var initialFrameIndex: Int {
        guard !asset.frames.isEmpty else { return 0 }
        if animatesFrames, !reduceMotion {
            return 0
        }
        if asset.format == .jpeg {
            return 0
        }
        return min(asset.representativeFrameIndex, asset.frames.count - 1)
    }

    private func loadFrame(at index: Int) async -> UIImage? {
        guard asset.frames.indices.contains(index) else { return nil }
        let frame = asset.frames[index]
        return await Task.detached(priority: .userInitiated) {
            ExerciseImageCache.shared.image(for: frame, maxPixelSize: maxPixelSize)
        }.value
    }
}

private struct ExerciseImageTaskID: Equatable {
    let asset: ExerciseVisualAsset
    let animatesFrames: Bool
    let reduceMotion: Bool
    let maxPixelSize: Int?
}

private final class ExerciseImageCache {
    static let shared = ExerciseImageCache()

    private let imagesByFrame: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 96
        cache.totalCostLimit = 48 * 1_024 * 1_024
        return cache
    }()

    private init() {}

    func image(for frame: ExerciseVisualFrame, maxPixelSize: Int?) -> UIImage? {
        let cacheKey = frame.cacheKey(maxPixelSize: maxPixelSize)
        if let image = imagesByFrame.object(forKey: cacheKey) {
            return image
        }

        let image: UIImage?
        switch frame {
        case .file(let url):
            image = Self.decodeImage(fromFileURL: url, maxPixelSize: maxPixelSize)
        case .imageAsset(let name):
            image = Self.decodeAssetImage(named: name, maxPixelSize: maxPixelSize)
        }

        guard let image else {
            return nil
        }

        imagesByFrame.setObject(image, forKey: cacheKey, cost: image.estimatedMemoryCost)
        return image
    }

    private static func decodeImage(fromFileURL url: URL, maxPixelSize: Int?) -> UIImage? {
        guard let maxPixelSize, maxPixelSize > 0 else {
            return UIImage(contentsOfFile: url.path)
        }

        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
            return UIImage(contentsOfFile: url.path)
        }
        return thumbnail(from: source, maxPixelSize: maxPixelSize)
    }

    private static func decodeAssetImage(named name: String, maxPixelSize: Int?) -> UIImage? {
        guard let image = UIImage(named: name) else { return nil }
        guard let maxPixelSize, maxPixelSize > 0 else { return image }

        let longestEdge = max(image.size.width, image.size.height) * image.scale
        guard longestEdge > CGFloat(maxPixelSize) else { return image }

        let target = CGSize(width: maxPixelSize, height: maxPixelSize)
        return image.preparingThumbnail(of: target) ?? image
    }

    private static func thumbnail(from source: CGImageSource, maxPixelSize: Int) -> UIImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            kCGImageSourceCreateThumbnailWithTransform: true
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }
}

private extension ExerciseVisualFrame {
    func cacheKey(maxPixelSize: Int?) -> NSString {
        let pixelKey = maxPixelSize.map(String.init) ?? "full"
        switch self {
        case .file(let url):
            return "file:\(url.standardizedFileURL.absoluteString):\(pixelKey)" as NSString
        case .imageAsset(let name):
            return "asset:\(name):\(pixelKey)" as NSString
        }
    }
}

private extension UIImage {
    var estimatedMemoryCost: Int {
        let pixelWidth = max(Int(size.width * scale), 1)
        let pixelHeight = max(Int(size.height * scale), 1)
        return pixelWidth * pixelHeight * 4
    }
}
