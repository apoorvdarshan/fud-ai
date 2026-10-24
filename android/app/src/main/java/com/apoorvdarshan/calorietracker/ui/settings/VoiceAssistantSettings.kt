package com.apoorvdarshan.calorietracker.ui.settings

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.VoiceAssistantPreferences
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.AppLanguage
import com.apoorvdarshan.calorietracker.services.hosted.HostedPrompt
import com.apoorvdarshan.calorietracker.services.hosted.HostedVoiceQuota
import com.apoorvdarshan.calorietracker.services.voice.LiveProvider
import com.apoorvdarshan.calorietracker.services.voice.VoiceAccess
import com.apoorvdarshan.calorietracker.services.voice.VoiceAssistantTileService
import com.apoorvdarshan.calorietracker.services.voice.shownAppLanguage
import com.apoorvdarshan.calorietracker.services.voice.VoiceProviderChoice
import com.apoorvdarshan.calorietracker.services.voice.VoiceProviderResolver
import com.apoorvdarshan.calorietracker.services.voice.VoicePhase
import com.apoorvdarshan.calorietracker.services.voice.VoicePreviewError
import com.apoorvdarshan.calorietracker.services.voice.VoicePreviewPhase
import com.apoorvdarshan.calorietracker.services.voice.VoicePreviewState
import com.apoorvdarshan.calorietracker.ui.components.FudIconBubble
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.ui.voice.minutesText
import com.apoorvdarshan.calorietracker.ui.voice.providerName
import com.apoorvdarshan.calorietracker.widget.VoiceAssistantWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class VoiceSheet { PROVIDER, MODEL, VOICE, LANGUAGE }

/** Languages offered for replies: the app's languages. */
private val REPLY_LANGUAGES: List<String> = AppLanguage.supportedTags

/**
 * Settings → Voice Assistant: provider (Automatic, Fud AI Plus/Pro, Gemini, OpenAI, xAI),
 * model and voice, reply language, capture/confirm/Coach switches, hosted voice minutes,
 * and how to open the assistant from the system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceAssistantSettings(container: AppContainer, onOpenAiProviders: () -> Unit) {
    val prefs by container.prefs.voiceAssistantPreferences.collectAsState(initial = VoiceAssistantPreferences())
    val selectedAi by container.prefs.selectedAIProvider.collectAsState(initial = AIProvider.GEMINI)
    val entitlement by container.billing.entitlement.collectAsState()
    val context = LocalContext.current
    val preview = container.voicePreview
    val previewState by preview.state.collectAsState()
    val assistantState by container.voiceAssistant.ui.collectAsState()
    val assistantActive = assistantState.phase != VoicePhase.IDLE
    val previewSample = stringResource(R.string.voice_preview_sample)
    // Recomputed when the configuration (and so the app or device language) changes.
    val configuration = LocalConfiguration.current
    val appLanguage = remember(configuration) { shownAppLanguage() }
    val talkLabel = stringResource(R.string.voice_talk_to_fud_ai)
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<VoiceSheet?>(null) }

    // Which own keys are saved, re-read whenever Settings comes back to the foreground.
    var keyEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, preview) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) preview.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            preview.stop()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) keyEpoch++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var keys by remember { mutableStateOf<Set<LiveProvider>>(emptySet()) }
    LaunchedEffect(keyEpoch, selectedAi) {
        keys = withContext(Dispatchers.IO) {
            LiveProvider.ownKeyProviders.filter { provider ->
                provider.aiProvider?.let { container.keyStore.apiKey(it) }?.isNotBlank() == true
            }.toSet()
        }
    }
    var voiceQuota by remember { mutableStateOf<HostedVoiceQuota?>(null) }
    LaunchedEffect(entitlement.isEntitled, previewState.active) {
        if (!entitlement.isEntitled) voiceQuota = null
        else if (!previewState.active) voiceQuota =
            runCatching { withContext(Dispatchers.IO) { container.hostedAiClient.fetchVoiceQuota() } }.getOrNull()
    }

    val access = VoiceProviderResolver.resolve(prefs.choice, selectedAi, { it in keys }, entitlement.isEntitled)
    val provider = (access as? VoiceAccess.Ready)?.provider ?: prefs.choice.provider ?: LiveProvider.GEMINI
    // What a session would use: a stored model or voice the provider no longer offers falls back.
    val model = provider.supportedModelOrDefault(prefs.model(provider))
    val voice = provider.supportedVoiceOrDefault(prefs.voice(provider))
    LaunchedEffect(prefs, selectedAi, keys, entitlement.isEntitled, appLanguage) { preview.stop() }
    LaunchedEffect(assistantActive) { if (assistantActive) preview.stop() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            SettingRow(
                stringResource(R.string.voice_settings_provider),
                choiceLabel(prefs.choice),
                icon = Icons.Outlined.RecordVoiceOver
            ) { sheet = VoiceSheet.PROVIDER }
            HorizontalDivider()
            AccessRow(
                access = access,
                onAddKey = onOpenAiProviders,
                onSeePlans = { container.hostedUi.show(HostedPrompt.Paywall) }
            )
            if (provider.usesOwnKey) {
                HorizontalDivider()
                SettingRow(stringResource(R.string.voice_settings_model), model, icon = Icons.Outlined.Tune) {
                    sheet = VoiceSheet.MODEL
                }
            }
            HorizontalDivider()
            SettingRow(
                stringResource(R.string.voice_settings_voice),
                LiveProvider.voiceDisplayName(voice),
                icon = Icons.Outlined.GraphicEq
            ) { sheet = VoiceSheet.VOICE }
            VoicePreviewControls(previewState, access is VoiceAccess.Ready, assistantActive) {
                if (previewState.active) preview.stop() else preview.start(previewSample, appLanguage)
            }
            HorizontalDivider()
            SettingRow(
                stringResource(R.string.voice_settings_language),
                languageLabel(prefs.language),
                icon = Icons.Outlined.Translate
            ) { sheet = VoiceSheet.LANGUAGE }
        }
        VoiceFooter(stringResource(R.string.voice_settings_automatic_footer))

        SectionCard {
            VoiceToggleRow(
                label = stringResource(R.string.voice_settings_auto_capture),
                subtitle = stringResource(R.string.voice_settings_auto_capture_footer),
                checked = prefs.autoCapture,
                icon = Icons.Outlined.CameraAlt,
                testTag = "voiceSettings.autoCapture"
            ) { on -> scope.launch { container.prefs.setVoiceAssistantAutoCapture(on) } }
            HorizontalDivider()
            VoiceToggleRow(
                label = stringResource(R.string.voice_settings_confirm_saves),
                subtitle = stringResource(R.string.voice_settings_confirm_saves_footer),
                checked = prefs.confirmSaves,
                icon = Icons.Outlined.VerifiedUser,
                testTag = "voiceSettings.confirmSaves"
            ) { on -> scope.launch { container.prefs.setVoiceAssistantConfirmSaves(on) } }
            HorizontalDivider()
            VoiceToggleRow(
                label = stringResource(R.string.voice_settings_save_to_coach),
                subtitle = stringResource(R.string.voice_settings_save_to_coach_footer),
                checked = prefs.saveToCoach,
                icon = Icons.Outlined.Forum,
                testTag = "voiceSettings.saveToCoach"
            ) { on -> scope.launch { container.prefs.setVoiceAssistantSaveToCoach(on) } }
        }

        if (entitlement.isEntitled || prefs.choice == VoiceProviderChoice.HOSTED) {
            SectionCard {
                SettingRow(
                    stringResource(R.string.voice_settings_minutes_left),
                    voiceQuota?.let { minutesLeftLabel(it) } ?: stringResource(R.string.voice_settings_minutes_unknown),
                    icon = Icons.Outlined.Timer
                ) { container.hostedUi.show(if (entitlement.isEntitled) HostedPrompt.Credits else HostedPrompt.Paywall) }
            }
            VoiceFooter(stringResource(R.string.voice_settings_minutes_footer))
        }

        SectionCard(title = stringResource(R.string.voice_settings_open_title)) {
            Text(
                stringResource(R.string.voice_settings_open_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                HorizontalDivider()
                SettingRow(stringResource(R.string.voice_settings_add_tile), "", icon = Icons.Outlined.GraphicEq) {
                    runCatching {
                        context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                            ComponentName(context, VoiceAssistantTileService::class.java),
                            talkLabel,
                            Icon.createWithResource(context, R.drawable.ic_voice_orb),
                            context.mainExecutor
                        ) { }
                    }
                }
            }
            val widgets = remember(context) { AppWidgetManager.getInstance(context) }
            if (widgets?.isRequestPinAppWidgetSupported == true) {
                HorizontalDivider()
                SettingRow(stringResource(R.string.voice_settings_add_widget), "", icon = Icons.AutoMirrored.Outlined.AddToHomeScreen) {
                    runCatching {
                        widgets.requestPinAppWidget(ComponentName(context, VoiceAssistantWidgetReceiver::class.java), null, null)
                    }
                }
            }
        }
        VoiceFooter(stringResource(R.string.voice_settings_privacy_footer))
    }

    sheet?.let { current ->
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                when (current) {
                    VoiceSheet.PROVIDER -> VoiceListSheet(
                        title = stringResource(R.string.voice_settings_provider),
                        items = VoiceProviderChoice.entries,
                        label = { choiceLabel(it) },
                        subtitle = { choice -> choiceSubtitle(choice, keys, entitlement.isEntitled) },
                        selected = { it == prefs.choice }
                    ) { choice ->
                        scope.launch { container.prefs.setVoiceAssistantProvider(choice) }
                        sheet = null
                    }
                    VoiceSheet.MODEL -> VoiceListSheet(
                        title = stringResource(R.string.voice_settings_model),
                        items = provider.models,
                        label = { it },
                        selected = { it == model }
                    ) { model ->
                        scope.launch { container.prefs.setVoiceAssistantModel(provider, model) }
                        sheet = null
                    }
                    VoiceSheet.VOICE -> VoiceListSheet(
                        title = stringResource(R.string.voice_settings_voice),
                        items = provider.voices,
                        label = { LiveProvider.voiceDisplayName(it) },
                        selected = { it == voice }
                    ) { voice ->
                        scope.launch { container.prefs.setVoiceAssistantVoice(provider, voice) }
                        sheet = null
                    }
                    VoiceSheet.LANGUAGE -> VoiceListSheet(
                        title = stringResource(R.string.voice_settings_language),
                        items = listOf(VoiceAssistantPreferences.LANGUAGE_APP, VoiceAssistantPreferences.LANGUAGE_AUTOMATIC) +
                            REPLY_LANGUAGES,
                        label = { languageLabel(it) },
                        selected = { it == prefs.language }
                    ) { language ->
                        scope.launch { container.prefs.setVoiceAssistantLanguage(language) }
                        sheet = null
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessRow(access: VoiceAccess, onAddKey: () -> Unit, onSeePlans: () -> Unit) {
    val (text, action, icon) = when (access) {
        is VoiceAccess.Ready -> Triple(stringResource(R.string.voice_settings_using, providerName(access.provider)), null, Icons.Filled.Check)
        is VoiceAccess.MissingKey -> Triple(
            stringResource(R.string.voice_settings_add_key, providerName(access.provider)),
            onAddKey,
            Icons.Outlined.Key
        )
        VoiceAccess.NeedsSubscription -> Triple(stringResource(R.string.voice_settings_needs_plan), onSeePlans, Icons.Outlined.WorkspacePremium)
        VoiceAccess.NeedsSetup -> Triple(stringResource(R.string.voice_settings_needs_setup), onAddKey, Icons.Outlined.Key)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (action != null) Modifier.clickable(onClick = action) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("voiceSettings.access"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (action != null) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            fontWeight = if (action != null) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
    }
}

/** A physical button with a stop action during both setup and playback. */
@Composable
internal fun VoicePreviewControls(
    state: VoicePreviewState,
    available: Boolean,
    assistantActive: Boolean,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        TextButton(
            onClick = onToggle,
            enabled = state.active || (available && !assistantActive),
            modifier = Modifier.testTag("voiceSettings.preview")
        ) {
            Icon(if (state.active) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (state.active) R.string.voice_preview_stop else R.string.voice_preview_start))
        }
        if (state.phase == VoicePreviewPhase.PREPARING) {
            Text(
                stringResource(R.string.voice_preview_preparing),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("voiceSettings.previewPreparing").semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        val error = when {
            assistantActive || state.error == VoicePreviewError.BUSY -> R.string.voice_preview_busy
            state.error == VoicePreviewError.FAILED -> R.string.voice_preview_failed
            else -> null
        }
        if (error != null) {
            Text(
                stringResource(error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("voiceSettings.previewError").semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        Text(
            stringResource(R.string.voice_preview_usage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VoiceToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    icon: ImageVector,
    testTag: String,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FudIconBubble(icon = icon, size = 22.dp, iconSize = 14.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(testTag))
    }
}

@Composable
private fun <T> VoiceListSheet(
    title: String,
    items: List<T>,
    label: @Composable (T) -> String,
    subtitle: (@Composable (T) -> String?)? = null,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.size(12.dp))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            val isSelected = selected(item)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isSelected) AppColors.Calorie.copy(alpha = 0.13f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    )
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label(item), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    subtitle?.invoke(item)?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.sheet_selected_a11y),
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

@Composable
private fun choiceLabel(choice: VoiceProviderChoice): String = when (choice) {
    VoiceProviderChoice.AUTOMATIC -> stringResource(R.string.voice_provider_automatic)
    else -> providerName(choice.provider!!)
}

@Composable
private fun choiceSubtitle(choice: VoiceProviderChoice, keys: Set<LiveProvider>, entitled: Boolean): String? = when (choice) {
    VoiceProviderChoice.AUTOMATIC -> null
    VoiceProviderChoice.HOSTED -> if (entitled) null else stringResource(R.string.voice_settings_needs_plan)
    else -> if (choice.provider in keys) null else stringResource(R.string.voice_settings_key_missing, providerName(choice.provider!!))
}

@Composable
private fun languageLabel(language: String): String = when (language) {
    VoiceAssistantPreferences.LANGUAGE_AUTOMATIC -> stringResource(R.string.voice_settings_language_auto)
    VoiceAssistantPreferences.LANGUAGE_APP -> stringResource(R.string.voice_settings_language_app)
    else -> AppLanguage.displayName(language)
}

/** This month's allowance left, plus the talk time the credit bank covers (1 credit per 15 s). */
@Composable
private fun minutesLeftLabel(quota: HostedVoiceQuota): String =
    if (quota.creditSeconds > 0) {
        stringResource(
            R.string.voice_settings_minutes_with_credits,
            minutesText(quota.allowanceSecondsLeft),
            minutesText(quota.creditSeconds)
        )
    } else {
        minutesText(quota.allowanceSecondsLeft)
    }
