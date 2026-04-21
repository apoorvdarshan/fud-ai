import SwiftUI
import StoreKit

@Observable
class StoreManager {
    // MARK: - Product IDs
    static let monthlyID = "fudai.subscription.monthly"
    static let yearlyID = "fudai.subscription.yearly"
    static let yearlyDiscountID = "fudai.subscription.yearly.discount"

    private static let allProductIDs: Set<String> = [monthlyID, yearlyID, yearlyDiscountID]

    // MARK: - StoreKit State
    var products: [Product] = []
    var isSubscribed = false
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
            return dailyScansUsed < 25
        }
        return freeScansUsed < 4
    }

    var canUseApp: Bool {
        return isSubscribed || freeScansUsed < 4
    }

    var remainingScans: Int {
        if isSubscribed {
            resetDailyCounterIfNeeded()
            return max(0, 25 - dailyScansUsed)
        }
        return max(0, 3 - freeScansUsed)
    }

    var monthlyProduct: Product? {
        products.first { $0.id == Self.monthlyID }
    }

    var yearlyProduct: Product? {
        products.first { $0.id == Self.yearlyID }
    }

    var yearlyDiscountProduct: Product? {
        products.first { $0.id == Self.yearlyDiscountID }
    }

    var currentPlanName: String {
        guard let id = currentSubscriptionProductID else { return "Free" }
        switch id {
        case Self.monthlyID: return "Monthly"
        case Self.yearlyID: return "Yearly"
        case Self.yearlyDiscountID: return "Yearly (Discount)"
        default: return "Premium"
        }
    }

    // MARK: - Init
    init() {
        freeScansUsed = UserDefaults.standard.integer(forKey: "freeScansUsed")
        dailyScansUsed = UserDefaults.standard.integer(forKey: "dailyScansUsed")
        lastScanDate = UserDefaults.standard.object(forKey: "lastScanDate") as? Date

        transactionListener = listenForTransactions()

        Task {
            await loadProducts()
            await checkEntitlements()
        }
    }

    deinit {
        transactionListener?.cancel()
    }

    // MARK: - Load Products
    func loadProducts() async {
        do {
            products = try await Product.products(for: Self.allProductIDs)
        } catch {
            print("Failed to load products: \(error)")
        }
    }

    // MARK: - Purchase
    func purchase(_ product: Product) async {
        isPurchasing = true
        purchaseError = nil

        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                let transaction = extractTransaction(verification)
                await transaction.finish()
                await checkEntitlements()
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

        isPurchasing = false
    }

    // MARK: - Restore
    func restorePurchases() async {
        do {
            try await AppStore.sync()
            await checkEntitlements()
        } catch {
            purchaseError = error.localizedDescription
        }
    }

    // MARK: - Entitlements
    func checkEntitlements() async {
        var subscribed = false
        var activeProductID: String?

        for await result in Transaction.currentEntitlements {
            let transaction = extractTransaction(result)
            if transaction.productType == .autoRenewable {
                subscribed = true
                activeProductID = transaction.productID
            }
        }

        isSubscribed = subscribed
        currentSubscriptionProductID = activeProductID
        hasCheckedEntitlements = true
    }

    // MARK: - Transaction Listener
    private func listenForTransactions() -> Task<Void, Never> {
        Task.detached {
            for await result in Transaction.updates {
                let transaction = self.extractTransaction(result)
                await transaction.finish()
                await self.checkEntitlements()
            }
        }
    }

    // MARK: - Verification
    nonisolated private func extractTransaction<T>(_ result: VerificationResult<T>) -> T {
        switch result {
        case .unverified(let payload, _):
            return payload
        case .verified(let safe):
            return safe
        }
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
}
