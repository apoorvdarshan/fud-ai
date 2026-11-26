import SwiftUI

/// The floating entry point on Home and the workout diary. It animates gently
/// while its tab is on screen and stops drawing otherwise.
struct VoiceAssistantHomeButton: View {
    let controller: VoiceAssistantController
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(VoiceMascotPreferences.storageKey) private var mascotRaw = ""
    @State private var isVisible = false

    var body: some View {
        Button {
            AppHaptics.impact(.medium)
            controller.start()
        } label: {
            VoiceMascotView(mode: .idle, profile: VoiceMascotPreferences.decode(mascotRaw).profile)
            .environment(\.voiceAssistantAnimationActive, isVisible && scenePhase == .active && !reduceMotion)
            .frame(width: 60, height: 60)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Talk to Fud AI"))
        .accessibilityHint(Text("Starts the voice assistant"))
        .accessibilityIdentifier("voiceAssistantButton")
        .onAppear { isVisible = true }
        .onDisappear { isVisible = false }
    }
}
