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

    private override init() {
        super.init()
    }

    func configure() {
        Purchases.shared.delegate = self
        Task { await refreshCustomerInfo() }
    }

    func refreshCustomerInfo() async {
        do {
            let info = try await Purchases.shared.customerInfo()
            applyCustomerInfo(info)
        } catch {
            lastError = error.localizedDescription
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

    func purchase(package: Package) async throws {
        let result = try await Purchases.shared.purchase(package: package)
        applyCustomerInfo(result.customerInfo)
        if let credits = HostedAIConstants.creditAmount(for: package.storeProduct.productIdentifier) {
            HostedAIQuotaManager.shared.addCredits(credits)
        }
    }

    func purchase(product: StoreProduct) async throws {
        let result = try await Purchases.shared.purchase(product: product)
        applyCustomerInfo(result.customerInfo)
        if let credits = HostedAIConstants.creditAmount(for: product.productIdentifier) {
            HostedAIQuotaManager.shared.addCredits(credits)
        }
    }

    func restorePurchases() async throws {
        let info = try await Purchases.shared.restorePurchases()
        applyCustomerInfo(info)
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
