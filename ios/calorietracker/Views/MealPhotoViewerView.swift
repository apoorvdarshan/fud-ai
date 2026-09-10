import SwiftUI

struct MealPhotoViewerItem: Identifiable {
    let id = UUID()
    let images: [UIImage]
    let startIndex: Int

    init(images: [UIImage], startIndex: Int = 0) {
        self.images = images
        self.startIndex = min(max(startIndex, 0), max(images.count - 1, 0))
    }
}

enum MealPhotoLoader {
    static func images(from entry: FoodEntry) -> [UIImage] {
        var images: [UIImage] = []
        if let data = entry.imageData, let image = UIImage(data: data) {
            images.append(image)
        }
        images.append(contentsOf: entry.additionalImageData.compactMap { UIImage(data: $0) })
        if images.isEmpty {
            for filename in entry.allImageFilenames {
                guard let data = FoodImageStore.shared.load(filename: filename),
                      let image = UIImage(data: data) else { continue }
                images.append(image)
            }
        }
        return images
    }
}

struct MealPhotoViewerView: View {
    let images: [UIImage]
    @State private var currentIndex: Int
    @Environment(\.dismiss) private var dismiss
    @State private var dragOffset: CGFloat = 0

    init(images: [UIImage], startIndex: Int = 0) {
        self.images = images
        _currentIndex = State(initialValue: min(max(startIndex, 0), max(images.count - 1, 0)))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            TabView(selection: $currentIndex) {
                ForEach(Array(images.enumerated()), id: \.offset) { index, image in
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .offset(y: dragOffset)
            .opacity(1 - min(dragOffset / 300, 0.35))

            VStack {
                HStack {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .background(.black.opacity(0.45), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Close")

                    Spacer()

                    if images.count > 1 {
                        Text("\(currentIndex + 1)/\(images.count)")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(.black.opacity(0.45), in: Capsule())
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 12)

                Spacer()
            }
        }
        .gesture(
            DragGesture(minimumDistance: 20)
                .onChanged { value in
                    let vertical = value.translation.height
                    let horizontal = abs(value.translation.width)
                    guard vertical > 0, vertical > horizontal else { return }
                    dragOffset = vertical
                }
                .onEnded { value in
                    let vertical = value.translation.height
                    let horizontal = abs(value.translation.width)
                    if vertical > 120, vertical > horizontal {
                        dismiss()
                    } else {
                        withAnimation(.spring(response: 0.28, dampingFraction: 0.86)) {
                            dragOffset = 0
                        }
                    }
                }
        )
    }
}
