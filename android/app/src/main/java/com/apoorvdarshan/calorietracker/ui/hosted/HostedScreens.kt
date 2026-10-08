package com.apoorvdarshan.calorietracker.ui.hosted

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.hosted.AiAccessMode
import com.apoorvdarshan.calorietracker.services.hosted.BillingPeriod
import com.apoorvdarshan.calorietracker.services.hosted.HostedAiConstants
import com.apoorvdarshan.calorietracker.services.hosted.HostedCatalog
import com.apoorvdarshan.calorietracker.services.hosted.HostedPlan
import com.apoorvdarshan.calorietracker.services.hosted.HostedPrompt
import com.apoorvdarshan.calorietracker.services.hosted.OfferingsState
import com.apoorvdarshan.calorietracker.services.hosted.PlanChange
import com.apoorvdarshan.calorietracker.services.hosted.PlanOption
import com.apoorvdarshan.calorietracker.services.hosted.PurchaseOutcome
import com.apoorvdarshan.calorietracker.services.hosted.RestoreOutcome
import com.apoorvdarshan.calorietracker.services.hosted.planChange
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.components.FudGlassPrimaryButton
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextButton
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.revenuecat.purchases.models.StoreProduct
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Renders whichever Hosted AI prompt is requested app-wide. Exactly one modal at a
 * time: switching from the out-of-quota dialog to the credits sheet swaps the
 * value instead of stacking a second sheet (an iOS bug we do not port).
 */
@Composable
fun HostedUiHost(container: AppContainer) {
    val router = container.hostedUi
    val prompt by router.prompt.collectAsState()
    val entitlement by container.billing.entitlement.collectAsState()
    val scope = rememberCoroutineScope()
    when (prompt) {
        HostedPrompt.Paywall -> HostedPaywallSheet(
            container = container,
            onDismiss = router::dismiss,
            onOpenCredits = { router.show(HostedPrompt.Credits) }
        )
        HostedPrompt.Credits -> HostedCreditsSheet(
            container = container,
            onDismiss = router::dismiss,
            onNeedPlan = { router.show(HostedPrompt.Paywall) }
        )
        HostedPrompt.OutOfQuota -> HostedOutOfQuotaDialog(
            onBuyOrUpgrade = {
                router.show(if (entitlement.isEntitled) HostedPrompt.Credits else HostedPrompt.Paywall)
            },
            onSwitchToByok = {
                scope.launch { container.prefs.setAiAccessMode(AiAccessMode.BYOK) }
                router.dismiss()
            },
            onClose = router::dismiss
        )
        null -> Unit
    }
}

/** Yearly savings vs 12× monthly, only when both prices share a currency and it is at least 5%. */
fun yearlySavingsPercent(monthlyMicros: Long, monthlyCurrency: String, yearlyMicros: Long, yearlyCurrency: String): Int? {
    if (monthlyCurrency != yearlyCurrency || monthlyMicros <= 0 || yearlyMicros <= 0) return null
    val twelveMonths = monthlyMicros * 12.0
    if (yearlyMicros >= twelveMonths) return null
    return ((1 - yearlyMicros / twelveMonths) * 100).roundToInt().takeIf { it >= 5 }
}

private data class HostedMessage(val title: String?, val body: String, val closeOnOk: Boolean)

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun sheetContainerColor(): Color {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDark) Color(0xF2141416) else Color(0xFFFAF3EE)
}

@Composable
private fun planName(plan: HostedPlan): String = when (plan) {
    HostedPlan.PLUS -> stringResource(R.string.hosted_plan_plus)
    HostedPlan.PRO -> stringResource(R.string.hosted_plan_pro)
    HostedPlan.NONE -> stringResource(R.string.hosted_plan_none)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostedPaywallSheet(
    container: AppContainer,
    onDismiss: () -> Unit,
    onOpenCredits: () -> Unit
) {
    val billing = container.billing
    val entitlement by billing.entitlement.collectAsState()
    val offerings by billing.offerings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var period by rememberSaveable { mutableStateOf<BillingPeriod?>(null) }
    var plan by rememberSaveable { mutableStateOf<HostedPlan?>(null) }
    var purchasing by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<HostedMessage?>(null) }

    LaunchedEffect(Unit) {
        billing.refreshCustomerInfo()
        billing.loadOfferings()
    }

    val catalog = (offerings as? OfferingsState.Loaded)?.catalog?.takeIf { it.hasPurchasablePlans }
    // The user's picks, snapped to what the store actually offers (yearly preferred;
    // subscribers start on Pro, so a Plus subscriber sees the upgrade first).
    val selected: PlanOption? = catalog?.let { c ->
        val periods = c.periods
        val resolvedPeriod = period?.takeIf { it in periods }
            ?: entitlement.activePeriod?.takeIf { it in periods }
            ?: BillingPeriod.YEARLY.takeIf { it in periods }
            ?: periods.first()
        val preferredPlan = plan ?: if (entitlement.isEntitled) HostedPlan.PRO else HostedPlan.PLUS
        c.option(preferredPlan, resolvedPeriod) ?: c.plans.first { it.period == resolvedPeriod }
    }

    val successTitle = stringResource(R.string.hosted_purchase_success_title)
    val successBody = stringResource(R.string.hosted_purchase_success_body)
    val pendingText = stringResource(R.string.hosted_purchase_pending)
    val failedText = stringResource(R.string.hosted_purchase_failed)
    val ownedText = stringResource(R.string.hosted_purchase_already_owned)
    val restoredText = stringResource(R.string.hosted_restore_success)
    val restoreNoneText = stringResource(R.string.hosted_restore_none)
    val restoreFailedText = stringResource(R.string.hosted_restore_failed)

    fun purchase(option: PlanOption) {
        val activity = context.findActivity() ?: return
        if (purchasing || restoring) return
        purchasing = true // set before launching so a double tap cannot start two purchases
        scope.launch {
            val outcome = billing.purchasePlan(activity, option)
            purchasing = false
            message = when (outcome) {
                PurchaseOutcome.Success -> {
                    container.prefs.setAiAccessMode(AiAccessMode.HOSTED)
                    HostedMessage(successTitle, successBody, closeOnOk = true)
                }
                PurchaseOutcome.Pending -> HostedMessage(null, pendingText, closeOnOk = true)
                PurchaseOutcome.AlreadyOwned -> HostedMessage(null, ownedText, closeOnOk = false)
                PurchaseOutcome.Failed -> HostedMessage(null, failedText, closeOnOk = false)
                PurchaseOutcome.Cancelled, PurchaseOutcome.Busy -> null
            }
        }
    }

    fun restore() {
        if (purchasing || restoring) return
        restoring = true
        scope.launch {
            val outcome = billing.restore()
            restoring = false
            message = when (outcome) {
                RestoreOutcome.Restored -> {
                    container.prefs.setAiAccessMode(AiAccessMode.HOSTED)
                    HostedMessage(null, restoredText, closeOnOk = true)
                }
                RestoreOutcome.NothingToRestore -> HostedMessage(null, restoreNoneText, closeOnOk = false)
                RestoreOutcome.Failed -> HostedMessage(null, restoreFailedText, closeOnOk = false)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetContainerColor(),
        dragHandle = null
    ) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.hosted_close))
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PaywallHero()
                when {
                    catalog != null && selected != null -> {
                        if (catalog.periods.size > 1) {
                            PeriodToggle(catalog = catalog, selectedPlan = selected.plan, period = selected.period) { period = it }
                        }
                        catalog.plans.filter { it.period == selected.period }.forEach { option ->
                            PlanCard(
                                option = option,
                                isSelected = option.plan == selected.plan,
                                isCurrent = entitlement.plan == option.plan,
                                onClick = { plan = option.plan }
                            )
                        }
                        IncludedFeatures()
                        if (entitlement.isEntitled) {
                            FudGlassTextButton(
                                text = stringResource(R.string.hosted_buy_credits),
                                onClick = onOpenCredits,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    offerings is OfferingsState.Failed || (offerings is OfferingsState.Loaded && catalog == null) ->
                        PlansUnavailable(onRetry = { scope.launch { billing.loadOfferings() } })
                    else -> PlansLoading()
                }
                Spacer(Modifier.height(4.dp))
            }
            if (selected != null) {
                PurchaseBar(
                    option = selected,
                    change = planChange(entitlement, selected.plan, selected.period),
                    purchasing = purchasing,
                    restoring = restoring,
                    onPurchase = { purchase(selected) },
                    onRestore = ::restore,
                    onOpenUrl = context::openUrl
                )
            } else {
                LegalLinks(restoring = restoring, onRestore = ::restore, onOpenUrl = context::openUrl)
            }
        }
    }

    message?.let { current ->
        MessageDialog(current) {
            message = null
            if (current.closeOnOk) onDismiss()
        }
    }
}

@Composable
private fun PaywallHero() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(painter = painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(64.dp))
        Text(stringResource(R.string.hosted_paywall_title), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.hosted_paywall_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PeriodToggle(catalog: HostedCatalog, selectedPlan: HostedPlan, period: BillingPeriod, onSelect: (BillingPeriod) -> Unit) {
    val monthly = catalog.option(selectedPlan, BillingPeriod.MONTHLY)?.product?.price
    val yearly = catalog.option(selectedPlan, BillingPeriod.YEARLY)?.product?.price
    val savings = if (monthly != null && yearly != null) {
        yearlySavingsPercent(monthly.amountMicros, monthly.currencyCode, yearly.amountMicros, yearly.currencyCode)
    } else null
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (option in catalog.periods) {
            val active = option == period
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) AppColors.Calorie.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(if (option == BillingPeriod.MONTHLY) R.string.hosted_period_monthly else R.string.hosted_period_yearly),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) AppColors.Calorie else MaterialTheme.colorScheme.onSurface
                )
                if (option == BillingPeriod.YEARLY && savings != null) {
                    Text(stringResource(R.string.hosted_save_percent, savings), fontSize = 11.sp, color = AppColors.Calorie)
                }
            }
        }
    }
}

@Composable
private fun PlanCard(option: PlanOption, isSelected: Boolean, isCurrent: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val perUnit = stringResource(if (option.period == BillingPeriod.MONTHLY) R.string.hosted_per_month else R.string.hosted_per_year)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSelected) 0.07f else 0.03f))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                shape = shape
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(planName(option.plan), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.hosted_current_plan),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.Calorie,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.Calorie.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                stringResource(R.string.hosted_plan_actions, option.plan.dailyLimit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        // The billed amount is the most prominent price (Play subscriptions policy).
        Column(horizontalAlignment = Alignment.End) {
            Text(option.product.price.formatted, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(perUnit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun IncludedFeatures() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.hosted_included_title), fontWeight = FontWeight.SemiBold)
        FeatureRow(Icons.Filled.AutoAwesome, R.string.hosted_feature_all_title, R.string.hosted_feature_all_body)
        FeatureRow(Icons.Filled.CalendarToday, R.string.hosted_feature_daily_title, R.string.hosted_feature_daily_body)
        FeatureRow(Icons.Filled.Lock, R.string.hosted_feature_private_title, R.string.hosted_feature_private_body)
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, titleRes: Int, bodyRes: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(titleRes), fontWeight = FontWeight.Medium)
            Text(
                stringResource(bodyRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }
}

@Composable
private fun PlansLoading() {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.hosted_plans_loading))
    }
}

@Composable
private fun PlansUnavailable(onRetry: () -> Unit) {
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.hosted_plans_unavailable_title), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.hosted_plans_unavailable_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                textAlign = TextAlign.Center
            )
            FudGlassTextButton(text = stringResource(R.string.hosted_try_again), onClick = onRetry)
        }
    }
}

@Composable
private fun PurchaseBar(
    option: PlanOption,
    change: PlanChange,
    purchasing: Boolean,
    restoring: Boolean,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val perUnit = stringResource(if (option.period == BillingPeriod.MONTHLY) R.string.hosted_per_month else R.string.hosted_per_year)
    val title = when (change) {
        PlanChange.NEW -> stringResource(R.string.hosted_cta_subscribe, planName(option.plan))
        PlanChange.UPGRADE -> stringResource(R.string.hosted_cta_upgrade)
        PlanChange.SWITCH -> stringResource(R.string.hosted_cta_switch)
        PlanChange.CURRENT -> stringResource(R.string.hosted_cta_current)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FudGlassPrimaryButton(
            text = title,
            onClick = onPurchase,
            enabled = change != PlanChange.CURRENT && !purchasing && !restoring
        ) {
            if (purchasing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
        val note = when (change) {
            PlanChange.UPGRADE -> stringResource(R.string.hosted_upgrade_note)
            PlanChange.SWITCH -> stringResource(R.string.hosted_switch_note)
            else -> null
        }
        note?.let { SmallCenteredText(it) }
        SmallCenteredText(stringResource(R.string.hosted_disclosure, option.product.price.formatted, perUnit))
        SmallCenteredText(stringResource(R.string.hosted_optional_note))
        LegalLinks(restoring = restoring, onRestore = onRestore, onOpenUrl = onOpenUrl, padded = false)
    }
}

@Composable
private fun SmallCenteredText(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LegalLinks(restoring: Boolean, onRestore: () -> Unit, onOpenUrl: (String) -> Unit, padded: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = if (padded) 12.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (restoring) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
        } else {
            LinkText(stringResource(R.string.hosted_restore), onRestore)
        }
        Dot()
        LinkText(stringResource(R.string.hosted_terms)) { onOpenUrl(HostedAiConstants.TERMS_URL) }
        Dot()
        LinkText(stringResource(R.string.hosted_privacy)) { onOpenUrl(HostedAiConstants.PRIVACY_URL) }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        color = AppColors.Calorie,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun Dot() {
    Text("·", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
}

@Composable
private fun MessageDialog(message: HostedMessage, onOk: () -> Unit) {
    FudGlassDialog(onDismissRequest = onOk) {
        message.title?.let { Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        Text(message.body, style = MaterialTheme.typography.bodyMedium)
        FudGlassDialogActions(primaryText = stringResource(R.string.action_ok), onPrimary = onOk)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostedCreditsSheet(
    container: AppContainer,
    onDismiss: () -> Unit,
    onNeedPlan: () -> Unit
) {
    val billing = container.billing
    val entitlement by billing.entitlement.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var products by remember { mutableStateOf<Map<String, StoreProduct>?>(null) }
    var purchasingId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<HostedMessage?>(null) }

    // Credits are only spendable while a plan is active, so they are only sold then.
    LaunchedEffect(entitlement.loaded, entitlement.isEntitled) {
        if (entitlement.loaded && !entitlement.isEntitled) onNeedPlan()
    }
    LaunchedEffect(Unit) {
        products = billing.loadProducts(HostedAiConstants.CREDIT_PACKS.map { it.productId }).associateBy { it.id }
    }

    val addedText = stringResource(R.string.hosted_credits_added)
    val creditsPendingText = stringResource(R.string.hosted_credits_pending)
    val pendingText = stringResource(R.string.hosted_purchase_pending)
    val failedText = stringResource(R.string.hosted_purchase_failed)

    fun buy(product: StoreProduct) {
        val activity = context.findActivity() ?: return
        if (purchasingId != null) return
        purchasingId = product.id
        scope.launch {
            val before = container.hostedQuota.snapshot.value?.creditBank ?: 0
            val outcome = billing.purchaseProduct(activity, product)
            message = when (outcome) {
                PurchaseOutcome.Success -> {
                    // The Worker grants credits from RevenueCat; confirm before saying so.
                    val after = container.hostedQuota.refresh(force = true).getOrNull()?.creditBank
                    HostedMessage(null, if (after != null && after > before) addedText else creditsPendingText, closeOnOk = true)
                }
                PurchaseOutcome.Pending -> HostedMessage(null, pendingText, closeOnOk = true)
                PurchaseOutcome.Failed, PurchaseOutcome.AlreadyOwned -> HostedMessage(null, failedText, closeOnOk = false)
                PurchaseOutcome.Cancelled, PurchaseOutcome.Busy -> null
            }
            purchasingId = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetContainerColor()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.hosted_credits_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.hosted_credits_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            val loaded = products
            when {
                loaded == null -> PlansLoading()
                loaded.isEmpty() -> Text(stringResource(R.string.hosted_credits_unavailable))
                else -> FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 0.dp) {
                    Column {
                        HostedAiConstants.CREDIT_PACKS.forEach { pack ->
                            val product = loaded[pack.productId] ?: return@forEach
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = purchasingId == null) { buy(product) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.hosted_credits_amount, pack.credits), Modifier.weight(1f), fontSize = 17.sp)
                                if (purchasingId == pack.productId) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
                                } else {
                                    Text(product.price.formatted, color = AppColors.Calorie, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            FudGlassTextButton(text = stringResource(R.string.action_done), onClick = onDismiss, modifier = Modifier.align(Alignment.End))
        }
    }

    message?.let { current ->
        MessageDialog(current) {
            message = null
            if (current.closeOnOk) onDismiss()
        }
    }
}

@Composable
fun HostedOutOfQuotaDialog(onBuyOrUpgrade: () -> Unit, onSwitchToByok: () -> Unit, onClose: () -> Unit) {
    FudGlassDialog(onDismissRequest = onClose) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.HourglassEmpty, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(36.dp))
        }
        Text(
            stringResource(R.string.hosted_out_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            stringResource(R.string.hosted_out_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        FudGlassPrimaryButton(text = stringResource(R.string.hosted_out_buy), onClick = onBuyOrUpgrade)
        FudGlassTextButton(text = stringResource(R.string.hosted_out_byok), onClick = onSwitchToByok, modifier = Modifier.fillMaxWidth())
        FudGlassTextButton(
            text = stringResource(R.string.hosted_close),
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
        )
    }
}
