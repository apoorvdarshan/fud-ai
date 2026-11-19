package com.apoorvdarshan.calorietracker.ui.hosted

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.android.billingclient.api.ProductDetails
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.FudAIApp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.hosted.*
import com.apoorvdarshan.calorietracker.testing.FudAITestState
import com.apoorvdarshan.calorietracker.ui.components.PostUpdatePromptsHost
import com.apoorvdarshan.calorietracker.ui.onboarding.OnboardingViewModel
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.PresentedOfferingContext
import com.revenuecat.purchases.models.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Actual production popup, router, paywall, preference persistence and trial detection.
 * Only billing I/O and unrelated background services are replaced. Real RevenueCat
 * pricing phases/default-offer selection represent what Play returns for an eligible
 * account or an account without an offer. These are UI tests, not store eligibility tests.
 * No production source, purchase, network call or personal device is involved.
 */
class TrialOfferUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val actual get() = (context.applicationContext as FudAIApp).container
    private val prefs get() = actual.prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val viewModels = ViewModelStore()
    private lateinit var entitlement: MutableStateFlow<HostedEntitlement>
    private lateinit var offerings: MutableStateFlow<OfferingsState>
    private val generation = mutableStateOf(0)
    private val completedPromptFlows = AtomicInteger()
    private lateinit var billing: BillingManager
    private lateinit var container: AppContainer
    private lateinit var router: HostedUiRouter

    @Before fun prepare() {
        FudAITestState.reset()
        runBlocking {
            // Existing upgraded user: onboarding was completed before this campaign.
            prefs.setHasSeenHostedUpsellPrompt(false)
            prefs.setHasSeenWhatsNewLiveVoice(true)
            prefs.setHasSeenMeetDeveloperPrompt(true)
            prefs.setHasSeenProductHuntLaunchPrompt(true)
        }
        router = HostedUiRouter()
        billing = spyk(BillingManager(scope, mockk()))
        every { billing.isAvailable } returns true
        // Seed the actual backing flows, rather than mocking their public getters:
        // Kotlin reads backing fields directly inside BillingManager's computed
        // properties. This preserves its real trial detection without app changes.
        entitlement = billingState("_entitlement")
        offerings = billingState("_offerings")
        entitlement.value = HostedEntitlement(loaded = true)
        coEvery { billing.refreshCustomerInfo() } returns true
        coEvery { billing.loadOfferings() } answers { offerings.value }
        coEvery { billing.loadProducts(any()) } returns emptyList()
        // hasEligibleYearlyTrial and PlanOption.hasThreeDayTrial run unchanged.
        container = mockk()
        every { container.prefs } returns prefs
        every { container.billing } returns billing
        every { container.hostedUi } returns router
        every { container.keyStore } returns actual.keyStore
        every { container.voiceAssistant.isActive } returns false
        every { container.notifications.cancelWhatsNew() } returns Unit
        coEvery { container.notifications.scheduleProductHuntLaunchReminderIfNeeded(any()) } answers {
            completedPromptFlows.incrementAndGet()
            false
        }
        catalog(eligible = true)
        check(billing.hasEligibleYearlyTrial) { "Eligible billing fixture must reach the real computed property" }
    }

    @After fun cleanUp() {
        instrumentation.runOnMainSync { viewModels.clear() }
        scope.cancel()
    }

    @Test fun existingNeverSubscribedUserGetsPopupAndYearlyTrialTerms() {
        render()
        waitFor(R.string.hosted_upsell_cta)
        compose.onNodeWithText(text(R.string.hosted_trial_invite_title)).assertIsDisplayed()
        screenshot("existing-user-trial-popup")
        compose.onNodeWithText(text(R.string.hosted_upsell_cta)).performClick()
        assertTrialPaywall("₹6,800.00", 2)
        screenshot("existing-user-plus-trial-paywall")
        compose.onNodeWithText(text(R.string.hosted_plan_pro)).performClick()
        assertTrialPaywall("₹17,100.00", 5)
        assertTrue(runBlocking { prefs.hasSeenHostedUpsellPrompt.first() })
    }

    @Test fun newUserHostedOnboardingActionOpensTrialPaywall() {
        runBlocking { prefs.clearAll() }
        render()
        // The production action behind onboarding's Hosted AI card opens the same router.
        instrumentation.runOnMainSync {
            val vm = OnboardingViewModel(container)
            viewModels.put("onboarding", vm)
            vm.selectHostedSetup()
        }
        assertTrialPaywall("₹6,800.00", 2)
        compose.onNodeWithText(text(R.string.hosted_upsell_cta)).assertDoesNotExist()
        screenshot("new-user-trial-paywall")
    }

    @Test fun freshOnboardingCompletionDoesNotShowAnExistingUserPopup() {
        runBlocking {
            prefs.clearAll()
            prefs.setOnboardingCompleted(true)
            prefs.setHasSeenProductHuntLaunchPrompt(true)
        }
        render()
        settlePrompt()
        assertNoInvite()
        assertTrue(runBlocking { prefs.hasSeenHostedUpsellPrompt.first() })
        // Fresh users can still get the offer by opening the paywall themselves.
        compose.runOnIdle { router.show(HostedPrompt.Paywall) }
        assertTrialPaywall("₹6,800.00", 2)
    }

    @Test fun offerUnavailableAtFirstLaunchRemainsEligibleForPopupOnNextLaunch() {
        catalog(eligible = false)
        render()
        settlePrompt()
        assertNoInvite()
        assertFalse(runBlocking { prefs.hasSeenHostedUpsellPrompt.first() })
        catalog(eligible = true)
        relaunchPrompt()
        waitFor(R.string.hosted_upsell_cta)
        compose.onNodeWithText(text(R.string.hosted_trial_invite_title)).assertIsDisplayed()
    }

    @Test fun failedBillingRefreshDoesNotConsumeThePopup() {
        coEvery { billing.refreshCustomerInfo() } returns false
        render()
        settlePrompt()
        assertNoInvite()
        assertFalse(runBlocking { prefs.hasSeenHostedUpsellPrompt.first() })
        coEvery { billing.refreshCustomerInfo() } returns true
        relaunchPrompt()
        waitFor(R.string.hosted_upsell_cta)
    }

    @Test fun dismissedPopupDoesNotRepeatOnNextLaunch() {
        render()
        waitFor(R.string.hosted_upsell_cta)
        compose.onNodeWithText(text(R.string.hosted_upsell_keep)).performClick()
        relaunchPrompt()
        settlePrompt()
        assertNoInvite()
        assertTrue(runBlocking { prefs.hasSeenHostedUpsellPrompt.first() })
    }

    @Test fun activeSubscriberDoesNotGetTrialPopupOrTrialPurchaseButton() {
        entitlement.value = HostedEntitlement(
            plan = HostedPlan.PLUS, subscriptionId = "plus", basePlanId = "yearly",
            isPlayStore = true, loaded = true
        )
        render()
        settlePrompt()
        assertNoInvite()
        compose.runOnIdle { router.show(HostedPrompt.Paywall) }
        waitFor(R.string.hosted_cta_upgrade)
        compose.onNodeWithText(text(R.string.hosted_trial_cta)).assertDoesNotExist()
    }

    @Test fun ineligibleAccountSeesPaidTermsWithoutMisleadingTrialText() {
        catalog(eligible = false)
        render()
        settlePrompt()
        assertNoInvite()
        compose.runOnIdle { router.show(HostedPrompt.Paywall) }
        waitForText(context.getString(R.string.hosted_cta_subscribe, "Plus"))
        compose.onNodeWithText(text(R.string.hosted_trial_cta)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.hosted_trial_disclosure, "₹6,800.00")).assertDoesNotExist()
        screenshot("ineligible-user-paid-paywall")
    }

    @Test fun monthlyPlanHasPaidTermsAndSwitchingBackRestoresYearlyTrial() {
        // Avoid the separate invite while testing period selection in the open paywall.
        runBlocking { prefs.setHasSeenHostedUpsellPrompt(true) }
        render()
        compose.runOnIdle { router.show(HostedPrompt.Paywall) }
        assertTrialPaywall("₹6,800.00", 2)
        compose.onNodeWithText(text(R.string.hosted_period_monthly)).performClick()
        waitForText(context.getString(R.string.hosted_cta_subscribe, "Plus"))
        compose.onNodeWithText(text(R.string.hosted_trial_cta)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.hosted_period_yearly)).performClick()
        assertTrialPaywall("₹6,800.00", 2)
    }

    @Test fun inactiveHostedModeDoesNotGetByokOnlyAutomaticPopup() {
        runBlocking { prefs.setAiAccessMode(AiAccessMode.HOSTED) }
        render()
        settlePrompt()
        assertNoInvite()
        assertFalse(runBlocking { prefs.hasSeenHostedUpsellPrompt.first() })
        compose.runOnIdle { router.show(HostedPrompt.Paywall) }
        assertTrialPaywall("₹6,800.00", 2)
    }

    @Test fun unseenWhatsNewComesFirstAndTrialPopupAppearsOnNextLaunch() {
        runBlocking { prefs.setHasSeenWhatsNewLiveVoice(false) }
        render()
        waitFor(R.string.whats_new_next)
        compose.onNodeWithText(text(R.string.hosted_trial_invite_title)).assertDoesNotExist()
        // Complete the real four-page What's New flow before relaunching the host.
        repeat(3) {
            compose.onNodeWithText(text(R.string.whats_new_next)).performClick()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        waitFor(R.string.cd_close)
        compose.onNodeWithText(text(R.string.cd_close)).performClick()
        compose.waitUntil(5_000) { runBlocking { prefs.hasSeenWhatsNewLiveVoice.first() } }
        assertNoInvite()
        assertFalse(runBlocking { prefs.hasSeenHostedUpsellPrompt.first() })
        relaunchPrompt()
        waitFor(R.string.hosted_upsell_cta)
        compose.onNodeWithText(text(R.string.hosted_trial_invite_title)).assertIsDisplayed()
    }

    private fun render() {
        compose.setContent {
            MaterialTheme {
                key(generation.value) { PostUpdatePromptsHost(container, enabled = true) }
                HostedUiHost(container)
            }
        }
    }

    private fun relaunchPrompt() {
        compose.runOnIdle {
            completedPromptFlows.set(0)
            generation.value++
        }
    }

    private fun settlePrompt() {
        compose.waitForIdle()
        // The production prompt waits 2.5 seconds before reading persisted state.
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        // DataStore resumes outside Compose's virtual clock; wait for the end-of-flow hook.
        compose.waitUntil(5_000) { completedPromptFlows.get() > 0 }
    }

    private fun assertNoInvite() {
        compose.onNodeWithText(text(R.string.hosted_upsell_cta)).assertDoesNotExist()
    }

    private fun assertTrialPaywall(price: String, minutes: Int) {
        waitFor(R.string.hosted_trial_cta)
        compose.onNodeWithText(text(R.string.hosted_trial_cta)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hosted_trial_disclosure, price)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hosted_trial_limits, minutes)).assertIsDisplayed()
    }

    private fun text(id: Int) = context.getString(id)

    @Suppress("UNCHECKED_CAST")
    private fun <T> billingState(name: String): MutableStateFlow<T> =
        BillingManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(billing) as MutableStateFlow<T>

    private fun waitFor(id: Int) = waitForText(text(id))
    private fun waitForText(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun catalog(eligible: Boolean) {
        offerings.value = OfferingsState.Loaded(HostedCatalog(
            plans = listOf(
                option(HostedPlan.PLUS, BillingPeriod.YEARLY, "₹6,800.00", 6_800_000_000, eligible),
                option(HostedPlan.PRO, BillingPeriod.YEARLY, "₹17,100.00", 17_100_000_000, eligible),
                option(HostedPlan.PLUS, BillingPeriod.MONTHLY, "₹899.00", 899_000_000, false),
                option(HostedPlan.PRO, BillingPeriod.MONTHLY, "₹1,999.00", 1_999_000_000, false)
            ), creditPacks = emptyList()
        ))
    }

    private fun option(plan: HostedPlan, period: BillingPeriod, formatted: String, micros: Long, eligible: Boolean): PlanOption {
        val id = if (plan == HostedPlan.PLUS) "plus" else "pro"
        val baseId = if (period == BillingPeriod.YEARLY) "yearly" else "monthly"
        val price = Price(formatted, micros, "INR")
        val billingPeriod = Period.create(if (period == BillingPeriod.YEARLY) "P1Y" else "P1M")
        val paid = PricingPhase(billingPeriod, RecurrenceMode.INFINITE_RECURRING, null, price)
        val details = mockk<ProductDetails>()
        val base = GoogleSubscriptionOption(id, baseId, null, listOf(paid), emptyList(), details, "test-base")
        val trial = GoogleSubscriptionOption(
            id, baseId, "three-day-trial",
            listOf(PricingPhase(Period.create("P3D"), RecurrenceMode.FINITE_RECURRING, 1, Price("₹0.00", 0, "INR")), paid),
            emptyList(), details, "test-trial"
        )
        val options = SubscriptionOptions(if (eligible) listOf(base, trial) else listOf(base))
        val product = mockk<StoreProduct> {
            every { this@mockk.price } returns price
            every { this@mockk.period } returns billingPeriod
            every { this@mockk.id } returns "$id:$baseId"
            every { defaultOption } returns options.defaultOffer
        }
        return PlanOption(plan, period, Package(
            if (period == BillingPeriod.YEARLY) "\$rc_annual" else "\$rc_monthly",
            if (period == BillingPeriod.YEARLY) PackageType.ANNUAL else PackageType.MONTHLY,
            product, PresentedOfferingContext(id)
        ))
    }
}
