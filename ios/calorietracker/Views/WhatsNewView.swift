import AVFoundation
import RevenueCat
import SwiftUI

// MARK: - Pages

/// One page of the Live Voice "What's new" pop-up.
nonisolated enum WhatsNewPage: Hashable, Sendable {
    case liveVoice
    /// Plus and Pro, and adding an own key when Live Voice can't start yet.
    case access
    case support
    case developer
}

/// What the access page can offer, from the subscription and the saved keys.
nonisolated struct WhatsNewAccess: Equatable, Sendable {
    /// Plus or Pro can be bought now: not subscribed, and the plans loaded.
    var canBuyPlans: Bool
    /// Live Voice can't start yet: no plan and no usable own key.
    var needsLiveVoiceSetup: Bool
}

nonisolated enum WhatsNewPages {
    /// - Parameter access: nil when the subscription couldn't be checked. The access page is
    ///   then left out, so a subscriber is never asked to subscribe.
    static func pages(access: WhatsNewAccess?) -> [WhatsNewPage] {
        var pages: [WhatsNewPage] = [.liveVoice]
        if let access, access.canBuyPlans || access.needsLiveVoiceSetup {
            pages.append(.access)
        }
        pages.append(contentsOf: [.support, .developer])
        return pages
    }
}

// MARK: - Access check

enum WhatsNewAccessCheck {
    /// Checks the subscription, the plans and the saved keys, giving up after `timeout` so an
    /// offline launch still shows the pop-up, just without the access page.
    static func current(timeout: Duration = .seconds(6)) async -> WhatsNewAccess? {
        await firstOf(timeout: timeout) {
            if Purchases.isConfigured {
                // A failed refresh says nothing about the subscription.
                guard await RevenueCatManager.shared.refreshCustomerInfo() else { return nil }
                if RevenueCatManager.shared.hasHostedEntitlement {
                    return WhatsNewAccess(canBuyPlans: false, needsLiveVoiceSetup: false)
                }
                await RevenueCatManager.shared.loadOfferings()
            }
            var needsSetup = false
            if case .needsAccess = VoiceAssistantRouting.current() { needsSetup = true }
            return WhatsNewAccess(
                canBuyPlans: Purchases.isConfigured && RevenueCatManager.shared.hasPurchasableHostedPlans,
                needsLiveVoiceSetup: needsSetup
            )
        }
    }

    /// The work's result, or nil when the timeout comes first.
    private static func firstOf<T: Sendable>(timeout: Duration, _ work: @escaping () async -> T?) async -> T? {
        await withCheckedContinuation { continuation in
            let gate = ResumeGate()
            Task {
                let value = await work()
                if gate.claim() { continuation.resume(returning: value) }
            }
            Task {
                try? await Task.sleep(for: timeout)
                if gate.claim() { continuation.resume(returning: nil) }
            }
        }
    }

    private final class ResumeGate {
        private var resumed = false

        func claim() -> Bool {
            guard !resumed else { return false }
            resumed = true
            return true
        }
    }
}

// MARK: - Sound

/// The demo starts with sound, following the Ring/Silent switch and mixing with other
/// audio like other autoplaying video, so opening it never stops the user's music. The
/// sound button plays it even on silent, taking over like any video.
enum WhatsNewAudio {
    enum Sound {
        case muted
        /// Sound on unless the phone is on silent.
        case followingSilentSwitch
        /// Sound on, even on silent.
        case on
    }

    private static var changedSession = false

    static func apply(_ sound: Sound) {
        // A live conversation owns the audio session.
        guard !VoiceAssistantController.shared.isActive else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            switch sound {
            case .muted:
                try session.setCategory(.ambient)
            case .followingSilentSwitch:
                // Ambient follows the switch and mixes, so silent autoplay never stops music.
                try session.setCategory(.ambient)
                try session.setActive(true)
            case .on:
                try session.setCategory(.playback, mode: .moviePlayback)
                try session.setActive(true)
            }
            changedSession = true
        } catch {
            // Playback still works with the current session.
        }
    }

    static func end() {
        guard changedSession, !VoiceAssistantController.shared.isActive else { return }
        changedSession = false
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        try? session.setCategory(.soloAmbient)
    }
}

// MARK: - Sheet

/// The one-time pop-up existing users see after the Live Voice update. Only Close on the last
/// page (or Add an API Key) finishes it, so a swipe, a system tear-down or a killed app shows it
/// again on the next launch.
struct WhatsNewSheet: View {
    let access: WhatsNewAccess?
    @Binding var selection: Int
    var onClose: () -> Void
    var onAddKey: () -> Void

    @State private var muted = false
    @State private var showPlans = false
    @Environment(\.scenePhase) private var scenePhase

    private var pages: [WhatsNewPage] { WhatsNewPages.pages(access: access) }
    private var isLast: Bool { selection >= pages.count - 1 }

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $selection) {
                ForEach(Array(pages.enumerated()), id: \.element) { index, page in
                    pageView(page, isCurrent: selection == index)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            pageDots
                .padding(.top, 6)
                .padding(.bottom, 14)

            controls
                .padding(.horizontal, 24)
                .padding(.bottom, 12)
        }
        .background(AppColors.appBackground.ignoresSafeArea())
        .presentationDetents([.large])
        .interactiveDismissDisabled()
        .sheet(isPresented: $showPlans) {
            HostedPaywallView()
        }
        .onAppear { WhatsNewAudio.apply(.followingSilentSwitch) }
        .onDisappear { WhatsNewAudio.end() }
        .onChange(of: selection) { _, page in
            if page != 0 { muted = true }
        }
        .onChange(of: muted) { _, isMuted in
            WhatsNewAudio.apply(isMuted ? .muted : .on)
        }
        .onChange(of: scenePhase) { _, phase in
            // Leaving the app turns the sound back off, so other audio is never held up.
            if phase != .active { muted = true }
        }
    }

    @ViewBuilder
    private func pageView(_ page: WhatsNewPage, isCurrent: Bool) -> some View {
        switch page {
        case .liveVoice:
            WhatsNewLiveVoicePage(isCurrent: isCurrent, muted: $muted)
        case .access:
            if let access {
                WhatsNewAccessPage(
                    access: access,
                    isCurrent: isCurrent,
                    onPlans: { showPlans = true },
                    onAddKey: onAddKey
                )
            }
        case .support:
            WhatsNewSupportPage()
        case .developer:
            WhatsNewDeveloperPage()
        }
    }

    private var pageDots: some View {
        HStack(spacing: 7) {
            ForEach(pages.indices, id: \.self) { index in
                Capsule()
                    .fill(index == selection ? AppColors.calorie : Color.secondary.opacity(0.3))
                    .frame(width: index == selection ? 20 : 7, height: 7)
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: selection)
        .accessibilityHidden(true)
    }

    private var controls: some View {
        HStack(spacing: 12) {
            if selection > 0 {
                Button {
                    withAnimation(.easeInOut(duration: 0.3)) { selection -= 1 }
                } label: {
                    WhatsNewButtonLabel(title: "Previous", prominent: false)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("whatsNew.previous")
            }
            Button {
                if isLast {
                    onClose()
                } else {
                    withAnimation(.easeInOut(duration: 0.3)) { selection += 1 }
                }
            } label: {
                WhatsNewButtonLabel(title: isLast ? "Close" : "Next", prominent: true)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier(isLast ? "whatsNew.close" : "whatsNew.next")
        }
    }
}

// MARK: - Page 1: Live Voice

private struct WhatsNewLiveVoicePage: View {
    let isCurrent: Bool
    @Binding var muted: Bool
    @Environment(\.scenePhase) private var scenePhase

    private static let videoURL = Bundle.main.url(forResource: "live_voice_whats_new", withExtension: "mp4")

    var body: some View {
        GeometryReader { proxy in
            let videoHeight = min(max(proxy.size.height - 250, 220), 560)
            ScrollView(showsIndicators: false) {
                VStack(spacing: 16) {
                    video
                        .frame(width: videoHeight * 9 / 16, height: videoHeight)
                    VStack(spacing: 8) {
                        Text("New: Live Voice")
                            .font(.system(.title2, design: .rounded, weight: .bold))
                            .accessibilityAddTraits(.isHeader)
                        Text("Talk to Fud AI to log meals, scan barcodes and check your progress.")
                            .font(.system(.body, design: .rounded))
                            .foregroundStyle(.secondary)
                        Text("Tap the orb on Home to start.")
                            .font(.system(.body, design: .rounded, weight: .semibold))
                        Text("Included with Plus and Pro, or use your own AI key.")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.secondary)
                            .padding(.top, 2)
                    }
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 24)
                .padding(.top, 24)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private var video: some View {
        ZStack(alignment: .bottomTrailing) {
            if let url = Self.videoURL {
                LoopingVideoView(url: url, muted: muted, playing: isCurrent && scenePhase == .active)
                    .accessibilityElement()
                    .accessibilityLabel(Text("Live Voice demo video"))
            } else {
                Color.black
            }
            Button {
                muted.toggle()
            } label: {
                Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(.black.opacity(0.5), in: Circle())
            }
            .buttonStyle(.plain)
            .padding(10)
            .accessibilityLabel(muted ? Text("Turn sound on") : Text("Turn sound off"))
            .accessibilityIdentifier("whatsNew.sound")
        }
        .background(Color.black)
        .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .strokeBorder(.white.opacity(0.08))
        }
        .shadow(color: .black.opacity(0.22), radius: 18, y: 8)
    }
}

/// A looping, muted-by-default video for the pop-up.
private struct LoopingVideoView: UIViewRepresentable {
    let url: URL
    let muted: Bool
    let playing: Bool

    func makeUIView(context: Context) -> PlayerView {
        PlayerView(url: url)
    }

    func updateUIView(_ view: PlayerView, context: Context) {
        view.player.isMuted = muted
        if playing {
            view.player.play()
        } else {
            view.player.pause()
        }
    }

    static func dismantleUIView(_ view: PlayerView, coordinator: ()) {
        view.player.pause()
    }

    final class PlayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        let player = AVQueuePlayer()
        private var looper: AVPlayerLooper?

        init(url: URL) {
            super.init(frame: .zero)
            backgroundColor = .black
            player.isMuted = true
            player.preventsDisplaySleepDuringVideoPlayback = false
            looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(url: url))
            let playerLayer = layer as? AVPlayerLayer
            playerLayer?.player = player
            playerLayer?.videoGravity = .resizeAspectFill
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) is not used")
        }
    }
}

// MARK: - Page 2: getting Live Voice

private struct WhatsNewAccessPage: View {
    let access: WhatsNewAccess
    let isCurrent: Bool
    var onPlans: () -> Void
    var onAddKey: () -> Void

    private static let ownKeyProviders: [AIProvider] = [.gemini, .openai, .xai, .qwen]

    var body: some View {
        WhatsNewPageScroll {
            LiveOrbView(
                mode: .listening,
                tint: AppColors.calorie,
                isPaused: !isCurrent,
                minimumFrameInterval: 1.0 / 30,
                clearBackground: true
            )
            .frame(width: 180, height: 180)
            .accessibilityHidden(true)

            VStack(spacing: 8) {
                Group {
                    if access.needsLiveVoiceSetup {
                        Text("Get Live Voice")
                    } else {
                        Text("Switch to Plus or Pro")
                    }
                }
                .font(.system(.title2, design: .rounded, weight: .bold))
                .accessibilityAddTraits(.isHeader)
                Group {
                    if !access.needsLiveVoiceSetup {
                        Text("Plus and Pro run the AI for you, with Live Voice included and no API keys to manage. Fud AI stays free with your own keys.")
                    } else if access.canBuyPlans {
                        Text("Pick Plus or Pro and we run the AI for you, or add your own Gemini, OpenAI, xAI or Qwen API key.")
                    } else {
                        Text("Add your own Gemini, OpenAI, xAI or Qwen API key to start using Live Voice.")
                    }
                }
                .font(.system(.body, design: .rounded))
                .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)

            VStack(spacing: 10) {
                // Only plans that can actually be bought; otherwise the paywall would be empty.
                if access.canBuyPlans {
                    Button(action: onPlans) {
                        WhatsNewButtonLabel(title: "See Plus & Pro Plans", systemImage: "sparkles", prominent: true)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("whatsNew.plans")
                }

                if access.needsLiveVoiceSetup {
                    Button(action: onAddKey) {
                        WhatsNewButtonLabel(title: "Add an API Key", systemImage: "key.fill", prominent: !access.canBuyPlans)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("whatsNew.addKey")

                    HStack(spacing: 16) {
                        ForEach(Self.ownKeyProviders, id: \.self) { provider in
                            AIProviderBrandIcon(provider: provider, size: 22)
                        }
                    }
                    .padding(.top, 4)
                    .accessibilityHidden(true)
                }
            }
        }
    }
}

// MARK: - Page 3: support

private struct WhatsNewSupportPage: View {
    @State private var tipJar = TipJarStore()
    @State private var showShareSheet = false
    @Environment(\.openURL) private var openURL

    private var shareMessage: String {
        String(localized: "I've been tracking my meals with Fud AI — snap a photo, speak it, or type it, and the AI logs the calories. It's free, open source, and your data stays on your device.\n\nDownload: https://fud-ai.app")
    }

    var body: some View {
        WhatsNewPageScroll {
            HStack(spacing: 14) {
                ForEach(TipJarStore.tiers) { tier in
                    Image(tier.icon)
                        .resizable()
                        .interpolation(.none)
                        .scaledToFit()
                        .frame(width: 44, height: 44)
                }
            }
            .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("Support Fud AI")
                    .font(.system(.title2, design: .rounded, weight: .bold))
                    .accessibilityAddTraits(.isHeader)
                Text("Fud AI is free and built by one developer. A rating, a share or a small tip helps keep it going.")
                    .font(.system(.body, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 10) {
                Button {
                    AppRating.rate(openURL: openURL)
                } label: {
                    WhatsNewButtonLabel(title: "Rate the App", systemImage: "star.fill", prominent: false)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("whatsNew.rate")
                Button {
                    showShareSheet = true
                } label: {
                    WhatsNewButtonLabel(title: "Share the App", systemImage: "square.and.arrow.up", prominent: false)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("whatsNew.share")
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("Leave a Tip")
                    .font(.system(.footnote, design: .rounded, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                    .accessibilityAddTraits(.isHeader)
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                    ForEach(TipJarStore.tiers) { tier in
                        tipButton(tier)
                    }
                }
            }
        }
        .task {
            await tipJar.loadIfNeeded()
        }
        .alert("Thank you!", isPresented: $tipJar.didTip) {
            Button("Done", role: .cancel) {}
        } message: {
            Text("Your support keeps Fud AI free for everyone.")
        }
        .sheet(isPresented: $showShareSheet) {
            ActivityShareSheet(activityItems: [shareMessage, FudAILinks.appStore])
        }
    }

    private func tipButton(_ tier: TipJarStore.Tier) -> some View {
        let product = tipJar.products[tier.productID]
        return Button {
            if let product { Task { await tipJar.purchase(product) } }
        } label: {
            VStack(spacing: 6) {
                Image(tier.icon)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
                    .frame(width: 30, height: 30)
                Text(tier.name)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Group {
                    if tipJar.purchasingID == tier.productID || (product == nil && tipJar.isLoading) {
                        ProgressView()
                            .controlSize(.small)
                    } else if let product {
                        Text(product.localizedPriceString)
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .foregroundStyle(AppColors.calorie)
                    } else {
                        Image(systemName: "wifi.slash")
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(height: 20)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(product == nil || tipJar.purchasingID != nil)
        .accessibilityIdentifier("whatsNew.tip.\(tier.icon)")
    }
}

// MARK: - Page 4: the developer and the community

private struct WhatsNewDeveloperPage: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        WhatsNewPageScroll {
            Image("onboardingLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 64, height: 64)
                .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("Meet the developer")
                    .font(.system(.footnote, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .textCase(.uppercase)
                Text("Hi, I'm Apoorv 👋")
                    .font(.system(.title2, design: .rounded, weight: .bold))
                    .accessibilityAddTraits(.isHeader)
                Text("I build Fud AI on my own, in the open. Come say hi, share feedback, or just follow along — it genuinely helps.")
                    .font(.system(.body, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)

            VStack(spacing: 0) {
                socialRow("Follow on X", handle: "@apoorvdarshan", systemImage: "at", url: FudAILinks.x)
                Divider().padding(.leading, 52)
                socialRow("Follow on Instagram", handle: "@apoorvcodes", systemImage: "camera.fill", url: FudAILinks.instagram)
            }
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))

            VStack(spacing: 10) {
                Label {
                    Text("Join the Fud AI community")
                        .font(.system(.headline, design: .rounded))
                } icon: {
                    Image(systemName: "bubble.left.and.bubble.right.fill")
                        .foregroundStyle(AppColors.calorie)
                }
                Text("Get help, share ideas and meet other Fud AI users on Discord.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    openURL(FudAILinks.discord)
                } label: {
                    WhatsNewButtonLabel(title: "Join Discord", prominent: true)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("whatsNew.discord")
            }
            .padding(16)
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    private func socialRow(_ title: LocalizedStringKey, handle: String, systemImage: String, url: URL) -> some View {
        Button {
            openURL(url)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(.primary)
                    Text(verbatim: handle)
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "arrow.up.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Shared pieces

/// Centres a page vertically and scrolls when it doesn't fit (small phones, large text).
private struct WhatsNewPageScroll<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        GeometryReader { proxy in
            ScrollView(showsIndicators: false) {
                VStack(spacing: 18) {
                    content
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 24)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
        }
    }
}

private struct WhatsNewButtonLabel: View {
    let title: LocalizedStringKey
    var systemImage: String?
    let prominent: Bool

    var body: some View {
        HStack(spacing: 8) {
            if let systemImage {
                Image(systemName: systemImage)
            }
            Text(title)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .font(.system(.body, design: .rounded, weight: .semibold))
        .foregroundStyle(prominent ? Color.white : Color.primary)
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity)
        .frame(minHeight: 52)
        .background(
            prominent ? AnyShapeStyle(AppColors.calorie) : AnyShapeStyle(Color.secondary.opacity(0.14)),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
        .contentShape(Rectangle())
    }
}
