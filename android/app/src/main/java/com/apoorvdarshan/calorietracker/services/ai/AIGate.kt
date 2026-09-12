package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.billing.AIMode
import com.apoorvdarshan.calorietracker.billing.HostedAIAction
import com.apoorvdarshan.calorietracker.billing.HostedAIQuotaError
import com.apoorvdarshan.calorietracker.billing.HostedAIQuotaManager
import com.apoorvdarshan.calorietracker.billing.HostedAISpendResult
import com.apoorvdarshan.calorietracker.billing.RevenueCatManager
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import kotlinx.coroutines.flow.first

class AIGate(
    private val prefs: PreferencesStore,
    private val quotaManager: HostedAIQuotaManager,
    private val revenueCat: RevenueCatManager
) {
    suspend fun isHostedMode(): Boolean = prefs.aiAccessMode.first() == AIMode.HOSTED

    suspend fun consumeIfHosted(action: HostedAIAction) {
        if (!isHostedMode()) return
        consumeIfHosted(action.cost)
    }

    suspend fun consumeIfHosted(cost: Int) {
        if (!isHostedMode()) return
        val plan = revenueCat.activePlan.value
        val entitled = revenueCat.hasHostedEntitlement.value
        when (val result = quotaManager.spend(cost, plan, entitled)) {
            is HostedAISpendResult.Rejected -> throw result.error
            is HostedAISpendResult.Spent -> Unit
        }
    }
}
