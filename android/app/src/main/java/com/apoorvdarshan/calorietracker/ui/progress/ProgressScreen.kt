package com.apoorvdarshan.calorietracker.ui.progress

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import com.apoorvdarshan.calorietracker.models.BodyMeasurement
import com.apoorvdarshan.calorietracker.models.Gender
import com.apoorvdarshan.calorietracker.ui.components.DecimalWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.components.FudGlassPrimaryButton
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextButton
import com.apoorvdarshan.calorietracker.ui.components.FudIconBubble
import com.apoorvdarshan.calorietracker.ui.components.SplitDecimalWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.UnitToggle
import com.apoorvdarshan.calorietracker.ui.settings.NutritionPickerSheet
import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.HomeTopNutrient
import com.apoorvdarshan.calorietracker.models.MacroValueFormatter
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Verbatim port of ios/calorietracker/ContentView.swift > struct ProgressTabView,
 * including the per-section components in ProgressComponents.swift.
 *
 * Layout (top -> bottom):
 *   1. Segmented TimeRange picker — 1W / 1M / 3M / 6M / 1Y / All
 *   2. WeightChartSection — Weight title + Log Weight pill + StatBadges
 *      (Current, Goal, Net Change, Average) + line chart with green dashed goal rule
 *   3. WeightHistoryLink — only shown if any weight entries exist; shows
 *      count + chevron, opens AllWeightHistorySheet. BodyFatHistoryLink
 *      mirrors it for body-fat entries, opening AllBodyFatHistorySheet
 *   4. CalorieChartSection — Calories title + Avg badge + bar chart of
 *      per-day calories with calorieGradient bars (dimmed below goal,
 *      pink above goal — same as iOS)
 *   5. MacroAveragesSection — averages over the selected time range,
 *      one MacroProgressRow per macro
 */
enum class TimeRange(@StringRes val labelRes: Int, val days: Int) {
    WEEK(R.string.progress_range_week, 7),
    MONTH(R.string.progress_range_month, 30),
    THREE_MONTHS(R.string.progress_range_3m, 90),
    SIX_MONTHS(R.string.progress_range_6m, 180),
    YEAR(R.string.progress_range_year, 365),
    ALL_TIME(R.string.progress_range_all, 3650);

    fun dateRange(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> {
        val start = today.minusDays((days - 1).toLong())
        return start to today
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(container: AppContainer) {
    var destination by rememberSaveable { mutableStateOf(ProgressDestination.MY_PROGRESS) }
    val destinationState = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        ProgressDestinationSelector(
            selected = destination,
            onSelect = { destination = it },
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)
        )
        Box(Modifier.weight(1f)) {
            destinationState.SaveableStateProvider(destination.name) {
                when (destination) {
                    ProgressDestination.MY_PROGRESS -> MyProgressContent(container)
                    ProgressDestination.WEEKLY_CHALLENGE -> WeeklyChallengeScreen(container)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyProgressContent(container: AppContainer) {
    val vm: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val foods by container.foodRepository.entries.collectAsState(initial = emptyList())
    val weightUnit by container.prefs.weightUnit.collectAsState(initial = "kg")
    val optionalNutrientGoals by container.prefs.optionalNutrientGoals.collectAsState(
        initial = OptionalNutrientGoals.Default
    )
    val weightMetric = weightUnit == "kg"

    var range by rememberSaveable { mutableStateOf(TimeRange.WEEK) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddBodyFatDialog by remember { mutableStateOf(false) }
    var showAllWeights by remember { mutableStateOf(false) }
    var showAllBodyFats by remember { mutableStateOf(false) }
    var showAllWorkouts by remember { mutableStateOf(false) }
    var workoutPendingDelete by remember { mutableStateOf<WorkoutSession?>(null) }
    var selectedMetric by rememberSaveable { mutableStateOf(ProgressMetric.WEIGHT) }

    // Filter weights + body fats to range
    val (rangeStartDate, rangeEndDate) = range.dateRange()
    val zone = ZoneId.systemDefault()
    val rangeStart = rangeStartDate.atStartOfDay(zone).toInstant()
    val rangeEndExclusive = rangeEndDate.plusDays(1).atStartOfDay(zone).toInstant()
    val filteredWeights = ui.entries
        .filter { it.date >= rangeStart && it.date < rangeEndExclusive }
        .sortedBy { it.date }
    val filteredBodyFats = ui.bodyFatEntries
        .filter { it.date >= rangeStart && it.date < rangeEndExclusive }
        .sortedBy { it.date }
    val reliableWorkoutBurnSessions = remember(ui.workoutBurnSessions) {
        ui.workoutBurnSessions.filter { session ->
            session.caloriesBurned?.let { it in 1..5_000 } == true &&
                runCatching { LocalDate.parse(session.diaryDateKey) }.isSuccess
        }
    }
    val filteredWorkouts = reliableWorkoutBurnSessions.filter { session ->
        runCatching { LocalDate.parse(session.diaryDateKey) }.getOrNull()?.let { date ->
            date in rangeStartDate..rangeEndDate
        } == true
    }
    // Body Fat segment only renders when the user has opted in — same visibility
    // rule as iOS: hidden entirely for users who never set body fat OR a goal.
    val bodyFatAvailable = ui.bodyFatEntries.isNotEmpty()
        || ui.profile?.bodyFatPercentage != null
        || ui.profile?.goalBodyFatPercentage != null
    val availableMetrics = availableProgressMetrics(
        bodyFatAvailable = bodyFatAvailable,
        workoutHistoryAvailable = reliableWorkoutBurnSessions.isNotEmpty()
    )
    LaunchedEffect(availableMetrics, selectedMetric) {
        if (selectedMetric !in availableMetrics) selectedMetric = ProgressMetric.WEIGHT
    }

    // Nutrition aggregates load on a background dispatcher. Changing `range`
    // cancels the previous LaunchedEffect so the picker stays interruptible.
    var foodRangeStats by remember { mutableStateOf<ProgressFoodRangeStats?>(null) }
    var isLoadingFoodRangeStats by remember { mutableStateOf(false) }
    LaunchedEffect(foods, range, ui.profile, optionalNutrientGoals) {
        isLoadingFoodRangeStats = true
        foodRangeStats = null
        val computed = withContext(Dispatchers.Default) {
            computeProgressFoodRangeStats(
                foods = foods,
                dayCount = range.days,
                zone = zone,
                profile = ui.profile,
                optionalGoals = optionalNutrientGoals
            )
        }
        foodRangeStats = computed
        isLoadingFoodRangeStats = false
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = BottomNavScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. Segmented TimeRange picker
            item { TimeRangePicker(selected = range, onSelect = { range = it }) }

            // 2. Progress metric selector. Optional metrics keep their stable
            //    preference order.
            item {
                ProgressMetricSelector(
                    metrics = availableMetrics,
                    selected = selectedMetric,
                    onSelect = { selectedMetric = it }
                )
            }

            // 3. Selected metric chart.
            item {
                CardSection {
                    when (selectedMetric) {
                        ProgressMetric.WEIGHT -> WeightSection(
                            entries = filteredWeights,
                            latest = ui.entries.maxByOrNull { it.date },
                            goalKg = ui.profile?.goalWeightKg,
                            useMetric = weightMetric,
                            onLogWeight = { showAddDialog = true }
                        )
                        ProgressMetric.BODY_FAT -> BodyFatSection(
                            entries = filteredBodyFats,
                            latest = ui.bodyFatEntries.maxByOrNull { it.date }?.bodyFatFraction
                                ?: ui.profile?.bodyFatPercentage,
                            goalFraction = ui.profile?.goalBodyFatPercentage,
                            onLogBodyFat = { showAddBodyFatDialog = true }
                        )
                        ProgressMetric.WORKOUTS -> WorkoutProgressSection(filteredWorkouts)
                    }
                }
            }

            // 4. The history affordance follows the selected metric instead of stacking
            //    every history row and making Progress unnecessarily tall.
            when (selectedMetric) {
                ProgressMetric.WEIGHT -> if (ui.entries.isNotEmpty()) {
                    item { WeightHistoryLink(ui.entries.size) { showAllWeights = true } }
                }
                ProgressMetric.BODY_FAT -> if (ui.bodyFatEntries.isNotEmpty()) {
                    item { BodyFatHistoryLink(ui.bodyFatEntries.size) { showAllBodyFats = true } }
                }
                ProgressMetric.WORKOUTS -> if (reliableWorkoutBurnSessions.isNotEmpty()) {
                    item {
                        WorkoutHistoryLink(reliableWorkoutBurnSessions.size) { showAllWorkouts = true }
                    }
                }
            }

            // 5–7. Nutrition sections: show a loading card while computing so
            // range switches stay instant and can be interrupted mid-load.
            if (isLoadingFoodRangeStats && foodRangeStats == null) {
                item {
                    CardSection { ProgressNutritionLoadingCard() }
                }
            } else {
                val stats = foodRangeStats
                if (stats != null) {
                    item {
                        CardSection {
                            Box {
                                CalorieSection(
                                    dailyCalories = stats.dailyCalories,
                                    calorieGoal = ui.profile?.effectiveCalories ?: 2000
                                )
                                if (isLoadingFoodRangeStats) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = AppColors.Calorie
                                    )
                                }
                            }
                        }
                    }

                    ui.profile?.let { p ->
                        item {
                            CardSection {
                                MacroAveragesSection(
                                    avgProtein = stats.avgProtein,
                                    avgCarbs = stats.avgCarbs,
                                    avgFat = stats.avgFat,
                                    proteinGoal = p.effectiveProtein,
                                    carbsGoal = p.effectiveCarbs,
                                    fatGoal = p.effectiveFat
                                )
                            }
                        }
                    }

                    if (stats.nutrientItems.isNotEmpty()) {
                        item {
                            CardSection {
                                NutrientAveragesSection(items = stats.nutrientItems)
                            }
                        }
                    }
                } else {
                    item {
                        CardSection { ProgressNutritionLoadingCard() }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        // Seed the wheel from the most recent entry, or fall back to the profile
        // weight, or a sane default. Avoids the picker landing on its min row
        // (30 kg) when the user has never logged before.
        val seedKg = ui.entries.maxByOrNull { it.date }?.weightKg
            ?: ui.profile?.weightKg
            ?: 70.0
        val scope = rememberCoroutineScope()
        AddWeightDialog(
            useMetric = weightMetric,
            initialKg = seedKg,
            onUnitChange = { metric ->
                scope.launch { container.prefs.setWeightUnit(if (metric) "kg" else "lbs") }
            },
            onDismiss = { showAddDialog = false }
        ) { kg ->
            vm.addWeight(kg); showAddDialog = false
        }
    }
    if (showAddBodyFatDialog) {
        // Same seeding chain as weight — last entry → profile → 20% fallback.
        val seedFraction = ui.bodyFatEntries.maxByOrNull { it.date }?.bodyFatFraction
            ?: ui.profile?.bodyFatPercentage
            ?: 0.20
        AddBodyFatDialog(initialFraction = seedFraction, onDismiss = { showAddBodyFatDialog = false }) { fraction ->
            vm.addBodyFat(fraction); showAddBodyFatDialog = false
        }
    }
    if (showAllWeights) {
        AllWeightHistorySheet(
            entries = ui.entries.sortedByDescending { it.date },
            useMetric = weightMetric,
            onDelete = vm::deleteWeight,
            onDismiss = { showAllWeights = false }
        )
    }
    if (showAllBodyFats) {
        AllBodyFatHistorySheet(
            entries = ui.bodyFatEntries.sortedByDescending { it.date },
            onDelete = vm::deleteBodyFat,
            onDismiss = { showAllBodyFats = false }
        )
    }
    if (showAllWorkouts) {
        AllWorkoutHistorySheet(
            entries = reliableWorkoutBurnSessions,
            onRequestDelete = { workoutPendingDelete = it },
            onDismiss = { showAllWorkouts = false }
        )
    }
    workoutPendingDelete?.let { session ->
        FudGlassDialog(onDismissRequest = { workoutPendingDelete = null }) {
            Text(stringResource(R.string.progress_workout_delete_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.progress_workout_delete_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = {
                    vm.deleteWorkoutBurn(session.id)
                    workoutPendingDelete = null
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { workoutPendingDelete = null },
                destructive = true
            )
        }
    }
    if (ui.goalReached) {
        FudGlassDialog(onDismissRequest = { vm.dismissGoalReached() }) {
            Text(stringResource(R.string.progress_goal_reached_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.progress_goal_reached_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.action_keep_going),
                onPrimary = { vm.dismissGoalReached() }
            )
        }
    }
}

// ── Components ──────────────────────────────────────────────────────

@Composable
private fun TimeRangePicker(selected: TimeRange, onSelect: (TimeRange) -> Unit) {
    // Match iOS `.pickerStyle(.segmented)` for 1W / 1M / … — elevated selected
    // pill on a muted track, not the brand gradient chips.
    IosStyleSegmentedControl(
        options = TimeRange.values().toList(),
        selected = selected,
        label = { stringResource(it.labelRes) },
        onSelect = onSelect
    )
}

/**
 * Plain iOS-style segmented control used for time range and progress metrics.
 */
@Composable
private fun <T> IosStyleSegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackFill = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
    } else {
        Color(0xFFE5DAD3).copy(alpha = 0.88f)
    }
    val selectedFill = if (isDark) {
        Color.White.copy(alpha = 0.18f)
    } else {
        Color.White
    }
    val shadowAlpha = if (isDark) 0.16f else 0.06f
    Row(
        modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDark) 10.dp else 4.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = shadowAlpha),
                spotColor = Color.Black.copy(alpha = shadowAlpha)
            )
            .clip(shape)
            .background(trackFill)
            .border(
                0.7.dp,
                Color.White.copy(alpha = if (isDark) 0.10f else 0.45f),
                shape
            )
            .padding(3.dp)
            .selectableGroup()
    ) {
        options.forEach { option ->
            val isSel = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(13.dp))
                    .then(
                        if (isSel) {
                            Modifier
                                .shadow(
                                    elevation = if (isDark) 2.dp else 3.dp,
                                    shape = RoundedCornerShape(13.dp),
                                    ambientColor = Color.Black.copy(alpha = if (isDark) 0.25f else 0.08f),
                                    spotColor = Color.Black.copy(alpha = if (isDark) 0.25f else 0.08f)
                                )
                                .background(selectedFill)
                        } else {
                            Modifier.background(Color.Transparent)
                        }
                    )
                    .selectable(
                        selected = isSel,
                        role = Role.Tab,
                        onClick = { onSelect(option) }
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    color = if (isSel) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    }
                )
            }
        }
    }
}

@Composable
private fun CardSection(content: @Composable () -> Unit) {
    FudGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        padding = 16.dp
    ) { content() }
}

@Composable
private fun WeightSection(
    entries: List<WeightEntry>,
    latest: WeightEntry?,
    goalKg: Double?,
    useMetric: Boolean,
    onLogWeight: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // iOS .font(.headline) = 17sp semibold rounded.
            Text(stringResource(R.string.progress_weight_section), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.clickable(onClick = onLogWeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.AddCircle, null, tint = AppColors.Calorie, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.progress_log_weight), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.Calorie)
            }
        }
        if (entries.isEmpty()) {
            // iOS emptyState: centered secondary text inside the card.
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.progress_log_first_weight),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        } else {
            val sortedEntries = entries.sortedBy { it.date }
            val netChangeKg = sortedEntries.last().weightKg - sortedEntries.first().weightKg
            val averageKg = sortedEntries.map { it.weightKg }.average()
            val currentLabel = stringResource(R.string.progress_stat_current)
            val goalLabel = stringResource(R.string.progress_stat_goal)
            val netChangeLabel = stringResource(R.string.progress_stat_net_change)
            val averageLabel = stringResource(R.string.progress_stat_average)
            StatBadgeRow(
                buildList {
                    latest?.let {
                        add(currentLabel to formatWeight(it.weightKg, useMetric))
                    }
                    goalKg?.let {
                        add(goalLabel to formatWeight(it, useMetric))
                    }
                    add(netChangeLabel to formatWeightChange(netChangeKg, useMetric))
                    add(averageLabel to formatWeight(averageKg, useMetric))
                }
            )
            WeightChartCanvas(entries = entries, goalKg = goalKg, useMetric = useMetric)
        }
    }
}

@Composable
private fun StatBadgeRow(items: List<Pair<String, String>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { (label, value) ->
            StatBadge(
                label = label,
                value = value,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatBadge(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Calorie.copy(alpha = 0.055f))
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 15.sp, stepSize = 0.5.sp)
        )
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 11.sp, stepSize = 0.5.sp)
        )
    }
}

@Composable
private fun WeightChartCanvas(entries: List<WeightEntry>, goalKg: Double?, useMetric: Boolean) {
    val displayKg = { kg: Double -> if (useMetric) kg else kg * 2.20462 }
    val sortedEntries = remember(entries) { entries.sortedBy { it.date } }
    val displayWeights = remember(sortedEntries, goalKg, useMetric) {
        sortedEntries.map { if (useMetric) it.weightKg else it.weightKg * 2.20462 } +
            listOfNotNull(goalKg?.let { if (useMetric) it else it * 2.20462 })
    }
    val minW = displayWeights.min()
    val maxW = displayWeights.max()
    val pad = maxOf((maxW - minW) * 0.15, 2.0)
    val yMin = minW - pad
    val yMax = maxW + pad
    val tStart = sortedEntries.first().date.toEpochMilli()
    val tEnd = sortedEntries.last().date.toEpochMilli()
    val singleEntry = sortedEntries.size == 1
    val tRange = maxOf(1L, tEnd - tStart)
    val goalLineColor = Color(0xFF34C759).copy(alpha = 0.7f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    val secondaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val chartSurfaceColor = MaterialTheme.colorScheme.surface
    val ticks = remember(yMin, yMax) { niceAxisTicks(yMin, yMax, count = 5) }
    val zone = ZoneId.systemDefault()
    // Labels pick up the year once a longer range crosses a calendar-year
    // boundary — "Jul 2024" instead of an ambiguous "Jul 3" (same iOS rule).
    val spanDays = maxOf(1L, (tEnd - tStart) / 86_400_000L)
    val showsYear = spanDays > 150 &&
        Instant.ofEpochMilli(tStart).atZone(zone).year != Instant.ofEpochMilli(tEnd).atZone(zone).year
    val xLabelFmt = DateTimeFormatter.ofPattern(if (showsYear) "MMM yyyy" else "MMM d", Locale.US).withZone(zone)
    // Dense ranges plot bucket averages so the line stays a readable curve;
    // dots only render while each reading is still distinguishable.
    val points = remember(sortedEntries, useMetric) {
        downsampleTrend(
            sortedEntries.map {
                TrendPoint(it.date.toEpochMilli(), if (useMetric) it.weightKg else it.weightKg * 2.20462)
            }
        )
    }
    val showsDots = points.size <= 31
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val selectedPoint = selectedIndex?.let(points::getOrNull)
    val inspectorDateFormat = remember {
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())
    }

    Row(Modifier.fillMaxWidth().height(190.dp)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.Calorie.copy(alpha = 0.025f))
        ) {
            val canvasWidthPx = with(density) { maxWidth.toPx() }
            val selectedTargetX = selectedPoint?.let { point ->
                if (singleEntry) canvasWidthPx / 2f
                else ((point.timeMs - tStart).toDouble() / tRange * canvasWidthPx).toFloat()
            } ?: 0f
            val animatedInspectorX by animateFloatAsState(
                targetValue = selectedTargetX,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "weightInspectorX"
            )

            fun selectNearest(x: Float) {
                if (points.isEmpty() || canvasWidthPx <= 0f) return
                val clampedX = x.coerceIn(0f, canvasWidthPx)
                val nearest = points.indices.minByOrNull { index ->
                    val pointX = if (singleEntry) canvasWidthPx / 2f else
                        ((points[index].timeMs - tStart).toDouble() / tRange * canvasWidthPx).toFloat()
                    kotlin.math.abs(pointX - clampedX)
                } ?: return
                if (nearest != selectedIndex) {
                    selectedIndex = nearest
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(points, canvasWidthPx) {
                        // Gesture position stays local to the pointer coroutine.
                        // Making every pixel of movement Compose state caused a
                        // full chart recomposition and severe input latency on
                        // 6M / 1Y / All ranges.
                        var gestureX = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                gestureX = offset.x
                                selectNearest(gestureX)
                            },
                            onDragEnd = { selectedIndex = null },
                            onDragCancel = { selectedIndex = null },
                            onHorizontalDrag = { change, dragAmount ->
                                gestureX = (gestureX + dragAmount).coerceIn(0f, size.width.toFloat())
                                selectNearest(gestureX)
                                change.consume()
                            }
                        )
                    }
            ) {
                val w = size.width; val h = size.height
                // Horizontal grid + tick marks
                ticks.forEach { tick ->
                    val y = h - (((tick - yMin) / (yMax - yMin)).toFloat() * h)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y), end = Offset(w, y),
                        strokeWidth = 1f
                    )
                }
                // Vertical grid (4 columns) — faint dashed
                for (i in 0..4) {
                    val x = (i.toFloat() / 4f) * w
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f), end = Offset(x, h),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
                    )
                }
                goalKg?.let { gk ->
                    val gv = displayKg(gk)
                    val y = h - (((gv - yMin) / (yMax - yMin)).toFloat() * h)
                    drawLine(
                        color = goalLineColor,
                        start = Offset(0f, y), end = Offset(w, y),
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
                    )
                }
                val offsets = points.map { p ->
                    Offset(
                        if (singleEntry) w / 2f
                        else ((p.timeMs - tStart).toDouble() / tRange * w).toFloat(),
                        h - (((p.value - yMin) / (yMax - yMin)).toFloat() * h)
                    )
                }
                // clipRect: the smoothed curve can overshoot the value range a
                // touch between points — keep it inside the plot like iOS .clipped()
                clipRect {
                    drawPath(
                        smoothTrendAreaPath(offsets, h),
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                AppColors.Calorie.copy(alpha = 0.12f),
                                AppColors.Calorie.copy(alpha = 0.01f)
                            ),
                            startY = 0f,
                            endY = h
                        )
                    )
                    drawPath(
                        smoothTrendPath(offsets),
                        AppColors.Calorie,
                        style = Stroke(width = 6f)
                    )
                    if (showsDots) {
                        offsets.forEach { drawCircle(AppColors.Calorie, radius = 6f, center = it) }
                    }

                    selectedPoint?.let { point ->
                        val pointY = h - (((point.value - yMin) / (yMax - yMin)).toFloat() * h)
                        drawLine(
                            color = AppColors.Calorie.copy(alpha = 0.62f),
                            start = Offset(animatedInspectorX, 0f),
                            end = Offset(animatedInspectorX, h),
                            strokeWidth = 2.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f))
                        )
                        drawCircle(chartSurfaceColor, radius = 11f, center = Offset(animatedInspectorX, pointY))
                        drawCircle(AppColors.Calorie, radius = 6f, center = Offset(animatedInspectorX, pointY))
                    }
                }
            }

            selectedPoint?.let { point ->
                val tooltipWidth = 116.dp
                val tooltipWidthPx = with(density) { tooltipWidth.toPx() }
                val tooltipLeft = (animatedInspectorX - tooltipWidthPx / 2f)
                    .coerceIn(0f, (canvasWidthPx - tooltipWidthPx).coerceAtLeast(0f))
                Column(
                    modifier = Modifier
                        .offset { IntOffset(tooltipLeft.roundToInt(), with(density) { 6.dp.roundToPx() }) }
                        .width(tooltipWidth)
                        .shadow(8.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                        .border(0.75.dp, AppColors.Calorie.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        inspectorDateFormat.format(Instant.ofEpochMilli(point.timeMs)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = secondaryColor,
                        maxLines = 1
                    )
                    Text(
                        String.format(Locale.US, "%.1f %s", point.value, if (useMetric) "kg" else "lbs"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
        // Y-axis labels on the right
        Column(
            Modifier.width(36.dp).fillMaxSize().padding(start = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            ticks.reversed().forEach { tick ->
                Text(
                    formatTick(tick),
                    fontSize = 11.sp,
                    color = secondaryColor
                )
            }
        }
    }
    TrendXAxisLabels(tStart, tEnd, showsYear, singleEntry, xLabelFmt, secondaryColor, endPadding = 36.dp)
}

/** X-axis labels under a trend chart, matching the label density of the iOS
 *  charts: five dates aligned with the canvas' quarter gridlines, or
 *  first/middle/last with the year on multi-year spans (wider "MMM yyyy"
 *  labels need the extra room). */
@Composable
private fun TrendXAxisLabels(
    tStart: Long,
    tEnd: Long,
    showsYear: Boolean,
    singleEntry: Boolean,
    fmt: DateTimeFormatter,
    color: Color,
    endPadding: Dp
) {
    val labels = when {
        singleEntry -> listOf(fmt.format(Instant.ofEpochMilli(tStart)))
        showsYear -> listOf(tStart, (tStart + tEnd) / 2, tEnd)
            .map { fmt.format(Instant.ofEpochMilli(it)) }
        else -> (0..4)
            .map { i -> fmt.format(Instant.ofEpochMilli(tStart + (tEnd - tStart) * i / 4)) }
            // Spans of a couple days format to repeating dates — drop the dupes.
            .let { all -> all.filterIndexed { i, label -> i == 0 || label != all[i - 1] } }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, end = endPadding),
        horizontalArrangement = if (labels.size == 1) Arrangement.Center else Arrangement.SpaceBetween
    ) {
        labels.forEach { Text(it, fontSize = 11.sp, color = color) }
    }
}

/** One plotted point on a trend chart — either a raw entry or the average of
 *  a date bucket when the range is too dense to draw every reading. Mirrors
 *  the iOS TrendPoint/downsampled helpers in ProgressComponents.swift. */
private data class TrendPoint(val timeMs: Long, val value: Double)

/** Averages a date-sorted series into equal date buckets once it outgrows
 *  [maxPoints]. Hundreds of raw readings drew every dot on top of its
 *  neighbours and turned the line into a solid band — ~60 bucket averages
 *  keep the trend shape readable. Sparse series pass through untouched. */
private fun downsampleTrend(points: List<TrendPoint>, maxPoints: Int = 60): List<TrendPoint> {
    if (points.size < 2) return points
    val dayMs = 86_400_000L
    val first = points.first().timeMs
    val spanDays = maxOf(1L, (points.last().timeMs - first) / dayMs)
    // Long ranges need fewer interaction targets than short ranges. This keeps
    // the visual trend intact while avoiding dozens of redraws + haptics during
    // a single scrub across a two-year series.
    val adaptiveLimit = minOf(maxPoints, when {
        spanDays <= 45 -> 60
        spanDays <= 100 -> 48
        spanDays <= 200 -> 36
        spanDays <= 400 -> 30
        else -> 24
    })
    if (points.size <= adaptiveLimit) return points
    val bucketMs = Math.ceil(spanDays.toDouble() / adaptiveLimit).toLong().coerceAtLeast(1L) * dayMs
    return points
        .groupBy { (it.timeMs - first) / bucketMs }
        .toSortedMap()
        .values
        .map { bucket ->
            TrendPoint(
                timeMs = bucket.map { it.timeMs }.average().toLong(),
                value = bucket.map { it.value }.average()
            )
        }
}

/** Catmull-Rom smoothed path through [points] — same curve the iOS charts
 *  get from interpolationMethod(.catmullRom). */
private fun smoothTrendPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val p0 = points[maxOf(i - 2, 0)]
        val p1 = points[i - 1]
        val p2 = points[i]
        val p3 = points[minOf(i + 1, points.size - 1)]
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y
        )
    }
    return path
}

/** The same smoothed trend closed against the plot floor, used for the quiet
 *  accent wash beneath weight and body-fat lines. */
private fun smoothTrendAreaPath(points: List<Offset>, bottomY: Float): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, bottomY)
    path.lineTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val p0 = points[maxOf(i - 2, 0)]
        val p1 = points[i - 1]
        val p2 = points[i]
        val p3 = points[minOf(i + 1, points.size - 1)]
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y
        )
    }
    path.lineTo(points.last().x, bottomY)
    path.close()
    return path
}

/** Compute "nice" axis tick values across [min, max] with approx [count] divisions. */
private fun niceAxisTicks(min: Double, max: Double, count: Int): List<Double> {
    val range = max - min
    if (range <= 0) return listOf(min)
    val rawStep = range / (count - 1)
    val mag = Math.pow(10.0, Math.floor(Math.log10(rawStep)))
    val normalized = rawStep / mag
    val niceStep = when {
        normalized < 1.5 -> 1.0
        normalized < 3.0 -> 2.0
        normalized < 7.0 -> 5.0
        else -> 10.0
    } * mag
    val firstTick = Math.ceil(min / niceStep) * niceStep
    val out = mutableListOf<Double>()
    var v = firstTick
    while (v <= max + 1e-9) {
        out.add(v)
        v += niceStep
    }
    return out
}

private fun formatTick(value: Double): String =
    if (value >= 1000) String.format(Locale.US, "%,d", value.toInt())
    else if (value == value.toInt().toDouble()) value.toInt().toString()
    else String.format(Locale.US, "%.1f", value)

@Composable
private fun WeightHistoryLink(count: Int, onClick: () -> Unit) {
    FudGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 18.dp,
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FudIconBubble(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                size = 28.dp,
                iconSize = 16.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.progress_weight_history), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    pluralStringResource(R.plurals.progress_history_count_format, count, count),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun BodyFatHistoryLink(count: Int, onClick: () -> Unit) {
    FudGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 18.dp,
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FudIconBubble(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                size = 28.dp,
                iconSize = 16.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.progress_body_fat_history), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    pluralStringResource(R.plurals.progress_history_count_format, count, count),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun WorkoutHistoryLink(count: Int, onClick: () -> Unit) {
    FudGlassSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        cornerRadius = 18.dp,
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FudIconBubble(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                size = 28.dp,
                iconSize = 16.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.progress_workout_history),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    pluralStringResource(
                        R.plurals.progress_workout_history_count_format,
                        count,
                        count
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CalorieSection(dailyCalories: List<Pair<LocalDate, Int>>, calorieGoal: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.progress_calories_section), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (dailyCalories.isNotEmpty()) {
                val avg = dailyCalories.sumOf { it.second } / dailyCalories.size
                Text(
                    stringResource(R.string.progress_avg_format, avg),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        if (dailyCalories.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.progress_no_food),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        } else {
            CalorieBarChart(dailyCalories = dailyCalories, goal = calorieGoal)
        }
    }
}

@Composable
private fun CalorieBarChart(dailyCalories: List<Pair<LocalDate, Int>>, goal: Int) {
    val maxValue = dailyCalories.maxOf { it.second }.coerceAtLeast(goal).toDouble()
    val gradientStart = AppColors.CalorieStart
    val gradientEnd = AppColors.CalorieEnd
    val goalColor = AppColors.Calorie.copy(alpha = 0.4f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    val secondaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val ticks = niceAxisTicks(0.0, maxValue, count = 5)
    val yTop = ticks.last().coerceAtLeast(maxValue)
    val xLabelFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    Column {
        Row(Modifier.fillMaxWidth().height(190.dp)) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.Calorie.copy(alpha = 0.025f))
            ) {
                val barAreaWidthPx = with(density) { maxWidth.toPx() }
                val n = dailyCalories.size
                val gap = 4f
                // Cap bar width so single-day / very-sparse charts don't render as
                // one giant block, but keep the cap generous enough that a 1W view
                // fills the card width and leaves each bar a slot wide enough to
                // hold its "Apr 18" label underneath.
                val maxBarPx = with(density) { 60.dp.toPx() }
                val rawWidth = (barAreaWidthPx - gap * (n - 1)) / n
                val barWidth = rawWidth.coerceIn(2f, maxBarPx)
                val totalGroupW = barWidth * n + gap * (n - 1)
                val startX = ((barAreaWidthPx - totalGroupW) / 2f).coerceAtLeast(0f)

                Canvas(Modifier.fillMaxSize()) {
                    val pxW = size.width; val pxH = size.height
                    ticks.forEach { tick ->
                        val y = pxH - ((tick / yTop).toFloat() * pxH)
                        drawLine(gridColor, Offset(0f, y), Offset(pxW, y), strokeWidth = 1f)
                    }
                    for (i in 0 until n) {
                        val cx = startX + i * (barWidth + gap) + barWidth / 2f
                        drawLine(
                            color = gridColor,
                            start = Offset(cx, 0f), end = Offset(cx, pxH),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
                        )
                    }
                    val goalY = pxH - ((goal / yTop).toFloat() * pxH)
                    drawLine(
                        color = goalColor,
                        start = Offset(0f, goalY), end = Offset(pxW, goalY),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                    )
                    dailyCalories.forEachIndexed { i, (_, cals) ->
                        val barH = ((cals / yTop).toFloat() * pxH)
                        val x = startX + i * (barWidth + gap)
                        val y = pxH - barH
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(gradientEnd, gradientStart),
                                startY = y, endY = pxH
                            ),
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f)
                        )
                    }
                }
            }
            Column(
                Modifier.width(44.dp).fillMaxSize().padding(start = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                ticks.reversed().forEach { tick ->
                    Text(formatTick(tick), fontSize = 11.sp, color = secondaryColor)
                }
            }
        }
        // X-axis date labels — anchored to each bar's center using the same geometry.
        // Label box = slot width (barWidth + gap) capped at 52dp so dense 1W charts
        // don't overlap labels. For ranges with many bars, pickXLabelIndices has
        // already downsampled to ~7 labels.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val areaWidthDp = maxWidth
                val areaWidthPx = with(density) { areaWidthDp.toPx() }
                val n = dailyCalories.size
                val gap = 4f
                val maxBarPx = with(density) { 60.dp.toPx() }
                val rawWidth = (areaWidthPx - gap * (n - 1)) / n
                val barWidth = rawWidth.coerceIn(2f, maxBarPx)
                val totalGroupW = barWidth * n + gap * (n - 1)
                val startX = ((areaWidthPx - totalGroupW) / 2f).coerceAtLeast(0f)
                val slotPx = barWidth + gap
                val slotDp = with(density) { slotPx.toDp() }
                // "Apr 18" at 11sp is ~38dp wide; add tiny breathing room.
                val minLabelDp = 40.dp
                val slotStep = if (slotDp >= minLabelDp) 1
                    else Math.ceil((minLabelDp.value / slotDp.value).toDouble()).toInt().coerceAtLeast(1)
                val pickedIndices = buildList {
                    var i = 0
                    while (i < n) { add(i); i += slotStep }
                    if (last() != n - 1) add(n - 1)
                }.distinct()
                val labelBoxWidth = if (slotStep == 1) slotDp else minLabelDp.coerceAtLeast(slotDp)
                pickedIndices.forEach { i ->
                    val cxPx = startX + i * (barWidth + gap) + barWidth / 2f
                    val cxDp = with(density) { cxPx.toDp() }
                    Box(
                        Modifier
                            .width(labelBoxWidth)
                            .offset(x = cxDp - labelBoxWidth / 2),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            xLabelFmt.format(dailyCalories[i].first),
                            fontSize = 11.sp,
                            color = secondaryColor,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.width(44.dp))
        }
    }
}

/** Pick at most [maxLabels] evenly-spaced bar indices for x-axis labelling. */
private fun pickXLabelIndices(n: Int, maxLabels: Int = 7): List<Int> {
    if (n <= 0) return emptyList()
    if (n <= maxLabels) return (0 until n).toList()
    val step = (n - 1).toFloat() / (maxLabels - 1)
    return (0 until maxLabels).map { i -> (i * step).toInt().coerceIn(0, n - 1) }.distinct()
}

@Composable
private fun MacroAveragesSection(
    avgProtein: Double, avgCarbs: Double, avgFat: Double,
    proteinGoal: Int, carbsGoal: Int, fatGoal: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.progress_macro_averages), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        MacroProgressRow(stringResource(R.string.macro_protein), avgProtein, proteinGoal, stringResource(R.string.unit_g))
        MacroProgressRow(stringResource(R.string.macro_carbs), avgCarbs, carbsGoal, stringResource(R.string.unit_g))
        MacroProgressRow(stringResource(R.string.macro_fat), avgFat, fatGoal, stringResource(R.string.unit_g))
    }
}


private data class ProgressFoodRangeStats(
    val dailyCalories: List<Pair<LocalDate, Int>>,
    val avgProtein: Double,
    val avgCarbs: Double,
    val avgFat: Double,
    val nutrientItems: List<NutrientAverageItem>
)

private fun computeProgressFoodRangeStats(
    foods: List<FoodEntry>,
    dayCount: Int,
    zone: ZoneId,
    profile: com.apoorvdarshan.calorietracker.models.UserProfile?,
    optionalGoals: OptionalNutrientGoals
): ProgressFoodRangeStats {
    val today = LocalDate.now()
    val rangeStart = today.minusDays((dayCount - 1).toLong())
    val buckets = linkedMapOf<LocalDate, MutableList<FoodEntry>>()
    for (entry in foods) {
        val day = entry.timestamp.atZone(zone).toLocalDate()
        if (day < rangeStart || day > today) continue
        buckets.getOrPut(day) { mutableListOf() }.add(entry)
    }

    val nutrients = HomeTopNutrient.entries.filter {
        it != HomeTopNutrient.PROTEIN && it != HomeTopNutrient.CARBS && it != HomeTopNutrient.FAT
    }
    val dailyCalories = mutableListOf<Pair<LocalDate, Int>>()
    var totalP = 0.0
    var totalC = 0.0
    var totalF = 0.0
    var loggedDays = 0
    val nutrientTotals = mutableMapOf<HomeTopNutrient, Double>().withDefault { 0.0 }

    for (day in buckets.keys.sorted()) {
        val dayEntries = buckets[day].orEmpty()
        if (dayEntries.isEmpty()) continue
        val calories = dayEntries.sumOf { it.calories }
        if (calories > 0) dailyCalories.add(day to calories)
        totalP += dayEntries.sumOf { it.protein }
        totalC += dayEntries.sumOf { it.carbs }
        totalF += dayEntries.sumOf { it.fat }
        loggedDays += 1
        for (nutrient in nutrients) {
            nutrientTotals[nutrient] = nutrientTotals.getValue(nutrient) + nutrient.current(dayEntries)
        }
    }

    if (loggedDays == 0) {
        return ProgressFoodRangeStats(dailyCalories, 0.0, 0.0, 0.0, emptyList())
    }
    val divisor = loggedDays.toDouble()
    val nutrientItems = nutrients.mapNotNull { nutrient ->
        val average = nutrientTotals.getValue(nutrient) / divisor
        val goal = nutrient.goal(profile, optionalGoals)
        if (average < 0.05 && goal == 0) null
        else NutrientAverageItem(
            key = nutrient.storageKey,
            labelRes = nutrient.displayNameRes,
            current = average,
            goal = goal,
            unitRes = nutrient.unitRes
        )
    }
    return ProgressFoodRangeStats(
        dailyCalories = dailyCalories,
        avgProtein = totalP / divisor,
        avgCarbs = totalC / divisor,
        avgFat = totalF / divisor,
        nutrientItems = nutrientItems
    )
}

@Composable
private fun ProgressNutritionLoadingCard() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = AppColors.Calorie
        )
        Text(
            stringResource(R.string.progress_loading_nutrition),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private data class NutrientAverageItem(
    val key: String,
    val labelRes: Int,
    val current: Double,
    val goal: Int,
    val unitRes: Int
)

@Composable
private fun NutrientAveragesSection(items: List<NutrientAverageItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.progress_nutrient_averages), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        items.forEach { item ->
            key(item.key) {
                MacroProgressRow(
                    label = stringResource(item.labelRes),
                    current = item.current,
                    goal = item.goal,
                    unit = stringResource(item.unitRes)
                )
            }
        }
    }
}

@Composable
private fun MacroProgressRow(label: String, current: Double, goal: Int, unit: String) {
    val progress = if (goal > 0) (current.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val valueText = if (goal > 0) {
        "${MacroValueFormatter.string(current)}$unit / ${goal}$unit"
    } else {
        "${MacroValueFormatter.string(current)}$unit"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp)) {
            val w = maxWidth
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(AppColors.Calorie.copy(alpha = 0.12f))
            )
            val barWidth = (w * progress).coerceAtLeast(6.dp)
            Box(
                Modifier
                    .width(barWidth)
                    .height(8.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(4.dp),
                        ambientColor = AppColors.Calorie.copy(alpha = 0.3f),
                        spotColor = AppColors.Calorie.copy(alpha = 0.3f)
                    )
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppColors.CalorieGradient)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllWeightHistorySheet(
    entries: List<WeightEntry>,
    useMetric: Boolean,
    onDelete: (java.util.UUID) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetSurface = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFAF3EE)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.progress_weight_history), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = AppColors.Calorie) }
            }
            Spacer(Modifier.height(12.dp))
            FudGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                padding = 0.dp
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .padding(vertical = 4.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(formatWeight(entry.weightKg, useMetric), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(fmt.format(entry.date), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            }
                            IconButton(onClick = { onDelete(entry.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllBodyFatHistorySheet(
    entries: List<BodyFatEntry>,
    onDelete: (java.util.UUID) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetSurface = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFAF3EE)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.progress_body_fat_history), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = AppColors.Calorie) }
            }
            Spacer(Modifier.height(12.dp))
            FudGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                padding = 0.dp
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .padding(vertical = 4.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(String.format(Locale.US, "%.1f%%", entry.bodyFatPercent), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(fmt.format(entry.date), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            }
                            IconButton(onClick = { onDelete(entry.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllWorkoutHistorySheet(
    entries: List<WorkoutSession>,
    onRequestDelete: (WorkoutSession) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val displayDate = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetSurface = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFAF3EE)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.progress_workout_history),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done), color = AppColors.Calorie)
                }
            }
            Spacer(Modifier.height(12.dp))
            FudGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                padding = 0.dp
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(vertical = 4.dp)
                ) {
                    items(entries.sortedWith(
                        compareByDescending<WorkoutSession> { it.diaryDateKey }
                            .thenByDescending { it.completedAt }
                    ), key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${entry.caloriesBurned ?: 0} kcal",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    runCatching { LocalDate.parse(entry.diaryDateKey).format(displayDate) }
                                        .getOrDefault(entry.diaryDateKey),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            IconButton(onClick = { onRequestDelete(entry) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Box(
                            Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AddWeightDialog(
    useMetric: Boolean,
    initialKg: Double,
    onUnitChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (Double) -> Unit
) {
    // Wheel picker matches Settings → Goal Weight + the onboarding height/weight
    // step — split-decimal so users land on e.g. 72.4 without typing.
    var pickerKg by remember { mutableStateOf(initialKg) }
    var metric by remember { mutableStateOf(useMetric) }
    FudGlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.progress_log_weight_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        UnitToggle(stringResource(R.string.unit_kg), stringResource(R.string.unit_lbs), metric, { metric = it; onUnitChange(it) }, Modifier.fillMaxWidth())
        if (metric) {
            SplitDecimalWheelPicker(
                value = pickerKg.coerceIn(30.0, 250.0),
                onValueChange = { pickerKg = it },
                min = 30,
                max = 250,
                unit = stringResource(R.string.unit_kg)
            )
        } else {
            val lbs = (pickerKg * 2.20462).coerceIn(60.0, 500.0)
            SplitDecimalWheelPicker(
                value = lbs,
                onValueChange = { newLbs -> pickerKg = newLbs / 2.20462 },
                min = 60,
                max = 500,
                unit = stringResource(R.string.unit_lbs)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            FudGlassTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            Spacer(Modifier.width(8.dp))
            FudGlassPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = { onSubmit(pickerKg) },
                modifier = Modifier.width(132.dp)
            )
        }
    }
}

private fun formatWeight(kg: Double, useMetric: Boolean): String =
    if (useMetric) String.format(Locale.US, "%.1f kg", kg)
    else String.format(Locale.US, "%.1f lbs", kg * 2.20462)

private fun formatWeightChange(deltaKg: Double, useMetric: Boolean): String {
    val displayValue = if (useMetric) deltaKg else deltaKg * 2.20462
    val roundedValue = if (Math.abs(displayValue) < 0.05) 0.0 else displayValue
    val sign = if (roundedValue > 0) "+" else ""
    val unit = if (useMetric) "kg" else "lbs"
    return String.format(Locale.US, "%s%.1f %s", sign, roundedValue, unit)
}

// MARK: - Body Fat surfaces ----------------------------------------------

@Composable
private fun ProgressMetricSelector(
    metrics: List<ProgressMetric>,
    selected: ProgressMetric,
    onSelect: (ProgressMetric) -> Unit
) {
    // Match iOS `ProgressMetricSelector` segmented pill (text only, equal weight).
    val labels = mapOf(
        ProgressMetric.WEIGHT to stringResource(R.string.progress_metric_weight),
        ProgressMetric.BODY_FAT to stringResource(R.string.progress_metric_body_fat),
        ProgressMetric.WORKOUTS to stringResource(R.string.progress_metric_workouts)
    )
    IosStyleSegmentedControl(
        options = metrics,
        selected = selected,
        label = { labels.getValue(it) },
        onSelect = onSelect
    )
}

@Composable
private fun BodyFatSection(
    entries: List<BodyFatEntry>,
    latest: Double?,
    goalFraction: Double?,
    onLogBodyFat: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.progress_metric_body_fat), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.clickable(onClick = onLogBodyFat),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.AddCircle, null, tint = AppColors.Calorie, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.progress_log_body_fat), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.Calorie)
            }
        }
        if (entries.isEmpty() && latest == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.progress_log_first_body_fat),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        } else {
            val currentLabel = stringResource(R.string.progress_stat_current)
            val goalLabel = stringResource(R.string.progress_stat_goal)
            val netChangeLabel = stringResource(R.string.progress_stat_net_change)
            val averageLabel = stringResource(R.string.progress_stat_average)
            StatBadgeRow(
                buildList {
                    latest?.let {
                        add(currentLabel to formatPercent(it))
                    }
                    goalFraction?.let {
                        add(goalLabel to formatPercent(it))
                    }
                    if (entries.isNotEmpty()) {
                        val sortedEntries = entries.sortedBy { it.date }
                        val netChange = sortedEntries.last().bodyFatPercent - sortedEntries.first().bodyFatPercent
                        val average = sortedEntries.map { it.bodyFatPercent }.average()
                        add(netChangeLabel to formatPercentChange(netChange))
                        add(averageLabel to formatPercentValue(average))
                    }
                }
            )
            if (entries.isNotEmpty()) {
                BodyFatChartCanvas(entries = entries, goalFraction = goalFraction)
            }
        }
    }
}

@Composable
private fun BodyFatChartCanvas(entries: List<BodyFatEntry>, goalFraction: Double?) {
    // Mirrors WeightChartCanvas — same horizontal grid + tick marks, vertical
    // dashed columns, dashed green goal line, right-side Y-axis labels (with
    // "%" suffix), draggable inspector, and bottom date labels.
    val sortedEntries = remember(entries) { entries.sortedBy { it.date } }
    val percents = sortedEntries.map { it.bodyFatFraction * 100 } + listOfNotNull(goalFraction?.let { it * 100 })
    val minP = percents.min()
    val maxP = percents.max()
    val pad = maxOf((maxP - minP) * 0.15, 1.0)
    val yMin = (minP - pad).coerceAtLeast(0.0)
    val yMax = maxP + pad
    val tStart = sortedEntries.first().date.toEpochMilli()
    val tEnd = sortedEntries.last().date.toEpochMilli()
    val singleEntry = sortedEntries.size == 1
    val tRange = maxOf(1L, tEnd - tStart)
    val goalLineColor = Color(0xFF34C759).copy(alpha = 0.7f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    val secondaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val chartSurfaceColor = MaterialTheme.colorScheme.surface
    val ticks = niceAxisTicks(yMin, yMax, count = 5)
    val zone = ZoneId.systemDefault()
    // Same year-label + downsampling policy as WeightChartCanvas.
    val spanDays = maxOf(1L, (tEnd - tStart) / 86_400_000L)
    val showsYear = spanDays > 150 &&
        Instant.ofEpochMilli(tStart).atZone(zone).year != Instant.ofEpochMilli(tEnd).atZone(zone).year
    val xLabelFmt = DateTimeFormatter.ofPattern(if (showsYear) "MMM yyyy" else "MMM d", Locale.US).withZone(zone)
    val points = remember(sortedEntries) {
        downsampleTrend(sortedEntries.map { TrendPoint(it.date.toEpochMilli(), it.bodyFatFraction * 100) })
    }
    val showsDots = points.size <= 31
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val selectedPoint = selectedIndex?.let(points::getOrNull)
    val inspectorDateFormat = remember {
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())
    }

    Row(Modifier.fillMaxWidth().height(190.dp)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.Calorie.copy(alpha = 0.025f))
        ) {
            val canvasWidthPx = with(density) { maxWidth.toPx() }
            val selectedTargetX = selectedPoint?.let { point ->
                if (singleEntry) canvasWidthPx / 2f
                else ((point.timeMs - tStart).toDouble() / tRange * canvasWidthPx).toFloat()
            } ?: 0f
            val animatedInspectorX by animateFloatAsState(
                targetValue = selectedTargetX,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "bodyFatInspectorX"
            )

            fun selectNearest(x: Float) {
                if (points.isEmpty() || canvasWidthPx <= 0f) return
                val clampedX = x.coerceIn(0f, canvasWidthPx)
                val nearest = points.indices.minByOrNull { index ->
                    val pointX = if (singleEntry) canvasWidthPx / 2f else
                        ((points[index].timeMs - tStart).toDouble() / tRange * canvasWidthPx).toFloat()
                    kotlin.math.abs(pointX - clampedX)
                } ?: return
                if (nearest != selectedIndex) {
                    selectedIndex = nearest
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(points, canvasWidthPx) {
                        var gestureX = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                gestureX = offset.x
                                selectNearest(gestureX)
                            },
                            onDragEnd = { selectedIndex = null },
                            onDragCancel = { selectedIndex = null },
                            onHorizontalDrag = { change, dragAmount ->
                                gestureX = (gestureX + dragAmount).coerceIn(0f, size.width.toFloat())
                                selectNearest(gestureX)
                                change.consume()
                            }
                        )
                    }
            ) {
                val w = size.width; val h = size.height
                // Horizontal grid + tick marks
                ticks.forEach { tick ->
                    val y = h - (((tick - yMin) / (yMax - yMin)).toFloat() * h)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y), end = Offset(w, y),
                        strokeWidth = 1f
                    )
                }
                // Vertical grid (4 columns) — faint dashed
                for (i in 0..4) {
                    val x = (i.toFloat() / 4f) * w
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f), end = Offset(x, h),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
                    )
                }
                goalFraction?.let { g ->
                    val gPct = g * 100
                    val y = h - (((gPct - yMin) / (yMax - yMin)).toFloat() * h)
                    drawLine(
                        color = goalLineColor,
                        start = Offset(0f, y), end = Offset(w, y),
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
                    )
                }
                val offsets = points.map { p ->
                    Offset(
                        if (singleEntry) w / 2f
                        else ((p.timeMs - tStart).toDouble() / tRange * w).toFloat(),
                        h - (((p.value - yMin) / (yMax - yMin)).toFloat() * h)
                    )
                }
                clipRect {
                    drawPath(
                        smoothTrendAreaPath(offsets, h),
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                AppColors.Calorie.copy(alpha = 0.12f),
                                AppColors.Calorie.copy(alpha = 0.01f)
                            ),
                            startY = 0f,
                            endY = h
                        )
                    )
                    drawPath(smoothTrendPath(offsets), AppColors.Calorie, style = Stroke(width = 6f))
                    if (showsDots) {
                        offsets.forEach { drawCircle(AppColors.Calorie, radius = 6f, center = it) }
                    }

                    selectedPoint?.let { point ->
                        val pointY = h - (((point.value - yMin) / (yMax - yMin)).toFloat() * h)
                        drawLine(
                            color = AppColors.Calorie.copy(alpha = 0.62f),
                            start = Offset(animatedInspectorX, 0f),
                            end = Offset(animatedInspectorX, h),
                            strokeWidth = 2.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f))
                        )
                        drawCircle(chartSurfaceColor, radius = 11f, center = Offset(animatedInspectorX, pointY))
                        drawCircle(AppColors.Calorie, radius = 6f, center = Offset(animatedInspectorX, pointY))
                    }
                }
            }

            selectedPoint?.let { point ->
                val tooltipWidth = 116.dp
                val tooltipWidthPx = with(density) { tooltipWidth.toPx() }
                val tooltipLeft = (animatedInspectorX - tooltipWidthPx / 2f)
                    .coerceIn(0f, (canvasWidthPx - tooltipWidthPx).coerceAtLeast(0f))
                Column(
                    modifier = Modifier
                        .offset { IntOffset(tooltipLeft.roundToInt(), with(density) { 6.dp.roundToPx() }) }
                        .width(tooltipWidth)
                        .shadow(8.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                        .border(0.75.dp, AppColors.Calorie.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        inspectorDateFormat.format(Instant.ofEpochMilli(point.timeMs)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = secondaryColor,
                        maxLines = 1
                    )
                    Text(
                        String.format(Locale.US, "%.1f%%", point.value),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
        // Y-axis labels on the right
        Column(
            Modifier.width(40.dp).fillMaxSize().padding(start = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            ticks.reversed().forEach { tick ->
                Text(
                    formatPercentTick(tick),
                    fontSize = 11.sp,
                    color = secondaryColor
                )
            }
        }
    }
    TrendXAxisLabels(tStart, tEnd, showsYear, singleEntry, xLabelFmt, secondaryColor, endPadding = 40.dp)
}

/** Format a body-fat tick value for the Y-axis label (e.g. 17.5 → "17.5%"
 *  when the tick has a fractional part, otherwise "18%" — keeps short ticks
 *  short and falls back to one decimal when the chart is zoomed in). */
private fun formatPercentTick(value: Double): String {
    val rounded = (value * 10).toInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) "${rounded.toInt()}%"
    else String.format(Locale.US, "%.1f%%", rounded)
}

@Composable
private fun AddBodyFatDialog(
    initialFraction: Double,
    onDismiss: () -> Unit,
    onSubmit: (Double) -> Unit
) {
    // Whole-percent wheel — body fat measurements rarely justify 0.1% resolution
    // given the noise of calipers / smart scales (matches iOS LogBodyFatSheet).
    var pct by remember { mutableStateOf(initialFraction * 100) }
    FudGlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.progress_log_body_fat_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        DecimalWheelPicker(
            value = pct.coerceIn(3.0, 60.0),
            onValueChange = { pct = it },
            min = 3.0,
            max = 60.0,
            step = 0.5,
            unit = stringResource(R.string.unit_percent)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            FudGlassTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            Spacer(Modifier.width(8.dp))
            FudGlassPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = { onSubmit(pct / 100.0) },
                modifier = Modifier.width(132.dp)
            )
        }
    }
}

private fun formatPercent(fraction: Double): String =
    String.format(Locale.US, "%.1f%%", fraction * 100)

private fun formatPercentValue(percent: Double): String =
    String.format(Locale.US, "%.1f%%", percent)

private fun formatPercentChange(deltaPercent: Double): String {
    val roundedValue = if (Math.abs(deltaPercent) < 0.05) 0.0 else deltaPercent
    val sign = if (roundedValue > 0) "+" else ""
    return String.format(Locale.US, "%s%.1f%%", sign, roundedValue)
}

// ── Body Measurements (optional tape-measure tracking) ──────────────────

private val measurementHistoryFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())

private fun displayLengthCm(context: android.content.Context, cm: Double, useMetric: Boolean): String =
    if (useMetric) String.format(Locale.US, "%.1f %s", cm, context.getString(R.string.unit_cm))
    else String.format(Locale.US, "%.1f %s", cm / 2.54, context.getString(R.string.unit_in))

/** Logged sites in display order, skipping any that weren't entered. */
private fun measurementSiteList(context: android.content.Context, m: BodyMeasurement): List<Pair<String, Double>> = buildList {
    m.neckCm?.let { add(context.getString(R.string.measure_neck) to it) }
    m.waistCm?.let { add(context.getString(R.string.measure_waist) to it) }
    m.hipsCm?.let { add(context.getString(R.string.measure_hips) to it) }
    m.chestCm?.let { add(context.getString(R.string.measure_chest) to it) }
    m.upperArmCm?.let { add(context.getString(R.string.measure_upper_arm) to it) }
    m.thighCm?.let { add(context.getString(R.string.measure_thigh) to it) }
    m.calfCm?.let { add(context.getString(R.string.measure_calf) to it) }
    m.wristCm?.let { add(context.getString(R.string.measure_wrist) to it) }
}

/** Derived metrics computable from this entry + profile, skipping any missing their inputs. */
private fun derivedMetricList(context: android.content.Context, m: BodyMeasurement, gender: Gender, heightCm: Double): List<Pair<String, String>> = buildList {
    m.waistToHipRatio?.let { add(context.getString(R.string.derived_waist_to_hip) to String.format(Locale.US, "%.2f", it)) }
    m.waistToHeightRatio(heightCm)?.let { add(context.getString(R.string.derived_waist_to_height) to String.format(Locale.US, "%.2f", it)) }
    m.usNavyBodyFatPercent(gender, heightCm)?.let { add(context.getString(R.string.derived_body_fat) to String.format(Locale.US, "%.0f%%", it)) }
    m.wristFrame(gender, heightCm)?.let { add(context.getString(R.string.derived_frame) to context.getString(it.labelRes)) }
}

private fun measurementHistorySummary(context: android.content.Context, m: BodyMeasurement, gender: Gender, heightCm: Double, useMetric: Boolean): String {
    val sites = measurementSiteList(context, m).map { "${it.first} ${displayLengthCm(context, it.second, useMetric)}" }
    val bf = m.usNavyBodyFatPercent(gender, heightCm)?.let { "BF ${String.format(Locale.US, "%.0f%%", it)}" }
    return (sites + listOfNotNull(bf)).joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BodyMeasurementsHistorySheet(
    entries: List<BodyMeasurement>,
    gender: Gender,
    heightCm: Double,
    useMetric: Boolean,
    onDelete: (java.util.UUID) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetSurface = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFAF3EE)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.measurement_history), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = AppColors.Calorie) }
            }
            Spacer(Modifier.height(12.dp))
            FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 0.dp) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(vertical = 4.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(measurementHistoryFmt.format(entry.date), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    measurementHistorySummary(LocalContext.current, entry, gender, heightCm, useMetric),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            IconButton(onClick = { onDelete(entry.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Settings → Personal Info detail screen for body-circumference measurements. Mirrors the Other
 * Nutrients screen: a tappable row per body part that opens a wheel picker to set its value, plus
 * the AI-derived metrics and history. Talks to BodyMeasurementRepository directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyMeasurementsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by container.bodyMeasurementRepository.entries.collectAsState(initial = emptyList())
    val profile by container.profileRepository.profile.collectAsState(initial = null)
    val heightUnit by container.prefs.heightUnit.collectAsState(initial = "cm")
    val heightMetric = heightUnit == "cm"
    val gender = profile?.gender ?: Gender.MALE
    val heightCm = profile?.heightCm ?: 0.0
    val latest = entries.maxByOrNull { it.date }
    val unit = if (heightMetric) "cm" else "in"

    var editing by remember { mutableStateOf<BodyMeasurement.Site?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    val notSet = stringResource(R.string.settings_not_set)
    val cmUnit = stringResource(R.string.unit_cm)
    val inUnit = stringResource(R.string.unit_in)
    fun displayValue(site: BodyMeasurement.Site): String {
        val cm = latest?.value(site) ?: return notSet
        return if (heightMetric) String.format(Locale.US, "%.0f %s", cm, cmUnit) else String.format(Locale.US, "%.0f %s", cm / 2.54, inUnit)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onBack() }
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = AppColors.Calorie, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.nav_settings), color = AppColors.Calorie, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item {
                Text(stringResource(R.string.body_measurements_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Optional. Fud AI turns these into waist-to-hip, waist-to-height, body-fat %, and frame size, and reads them when it recalculates your goals and in Coach.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            item {
                FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 0.dp) {
                    Column {
                        BodyMeasurement.Site.values().forEachIndexed { index, site ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { editing = site }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(site.labelRes), modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(displayValue(site), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                            }
                            if (index != BodyMeasurement.Site.values().lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            }
                        }
                    }
                }
            }
            if (latest != null) {
                val derived = derivedMetricList(context, latest, gender, heightCm)
                if (derived.isNotEmpty()) {
                    item {
                        FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 16.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.label_derived), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                                derived.forEach { (label, value) ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp)
                                        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Calorie)
                                    }
                                }
                            }
                        }
                    }
                }
                if (entries.size > 1) {
                    item {
                        FudGlassSurface(
                            modifier = Modifier.fillMaxWidth().clickable { showHistory = true },
                            cornerRadius = 16.dp,
                            padding = 14.dp
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.measurement_history), modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("${entries.size}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { site ->
        val current = latest?.value(site)
        // Editor-owned display value: a unit flip CONVERTS what's on the wheel
        // (clamped into the destination rows), matching the height/weight editors,
        // instead of re-seeding from the saved value.
        var editorValue by remember(site) {
            mutableStateOf(
                current?.let { if (heightMetric) Math.round(it).toInt() else Math.round(it / 2.54).toInt() }
                    ?: if (heightMetric) 80 else 32
            )
        }
        FudGlassDialog(onDismissRequest = { editing = null }) {
            // Flipping here persists the shared length standard (same pref as the
            // Height editor); the collected pref recomposes range + labels.
            UnitToggle(
                stringResource(R.string.unit_cm),
                stringResource(R.string.unit_in),
                heightMetric,
                { metric ->
                    if (metric != heightMetric) {
                        editorValue = if (metric) {
                            Math.round(editorValue * 2.54).toInt().coerceIn(10, 250)
                        } else {
                            Math.round(editorValue / 2.54).toInt().coerceIn(4, 100)
                        }
                        scope.launch { container.prefs.setHeightUnit(if (metric) "cm" else "ftin") }
                    }
                },
                Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            // key() so a unit flip rebuilds the wheel seeded with the converted
            // value: its internal scroll state otherwise survives the range swap.
            key(heightMetric) {
                NutritionPickerSheet(
                    label = stringResource(site.labelRes),
                    unit = unit,
                    currentValue = editorValue,
                    range = if (heightMetric) 10..250 else 4..100,
                    step = 1,
                    onSave = { v ->
                        val cm = if (heightMetric) v.toDouble() else v * 2.54
                        scope.launch { container.bodyMeasurementRepository.setValue(site, cm) }
                        editing = null
                    },
                    onResetToAuto = if (current != null) {
                        { scope.launch { container.bodyMeasurementRepository.setValue(site, null) }; editing = null }
                    } else null,
                    resetLabel = stringResource(R.string.action_clear),
                    onValueChange = { editorValue = it }
                )
            }
        }
    }
    if (showHistory) {
        BodyMeasurementsHistorySheet(
            entries = entries.sortedByDescending { it.date },
            gender = gender,
            heightCm = heightCm,
            useMetric = heightMetric,
            onDelete = { id -> scope.launch { container.bodyMeasurementRepository.deleteEntry(id) } },
            onDismiss = { showHistory = false }
        )
    }
}
