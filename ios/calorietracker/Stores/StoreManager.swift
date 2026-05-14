import SwiftUI
import StoreKit
import RevenueCat

enum RevenueCatConfig {
    static let appleAPIKeyInfoKey = "RevenueCatAppleAPIKey"
    static let entitlementID = "plus"

    private static var didConfigure = false

    static var apiKey: String? {
        let raw = (Bundle.main.object(forInfoDictionaryKey: appleAPIKeyInfoKey) as? String) ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.contains("$(") else { return nil }
        return trimmed
    }

    static var isConfigured: Bool {
        didConfigure
    }

    static func configureIfNeeded() {
        guard !didConfigure, let apiKey else { return }
        #if DEBUG
        Purchases.logLevel = .debug
        #endif
        Purchases.configure(withAPIKey: apiKey, appUserID: AIAccessSettings.installID)
        didConfigure = true
    }
}

struct PlusProduct: Identifiable {
    fileprivate enum Source {
        case revenueCat(Package)
        case storeKit(Product)
    }

    let id: String
    let productID: String
    let title: String
    let displayPrice: String
    let detail: String
    fileprivate let source: Source
}

@MainActor
@Observable
class StoreManager {
    // MARK: - Product IDs
    static let weeklyID = "fudai.plus.weekly"
    static let monthlyID = "fudai.plus.monthly"
    static let yearlyID = "fudai.plus.yearly"

    private static let allProductIDs: Set<String> = [weeklyID, monthlyID, yearlyID]

    private static var usesStoreKitTestingProducts: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }

    // MARK: - Purchase State
    var products: [PlusProduct] = []
    var isSubscribed = false {
        didSet { AIAccessSettings.setActivePlusEntitlement(isSubscribed) }
    }
    var currentSubscriptionProductID: String?

    // MARK: - Scan Tracking (UserDefaults-backed)
    var freeScansUsed: Int {
        didSet { UserDefaults.standard.set(freeScansUsed, forKey: "freeScansUsed") }
    }
    var dailyScansUsed: Int {
        didSet { UserDefaults.standard.set(dailyScansUsed, forKey: "dailyScansUsed") }
    }
    var lastScanDate: Date? {
        didSet { UserDefaults.standard.set(lastScanDate, forKey: "lastScanDate") }
    }

    // MARK: - Loading / Error
    var hasCheckedEntitlements = false
    var isPurchasing = false
    var purchaseError: String?

    // MARK: - Transaction listener
    private var transactionListener: Task<Void, Never>?

    // MARK: - Computed
    var canScan: Bool {
        if isSubscribed {
            resetDailyCounterIfNeeded()
            return dailyScansUsed < AIAccessSettings.paidFoodDailyRequestLimit
        }
        return freeScansUsed < 4
    }

    var canUseApp: Bool {
        return isSubscribed || freeScansUsed < 4
    }

    var remainingScans: Int {
        if isSubscribed {
            resetDailyCounterIfNeeded()
            return max(0, AIAccessSettings.paidFoodDailyRequestLimit - dailyScansUsed)
        }
        return max(0, 3 - freeScansUsed)
    }

    var monthlyProduct: PlusProduct? {
        products.first { $0.id == Self.monthlyID }
    }

    var weeklyProduct: PlusProduct? {
        products.first { $0.id == Self.weeklyID }
    }

    var yearlyProduct: PlusProduct? {
        products.first { $0.id == Self.yearlyID }
    }

    var currentPlanName: String {
        guard let id = currentSubscriptionProductID else { return "Free" }
        switch id {
        case Self.weeklyID: return "Weekly"
        case Self.monthlyID: return "Monthly"
        case Self.yearlyID: return "Yearly"
        default: return "Premium"
        }
    }

    // MARK: - Init
    init() {
        RevenueCatConfig.configureIfNeeded()

        freeScansUsed = UserDefaults.standard.integer(forKey: "freeScansUsed")
        dailyScansUsed = UserDefaults.standard.integer(forKey: "dailyScansUsed")
        lastScanDate = UserDefaults.standard.object(forKey: "lastScanDate") as? Date

        if !RevenueCatConfig.isConfigured || Self.usesStoreKitTestingProducts {
            transactionListener = listenForTransactions()
        }

        Task {
            await loadProducts()
            await checkEntitlements()
        }
    }

    // MARK: - Load Products
    func loadProducts() async {
        if Self.usesStoreKitTestingProducts {
            await loadStoreKitProducts()
            return
        }

        if RevenueCatConfig.isConfigured {
            await loadRevenueCatProducts()
            return
        }

        await loadStoreKitProducts()
    }

    private func loadRevenueCatProducts() async {
        do {
            guard let offering = try await Purchases.shared.offerings().current else {
                purchaseError = "RevenueCat offering is not configured."
                return
            }
            products = plusProducts(from: offering)
        } catch {
            purchaseError = error.localizedDescription
            print("Failed to load RevenueCat offerings: \(error)")
        }
    }

    private func loadStoreKitProducts() async {
        do {
            let storeProducts = try await Product.products(for: Self.allProductIDs)
            products = storeProducts.map { product in
                PlusProduct(
                    id: product.id,
                    productID: product.id,
                    title: Self.title(forProductID: product.id),
                    displayPrice: product.displayPrice,
                    detail: detail(for: product),
                    source: .storeKit(product)
                )
            }
            .sorted { Self.productSortRank($0.productID) < Self.productSortRank($1.productID) }
            if !products.isEmpty {
                purchaseError = nil
            }
        } catch {
            print("Failed to load products: \(error)")
        }
    }

    // MARK: - Purchase
    @discardableResult
    func purchase(_ product: PlusProduct) async -> Bool {
        isPurchasing = true
        purchaseError = nil
        defer { isPurchasing = false }

        switch product.source {
        case .revenueCat(let package):
            return await purchaseRevenueCat(package)
        case .storeKit(let storeProduct):
            return await purchaseStoreKit(storeProduct)
        }
    }

    private func purchaseRevenueCat(_ package: Package) async -> Bool {
        do {
            let result = try await Purchases.shared.purchase(package: package)
            guard !result.userCancelled else { return false }
            applyCustomerInfo(result.customerInfo, fallbackProductID: package.storeProduct.productIdentifier)
            return isSubscribed
        } catch {
            purchaseError = error.localizedDescription
            return false
        }
    }

    private func purchaseStoreKit(_ product: Product) async -> Bool {
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                let transaction = extractTransaction(verification)
                let purchasedPlusSubscription = isActivePlusTransaction(transaction)
                if purchasedPlusSubscription {
                    applySubscriptionState(isSubscribed: true, productID: transaction.productID)
                }
                await transaction.finish()
                await checkEntitlements(fallbackActiveProductID: purchasedPlusSubscription ? transaction.productID : nil)
                return purchasedPlusSubscription || isSubscribed
            case .userCancelled:
                break
            case .pending:
                break
            @unknown default:
                break
            }
        } catch {
            purchaseError = error.localizedDescription
        }

        return false
    }

    // MARK: - Restore
    @discardableResult
    func restorePurchases() async -> Bool {
        if RevenueCatConfig.isConfigured && !Self.usesStoreKitTestingProducts {
            do {
                let customerInfo = try await Purchases.shared.restorePurchases()
                applyCustomerInfo(customerInfo)
                return isSubscribed
            } catch {
                purchaseError = error.localizedDescription
                return false
            }
        }

        do {
            try await AppStore.sync()
            await checkEntitlements()
            return isSubscribed
        } catch {
            purchaseError = error.localizedDescription
            return false
        }
    }

    // MARK: - Entitlements
    func checkEntitlements(fallbackActiveProductID: String? = nil) async {
        if RevenueCatConfig.isConfigured && !Self.usesStoreKitTestingProducts {
            do {
                let customerInfo = try await Purchases.shared.customerInfo()
                applyCustomerInfo(customerInfo, fallbackProductID: fallbackActiveProductID)
                hasCheckedEntitlements = true
                return
            } catch {
                print("Failed to check RevenueCat entitlements: \(error)")
            }
        }

        var subscribed = false
        var activeProductID: String?

        for await result in Transaction.currentEntitlements {
            let transaction = extractTransaction(result)
            if isActivePlusTransaction(transaction) {
                subscribed = true
                activeProductID = transaction.productID
            }
        }

        if !subscribed, let fallbackActiveProductID {
            subscribed = true
            activeProductID = fallbackActiveProductID
        }

        applySubscriptionState(isSubscribed: subscribed, productID: activeProductID)
        hasCheckedEntitlements = true
    }

    // MARK: - Transaction Listener
    private func listenForTransactions() -> Task<Void, Never> {
        Task { [weak self] in
            for await result in Transaction.updates {
                guard let self else { break }
                let transaction = self.extractTransaction(result)
                if self.isActivePlusTransaction(transaction) {
                    self.applySubscriptionState(isSubscribed: true, productID: transaction.productID)
                }
                await transaction.finish()
                await self.checkEntitlements()
            }
        }
    }

    // MARK: - Verification
    nonisolated private func extractTransaction<T>(_ result: StoreKit.VerificationResult<T>) -> T {
        switch result {
        case .unverified(let payload, _):
            return payload
        case .verified(let safe):
            return safe
        }
    }

    private func isActivePlusTransaction(_ transaction: StoreKit.Transaction) -> Bool {
        guard Self.allProductIDs.contains(transaction.productID),
              transaction.productType == .autoRenewable,
              transaction.revocationDate == nil else {
            return false
        }

        if let expirationDate = transaction.expirationDate {
            return expirationDate > .now
        }

        return true
    }

    private func applySubscriptionState(isSubscribed subscribed: Bool, productID: String?) {
        isSubscribed = subscribed
        currentSubscriptionProductID = productID
    }

    private func applyCustomerInfo(_ customerInfo: CustomerInfo, fallbackProductID: String? = nil) {
        let entitlement = customerInfo.entitlements[RevenueCatConfig.entitlementID]
        let entitlementProductID = entitlement?.isActive == true ? entitlement?.productIdentifier : nil
        let activeKnownProductID = customerInfo.activeSubscriptions.first { Self.allProductIDs.contains($0) }
        let productID = entitlementProductID ?? activeKnownProductID ?? fallbackProductID
        let subscribed = entitlement?.isActive == true || activeKnownProductID != nil || fallbackProductID != nil
        applySubscriptionState(isSubscribed: subscribed, productID: productID)
    }

    // MARK: - Scan Recording
    func recordScan() {
        if isSubscribed {
            resetDailyCounterIfNeeded()
            dailyScansUsed += 1
        } else {
            freeScansUsed += 1
        }
    }

    func resetDailyCounterIfNeeded() {
        guard let lastDate = lastScanDate else {
            lastScanDate = .now
            return
        }
        if !Calendar.current.isDateInToday(lastDate) {
            dailyScansUsed = 0
            lastScanDate = .now
        }
    }

    private func plusProducts(from offering: Offering) -> [PlusProduct] {
        var packages: [Package] = []
        if let annual = offering.annual { packages.append(annual) }
        if let monthly = offering.monthly { packages.append(monthly) }
        if let weekly = offering.weekly { packages.append(weekly) }

        for package in offering.availablePackages where Self.allProductIDs.contains(package.storeProduct.productIdentifier) {
            if !packages.contains(where: { $0.storeProduct.productIdentifier == package.storeProduct.productIdentifier }) {
                packages.append(package)
            }
        }

        return packages.map { package in
            PlusProduct(
                id: package.storeProduct.productIdentifier,
                productID: package.storeProduct.productIdentifier,
                title: title(for: package),
                displayPrice: package.localizedPriceString,
                detail: detail(for: package),
                source: .revenueCat(package)
            )
        }
        .sorted { Self.productSortRank($0.productID) < Self.productSortRank($1.productID) }
    }

    private static func productSortRank(_ productID: String) -> Int {
        switch productID {
        case yearlyID: return 0
        case monthlyID: return 1
        case weeklyID: return 2
        default: return 3
        }
    }

    private static func title(forProductID productID: String) -> String {
        switch productID {
        case yearlyID: return "Yearly"
        case monthlyID: return "Monthly"
        case weeklyID: return "Weekly"
        default: return "Premium"
        }
    }

    private func title(for package: Package) -> String {
        switch package.packageType {
        case .annual: return "Yearly"
        case .monthly: return "Monthly"
        case .weekly: return "Weekly"
        default:
            switch package.storeProduct.productIdentifier {
            case Self.yearlyID: return "Yearly"
            case Self.monthlyID: return "Monthly"
            case Self.weeklyID: return "Weekly"
            default: return package.storeProduct.localizedTitle
            }
        }
    }

    private func detail(for product: Product) -> String {
        switch product.id {
        case Self.yearlyID:
            return yearlyDetailText(product)
        case Self.monthlyID:
            return "per month"
        case Self.weeklyID:
            return "per week"
        default:
            return "subscription"
        }
    }

    private func detail(for package: Package) -> String {
        switch package.packageType {
        case .annual:
            if let monthlyEquivalent = package.storeProduct.localizedPricePerMonth {
                return "\(monthlyEquivalent)/mo"
            }
            return "per year"
        case .monthly:
            return "per month"
        case .weekly:
            return "per week"
        default:
            return package.storeProduct.subscriptionPeriod == nil ? "one time" : "subscription"
        }
    }

    private func yearlyDetailText(_ product: Product) -> String {
        let monthlyEquivalent = product.price / 12
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = product.priceFormatStyle.locale
        let monthlyStr = formatter.string(from: monthlyEquivalent as NSDecimalNumber) ?? ""
        return "\(monthlyStr)/mo"
    }
}
