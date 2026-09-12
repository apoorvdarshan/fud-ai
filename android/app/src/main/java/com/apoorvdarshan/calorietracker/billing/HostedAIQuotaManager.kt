package com.apoorvdarshan.calorietracker.billing

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.apoorvdarshan.calorietracker.data.fudaiDataStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class HostedAIQuotaSnapshot(
    val dailyUsed: Int,
    val dailyLimit: Int,
    val creditBank: Int,
    val plan: HostedPlan
) {
    val dailyRemaining: Int get() = (dailyLimit - dailyUsed).coerceAtLeast(0)
}

sealed class HostedAISpendResult {
    data class Spent(val fromDaily: Int, val fromCredits: Int) : HostedAISpendResult()
    data class Rejected(val error: HostedAIQuotaError) : HostedAISpendResult()
}

class HostedAIQuotaManager(context: Context) {
    private val ds = context.fudaiDataStore

    private object Keys {
        val DAILY_USED = intPreferencesKey("hostedAI.dailyUsed")
        val DAILY_RESET_DAY = stringPreferencesKey("hostedAI.dailyResetDay")
        val CREDIT_BANK = intPreferencesKey("hostedAI.creditBank")
    }

    suspend fun creditBank(): Int = ds.data.map { it[Keys.CREDIT_BANK] ?: 0 }.first()

    suspend fun addCredits(amount: Int) {
        if (amount <= 0) return
        ds.edit { prefs ->
            prefs[Keys.CREDIT_BANK] = (prefs[Keys.CREDIT_BANK] ?: 0) + amount
        }
    }

    suspend fun refund(fromDaily: Int, fromCredits: Int) {
        resetIfNeeded()
        ds.edit { prefs ->
            if (fromDaily > 0) {
                prefs[Keys.DAILY_USED] = ((prefs[Keys.DAILY_USED] ?: 0) - fromDaily).coerceAtLeast(0)
            }
            if (fromCredits > 0) {
                prefs[Keys.CREDIT_BANK] = (prefs[Keys.CREDIT_BANK] ?: 0) + fromCredits
            }
        }
    }

    suspend fun resetIfNeeded(today: String = localDayKey()) {
        ds.edit { prefs ->
            if (prefs[Keys.DAILY_RESET_DAY] != today) {
                prefs[Keys.DAILY_USED] = 0
                prefs[Keys.DAILY_RESET_DAY] = today
            }
        }
    }

    suspend fun snapshot(plan: HostedPlan): HostedAIQuotaSnapshot {
        resetIfNeeded()
        val prefs = ds.data.first()
        return HostedAIQuotaSnapshot(
            dailyUsed = prefs[Keys.DAILY_USED] ?: 0,
            dailyLimit = HostedAIConstants.dailyLimit(plan),
            creditBank = prefs[Keys.CREDIT_BANK] ?: 0,
            plan = plan
        )
    }

    suspend fun availableActions(plan: HostedPlan, hasEntitlement: Boolean): Int {
        resetIfNeeded()
        if (!hasEntitlement) return 0
        val prefs = ds.data.first()
        val used = prefs[Keys.DAILY_USED] ?: 0
        val dailyRemaining = (HostedAIConstants.dailyLimit(plan) - used).coerceAtLeast(0)
        return dailyRemaining + (prefs[Keys.CREDIT_BANK] ?: 0)
    }

    suspend fun spend(cost: Int, plan: HostedPlan, hasEntitlement: Boolean): HostedAISpendResult {
        resetIfNeeded()
        if (!hasEntitlement) return HostedAISpendResult.Rejected(HostedAIQuotaError.NoActiveSubscription)
        if (cost <= 0) return HostedAISpendResult.Spent(0, 0)

        var fromDaily = 0
        var fromCredits = 0
        var rejected: HostedAIQuotaError? = null

        ds.edit { prefs ->
            val limit = HostedAIConstants.dailyLimit(plan)
            val used = prefs[Keys.DAILY_USED] ?: 0
            val bank = prefs[Keys.CREDIT_BANK] ?: 0
            val dailyRemaining = (limit - used).coerceAtLeast(0)

            var remaining = cost
            fromDaily = minOf(dailyRemaining, remaining)
            remaining -= fromDaily
            fromCredits = remaining

            if (remaining > 0) {
                if (bank < remaining) {
                    rejected = HostedAIQuotaError.QuotaExceeded(dailyRemaining, bank)
                    return@edit
                }
                prefs[Keys.CREDIT_BANK] = bank - remaining
                prefs[Keys.DAILY_USED] = used + fromDaily
            } else {
                prefs[Keys.DAILY_USED] = used + fromDaily
            }
        }

        rejected?.let { return HostedAISpendResult.Rejected(it) }
        return HostedAISpendResult.Spent(fromDaily, fromCredits)
    }

    companion object {
        fun localDayKey(date: LocalDate = LocalDate.now()): String =
            date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
}
