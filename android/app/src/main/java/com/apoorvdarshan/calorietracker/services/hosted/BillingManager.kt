package com.apoorvdarshan.calorietracker.services.hosted

import android.app.Activity
import android.app.Application
import android.util.Log
import com.apoorvdarshan.calorietracker.BuildConfig
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.Store
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitGetProducts
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.models.Period
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.models.StoreReplacementMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** What RevenueCat says the user is entitled to. */
data class HostedEntitlement(
    val plan: HostedPlan = HostedPlan.NONE,
    /** Play subscription id (`plus` / `pro`) of the active plan, for product changes and Manage. */
    val subscriptionId: String? = null,
    /** Play base plan id (`monthly` / `yearly`) of the active plan. */
    val basePlanId: String? = null,
    val isPlayStore: Boolean = false,
    val managementUrl: String? = null,
    /** False until the first CustomerInfo arrived (cached or fetched). */
    val loaded: Boolean = false
) {
    val isEntitled: Boolean get() = plan != HostedPlan.NONE
    val activePeriod: BillingPeriod? get() = periodFromIdentifier(basePlanId)
}

data class PlanOption(val plan: HostedPlan, val period: BillingPeriod, val pkg: Package) {
    val product: StoreProduct get() = pkg.product
}

/** Purchasable plans and credit packs, resolved from the `plus` / `pro` offerings. */
data class HostedCatalog(val plans: List<PlanOption>, val creditPacks: List<Package>) {
    val hasPurchasablePlans: Boolean get() = plans.isNotEmpty()
    val periods: List<BillingPeriod> get() = BillingPeriod.entries.filter { p -> plans.any { it.period == p } }
    fun option(plan: HostedPlan, period: BillingPeriod): PlanOption? =
        plans.firstOrNull { it.plan == plan && it.period == period }
}

sealed interface OfferingsState {
    data object Idle : OfferingsState
    data object Loading : OfferingsState
    data class Loaded(val catalog: HostedCatalog) : OfferingsState
    data object Failed : OfferingsState
}

sealed interface PurchaseOutcome {
    /** A subscription is active (or a consumable/tip transaction completed). */
    data object Success : PurchaseOutcome
    /** Google Play has not confirmed payment yet (cash/pending methods) or RevenueCat is still validating. */
    data object Pending : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data object AlreadyOwned : PurchaseOutcome
    data object Busy : PurchaseOutcome
    data object Failed : PurchaseOutcome
}

sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome
    data object NothingToRestore : RestoreOutcome
    data object Failed : RestoreOutcome
}

/** How a plan purchase relates to the active one; decides the Play replacement mode and the CTA. */
enum class PlanChange { NEW, CURRENT, UPGRADE, SWITCH }

fun planChange(current: HostedEntitlement, plan: HostedPlan, period: BillingPeriod): PlanChange = when {
    !current.isEntitled -> PlanChange.NEW
    current.plan == plan && current.activePeriod == period -> PlanChange.CURRENT
    current.plan == HostedPlan.PLUS && plan == HostedPlan.PRO -> PlanChange.UPGRADE
    else -> PlanChange.SWITCH
}

/** Maps a RevenueCat package to a billing period: package type, then store period, then id suffix. */
fun billingPeriodOf(packageIdentifier: String?, periodUnit: Period.Unit?, periodValue: Int?, productId: String): BillingPeriod? {
    when (packageIdentifier) {
        PackageType.MONTHLY.identifier -> return BillingPeriod.MONTHLY
        PackageType.ANNUAL.identifier -> return BillingPeriod.YEARLY
    }
    when {
        periodUnit == Period.Unit.MONTH && periodValue == 1 -> return BillingPeriod.MONTHLY
        periodUnit == Period.Unit.YEAR && periodValue == 1 -> return BillingPeriod.YEARLY
        periodUnit == Period.Unit.MONTH && periodValue == 12 -> return BillingPeriod.YEARLY
    }
    return periodFromIdentifier(productId.substringAfterLast(':').substringAfterLast('.'))
}

private fun periodFromIdentifier(raw: String?): BillingPeriod? = when (raw?.lowercase()) {
    "monthly", "month", "p1m" -> BillingPeriod.MONTHLY
    "yearly", "annual", "year", "p1y", "p12m" -> BillingPeriod.YEARLY
    else -> null
}

/**
 * RevenueCat + Google Play Billing for Hosted AI plans, credit packs and the tip jar.
 * Credits are never granted on the device: after a purchase the Worker re-reads
 * RevenueCat (`/quota?refresh=1`) and reconciles the ledger.
 */
class BillingManager(
    private val scope: CoroutineScope,
    private val quota: HostedQuotaStore
) {
    val isAvailable: Boolean get() = Purchases.isConfigured

    private val _entitlement = MutableStateFlow(HostedEntitlement())
    val entitlement: StateFlow<HostedEntitlement> = _entitlement.asStateFlow()

    private val _offerings = MutableStateFlow<OfferingsState>(OfferingsState.Idle)
    val offerings: StateFlow<OfferingsState> = _offerings.asStateFlow()

    private val purchaseInFlight = AtomicBoolean(false)
    private var lastSignature: Pair<HostedPlan, Int>? = null

    fun start() {
        if (!isAvailable) return
        Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener { info -> apply(info) }
        scope.launch { refreshCustomerInfo() }
    }

    /** The anonymous RevenueCat id the Worker ledger is keyed on; null when billing is unavailable. */
    fun appUserId(): String? = if (isAvailable) Purchases.sharedInstance.appUserID else null

    /** False on failure — callers must not treat a failed refresh as "no plan". */
    suspend fun refreshCustomerInfo(): Boolean {
        if (!isAvailable) return false
        return try {
            apply(Purchases.sharedInstance.awaitCustomerInfo())
            true
        } catch (e: PurchasesException) {
            Log.w(TAG, "customerInfo failed: ${e.code}")
            false
        }
    }

    private val offeringsLock = Mutex()
    private var offeringsJob: Deferred<OfferingsState>? = null

    /** Loads the plans once at a time; a reload keeps showing the last good catalog. */
    suspend fun loadOfferings(): OfferingsState {
        if (!isAvailable) {
            _offerings.value = OfferingsState.Failed
            return OfferingsState.Failed
        }
        val job = offeringsLock.withLock {
            offeringsJob?.takeIf { it.isActive } ?: scope.async { fetchOfferings() }.also { offeringsJob = it }
        }
        return job.await()
    }

    private suspend fun fetchOfferings(): OfferingsState {
        val previous = _offerings.value
        if (previous !is OfferingsState.Loaded) _offerings.value = OfferingsState.Loading
        val next = try {
            OfferingsState.Loaded(catalogFrom(Purchases.sharedInstance.awaitOfferings()))
        } catch (e: PurchasesException) {
            Log.w(TAG, "offerings failed: ${e.code}")
            previous as? OfferingsState.Loaded ?: OfferingsState.Failed
        }
        _offerings.value = next
        return next
    }

    val hasPurchasablePlans: Boolean
        get() = (offerings.value as? OfferingsState.Loaded)?.catalog?.hasPurchasablePlans == true

    suspend fun purchasePlan(activity: Activity, option: PlanOption): PurchaseOutcome = guarded {
        val current = _entitlement.value
        val builder = PurchaseParams.Builder(activity, option.pkg)
        val change = planChange(current, option.plan, option.period)
        if (change == PlanChange.CURRENT) return@guarded PurchaseOutcome.AlreadyOwned
        val oldSubscription = current.subscriptionId
        if (change != PlanChange.NEW && current.isPlayStore && oldSubscription != null) {
            // Without oldProductId Play would start a second subscription and bill both.
            builder.oldProductId(oldSubscription)
            builder.replacementMode(
                if (change == PlanChange.UPGRADE) StoreReplacementMode.CHARGE_PRORATED_PRICE
                else StoreReplacementMode.DEFERRED
            )
        }
        val result = Purchases.sharedInstance.awaitPurchase(builder.build())
        apply(result.customerInfo)
        refreshQuotaInBackground()
        if (_entitlement.value.isEntitled) PurchaseOutcome.Success else PurchaseOutcome.Pending
    }

    /** Credit packs and tips: one-time Play products, consumed by RevenueCat so they can be bought again. */
    suspend fun purchaseProduct(activity: Activity, product: StoreProduct): PurchaseOutcome = guarded {
        val result = Purchases.sharedInstance.awaitPurchase(PurchaseParams.Builder(activity, product).build())
        apply(result.customerInfo)
        PurchaseOutcome.Success
    }

    suspend fun loadProducts(productIds: List<String>): List<StoreProduct> {
        if (!isAvailable) return emptyList()
        return try {
            val byId = Purchases.sharedInstance.awaitGetProducts(productIds, ProductType.INAPP).associateBy { it.id }
            productIds.mapNotNull { byId[it] }
        } catch (e: PurchasesException) {
            Log.w(TAG, "products failed: ${e.code}")
            emptyList()
        }
    }

    suspend fun restore(): RestoreOutcome {
        if (!isAvailable) return RestoreOutcome.Failed
        return try {
            apply(Purchases.sharedInstance.awaitRestore())
            quota.refresh(force = true)
            if (_entitlement.value.isEntitled) RestoreOutcome.Restored else RestoreOutcome.NothingToRestore
        } catch (e: PurchasesException) {
            Log.w(TAG, "restore failed: ${e.code}")
            RestoreOutcome.Failed
        }
    }

    private suspend fun guarded(block: suspend () -> PurchaseOutcome): PurchaseOutcome {
        if (!isAvailable) return PurchaseOutcome.Failed
        if (!purchaseInFlight.compareAndSet(false, true)) return PurchaseOutcome.Busy
        return try {
            block()
        } catch (e: PurchasesTransactionException) {
            when {
                e.userCancelled -> PurchaseOutcome.Cancelled
                e.code == PurchasesErrorCode.PaymentPendingError -> PurchaseOutcome.Pending
                e.code == PurchasesErrorCode.ProductAlreadyPurchasedError -> PurchaseOutcome.AlreadyOwned
                else -> {
                    Log.w(TAG, "purchase failed: ${e.code}")
                    PurchaseOutcome.Failed
                }
            }
        } catch (e: PurchasesException) {
            Log.w(TAG, "purchase failed: ${e.code}")
            PurchaseOutcome.Failed
        } finally {
            purchaseInFlight.set(false)
        }
    }

    private fun refreshQuotaInBackground() {
        scope.launch { quota.refresh(force = true) }
    }

    private fun apply(info: CustomerInfo) {
        val pro = info.entitlements[HostedAiConstants.PRO_ENTITLEMENT]?.takeIf { it.isActive }
        val plus = info.entitlements[HostedAiConstants.PLUS_ENTITLEMENT]?.takeIf { it.isActive }
        val active = pro ?: plus
        val plan = when {
            pro != null -> HostedPlan.PRO
            plus != null -> HostedPlan.PLUS
            else -> HostedPlan.NONE
        }
        _entitlement.value = HostedEntitlement(
            plan = plan,
            subscriptionId = active?.productIdentifier?.substringBefore(':'),
            basePlanId = active?.productPlanIdentifier ?: active?.productIdentifier?.substringAfter(':', "")?.ifEmpty { null },
            isPlayStore = active?.store == Store.PLAY_STORE,
            managementUrl = info.managementURL?.toString(),
            loaded = true
        )
        // A plan change or a new consumable (e.g. a pending purchase that just
        // completed) means the Worker ledger changed: re-verify it now.
        val signature = plan to info.nonSubscriptionTransactions.size
        val previous = lastSignature
        lastSignature = signature
        if (previous != null && previous != signature) refreshQuotaInBackground()
    }

    companion object {
        private const val TAG = "BillingManager"

        /** Configure once, before anything reads [Purchases.sharedInstance]. Empty key = billing off. */
        fun configure(app: Application) {
            val key = BuildConfig.REVENUECAT_API_KEY
            if (key.isBlank() || Purchases.isConfigured) return
            Purchases.logLevel = LogLevel.WARN
            Purchases.configure(PurchasesConfiguration.Builder(app, key).build())
        }

        fun catalogFrom(offerings: Offerings): HostedCatalog {
            val plans = mutableListOf<PlanOption>()
            val credits = linkedMapOf<String, Package>()
            val creditIds = HostedAiConstants.CREDIT_PACKS.map { it.productId }.toSet()
            for ((plan, offeringId) in listOf(
                HostedPlan.PLUS to HostedAiConstants.PLUS_OFFERING,
                HostedPlan.PRO to HostedAiConstants.PRO_OFFERING
            )) {
                val offering = offerings.getOffering(offeringId) ?: continue
                for (pkg in offering.availablePackages) {
                    val product = pkg.product
                    if (product.type == ProductType.SUBS) {
                        val period = billingPeriodOf(
                            pkg.packageType.identifier ?: pkg.identifier,
                            product.period?.unit,
                            product.period?.value,
                            product.id
                        ) ?: continue
                        if (plans.none { it.plan == plan && it.period == period }) plans += PlanOption(plan, period, pkg)
                    } else if (product.id in creditIds) {
                        credits.putIfAbsent(product.id, pkg)
                    }
                }
            }
            val orderedCredits = HostedAiConstants.CREDIT_PACKS.mapNotNull { credits[it.productId] }
            return HostedCatalog(plans, orderedCredits)
        }
    }
}
