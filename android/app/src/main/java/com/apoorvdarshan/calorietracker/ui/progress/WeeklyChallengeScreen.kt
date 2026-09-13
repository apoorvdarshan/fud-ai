package com.apoorvdarshan.calorietracker.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeAggregate
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeCategory
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeLeaderboardRow
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeProfileValidator
import com.apoorvdarshan.calorietracker.models.WeeklyChallengePublicProfile
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeReportReason
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeReportValidator
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeSocialPlatform
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeWeek
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeeklyChallengeScreen(container: AppContainer) {
    val vm: WeeklyChallengeViewModel = viewModel(factory = WeeklyChallengeViewModel.Factory(container))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showJoin by remember { mutableStateOf(false) }
    var editProfile by remember { mutableStateOf<WeeklyChallengePublicProfile?>(null) }
    var showLeave by remember { mutableStateOf(false) }
    var showBlocked by remember { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<WeeklyChallengeLeaderboardRow?>(null) }
    var blockTarget by remember { mutableStateOf<WeeklyChallengeLeaderboardRow?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.setVisible(true)
                Lifecycle.Event.ON_STOP -> vm.setVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            vm.setVisible(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.setVisible(false)
        }
    }

    val viewer = ui.leaderboard?.viewer
        ?: ui.leaderboard?.rankings?.firstOrNull { it.isViewer }
    val viewerId = viewer?.participantId ?: ui.profile?.participantId
    val rankings = ui.leaderboard?.rankings.orEmpty()
        .asSequence()
        .distinctBy { it.participantId }
        .filterNot { it.participantId == viewerId }
        .filterNot { it.participantId in ui.blockedParticipants }
        .toList()

    PullToRefreshBox(
        isRefreshing = ui.isRefreshing,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = BottomNavScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ChallengeHeader(
                    weekStart = ui.weekStart,
                    lastUpdatedEpochMillis = ui.lastUpdatedEpochMillis,
                    isOffline = ui.isOffline
                )
            }

            ui.error?.let { error ->
                item {
                    ChallengeErrorCard(
                        error = error,
                        onRetry = vm::refresh,
                        onDismiss = vm::dismissError
                    )
                }
            }

            if (ui.pendingRemoteDeletion) {
                item {
                    PendingDeletionCard(
                        isBusy = ui.isMutating,
                        onRetry = vm::retryPendingDeletion
                    )
                }
            } else if (!ui.isJoined) {
                item {
                    ChallengeIntroduction(
                        onJoin = {
                            vm.dismissError()
                            showJoin = true
                        }
                    )
                }
            } else {
                item {
                    ChallengeCategorySelector(
                        selected = ui.category,
                        onSelect = vm::selectCategory
                    )
                }
                item { ChallengePointsExplanation() }
                item {
                    ViewerPositionCard(
                        profile = requireNotNull(ui.profile),
                        viewer = viewer,
                        aggregate = ui.aggregate,
                        category = ui.category,
                        isSaving = ui.isMutating,
                        onEdit = {
                            vm.dismissError()
                            editProfile = ui.profile
                        },
                        onLeave = { showLeave = true }
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.challenge_leaderboard_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showBlocked = true }) {
                            Text(stringResource(R.string.challenge_manage_blocked))
                        }
                    }
                }
                if (rankings.isEmpty()) {
                    item { ChallengeEmptyLeaderboard() }
                } else {
                    items(rankings, key = { it.participantId }) { row ->
                        LeaderboardRowCard(
                            row = row,
                            category = ui.category,
                            onReport = {
                                vm.dismissError()
                                reportTarget = row
                            },
                            onBlock = { blockTarget = row }
                        )
                    }
                }
            }
        }
    }

    if (showJoin) {
        ChallengeProfileDialog(
            existing = null,
            isBusy = ui.isMutating,
            error = ui.error,
            onDismiss = { showJoin = false },
            onSubmit = { name, platform, handle, acceptedRules, eligibilityAccepted ->
                vm.join(name, platform, handle, acceptedRules, eligibilityAccepted) {
                    showJoin = false
                }
            }
        )
    }
    editProfile?.let { profile ->
        ChallengeProfileDialog(
            existing = profile,
            isBusy = ui.isMutating,
            error = ui.error,
            onDismiss = { editProfile = null },
            onSubmit = { name, platform, handle, _, _ ->
                vm.updateProfile(name, platform, handle) { editProfile = null }
            }
        )
    }
    if (showLeave) {
        LeaveChallengeDialog(
            isBusy = ui.isMutating,
            onDismiss = { showLeave = false },
            onLeave = {
                vm.leaveAndDelete()
                showLeave = false
            }
        )
    }
    reportTarget?.let { row ->
        ReportParticipantDialog(
            row = row,
            isBusy = ui.isMutating,
            error = ui.error,
            onDismiss = { reportTarget = null },
            onReport = { reason, details ->
                vm.report(row, reason, details) { reportTarget = null }
            }
        )
    }
    blockTarget?.let { row ->
        BlockParticipantDialog(
            row = row,
            onDismiss = { blockTarget = null },
            onBlock = {
                vm.block(row)
                blockTarget = null
            }
        )
    }
    if (showBlocked) {
        ManageBlockedDialog(
            blocked = ui.blockedParticipants,
            onDismiss = { showBlocked = false },
            onUnblock = vm::unblock
        )
    }
    ui.reportConfirmationName?.let { name ->
        AlertDialog(
            onDismissRequest = vm::dismissReportConfirmation,
            title = { Text(stringResource(R.string.challenge_report_sent_title)) },
            text = { Text(stringResource(R.string.challenge_report_sent_message, name)) },
            confirmButton = {
                TextButton(onClick = vm::dismissReportConfirmation) {
                    Text(stringResource(R.string.action_done))
                }
            }
        )
    }
}

@Composable
private fun ChallengeHeader(
    weekStart: LocalDate,
    lastUpdatedEpochMillis: Long?,
    isOffline: Boolean
) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.challenge_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(
                R.string.challenge_week_range,
                weekStart.format(formatter),
                WeeklyChallengeWeek.endFor(weekStart).format(formatter)
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val status = when {
            isOffline -> stringResource(R.string.challenge_offline_status)
            lastUpdatedEpochMillis != null -> {
                val formatted = remember(lastUpdatedEpochMillis, locale) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
                        .format(Date(lastUpdatedEpochMillis))
                }
                stringResource(R.string.challenge_last_updated, formatted)
            }
            else -> stringResource(R.string.challenge_not_updated)
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = if (isOffline) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChallengeIntroduction(onJoin: () -> Unit) {
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.challenge_intro_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.challenge_intro_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.challenge_privacy_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.challenge_public_profile_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.challenge_points_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.challenge_points_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.challenge_join_action))
            }
        }
    }
}

@Composable
private fun ChallengePointsExplanation() {
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.challenge_points_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.challenge_points_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PendingDeletionCard(isBusy: Boolean, onRetry: () -> Unit) {
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.challenge_pending_delete_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.challenge_pending_delete_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onRetry, enabled = !isBusy) {
                Text(stringResource(R.string.challenge_retry_deletion))
            }
        }
    }
}

@Composable
private fun ChallengeCategorySelector(
    selected: WeeklyChallengeCategory,
    onSelect: (WeeklyChallengeCategory) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WeeklyChallengeCategory.entries.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                label = { Text(stringResource(category.labelRes())) }
            )
        }
    }
}

@Composable
private fun ViewerPositionCard(
    profile: WeeklyChallengePublicProfile,
    viewer: WeeklyChallengeLeaderboardRow?,
    aggregate: WeeklyChallengeAggregate,
    category: WeeklyChallengeCategory,
    isSaving: Boolean,
    onEdit: () -> Unit,
    onLeave: () -> Unit
) {
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.challenge_my_position),
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.Calorie,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    SocialHandle(profile.socialPlatform, profile.socialHandle)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = viewer?.rank?.let { stringResource(R.string.challenge_rank_format, it) }
                            ?: stringResource(R.string.challenge_unranked),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = scoreText(category, viewer, aggregate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider()
            AggregateBreakdown(viewer = viewer, aggregate = aggregate)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit, enabled = !isSaving) {
                    Text(stringResource(R.string.challenge_edit_profile))
                }
                TextButton(onClick = onLeave, enabled = !isSaving) {
                    Text(
                        stringResource(R.string.challenge_leave_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun AggregateBreakdown(
    viewer: WeeklyChallengeLeaderboardRow?,
    aggregate: WeeklyChallengeAggregate
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.challenge_weekly_breakdown),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        MetricLine(
            stringResource(R.string.challenge_category_overall),
            stringResource(
                R.string.challenge_points_format,
                viewer?.overallPoints ?: aggregate.overallPoints
            )
        )
        MetricLine(
            stringResource(R.string.challenge_category_activity),
            stringResource(
                R.string.challenge_days_kcal_format,
                viewer?.activityDays ?: aggregate.activityDays,
                viewer?.activityKcal ?: aggregate.activityKcal
            )
        )
        MetricLine(
            stringResource(R.string.challenge_category_nutrition),
            stringResource(R.string.challenge_days_format, viewer?.nutritionDays ?: aggregate.nutritionDays)
        )
        MetricLine(
            stringResource(R.string.challenge_category_consistency),
            stringResource(R.string.challenge_days_format, viewer?.consistencyDays ?: aggregate.consistencyDays)
        )
        MetricLine(
            stringResource(R.string.challenge_category_hydration),
            stringResource(R.string.challenge_days_format, viewer?.hydrationDays ?: aggregate.hydrationDays)
        )
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ChallengeEmptyLeaderboard() {
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 18.dp) {
        Text(
            text = stringResource(R.string.challenge_leaderboard_empty),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LeaderboardRowCard(
    row: WeeklyChallengeLeaderboardRow,
    category: WeeklyChallengeCategory,
    onReport: () -> Unit,
    onBlock: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.rank?.let { stringResource(R.string.challenge_rank_format, it) }
                    ?: stringResource(R.string.challenge_unranked),
                modifier = Modifier.width(54.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.displayName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                SocialHandle(row.socialPlatform, row.socialHandle)
            }
            Text(
                text = leaderboardScoreText(category, row),
                fontWeight = FontWeight.Bold,
                color = AppColors.Calorie
            )
            Box {
                val description = stringResource(R.string.challenge_more_actions, row.displayName)
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = description }
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.challenge_report_action)) },
                        onClick = {
                            menuOpen = false
                            onReport()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.challenge_block_action)) },
                        onClick = {
                            menuOpen = false
                            onBlock()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SocialHandle(
    platform: WeeklyChallengeSocialPlatform?,
    handle: String?
) {
    if (platform == null || handle.isNullOrBlank()) return
    val uriHandler = LocalUriHandler.current
    val platformName = stringResource(platform.labelRes())
    val description = stringResource(R.string.challenge_open_social, handle, platformName)
    val url = when (platform) {
        WeeklyChallengeSocialPlatform.X -> "https://x.com/$handle"
        WeeklyChallengeSocialPlatform.INSTAGRAM -> "https://www.instagram.com/$handle/"
    }
    Text(
        text = stringResource(R.string.challenge_social_display, handle, platformName),
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.Calorie,
        modifier = Modifier
            .semantics { contentDescription = description }
            .clickable(role = Role.Button) { uriHandler.openUri(url) }
            .padding(vertical = 2.dp)
    )
}

@Composable
private fun ChallengeErrorCard(
    error: WeeklyChallengeUiError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(error.messageRes()),
                color = MaterialTheme.colorScheme.error
            )
            Row {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

@Composable
private fun ChallengeProfileDialog(
    existing: WeeklyChallengePublicProfile?,
    isBusy: Boolean,
    error: WeeklyChallengeUiError?,
    onDismiss: () -> Unit,
    onSubmit: (
        name: String,
        platform: WeeklyChallengeSocialPlatform?,
        handle: String,
        acceptedRules: Boolean,
        eligibilityAccepted: Boolean
    ) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var displayName by remember(existing) { mutableStateOf(existing?.displayName.orEmpty()) }
    var socialPlatform by remember(existing) { mutableStateOf(existing?.socialPlatform) }
    var handle by remember(existing) { mutableStateOf(existing?.socialHandle.orEmpty()) }
    var rulesAccepted by remember { mutableStateOf(false) }
    var eligibilityAccepted by remember { mutableStateOf(false) }

    val isCreating = existing == null
    val nameValid = WeeklyChallengeProfileValidator.validDisplayName(displayName)
    val handleValid = socialPlatform?.let {
        WeeklyChallengeProfileValidator.validHandle(it, handle)
    } ?: true
    val canSubmit = nameValid && handleValid && !isBusy &&
        (!isCreating || (rulesAccepted && eligibilityAccepted))

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (isCreating) R.string.challenge_join_title else R.string.challenge_edit_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let {
                    Text(
                        text = stringResource(it.messageRes()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.challenge_display_name_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (displayName.isEmpty() || nameValid) {
                                    R.string.challenge_display_name_help
                                } else {
                                    R.string.challenge_display_name_error
                                }
                            )
                        )
                    },
                    isError = displayName.isNotEmpty() && !nameValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                Text(
                    text = stringResource(R.string.challenge_social_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = socialPlatform == null,
                        enabled = !isBusy,
                        onClick = {
                            socialPlatform = null
                            handle = ""
                        },
                        label = { Text(stringResource(R.string.challenge_social_none)) }
                    )
                    WeeklyChallengeSocialPlatform.entries.forEach { platform ->
                        FilterChip(
                            selected = socialPlatform == platform,
                            enabled = !isBusy,
                            onClick = {
                                socialPlatform = platform
                                handle = if (existing?.socialPlatform == platform) {
                                    existing.socialHandle.orEmpty()
                                } else {
                                    ""
                                }
                            },
                            label = { Text(stringResource(platform.labelRes())) }
                        )
                    }
                }
                socialPlatform?.let { platform ->
                    OutlinedTextField(
                        value = handle,
                        onValueChange = { handle = it },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.challenge_handle_label)) },
                        prefix = { Text("@") },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (handle.isEmpty() || handleValid) {
                                        R.string.challenge_handle_help
                                    } else if (platform == WeeklyChallengeSocialPlatform.X) {
                                        R.string.challenge_x_handle_error
                                    } else {
                                        R.string.challenge_instagram_handle_error
                                    }
                                )
                            )
                        },
                        isError = handle.isNotEmpty() && !handleValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
                    )
                }
                Text(
                    text = stringResource(R.string.challenge_public_profile_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isCreating) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.challenge_privacy_disclosure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ConsentRow(
                        checked = rulesAccepted,
                        onCheckedChange = { rulesAccepted = it },
                        label = stringResource(R.string.challenge_rules_consent),
                        enabled = !isBusy
                    )
                    TextButton(
                        onClick = { uriHandler.openUri(COMMUNITY_RULES_URL) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy
                    ) {
                        Text(stringResource(R.string.challenge_read_community_rules))
                    }
                    ConsentRow(
                        checked = eligibilityAccepted,
                        onCheckedChange = { eligibilityAccepted = it },
                        label = stringResource(R.string.challenge_age_consent),
                        enabled = !isBusy
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(
                        displayName,
                        socialPlatform,
                        handle,
                        rulesAccepted,
                        eligibilityAccepted
                    )
                },
                enabled = canSubmit
            ) {
                BusyActionLabel(
                    label = stringResource(
                        if (isCreating) R.string.challenge_join_action else R.string.action_save
                    ),
                    isBusy = isBusy
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun BusyActionLabel(label: String, isBusy: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        }
        Text(label)
    }
}

@Composable
private fun ConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            modifier = Modifier.padding(top = 11.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun LeaveChallengeDialog(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onLeave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text(stringResource(R.string.challenge_leave_title)) },
        text = { Text(stringResource(R.string.challenge_leave_message)) },
        confirmButton = {
            TextButton(onClick = onLeave, enabled = !isBusy) {
                Text(
                    stringResource(R.string.challenge_leave_confirm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ReportParticipantDialog(
    row: WeeklyChallengeLeaderboardRow,
    isBusy: Boolean,
    error: WeeklyChallengeUiError?,
    onDismiss: () -> Unit,
    onReport: (WeeklyChallengeReportReason, String) -> Unit
) {
    var reason by remember { mutableStateOf(WeeklyChallengeReportReason.INAPPROPRIATE_NAME) }
    var details by remember { mutableStateOf("") }
    val detailsLength = details.codePointCount(0, details.length)
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text(stringResource(R.string.challenge_report_title, row.displayName)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                error?.let {
                    Text(
                        text = stringResource(it.messageRes()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = stringResource(R.string.challenge_report_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WeeklyChallengeReportReason.entries.forEach { option ->
                    FilterChip(
                        selected = reason == option,
                        enabled = !isBusy,
                        onClick = { reason = option },
                        label = { Text(stringResource(option.labelRes())) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { candidate ->
                        val sanitized = WeeklyChallengeReportValidator.sanitizedInput(candidate)
                        if (WeeklyChallengeReportValidator.validDetails(sanitized)) {
                            details = sanitized
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.challenge_report_details)) },
                    supportingText = {
                        Text(stringResource(R.string.challenge_character_count, detailsLength, 300))
                    },
                    minLines = 3,
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReport(reason, details) }, enabled = !isBusy) {
                BusyActionLabel(
                    label = stringResource(R.string.challenge_report_submit),
                    isBusy = isBusy
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun BlockParticipantDialog(
    row: WeeklyChallengeLeaderboardRow,
    onDismiss: () -> Unit,
    onBlock: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.challenge_block_title, row.displayName)) },
        text = { Text(stringResource(R.string.challenge_block_message)) },
        confirmButton = {
            TextButton(onClick = onBlock) {
                Text(
                    stringResource(R.string.challenge_block_action),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun ManageBlockedDialog(
    blocked: Map<String, String>,
    onDismiss: () -> Unit,
    onUnblock: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.challenge_blocked_title)) },
        text = {
            if (blocked.isEmpty()) {
                Text(stringResource(R.string.challenge_blocked_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(blocked.entries.toList(), key = { it.key }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.value,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = { onUnblock(item.key) }) {
                                Text(stringResource(R.string.challenge_unblock_action))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
    )
}

@Composable
private fun scoreText(
    category: WeeklyChallengeCategory,
    viewer: WeeklyChallengeLeaderboardRow?,
    aggregate: WeeklyChallengeAggregate
): String {
    val value = viewer?.let { rowValue(category, it) } ?: aggregate.scoreFor(category)
    return when (category) {
        WeeklyChallengeCategory.OVERALL -> stringResource(R.string.challenge_points_format, value)
        WeeklyChallengeCategory.ACTIVITY -> stringResource(
            R.string.challenge_days_kcal_format,
            value,
            viewer?.activityKcal ?: aggregate.activityKcal
        )
        else -> stringResource(R.string.challenge_days_format, value)
    }
}

@Composable
private fun leaderboardScoreText(
    category: WeeklyChallengeCategory,
    row: WeeklyChallengeLeaderboardRow
): String = when (category) {
    WeeklyChallengeCategory.OVERALL -> stringResource(R.string.challenge_points_format, row.overallPoints)
    WeeklyChallengeCategory.ACTIVITY -> stringResource(
        R.string.challenge_days_kcal_format,
        row.activityDays,
        row.activityKcal
    )
    WeeklyChallengeCategory.NUTRITION -> stringResource(R.string.challenge_days_format, row.nutritionDays)
    WeeklyChallengeCategory.CONSISTENCY -> stringResource(R.string.challenge_days_format, row.consistencyDays)
    WeeklyChallengeCategory.HYDRATION -> stringResource(R.string.challenge_days_format, row.hydrationDays)
}

private fun rowValue(
    category: WeeklyChallengeCategory,
    row: WeeklyChallengeLeaderboardRow
): Int = when (category) {
    WeeklyChallengeCategory.OVERALL -> row.overallPoints
    WeeklyChallengeCategory.ACTIVITY -> row.activityDays
    WeeklyChallengeCategory.NUTRITION -> row.nutritionDays
    WeeklyChallengeCategory.CONSISTENCY -> row.consistencyDays
    WeeklyChallengeCategory.HYDRATION -> row.hydrationDays
}

private fun WeeklyChallengeCategory.labelRes(): Int = when (this) {
    WeeklyChallengeCategory.OVERALL -> R.string.challenge_category_overall
    WeeklyChallengeCategory.ACTIVITY -> R.string.challenge_category_activity
    WeeklyChallengeCategory.NUTRITION -> R.string.challenge_category_nutrition
    WeeklyChallengeCategory.CONSISTENCY -> R.string.challenge_category_consistency
    WeeklyChallengeCategory.HYDRATION -> R.string.challenge_category_hydration
}

private fun WeeklyChallengeSocialPlatform.labelRes(): Int = when (this) {
    WeeklyChallengeSocialPlatform.X -> R.string.challenge_social_x
    WeeklyChallengeSocialPlatform.INSTAGRAM -> R.string.challenge_social_instagram
}

private fun WeeklyChallengeReportReason.labelRes(): Int = when (this) {
    WeeklyChallengeReportReason.INAPPROPRIATE_NAME -> R.string.challenge_report_inappropriate_name
    WeeklyChallengeReportReason.IMPERSONATION -> R.string.challenge_report_impersonation
    WeeklyChallengeReportReason.SPAM -> R.string.challenge_report_spam
    WeeklyChallengeReportReason.UNSAFE_CONTENT -> R.string.challenge_report_unsafe_content
    WeeklyChallengeReportReason.OTHER -> R.string.challenge_report_other
}

private fun WeeklyChallengeUiError.messageRes(): Int = when (this) {
    WeeklyChallengeUiError.NETWORK -> R.string.challenge_error_network
    WeeklyChallengeUiError.AUTH -> R.string.challenge_error_auth
    WeeklyChallengeUiError.SERVER -> R.string.challenge_error_server
    WeeklyChallengeUiError.VALIDATION -> R.string.challenge_error_validation
}

private const val COMMUNITY_RULES_URL = "https://fud-ai.app/terms.html#community-rules"
