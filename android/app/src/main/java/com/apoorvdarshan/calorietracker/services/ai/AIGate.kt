package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.billing.AIMode
import com.apoorvdarshan.calorietracker.billing.HostedAIAction
import com.apoorvdarshan.calorietracker.billing.HostedAIQuotaError
import com.apoorvdarshan.calorietracker.billing.HostedAIQuotaManager
import com.apoorvdarshan.calorietracker.billing.HostedAISpendReceipt
import com.apoorvdarshan.calorietracker.billing.HostedAISpendResult
import com.apoorvdarshan.calorietracker.billing.RevenueCatManager
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class AIGate(
    private val prefs: PreferencesStore,
    private val quotaManager: HostedAIQuotaManager,
    private val revenueCat: RevenueCatManager
) {
    suspend fun isHostedMode(): Boolean = prefs.aiAccessMode.first() == AIMode.HOSTED

    suspend fun consumeIfHosted(action: HostedAIAction): HostedAISpendReceipt? =
        consumeIfHosted(action.cost)

    suspend fun consumeIfHosted(cost: Int): HostedAISpendReceipt? {
        if (!isHostedMode()) return null
        val plan = revenueCat.activePlan.value
        val entitled = revenueCat.hasHostedEntitlement.value
        return when (val result = quotaManager.spend(cost, plan, entitled)) {
            is HostedAISpendResult.Rejected -> throw result.error
            is HostedAISpendResult.Spent -> HostedAISpendReceipt(result.fromDaily, result.fromCredits)
        }
    }

    suspend fun refundHosted(receipt: HostedAISpendReceipt) {
        if (!isHostedMode()) return
        quotaManager.refund(receipt.fromDaily, receipt.fromCredits)
    }

    suspend fun <T> runWithHostedQuota(action: HostedAIAction, block: suspend () -> T): T =
        runWithHostedCost(action.cost, block)

    suspend fun <T> runWithHostedCost(cost: Int, block: suspend () -> T): T {
        val receipt = consumeIfHosted(cost)
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            receipt?.let { refundHosted(it) }
            throw e
        }
    }
}
