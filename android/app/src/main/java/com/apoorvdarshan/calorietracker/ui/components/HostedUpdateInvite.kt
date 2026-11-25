package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.hosted.HostedPrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** The only post-update popup in 9.0, even for users skipping earlier versions. */
@Composable
fun HostedUpdateInviteHost(container: AppContainer, enabled: Boolean, canPresent: () -> Boolean = { true }) {
    val prefs = container.prefs
    val canPresentNow by rememberUpdatedState(canPresent)
    var showInvite by remember { mutableStateOf(false) }

    fun isSafeToPresent() =
        canPresentNow() && !container.voiceAssistant.isActive && container.hostedUi.prompt.value == null

    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        delay(2_500)
        if (!isSafeToPresent() || !prefs.hasCompletedOnboarding.first() ||
            prefs.hasSeenHostedUpsellPrompt.first()
        ) return@LaunchedEffect

        val billing = container.billing
        // Check the subscription, not the saved AI mode: an expired subscriber can still have Hosted selected.
        // Neither a failed entitlement refresh nor an empty catalog consumes the invite.
        if (!billing.isAvailable || !billing.refreshCustomerInfo()) return@LaunchedEffect
        if (billing.entitlement.value.isEntitled) {
            prefs.setHasSeenHostedUpsellPrompt(true)
            return@LaunchedEffect
        }
        billing.loadOfferings()
        if (!billing.hasPurchasablePlans || billing.entitlement.value.isEntitled || !isSafeToPresent()
        ) return@LaunchedEffect

        prefs.setHasSeenHostedUpsellPrompt(true)
        showInvite = true
    }

    if (showInvite) {
        FudGlassDialog(onDismissRequest = { showInvite = false }) {
            Text(
                text = stringResource(R.string.hosted_upsell_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.hosted_upsell_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.hosted_upsell_cta),
                onPrimary = {
                    showInvite = false
                    container.hostedUi.show(HostedPrompt.Paywall)
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { showInvite = false }
            )
        }
    }
}
