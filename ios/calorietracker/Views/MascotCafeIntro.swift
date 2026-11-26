import AVKit
import SwiftUI

struct MascotCafeIntro: View {
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
