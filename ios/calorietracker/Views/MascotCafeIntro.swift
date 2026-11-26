import AVKit
import SwiftUI

struct MascotCafeInvite: View {
    let onWatched: () -> Void
    let finish: () -> Void
    private enum Page { case preview, video, community, tips, rating }
    @State private var page: Page = .preview
    @Environment(\.openURL) private var openURL

    var body: some View {
        if page == .video, let url = Bundle.main.url(forResource: "mascot_cafe", withExtension: "mp4") {
            MascotCafeIntro(url: url) {
                onWatched()
                page = .community
            }
                .presentationDetents([.large])
        } else if page == .community || page == .tips || page == .rating {
            VStack(spacing: 12) {
                if page == .tips {
                    List { TipJarSettingsSection() }
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            Image(systemName: page == .community ? "person.3.fill" : "star.fill")
                                .font(.largeTitle).foregroundStyle(AppColors.calorie).accessibilityHidden(true)
                            if page == .community {
                                Text("Community").font(.title2.bold())
                                Link(destination: FudAILinks.x) { Label("Follow on X", systemImage: "at") }
                                Link(destination: FudAILinks.instagram) { Label("Follow on Instagram", systemImage: "camera") }
                                Link(destination: FudAILinks.discord) { Label("Join Discord", systemImage: "bubble.left.and.bubble.right") }
                            } else {
                                Button("Rate the App") { AppRating.rate(openURL: openURL) }
                                    .buttonStyle(.borderedProminent)
                            }
                        }
                        .padding(24)
                    }
                }
                if page == .rating {
                    Button("Done", action: finish)
                } else {
                    Button("Continue") { page = page == .community ? .tips : .rating }
                        .buttonStyle(.borderedProminent)
                    Button("Done", action: finish)
                }
            }
            .padding(.vertical, 16)
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        } else {
            ScrollView {
              VStack(spacing: 14) {
                Button { page = .video } label: {
                    Image("MascotCafePreview")
                        .resizable()
                        .scaledToFit()
                        .overlay(alignment: .bottomLeading) {
                            Label {
                                Text("Play video", tableName: "MascotCafe")
                            } icon: { Image(systemName: "play.fill") }
                                .font(.headline)
                                .padding(.horizontal, 14).padding(.vertical, 9)
                                .foregroundStyle(.white)
                                .background(.black.opacity(0.8), in: Capsule())
                                .padding(12)
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 20))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Play video", tableName: "MascotCafe"))
                Text("Meet the voice mascots", tableName: "MascotCafe").font(.title3.bold())
                Button("Not now", action: finish)
              }
              .frame(maxWidth: 390)
              .padding(18)
              .frame(maxWidth: .infinity)
            }
            .presentationDetents([.height(385), .large])
            .presentationDragIndicator(.visible)
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
