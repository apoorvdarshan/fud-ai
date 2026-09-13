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

struct HostedPaywallView: View {
    @Environment(\.dismiss) private var dismiss
    var onSubscribed: (() -> Void)? = nil
    @State private var rc = RevenueCatManager.shared
    @State private var purchasingID: String?
    @State private var errorMessage: String?
    @State private var isRestoring = false
    @State private var restoreMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section("Plus — \(HostedAIConstants.plusDailyLimit)/day") {
                    packageRows(offeringID: "plus")
                }
                Section("Pro — \(HostedAIConstants.proDailyLimit)/day") {
                    packageRows(offeringID: "pro")
                }
                Section {
                    Button {
                        Task { await restore() }
                    } label: {
                        HStack {
                            Text("Restore Purchases")
                            Spacer()
                            if isRestoring {
                                ProgressView()
                            }
                        }
                    }
                    .disabled(isRestoring || purchasingID != nil)
                }
            }
            .navigationTitle("Hosted AI")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .task { await rc.loadOfferings() }
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

    @ViewBuilder
    private func packageRows(offeringID: String) -> some View {
        if let packages = rc.offerings?.offering(identifier: offeringID)?.availablePackages {
            ForEach(packages, id: \.identifier) { package in
                Button {
                    Task { await purchase(package) }
                } label: {
                    HStack {
                        Text(package.storeProduct.localizedTitle)
                        Spacer()
                        if purchasingID == package.storeProduct.productIdentifier {
                            ProgressView()
                        } else {
                            Text(package.storeProduct.localizedPriceString)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(purchasingID != nil || isRestoring)
            }
        } else {
            Text("Plans loading…")
                .foregroundStyle(.secondary)
        }
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

/// Soft paywall when hosted quota is exhausted.
struct HostedQuotaSoftPaywall: View {
    @Environment(\.dismiss) private var dismiss
    let onBuyCreditsOrUpgrade: () -> Void
    let onSwitchBYOK: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Out of Hosted Actions")
                    .font(.title2.bold())
                Text("Buy credits, upgrade your plan, or switch to BYOK with your own API key.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                Button("Buy Credits or Upgrade") {
                    dismiss()
                    onBuyCreditsOrUpgrade()
                }
                .buttonStyle(.borderedProminent)
                Button("Switch to BYOK") {
                    AIModeSettings.mode = .byok
                    onSwitchBYOK()
                    dismiss()
                }
            }
            .padding()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}
