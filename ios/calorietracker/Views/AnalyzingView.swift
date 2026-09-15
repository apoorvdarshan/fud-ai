import SwiftUI

struct AnalyzingView: View {
    let image: UIImage?
    var message: String = "Analyzing your food..."
    /// A slow or stalled provider used to leave this sheet up with no way out (#357).
    var onCancel: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 250, maxHeight: 250)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .shadow(radius: 8)
            } else {
                Image(systemName: "text.magnifyingglass")
                    .font(.system(size: 64))
                    .foregroundStyle(AppColors.calorie)
                    .frame(maxWidth: 250, maxHeight: 250)
            }

            ProgressView()
                .controlSize(.large)
                .tint(AppColors.calorie)

            Text(message)
                .font(.headline)
                .foregroundStyle(AppColors.calorie)

            if let onCancel {
                Button("Cancel", action: onCancel)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
            }

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background)
    }
}
