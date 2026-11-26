//
//  HostedAISettingsView.swift
//  calorietracker
//

import SwiftUI
import RevenueCat

struct HostedAISettingsView: View {
    @State private var rc = RevenueCatManager.shared
    @State private var quotaManager = HostedAIQuotaManager.shared
    @State private var aiMode = AIModeSettings.mode
    @State private var showPaywall = false
    @State private var showCredits = false
    @State private var isRestoring = false
    @State private var restoreMessage: String?

    /// Server-reported numbers (the Worker owns the ledger); cached for display.
    private var quota: HostedAIQuotaSnapshot {
        quotaManager.snapshot(plan: rc.activePlan)
    }

    var body: some View {
        List {
            Section {
                Picker("AI Mode", selection: $aiMode) {
                    ForEach(AIMode.allCases) { mode in
                        Text(mode.displayName).tag(mode)
                    }
                }
                .onChange(of: aiMode) { _, newValue in
                    if newValue == .hosted && !rc.hasHostedEntitlement {
                        aiMode = .byok
                        showPaywall = true
                    } else {
                        AIModeSettings.mode = newValue
                    }
                }
            } footer: {
                Text("BYOK uses your own provider keys with no limits. Hosted uses Fud AI’s Plus/Pro plan and burns your daily pool, then credit bank.")
            }

            if aiMode == .hosted {
                Section {
                    LabeledContent("Plan", value: rc.activePlan.displayName)
                    if let end = rc.trialEndsAt {
                        LabeledContent("Trial ends", value: end.formatted(date: .abbreviated, time: .shortened))
                    }
                    LabeledContent("Today (UTC)", value: "\(quota.dailyUsed)/\(quota.dailyLimit)")
                    LabeledContent("Credit bank", value: "\(quota.creditBank)")
                } header: {
                    Text("Hosted Plan")
                } footer: {
                    Text("Usage is metered by Fud AI’s server per AI call and resets at midnight UTC. Coach replies that need several tool calls use several actions.")
                }

                Section {
                    Button("Subscribe or Upgrade") { showPaywall = true }
                    Button("Buy Credits") { showCredits = true }
                        .disabled(!rc.hasHostedEntitlement)
                }
            }

            Section {
                Button("Restore Purchases") {
                    Task { await restore() }
                }
                .disabled(isRestoring)
            }
        }
        .navigationTitle("AI Access")
        .listRowBackground(AppColors.appCard)
        .task {
            await rc.refreshCustomerInfo()
            await rc.loadOfferings()
            if rc.hasHostedEntitlement {
                await quotaManager.refresh()
            }
        }
        .sheet(isPresented: $showPaywall) {
            HostedPaywallView()
        }
        .sheet(isPresented: $showCredits) {
            HostedCreditsSheet()
        }
        .alert("Restore Purchases", isPresented: Binding(
            get: { restoreMessage != nil },
            set: { if !$0 { restoreMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(restoreMessage ?? "")
        }
    }

    private func restore() async {
        isRestoring = true
        defer { isRestoring = false }
        do {
            try await rc.restorePurchases()
            restoreMessage = String(localized: "Purchases restored.")
        } catch {
            restoreMessage = error.localizedDescription
        }
    }
}

// MARK: - Paywall model types
//
// Release-build crash history for this sheet (device SIGSEGV on open):
//
// 1. A `private enum BillingPeriod` nested inside `HostedPaywallView`, used as
//    a `ForEach` id (`\.id`, then `\.rawValue`), died in `_swift_getKeyPath`.
// 2. After that, `private func periodButton(_:) -> some View` whose body
//    changed shape (`if period == .yearly, let savings { Text }`) plus
//    `matchedGeometryEffect` died in `swift_getOpaqueTypeMetadata` /
//    `__swift_instantiateGenericMetadata`.
//
// Rules for everything below, in order of the incidents above:
// - Billing/catalog types live at file scope, never nested in a view.
// - No `ForEach` over app or RevenueCat types. Fixed lists are spelled out
//   explicitly; dynamic lists iterate `[String]` with `id: \.self` only.
// - Repeated UI is a concrete `struct … : View` with plain stored properties,
//   not a `some View` helper. Each body has ONE shape: optional decorations are
//   always in the tree and collapsed with `opacity` / zero width when absent.
// - No `@Namespace` / `matchedGeometryEffect`; selection is a plain background.
// - No custom `ButtonStyle`; buttons are `.plain` and styled inline.

/// Billing term for a hosted subscription package.
enum HostedBillingPeriod: String, CaseIterable {
    case monthly
    case yearly

    var title: String {
        switch self {
        case .monthly: String(localized: "Monthly")
        case .yearly: String(localized: "Yearly")
        }
    }

    var perUnit: String {
        switch self {
        case .monthly: String(localized: "per month")
        case .yearly: String(localized: "per year")
        }
    }

    /// RevenueCat package types first, then StoreKit's subscription period,
    /// then the product-id suffix so custom package identifiers still map.
    init?(package: Package) {
        switch package.packageType {
        case .monthly:
            self = .monthly
            return
        case .annual:
            self = .yearly
            return
        default:
            break
        }
        if let period = package.storeProduct.subscriptionPeriod {
            switch (period.unit, period.value) {
            case (.month, 1):
                self = .monthly
                return
            case (.year, 1), (.month, 12):
                self = .yearly
                return
            default:
                break
            }
        }
        let productID = package.storeProduct.productIdentifier
        if productID.hasSuffix(".yearly") {
            self = .yearly
        } else if productID.hasSuffix(".monthly") {
            self = .monthly
        } else {
            return nil
        }
    }
}

/// What the `plus` / `pro` RevenueCat offerings resolved to, split into
/// subscriptions (by plan and term) and one-time credit packs. Credit packs are
/// exposed as plain product-identifier strings so the paywall can key rows on
/// `String` rather than on RevenueCat's Objective-C `Package` class.
struct HostedPaywallCatalog {
    var subscriptions: [HostedPlan: [HostedBillingPeriod: Package]] = [:]
    /// Deduplicated credit pack product identifiers, ascending by credit amount.
    var creditPackIDs: [String] = []
    var creditPacks: [String: Package] = [:]

    var hasSubscriptions: Bool { subscriptions.values.contains { !$0.isEmpty } }
    var hasCreditPacks: Bool { !creditPackIDs.isEmpty }
}

// MARK: - Paywall

/// Hosted AI paywall: Fud AI logo hero → Plus/Pro plan cards with a
/// monthly/yearly toggle → pinned subscribe CTA, with credit packs demoted to a
/// secondary section. Styled like the rest of the app (app card / accent
/// colour, rounded fonts, minimal chrome). Purchase/restore plumbing is
/// unchanged from the original List-based sheet; only the presentation differs.
struct HostedPaywallView: View {
    private static let plans: [HostedPlan] = [.plus, .pro]
    private static let termsURL = URL(string: "https://fud-ai.app/terms.html")!
    private static let privacyURL = URL(string: "https://fud-ai.app/privacy.html")!

    @Environment(\.dismiss) private var dismiss
    var onSubscribed: (() -> Void)? = nil
    @State private var rc = RevenueCatManager.shared
    @State private var purchasingID: String?
    @State private var errorMessage: String?
    @State private var isRestoring = false
    @State private var restoreMessage: String?
    @State private var selectedPlan: HostedPlan = .plus
    @State private var selectedPeriod: HostedBillingPeriod = .yearly
    @State private var didFinishInitialLoad = false

    private var isBusy: Bool { purchasingID != nil || isRestoring }

    var body: some View {
        NavigationStack {
            ScrollView(showsIndicators: false) {
                VStack(spacing: 24) {
                    hero
                    plansSection
                    if catalog.hasSubscriptions {
                        featureList
                    }
                    if catalog.hasCreditPacks {
                        creditPacksSection
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .padding(.bottom, 12)
            }
            .background(AppColors.appBackground.ignoresSafeArea())
            .safeAreaInset(edge: .bottom, spacing: 0) {
                purchaseBar
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title2)
                            .symbolRenderingMode(.hierarchical)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityLabel("Close")
                }
            }
            .task {
                await rc.loadOfferings()
                didFinishInitialLoad = true
                // Subscribers opening this sheet are here to upgrade; start them on Pro.
                if rc.activePlan == .plus {
                    selectedPlan = .pro
                }
                reconcileSelection()
            }
            .onChange(of: selectedPeriod) { _, _ in
                reconcileSelection()
            }
            .onChange(of: rc.offerings) { _, _ in
                reconcileSelection()
            }
            .alert("Purchase", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
            .alert("Restore Purchases", isPresented: Binding(
                get: { restoreMessage != nil },
                set: { if !$0 { restoreMessage = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(restoreMessage ?? "")
            }
        }
    }

    // MARK: Hero

    /// Same logo treatment as About / Meet the developer: the real app mark,
    /// a short title, one quiet line. No decorative tile, glow or badges.
    private var hero: some View {
        VStack(spacing: 12) {
            Image("onboardingLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 60, height: 60)
                .accessibilityHidden(true)

            VStack(spacing: 4) {
                Text("Hosted AI")
                    .font(.system(.title2, design: .rounded, weight: .bold))
                Text("Fud AI runs the models. No API keys, no setup.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 4)
    }

    // MARK: Plans

    @ViewBuilder
    private var plansSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            if catalog.hasSubscriptions {
                if availablePeriods.count > 1 {
                    periodToggle
                }
                // Two explicit cards; deliberately not a ForEach over HostedPlan.
                VStack(spacing: 8) {
                    if let package = package(for: .plus) {
                        planCard(plan: .plus, package: package)
                    }
                    if let package = package(for: .pro) {
                        planCard(plan: .pro, package: package)
                    }
                }
            } else {
                HostedPlansStatusCard(
                    isLoading: rc.isLoadingOfferings || !didFinishInitialLoad,
                    retry: { Task { await rc.loadOfferings() } }
                )
            }
        }
    }

    /// Two fixed buttons in a plain `HStack`; see the crash notes above.
    private var periodToggle: some View {
        HStack(spacing: 4) {
            HostedPeriodButton(
                title: HostedBillingPeriod.monthly.title,
                savingsLabel: nil,
                isSelected: selectedPeriod == .monthly,
                action: { select(period: .monthly) }
            )
            HostedPeriodButton(
                title: HostedBillingPeriod.yearly.title,
                savingsLabel: yearlySavingsLabel,
                isSelected: selectedPeriod == .yearly,
                action: { select(period: .yearly) }
            )
        }
        .padding(3)
        .background(AppColors.appCard, in: Capsule())
        .disabled(isBusy)
    }

    private func select(period: HostedBillingPeriod) {
        withAnimation(.snappy) { selectedPeriod = period }
    }

    /// Builds the concrete card view for one plan. The returned type is always
    /// exactly `HostedPlanCard`; all per-plan variation is in stored values.
    private func planCard(plan: HostedPlan, package: Package) -> HostedPlanCard {
        let product = package.storeProduct
        let period = HostedBillingPeriod(package: package) ?? selectedPeriod
        let dailyLimit = HostedAIConstants.dailyLimit(for: plan)

        // Only a functional marker; no marketing badges on the cards.
        let badge: String? = rc.activePlan == plan ? String(localized: "Current plan") : nil

        let priceDetail: String
        if let perMonth = monthlyEquivalent(for: product) {
            priceDetail = String(localized: "≈ \(perMonth) / mo")
        } else {
            priceDetail = period.perUnit
        }

        return HostedPlanCard(
            title: plan.displayName,
            badge: badge,
            subtitle: String(localized: "\(dailyLimit) AI actions every day"),
            price: product.localizedPriceString,
            priceDetail: priceDetail,
            isSelected: selectedPlan == plan,
            isEnabled: !isBusy,
            accessibilityLabel: "\(product.localizedTitle), \(product.localizedPriceString) \(period.perUnit)",
            action: { withAnimation(.snappy) { selectedPlan = plan } }
        )
    }

    // MARK: Features

    private var featureList: some View {
        VStack(alignment: .leading, spacing: 10) {
            HostedSectionTitle("What's included")
            VStack(alignment: .leading, spacing: 12) {
                HostedFeatureRow(
                    icon: "camera.viewfinder",
                    title: String(localized: "Every AI feature"),
                    detail: String(localized: "Photo, voice and text logging, Coach, and workout AI.")
                )
                HostedFeatureRow(
                    icon: "clock.arrow.circlepath",
                    title: String(localized: "Daily allowance"),
                    detail: String(localized: "Resets at midnight UTC. Credit packs cover busy days.")
                )
                HostedFeatureRow(
                    icon: "lock.shield",
                    title: String(localized: "Only your request is sent"),
                    detail: String(localized: "Your diary and history stay on this device. Cancel anytime in the App Store.")
                )
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(HostedCardBackground())
        }
    }

    // MARK: Credit packs

    private var creditPacksSection: some View {
        let catalog = self.catalog
        return VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HostedSectionTitle("Credit packs")
                Text("One-time top-ups, spent only while a plan is active.")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            VStack(spacing: 0) {
                // Rows are keyed on product identifier `String`s only.
                ForEach(catalog.creditPackIDs, id: \.self) { productID in
                    if let package = catalog.creditPacks[productID] {
                        HostedCreditRow(
                            title: creditTitle(for: package.storeProduct),
                            price: package.storeProduct.localizedPriceString,
                            isPurchasing: purchasingID == productID,
                            isEnabled: !isBusy,
                            showsDivider: productID != catalog.creditPackIDs.last,
                            action: { Task { await purchase(package) } }
                        )
                    }
                }
            }
            .background(HostedCardBackground())
        }
    }

    // MARK: Purchase bar

    /// Flat, single-colour CTA in the app accent (no gradient or drop shadow);
    /// disclosure and legal links stay small and secondary.
    private var purchaseBar: some View {
        VStack(spacing: 8) {
            Button {
                if let package = selectedPackage {
                    Task { await purchase(package) }
                }
            } label: {
                ZStack {
                    Text(ctaTitle)
                        .opacity(isPurchasingSelectedPackage ? 0 : 1)
                    ProgressView()
                        .tint(.white)
                        .opacity(isPurchasingSelectedPackage ? 1 : 0)
                }
                .font(.system(.body, design: .rounded, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .background(AppColors.calorie, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(selectedPackage == nil || isBusy)
            .opacity(selectedPackage == nil ? 0.45 : 1)

            Text(ctaDetail)
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(height: ctaDetail.isEmpty ? 0 : nil)
                .opacity(ctaDetail.isEmpty ? 0 : 1)
                .accessibilityHidden(ctaDetail.isEmpty)

            HStack(spacing: 6) {
                Button {
                    Task { await restore() }
                } label: {
                    HStack(spacing: isRestoring ? 6 : 0) {
                        ProgressView()
                            .controlSize(.mini)
                            .frame(width: isRestoring ? nil : 0)
                            .opacity(isRestoring ? 1 : 0)
                        Text("Restore Purchases")
                    }
                }
                .disabled(isBusy)
                Text("·")
                Link("Terms", destination: Self.termsURL)
                Text("·")
                Link("Privacy", destination: Self.privacyURL)
            }
            .font(.system(.caption, design: .rounded, weight: .medium))
            .foregroundStyle(.secondary)
            .tint(.secondary)
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 6)
        .background(.bar)
    }

    // MARK: Small helpers

    private func creditTitle(for product: StoreProduct) -> String {
        if let amount = HostedAIConstants.creditAmount(for: product.productIdentifier) {
            return String(localized: "\(amount) credits")
        }
        return product.localizedTitle
    }

    // MARK: Catalog

    /// Splits the `plus` / `pro` offerings into subscriptions keyed by plan and
    /// period, and a deduplicated list of credit packs (both offerings carry the
    /// same consumables). Rendering is entirely driven by what RevenueCat returns.
    private var catalog: HostedPaywallCatalog {
        var result = HostedPaywallCatalog()
        for plan in Self.plans {
            guard let packages = rc.offerings?.offering(identifier: plan.rawValue)?.availablePackages else { continue }
            for package in packages {
                let product = package.storeProduct
                let isSubscription = product.productCategory == .subscription
                    || HostedAIConstants.subscriptionProductIDs.contains(product.productIdentifier)
                if isSubscription {
                    if let period = HostedBillingPeriod(package: package) {
                        result.subscriptions[plan, default: [:]][period] = package
                    }
                } else if HostedAIConstants.creditAmount(for: product.productIdentifier) != nil {
                    result.creditPacks[product.productIdentifier] = package
                }
            }
        }
        result.creditPackIDs = result.creditPacks.keys.sorted {
            (HostedAIConstants.creditAmount(for: $0) ?? 0) < (HostedAIConstants.creditAmount(for: $1) ?? 0)
        }
        return result
    }

    private var availablePeriods: [HostedBillingPeriod] {
        let catalog = self.catalog
        return HostedBillingPeriod.allCases.filter { period in
            Self.plans.contains { catalog.subscriptions[$0]?[period] != nil }
        }
    }

    /// The package shown on a plan card for the selected period only. A plan
    /// that doesn't offer the selected period gets no card, so the toggle, the
    /// displayed price and the purchased product always share one term.
    private func package(for plan: HostedPlan) -> Package? {
        catalog.subscriptions[plan]?[selectedPeriod]
    }

    /// Plans that render a card for the selected period, in display order.
    private var visiblePlans: [HostedPlan] {
        Self.plans.filter { package(for: $0) != nil }
    }

    private var selectedPackage: Package? {
        package(for: selectedPlan)
    }

    private var isPurchasingSelectedPackage: Bool {
        guard let purchasingID, let package = selectedPackage else { return false }
        return purchasingID == package.storeProduct.productIdentifier
    }

    /// Keeps the selection purchasable: snap the period to one the catalog
    /// offers, then make sure the selected plan has a visible card for it.
    /// Prefers a plan the user isn't already on so the CTA is never a no-op.
    private func reconcileSelection() {
        let periods = availablePeriods
        if !periods.isEmpty, !periods.contains(selectedPeriod) {
            selectedPeriod = periods.contains(.yearly) ? .yearly : periods[0]
        }
        guard package(for: selectedPlan) == nil else { return }
        let candidates = visiblePlans
        if let plan = candidates.first(where: { $0 != rc.activePlan }) ?? candidates.first {
            selectedPlan = plan
        }
    }

    private var ctaTitle: String {
        if let package = selectedPackage, rc.hasThreeDayTrial(package), !rc.hasHostedEntitlement {
            return String(localized: "Try free for 3 days")
        }
        if rc.activePlan == .plus && selectedPlan == .pro {
            return String(localized: "Upgrade to Pro")
        }
        return String(localized: "Subscribe to \(selectedPlan.displayName)")
    }

    /// Renewal disclosure under the CTA; empty while no package is selected.
    private var ctaDetail: String {
        guard let package = selectedPackage else { return "" }
        let product = package.storeProduct
        if rc.hasThreeDayTrial(package), !rc.hasHostedEntitlement {
            let minutes = selectedPlan == .plus ? 2 : 5
            return String(localized: "3 days free, then \(product.localizedPriceString)/year. Renews automatically. Cancel before the trial ends to avoid a charge.")
                + "\n" + String(localized: "Trial: 20 AI actions and \(minutes) voice minutes/day (UTC). Full plan limits after trial. Extra credits cost money.")
        }
        let period = HostedBillingPeriod(package: package) ?? selectedPeriod
        return String(localized: "\(product.localizedPriceString) \(period.perUnit) · Renews automatically, cancel anytime.")
    }

    /// Yearly discount versus paying monthly for the selected plan, only when
    /// both prices exist in the same currency and the saving is meaningful.
    private var yearlySavingsPercent: Int? {
        guard let monthly = catalog.subscriptions[selectedPlan]?[.monthly]?.storeProduct,
              let yearly = catalog.subscriptions[selectedPlan]?[.yearly]?.storeProduct,
              monthly.currencyCode == yearly.currencyCode else { return nil }
        let monthlyAnnualized = NSDecimalNumber(decimal: monthly.price * 12).doubleValue
        let yearlyPrice = NSDecimalNumber(decimal: yearly.price).doubleValue
        guard monthlyAnnualized > 0, yearlyPrice < monthlyAnnualized else { return nil }
        let percent = Int(((1 - yearlyPrice / monthlyAnnualized) * 100).rounded())
        return percent >= 5 ? percent : nil
    }

    private var yearlySavingsLabel: String? {
        guard let savings = yearlySavingsPercent else { return nil }
        return String(localized: "Save \(savings)%")
    }

    /// "≈ ₹575 / mo" for multi-month terms, formatted with the product's own
    /// price formatter so the currency always matches the store.
    private func monthlyEquivalent(for product: StoreProduct) -> String? {
        guard let period = product.subscriptionPeriod,
              let formatter = product.priceFormatter else { return nil }
        let months: Int
        switch period.unit {
        case .year: months = period.value * 12
        case .month: months = period.value
        default: return nil
        }
        guard months > 1 else { return nil }
        let perMonth = product.price / Decimal(months)
        return formatter.string(from: NSDecimalNumber(decimal: perMonth))
    }

    private func purchase(_ package: Package) async {
        purchasingID = package.storeProduct.productIdentifier
        defer { purchasingID = nil }
        do {
            try await rc.purchase(package: package)
            AIModeSettings.mode = .hosted
            onSubscribed?()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func restore() async {
        isRestoring = true
        defer { isRestoring = false }
        do {
            try await rc.restorePurchases()
            if rc.hasHostedEntitlement {
                AIModeSettings.mode = .hosted
                restoreMessage = String(localized: "Purchases restored.")
                onSubscribed?()
                dismiss()
            } else {
                restoreMessage = String(localized: "No active Plus or Pro subscription found for this Apple ID.")
            }
        } catch {
            restoreMessage = error.localizedDescription
        }
    }
}

// MARK: - Paywall building blocks
//
// Every type here is a concrete, non-generic `View` with plain stored
// properties and a body of exactly one shape (no `if` that changes the tree).

/// Card chrome shared by the paywall sections.
struct HostedCardBackground: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(AppColors.appCard)
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 1)
        }
    }
}

struct HostedSectionTitle: View {
    let title: LocalizedStringKey

    init(_ title: LocalizedStringKey) {
        self.title = title
    }

    var body: some View {
        Text(title)
            .font(.system(.subheadline, design: .rounded, weight: .semibold))
            .foregroundStyle(.secondary)
            .padding(.leading, 4)
    }
}

/// One half of the monthly/yearly toggle. The savings text is always part of
/// the tree and collapses to zero width when there is nothing to show, so the
/// monthly and yearly buttons are the exact same type with the same layout.
struct HostedPeriodButton: View {
    let title: String
    let savingsLabel: String?
    let isSelected: Bool
    let action: () -> Void

    private var hasSavings: Bool { savingsLabel != nil }

    var body: some View {
        Button(action: action) {
            HStack(spacing: hasSavings ? 5 : 0) {
                Text(title)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(isSelected ? Color.white : Color.primary)
                Text(savingsLabel ?? "")
                    .font(.system(.caption2, design: .rounded, weight: .semibold))
                    .foregroundStyle(isSelected ? Color.white.opacity(0.85) : AppColors.calorie)
                    .frame(width: hasSavings ? nil : 0)
                    .opacity(hasSavings ? 1 : 0)
                    .accessibilityHidden(!hasSavings)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 36)
            .background(isSelected ? AppColors.calorie : Color.clear, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Selectable Plus / Pro plan card. The badge ("Current plan") is always in
/// the tree and collapses when nil.
struct HostedPlanCard: View {
    let title: String
    let badge: String?
    let subtitle: String
    let price: String
    let priceDetail: String
    let isSelected: Bool
    let isEnabled: Bool
    let accessibilityLabel: String
    let action: () -> Void

    private var hasBadge: Bool { badge != nil }

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(isSelected ? AppColors.calorie : Color.secondary.opacity(0.4))

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: hasBadge ? 6 : 0) {
                        Text(title)
                            .font(.system(.body, design: .rounded, weight: .semibold))
                            .foregroundStyle(.primary)
                        Text(badge ?? "")
                            .font(.system(.caption2, design: .rounded, weight: .semibold))
                            .foregroundStyle(AppColors.calorie)
                            .frame(width: hasBadge ? nil : 0)
                            .opacity(hasBadge ? 1 : 0)
                            .accessibilityHidden(!hasBadge)
                    }
                    Text(subtitle)
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                VStack(alignment: .trailing, spacing: 1) {
                    Text(price)
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(.primary)
                    Text(priceDetail)
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(
                        isSelected ? AppColors.calorie : Color.primary.opacity(0.06),
                        lineWidth: isSelected ? 1.5 : 1
                    )
            }
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

struct HostedFeatureRow: View {
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AppColors.calorie)
                .frame(width: 22, height: 22)
                .padding(.top, 1)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(.primary)
                Text(detail)
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// One credit pack row. Price and spinner are both always present and swapped
/// with opacity; the divider collapses to zero height on the last row.
struct HostedCreditRow: View {
    let title: String
    let price: String
    let isPurchasing: Bool
    let isEnabled: Bool
    let showsDivider: Bool
    let action: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Button(action: action) {
                HStack(spacing: 12) {
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)
                        .frame(width: 22, height: 22)
                        .accessibilityHidden(true)
                    Text(title)
                        .font(.system(.body, design: .rounded))
                        .foregroundStyle(.primary)
                    Spacer()
                    ZStack(alignment: .trailing) {
                        Text(price)
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                            .opacity(isPurchasing ? 0 : 1)
                        ProgressView()
                            .opacity(isPurchasing ? 1 : 0)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!isEnabled)

            Divider()
                .padding(.leading, 48)
                .frame(height: showsDivider ? nil : 0)
                .opacity(showsDivider ? 1 : 0)
        }
    }
}

/// Placeholder shown in place of the plan cards while offerings load or when
/// RevenueCat returned nothing. Both states are always in the tree.
struct HostedPlansStatusCard: View {
    let isLoading: Bool
    let retry: () -> Void

    var body: some View {
        ZStack {
            VStack(spacing: 10) {
                ProgressView()
                Text("Loading plans…")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .opacity(isLoading ? 1 : 0)
            .accessibilityHidden(!isLoading)

            VStack(spacing: 10) {
                Image(systemName: "wifi.slash")
                    .font(.title2)
                    .foregroundStyle(.secondary)
                Text("Plans are unavailable right now.")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Text("Check your connection and try again.")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                Button("Try Again", action: retry)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .buttonStyle(.bordered)
                    .tint(AppColors.calorie)
            }
            .opacity(isLoading ? 0 : 1)
            .disabled(isLoading)
            .accessibilityHidden(isLoading)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .background(HostedCardBackground())
    }
}

// MARK: - Credits sheet

struct HostedCreditsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var rc = RevenueCatManager.shared
    @State private var products: [String: StoreProduct] = [:]
    @State private var purchasingID: String?
    @State private var didPurchase = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                ForEach(HostedAIConstants.creditProductIDs, id: \.self) { productID in
                    Button {
                        if let product = products[productID] {
                            Task { await purchase(product) }
                        }
                    } label: {
                        HStack {
                            Text(creditLabel(productID))
                            Spacer()
                            if purchasingID == productID {
                                ProgressView()
                            } else if let product = products[productID] {
                                Text(product.localizedPriceString)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .disabled(products[productID] == nil || purchasingID != nil)
                }
            }
            .navigationTitle("Buy Credits")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                let fetched = await Purchases.shared.products(HostedAIConstants.creditProductIDs)
                products = Dictionary(uniqueKeysWithValues: fetched.map { ($0.productIdentifier, $0) })
            }
            .alert("Credits added", isPresented: $didPurchase) {
                Button("OK") { dismiss() }
            }
            .alert("Purchase", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private func creditLabel(_ productID: String) -> String {
        if let amount = HostedAIConstants.creditAmount(for: productID) {
            return String(localized: "\(amount) credits")
        }
        return productID
    }

    private func purchase(_ product: StoreProduct) async {
        purchasingID = product.productIdentifier
        defer { purchasingID = nil }
        do {
            try await rc.purchase(product: product)
            didPurchase = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Soft paywall

/// Soft paywall when hosted quota is exhausted.
struct HostedQuotaSoftPaywall: View {
    @Environment(\.dismiss) private var dismiss
    let onBuyCreditsOrUpgrade: () -> Void
    let onSwitchBYOK: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer(minLength: 0)

                ZStack {
                    Circle()
                        .fill(AppColors.calorie.opacity(0.12))
                        .frame(width: 88, height: 88)
                    Image(systemName: "hourglass")
                        .font(.system(size: 36, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)
                }
                .accessibilityHidden(true)

                VStack(spacing: 8) {
                    Text("Out of Hosted Actions")
                        .font(.system(.title2, design: .rounded, weight: .bold))
                        .multilineTextAlignment(.center)
                    Text("Buy credits, upgrade your plan, or switch to BYOK with your own API key.")
                        .font(.system(.callout, design: .rounded))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)

                VStack(spacing: 12) {
                    Button {
                        dismiss()
                        onBuyCreditsOrUpgrade()
                    } label: {
                        Text("Buy Credits or Upgrade")
                            .font(.system(.body, design: .rounded, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .background(
                                LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing),
                                in: RoundedRectangle(cornerRadius: 16, style: .continuous)
                            )
                            .shadow(color: AppColors.calorie.opacity(0.3), radius: 8, y: 4)
                            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)

                    Button {
                        AIModeSettings.mode = .byok
                        onSwitchBYOK()
                        dismiss()
                    } label: {
                        Text("Switch to BYOK")
                            .font(.system(.body, design: .rounded, weight: .semibold))
                            .foregroundStyle(AppColors.calorie)
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .background(AppColors.calorie.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
            .background(AppColors.appBackground.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}
