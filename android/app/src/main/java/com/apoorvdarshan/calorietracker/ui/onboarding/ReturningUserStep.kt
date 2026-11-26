package com.apoorvdarshan.calorietracker.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.MainActivity
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.health.HealthConnectAvailability
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.hosted.findActivity
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Google Drive's blue into its green, for the restore tile. */
private val DriveTileGradient = listOf(Color(0xFF4285F4), Color(0xFF34A853))

/** Health Connect's pink into red, for the import tile. */
private val HealthTileGradient = listOf(Color(0xFFFF6F91), Color(0xFFE5304F))

/** False when the system turned animations off (Developer options or accessibility). */
internal fun animationsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/**
 * Welcome back: pick a Google Drive restore, a Health Connect import, both, or neither.
 * Continue runs the picked ones in order with a step list; Start fresh skips both. Google
 * and Health Connect sheets open only after Continue.
 */
@Composable
internal fun ReturningUserStep(container: AppContainer, vm: OnboardingViewModel, state: ReturningUserState) {
    val context = LocalContext.current
    val animate = remember(context) { animationsEnabled(context) }

    BackHandler {
        when {
            state.confirmingRestore -> vm.cancelDriveRestore()
            else -> vm.closeReturningUser()
        }
    }

    // A finished restore relaunches the task, so the start destination and every
    // Activity-scoped ViewModel load the restored data (recreate() would keep them).
    LaunchedEffect(state.restartRequested) {
        if (!state.restartRequested) return@LaunchedEffect
        val activity = context.findActivity() ?: return@LaunchedEffect
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }

    // Google Drive: the same account flow Settings uses. The ViewModel authorizes; the
    // launchers live here because they need the Activity's result registry.
    val driveConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val activity = context.findActivity()
        when {
            result.resultCode == Activity.RESULT_CANCELED -> vm.driveSignInCancelled()
            result.resultCode != Activity.RESULT_OK || activity == null -> vm.driveSignInFailed()
            else -> vm.finishDriveAuthorization(activity, result.data)
        }
    }
    LaunchedEffect(state.driveConsent) {
        val consent = state.driveConsent ?: return@LaunchedEffect
        vm.driveConsentLaunched()
        runCatching { driveConsentLauncher.launch(IntentSenderRequest.Builder(consent).build()) }
            .onFailure { vm.driveSignInFailed() }
    }
    val accountPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val account = if (result.resultCode == Activity.RESULT_OK) {
            container.cloudBackup.accountFromPickerResult(result.data)
        } else {
            null
        }
        val activity = context.findActivity()
        when {
            result.resultCode == Activity.RESULT_CANCELED -> vm.driveSignInCancelled()
            account == null || activity == null -> vm.driveSignInFailed()
            else -> vm.authorizeDrive(activity, account)
        }
    }

    // Health Connect: availability is re-read on resume, since installing or updating it
    // from the Play Store happens outside the app.
    var healthAvailability by remember { mutableStateOf(container.health.availability()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) healthAvailability = container.health.availability()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val healthLauncher = rememberLauncherForActivityResult(
        container.health.permissionRequestContract()
    ) { granted ->
        // A partial grant is a valid connection; every read checks its own permission.
        vm.importFromHealthConnect(granted.any { it in container.health.permissions })
    }
    LaunchedEffect(state.healthPermissionRequested) {
        if (!state.healthPermissionRequested) return@LaunchedEffect
        vm.healthPermissionLaunched()
        if (container.health.availability() != HealthConnectAvailability.AVAILABLE) {
            vm.healthUnavailable()
        } else {
            runCatching { healthLauncher.launch(container.health.connectPermissions) }
                .onFailure { vm.healthUnavailable() }
        }
    }

    val onContinue: () -> Unit = {
        if (vm.startReturningRun() == ReturningSource.DRIVE) {
            runCatching { accountPickerLauncher.launch(container.cloudBackup.accountPickerIntent()) }
                .onFailure { vm.driveSignInFailed() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .testTag("onboarding.RETURNING")
    ) {
        // Gone while working, and after a restore (the welcome's Get Started would run the
        // whole setup over the restored data).
        if (!state.busy && !state.restoredBackup) {
            IconButton(
                onClick = { vm.closeReturningUser() },
                // Line the chevron up with the content edge while keeping the 48dp target.
                modifier = Modifier.offset(x = (-12).dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChevronLeft,
                    contentDescription = stringResource(R.string.onboarding_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(28.dp)
                )
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The same logo, size and headline as the plain welcome.
            Image(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = stringResource(R.string.onboarding_logo_description),
                modifier = Modifier.size(120.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.returning_title),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.returning_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            if (state.phase == ReturningPhase.SELECTING) {
                Text(
                    stringResource(R.string.returning_pick_hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReturningSourceCard(
                        icon = Icons.Outlined.CloudDownload,
                        tile = DriveTileGradient,
                        title = stringResource(R.string.returning_drive_title),
                        detail = stringResource(R.string.returning_drive_detail),
                        note = stringResource(R.string.returning_drive_note),
                        selected = ReturningSource.DRIVE in state.selected,
                        enabled = true,
                        animate = animate,
                        onToggle = { vm.toggleReturningSource(ReturningSource.DRIVE) }
                    )
                    ReturningSourceCard(
                        icon = Icons.Filled.Favorite,
                        tile = HealthTileGradient,
                        title = stringResource(R.string.returning_health_title),
                        detail = stringResource(R.string.returning_health_detail),
                        note = when (healthAvailability) {
                            HealthConnectAvailability.AVAILABLE -> null
                            HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED -> stringResource(R.string.returning_health_update)
                            else -> stringResource(R.string.onboarding_health_unavailable)
                        },
                        selected = ReturningSource.HEALTH in state.selected,
                        enabled = healthAvailability == HealthConnectAvailability.AVAILABLE,
                        animate = animate,
                        onToggle = { vm.toggleReturningSource(ReturningSource.HEALTH) },
                        // Tapping it opens the Play Store instead of picking an unusable source.
                        onDisabledClick = if (healthAvailability == HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED) {
                            { container.health.openPlayStore() }
                        } else {
                            null
                        }
                    )
                }
            } else {
                ReturningStepList(state = state, animate = animate)
            }
            Spacer(Modifier.height(24.dp))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.phase) {
                ReturningPhase.SELECTING -> {
                    GradientButton(
                        text = stringResource(R.string.action_continue),
                        enabled = state.canContinue,
                        onClick = onContinue
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = vm::startFresh, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            stringResource(R.string.returning_start_fresh),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                ReturningPhase.RUNNING -> Spacer(Modifier.height(56.dp + 4.dp + 48.dp))
                ReturningPhase.FINISHED -> {
                    GradientButton(
                        text = stringResource(
                            if (state.restoredSetup) R.string.action_continue else R.string.returning_continue_setup
                        ),
                        enabled = !state.restartRequested,
                        onClick = vm::finishReturning
                    )
                    Spacer(Modifier.height(4.dp))
                    if (state.anyStepUnfinished) {
                        TextButton(onClick = vm::retryReturning, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(
                                stringResource(R.string.action_retry),
                                color = AppColors.Calorie,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Spacer(Modifier.height(48.dp))
                    }
                }
            }
        }
    }

    if (state.confirmingRestore) {
        val locale = LocalConfiguration.current.locales[0]
        val backupDate = state.backupModifiedAt?.let {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale)
                .withZone(ZoneId.systemDefault())
                .format(it)
        }
        FudGlassDialog(onDismissRequest = vm::cancelDriveRestore) {
            Text(
                stringResource(R.string.returning_drive_confirm_title),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (backupDate != null) stringResource(R.string.returning_drive_confirm_message_dated, backupDate)
                else stringResource(R.string.returning_drive_confirm_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.cloud_backup_restore),
                onPrimary = vm::restoreDriveBackup,
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = vm::cancelDriveRestore
            )
        }
    }
}

@Composable
private fun ReturningSourceCard(
    icon: ImageVector,
    tile: List<Color>,
    title: String,
    detail: String,
    note: String?,
    selected: Boolean,
    enabled: Boolean,
    animate: Boolean,
    onToggle: () -> Unit,
    onDisabledClick: (() -> Unit)? = null
) {
    // The onboarding card surface and outline; picked cards get a solid accent border.
    val shape = RoundedCornerShape(20.dp)
    val border = if (selected) {
        Modifier.border(BorderStroke(2.dp, AppColors.Calorie), shape)
    } else {
        Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)), shape)
    }
    val interaction = when {
        enabled -> Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
        onDisabledClick != null -> Modifier.clickable(role = Role.Button, onClick = onDisabledClick)
        else -> Modifier
    }
    val contentAlpha = if (enabled) 1f else 0.5f
    FudGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(border)
            .then(interaction),
        cornerRadius = 20.dp,
        padding = 16.dp
    ) {
        Row(verticalAlignment = Alignment.Top) {
            GradientTile(icon = icon, colors = tile, alpha = contentAlpha)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = contentAlpha)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f * contentAlpha)
                )
                note?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            if (enabled) SelectionCheck(selected = selected, animate = animate)
        }
    }
}

@Composable
private fun GradientTile(icon: ImageVector, colors: List<Color>, alpha: Float, size: Dp = 56.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(colors.map { it.copy(alpha = it.alpha * alpha) })),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** The card's check: a solid accent circle that scales in when picked (instantly with
 *  animations off). */
@Composable
private fun SelectionCheck(selected: Boolean, animate: Boolean) {
    val pop = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) {
        val target = if (selected) 1f else 0f
        if (animate) {
            pop.animateTo(target, spring(stiffness = Spring.StiffnessMedium))
        } else {
            pop.snapTo(target)
        }
    }
    Box(
        Modifier
            .padding(top = 2.dp)
            .size(26.dp)
            .border(1.5.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (pop.value > 0f) {
            Box(
                Modifier
                    .size(26.dp)
                    .scale(pop.value)
                    .clip(CircleShape)
                    .background(AppColors.Calorie),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** One row per picked source: waiting, working, then how it went. */
@Composable
private fun ReturningStepList(state: ReturningUserState, animate: Boolean) {
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for (step in state.steps) {
                val title = stringResource(
                    when (step.source) {
                        ReturningSource.DRIVE -> R.string.returning_drive_title
                        ReturningSource.HEALTH -> R.string.returning_health_title
                    }
                )
                val detail = when (step.status) {
                    ReturningStepStatus.WAITING -> stringResource(R.string.returning_step_waiting)
                    ReturningStepStatus.WORKING -> stringResource(
                        when {
                            step.source == ReturningSource.HEALTH -> R.string.returning_health_importing
                            step.restoring -> R.string.returning_drive_restoring
                            else -> R.string.returning_drive_checking
                        }
                    )
                    ReturningStepStatus.DONE -> step.message?.let { returningMessageText(it) } ?: when (step.source) {
                        ReturningSource.DRIVE -> stringResource(R.string.returning_step_drive_done)
                        ReturningSource.HEALTH -> healthSummaryText(state)
                    }
                    ReturningStepStatus.FAILED, ReturningStepStatus.SKIPPED ->
                        step.message?.let { returningMessageText(it) } ?: stringResource(R.string.returning_step_failed)
                }
                ReturningStepRow(
                    tile = when (step.source) {
                        ReturningSource.DRIVE -> DriveTileGradient
                        ReturningSource.HEALTH -> HealthTileGradient
                    },
                    icon = when (step.source) {
                        ReturningSource.DRIVE -> Icons.Outlined.CloudDownload
                        ReturningSource.HEALTH -> Icons.Filled.Favorite
                    },
                    title = title,
                    detail = detail,
                    status = step.status,
                    animate = animate
                )
            }
        }
    }
}

@Composable
private fun ReturningStepRow(
    tile: List<Color>,
    icon: ImageVector,
    title: String,
    detail: String,
    status: ReturningStepStatus,
    animate: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GradientTile(icon = icon, colors = tile, alpha = if (status == ReturningStepStatus.WAITING) 0.45f else 1f, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = when (status) {
                    ReturningStepStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
                }
            )
        }
        Spacer(Modifier.width(12.dp))
        StepStatusIndicator(status = status, animate = animate)
    }
}

@Composable
private fun StepStatusIndicator(status: ReturningStepStatus, animate: Boolean) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(status) {
        if (status == ReturningStepStatus.DONE || status == ReturningStepStatus.FAILED || status == ReturningStepStatus.SKIPPED) {
            if (animate) {
                pop.snapTo(0.6f)
                pop.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
            } else {
                pop.snapTo(1f)
            }
        }
    }
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when (status) {
            ReturningStepStatus.WAITING -> Box(
                Modifier
                    .size(24.dp)
                    .border(1.5.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f), CircleShape)
            )
            ReturningStepStatus.WORKING -> CircularProgressIndicator(
                Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = AppColors.Calorie
            )
            ReturningStepStatus.DONE -> StatusDot(
                pop = pop.value,
                background = AppColors.Calorie,
                icon = Icons.Filled.Check,
                description = stringResource(R.string.action_done)
            )
            ReturningStepStatus.FAILED -> StatusDot(
                pop = pop.value,
                background = MaterialTheme.colorScheme.error,
                icon = Icons.Filled.PriorityHigh,
                description = stringResource(R.string.returning_step_failed)
            )
            ReturningStepStatus.SKIPPED -> StatusDot(
                pop = pop.value,
                background = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                icon = Icons.Filled.Remove,
                description = null
            )
        }
    }
}

@Composable
private fun StatusDot(pop: Float, background: Color, icon: ImageVector, description: String?) {
    Box(
        Modifier
            .size(24.dp)
            .scale(pop)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** Onboarding's calorie-gradient Get Started capsule; disabled, it uses the same faded fill
 *  as the Health Connect step's unavailable button. */
@Composable
private fun GradientButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val disabled = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) Brush.horizontalGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd))
                else Brush.horizontalGradient(listOf(disabled, disabled))
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun healthSummaryText(state: ReturningUserState): String {
    val added = state.healthAdded ?: HealthRecordCounts()
    val meals = pluralStringResource(R.plurals.returning_health_meals, added.meals, added.meals)
    val weighIns = pluralStringResource(R.plurals.returning_health_weigh_ins, added.weighIns, added.weighIns)
    return when (healthImportSummary(added)) {
        HealthImportSummary.MEALS_AND_WEIGH_INS -> stringResource(R.string.returning_health_imported_both, meals, weighIns)
        HealthImportSummary.MEALS -> stringResource(R.string.returning_health_imported_one, meals)
        HealthImportSummary.WEIGH_INS -> stringResource(R.string.returning_health_imported_one, weighIns)
        HealthImportSummary.OTHER_DATA -> stringResource(R.string.returning_health_imported_other)
        HealthImportSummary.NOTHING -> stringResource(
            if (state.restoredBackup) R.string.returning_health_nothing_new else R.string.returning_health_nothing
        )
    }
}

@Composable
private fun returningMessageText(message: ReturningMessage): String = stringResource(
    when (message) {
        ReturningMessage.SIGN_IN_FAILED -> R.string.cloud_backup_sign_in_failed
        ReturningMessage.DRIVE_CANCELLED -> R.string.returning_step_drive_cancelled
        ReturningMessage.NO_BACKUP -> R.string.returning_drive_no_backup
        ReturningMessage.DRIVE_UNREACHABLE -> R.string.returning_drive_unreachable
        ReturningMessage.NO_SPACE -> R.string.cloud_backup_restore_no_space
        ReturningMessage.NOT_A_BACKUP -> R.string.returning_drive_not_a_backup
        ReturningMessage.NEEDS_NEWER_APP -> R.string.returning_drive_needs_update
        ReturningMessage.SETUP_MISSING -> R.string.returning_drive_setup_missing
        ReturningMessage.HEALTH_DENIED -> R.string.returning_health_denied
        ReturningMessage.HEALTH_UNAVAILABLE -> R.string.onboarding_health_unavailable
        ReturningMessage.HEALTH_READ_FAILED -> R.string.returning_health_read_failed
    }
)
