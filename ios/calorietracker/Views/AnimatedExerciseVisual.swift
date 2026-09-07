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
    var fallbackSystemImage = "figure.strengthtraining.traditional"
    var fallbackTitle = String(localized: "Exercise")
    @Environment(ProfileStore.self) private var profileStore
    @State private var animate = false

    var body: some View {
        let visualAsset = resolvedVisualAsset

        ZStack {
            if !visualAsset.frames.isEmpty {
                ExerciseImageView(asset: visualAsset, animatesFrames: animatesFrames)
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var frameIndex = 0
    @State private var frames: [UIImage] = []

    private var taskID: ExerciseImageTaskID {
        ExerciseImageTaskID(asset: asset, animatesFrames: animatesFrames, reduceMotion: reduceMotion)
    }

    var body: some View {
        ZStack {
            // Opaque in light and dark so transparent PNG cutouts never
            // composite over scrolling content behind a sticky hero.
            Color.workoutBackground

            if !frames.isEmpty {
                ZStack {
                    ForEach(frames.indices, id: \.self) { index in
                        exerciseFrame(frames[index])
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .clipped()
                            .opacity(index == frameIndex ? 1 : 0)
                    }
                }
            }
        }
        .task(id: taskID) {
            frames = []
            frameIndex = 0

            let frameSources = frameSourcesToLoad
            let loadedFrames = ExerciseImageCache.shared.images(for: frameSources)
            guard !Task.isCancelled else { return }
            if !loadedFrames.isEmpty {
                frames = loadedFrames
            }
            guard animatesFrames, frames.count > 1, !reduceMotion else { return }

            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 850_000_000)
                guard !Task.isCancelled else { return }
                frameIndex = (frameIndex + 1) % frames.count
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

    private var frameSourcesToLoad: [ExerciseVisualFrame] {
        guard !asset.frames.isEmpty else { return [] }
        guard !animatesFrames || reduceMotion else { return asset.frames }

        let representativeIndex = asset.format == .jpeg
            ? 0
            : min(asset.representativeFrameIndex, asset.frames.count - 1)
        return [asset.frames[representativeIndex]]
    }
}

private struct ExerciseImageTaskID: Equatable {
    let asset: ExerciseVisualAsset
    let animatesFrames: Bool
    let reduceMotion: Bool
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

    func images(for frames: [ExerciseVisualFrame]) -> [UIImage] {
        frames.compactMap { image(for: $0) }
    }

    private func image(for frame: ExerciseVisualFrame) -> UIImage? {
        let cacheKey = frame.cacheKey
        if let image = imagesByFrame.object(forKey: cacheKey) {
            return image
        }

        let image: UIImage?
        switch frame {
        case .file(let url):
            image = UIImage(contentsOfFile: url.path)
        case .imageAsset(let name):
            image = UIImage(named: name)
        }

        guard let image else {
            return nil
        }

        imagesByFrame.setObject(image, forKey: cacheKey, cost: image.estimatedMemoryCost)
        return image
    }
}

private extension ExerciseVisualFrame {
    var cacheKey: NSString {
        switch self {
        case .file(let url):
            return "file:\(url.standardizedFileURL.absoluteString)" as NSString
        case .imageAsset(let name):
            return "asset:\(name)" as NSString
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
