package com.apoorvdarshan.calorietracker.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("Progress") }) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = AppColors.Calorie,
                contentColor = Color.White
            ) { Icon(Icons.Filled.Add, contentDescription = "Add weight") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            StatsCard(ui = ui)

            Spacer(Modifier.height(16.dp))

            WeightChart(entries = ui.entries)

            Spacer(Modifier.height(16.dp))

            Text("History", style = MaterialTheme.typography.titleMedium)

            LazyColumn(
                Modifier.fillMaxSize().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ui.entries.sortedByDescending { it.date }, key = { it.id }) { entry ->
                    WeightRow(entry = entry, onDelete = { vm.deleteWeight(entry.id) })
                }
            }
        }
    }

    if (showAddDialog) {
        AddWeightDialog(
            useMetric = ui.profile?.let { true } ?: true, // simple default
            onDismiss = { showAddDialog = false },
            onSubmit = { kg ->
                vm.addWeight(kg)
                showAddDialog = false
            }
        )
    }

    if (ui.goalReached) {
        AlertDialog(
            onDismissRequest = { vm.dismissGoalReached() },
            title = { Text("Congratulations! 🎉") },
            text = { Text("You reached your goal weight.") },
            confirmButton = { TextButton(onClick = { vm.dismissGoalReached() }) { Text("Thanks") } }
        )
    }
}

@Composable
private fun StatsCard(ui: ProgressUiState) {
    val entries = ui.entries.sortedBy { it.date }
    val current = entries.lastOrNull()?.weightKg ?: ui.profile?.weightKg
    val start = entries.firstOrNull()?.weightKg
    val delta = if (start != null && current != null) current - start else null

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Current", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8E8E93))
            Text(
                current?.let { String.format(Locale.US, "%.1f kg", it) } ?: "—",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            if (delta != null) {
                val sign = if (delta >= 0) "+" else ""
                Text(
                    "$sign${String.format(Locale.US, "%.1f kg", delta)} since first log",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF8E8E93)
                )
            }
            ui.profile?.goalWeightKg?.let { goal ->
                Text(
                    "Goal: ${String.format(Locale.US, "%.1f kg", goal)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun WeightChart(entries: List<WeightEntry>) {
    val sorted = entries.sortedBy { it.date }
    if (sorted.size < 2) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().height(180.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Log two or more weights to see a chart.", color = Color(0xFF8E8E93))
            }
        }
        return
    }

    val minW = sorted.minOf { it.weightKg }
    val maxW = sorted.maxOf { it.weightKg }
    val range = maxOf(0.5, maxW - minW)
    val tStart = sorted.first().date.toEpochMilli()
    val tEnd = sorted.last().date.toEpochMilli()
    val tRange = maxOf(1L, tEnd - tStart)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().height(180.dp)
    ) {
        Canvas(Modifier.fillMaxSize().padding(16.dp)) {
            val path = Path()
            sorted.forEachIndexed { i, entry ->
                val x = ((entry.date.toEpochMilli() - tStart).toDouble() / tRange.toDouble()).toFloat() * size.width
                val y = size.height - (((entry.weightKg - minW) / range).toFloat() * size.height)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = AppColors.Calorie,
                style = Stroke(width = 4f)
            )
            sorted.forEach { entry ->
                val x = ((entry.date.toEpochMilli() - tStart).toDouble() / tRange.toDouble()).toFloat() * size.width
                val y = size.height - (((entry.weightKg - minW) / range).toFloat() * size.height)
                drawCircle(color = AppColors.Calorie, radius = 5f, center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun WeightRow(entry: WeightEntry, onDelete: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(zone)
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(String.format(Locale.US, "%.1f kg", entry.weightKg), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(fmt.format(entry.date), style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E8E93))
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun AddWeightDialog(useMetric: Boolean, onDismiss: () -> Unit, onSubmit: (Double) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log weight") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (useMetric) "kg" else "lbs") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                val v = input.toDoubleOrNull()
                if (v != null && v > 0.0) {
                    val kg = if (useMetric) v else v / 2.20462
                    onSubmit(kg)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
