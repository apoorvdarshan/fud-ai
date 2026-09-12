package com.apoorvdarshan.calorietracker.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostedAIQuotaManagerTest {

    private lateinit var manager: HostedAIQuotaManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = HostedAIQuotaManager(context)
    }

    @Test
    fun plusDailyLimitIs30() {
        assertEquals(30, HostedAIConstants.dailyLimit(HostedPlan.PLUS))
    }

    @Test
    fun proDailyLimitIs60() {
        assertEquals(60, HostedAIConstants.dailyLimit(HostedPlan.PRO))
    }

    @Test
    fun voiceFoodCostsTwo() {
        assertEquals(2, HostedAIAction.VOICE_FOOD.cost)
    }

    @Test
    fun productionLedgerSpendAndReject() = runBlocking {
        manager.resetIfNeeded()

        val first = manager.spend(5, HostedPlan.PLUS, hasEntitlement = true)
        assertTrue(first is HostedAISpendResult.Spent)
        first as HostedAISpendResult.Spent
        assertEquals(5, first.fromDaily)
        assertEquals(0, first.fromCredits)

        manager.addCredits(50)
        repeat(25) {
            manager.spend(1, HostedPlan.PLUS, hasEntitlement = true)
        }
        val overflow = manager.spend(5, HostedPlan.PLUS, hasEntitlement = true)
        assertTrue(overflow is HostedAISpendResult.Spent)
        overflow as HostedAISpendResult.Spent
        assertEquals(0, overflow.fromDaily)
        assertEquals(5, overflow.fromCredits)

        val rejected = manager.spend(999, HostedPlan.PLUS, hasEntitlement = true)
        assertTrue(rejected is HostedAISpendResult.Rejected)

        val withoutEntitlement = manager.spend(1, HostedPlan.PLUS, hasEntitlement = false)
        assertTrue(withoutEntitlement is HostedAISpendResult.Rejected)
    }
}
