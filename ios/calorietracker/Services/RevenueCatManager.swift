//
//  RevenueCatManager.swift
//  calorietracker
//

import Foundation
import RevenueCat
import UserNotifications

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
    private(set) var trialEligibility: [String: IntroEligibility] = [:]
    private(set) var trialEndsAt: Date?

    func hasThreeDayTrial(_ package: Package) -> Bool {
        let product = package.storeProduct
        guard HostedBillingPeriod(package: package) == .yearly,
              let offer = product.introductoryDiscount,
              offer.paymentMode == .freeTrial,
              offer.subscriptionPeriod.unit == .day,
              offer.subscriptionPeriod.value * offer.numberOfPeriods == 3 else { return false }
        return trialEligibility[product.productIdentifier]?.status == .eligible
    }

    var hasEligibleYearlyTrial: Bool {
        ["plus", "pro"].contains { plan in
            offerings?.offering(identifier: plan)?.availablePackages.contains(where: hasThreeDayTrial) == true
        }
    }

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
        guard Purchases.isConfigured else { return }
        Purchases.shared.delegate = self
        Task { await refreshCustomerInfo() }
    }

    /// Returns whether customer info was successfully loaded. Callers that gate
    /// one-time UI on entitlement state must not treat a failed refresh as "no plan".
    @discardableResult
    func refreshCustomerInfo() async -> Bool {
        guard Purchases.isConfigured else { return false }
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
        guard Purchases.isConfigured else { return }
        isLoadingOfferings = true
        defer { isLoadingOfferings = false }
        do {
            trialEligibility = [:]
            offerings = try await Purchases.shared.offerings()
            let ids = [HostedAIConstants.plusYearlyProductID, HostedAIConstants.proYearlyProductID]
            trialEligibility = await Purchases.shared.checkTrialOrIntroDiscountEligibility(productIdentifiers: ids)
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

    private var trialReminderRevision = 0

    private func updateTrialReminder(end: Date?) {
        trialReminderRevision += 1
        let revision = trialReminderRevision
        Task { @MainActor in
            let defaults = UserDefaults.standard
            let key = "hosted.trialReminderExpiry"
            let identifier = "hosted.trial-reminder"
            let pending = await UNUserNotificationCenter.current().pendingNotificationRequests()
            guard revision == trialReminderRevision else { return }
            guard defaults.bool(forKey: "notificationsEnabled"), let end, end > .now else {
                PendingNotificationRequests.remove([identifier])
                defaults.removeObject(forKey: key)
                return
            }
            if defaults.object(forKey: key) as? Date == end,
               pending.contains(where: { $0.identifier == identifier }) { return }
            PendingNotificationRequests.remove([identifier])
            defaults.removeObject(forKey: key)
            let delay = end.timeIntervalSinceNow - 24 * 60 * 60
            guard delay > 0 else { return }
            let content = UNMutableNotificationContent()
            content.title = String(localized: "Trial ends tomorrow")
            content.body = String(localized: "Your yearly plan renews after the trial. Review or cancel in store settings.")
            let request = UNNotificationRequest(identifier: identifier, content: content,
                trigger: UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false))
            do {
                try await PendingNotificationRequests.addAfterPendingChanges(request)
                // Do not resurrect a marker after cancellation or a newer entitlement refresh.
                if revision == trialReminderRevision, defaults.bool(forKey: "notificationsEnabled") {
                    defaults.set(end, forKey: key)
                }
            } catch {
                // Leave it unmarked so the next refresh can retry a transient OS failure.
            }
        }
    }

    private func applyCustomerInfo(_ info: CustomerInfo) {
        let active = info.entitlements[HostedAIConstants.proEntitlementID].flatMap { $0.isActive ? $0 : nil }
            ?? info.entitlements[HostedAIConstants.plusEntitlementID].flatMap { $0.isActive ? $0 : nil }
        trialEndsAt = active?.periodType == .trial ? active?.expirationDate : nil
        updateTrialReminder(end: active?.willRenew == true ? trialEndsAt : nil)
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
