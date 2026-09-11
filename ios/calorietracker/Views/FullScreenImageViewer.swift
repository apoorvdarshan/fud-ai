import Photos
import SwiftUI
import UIKit

/// Session payload for presenting one or more meal photos full-screen.
struct FullScreenImagePreview: Identifiable {
    let id = UUID()
    let images: [UIImage]
    let initialIndex: Int

    init(images: [UIImage], initialIndex: Int = 0) {
        self.images = images
        self.initialIndex = min(max(0, initialIndex), max(images.count - 1, 0))
    }
}

private enum MealPhotoSaveOutcome {
    case saved
    case permissionDenied
    case failed
}

/// Full-screen meal photo viewer with pinch/double-tap zoom, page swipe, save, close, and swipe-down dismiss.
struct FullScreenImageViewer: View {
    let images: [UIImage]
    let initialIndex: Int

    @Environment(\.dismiss) private var dismiss
    @State private var currentIndex: Int
    @State private var dragOffset: CGFloat = 0
    @State private var backdropOpacity: Double = 1
    @State private var verticalDismissCommitted = false
    @State private var zoomedPages: Set<Int> = []
    @State private var isSaving = false
    @State private var saveBanner: String?

    private let doubleTapZoomScale: CGFloat = 2.5

    init(images: [UIImage], initialIndex: Int = 0) {
        self.images = images
        self.initialIndex = initialIndex
        _currentIndex = State(initialValue: min(max(0, initialIndex), max(images.count - 1, 0)))
    }

    private var isCurrentPageZoomed: Bool {
        zoomedPages.contains(currentIndex)
    }

    var body: some View {
        ZStack {
            Color.black
                .opacity(backdropOpacity)
                .ignoresSafeArea()

            TabView(selection: $currentIndex) {
                ForEach(Array(images.enumerated()), id: \.offset) { index, image in
                    ZoomableMealPhotoPage(
                        image: image,
                        doubleTapZoomScale: doubleTapZoomScale,
                        isActive: currentIndex == index,
                        isZoomed: Binding(
                            get: { zoomedPages.contains(index) },
                            set: { zoomed in
                                if zoomed {
                                    zoomedPages.insert(index)
                                } else {
                                    zoomedPages.remove(index)
                                }
                            }
                        )
                    )
                    .tag(index)
                    .accessibilityLabel(String(localized: "Photo \(index + 1)"))
                }
            }
            .tabViewStyle(.page(indexDisplayMode: images.count > 1 ? .automatic : .never))
            .scrollDisabled(isCurrentPageZoomed)
            .offset(y: dragOffset)

            VStack {
                HStack {
                    if images.count > 1 {
                        Text("\(currentIndex + 1)/\(images.count)")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(.white.opacity(0.18), in: Capsule())
                    }
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 36, height: 36)
                            .background(.white.opacity(0.22), in: Circle())
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: "Close"))
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)

                Spacer()

                Button {
                    Task { await saveCurrentPhoto() }
                } label: {
                    HStack(spacing: 8) {
                        if isSaving {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Image(systemName: "square.and.arrow.down")
                                .font(.system(size: 15, weight: .semibold))
                        }
                        Text("Save Photo", comment: "Save the current meal photo to the device photo library")
                            .font(.subheadline.weight(.semibold))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(.white.opacity(0.22), in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(isSaving)
                .accessibilityLabel(String(localized: "Save photo"))
                .padding(.bottom, 28)
            }

            if let saveBanner {
                VStack {
                    Spacer()
                    Text(saveBanner)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.black.opacity(0.72), in: Capsule())
                        .padding(.bottom, 96)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .statusBarHidden(true)
        .simultaneousGesture(
            DragGesture(minimumDistance: 24)
                .onChanged { value in
                    guard !isCurrentPageZoomed else {
                        verticalDismissCommitted = false
                        return
                    }
                    let vertical = value.translation.height
                    let horizontal = abs(value.translation.width)
                    guard abs(vertical) > horizontal else {
                        verticalDismissCommitted = false
                        return
                    }
                    verticalDismissCommitted = true
                    dragOffset = vertical
                    backdropOpacity = Double(max(0.35, 1 - abs(vertical) / 420))
                }
                .onEnded { value in
                    guard !isCurrentPageZoomed else {
                        verticalDismissCommitted = false
                        withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                            dragOffset = 0
                            backdropOpacity = 1
                        }
                        return
                    }
                    let vertical = value.translation.height
                    let horizontal = abs(value.translation.width)
                    guard verticalDismissCommitted, abs(vertical) > horizontal else {
                        verticalDismissCommitted = false
                        withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                            dragOffset = 0
                            backdropOpacity = 1
                        }
                        return
                    }
                    verticalDismissCommitted = false
                    let shouldDismiss = abs(vertical) > 120
                        || abs(value.predictedEndTranslation.height) > 420
                    if shouldDismiss {
                        dismiss()
                    } else {
                        withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                            dragOffset = 0
                            backdropOpacity = 1
                        }
                    }
                }
        )
    }

    @MainActor
    private func saveCurrentPhoto() async {
        guard images.indices.contains(currentIndex), !isSaving else { return }
        isSaving = true
        defer { isSaving = false }

        let outcome = await saveImageToPhotoLibrary(images[currentIndex])
        let message: String
        switch outcome {
        case .saved:
            message = String(localized: "Photo saved")
        case .permissionDenied:
            message = String(localized: "Photo access denied")
        case .failed:
            message = String(localized: "Couldn't save photo")
        }

        withAnimation(.easeInOut(duration: 0.2)) {
            saveBanner = message
        }
        try? await Task.sleep(for: .seconds(2))
        withAnimation(.easeInOut(duration: 0.2)) {
            saveBanner = nil
        }
    }

    private func saveImageToPhotoLibrary(_ image: UIImage) async -> MealPhotoSaveOutcome {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            return .permissionDenied
        }

        return await withCheckedContinuation { continuation in
            PHPhotoLibrary.shared().performChanges({
                PHAssetCreationRequest.creationRequestForAsset(from: image)
            }) { success, _ in
                continuation.resume(returning: success ? .saved : .failed)
            }
        }
    }
}

private struct ZoomableMealPhotoPage: View {
    let image: UIImage
    let doubleTapZoomScale: CGFloat
    let isActive: Bool
    @Binding var isZoomed: Bool

    var body: some View {
        ZoomableMealPhotoScrollView(
            image: image,
            doubleTapZoomScale: doubleTapZoomScale,
            isActive: isActive,
            isZoomed: $isZoomed
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ZoomableMealPhotoScrollView: UIViewRepresentable {
    let image: UIImage
    let doubleTapZoomScale: CGFloat
    let isActive: Bool
    @Binding var isZoomed: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> UIScrollView {
        let scrollView = UIScrollView()
        scrollView.delegate = context.coordinator
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = max(doubleTapZoomScale, 4)
        scrollView.bouncesZoom = true
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.backgroundColor = .clear
        scrollView.contentInsetAdjustmentBehavior = .never

        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.isUserInteractionEnabled = true
        scrollView.addSubview(imageView)
        context.coordinator.imageView = imageView
        context.coordinator.scrollView = scrollView

        let doubleTap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleDoubleTap(_:))
        )
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)

        return scrollView
    }

    func updateUIView(_ scrollView: UIScrollView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.imageView?.image = image

        if !isActive, scrollView.zoomScale > scrollView.minimumZoomScale + 0.01 {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: false)
            isZoomed = false
        }

        let isAtMinZoom = scrollView.zoomScale <= scrollView.minimumZoomScale + 0.01
        let boundsChanged = scrollView.bounds.size != context.coordinator.lastLayoutBounds
        let imageChanged = context.coordinator.lastLayoutImage !== image
        if isAtMinZoom && (boundsChanged || imageChanged) {
            context.coordinator.layoutImage(in: scrollView)
            context.coordinator.lastLayoutBounds = scrollView.bounds.size
            context.coordinator.lastLayoutImage = image
        }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var parent: ZoomableMealPhotoScrollView
        weak var scrollView: UIScrollView?
        weak var imageView: UIImageView?
        var lastLayoutBounds: CGSize = .zero
        var lastLayoutImage: UIImage?

        init(parent: ZoomableMealPhotoScrollView) {
            self.parent = parent
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            imageView
        }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            centerImage(in: scrollView)
            parent.isZoomed = scrollView.zoomScale > scrollView.minimumZoomScale + 0.01
        }

        func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
            parent.isZoomed = scale > scrollView.minimumZoomScale + 0.01
        }

        @objc func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
            guard let scrollView, let imageView else { return }

            if scrollView.zoomScale > scrollView.minimumZoomScale + 0.01 {
                scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
                return
            }

            let targetScale = min(parent.doubleTapZoomScale, scrollView.maximumZoomScale)
            let point = recognizer.location(in: imageView)
            let zoomRect = zoomRect(for: targetScale, center: point, in: scrollView)
            scrollView.zoom(to: zoomRect, animated: true)
        }

        func layoutImage(in scrollView: UIScrollView) {
            guard let imageView, let image = imageView.image else { return }
            let bounds = scrollView.bounds
            guard bounds.width > 0, bounds.height > 0 else { return }

            let imageSize = image.size
            guard imageSize.width > 0, imageSize.height > 0 else { return }

            let widthScale = bounds.width / imageSize.width
            let heightScale = bounds.height / imageSize.height
            let fitScale = min(widthScale, heightScale)
            let fittedSize = CGSize(width: imageSize.width * fitScale, height: imageSize.height * fitScale)

            imageView.frame = CGRect(origin: .zero, size: fittedSize)
            scrollView.contentSize = fittedSize
            centerImage(in: scrollView)
        }

        private func centerImage(in scrollView: UIScrollView) {
            guard let imageView else { return }
            let boundsSize = scrollView.bounds.size
            var frame = imageView.frame

            frame.origin.x = frame.size.width < boundsSize.width
                ? (boundsSize.width - frame.size.width) / 2
                : frame.origin.x
            frame.origin.y = frame.size.height < boundsSize.height
                ? (boundsSize.height - frame.size.height) / 2
                : frame.origin.y

            imageView.frame = frame
        }

        private func zoomRect(for scale: CGFloat, center: CGPoint, in scrollView: UIScrollView) -> CGRect {
            let size = scrollView.bounds.size
            let width = size.width / scale
            let height = size.height / scale
            let originX = center.x - width / 2
            let originY = center.y - height / 2
            return CGRect(x: originX, y: originY, width: width, height: height)
        }
    }
}

extension View {
    func fullScreenImagePreview(_ preview: Binding<FullScreenImagePreview?>) -> some View {
        fullScreenCover(item: preview) { session in
            FullScreenImageViewer(images: session.images, initialIndex: session.initialIndex)
        }
    }
}
