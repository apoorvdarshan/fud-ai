package com.apoorvdarshan.calorietracker.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(container: AppContainer) {
    val vm: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val useMetric by container.prefs.useMetric.collectAsState(initial = true)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Progress", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CardSection {
                    WeightSection(
                        entries = ui.entries,
                        goalKg = ui.profile?.goalWeightKg,
                        useMetric = useMetric,
                        onLogWeight = { showAddDialog = true }
                    )
                }
            }
            item {
                CardSection {
                    StatsSection(
                        streak = 0,
                        bestStreak = 0,
                        daysOnTarget = 0,
                        totalEntries = 0
                    )
                }
            }
            item {
                CardSection {
                    HistorySection(
                        entries = ui.entries,
                        useMetric = useMetric,
                        onDelete = vm::deleteWeight
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddWeightDialog(
            useMetric = useMetric,
            onDismiss = { showAddDialog = false },
            onSubmit = { kg -> vm.addWeight(kg); showAddDialog = false }
        )
    }

    if (ui.goalReached) {
        AlertDialog(
            onDismissRequest = { vm.dismissGoalReached() },
            title = { Text("Congratulations! 🎉", fontWeight = FontWeight.SemiBold) },
            text = { Text("You reached your goal weight.") },
            confirmButton = { TextButton(onClick = { vm.dismissGoalReached() }) { Text("Thanks") } }
        )
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
    goalKg: Double?,
    useMetric: Boolean,
    onLogWeight: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Weight", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
            val sorted = entries.sortedBy { it.date }
            val current = sorted.lastOrNull()?.weightKg
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                current?.let { StatBadge("Current", formatWeight(it, useMetric)) }
                goalKg?.let { StatBadge("Goal", formatWeight(it, useMetric)) }
            }
            WeightChartCanvas(entries = sorted, goalKg = goalKg, useMetric = useMetric)
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
    val weights = entries.map { it.weightKg } + listOfNotNull(goalKg)
    val minW = weights.min()
    val maxW = weights.max()
    val pad = maxOf((maxW - minW) * 0.15, 2.0)
    val yMin = minW - pad
    val yMax = maxW + pad
    val tStart = entries.first().date.toEpochMilli()
    val tEnd = entries.last().date.toEpochMilli()
    val tRange = maxOf(1L, tEnd - tStart)

    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val w = size.width
        val h = size.height

        // Goal line
        goalKg?.let {
            val y = h - (((it - yMin) / (yMax - yMin)).toFloat() * h)
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
            drawLine(
                color = Color(0xFF34C759).copy(alpha = 0.7f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 3f,
                pathEffect = dash
            )
        }

        // Line path (catmull-ish via cubic smoothing — approximation)
        val path = Path()
        entries.forEachIndexed { i, e ->
            val x = ((e.date.toEpochMilli() - tStart).toDouble() / tRange * w).toFloat()
            val y = h - (((e.weightKg - yMin) / (yMax - yMin)).toFloat() * h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = AppColors.Calorie, style = Stroke(width = 5f))

        // Points
        entries.forEach { e ->
            val x = ((e.date.toEpochMilli() - tStart).toDouble() / tRange * w).toFloat()
            val y = h - (((e.weightKg - yMin) / (yMax - yMin)).toFloat() * h)
            drawCircle(AppColors.Calorie, radius = 5.5f, center = Offset(x, y))
        }
    }
}

@Composable
private fun StatsSection(streak: Int, bestStreak: Int, daysOnTarget: Int, totalEntries: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Streaks & Stats", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile(icon = Icons.Filled.LocalFireDepartment, label = "Current Streak", value = "$streak days", color = AppColors.Calorie, modifier = Modifier.weight(1f))
            StatTile(icon = Icons.Filled.EmojiEvents, label = "Best Streak", value = "$bestStreak days", color = Color(0xFFFF9500), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile(icon = Icons.Filled.TrackChanges, label = "Days on Target", value = "$daysOnTarget", color = Color(0xFF007AFF), modifier = Modifier.weight(1f))
            StatTile(icon = Icons.Filled.Restaurant, label = "Total Entries", value = "$totalEntries", color = Color(0xFF34C759), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun HistorySection(entries: List<WeightEntry>, useMetric: Boolean, onDelete: (java.util.UUID) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("History", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (entries.isEmpty()) {
            Text("No weight entries yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        } else {
            val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())
            entries.sortedByDescending { it.date }.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(formatWeight(entry.weightKg, useMetric), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(fmt.format(entry.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                    IconButton(onClick = { onDelete(entry.id) }) {
                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                    }
                }
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
                    if (v != null && v > 0.0) {
                        onSubmit(if (useMetric) v else v / 2.20462)
                    }
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
