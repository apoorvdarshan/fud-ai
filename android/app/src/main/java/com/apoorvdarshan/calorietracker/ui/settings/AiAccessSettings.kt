package com.apoorvdarshan.calorietracker.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.hosted.AiAccessMode
import com.apoorvdarshan.calorietracker.services.hosted.HostedAiConstants
import com.apoorvdarshan.calorietracker.services.hosted.HostedPlan
import com.apoorvdarshan.calorietracker.services.hosted.HostedPrompt
import com.apoorvdarshan.calorietracker.services.hosted.RestoreOutcome
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.launch

/** Settings → AI Access: BYOK vs Hosted (Plus/Pro), plan and usage, purchases. */
@Composable
internal fun AiAccessSettings(container: AppContainer) {
    val billing = container.billing
    val router = container.hostedUi
    val mode by container.prefs.aiAccessMode.collectAsState(initial = AiAccessMode.BYOK)
    val entitlement by billing.entitlement.collectAsState()
    val snapshot by container.hostedQuota.snapshot.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        billing.refreshCustomerInfo()
        billing.loadOfferings()
    }
    LaunchedEffect(entitlement.isEntitled) {
        if (entitlement.isEntitled) container.hostedQuota.refresh()
    }

    fun selectMode(target: AiAccessMode) {
        if (target == mode) return
        if (target == AiAccessMode.HOSTED && !entitlement.isEntitled) {
            // Hosted needs a plan first; the paywall switches the mode after a purchase.
            router.show(HostedPrompt.Paywall)
            return
        }
        scope.launch { container.prefs.setAiAccessMode(target) }
    }

    val restoredText = stringResource(R.string.hosted_restore_success)
    val noneText = stringResource(R.string.hosted_restore_none)
    val failedText = stringResource(R.string.hosted_restore_failed)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard(title = stringResource(R.string.hosted_mode_title)) {
            ModeOption(stringResource(R.string.hosted_mode_byok), mode == AiAccessMode.BYOK) { selectMode(AiAccessMode.BYOK) }
            HorizontalDivider()
            ModeOption(stringResource(R.string.hosted_mode_hosted), mode == AiAccessMode.HOSTED) { selectMode(AiAccessMode.HOSTED) }
        }
        Footer(stringResource(R.string.hosted_mode_footer))

        if (mode == AiAccessMode.HOSTED) {
            val plan = entitlement.plan
            val today = container.hostedQuota.snapshotFor(plan)
            SectionCard {
                InfoRow(stringResource(R.string.hosted_plan_label), planLabel(plan))
                if (plan != HostedPlan.NONE) {
                    HorizontalDivider()
                    InfoRow(
                        stringResource(R.string.hosted_today_label),
                        stringResource(R.string.hosted_today_value, today.dailyUsed, today.dailyLimit)
                    )
                }
                HorizontalDivider()
                InfoRow(stringResource(R.string.hosted_credits_label), (snapshot?.creditBank ?: 0).toString())
            }
            Footer(stringResource(R.string.hosted_usage_footer))
        }

        SectionCard {
            SettingRow(
                stringResource(if (entitlement.isEntitled) R.string.hosted_change_plan else R.string.hosted_view_plans),
                "",
                icon = Icons.Outlined.WorkspacePremium
            ) { router.show(HostedPrompt.Paywall) }
            if (entitlement.isEntitled) {
                HorizontalDivider()
                SettingRow(stringResource(R.string.hosted_buy_credits), "", icon = Icons.Outlined.AddShoppingCart) {
                    router.show(HostedPrompt.Credits)
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.hosted_manage_subscription), "", icon = Icons.Outlined.ManageAccounts) {
                    val url = entitlement.managementUrl
                        ?: HostedAiConstants.manageSubscriptionsUrl(entitlement.subscriptionId)
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }
            HorizontalDivider()
            if (restoring) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
                }
            } else {
                SettingRow(stringResource(R.string.hosted_restore), "", icon = Icons.Outlined.Restore) {
                    restoring = true
                    scope.launch {
                        restoreMessage = when (billing.restore()) {
                            RestoreOutcome.Restored -> restoredText
                            RestoreOutcome.NothingToRestore -> noneText
                            RestoreOutcome.Failed -> failedText
                        }
                        restoring = false
                    }
                }
            }
        }
    }

    restoreMessage?.let { text ->
        FudGlassDialog(onDismissRequest = { restoreMessage = null }) {
            Text(stringResource(R.string.hosted_restore), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            FudGlassDialogActions(primaryText = stringResource(R.string.action_ok), onPrimary = { restoreMessage = null })
        }
    }
}

@Composable
private fun planLabel(plan: HostedPlan): String = stringResource(
    when (plan) {
        HostedPlan.NONE -> R.string.hosted_plan_none
        HostedPlan.PLUS -> R.string.hosted_plan_plus
        HostedPlan.PRO -> R.string.hosted_plan_pro
    }
)

@Composable
private fun ModeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = AppColors.Calorie)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 10.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}
