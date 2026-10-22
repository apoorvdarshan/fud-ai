import SwiftUI

struct AnalyzingView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let image: UIImage?
    var message: String = String(localized: "Analyzing your food...")
    /// Tracker of the running analysis. The view reads its state directly, so the
    /// sheet updates as soon as the analysis reports a new stage.
    var progressTracker: AnalysisProgressTracker?
    /// A slow or stalled provider used to leave this screen up with no way out (#357).
    var onCancel: (() -> Void)? = nil

    var body: some View {
        // The content is centred while it fits, but a very large Dynamic Type size must
        // not push the Cancel button off the screen. The GeometryReader gives the inner
        // stack the height of the viewport, so the spacers can centre the content; once
        // the content is taller, the ScrollView takes over.
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 24) {
                    Spacer(minLength: 24)

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

                    // With reduced motion on, a static icon replaces the spinner: the
                    // stage and the elapsed time still show the progress.
                    if reduceMotion {
                        Image(systemName: "hourglass")
                            .font(.system(size: 34))
                            .foregroundStyle(AppColors.calorie)
                            .frame(width: 40, height: 40)
                            .accessibilityHidden(true)
                    } else {
                        ProgressView()
                            .controlSize(.large)
                            .tint(AppColors.calorie)
                    }

                    if let state = progressTracker?.state {
                        AnalysisProgressView(state: state)
                    } else {
                        Text(message)
                            .font(.headline)
                            // The calorie accent is too light for text on the light theme.
                            .foregroundStyle(.primary)
                    }

                    if let onCancel {
                        Button("Cancel", action: onCancel)
                            .font(.body.weight(.medium))
                            .foregroundStyle(Color.primary.opacity(0.8))
                            .padding(.top, 8)
                    }

                    Spacer(minLength: 24)
                }
                .frame(maxWidth: .infinity)
                .frame(minHeight: geometry.size.height)
            }
        }
        .background(.background)
    }
}
