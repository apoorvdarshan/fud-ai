package com.apoorvdarshan.calorietracker.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalDining
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.SportsMartialArts
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.Wc
import androidx.compose.material.icons.outlined.Female
import androidx.compose.material.icons.outlined.Male
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Height
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.R
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.ActivityLevel
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.AutoBalanceMacro
import com.apoorvdarshan.calorietracker.models.Gender
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightGoal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.apoorvdarshan.calorietracker.ui.components.DecimalWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.FeetInchesWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.NumericWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.SplitDecimalWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.UnitToggle
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

private enum class SettingsSheet {
    AI_PROVIDER, AI_MODEL, API_KEY, CUSTOM_BASE_URL, SPEECH_PROVIDER, SPEECH_KEY,
    GENDER, BIRTHDAY, HEIGHT, WEIGHT, BODY_FAT, GOAL_BODY_FAT, ACTIVITY, GOAL, GOAL_WEIGHT, GOAL_SPEED,
    CALORIES, PROTEIN, CARBS, FAT,
    APPEARANCE, WEEK_START
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, nav: NavHostController) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val profile = ui.profile

    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearFoodDialog by remember { mutableStateOf(false) }
    var showRecalcDialog by remember { mutableStateOf(false) }
    var invalidGoalWeightMessage by remember { mutableStateOf<String?>(null) }
    var showMaxPinnedAlert by remember { mutableStateOf(false) }
    var permissionDeniedMessage by remember { mutableStateOf<String?>(null) }
    val activityContext = LocalContext.current

    // Notifications: API 33+ requires runtime POST_NOTIFICATIONS. We only flip the
    // pref to true if the user actually grants. Denial leaves the toggle off so
    // the UI never lies about whether notifications can fire.
    val notifDeniedMsg = stringResource(R.string.settings_notifications_denied)
    val healthDeniedMsg = stringResource(R.string.settings_health_denied)
    val healthUnavailableMsg = stringResource(R.string.settings_health_unavailable)

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.setNotificationsEnabled(true)
        else permissionDeniedMessage = notifDeniedMsg
    }

    // Health Connect: launches the standard HC permissions screen for the read +
    // write × (Weight + Nutrition) bundle. Same idea — only flip the pref true
    // if the user grants the full set.
    val healthConnectLauncher = rememberLauncherForActivityResult(
        contract = container.health.permissionRequestContract()
    ) { granted ->
        if (granted.containsAll(container.health.permissions)) vm.setHealthConnectEnabled(true)
        else permissionDeniedMessage = healthDeniedMsg
    }

    fun onNotificationsToggle(enabled: Boolean) {
        if (!enabled) {
            vm.setNotificationsEnabled(false)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            vm.setNotificationsEnabled(true)
        } else {
            val granted = ContextCompat.checkSelfPermission(
                activityContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) vm.setNotificationsEnabled(true)
            else notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun onHealthConnectToggle(enabled: Boolean) {
        if (!enabled) {
            vm.setHealthConnectEnabled(false)
            return
        }
        if (!container.health.isAvailable()) {
            permissionDeniedMessage = healthUnavailableMsg
            return
        }
        // Don't pre-check granted state — Health Connect's contract handles the
        // already-granted case by returning the full set immediately.
        healthConnectLauncher.launch(container.health.permissions)
    }

    fun openBatteryOptimizationSettings() {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${activityContext.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                add(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${activityContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        for (intent in intents) {
            if (runCatching { activityContext.startActivity(intent) }.isSuccess) return
        }
    }

    // iOS Settings: bare List, no NavigationBar visible. Match that — no TopAppBar.
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Section 1 — Personal Info (matches iOS Section "Personal Info")
            SectionCard(title = stringResource(R.string.settings_section_personal)) {
                profile?.let { p ->
                    SettingRow(stringResource(R.string.settings_gender), stringResource(p.gender.displayNameRes), icon = Icons.Outlined.Person, inlineMenu = true) { sheet = SettingsSheet.GENDER }
                    HorizontalDivider()
                    SettingRow(stringResource(R.string.settings_birthday), birthdayDisplay(p), icon = Icons.Outlined.Cake) { sheet = SettingsSheet.BIRTHDAY }
                    HorizontalDivider()
                    SettingRow(
                        stringResource(R.string.settings_height),
                        if (ui.useMetric) stringResource(R.string.height_cm_format, p.heightCm.toInt())
                        else feetInchesLabel(p.heightCm.toInt()),
                        icon = Icons.Outlined.Height
                    ) { sheet = SettingsSheet.HEIGHT }
                    HorizontalDivider()
                    SettingRow(
                        stringResource(R.string.settings_weight),
                        if (ui.useMetric) String.format(Locale.US, "%.1f kg", p.weightKg)
                        else String.format(Locale.US, "%.1f lbs", p.weightKg * 2.20462),
                        icon = Icons.Outlined.MonitorWeight
                    ) { sheet = SettingsSheet.WEIGHT }
                    HorizontalDivider()
                    SettingRow(
                        stringResource(R.string.settings_body_fat),
                        p.bodyFatPercentage?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.settings_not_set),
                        icon = Icons.Outlined.Percent
                    ) { sheet = SettingsSheet.BODY_FAT }

                    // Goal Body Fat + Use-Body-Fat-for-BMR toggle only render
                    // when the user actually has a body fat % set — avoids
                    // surfacing irrelevant controls to users who never opted in.
                    if (p.bodyFatPercentage != null) {
                        HorizontalDivider()
                        SettingRow(
                            stringResource(R.string.settings_goal_body_fat),
                            p.goalBodyFatPercentage?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.settings_not_set),
                            icon = Icons.Outlined.TrackChanges
                        ) { sheet = SettingsSheet.GOAL_BODY_FAT }
                        HorizontalDivider()
                        // Use saveProfile (not recompute) for the toggle since
                        // BMR formula switching is precisely what should
                        // recompute calories + macros — handled inline via
                        // recalculatedFromFormulas after the copy.
                        ToggleRow(
                            stringResource(R.string.settings_use_body_fat_bmr),
                            p.useBodyFatInBMR ?: true,
                            icon = Icons.Outlined.Calculate,
                            onChange = { newValue ->
                                vm.updateProfileAndRecompute { it.copy(useBodyFatInBMR = newValue) }
                            }
                        )
                        Text(
                            stringResource(R.string.settings_use_body_fat_bmr_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Section 2 — Goals & Nutrition (matches iOS Section "Goals & Nutrition")
            SectionCard(title = stringResource(R.string.settings_section_goals)) {
                profile?.let { p ->
                    SettingRow(stringResource(R.string.settings_weight_goal), stringResource(p.goal.displayNameRes), icon = Icons.Outlined.Equalizer, inlineMenu = true) { sheet = SettingsSheet.GOAL }
                    HorizontalDivider()
                    SettingRow(stringResource(R.string.settings_activity_level), stringResource(p.activityLevel.displayNameRes), icon = Icons.AutoMirrored.Outlined.DirectionsRun, inlineMenu = true) { sheet = SettingsSheet.ACTIVITY }
                    if (p.goal != WeightGoal.MAINTAIN) {
                        HorizontalDivider()
                        SettingRow(
                            stringResource(R.string.settings_weekly_change),
                            p.weeklyChangeKg?.let {
                                if (ui.useMetric) String.format(Locale.US, "%.2f kg/wk", it)
                                else String.format(Locale.US, "%.2f lbs/wk", it * 2.20462)
                            } ?: stringResource(R.string.settings_weekly_default),
                            icon = Icons.Outlined.Speed
                        ) { sheet = SettingsSheet.GOAL_SPEED }
                        HorizontalDivider()
                        SettingRow(
                            stringResource(R.string.settings_goal_weight),
                            p.goalWeightKg?.let {
                                if (ui.useMetric) String.format(Locale.US, "%.1f kg", it)
                                else String.format(Locale.US, "%.1f lbs", it * 2.20462)
                            } ?: stringResource(R.string.settings_not_set),
                            icon = Icons.AutoMirrored.Outlined.TrendingUp
                        ) { sheet = SettingsSheet.GOAL_WEIGHT }
                    }
                    HorizontalDivider()
                    // iOS shows "2452 kcal" with no chevron suffix on the Calories row.
                    SettingRow(stringResource(R.string.settings_calories), stringResource(R.string.kcal_value_format, p.effectiveCalories), icon = Icons.Outlined.LocalFireDepartment) { sheet = SettingsSheet.CALORIES }
                    HorizontalDivider()
                    // Per-macro rows mirror iOS macroRow(): "{value}g · auto" suffix when
                    // unpinned, "{value}g" when pinned, plus lock.fill / lock.open icon.
                    // Max-2-pinned guard: tapping an unpinned macro when 2 are already
                    // pinned shows the alert instead of opening the picker.
                    val openMacro = { macro: AutoBalanceMacro, target: SettingsSheet ->
                        val isPinned = p.isPinned(macro)
                        if (!isPinned && p.pinnedCount >= 2) showMaxPinnedAlert = true
                        else sheet = target
                    }
                    MacroSettingRow(
                        label = stringResource(R.string.macro_protein),
                        value = p.effectiveProtein,
                        pinned = p.isPinned(AutoBalanceMacro.PROTEIN),
                        onClick = { openMacro(AutoBalanceMacro.PROTEIN, SettingsSheet.PROTEIN) }
                    )
                    HorizontalDivider()
                    MacroSettingRow(
                        label = stringResource(R.string.macro_carbs),
                        value = p.effectiveCarbs,
                        pinned = p.isPinned(AutoBalanceMacro.CARBS),
                        onClick = { openMacro(AutoBalanceMacro.CARBS, SettingsSheet.CARBS) }
                    )
                    HorizontalDivider()
                    MacroSettingRow(
                        label = stringResource(R.string.macro_fat),
                        value = p.effectiveFat,
                        pinned = p.isPinned(AutoBalanceMacro.FAT),
                        onClick = { openMacro(AutoBalanceMacro.FAT, SettingsSheet.FAT) }
                    )
                    HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showRecalcDialog = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = AppColors.Calorie,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.settings_recalculate_goals), color = AppColors.Calorie, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            // Section 3 — App Settings (matches iOS Section "App Settings")
            SectionCard(title = stringResource(R.string.settings_section_app)) {
                SettingRow(
                    stringResource(R.string.settings_appearance),
                    when (ui.appearanceMode) {
                        "light" -> stringResource(R.string.settings_appearance_light)
                        "dark" -> stringResource(R.string.settings_appearance_dark)
                        else -> stringResource(R.string.settings_appearance_system)
                    },
                    icon = Icons.Outlined.Brightness6
                ) { sheet = SettingsSheet.APPEARANCE }
                HorizontalDivider()
                ToggleRow(stringResource(R.string.settings_metric_units), ui.useMetric, icon = Icons.Outlined.Straighten, onChange = vm::setUseMetric)
                HorizontalDivider()
                SettingRow(
                    stringResource(R.string.settings_week_starts),
                    if (ui.weekStartsOnMonday) stringResource(R.string.settings_week_monday) else stringResource(R.string.settings_week_sunday),
                    icon = Icons.Outlined.CalendarToday
                ) { sheet = SettingsSheet.WEEK_START }
                HorizontalDivider()
                ToggleRow(stringResource(R.string.settings_notifications), ui.notificationsEnabled, icon = Icons.Outlined.Notifications, onChange = ::onNotificationsToggle)
                if (ui.notificationsEnabled) {
                    HorizontalDivider()
                    SettingRow(
                        stringResource(R.string.settings_battery_opt),
                        stringResource(R.string.settings_battery_opt_value),
                        icon = Icons.Outlined.BatteryAlert
                    ) { openBatteryOptimizationSettings() }
                }
            }

            // Section 4 — AI Provider (matches iOS Section "AI Provider")
            SectionCard(title = stringResource(R.string.settings_section_ai)) {
                SettingRow(stringResource(R.string.settings_ai_provider), stringResource(ui.selectedAI.displayNameRes), icon = Icons.Outlined.SmartToy) { sheet = SettingsSheet.AI_PROVIDER }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_ai_model), ui.selectedModel.ifEmpty { stringResource(R.string.settings_ai_model_unset) }, icon = Icons.Outlined.Tune) { sheet = SettingsSheet.AI_MODEL }
                if (ui.selectedAI.requiresApiKey) {
                    HorizontalDivider()
                    SettingRow(stringResource(R.string.settings_api_key), ui.apiKeyMasked.ifEmpty { stringResource(R.string.settings_not_set) }, icon = Icons.Outlined.Key) { sheet = SettingsSheet.API_KEY }
                }
                if (ui.selectedAI.requiresCustomEndpoint || ui.selectedAI == AIProvider.OLLAMA) {
                    HorizontalDivider()
                    SettingRow(
                        if (ui.selectedAI.requiresCustomEndpoint) stringResource(R.string.settings_base_url) else stringResource(R.string.settings_server_url),
                        stringResource(R.string.settings_tap_to_edit),
                        icon = Icons.Outlined.Link
                    ) { sheet = SettingsSheet.CUSTOM_BASE_URL }
                }
            }

            // Section 5 — Speech-to-Text (matches iOS Section "Speech-to-Text")
            SectionCard(title = stringResource(R.string.settings_section_speech)) {
                SettingRow(stringResource(R.string.settings_ai_provider), stringResource(ui.selectedSpeech.displayNameRes), icon = Icons.Outlined.Mic) { sheet = SettingsSheet.SPEECH_PROVIDER }
                HorizontalDivider()
                Text(
                    stringResource(ui.selectedSpeech.descriptionRes),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
                if (ui.selectedSpeech.requiresApiKey) {
                    HorizontalDivider()
                    SettingRow(
                        stringResource(R.string.settings_api_key),
                        ui.speechApiKeyMasked.ifEmpty { stringResource(R.string.settings_not_set) },
                        icon = Icons.Outlined.Key
                    ) { sheet = SettingsSheet.SPEECH_KEY }
                }
            }

            // Section 6 — Health & Data (matches iOS Section "Health & Data")
            SectionCard(title = stringResource(R.string.settings_section_health)) {
                ToggleRow(stringResource(R.string.settings_health_connect), ui.healthConnectEnabled, icon = Icons.Outlined.Favorite, onChange = ::onHealthConnectToggle)
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showClearFoodDialog = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = Color(0xFFFF9500), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_clear_food_log), color = Color(0xFFFF9500), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showDeleteDialog = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_delete_all_data), color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    sheet?.let { s ->
        SettingsSheets(
            sheet = s,
            ui = ui,
            vm = vm,
            onDismiss = { sheet = null },
            onInvalidGoalWeight = { invalidGoalWeightMessage = it }
        )
    }

    if (showClearFoodDialog) {
        AlertDialog(
            onDismissRequest = { showClearFoodDialog = false },
            title = { Text(stringResource(R.string.settings_clear_food_title)) },
            text = { Text(stringResource(R.string.settings_clear_food_message)) },
            confirmButton = {
                TextButton(onClick = { vm.clearFoodLog(); showClearFoodDialog = false }) {
                    Text(stringResource(R.string.action_clear), color = Color(0xFFD32F2F))
                }
            },
            dismissButton = { TextButton(onClick = { showClearFoodDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (showDeleteDialog) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.settings_delete_all_title)) },
            text = { Text(stringResource(R.string.settings_delete_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAllData {
                        showDeleteDialog = false
                        (context as? android.app.Activity)?.recreate()
                    }
                }) {
                    Text(stringResource(R.string.action_delete), color = Color(0xFFD32F2F))
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (showMaxPinnedAlert) {
        AlertDialog(
            onDismissRequest = { showMaxPinnedAlert = false },
            title = { Text(stringResource(R.string.settings_max_pinned_title)) },
            text = { Text(stringResource(R.string.settings_max_pinned_message)) },
            confirmButton = { TextButton(onClick = { showMaxPinnedAlert = false }) { Text(stringResource(R.string.action_ok)) } }
        )
    }

    if (showRecalcDialog) {
        AlertDialog(
            onDismissRequest = { showRecalcDialog = false },
            title = { Text(stringResource(R.string.settings_recalc_title)) },
            text = { Text(stringResource(R.string.settings_recalc_message)) },
            confirmButton = { TextButton(onClick = { vm.recalculateGoals(); showRecalcDialog = false }) { Text(stringResource(R.string.settings_recalc_confirm)) } },
            dismissButton = { TextButton(onClick = { showRecalcDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    invalidGoalWeightMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { invalidGoalWeightMessage = null },
            title = { Text(stringResource(R.string.settings_invalid_goal_title)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { invalidGoalWeightMessage = null }) { Text(stringResource(R.string.action_ok)) } }
        )
    }

    permissionDeniedMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { permissionDeniedMessage = null },
            title = { Text(stringResource(R.string.settings_permission_title)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { permissionDeniedMessage = null }) { Text(stringResource(R.string.action_ok)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheets(
    sheet: SettingsSheet,
    ui: SettingsUiState,
    vm: SettingsViewModel,
    onDismiss: () -> Unit,
    onInvalidGoalWeight: (String) -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val invalidLoseMsg = stringResource(R.string.settings_invalid_goal_lose)
    val invalidGainMsg = stringResource(R.string.settings_invalid_goal_gain)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            when (sheet) {
                SettingsSheet.AI_PROVIDER -> ListSheet(
                    title = stringResource(R.string.sheet_ai_provider),
                    items = AIProvider.values().toList(),
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.selectedAI },
                    onSelect = { vm.selectProvider(it); onDismiss() }
                )
                SettingsSheet.AI_MODEL -> ListSheet(
                    title = stringResource(R.string.sheet_model),
                    items = ui.selectedAI.models,
                    label = { it },
                    selected = { it == ui.selectedModel },
                    onSelect = { vm.selectModel(it); onDismiss() },
                    footer = if (ui.selectedAI.supportsCustomModelName) stringResource(R.string.sheet_model_footer) else null,
                    customField = if (ui.selectedAI.supportsCustomModelName) {
                        { m -> vm.selectModel(m); onDismiss() }
                    } else null
                )
                SettingsSheet.API_KEY -> ApiKeySheet(
                    title = stringResource(R.string.sheet_api_key_format, stringResource(ui.selectedAI.displayNameRes)),
                    placeholder = stringResource(ui.selectedAI.apiKeyPlaceholderRes),
                    onSave = { vm.setApiKey(it); onDismiss() }
                )
                SettingsSheet.CUSTOM_BASE_URL -> {
                    val existing = remember { runBlocking { vm.container.prefs.customBaseUrl(ui.selectedAI).first().orEmpty() } }
                    TextFieldSheet(
                        title = stringResource(R.string.settings_custom_url_title),
                        initial = existing,
                        placeholder = stringResource(R.string.settings_custom_url_placeholder),
                        onSave = { vm.setCustomBaseUrl(ui.selectedAI, it); onDismiss() }
                    )
                }
                SettingsSheet.SPEECH_PROVIDER -> ListSheet(
                    title = stringResource(R.string.sheet_speech_engine),
                    items = SpeechProvider.values().toList(),
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.selectedSpeech },
                    onSelect = { vm.selectSpeech(it); onDismiss() }
                )
                SettingsSheet.SPEECH_KEY -> ApiKeySheet(
                    title = stringResource(R.string.sheet_speech_api_key_format, stringResource(ui.selectedSpeech.displayNameRes)),
                    placeholder = stringResource(ui.selectedSpeech.apiKeyPlaceholderRes),
                    onSave = {
                        // Route through the VM so SettingsUiState.speechApiKeyMasked
                        // updates and the API Key row reflects the new value
                        // (was bypassing the VM and writing straight to KeyStore,
                        // which left the UI showing "Tap to edit" forever).
                        vm.setSpeechApiKey(it)
                        onDismiss()
                    }
                )
                SettingsSheet.GENDER -> ListSheet(
                    title = stringResource(R.string.sheet_gender),
                    items = Gender.values().toList(),
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.profile?.gender },
                    onSelect = { g -> vm.updateProfileAndRecompute { it.copy(gender = g) }; onDismiss() },
                    icon = { genderIcon(it) }
                )
                SettingsSheet.HEIGHT -> {
                    val cm = ui.profile?.heightCm?.toInt() ?: 175
                    HeightSheet(
                        current = cm,
                        useMetric = ui.useMetric,
                        onSave = { newCm -> vm.updateProfileAndRecompute { it.copy(heightCm = newCm.toDouble()) }; onDismiss() }
                    )
                }
                SettingsSheet.WEIGHT -> {
                    val kg = ui.profile?.weightKg ?: 70.0
                    WeightSheet(
                        titleText = stringResource(R.string.sheet_weight),
                        current = kg,
                        useMetric = ui.useMetric,
                        onSave = { newKg -> vm.saveCurrentWeight(newKg); onDismiss() }
                    )
                }
                SettingsSheet.BODY_FAT -> BodyFatSheet(
                    current = ui.profile?.bodyFatPercentage,
                    // Clearing the current value also clears the goal so a stale
                    // goal doesn't linger on someone who opted out of the
                    // body-fat track entirely.
                    onSave = { bf ->
                        vm.updateProfileAndRecompute {
                            it.copy(
                                bodyFatPercentage = bf,
                                goalBodyFatPercentage = if (bf == null) null else it.goalBodyFatPercentage
                            )
                        }
                        onDismiss()
                    }
                )
                SettingsSheet.GOAL_BODY_FAT -> GoalBodyFatSheet(
                    currentGoal = ui.profile?.goalBodyFatPercentage,
                    currentBodyFat = ui.profile?.bodyFatPercentage,
                    // Goal body fat doesn't feed BMR/TDEE/macro math, so use
                    // updateProfile (no recompute) — editing the goal must
                    // never silently wipe the user's pinned macros.
                    onSave = { goal -> vm.updateProfile { it.copy(goalBodyFatPercentage = goal) }; onDismiss() }
                )
                SettingsSheet.ACTIVITY -> ListSheet(
                    title = stringResource(R.string.sheet_activity_level),
                    items = ActivityLevel.values().toList(),
                    label = { stringResource(it.displayNameRes) },
                    subtitle = { stringResource(it.subtitleRes) },
                    selected = { it == ui.profile?.activityLevel },
                    onSelect = { a -> vm.updateProfileAndRecompute { it.copy(activityLevel = a) }; onDismiss() },
                    icon = { activityIcon(it) }
                )
                SettingsSheet.GOAL -> ListSheet(
                    title = stringResource(R.string.sheet_goal),
                    items = WeightGoal.values().toList(),
                    label = { stringResource(it.displayNameRes) },
                    selected = { it == ui.profile?.goal },
                    icon = { goalIcon(it) },
                    onSelect = { g ->
                        // Mirrors iOS ContentView.swift profile.goal onChange:
                        //   - Switching to MAINTAIN clears weeklyChangeKg + goalWeightKg.
                        //   - Switching to LOSE/GAIN seeds weeklyChangeKg if missing and
                        //     clears goalWeightKg if it now contradicts the new direction.
                        // Then recompute calories+macros from the new goal.
                        vm.updateProfileAndRecompute { p ->
                            when (g) {
                                WeightGoal.MAINTAIN ->
                                    p.copy(goal = g, weeklyChangeKg = null, goalWeightKg = null)
                                else -> {
                                    val gw = p.goalWeightKg
                                    val mismatched = gw != null && (
                                        (g == WeightGoal.LOSE && gw >= p.weightKg) ||
                                        (g == WeightGoal.GAIN && gw <= p.weightKg)
                                    )
                                    p.copy(
                                        goal = g,
                                        weeklyChangeKg = p.weeklyChangeKg ?: 0.5,
                                        goalWeightKg = if (mismatched) null else p.goalWeightKg
                                    )
                                }
                            }
                        }
                        onDismiss()
                    }
                )
                SettingsSheet.GOAL_WEIGHT -> {
                    val kg = ui.profile?.goalWeightKg ?: (ui.profile?.weightKg ?: 70.0)
                    WeightSheet(
                        titleText = stringResource(R.string.sheet_target_weight),
                        current = kg,
                        useMetric = ui.useMetric,
                        onSave = { newKg ->
                            // Mirrors iOS ContentView.swift case .editGoalWeight: a Lose goal
                            // requires target < current weight; a Gain goal requires target >
                            // current weight. Reject mismatched targets with an alert instead
                            // of silently saving an unreachable goal.
                            val p = ui.profile
                            val current = p?.weightKg
                            val invalid = p != null && current != null && (
                                (p.goal == WeightGoal.LOSE && newKg >= current) ||
                                (p.goal == WeightGoal.GAIN && newKg <= current)
                            )
                            if (invalid) {
                                onInvalidGoalWeight(
                                    if (p!!.goal == WeightGoal.LOSE)
                                        invalidLoseMsg
                                    else
                                        invalidGainMsg
                                )
                            } else {
                                vm.updateProfile { it.copy(goalWeightKg = newKg) }
                                onDismiss()
                            }
                        }
                    )
                }
                SettingsSheet.GOAL_SPEED -> GoalSpeedSheet(
                    current = ui.profile?.weeklyChangeKg ?: 0.5,
                    goal = ui.profile?.goal ?: WeightGoal.MAINTAIN,
                    useMetric = ui.useMetric,
                    onSave = { kg -> vm.updateProfileAndRecompute { it.copy(weeklyChangeKg = kg) }; onDismiss() }
                )
                SettingsSheet.BIRTHDAY -> BirthdaySheet(
                    current = ui.profile?.birthday ?: Instant.now(),
                    onSave = { newInstant ->
                        vm.updateProfile { it.copy(birthday = newInstant) }
                        onDismiss()
                    }
                )
                SettingsSheet.APPEARANCE -> ListSheet(
                    title = stringResource(R.string.sheet_appearance),
                    items = listOf(
                        "system" to stringResource(R.string.settings_appearance_system),
                        "light" to stringResource(R.string.settings_appearance_light),
                        "dark" to stringResource(R.string.settings_appearance_dark)
                    ),
                    label = { it.second },
                    selected = { it.first == ui.appearanceMode },
                    onSelect = { vm.setAppearanceMode(it.first); onDismiss() },
                    icon = { appearanceIcon(it.first) }
                )
                SettingsSheet.WEEK_START -> ListSheet(
                    title = stringResource(R.string.sheet_week_starts),
                    items = listOf(
                        false to stringResource(R.string.settings_week_sunday),
                        true to stringResource(R.string.settings_week_monday)
                    ),
                    label = { it.second },
                    selected = { it.first == ui.weekStartsOnMonday },
                    onSelect = { vm.setWeekStartsOnMonday(it.first); onDismiss() }
                )
                SettingsSheet.CALORIES -> NutritionPickerSheet(
                    label = stringResource(R.string.macro_calories), unit = stringResource(R.string.unit_kcal),
                    currentValue = ui.profile?.effectiveCalories ?: 2000,
                    range = 800..6000, step = 50,
                    onSave = { v ->
                        vm.updateProfile { it.copy(customCalories = v) }
                        onDismiss()
                    }
                )
                SettingsSheet.PROTEIN -> NutritionPickerSheet(
                    label = stringResource(R.string.macro_protein), unit = stringResource(R.string.unit_g),
                    currentValue = ui.profile?.effectiveProtein ?: 0,
                    range = 10..500, step = 5,
                    onSave = { v ->
                        vm.updateProfile { it.copy(customProtein = v) }
                        onDismiss()
                    },
                    onResetToAuto = if (ui.profile?.isPinned(AutoBalanceMacro.PROTEIN) == true) {
                        {
                            vm.updateProfile { it.copy(customProtein = null) }
                            onDismiss()
                        }
                    } else null
                )
                SettingsSheet.CARBS -> NutritionPickerSheet(
                    label = stringResource(R.string.macro_carbs), unit = stringResource(R.string.unit_g),
                    currentValue = ui.profile?.effectiveCarbs ?: 0,
                    range = 0..800, step = 5,
                    onSave = { v ->
                        vm.updateProfile { it.copy(customCarbs = v) }
                        onDismiss()
                    },
                    onResetToAuto = if (ui.profile?.isPinned(AutoBalanceMacro.CARBS) == true) {
                        {
                            vm.updateProfile { it.copy(customCarbs = null) }
                            onDismiss()
                        }
                    } else null
                )
                SettingsSheet.FAT -> NutritionPickerSheet(
                    label = stringResource(R.string.macro_fat), unit = stringResource(R.string.unit_g),
                    currentValue = ui.profile?.effectiveFat ?: 0,
                    range = 10..300, step = 5,
                    onSave = { v ->
                        vm.updateProfile { it.copy(customFat = v) }
                        onDismiss()
                    },
                    onResetToAuto = if (ui.profile?.isPinned(AutoBalanceMacro.FAT) == true) {
                        {
                            vm.updateProfile { it.copy(customFat = null) }
                            onDismiss()
                        }
                    } else null
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun <T> ListSheet(
    title: String,
    items: List<T>,
    label: @Composable (T) -> String,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    icon: ((T) -> ImageVector?)? = null,
    subtitle: (@Composable (T) -> String?)? = null,
    footer: String? = null,
    customField: ((String) -> Unit)? = null
) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            val isSel = selected(item)
            val rowIcon = icon?.invoke(item)
            val sub = subtitle?.invoke(item)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (rowIcon != null) {
                    Icon(rowIcon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        label(item),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (!sub.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                if (isSel) {
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
    if (customField != null) {
        footer?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        var custom by remember { mutableStateOf("") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it },
            placeholder = { Text(stringResource(R.string.sheet_any_model_id)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (custom.isNotBlank()) customField(custom.trim()) },
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.action_save), color = Color.White) }
    }
}

@Composable
private fun ApiKeySheet(title: String, placeholder: String, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        placeholder = { Text(placeholder) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onSave(value) },
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie),
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.action_save), color = Color.White) }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { onSave("") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_clear_key)) }
}

@Composable
private fun TextFieldSheet(title: String, initial: String, placeholder: String, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onSave(value.trim()) },
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie),
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.action_save), color = Color.White) }
}

@Composable
private fun HeightSheet(current: Int, useMetric: Boolean, onSave: (Int) -> Unit) {
    var cm by remember(current) { mutableStateOf(current) }
    var metric by remember { mutableStateOf(useMetric) }
    Text(stringResource(R.string.sheet_height), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    UnitToggle(stringResource(R.string.unit_cm), stringResource(R.string.unit_ft_in), metric, { metric = it }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(20.dp))
    if (metric) NumericWheelPicker(cm, { cm = it }, 100, 250, stringResource(R.string.unit_cm))
    else FeetInchesWheelPicker(cm, { cm = it })
    Spacer(Modifier.height(16.dp))
    GradientSaveButton { onSave(cm) }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun WeightSheet(titleText: String, current: Double, useMetric: Boolean, onSave: (Double) -> Unit) {
    var kg by remember(current) { mutableStateOf(current) }
    var metric by remember { mutableStateOf(useMetric) }
    Text(titleText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    UnitToggle(stringResource(R.string.unit_kg), stringResource(R.string.unit_lbs), metric, { metric = it }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(20.dp))
    if (metric) {
        SplitDecimalWheelPicker(kg, { kg = it }, 30, 250, stringResource(R.string.unit_kg))
    } else {
        SplitDecimalWheelPicker(kg * 2.20462, { lbs -> kg = lbs / 2.20462 }, 66, 551, stringResource(R.string.unit_lbs))
    }
    Spacer(Modifier.height(16.dp))
    GradientSaveButton { onSave(kg) }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun BodyFatSheet(current: Double?, onSave: (Double?) -> Unit) {
    var pct by remember(current) { mutableStateOf((current ?: 0.20) * 100) }
    Text(stringResource(R.string.sheet_body_fat_percent), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    DecimalWheelPicker(pct, { pct = it }, 5.0, 60.0, 0.5, stringResource(R.string.unit_percent))
    Spacer(Modifier.height(12.dp))
    GradientSaveButton { onSave(pct / 100.0) }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { onSave(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_clear)) }
    Spacer(Modifier.height(8.dp))
}

/** Same wheel UX as BodyFatSheet, but framed as a goal — separate, optional,
 *  display-only. Seeds from the existing goal, falling back to the user's
 *  current body fat % so the wheel lands somewhere sensible on first open. */
@Composable
private fun GoalBodyFatSheet(currentGoal: Double?, currentBodyFat: Double?, onSave: (Double?) -> Unit) {
    val seed = currentGoal ?: currentBodyFat ?: 0.15
    var pct by remember(currentGoal) { mutableStateOf(seed * 100) }
    Text(stringResource(R.string.sheet_goal_body_fat), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (currentBodyFat != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.sheet_goal_body_fat_currently, (currentBodyFat * 100).toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
    Spacer(Modifier.height(12.dp))
    DecimalWheelPicker(pct, { pct = it }, 3.0, 60.0, 0.5, stringResource(R.string.unit_percent))
    Spacer(Modifier.height(12.dp))
    GradientSaveButton { onSave(pct / 100.0) }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { onSave(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_remove_goal)) }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun GoalSpeedSheet(current: Double, goal: WeightGoal, useMetric: Boolean, onSave: (Double) -> Unit) {
    Text("Weekly Change", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    val unit = if (goal == WeightGoal.LOSE) "loss" else "gain"
    val options = listOf(
        Triple(0.25, "Slow", "0.25 ${if (useMetric) "kg" else "lbs"}/week $unit"),
        Triple(0.5, "Recommended", "0.5 ${if (useMetric) "kg" else "lbs"}/week $unit"),
        Triple(1.0, "Fast", "1.0 ${if (useMetric) "kg" else "lbs"}/week $unit")
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((kg, title, subtitle) in options) {
            val isSel = kotlin.math.abs(kg - current) < 0.01
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .clickable { onSave(kg) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (isSel) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Wheel-picker sheet for a single macro / calorie target. Mirrors iOS
 * NutritionPickerSheet exactly: title, wheel picker stepped at the requested
 * step, gradient Save button, optional "Reset to Auto-balance" link when the
 * macro is currently pinned.
 */
@Composable
private fun NutritionPickerSheet(
    label: String,
    unit: String,
    currentValue: Int,
    range: IntRange,
    step: Int,
    onSave: (Int) -> Unit,
    onResetToAuto: (() -> Unit)? = null
) {
    val items = remember(range, step) { (range.first..range.last step step).toList() }
    val snapped = (currentValue / step) * step
    val initial = snapped.coerceIn(range.first, range.last).let { v ->
        items.minByOrNull { kotlin.math.abs(it - v) } ?: items.first()
    }
    var selected by remember(initial) { mutableStateOf(initial) }
    Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        com.apoorvdarshan.calorietracker.ui.components.WheelPicker(
            items = items,
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.width(120.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            unit,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CalorieGradient)
            .clickable { onSave(selected) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Save",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
    }
    if (onResetToAuto != null) {
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onResetToAuto,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Reset to Auto-balance",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MacrosSheet(
    profile: com.apoorvdarshan.calorietracker.models.UserProfile?,
    onSaveCalories: (Int?) -> Unit,
    onSaveMacro: (AutoBalanceMacro, Int?) -> Unit,
    onClearPin: (AutoBalanceMacro) -> Unit
) {
    profile ?: return
    var caloriesText by remember(profile) { mutableStateOf(profile.effectiveCalories.toString()) }
    var proteinText by remember(profile) { mutableStateOf(profile.effectiveProtein.toString()) }
    var carbsText by remember(profile) { mutableStateOf(profile.effectiveCarbs.toString()) }
    var fatText by remember(profile) { mutableStateOf(profile.effectiveFat.toString()) }
    Text("Macros", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        "Pin up to 2 macros to exact grams. Unpinned ones auto-balance from remaining calories.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(12.dp))
    MacroField("Calories", caloriesText, { caloriesText = it }, "kcal") {
        caloriesText.toIntOrNull()?.let { onSaveCalories(it) }
    }
    Spacer(Modifier.height(6.dp))
    MacroField(
        label = "Protein (${if (profile.isPinned(AutoBalanceMacro.PROTEIN)) "pinned" else "auto"})",
        value = proteinText,
        onChange = { proteinText = it },
        unit = "g",
        pinned = profile.isPinned(AutoBalanceMacro.PROTEIN),
        onClearPin = { onClearPin(AutoBalanceMacro.PROTEIN) }
    ) { proteinText.toIntOrNull()?.let { onSaveMacro(AutoBalanceMacro.PROTEIN, it) } }
    Spacer(Modifier.height(6.dp))
    MacroField(
        label = "Carbs (${if (profile.isPinned(AutoBalanceMacro.CARBS)) "pinned" else "auto"})",
        value = carbsText,
        onChange = { carbsText = it },
        unit = "g",
        pinned = profile.isPinned(AutoBalanceMacro.CARBS),
        onClearPin = { onClearPin(AutoBalanceMacro.CARBS) }
    ) { carbsText.toIntOrNull()?.let { onSaveMacro(AutoBalanceMacro.CARBS, it) } }
    Spacer(Modifier.height(6.dp))
    MacroField(
        label = "Fat (${if (profile.isPinned(AutoBalanceMacro.FAT)) "pinned" else "auto"})",
        value = fatText,
        onChange = { fatText = it },
        unit = "g",
        pinned = profile.isPinned(AutoBalanceMacro.FAT),
        onClearPin = { onClearPin(AutoBalanceMacro.FAT) }
    ) { fatText.toIntOrNull()?.let { onSaveMacro(AutoBalanceMacro.FAT, it) } }
}

@Composable
private fun MacroField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    unit: String,
    pinned: Boolean = false,
    onClearPin: (() -> Unit)? = null,
    onPin: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            suffix = { Text(unit) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { if (pinned) onClearPin?.invoke() else onPin() }) {
            Text(if (pinned) "Clear" else "Pin", color = AppColors.Calorie)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    // iOS uses sentence-case section titles ("Personal Info", "Goals & Nutrition")
    // in a small grey caption. Match that — no uppercase transform.
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    icon: ImageVector? = null,
    // iOS `.menu` Picker rows render a `chevron.up.chevron.down` instead of a
    // right-chevron to signal the inline dropdown affordance. Pass inlineMenu=true
    // for Gender, Weight Goal, and Activity Level.
    inlineMenu: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = AppColors.Calorie,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
        }
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Icon(
            if (inlineMenu) Icons.Filled.UnfoldMore else Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = if (inlineMenu) Modifier.size(18.dp) else Modifier
        )
    }
}

/**
 * Verbatim port of iOS `macroRow(label:icon:macro:value:sheet:)` in
 * ContentView.swift's ProfileView. Uses the DataUsage circle icon (matches
 * iOS circle.dotted) on the left, the macro label, and on the right
 *   "{value}g"             when pinned (custom value)
 *   "{value}g · auto"      when auto-balanced
 * followed by a lock icon — Filled.Lock (pink) when pinned, Outlined.LockOpen
 * (gray) when not.
 */
@Composable
private fun MacroSettingRow(
    label: String,
    value: Int,
    pinned: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.DataUsage,
            contentDescription = null,
            tint = AppColors.Calorie,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (pinned) "${value}g" else "${value}g · auto",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            if (pinned) Icons.Filled.Lock else Icons.Outlined.LockOpen,
            contentDescription = if (pinned) "Pinned" else "Auto",
            tint = if (pinned) AppColors.Calorie
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    icon: ImageVector? = null,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = AppColors.Calorie,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
        }
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun feetInchesLabel(cm: Int): String {
    val totalInches = (cm / 2.54).toInt()
    val feet = totalInches / 12
    val inches = totalInches % 12
    return "$feet' $inches\""
}

private val birthdayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

private fun birthdayDisplay(profile: UserProfile): String {
    val date = profile.birthday.atZone(ZoneId.systemDefault()).toLocalDate()
    return "${date.format(birthdayFormatter)} (age ${profile.age})"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdaySheet(current: Instant, onSave: (Instant) -> Unit) {
    // Material3 DatePicker stores selection as UTC-midnight millis. We store
    // birthdays as a local-zone Instant. Round-trip both sides through the
    // user's local date to avoid an off-by-one when the user is east of UTC.
    val localDate = current.atZone(ZoneId.systemDefault()).toLocalDate()
    val initialMillis = localDate.atStartOfDay(java.time.ZoneOffset.UTC)
        .toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    Text("Birthday", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    DatePicker(
        state = state,
        title = null,
        headline = null,
        showModeToggle = false,
        colors = DatePickerDefaults.colors(
            selectedDayContainerColor = AppColors.Calorie,
            todayDateBorderColor = AppColors.Calorie,
            currentYearContentColor = AppColors.Calorie,
            selectedYearContainerColor = AppColors.Calorie
        )
    )
    Spacer(Modifier.height(12.dp))
    GradientSaveButton {
        val millis = state.selectedDateMillis ?: return@GradientSaveButton
        // Picker millis is UTC-midnight of the selected calendar day —
        // pull the LocalDate via UTC, then convert to local-zone Instant.
        val newDate = Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val newInstant = newDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
        onSave(newInstant)
    }
    Spacer(Modifier.height(8.dp))
}

// Closest Material mappings for the iOS SF Symbols used in picker rows.
private fun genderIcon(g: Gender): ImageVector = when (g) {
    Gender.MALE -> Icons.Outlined.Male
    Gender.FEMALE -> Icons.Outlined.Female
    Gender.OTHER -> Icons.Outlined.Wc
}

private fun activityIcon(a: ActivityLevel): ImageVector = when (a) {
    ActivityLevel.SEDENTARY -> Icons.Outlined.SelfImprovement
    ActivityLevel.LIGHT -> Icons.AutoMirrored.Outlined.DirectionsWalk
    ActivityLevel.MODERATE -> Icons.AutoMirrored.Outlined.DirectionsRun
    ActivityLevel.ACTIVE -> Icons.Outlined.LocalDining
    ActivityLevel.VERY_ACTIVE -> Icons.Outlined.FitnessCenter
    ActivityLevel.EXTRA_ACTIVE -> Icons.Outlined.SportsMartialArts
}

private fun goalIcon(g: WeightGoal): ImageVector = when (g) {
    WeightGoal.LOSE -> Icons.AutoMirrored.Filled.TrendingDown
    WeightGoal.MAINTAIN -> Icons.AutoMirrored.Filled.TrendingFlat
    WeightGoal.GAIN -> Icons.AutoMirrored.Outlined.TrendingUp
}

private fun appearanceIcon(key: String): ImageVector = when (key) {
    "light" -> Icons.Outlined.LightMode
    "dark" -> Icons.Outlined.DarkMode
    else -> Icons.Outlined.SettingsBrightness
}

/**
 * Pink-gradient capsule "Save" button matching the iOS picker sheets
 * (`LinearGradient(colors: AppColors.calorieGradient)` over a 14dp rounded
 * rectangle, white semibold label).
 */
@Composable
private fun GradientSaveButton(
    text: String = "Save",
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val brush = Brush.linearGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd))
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) brush else Brush.linearGradient(listOf(AppColors.Calorie.copy(alpha = 0.4f), AppColors.Calorie.copy(alpha = 0.4f))))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}
