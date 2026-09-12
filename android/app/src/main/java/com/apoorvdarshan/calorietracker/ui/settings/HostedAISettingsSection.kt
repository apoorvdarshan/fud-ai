package com.apoorvdarshan.calorietracker.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.billing.AIMode
import com.apoorvdarshan.calorietracker.billing.HostedAIConstants
import kotlinx.coroutines.launch

@Composable
fun HostedAISettingsSection(container: AppContainer) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val aiMode by container.prefs.aiAccessMode.collectAsState(initial = AIMode.BYOK)
    val plan by container.revenueCat.activePlan.collectAsState()
    val entitled by container.revenueCat.hasHostedEntitlement.collectAsState()
    var showPaywall by remember { mutableStateOf(false) }
    var dailyUsed by remember { mutableIntStateOf(0) }
    var dailyLimit by remember { mutableIntStateOf(0) }
    var creditBank by remember { mutableIntStateOf(0) }

    LaunchedEffect(plan, entitled) {
        container.revenueCat.refreshCustomerInfo()
        container.revenueCat.loadOfferings()
        val snap = container.hostedQuotaManager.snapshot(plan)
        dailyUsed = snap.dailyUsed
        dailyLimit = snap.dailyLimit
        creditBank = snap.creditBank
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(stringResource(R.string.settings_ai_mode), style = MaterialTheme.typography.titleSmall)
        AIMode.entries.forEach { mode ->
            TextButton(onClick = {
                scope.launch {
                    if (mode == AIMode.HOSTED && !entitled) {
                        showPaywall = true
                    } else {
                        container.prefs.setAiAccessMode(mode)
                    }
                }
            }) {
                RadioButton(selected = aiMode == mode, onClick = null)
                Text(
                    when (mode) {
                        AIMode.BYOK -> stringResource(R.string.settings_ai_mode_byok)
                        AIMode.HOSTED -> stringResource(R.string.settings_ai_mode_hosted)
                    }
                )
            }
        }

        if (aiMode == AIMode.HOSTED) {
            Text(stringResource(R.string.settings_hosted_plan, plan.name))
            Text(stringResource(R.string.settings_hosted_today, dailyUsed, dailyLimit))
            Text(stringResource(R.string.settings_hosted_credits, creditBank))
            TextButton(onClick = { showPaywall = true }) {
                Text(stringResource(R.string.settings_hosted_subscribe))
            }
            TextButton(onClick = { showPaywall = true }, enabled = entitled) {
                Text(stringResource(R.string.settings_hosted_buy_credits))
            }
            TextButton(onClick = {
                scope.launch {
                    runCatching { container.revenueCat.restorePurchases() }
                    val snap = container.hostedQuotaManager.snapshot(container.revenueCat.activePlan.value)
                    dailyUsed = snap.dailyUsed
                    dailyLimit = snap.dailyLimit
                    creditBank = snap.creditBank
                }
            }) {
                Text(stringResource(R.string.settings_restore_purchases))
            }
        }
    }

    if (showPaywall && activity != null) {
        AlertDialog(
            onDismissRequest = { showPaywall = false },
            title = { Text(stringResource(R.string.settings_hosted_subscribe)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.settings_hosted_paywall_body,
                            HostedAIConstants.PLUS_DAILY_LIMIT,
                            HostedAIConstants.PRO_DAILY_LIMIT
                        )
                    )
                    container.revenueCat.offerings.value?.current?.availablePackages?.forEach { pkg ->
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    container.revenueCat.purchasePackage(activity, pkg)
                                    container.prefs.setAiAccessMode(AIMode.HOSTED)
                                    showPaywall = false
                                }
                            }
                        }) {
                            Text("${pkg.product.title} — ${pkg.product.price.formatted}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPaywall = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
