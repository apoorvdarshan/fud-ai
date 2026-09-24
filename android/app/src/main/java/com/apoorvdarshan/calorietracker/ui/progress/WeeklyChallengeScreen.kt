package com.apoorvdarshan.calorietracker.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.WaterDrop
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    val rankings = buildList {
        addAll(ui.leaderboard?.rankings.orEmpty())
        val viewerRow = ui.leaderboard?.viewer
        if (viewerRow != null && none { it.participantId == viewerRow.participantId }) {
            add(viewerRow)
        }
    }
        .distinctBy { it.participantId }
        .filterNot { it.participantId in ui.blockedParticipants }
        .map { row ->
            if (viewer?.participantId == row.participantId) row.copy(isViewer = true) else row
        }
        .sortedWith(compareBy({ it.rank ?: Int.MAX_VALUE }, { it.participantId }))

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
                item {
                    RankingsBoard(
                        rankings = rankings,
                        category = ui.category,
                        onReport = { row ->
                            vm.dismissError()
                            reportTarget = row
                        },
                        onBlock = { row -> blockTarget = row },
                        onManageBlocked = if (ui.blockedParticipants.isNotEmpty()) {
                            { showBlocked = true }
                        } else {
                            null
                        }
                    )
                }
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
                item { ChallengePointsExplanation() }
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
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, padding = 14.dp) {
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
        WeekCalendarStrip(weekStart)
    }
    }
}

@Composable
private fun WeekCalendarStrip(weekStart: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("EEEEE", locale) }
    val today = LocalDate.now()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(7) { index ->
            val day = weekStart.plusDays(index.toLong())
            val isToday = day == today
            val passed = !day.isAfter(today)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = day.format(formatter),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    color = if (isToday) AppColors.Calorie else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(if (isToday) 8.dp else 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (passed) AppColors.Calorie
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                )
            }
        }
    }
}

@Composable
private fun ChallengeIntroduction(onJoin: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 22.dp) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = AppColors.Calorie
                )
                Text(
                    text = stringResource(R.string.challenge_intro_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.challenge_intro_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        IntroFact(Icons.Filled.Lock, stringResource(R.string.challenge_privacy_disclosure))
        IntroFact(Icons.Filled.Person, stringResource(R.string.challenge_public_profile_disclosure))
        ChallengePointsExplanation()
        Button(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.challenge_join_action))
        }
    }
}

@Composable
private fun IntroFact(icon: ImageVector, text: String) {
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = AppColors.Calorie
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChallengePointsExplanation() {
    var open by remember { mutableStateOf(false) }
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .semantics {
                        if (open) {
                            collapse { open = false; true }
                        } else {
                            expand { open = true; true }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.challenge_points_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }
            if (open) {
                Text(
                    text = stringResource(R.string.challenge_points_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            val on = selected == category
            val foreground = if (on) Color.White else MaterialTheme.colorScheme.onSurface
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .clickable { onSelect(category) }
                    .semantics {
                        role = Role.Tab
                        this.selected = on
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = category.chipIcon(),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = foreground
                )
                Text(
                    text = stringResource(category.labelRes()),
                    color = foreground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
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
                RankBadge(viewer?.rank, diameter = 56.dp)
                Spacer(Modifier.width(14.dp))
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
                    Text(
                        text = scoreText(category, viewer, aggregate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    SocialHandle(profile.socialPlatform, profile.socialHandle)
                }
            }
            HorizontalDivider()
            AggregateBreakdown(viewer = viewer, aggregate = aggregate, selected = category)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onEdit,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(R.string.challenge_edit_profile),
                        textAlign = TextAlign.Center
                    )
                }
                OutlinedButton(
                    onClick = onLeave,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(R.string.challenge_leave_action),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun AggregateBreakdown(
    viewer: WeeklyChallengeLeaderboardRow?,
    aggregate: WeeklyChallengeAggregate,
    selected: WeeklyChallengeCategory
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.challenge_weekly_breakdown),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        WeekScoreBar(
            label = stringResource(R.string.challenge_category_overall),
            valueText = stringResource(
                R.string.challenge_points_format,
                viewer?.overallPoints ?: aggregate.overallPoints
            ),
            fraction = (viewer?.overallPoints ?: aggregate.overallPoints) / 28f,
            emphasized = selected == WeeklyChallengeCategory.OVERALL
        )
        WeekDayTrack(
            stringResource(R.string.challenge_category_activity),
            viewer?.activityDays ?: aggregate.activityDays,
            emphasized = selected == WeeklyChallengeCategory.ACTIVITY,
            valueText = stringResource(
                R.string.challenge_days_kcal_format,
                viewer?.activityDays ?: aggregate.activityDays,
                viewer?.activityKcal ?: aggregate.activityKcal
            )
        )
        WeekDayTrack(
            stringResource(R.string.challenge_category_nutrition),
            viewer?.nutritionDays ?: aggregate.nutritionDays,
            emphasized = selected == WeeklyChallengeCategory.NUTRITION
        )
        WeekDayTrack(
            stringResource(R.string.challenge_category_consistency),
            viewer?.consistencyDays ?: aggregate.consistencyDays,
            emphasized = selected == WeeklyChallengeCategory.CONSISTENCY
        )
        WeekDayTrack(
            stringResource(R.string.challenge_category_hydration),
            viewer?.hydrationDays ?: aggregate.hydrationDays,
            emphasized = selected == WeeklyChallengeCategory.HYDRATION
        )
    }
}

@Composable
private fun WeekScoreBar(
    label: String,
    valueText: String,
    fraction: Float,
    emphasized: Boolean
) {
    val bar = if (emphasized) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = if (emphasized) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(valueText, fontWeight = FontWeight.Bold, color = bar)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (emphasized) 10.dp else 8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(bar)
            )
        }
    }
}

@Composable
private fun WeekDayTrack(
    label: String,
    days: Int,
    emphasized: Boolean,
    valueText: String? = null
) {
    val filled = days.coerceIn(0, 7)
    val bar = if (emphasized) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = if (emphasized) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = valueText ?: stringResource(R.string.challenge_days_format, filled),
                fontWeight = FontWeight.Medium,
                color = if (emphasized) bar else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(if (emphasized) 10.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(7) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (index < filled) bar
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                )
            }
        }
    }
}

@Composable
private fun RankingsBoard(
    rankings: List<WeeklyChallengeLeaderboardRow>,
    category: WeeklyChallengeCategory,
    onReport: (WeeklyChallengeLeaderboardRow) -> Unit,
    onBlock: (WeeklyChallengeLeaderboardRow) -> Unit,
    onManageBlocked: (() -> Unit)?
) {
    val podium = podiumSlots(rankings)
    FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 6.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.challenge_leaderboard_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (onManageBlocked != null) {
                    TextButton(onClick = onManageBlocked) {
                        Text(stringResource(R.string.challenge_manage_blocked))
                    }
                }
            }
            if (rankings.isEmpty()) {
                Text(
                    text = stringResource(R.string.challenge_leaderboard_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (podium != null) {
                    RankingsPodium(
                        second = podium.second,
                        first = podium.first,
                        third = podium.third,
                        category = category,
                        onReport = onReport,
                        onBlock = onBlock
                    )
                }
                rankings.forEachIndexed { index, row ->
                    key(row.participantId) {
                        if (index > 0 || podium != null) {
                            HorizontalDivider(Modifier.padding(start = 64.dp, end = 12.dp))
                        }
                        RankingRow(
                            row = row,
                            category = category,
                            striped = index % 2 == 1,
                            onReport = { onReport(row) },
                            onBlock = { onBlock(row) }
                        )
                    }
                }
            }
        }
    }
}

private data class PodiumSlots(
    val first: WeeklyChallengeLeaderboardRow,
    val second: WeeklyChallengeLeaderboardRow,
    val third: WeeklyChallengeLeaderboardRow?
)

private fun podiumSlots(rankings: List<WeeklyChallengeLeaderboardRow>): PodiumSlots? {
    val first = rankings.firstOrNull { it.rank == 1 } ?: return null
    val second = rankings.firstOrNull { it.rank == 2 } ?: return null
    return PodiumSlots(first, second, rankings.firstOrNull { it.rank == 3 })
}

@Composable
private fun RankingsPodium(
    second: WeeklyChallengeLeaderboardRow,
    first: WeeklyChallengeLeaderboardRow,
    third: WeeklyChallengeLeaderboardRow?,
    category: WeeklyChallengeCategory,
    onReport: (WeeklyChallengeLeaderboardRow) -> Unit,
    onBlock: (WeeklyChallengeLeaderboardRow) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        PodiumColumn(
            row = second,
            category = category,
            pedestalHeight = 64.dp,
            onReport = onReport,
            onBlock = onBlock
        )
        PodiumColumn(
            row = first,
            category = category,
            pedestalHeight = 96.dp,
            onReport = onReport,
            onBlock = onBlock
        )
        PodiumColumn(
            row = third,
            category = category,
            pedestalHeight = 52.dp,
            onReport = onReport,
            onBlock = onBlock
        )
    }
}

@Composable
private fun RowScope.PodiumColumn(
    row: WeeklyChallengeLeaderboardRow?,
    category: WeeklyChallengeCategory,
    pedestalHeight: Dp,
    onReport: (WeeklyChallengeLeaderboardRow) -> Unit,
    onBlock: (WeeklyChallengeLeaderboardRow) -> Unit
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        if (row == null) {
            Spacer(Modifier.height(pedestalHeight))
            return@Column
        }
        key(row.participantId) {
        var menuOpen by remember(row.participantId) { mutableStateOf(false) }
        val (fill, labelColor) = rankMedalColors(row.rank)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            Box(Modifier.fillMaxWidth()) {
                RankBadge(row.rank, modifier = Modifier.align(Alignment.Center))
                if (!row.isViewer) {
                    ParticipantActions(
                        displayName = row.displayName,
                        menuOpen = menuOpen,
                        onOpen = { menuOpen = true },
                        onDismiss = { menuOpen = false },
                        onReport = {
                            menuOpen = false
                            onReport(row)
                        },
                        onBlock = {
                            menuOpen = false
                            onBlock(row)
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        compact = true
                    )
                }
            }
            Text(
                text = row.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 2.dp, end = 2.dp),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            if (row.isViewer) {
                YouChip(Modifier.padding(top = 4.dp))
            }
            PodiumHandle(row.socialPlatform, row.socialHandle)
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, start = 4.dp, end = 4.dp)
                    .fillMaxWidth()
                    .height(pedestalHeight)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(fill),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = leaderboardScoreText(category, row),
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    color = labelColor,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        }
    }
}

@Composable
private fun YouChip(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.challenge_you),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(AppColors.Calorie.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = AppColors.Calorie
    )
}

@Composable
private fun PodiumHandle(
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
        style = MaterialTheme.typography.labelSmall,
        color = AppColors.Calorie,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = 2.dp, start = 2.dp, end = 2.dp)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button) { uriHandler.openUri(url) }
    )
}

@Composable
private fun ParticipantActions(
    displayName: String,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val description = stringResource(R.string.challenge_more_actions, displayName)
    Box(modifier) {
        IconButton(
            onClick = onOpen,
            modifier = Modifier
                .then(if (compact) Modifier.size(32.dp) else Modifier)
                .semantics { contentDescription = description }
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.challenge_report_action)) },
                onClick = onReport
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.challenge_block_action)) },
                onClick = onBlock
            )
        }
    }
}

@Composable
private fun rankMedalColors(place: Int?): Pair<Color, Color> {
    val onSurface = MaterialTheme.colorScheme.onSurface
    return when (place) {
        1 -> Color(0xFFFFC107) to Color(0xFF3A2A00)
        2 -> Color(0xFFD7D7D7) to Color(0xFF2C2C2C)
        3 -> Color(0xFFE0A15A) to Color(0xFF3A2208)
        else -> onSurface.copy(alpha = 0.08f) to onSurface
    }
}

@Composable
private fun RankingRow(
    row: WeeklyChallengeLeaderboardRow,
    category: WeeklyChallengeCategory,
    striped: Boolean,
    onReport: () -> Unit,
    onBlock: () -> Unit
) {
    var menuOpen by remember(row.participantId) { mutableStateOf(false) }
    val place = row.rank
    val rowColor = when {
        row.isViewer -> AppColors.Calorie.copy(alpha = 0.12f)
        striped -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowColor)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.isViewer) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(AppColors.Calorie)
            )
            Spacer(Modifier.width(8.dp))
        }
        RankBadge(place)
        Spacer(Modifier.width(10.dp))
        NameMark(row.displayName)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.displayName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (row.isViewer) {
                    YouChip(Modifier.padding(start = 8.dp))
                }
            }
            SocialHandle(row.socialPlatform, row.socialHandle)
        }
        Text(
            text = leaderboardScoreText(category, row),
            modifier = Modifier
                .padding(start = 8.dp)
                .widthIn(max = 148.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Calorie.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            fontWeight = FontWeight.Bold,
            color = AppColors.Calorie,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelLarge
        )
        if (!row.isViewer) {
            ParticipantActions(
                displayName = row.displayName,
                menuOpen = menuOpen,
                onOpen = { menuOpen = true },
                onDismiss = { menuOpen = false },
                onReport = {
                    menuOpen = false
                    onReport()
                },
                onBlock = {
                    menuOpen = false
                    onBlock()
                }
            )
        }
    }
}

private val nameMarkColors = listOf(
    Color(0xFFE85D75),
    Color(0xFF5B8DEF),
    Color(0xFF3CB89A),
    Color(0xFFF0A202),
    Color(0xFF9B6BFF),
    Color(0xFF2BB0C9)
)

@Composable
private fun NameMark(name: String) {
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val color = nameMarkColors[name.hashCode().mod(nameMarkColors.size)]
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun RankBadge(place: Int?, modifier: Modifier = Modifier, diameter: Dp = 36.dp) {
    val (fill, labelColor) = rankMedalColors(place)
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = place?.toString() ?: "–",
            fontWeight = FontWeight.Bold,
            color = labelColor
        )
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

private fun WeeklyChallengeCategory.chipIcon() = when (this) {
    WeeklyChallengeCategory.OVERALL -> Icons.Filled.EmojiEvents
    WeeklyChallengeCategory.ACTIVITY -> Icons.Filled.LocalFireDepartment
    WeeklyChallengeCategory.NUTRITION -> Icons.Filled.Restaurant
    WeeklyChallengeCategory.CONSISTENCY -> Icons.Filled.CheckCircle
    WeeklyChallengeCategory.HYDRATION -> Icons.Filled.WaterDrop
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
