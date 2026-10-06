//
//  RevenueCatManager.swift
//  calorietracker
//

import Foundation
import RevenueCat

/// Subscriptions, credit packs, and entitlement state. Tip Jar stays in TipJarView.
@MainActor
@Observable
final class RevenueCatManager: NSObject {
    static let shared = RevenueCatManager()

    private(set) var activePlan: HostedPlan = .none
    private(set) var hasPlusEntitlement = false
    private(set) var hasProEntitlement = false
    private(set) var isLoadingOfferings = false
    private(set) var offerings: Offerings?
    private(set) var lastError: String?

    var hasHostedEntitlement: Bool { activePlan != .none }

    /// True when RevenueCat resolves at least one Plus/Pro subscription package the paywall
    /// can actually render a plan card for. Mirrors the paywall's own resolution: the offering
    /// must key by plan id `plus` / `pro`, the package must be a subscription, and
    /// `HostedBillingPeriod(package:)` must map it to a monthly/yearly term. False while
    /// offerings are empty (IAPs not live, network failure, or still loading), so callers can
    /// avoid presenting an upsell that would dead-end on the paywall's
    /// "Plans are unavailable right now." card.
    var hasPurchasableHostedPlans: Bool {
        guard let offerings else { return false }
        for plan in [HostedPlan.plus, .pro] {
            guard let packages = offerings.offering(identifier: plan.rawValue)?.availablePackages else { continue }
            if packages.contains(where: { package in
                let product = package.storeProduct
                let isSubscription = product.productCategory == .subscription
                    || HostedAIConstants.subscriptionProductIDs.contains(product.productIdentifier)
                return isSubscription && HostedBillingPeriod(package: package) != nil
            }) {
                return true
            }
        }
        return false
    }

    private override init() {
        super.init()
    }

    func configure() {
        Purchases.shared.delegate = self
        Task { await refreshCustomerInfo() }
    }

    /// Returns whether customer info was successfully loaded. Callers that gate
    /// one-time UI on entitlement state must not treat a failed refresh as "no plan".
    @discardableResult
    func refreshCustomerInfo() async -> Bool {
        do {
            let info = try await Purchases.shared.customerInfo()
            applyCustomerInfo(info)
            lastError = nil
            return true
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    func loadOfferings() async {
        isLoadingOfferings = true
        defer { isLoadingOfferings = false }
        do {
            offerings = try await Purchases.shared.offerings()
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Credits are never granted on device. The Worker reconciles credit packs
    /// from RevenueCat `non_subscriptions` by transaction id, so a purchase is
    /// followed by a forced server-side re-verification to surface the new
    /// balance immediately.
    func purchase(package: Package) async throws {
        let result = try await Purchases.shared.purchase(package: package)
        applyCustomerInfo(result.customerInfo)
        await HostedAIQuotaManager.shared.refresh(force: true)
    }

    func purchase(product: StoreProduct) async throws {
        let result = try await Purchases.shared.purchase(product: product)
        applyCustomerInfo(result.customerInfo)
        await HostedAIQuotaManager.shared.refresh(force: true)
    }

    func restorePurchases() async throws {
        let info = try await Purchases.shared.restorePurchases()
        applyCustomerInfo(info)
        await HostedAIQuotaManager.shared.refresh(force: true)
    }

    func appUserID() async -> String {
        (try? await Purchases.shared.customerInfo().originalAppUserId) ?? Purchases.shared.appUserID
    }

    private func applyCustomerInfo(_ info: CustomerInfo) {
        hasProEntitlement = info.entitlements[HostedAIConstants.proEntitlementID]?.isActive == true
        hasPlusEntitlement = info.entitlements[HostedAIConstants.plusEntitlementID]?.isActive == true
        if hasProEntitlement {
            activePlan = .pro
        } else if hasPlusEntitlement {
            activePlan = .plus
        } else {
            activePlan = .none
        }
    }
}

extension RevenueCatManager: PurchasesDelegate {
    nonisolated func purchases(_ purchases: Purchases, receivedUpdated customerInfo: CustomerInfo) {
        Task { @MainActor in
            applyCustomerInfo(customerInfo)
        }
    }
}
