import AVKit
import SwiftUI

struct MascotCafeInvite: View {
    let finish: () -> Void
    @State private var playing = false

    var body: some View {
        if playing, let url = Bundle.main.url(forResource: "mascot_cafe", withExtension: "mp4") {
            MascotCafeIntro(url: url, finish: finish)
                .presentationDetents([.large])
        } else {
            VStack(spacing: 12) {
                Image("VoiceMascotTeddyColor").resizable().scaledToFit().frame(height: 62)
                    .accessibilityHidden(true)
                Text("Meet the voice mascots", tableName: "MascotCafe").font(.headline)
                Button { playing = true } label: {
                    Label { Text("Play video", tableName: "MascotCafe") } icon: { Image(systemName: "play.fill") }
                }
                .buttonStyle(.borderedProminent)
                Button("Not now", action: finish)
            }
            .presentationDetents([.height(250), .medium])
        }
    }
}

private struct MascotCafeIntro: View {
    let url: URL
    let finish: () -> Void
    @Environment(\.scenePhase) private var phase
    @State private var player: AVPlayer?

    var body: some View {
        VStack(alignment: .trailing) {
            Button("Skip", action: finish)
                .buttonStyle(.bordered)
                .tint(.white)
                .padding(.horizontal)
            if let player {
                VideoPlayer(player: player)
                    .onReceive(NotificationCenter.default.publisher(
                        for: .AVPlayerItemDidPlayToEndTime, object: player.currentItem
                    )) { _ in finish() }
                    .onReceive(NotificationCenter.default.publisher(
                        for: .AVPlayerItemFailedToPlayToEndTime, object: player.currentItem
                    )) { _ in finish() }
            } else {
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.ignoresSafeArea())
        .onAppear {
            let introPlayer = AVPlayer(url: url)
            introPlayer.isMuted = false
            player = introPlayer
            if phase == .active { startPlayback() }
        }
        .onChange(of: phase) { _, phase in
            if phase == .active { startPlayback() } else { stopPlayback() }
        }
        .onDisappear { stopPlayback() }
    }

    private func startPlayback() {
        WhatsNewAudio.apply(.on)
        player?.play()
    }

    private func stopPlayback() {
        player?.pause()
        WhatsNewAudio.end()
    }
}
