import SwiftUI

/// Public community links shared by the About section and the one-time
/// "Meet the developer" prompt so the URLs live in exactly one place.
enum FudAILinks {
    static let x = URL(string: "https://x.com/apoorvdarshan")!
    static let instagram = URL(string: "https://www.instagram.com/apoorvcodes/")!
    static let discord = URL(string: "https://discord.gg/Py4VrFctP3")!
    static let productHunt = URL(string: "https://www.producthunt.com/products/fud-ai")!
}

/// One-time prompts shown to *existing* users the first time they open the app
/// after updating. Fresh installs never see them: onboarding marks every prompt
/// as seen right before it flips `hasCompletedOnboarding`, so only users whose
/// defaults predate these keys qualify. Same once-only pattern as ReviewPrompter.
enum PostUpdatePrompts {
    static let hostedUpsellSeenKey = "hasSeenHostedUpsellPrompt"
    /// Named "completed" (not "seen") so only Done counts — opening Instagram must not consume it.
    static let meetDeveloperSeenKey = "hasCompletedMeetDeveloperPrompt"
    /// Separate from Meet the developer so people who already tapped Done still get the launch-day sheet.
    static let productHuntLaunchPromptSeenKey = "hasSeenProductHuntLaunchPrompt.2026-09-29"

    static var hasSeenHostedUpsell: Bool {
        get { UserDefaults.standard.bool(forKey: hostedUpsellSeenKey) }
        set { UserDefaults.standard.set(newValue, forKey: hostedUpsellSeenKey) }
    }

    static var hasSeenMeetDeveloper: Bool {
        get { UserDefaults.standard.bool(forKey: meetDeveloperSeenKey) }
        set { UserDefaults.standard.set(newValue, forKey: meetDeveloperSeenKey) }
    }

    static var hasSeenProductHuntLaunchPrompt: Bool {
        get { UserDefaults.standard.bool(forKey: productHuntLaunchPromptSeenKey) }
        set { UserDefaults.standard.set(newValue, forKey: productHuntLaunchPromptSeenKey) }
    }

    /// Called when onboarding completes so a brand-new user is never treated as
    /// an "existing user who just updated".
    static func markAllSeenForFreshInstall() {
        hasSeenHostedUpsell = true
        hasSeenMeetDeveloper = true
    }

    /// The hosted upsell is only relevant to BYOK users without an active Plus/Pro
    /// entitlement. Anyone already on hosted has nothing to be upsold.
    @MainActor
    static var isHostedUpsellEligible: Bool {
        AIModeSettings.mode == .byok && !RevenueCatManager.shared.hasHostedEntitlement
    }
}

/// Friendly one-time sheet pointing existing users at the developer's socials
/// and the Product Hunt page. Links reuse `FudAILinks` (same as About).
/// Stays up while opening socials — only `onDone` (Done button) dismisses it.
struct MeetDeveloperSheet: View {
    var onDone: () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 10) {
                        Image("onboardingLogo")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 56, height: 56)
                            .accessibilityHidden(true)
                        Text("Hi, I'm Apoorv 👋")
                            .font(.system(.title3, design: .rounded, weight: .bold))
                        Text("I build Fud AI on my own, in the open. Come say hi, share feedback, or just follow along — it genuinely helps.")
                            .font(.system(.subheadline, design: .rounded))
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }

                Section {
                    linkRow("Follow on X", systemImage: "at", url: FudAILinks.x)
                    linkRow("Follow on Instagram", systemImage: "camera.fill", url: FudAILinks.instagram)
                    linkRow("Join Discord", systemImage: "bubble.left.and.bubble.right.fill", url: FudAILinks.discord)
                    linkRow("Vote on Product Hunt", systemImage: "hand.thumbsup.fill", url: FudAILinks.productHunt)
                }
                .listRowBackground(AppColors.appCard)
            }
            .navigationTitle("Meet the developer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onDone)
                }
            }
        }
        .presentationDetents([.medium, .large])
        // Swipe-down must not count as "seen" — only Done closes the prompt.
        .interactiveDismissDisabled()
    }

    private func linkRow(_ title: LocalizedStringKey, systemImage: String, url: URL) -> some View {
        // Button + openURL keeps the sheet up when jumping to Instagram/X/etc.
        // (SwiftUI `Link` can dismiss the presenting sheet when leaving the app.)
        Button {
            openURL(url)
        } label: {
            Label {
                Text(title)
            } icon: {
                Image(systemName: systemImage)
                    .foregroundStyle(AppColors.calorie)
            }
        }
        .tint(.primary)
    }
}

/// One-time launch-day sheet. Shows during the Product Hunt window even if Meet the developer was already dismissed.
struct ProductHuntLaunchSheet: View {
    var onVote: () -> Void
    var onNotNow: () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: 16) {
            Image("onboardingLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 56, height: 56)
                .accessibilityHidden(true)
            Text("Fud AI is live on Product Hunt")
                .font(.system(.title3, design: .rounded, weight: .bold))
                .multilineTextAlignment(.center)
            Text("We just launched. A vote helps more people find Fud AI.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button {
                openURL(FudAILinks.productHunt)
                onVote()
            } label: {
                Text("Vote on Product Hunt")
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(AppColors.calorie, in: RoundedRectangle(cornerRadius: 14))
            }
            Button("Not now", action: onNotNow)
                .font(.system(.body, design: .rounded, weight: .semibold))
                .foregroundStyle(.secondary)
        }
        .padding(24)
        .presentationDetents([.medium])
        .interactiveDismissDisabled()
    }
}
