import SwiftUI

/// The floating entry point on Home and the workout diary. It animates gently
/// while its tab is on screen and stops drawing otherwise.
struct VoiceAssistantHomeButton: View {
    let controller: VoiceAssistantController
    var pinkNeoPalette: PinkNeoPalette? = nil
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isVisible = false

    var body: some View {
        Button {
            AppHaptics.impact(.medium)
            controller.start()
        } label: {
            if let palette = pinkNeoPalette {
                Image(systemName: "waveform")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(palette.ink)
                    .frame(width: 60, height: 60)
                    .background(palette.panel)
                    .pinkNeoRule(palette.rule, width: 1.5)
                    .contentShape(Rectangle())
            } else {
                LiveOrbView(
                    mode: .idle,
                    tint: AppColors.calorie,
                    isPaused: !isVisible || scenePhase != .active || reduceMotion,
                    minimumFrameInterval: 1.0 / 30,
                    clearBackground: true
                )
                // Keep the Workout entry point unchanged.
                .scaleEffect(1 / 0.56)
                .frame(width: 60, height: 60)
                .contentShape(Circle())
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Talk to Fud AI"))
        .accessibilityHint(Text("Starts the voice assistant"))
        .accessibilityIdentifier("voiceAssistantButton")
        .onAppear { isVisible = true }
        .onDisappear { isVisible = false }
    }
}
