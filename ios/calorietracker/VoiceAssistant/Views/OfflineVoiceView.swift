import AVFAudio
import SwiftUI

struct OfflineVoiceView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var voice = OfflineVoiceController()
    private let model = LocalSpeechModelManager.lfm3
    private let assistant = VoiceAssistantController.shared

    var body: some View {
        List {
            Section {
                LocalSpeechModelSettingsView(manager: model, onAvailabilityChange: {}, inUse: voice.isBusy)
            }
            .listRowBackground(AppColors.appCard)
            if model.model.isEligible() && model.isInstalled {
                Section {
                    switch voice.phase {
                    case .idle:
                        Button {
                            voice.start()
                        } label: {
                            Label {
                                Text("Speak", tableName: "OfflineVoice")
                            } icon: {
                                Image(systemName: "mic")
                            }
                        }
                        .disabled(voice.isBusy || assistant.isBusy)
                        .accessibilityIdentifier("settings.offlineVoice.speak")
                    case .requesting:
                        ProgressView()
                    case .recording:
                        Button { voice.send() } label: {
                            Text("Send recording", tableName: "OfflineVoice")
                        }
                        Button("Cancel", role: .cancel) { voice.stop() }
                    case .working:
                        ProgressView {
                            Text("Preparing reply on this device…", tableName: "OfflineVoice")
                        }
                        Button("Cancel", role: .cancel) { voice.stop() }
                    case .speaking:
                        Button { voice.stop() } label: { Text("Stop audio", tableName: "OfflineVoice") }
                    }
                    if let failure = voice.failure {
                        switch failure {
                        case .microphone: Text("Allow microphone access in Settings.", tableName: "OfflineVoice")
                        case .recording: Text("Couldn’t capture audio. Try speaking for at least one second.", tableName: "OfflineVoice")
                        case .reply: Text("Couldn’t generate or play a spoken reply.", tableName: "OfflineVoice")
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle(Text("Offline Voice", tableName: "OfflineVoice"))
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { voice.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background || (phase == .inactive && voice.phase != .requesting) { voice.stop() }
        }
        .onChange(of: assistant.isBusy) { _, busy in if busy { voice.stop() } }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { note in
            if note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt == AVAudioSession.InterruptionType.began.rawValue {
                voice.stop()
            }
        }
    }
}

struct OfflineAgentControls: View {
    let voice: OfflineVoiceController

    var body: some View {
        Group {
            switch voice.phase {
            case .idle:
                Button { voice.start() } label: { Text("Speak", tableName: "OfflineVoice") }
                    .disabled(voice.isBusy)
                    .accessibilityIdentifier("voiceAssistant.offline.speak")
            case .requesting: ProgressView()
            case .recording:
                Button { voice.send() } label: { Text("Send recording", tableName: "OfflineVoice") }
                Button("Cancel") { voice.stop() }
            case .working:
                ProgressView { Text("Preparing reply on this device…", tableName: "OfflineVoice") }
                Button("Cancel") { voice.stop() }
            case .speaking:
                Button { voice.stop() } label: { Text("Stop audio", tableName: "OfflineVoice") }
            }
            if let failure = voice.failure {
                switch failure {
                case .microphone: Text("Allow microphone access in Settings.", tableName: "OfflineVoice")
                case .recording: Text("Couldn’t capture audio. Try speaking for at least one second.", tableName: "OfflineVoice")
                case .reply: Text("Couldn’t generate or play a spoken reply.", tableName: "OfflineVoice")
                }
            }
        }
    }
}
