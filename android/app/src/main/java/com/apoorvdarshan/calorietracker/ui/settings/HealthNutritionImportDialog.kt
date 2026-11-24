package com.apoorvdarshan.calorietracker.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.components.FlashAlertDialog
import com.apoorvdarshan.calorietracker.ui.components.LocalEnergyUnit
import com.apoorvdarshan.calorietracker.data.nutritionImportCandidates
import com.apoorvdarshan.calorietracker.data.healthNutritionDateRange
import com.apoorvdarshan.calorietracker.services.health.ExternalNutrition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Explicit foreground import, independent of the one-shot app-owned restore. */
@Composable
internal fun HealthNutritionImportDialog(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var fromText by remember { mutableStateOf(LocalDate.now().minusDays(29).toString()) }
    var toText by remember { mutableStateOf(LocalDate.now().toString()) }
    var records by remember { mutableStateOf<List<ExternalNutrition>?>(null) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var rangeSummary by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    val selected = records.orEmpty().filter { it.originPackage == selectedSource }
    val permissionLauncher = rememberLauncherForActivityResult(container.health.permissionRequestContract()) {
        message = resources.getString(R.string.health_import_access_result)
        records = null
        selectedSource = null
    }
    fun preview() {
        busy = true
        message = null
        rangeSummary = null
        records = null
        selectedSource = null
        scope.launch {
            try {
                if (!container.health.isAvailable()) {
                    message = resources.getString(R.string.health_import_access_needed)
                    return@launch
                }
                val now = Instant.now()
                val zone = ZoneId.systemDefault()
                val range = runCatching {
                    healthNutritionDateRange(fromText, toText, now, zone)
                }.getOrNull()
                if (range == null) {
                    message = resources.getString(R.string.health_import_invalid_dates)
                    return@launch
                }
                rangeSummary = resources.getString(
                    R.string.health_import_range_summary,
                    range.from.toString(), range.through.toString(),
                    now.atZone(zone).toLocalDate().toString(), zone.id
                )
                val historyAccess = container.health.nutritionImportHistoryAccess()
                if (!historyAccess && range.start.isBefore(now.minus(30, ChronoUnit.DAYS))) {
                    message = resources.getString(
                        if (container.health.supportsNutritionHistory()) R.string.health_import_history_needed
                        else R.string.health_import_history_unsupported
                    )
                    return@launch
                }
                val read = container.health.readNutrition(range.start, range.endExclusive, onFailure = { throw it })
                if (read == null) {
                    message = resources.getString(R.string.health_import_failed)
                } else {
                    records = nutritionImportCandidates(read, context.packageName, container.prefs.nutritionImportedKeys.first())
                    selectedSource = records?.map { it.originPackage }?.distinct()?.singleOrNull()
                    if (records.isNullOrEmpty()) message = resources.getString(R.string.health_import_empty)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failures = generateSequence<Throwable>(error) { it.cause }.take(10).toList()
                val quotaExceeded = failures.any {
                    val detail = it.message.orEmpty()
                    detail.contains("quota", ignoreCase = true) ||
                        detail.contains("rate limit", ignoreCase = true)
                }
                message = resources.getString(when {
                    quotaExceeded -> R.string.health_import_rate_limited
                    failures.any { it is SecurityException } -> R.string.health_import_access_needed
                    else -> R.string.health_import_read_failed
                })
            } finally {
                busy = false
            }
        }
    }
    FlashAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.health_import_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!finished) {
                    Text(stringResource(R.string.health_import_description))
                    OutlinedTextField(fromText, {
                        fromText = it; records = null; selectedSource = null; message = null; rangeSummary = null
                    }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.health_import_from)) }, singleLine = true, enabled = !busy)
                    OutlinedTextField(toText, {
                        toText = it; records = null; selectedSource = null; message = null; rangeSummary = null
                    }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.health_import_to)) }, singleLine = true, enabled = !busy)
                    Text(stringResource(R.string.health_import_history_hint), style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy && container.health.isAvailable(), onClick = {
                        runCatching { permissionLauncher.launch(container.health.nutritionImportPermissions) }
                            .onFailure { message = resources.getString(R.string.health_import_access_needed) }
                    }) { Text(stringResource(R.string.health_import_grant)) }
                    TextButton(enabled = !busy, onClick = ::preview) { Text(stringResource(R.string.health_import_preview)) }
                    records?.map { it.originPackage }?.distinct()?.sorted()?.forEach { source ->
                        Row {
                            RadioButton(selected = selectedSource == source, enabled = !busy, onClick = { selectedSource = source })
                            // Package IDs remain unambiguous even when Android hides an app label.
                            Column(Modifier.weight(1f)) {
                                val label = remember(source) {
                                    runCatching {
                                        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(source, 0)).toString()
                                    }.getOrDefault(source)
                                }
                                Text(label)
                                if (label != source) Text(source, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (selected.isNotEmpty()) {
                        val energyUnit = LocalEnergyUnit.current
                        Text(
                            stringResource(
                                energyUnit.pick(R.string.health_import_count, R.string.health_import_count_kj),
                                selected.size,
                                energyUnit.displayValue(selected.sumOf { it.calories ?: 0.0 }.toInt())
                            )
                        )
                        selected.take(5).forEach { record ->
                            Text("${record.time.atZone(ZoneId.systemDefault()).toLocalDate()} · ${record.name?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.health_import_unnamed)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(R.string.health_import_local_only), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (busy) CircularProgressIndicator()
                rangeSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                message?.let { Text(it) }
            }
        },
        confirmButton = {
            if (!finished) TextButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    try {
                        // Permission may have been revoked while the preview was displayed.
                        if (!container.health.hasNutritionRead()) {
                            message = resources.getString(R.string.health_import_access_needed)
                            return@launch
                        }
                        val count = container.prefs.importHealthNutrition(selected, resources.getString(R.string.health_import_unnamed))
                        message = resources.getString(R.string.health_import_done, count)
                        finished = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        message = resources.getString(R.string.health_import_failed)
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.health_import_action)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.health_import_close)) } }
    )
}
