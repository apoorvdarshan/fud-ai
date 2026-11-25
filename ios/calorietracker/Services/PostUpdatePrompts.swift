import SwiftUI

/// Public community links shared by the About section and the one-time
/// "Meet the developer" prompt so the URLs live in exactly one place.
enum FudAILinks {
    private static let socialUsername = "fudaiapp"
    static let socialHandle = "@\(socialUsername)"
    static let x = URL(string: "https://x.com/\(socialUsername)")!
    static let instagram = URL(string: "https://www.instagram.com/\(socialUsername)/")!
    static let discord = URL(string: "https://discord.gg/Py4VrFctP3")!
    static let productHunt = URL(string: "https://www.producthunt.com/products/fud-ai")!
    static let appStore = URL(string: "https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726")!
}

/// The Hosted Plans invitation is for existing users only. Legacy markers are
/// kept for onboarding/storage compatibility, not to gate the 9.0 invitation.
enum PostUpdatePrompts {
    /// Versioned so the Hosted AI upsell is deliberately re-armed once for users who already
    /// dismissed it (the previous unversioned key was consumed the moment the alert was shown).
    /// Bump the suffix only when the upsell should fire again.
    static let hostedUpsellSeenKey = "hasSeenHostedUpsellPrompt.9.0"
    /// Named "completed" (not "seen") so only Done counts — opening Instagram must not consume it.
    static let meetDeveloperSeenKey = "hasCompletedMeetDeveloperPrompt"
    /// Separate from Meet the developer so people who already tapped Done still get the launch-day sheet.
    static let productHuntLaunchPromptSeenKey = "hasSeenProductHuntLaunchPrompt.2026-09-29"
    /// The Live Voice "What's new" pop-up. Set only when it is finished, so a swipe,
    /// a system tear-down or a killed app shows it again on the next launch.
    static let whatsNewSeenKey = "hasSeenWhatsNew.liveVoice"

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

    static var hasSeenWhatsNew: Bool {
        get { UserDefaults.standard.bool(forKey: whatsNewSeenKey) }
        set { UserDefaults.standard.set(newValue, forKey: whatsNewSeenKey) }
    }

    /// Called when onboarding completes so a brand-new user is never treated as
    /// an "existing user who just updated".
    static func markAllSeenForFreshInstall() {
        hasSeenHostedUpsell = true
        hasSeenMeetDeveloper = true
        hasSeenWhatsNew = true
    }

    /// An active Plus/Pro entitlement suppresses the invite, regardless of AI mode.
    /// A saved Hosted selection can outlive a subscription and does not suppress it.
    ///
    /// Also gated on RevenueCat actually resolving purchasable Plus/Pro packages: while the
    /// IAPs are not live the offerings come back empty, and showing the alert would only
    /// dead-end on "Plans are unavailable right now." In that case the prompt is left
    /// unconsumed so it appears once the plans go live.
    @MainActor
    static var isHostedUpsellEligible: Bool {
        shouldOfferHostedUpgrade(
            hasEntitlement: RevenueCatManager.shared.hasHostedEntitlement,
            hasPurchasablePlans: RevenueCatManager.shared.hasPurchasableHostedPlans
        )
    }

    static func shouldOfferHostedUpgrade(hasEntitlement: Bool, hasPurchasablePlans: Bool) -> Bool {
        !hasEntitlement && hasPurchasablePlans
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
                    linkRow("Follow on X", systemImage: "at", url: FudAILinks.x, handle: FudAILinks.socialHandle)
                    linkRow("Follow on Instagram", systemImage: "camera.fill", url: FudAILinks.instagram, handle: FudAILinks.socialHandle)
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

    private func linkRow(_ title: LocalizedStringKey, systemImage: String, url: URL, handle: String? = nil) -> some View {
        // Button + openURL keeps the sheet up when jumping to Instagram/X/etc.
        // (SwiftUI `Link` can dismiss the presenting sheet when leaving the app.)
        Button {
            openURL(url)
        } label: {
            Label {
                VStack(alignment: .leading) {
                    Text(title)
                    if let handle {
                        Text(verbatim: handle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
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
