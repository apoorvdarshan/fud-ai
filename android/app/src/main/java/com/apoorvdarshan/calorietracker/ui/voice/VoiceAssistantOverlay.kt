package com.apoorvdarshan.calorietracker.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.services.hosted.HostedPrompt
import com.apoorvdarshan.calorietracker.services.voice.LiveProvider
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascotPreferences
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascotProfile
import com.apoorvdarshan.calorietracker.services.voice.VoiceAssistantController
import com.apoorvdarshan.calorietracker.services.voice.VoiceCard
import com.apoorvdarshan.calorietracker.services.voice.VoiceNotice
import com.apoorvdarshan.calorietracker.services.voice.VoicePhase
import com.apoorvdarshan.calorietracker.services.voice.VoiceSettingsSection
import com.apoorvdarshan.calorietracker.services.voice.VoiceUiState
import com.apoorvdarshan.calorietracker.ui.hosted.findActivity
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

private val SessionPaper = Color(0xFF131114)
private val Muted = Color.White.copy(alpha = 0.6f)
private val EndRed = Color(0xFFFF453A)

/**
 * The live assistant over the app: **expanded** (dimmed background, large orb, captions,
 * the camera when open, cards and End) or **compact** (an orb bubble and a caption pill
 * above the tab bar while the app shows a screen). Also asks for the microphone, runs the
 * photo picker for `pick_photos`, keeps the screen on, and ends the conversation when the
 * app goes to the background.
 */
@Composable
fun VoiceAssistantHost(container: AppContainer) {
    val controller = container.voiceAssistant
    val ui by controller.ui.collectAsState()
    val cards by controller.cards.collectAsState()
    val waterUnit by container.prefs.waterUnit.collectAsState(initial = WaterUnit.Default)
    val weightUnit by container.prefs.weightUnit.collectAsState(initial = "kg")
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.externalActivityPending = false
        if (granted) controller.beginSession() else controller.microphoneDenied()
    }
    LaunchedEffect(ui.startRequest) {
        if (ui.phase != VoicePhase.PREPARING || !controller.claimStartRequest(ui.startRequest)) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            controller.beginSession()
        } else {
            controller.externalActivityPending = true
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val pickerContract = remember(ui.photoPickMax) {
        ActivityResultContracts.PickMultipleVisualMedia(ui.photoPickMax.coerceAtLeast(2))
    }
    val picker = rememberLauncherForActivityResult(pickerContract) { uris ->
        controller.photosPicked(uris)
    }
    LaunchedEffect(ui.photoPickRequest) {
        if (controller.claimPhotoPickRequest(ui.photoPickRequest)) {
            runCatching {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }.onFailure { controller.photosPicked(emptyList()) }
        }
    }

    // The conversation ends when the app leaves the foreground (not for pickers or rotation).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) {
                controller.appBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // While talking: keep the screen on, and let the volume keys set the conversation's volume.
    val view = LocalView.current
    val talking = ui.phase != VoicePhase.IDLE
    DisposableEffect(talking) {
        view.keepScreenOn = talking
        activity?.volumeControlStream = if (talking) AudioManager.STREAM_VOICE_CALL else AudioManager.USE_DEFAULT_STREAM_TYPE
        onDispose {
            view.keepScreenOn = false
            activity?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        }
    }

    if (!ui.visible) return

    val actions = remember(controller) {
        CardActions(
            confirm = controller::confirmCard,
            cancel = controller::cancelCard,
            undo = controller::undoCard,
            log = controller::logDraft,
            dismiss = controller::dismissCard
        )
    }
    val units = CardUnits(waterUnit, metric = weightUnit == "kg")
    val noticeActions = NoticeActions(
        retry = { controller.requestStart() },
        close = { controller.close() },
        showPlans = {
            controller.close()
            container.hostedUi.show(HostedPrompt.Paywall)
        },
        buyCredits = {
            controller.close()
            container.hostedUi.show(HostedPrompt.Credits)
        },
        openSettings = { section ->
            controller.close()
            container.navigator.showSettings(section)
        },
        openSystemSettings = {
            controller.close()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    )

    BackHandler(enabled = ui.expanded) {
        if (controller.isActive) controller.collapse() else controller.close()
    }

    val preferences by container.prefs.voiceMascotPreferences.collectAsState(initial = VoiceMascotPreferences())
    val mascot = ui.mascot ?: preferences.profile
    if (ui.expanded) {
        ExpandedOverlay(ui, cards, controller, actions, units, noticeActions, mascot)
    } else {
        CompactOverlay(ui, cards, controller, actions, units, mascot)
    }
}

// MARK: - Expanded

@Composable
private fun ExpandedOverlay(
    ui: VoiceUiState,
    cards: List<VoiceCard>,
    controller: VoiceAssistantController,
    actions: CardActions,
    units: CardUnits,
    noticeActions: NoticeActions,
    mascot: VoiceMascotProfile
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SessionPaper)
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("voiceAssistantOverlay")
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopBar(ui, controller)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val notice = ui.notice
                when {
                    notice != null -> NoticeView(notice, noticeActions, mascot)
                    ui.camera != null -> {
                        val panelWidth = min(maxWidth, maxHeight * 0.75f)
                        Box(Modifier.width(panelWidth)) {
                            VoiceCameraPanel(
                                controller = controller,
                                mode = ui.camera,
                                capturing = ui.capturing,
                                onShutter = controller::captureFromShutter,
                                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                            )
                            VoiceMascot(
                                mode = ui.orb,
                                profile = mascot,
                                level = controller::orbLevel,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                                    .size(56.dp)
                            )
                        }
                    }
                    else -> {
                        val side = min(min(maxWidth, maxHeight) * 0.92f, 360.dp)
                        VoiceMascot(
                            mode = ui.orb,
                            profile = mascot,
                            level = controller::orbLevel,
                            modifier = Modifier
                                .size(side)
                                .clickableNoRipple { if (ui.phase == VoicePhase.IDLE) controller.requestStart() }
                        )
                    }
                }
            }
            if (ui.notice == null) Captions(ui)
            if (cards.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    cards.forEach { card -> VoiceCardView(card, actions, units) }
                }
            }
            if (ui.notice == null) {
                Spacer(Modifier.height(14.dp))
                EndButton(onClick = controller::close)
            }
        }
    }
}

@Composable
private fun TopBar(ui: VoiceUiState, controller: VoiceAssistantController) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusChip(ui)
        ui.secondsRemaining?.takeIf { ui.provider == LiveProvider.HOSTED }?.let { seconds ->
            Spacer(Modifier.width(8.dp))
            Chip(Icons.Filled.Timer, stringResource(R.string.voice_time_left, minutesText(seconds)))
        }
        Spacer(Modifier.weight(1f))
        if (ui.phase != VoicePhase.IDLE) {
            IconButton(
                onClick = controller::collapse,
                modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
            ) {
                Icon(Icons.Filled.CloseFullscreen, contentDescription = stringResource(R.string.voice_minimize), tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
        }
        IconButton(
            onClick = controller::close,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .testTag("voiceAssistantClose")
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.voice_close), tint = Color.White)
        }
    }
}

@Composable
private fun StatusChip(ui: VoiceUiState) {
    if (ui.notice != null) return
    Chip(Icons.Filled.GraphicEq, statusText(ui))
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun statusText(ui: VoiceUiState): String = stringResource(
    when (ui.phase) {
        VoicePhase.IDLE -> R.string.voice_status_ended
        VoicePhase.PREPARING -> R.string.voice_status_preparing
        VoicePhase.CONNECTING -> R.string.voice_status_connecting
        VoicePhase.LIVE -> when (ui.orb) {
            OrbMode.SPEAKING -> R.string.voice_status_speaking
            OrbMode.THINKING -> R.string.voice_status_thinking
            else -> R.string.voice_status_listening
        }
    }
)

@Composable
private fun Captions(ui: VoiceUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (ui.userCaption.isNotBlank()) {
            Text(
                ui.userCaption,
                color = Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("voiceAssistantUserCaption")
            )
            Spacer(Modifier.height(6.dp))
        }
        val assistant = ui.assistantCaption.ifBlank {
            if (ui.phase == VoicePhase.LIVE && ui.userCaption.isBlank()) stringResource(R.string.voice_listening_hint) else ""
        }
        if (assistant.isNotBlank()) {
            Text(
                assistant,
                color = if (ui.assistantCaption.isBlank()) Muted else Color.White,
                fontSize = if (ui.assistantCaption.isBlank()) 16.sp else 20.sp,
                lineHeight = 27.sp,
                fontWeight = if (ui.assistantCaption.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("voiceAssistantCaption")
            )
        }
        ui.transientError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = EndRed, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun EndButton(onClick: () -> Unit) {
    val label = stringResource(R.string.voice_end)
    Row(
        Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(EndRed)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("voiceAssistantEnd")
            .padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.CallEnd, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

// MARK: - Compact

@Composable
private fun CompactOverlay(
    ui: VoiceUiState,
    cards: List<VoiceCard>,
    controller: VoiceAssistantController,
    actions: CardActions,
    units: CardUnits,
    mascot: VoiceMascotProfile
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, bottom = COMPACT_BOTTOM_PADDING),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val card = cards.lastOrNull { it is VoiceCard.Confirmation }
                ?: cards.lastOrNull { it is VoiceCard.Undo }
                ?: cards.lastOrNull { it is VoiceCard.Draft && !it.logged }
            AnimatedVisibility(visible = card != null, enter = fadeIn(), exit = fadeOut()) {
                card?.let { VoiceCardView(it, actions, units, compact = true) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val expandLabel = stringResource(R.string.voice_expand)
                Box(
                    Modifier
                        .size(56.dp)
                        .clickable(onClick = controller::expand)
                        .semantics { contentDescription = expandLabel }
                        .testTag("voiceAssistantBubble")
                ) {
                    VoiceMascot(mode = ui.orb, profile = mascot, level = controller::orbLevel, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(8.dp))
                val caption = ui.assistantCaption.ifBlank { ui.userCaption }.ifBlank { statusText(ui) }
                Text(
                    caption,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF16171C).copy(alpha = 0.92f))
                        .clickable(onClick = controller::expand)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("voiceAssistantCaptionPill")
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = controller::close,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF16171C).copy(alpha = 0.92f))
                        .testTag("voiceAssistantCompactEnd")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.voice_end), tint = Color.White)
                }
            }
        }
    }
}

// MARK: - Notices

private class NoticeActions(
    val retry: () -> Unit,
    val close: () -> Unit,
    val showPlans: () -> Unit,
    val buyCredits: () -> Unit,
    val openSettings: (VoiceSettingsSection) -> Unit,
    val openSystemSettings: () -> Unit
)

private class NoticeButton(val label: String, val primary: Boolean, val onClick: () -> Unit)

@Composable
private fun NoticeView(notice: VoiceNotice, actions: NoticeActions, mascot: VoiceMascotProfile) {
    val retry = NoticeButton(stringResource(R.string.voice_action_try_again), true, actions.retry)
    val close = NoticeButton(stringResource(R.string.voice_close), false, actions.close)
    val voiceSettings = NoticeButton(stringResource(R.string.voice_action_voice_settings), false) {
        actions.openSettings(VoiceSettingsSection.VOICE_ASSISTANT)
    }
    val aiProviders = NoticeButton(stringResource(R.string.voice_action_open_ai_providers), true) {
        actions.openSettings(VoiceSettingsSection.AI_PROVIDERS)
    }
    val (title, body, buttons) = when (notice) {
        VoiceNotice.NeedsSetup -> Triple(
            stringResource(R.string.voice_notice_setup_title),
            stringResource(R.string.voice_notice_setup_body),
            listOf(
                NoticeButton(stringResource(R.string.voice_action_see_plans), true, actions.showPlans),
                NoticeButton(stringResource(R.string.voice_action_add_key), false) { actions.openSettings(VoiceSettingsSection.AI_PROVIDERS) },
                close
            )
        )
        is VoiceNotice.MissingKey -> Triple(
            stringResource(R.string.voice_notice_missing_key_title, providerName(notice.provider)),
            stringResource(R.string.voice_notice_missing_key_body, providerName(notice.provider)),
            listOf(aiProviders, voiceSettings)
        )
        VoiceNotice.NeedsQwenSetup -> Triple(
            stringResource(R.string.voice_qwen_workspace_required),
            stringResource(R.string.voice_qwen_workspace_help),
            listOf(voiceSettings, close)
        )
        VoiceNotice.NeedsSubscription -> Triple(
            stringResource(R.string.voice_notice_subscription_title),
            stringResource(R.string.voice_notice_subscription_body),
            listOf(NoticeButton(stringResource(R.string.voice_action_see_plans), true, actions.showPlans), voiceSettings)
        )
        VoiceNotice.OutOfMinutes -> Triple(
            stringResource(R.string.voice_notice_minutes_title),
            stringResource(R.string.voice_notice_minutes_body),
            listOf(NoticeButton(stringResource(R.string.voice_action_buy_credits), true, actions.buyCredits), close)
        )
        VoiceNotice.SessionLimit -> Triple(
            stringResource(R.string.voice_notice_limit_title),
            stringResource(R.string.voice_notice_limit_body),
            listOf(NoticeButton(stringResource(R.string.voice_action_talk_again), true, actions.retry), close)
        )
        VoiceNotice.MicrophoneDenied -> Triple(
            stringResource(R.string.voice_notice_mic_title),
            stringResource(R.string.voice_notice_mic_body),
            listOf(NoticeButton(stringResource(R.string.voice_action_open_settings), true, actions.openSystemSettings), close)
        )
        VoiceNotice.MicrophoneUnavailable -> Triple(
            stringResource(R.string.voice_notice_mic_busy_title),
            stringResource(R.string.voice_notice_mic_busy_body),
            listOf(retry, close)
        )
        is VoiceNotice.KeyRejected -> Triple(
            stringResource(R.string.voice_notice_key_rejected_title, providerName(notice.provider)),
            stringResource(R.string.voice_notice_key_rejected_body, providerName(notice.provider)),
            listOf(aiProviders, close)
        )
        VoiceNotice.HostedUnauthorized -> Triple(
            stringResource(R.string.voice_notice_unauthorized_title),
            stringResource(R.string.voice_notice_unauthorized_body),
            listOf(retry, close)
        )
        VoiceNotice.RateLimited -> Triple(
            stringResource(R.string.voice_notice_busy_title),
            stringResource(R.string.voice_notice_busy_body),
            listOf(retry, close)
        )
        VoiceNotice.VoiceUnavailable -> Triple(
            stringResource(R.string.voice_notice_unavailable_title),
            stringResource(R.string.voice_notice_unavailable_body),
            listOf(retry, close)
        )
        VoiceNotice.ConnectionFailed -> Triple(
            stringResource(R.string.voice_notice_connect_title),
            stringResource(R.string.voice_notice_connect_body),
            listOf(retry, close)
        )
        VoiceNotice.ConnectionLost -> Triple(
            stringResource(R.string.voice_notice_lost_title),
            stringResource(R.string.voice_notice_lost_body),
            listOf(NoticeButton(stringResource(R.string.voice_action_talk_again), true, actions.retry), close)
        )
        is VoiceNotice.ProviderMessage -> Triple(
            stringResource(R.string.voice_notice_lost_title),
            notice.message,
            listOf(retry, close)
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFF16171C))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(26.dp))
            .padding(22.dp)
            .testTag("voiceAssistantNotice"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(84.dp)) {
            if (notice == VoiceNotice.MicrophoneDenied || notice == VoiceNotice.MicrophoneUnavailable) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MicOff, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(34.dp))
                }
            } else {
                VoiceMascot(mode = OrbMode.IDLE, profile = mascot, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Muted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        buttons.forEach { button ->
            Text(
                button.label,
                color = if (button.primary) Color.White else AppColors.Calorie,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (button.primary) AppColors.Calorie else Color.White.copy(alpha = 0.06f))
                    .clickable(onClick = button.onClick)
                    .padding(vertical = 13.dp)
            )
        }
    }
}

@Composable
internal fun providerName(provider: LiveProvider): String = stringResource(
    when (provider) {
        LiveProvider.HOSTED -> R.string.voice_provider_hosted
        LiveProvider.GEMINI -> R.string.voice_provider_gemini
        LiveProvider.OPENAI -> R.string.voice_provider_openai
        LiveProvider.XAI -> R.string.voice_provider_xai
        LiveProvider.QWEN -> R.string.voice_provider_qwen
        LiveProvider.GPT_LIVE -> R.string.voice_provider_gpt_live
    }
)

/** "12 min", "Under 1 min" for the last blocks, or "0 min" when nothing is left. */
@Composable
internal fun minutesText(seconds: Int): String =
    if (seconds in 1..59) stringResource(R.string.voice_less_than_a_minute)
    else stringResource(R.string.voice_minutes_short, seconds.coerceAtLeast(0) / 60)

/** Room above the floating tab bar (72 dp tall with 10 dp margins) for the compact bubble. */
private val COMPACT_BOTTOM_PADDING = 96.dp
