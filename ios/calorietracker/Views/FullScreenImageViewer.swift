import SwiftUI

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

/// Full-screen meal photo viewer with page swipe (multi-photo), close button, and swipe-down dismiss.
struct FullScreenImageViewer: View {
    let images: [UIImage]
    let initialIndex: Int

    @Environment(\.dismiss) private var dismiss
    @State private var currentIndex: Int
    @State private var dragOffset: CGFloat = 0
    @State private var backdropOpacity: Double = 1
    @State private var verticalDismissCommitted = false

    init(images: [UIImage], initialIndex: Int = 0) {
        self.images = images
        self.initialIndex = initialIndex
        _currentIndex = State(initialValue: min(max(0, initialIndex), max(images.count - 1, 0)))
    }

    var body: some View {
        ZStack {
            Color.black
                .opacity(backdropOpacity)
                .ignoresSafeArea()

            TabView(selection: $currentIndex) {
                ForEach(Array(images.enumerated()), id: \.offset) { index, image in
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .tag(index)
                        .accessibilityLabel("Photo \(index + 1)")
                }
            }
            .tabViewStyle(.page(indexDisplayMode: images.count > 1 ? .automatic : .never))
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
                    .accessibilityLabel("Close")
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                Spacer()
            }
        }
        .statusBarHidden(true)
        .simultaneousGesture(
            DragGesture(minimumDistance: 24)
                .onChanged { value in
                    let vertical = value.translation.height
                    let horizontal = abs(value.translation.width)
                    // Prefer vertical dismiss; ignore mostly-horizontal page swipes.
                    guard abs(vertical) > horizontal else {
                        verticalDismissCommitted = false
                        return
                    }
                    verticalDismissCommitted = true
                    dragOffset = vertical
                    backdropOpacity = Double(max(0.35, 1 - abs(vertical) / 420))
                }
                .onEnded { value in
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
}

extension View {
    func fullScreenImagePreview(_ preview: Binding<FullScreenImagePreview?>) -> some View {
        fullScreenCover(item: preview) { session in
            FullScreenImageViewer(images: session.images, initialIndex: session.initialIndex)
        }
    }
}
