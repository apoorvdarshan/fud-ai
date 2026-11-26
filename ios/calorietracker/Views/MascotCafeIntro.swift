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
            player = AVPlayer(url: url)
            if phase == .active { player?.play() }
        }
        .onChange(of: phase) { _, phase in
            if phase == .active { player?.play() } else { player?.pause() }
        }
        .onDisappear { player?.pause() }
    }
}
