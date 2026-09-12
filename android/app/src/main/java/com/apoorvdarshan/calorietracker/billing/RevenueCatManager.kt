package com.apoorvdarshan.calorietracker.billing

import android.app.Activity
import android.content.Context
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.models.StoreProduct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RevenueCatManager(
    context: Context,
    private val quotaManager: HostedAIQuotaManager
) {
    private val appContext = context.applicationContext

    private val _activePlan = MutableStateFlow(HostedPlan.NONE)
    val activePlan: StateFlow<HostedPlan> = _activePlan.asStateFlow()

    private val _hasHostedEntitlement = MutableStateFlow(false)
    val hasHostedEntitlement: StateFlow<Boolean> = _hasHostedEntitlement.asStateFlow()

    private val _offerings = MutableStateFlow<Offerings?>(null)
    val offerings: StateFlow<Offerings?> = _offerings.asStateFlow()

    fun configure(appUserId: String? = null) {
        if (HostedAIConstants.REVENUECAT_PUBLIC_SDK_KEY.contains("PLACEHOLDER")) return
        val builder = PurchasesConfiguration.Builder(appContext, HostedAIConstants.REVENUECAT_PUBLIC_SDK_KEY)
        appUserId?.let { builder.appUserID(it) }
        Purchases.configure(builder.build())
        Purchases.sharedInstance.updatedCustomerInfoListener = { info ->
            applyCustomerInfo(info)
        }
    }

    suspend fun refreshCustomerInfo() {
        if (!Purchases.isConfigured) return
        applyCustomerInfo(awaitCustomerInfo())
    }

    suspend fun loadOfferings() {
        if (!Purchases.isConfigured) return
        _offerings.value = awaitOfferings()
    }

    suspend fun purchasePackage(activity: Activity, packageToBuy: Package): CustomerInfo {
        val result = awaitPurchase(activity, PurchaseParams.Builder(activity, packageToBuy).build())
        applyCustomerInfo(result.customerInfo)
        HostedAIConstants.creditAmount(packageToBuy.product.id)?.let { quotaManager.addCredits(it) }
        return result.customerInfo
    }

    suspend fun purchaseProduct(activity: Activity, product: StoreProduct): CustomerInfo {
        val result = awaitPurchase(activity, PurchaseParams.Builder(activity, product).build())
        applyCustomerInfo(result.customerInfo)
        HostedAIConstants.creditAmount(product.id)?.let { quotaManager.addCredits(it) }
        return result.customerInfo
    }

    suspend fun restorePurchases(): CustomerInfo {
        val info = awaitRestore()
        applyCustomerInfo(info)
        return info
    }

    suspend fun appUserId(): String {
        if (!Purchases.isConfigured) return "anonymous"
        return awaitCustomerInfo().originalAppUserId
    }

    private fun applyCustomerInfo(info: CustomerInfo) {
        val hasPro = info.entitlements[HostedAIConstants.PRO_ENTITLEMENT]?.isActive == true
        val hasPlus = info.entitlements[HostedAIConstants.PLUS_ENTITLEMENT]?.isActive == true
        _activePlan.value = HostedPlan.fromEntitlements(hasPro, hasPlus)
        _hasHostedEntitlement.value = hasPro || hasPlus
    }

    private suspend fun awaitCustomerInfo(): CustomerInfo = suspendCancellableCoroutine { cont ->
        Purchases.sharedInstance.getCustomerInfoWith(
            onError = { cont.resumeWithException(it) },
            onSuccess = { cont.resume(it) }
        )
    }

    private suspend fun awaitOfferings(): Offerings = suspendCancellableCoroutine { cont ->
        Purchases.sharedInstance.getOfferingsWith(
            onError = { cont.resumeWithException(it) },
            onSuccess = { cont.resume(it) }
        )
    }

    private suspend fun awaitPurchase(activity: Activity, params: PurchaseParams) =
        suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.purchase(
                params,
                object : com.revenuecat.purchases.interfaces.PurchaseCallback {
                    override fun onCompleted(storeTransaction: com.revenuecat.purchases.models.StoreTransaction, customerInfo: CustomerInfo) {
                        cont.resume(PurchaseResult(customerInfo))
                    }

                    override fun onError(error: PurchasesError, userCancelled: Boolean) {
                        cont.resumeWithException(error)
                    }
                }
            )
        }

    private suspend fun awaitRestore(): CustomerInfo = suspendCancellableCoroutine { cont ->
        Purchases.sharedInstance.restorePurchasesWith(
            onError = { cont.resumeWithException(it) },
            onSuccess = { cont.resume(it) }
        )
    }

    private data class PurchaseResult(val customerInfo: CustomerInfo)
}
