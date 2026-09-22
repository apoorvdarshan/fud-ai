package com.apoorvdarshan.calorietracker.ui.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FudAILinks
import com.apoorvdarshan.calorietracker.services.ProductHuntLaunchReminder
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One-time prompts for *existing* users the first time they open the app after
 * updating. Android shows Meet the developer, then a Product Hunt vote sheet
 * during the launch day, then arms the launch reminder. Hosted Plus/Pro upsell
 * stays iOS-only (billing is iPhone-first).
 * Fresh installs never qualify — onboarding marks prompts as seen
 * (see PreferencesStore.setOnboardingCompleted).
 */
@Composable
fun PostUpdatePromptsHost(container: AppContainer, enabled: Boolean) {
    val prefs = container.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMeetDeveloper by remember { mutableStateOf(false) }
    var showProductHuntLaunch by remember { mutableStateOf(false) }

    suspend fun armProductHuntReminder(): Boolean =
        container.notifications.scheduleProductHuntLaunchReminderIfNeeded(prefs)

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            if (granted) {
                armProductHuntReminder()
            } else {
                // Ask once: denying means we stop retrying the PH reminder on every launch.
                prefs.setProductHuntLaunchNotificationScheduled(true)
            }
        }
    }

    suspend fun finishFlow() {
        val needsPermission = armProductHuntReminder()
        if (needsPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    suspend fun continueToProductHuntLaunch() {
        if (prefs.hasSeenProductHuntLaunchPrompt.first() ||
            ProductHuntLaunchReminder.plan(System.currentTimeMillis()) != ProductHuntLaunchReminder.Plan.FireNow
        ) {
            finishFlow()
            return
        }
        delay(400)
        showProductHuntLaunch = true
    }

    suspend fun continueToMeetDeveloper(delayMillis: Long) {
        if (prefs.hasSeenMeetDeveloperPrompt.first()) {
            continueToProductHuntLaunch()
            return
        }
        delay(delayMillis)
        // Mark seen only on Done so opening Instagram/X and returning keeps the dialog.
        showMeetDeveloper = true
    }

    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        // Let the first frame settle before interrupting.
        delay(2_500)
        if (!prefs.hasCompletedOnboarding.first()) return@LaunchedEffect
        // Hosted Plus/Pro billing is iPhone-first — don't show a paywall-style upsell on
        // Android (every user is BYOK today). Mark it seen so we never replay it later.
        if (!prefs.hasSeenHostedUpsellPrompt.first()) {
            prefs.setHasSeenHostedUpsellPrompt(true)
        }
        continueToMeetDeveloper(delayMillis = 0)
    }

    if (showMeetDeveloper) {
        MeetDeveloperDialog(
            onDone = {
                showMeetDeveloper = false
                scope.launch {
                    prefs.setHasSeenMeetDeveloperPrompt(true)
                    continueToProductHuntLaunch()
                }
            }
        )
    }
    if (showProductHuntLaunch) {
        ProductHuntLaunchDialog(
            onVote = {
                showProductHuntLaunch = false
                scope.launch {
                    prefs.setHasSeenProductHuntLaunchPrompt(true)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FudAILinks.PRODUCT_HUNT)))
                    finishFlow()
                }
            },
            onNotNow = {
                showProductHuntLaunch = false
                scope.launch {
                    prefs.setHasSeenProductHuntLaunchPrompt(true)
                    finishFlow()
                }
            }
        )
    }
}

@Composable
private fun ProductHuntLaunchDialog(onVote: () -> Unit, onNotNow: () -> Unit) {
    FudGlassDialog(onDismissRequest = {}) {
        Text(
            text = stringResource(R.string.post_update_product_hunt_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.post_update_product_hunt_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        FudGlassDialogActions(
            primaryText = stringResource(R.string.about_vote_ph),
            onPrimary = onVote,
            dismissText = stringResource(R.string.post_update_product_hunt_not_now),
            onDismiss = onNotNow
        )
    }
}

@Composable
private fun MeetDeveloperDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    // Ignore back / outside tap — only Done closes, so users can hop to Instagram and return.
    FudGlassDialog(onDismissRequest = {}) {
        Text(
            text = stringResource(R.string.post_update_meet_dev_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.post_update_meet_dev_greeting),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.post_update_meet_dev_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinkRow(Icons.Filled.AlternateEmail, stringResource(R.string.about_follow_x)) { open(FudAILinks.X) }
            LinkRow(Icons.Filled.CameraAlt, stringResource(R.string.about_follow_instagram)) { open(FudAILinks.INSTAGRAM) }
            LinkRow(Icons.Filled.Forum, stringResource(R.string.about_join_discord)) { open(FudAILinks.DISCORD) }
            LinkRow(Icons.Filled.ThumbUp, stringResource(R.string.about_vote_ph)) { open(FudAILinks.PRODUCT_HUNT) }
        }
        FudGlassDialogActions(
            primaryText = stringResource(R.string.action_done),
            onPrimary = onDone
        )
    }
}

@Composable
private fun LinkRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
