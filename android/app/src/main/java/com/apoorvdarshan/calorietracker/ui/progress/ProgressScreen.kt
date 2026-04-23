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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
enum class TimeRange(val label: String, val days: Int) {
    WEEK("1W", 7),
    MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 180),
    YEAR("1Y", 365),
    ALL_TIME("All", 3650);

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
        AddWeightDialog(useMetric = useMetric, onDismiss = { showAddDialog = false }) { kg ->
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
            title = { Text("Congratulations! 🎉", fontWeight = FontWeight.SemiBold) },
            text = { Text("You've reached your goal weight! Head to Settings to switch your goal and tap Recalculate Goals to refresh your targets.") },
            confirmButton = { TextButton(onClick = { vm.dismissGoalReached() }) { Text("Keep Going") } }
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
                    r.label,
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
            Text("Weight", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.clickable(onClick = onLogWeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.AddCircle, null, tint = AppColors.Calorie, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Log Weight", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.Calorie)
            }
        }
        if (entries.isEmpty()) {
            Text(
                "Log your first weight to see trends",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                latest?.let { StatBadge("Current", formatWeight(it.weightKg, useMetric)) }
                goalKg?.let { StatBadge("Goal", formatWeight(it, useMetric)) }
            }
            WeightChartCanvas(entries = entries, goalKg = goalKg)
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
private fun WeightChartCanvas(entries: List<WeightEntry>, goalKg: Double?) {
    val weights = entries.map { it.weightKg } + listOfNotNull(goalKg)
    val minW = weights.min()
    val maxW = weights.max()
    val pad = maxOf((maxW - minW) * 0.15, 2.0)
    val yMin = minW - pad
    val yMax = maxW + pad
    val tStart = entries.first().date.toEpochMilli()
    val tEnd = entries.last().date.toEpochMilli()
    val singleEntry = entries.size == 1
    val tRange = maxOf(1L, tEnd - tStart)
    val goalLineColor = Color(0xFF34C759).copy(alpha = 0.7f) // iOS systemGreen at 0.7

    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val w = size.width; val h = size.height
        goalKg?.let {
            val y = h - (((it - yMin) / (yMax - yMin)).toFloat() * h)
            drawLine(
                color = goalLineColor,
                start = Offset(0f, y), end = Offset(w, y),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
            )
        }
        // Center the lone marker when there's only one weight entry — otherwise
        // it would render at x=0 (left edge), looking misplaced.
        val xFor: (com.apoorvdarshan.calorietracker.models.WeightEntry) -> Float = { e ->
            if (singleEntry) w / 2f
            else ((e.date.toEpochMilli() - tStart).toDouble() / tRange * w).toFloat()
        }
        val path = Path()
        entries.forEachIndexed { i, e ->
            val x = xFor(e)
            val y = h - (((e.weightKg - yMin) / (yMax - yMin)).toFloat() * h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, AppColors.Calorie, style = Stroke(width = 5f))
        entries.forEach { e ->
            val x = xFor(e)
            val y = h - (((e.weightKg - yMin) / (yMax - yMin)).toFloat() * h)
            drawCircle(AppColors.Calorie, radius = 5.5f, center = Offset(x, y))
        }
    }
}

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
        Text("Weight History", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(
            "$count entries",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun CalorieSection(dailyCalories: List<Pair<LocalDate, Int>>, calorieGoal: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Calories", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (dailyCalories.isNotEmpty()) {
                val avg = dailyCalories.sumOf { it.second } / dailyCalories.size
                Text(
                    "Avg: $avg kcal",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        if (dailyCalories.isEmpty()) {
            Text(
                "No food logged yet",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        } else {
            CalorieBarChart(dailyCalories = dailyCalories, goal = calorieGoal)
        }
    }
}

@Composable
private fun CalorieBarChart(dailyCalories: List<Pair<LocalDate, Int>>, goal: Int) {
    val maxValue = (dailyCalories.maxOf { it.second }.coerceAtLeast(goal)).toFloat()
    val gradientStart = AppColors.CalorieStart
    val gradientEnd = AppColors.CalorieEnd
    val goalColor = AppColors.Calorie.copy(alpha = 0.4f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val pxW = size.width; val pxH = size.height
        val n = dailyCalories.size
        val gap = 4f
        // Cap bar width to ~28dp so a single-day chart doesn't render as one
        // giant block spanning the full width.
        val maxBarPx = with(density) { 28.dp.toPx() }
        val rawWidth = (pxW - gap * (n - 1)) / n
        val barWidth = rawWidth.coerceIn(2f, maxBarPx)
        // Center the bar group horizontally when capped (otherwise left-aligned).
        val totalGroupW = barWidth * n + gap * (n - 1)
        val startX = ((pxW - totalGroupW) / 2f).coerceAtLeast(0f)

        // Goal line
        val goalY = pxH - ((goal / maxValue) * pxH).coerceAtMost(pxH)
        drawLine(
            color = goalColor,
            start = Offset(0f, goalY), end = Offset(pxW, goalY),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
        )
        dailyCalories.forEachIndexed { i, (_, cals) ->
            val barH = ((cals / maxValue) * pxH)
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

@Composable
private fun MacroAveragesSection(
    avgProtein: Int, avgCarbs: Int, avgFat: Int,
    proteinGoal: Int, carbsGoal: Int, fatGoal: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Macro Averages", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        MacroProgressRow("Protein", avgProtein, proteinGoal)
        MacroProgressRow("Carbs", avgCarbs, carbsGoal)
        MacroProgressRow("Fat", avgFat, fatGoal)
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
                "${current}g / ${goal}g",
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
                Text("Weight History", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done", color = AppColors.Calorie) }
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
                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)))
            }
        }
    }
}

@Composable
private fun AddWeightDialog(useMetric: Boolean, onDismiss: () -> Unit, onSubmit: (Double) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Log Weight", fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (useMetric) "kg" else "lbs") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val v = input.toDoubleOrNull()
                    if (v != null && v > 0.0) onSubmit(if (useMetric) v else v / 2.20462)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie)
            ) { Text("Save", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatWeight(kg: Double, useMetric: Boolean): String =
    if (useMetric) String.format(Locale.US, "%.1f kg", kg)
    else String.format(Locale.US, "%.1f lbs", kg * 2.20462)
