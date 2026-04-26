package com.apoorvdarshan.calorietracker.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.apoorvdarshan.calorietracker.ui.components.SplitDecimalWheelPicker
import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Verbatim port of ios/calorietracker/ContentView.swift > struct ProgressTabView,
 * including the per-section components in ProgressComponents.swift.
 *
 * Layout (top -> bottom):
 *   1. Segmented TimeRange picker — 1W / 1M / 3M / 6M / 1Y / All
 *   2. WeightChartSection — Weight title + Log Weight pill + StatBadges
 *      (Current, Goal) + line chart with green dashed goal rule
 *   3. WeightHistoryLink — only shown if any weight entries exist; shows
 *      count + chevron, opens AllWeightHistorySheet
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
    val vm: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val foods by container.foodRepository.entries.collectAsState(initial = emptyList())
    val useMetric by container.prefs.useMetric.collectAsState(initial = true)

    var range by remember { mutableStateOf(TimeRange.WEEK) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAllWeights by remember { mutableStateOf(false) }

    // Filter weights to range
    val (rangeStartDate, rangeEndDate) = range.dateRange()
    val zone = ZoneId.systemDefault()
    val rangeStart = rangeStartDate.atStartOfDay(zone).toInstant()
    val rangeEnd = rangeEndDate.atTime(23, 59, 59).atZone(zone).toInstant()
    val filteredWeights = ui.entries.filter { it.date in rangeStart..rangeEnd }.sortedBy { it.date }

    // Build per-day calorie totals over the range (drop empty days, like iOS)
    val dailyCalories = remember(foods, range) {
        val today = LocalDate.now()
        (0 until range.days).mapNotNull { offset ->
            val day = today.minusDays(offset.toLong())
            val cals = foods
                .filter { it.timestamp.atZone(zone).toLocalDate() == day }
                .sumOf { it.calories }
            if (cals == 0) null else day to cals
        }.reversed()
    }

    // Macro averages over the range, only counting days with logged food
    val macroAverages = remember(foods, range) {
        val today = LocalDate.now()
        var p = 0; var c = 0; var f = 0; var n = 0
        for (offset in 0 until range.days) {
            val day = today.minusDays(offset.toLong())
            val dayEntries = foods.filter { it.timestamp.atZone(zone).toLocalDate() == day }
            if (dayEntries.isEmpty()) continue
            p += dayEntries.sumOf { it.protein }
            c += dayEntries.sumOf { it.carbs }
            f += dayEntries.sumOf { it.fat }
            n += 1
        }
        if (n == 0) Triple(0, 0, 0) else Triple(p / n, c / n, f / n)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Segmented TimeRange picker
            item { TimeRangePicker(selected = range, onSelect = { range = it }) }

            // 2. Weight chart section
            item {
                CardSection {
                    WeightSection(
                        entries = filteredWeights,
                        latest = ui.entries.maxByOrNull { it.date },
                        goalKg = ui.profile?.goalWeightKg,
                        useMetric = useMetric,
                        onLogWeight = { showAddDialog = true }
                    )
                }
            }

            // 3. Weight history link (if any)
            if (ui.entries.isNotEmpty()) {
                item {
                    WeightHistoryLink(count = ui.entries.size) { showAllWeights = true }
                }
            }

            // 4. Calorie chart section
            item {
                CardSection {
                    CalorieSection(
                        dailyCalories = dailyCalories,
                        calorieGoal = ui.profile?.effectiveCalories ?: 2000
                    )
                }
            }

            // 5. Macro averages
            ui.profile?.let { p ->
                item {
                    CardSection {
                        MacroAveragesSection(
                            avgProtein = macroAverages.first,
                            avgCarbs = macroAverages.second,
                            avgFat = macroAverages.third,
                            proteinGoal = p.effectiveProtein,
                            carbsGoal = p.effectiveCarbs,
                            fatGoal = p.effectiveFat
                        )
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
        AddWeightDialog(useMetric = useMetric, initialKg = seedKg, onDismiss = { showAddDialog = false }) { kg ->
            vm.addWeight(kg); showAddDialog = false
        }
    }
    if (showAllWeights) {
        AllWeightHistorySheet(
            entries = ui.entries.sortedByDescending { it.date },
            useMetric = useMetric,
            onDelete = vm::deleteWeight,
            onDismiss = { showAllWeights = false }
        )
    }
    if (ui.goalReached) {
        AlertDialog(
            onDismissRequest = { vm.dismissGoalReached() },
            title = { Text(stringResource(R.string.progress_goal_reached_title), fontWeight = FontWeight.SemiBold) },
            text = { Text(stringResource(R.string.progress_goal_reached_message)) },
            confirmButton = { TextButton(onClick = { vm.dismissGoalReached() }) { Text(stringResource(R.string.action_keep_going)) } }
        )
    }
}

// ── Components ──────────────────────────────────────────────────────

@Composable
private fun TimeRangePicker(selected: TimeRange, onSelect: (TimeRange) -> Unit) {
    // iOS .pickerStyle(.segmented): a track tinted with the system fill colour,
    // active segment drawn as a slightly raised darker pill, active text uses
    // the primary on-background colour (white in dark mode), not the brand pink.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            .padding(2.dp)
    ) {
        for (r in TimeRange.values()) {
            val isSel = r == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (isSel) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                        else Color.Transparent
                    )
                    .clickable { onSelect(r) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(r.labelRes),
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CardSection(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                latest?.let { StatBadge(stringResource(R.string.progress_stat_current), formatWeight(it.weightKg, useMetric)) }
                goalKg?.let { StatBadge(stringResource(R.string.progress_stat_goal), formatWeight(it, useMetric)) }
            }
            WeightChartCanvas(entries = entries, goalKg = goalKg, useMetric = useMetric)
        }
    }
}

@Composable
private fun StatBadge(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun WeightChartCanvas(entries: List<WeightEntry>, goalKg: Double?, useMetric: Boolean) {
    val displayKg = { kg: Double -> if (useMetric) kg else kg * 2.20462 }
    val unitLabel = if (useMetric) "" else ""
    val displayWeights = entries.map { displayKg(it.weightKg) } + listOfNotNull(goalKg?.let(displayKg))
    val minW = displayWeights.min()
    val maxW = displayWeights.max()
    val pad = maxOf((maxW - minW) * 0.15, 2.0)
    val yMin = minW - pad
    val yMax = maxW + pad
    val tStart = entries.first().date.toEpochMilli()
    val tEnd = entries.last().date.toEpochMilli()
    val singleEntry = entries.size == 1
    val tRange = maxOf(1L, tEnd - tStart)
    val goalLineColor = Color(0xFF34C759).copy(alpha = 0.7f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val secondaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val ticks = niceAxisTicks(yMin, yMax, count = 5)
    val zone = ZoneId.systemDefault()
    val xLabelFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(zone)

    Row(Modifier.fillMaxWidth().height(180.dp)) {
        Canvas(Modifier.weight(1f).fillMaxSize()) {
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
            val xFor: (com.apoorvdarshan.calorietracker.models.WeightEntry) -> Float = { e ->
                if (singleEntry) w / 2f
                else ((e.date.toEpochMilli() - tStart).toDouble() / tRange * w).toFloat()
            }
            val path = Path()
            entries.forEachIndexed { i, e ->
                val x = xFor(e)
                val y = h - (((displayKg(e.weightKg) - yMin) / (yMax - yMin)).toFloat() * h)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, AppColors.Calorie, style = Stroke(width = 5f))
            entries.forEach { e ->
                val x = xFor(e)
                val y = h - (((displayKg(e.weightKg) - yMin) / (yMax - yMin)).toFloat() * h)
                drawCircle(AppColors.Calorie, radius = 5.5f, center = Offset(x, y))
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
    // X-axis date labels
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 36.dp)) {
        Text(
            xLabelFmt.format(entries.first().date),
            fontSize = 11.sp,
            color = secondaryColor
        )
        Spacer(Modifier.weight(1f))
        if (!singleEntry) {
            Text(
                xLabelFmt.format(entries.last().date),
                fontSize = 11.sp,
                color = secondaryColor
            )
        }
    }
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
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ListAlt,
            null,
            tint = AppColors.Calorie,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.progress_weight_history), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.progress_history_count_format, count),
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
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val secondaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val ticks = niceAxisTicks(0.0, maxValue, count = 5)
    val yTop = ticks.last().coerceAtLeast(maxValue)
    val xLabelFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    Column {
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxSize()) {
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
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
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
    avgProtein: Int, avgCarbs: Int, avgFat: Int,
    proteinGoal: Int, carbsGoal: Int, fatGoal: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.progress_macro_averages), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        MacroProgressRow(stringResource(R.string.macro_protein), avgProtein, proteinGoal)
        MacroProgressRow(stringResource(R.string.macro_carbs), avgCarbs, carbsGoal)
        MacroProgressRow(stringResource(R.string.macro_fat), avgFat, fatGoal)
    }
}

@Composable
private fun MacroProgressRow(label: String, current: Int, goal: Int) {
    val progress = if (goal > 0) (current.toFloat() / goal).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.progress_macro_progress_format, current, goal),
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.progress_weight_history), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = AppColors.Calorie) }
            }
            Spacer(Modifier.height(12.dp))
            entries.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(formatWeight(entry.weightKg, useMetric), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(fmt.format(entry.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                    IconButton(onClick = { onDelete(entry.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)))
            }
        }
    }
}

@Composable
private fun AddWeightDialog(
    useMetric: Boolean,
    initialKg: Double,
    onDismiss: () -> Unit,
    onSubmit: (Double) -> Unit
) {
    // Wheel picker matches Settings → Goal Weight + the onboarding height/weight
    // step — split-decimal so users land on e.g. 72.4 without typing.
    var pickerKg by remember { mutableStateOf(initialKg) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.progress_log_weight_title), fontWeight = FontWeight.SemiBold) },
        text = {
            if (useMetric) {
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
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(pickerKg) },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie)
            ) { Text(stringResource(R.string.action_save), color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

private fun formatWeight(kg: Double, useMetric: Boolean): String =
    if (useMetric) String.format(Locale.US, "%.1f kg", kg)
    else String.format(Locale.US, "%.1f lbs", kg * 2.20462)
