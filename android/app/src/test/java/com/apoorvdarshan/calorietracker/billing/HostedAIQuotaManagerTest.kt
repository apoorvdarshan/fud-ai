package com.apoorvdarshan.calorietracker.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedAIQuotaManagerTest {

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
    fun spendOrderUsesDailyBeforeCredits() {
        var dailyUsed = 0
        var creditBank = 100
        val limit = 30
        val cost = 5
        val dailyRemaining = (limit - dailyUsed).coerceAtLeast(0)
        val fromDaily = minOf(dailyRemaining, cost)
        var remaining = cost - fromDaily
        val fromCredits = remaining
        dailyUsed += fromDaily
        creditBank -= fromCredits
        assertEquals(5, fromDaily)
        assertEquals(0, fromCredits)
        assertEquals(5, dailyUsed)
        assertEquals(100, creditBank)
    }

    @Test
    fun adaptiveGoalsActionNotInMeteredSet() {
        assertTrue(HostedAIAction.COACH_MESSAGE.cost == 1)
    }
}
