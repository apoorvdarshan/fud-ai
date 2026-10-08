package com.apoorvdarshan.calorietracker.services.hosted

import com.apoorvdarshan.calorietracker.ui.hosted.yearlySavingsPercent
import com.revenuecat.purchases.models.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingRulesTest {
    private val plusMonthly = HostedEntitlement(HostedPlan.PLUS, "plus", "monthly", isPlayStore = true, loaded = true)

    @Test
    fun planChangeDecidesTheReplacementPath() {
        assertEquals(PlanChange.NEW, planChange(HostedEntitlement(loaded = true), HostedPlan.PLUS, BillingPeriod.MONTHLY))
        assertEquals(PlanChange.CURRENT, planChange(plusMonthly, HostedPlan.PLUS, BillingPeriod.MONTHLY))
        assertEquals(PlanChange.UPGRADE, planChange(plusMonthly, HostedPlan.PRO, BillingPeriod.YEARLY))
        assertEquals(PlanChange.SWITCH, planChange(plusMonthly, HostedPlan.PLUS, BillingPeriod.YEARLY))
        val proYearly = HostedEntitlement(HostedPlan.PRO, "pro", "yearly", isPlayStore = true, loaded = true)
        assertEquals(PlanChange.SWITCH, planChange(proYearly, HostedPlan.PLUS, BillingPeriod.YEARLY))
    }

    @Test
    fun billingPeriodComesFromPackageTypeThenStorePeriodThenId() {
        assertEquals(BillingPeriod.MONTHLY, billingPeriodOf("\$rc_monthly", null, null, "plus:monthly"))
        assertEquals(BillingPeriod.YEARLY, billingPeriodOf("\$rc_annual", null, null, "x"))
        assertEquals(BillingPeriod.YEARLY, billingPeriodOf("pro_yearly", Period.Unit.YEAR, 1, "pro:yearly"))
        assertEquals(BillingPeriod.YEARLY, billingPeriodOf("pro_yearly", Period.Unit.MONTH, 12, "pro:annual-plan"))
        assertEquals(BillingPeriod.MONTHLY, billingPeriodOf("custom", null, null, "pro:monthly"))
        assertNull(billingPeriodOf("custom", Period.Unit.WEEK, 1, "pro:weekly"))
    }

    @Test
    fun yearlySavingsMatchesTheListPrices() {
        assertEquals(35, yearlySavingsPercent(8_990_000, "USD", 69_990_000, "USD"))
        assertEquals(31, yearlySavingsPercent(17_990_000, "USD", 149_990_000, "USD"))
        assertNull(yearlySavingsPercent(8_990_000, "USD", 69_990_000, "EUR"))
        assertNull(yearlySavingsPercent(8_990_000, "USD", 107_000_000, "USD"))
    }

    @Test
    fun aiAccessModeDefaultsToByok() {
        assertEquals(AiAccessMode.BYOK, AiAccessMode.fromStorage(null))
        assertEquals(AiAccessMode.HOSTED, AiAccessMode.fromStorage("hosted"))
        assertEquals(AiAccessMode.BYOK, AiAccessMode.fromStorage("unknown"))
    }

    @Test
    fun creditPacksMatchTheWorkerCatalog() {
        // web/hosted-ai-ledger.ts and store/catalog/products.json use the same Play ids.
        assertEquals(
            listOf("credits_50" to 50, "credits_150" to 150, "credits_400" to 400),
            HostedAiConstants.CREDIT_PACKS.map { it.productId to it.credits }
        )
    }
}
