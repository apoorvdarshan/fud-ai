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
import com.apoorvdarshan.calorietracker.data.nutritionImportCandidates
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
        records = null
        selectedSource = null
        scope.launch {
            try {
                if (!container.health.isAvailable() || !container.health.hasNutritionRead()) {
                    message = resources.getString(R.string.health_import_access_needed)
                    return@launch
                }
                val range = runCatching {
                    val from = LocalDate.parse(fromText)
                    val to = LocalDate.parse(toText)
                    require(!from.isAfter(to) && !to.isAfter(LocalDate.now()))
                    from.atStartOfDay(ZoneId.systemDefault()).toInstant() to
                        minOf(to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant(), Instant.now())
                }.getOrNull()
                if (range == null) {
                    message = resources.getString(R.string.health_import_invalid_dates)
                    return@launch
                }
                // Conservative fallback: never imply older history was searched without access.
                if (!container.health.hasNutritionHistory() &&
                    range.first.isBefore(Instant.now().minus(30, ChronoUnit.DAYS))) {
                    message = resources.getString(R.string.health_import_history_needed)
                    return@launch
                }
                val read = container.health.readNutrition(range.first, range.second)
                if (read == null) {
                    message = resources.getString(R.string.health_import_failed)
                } else {
                    records = nutritionImportCandidates(read, context.packageName, container.prefs.nutritionImportedKeys.first())
                    selectedSource = records?.map { it.originPackage }?.distinct()?.singleOrNull()
                    if (records.isNullOrEmpty()) message = resources.getString(R.string.health_import_empty)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = resources.getString(R.string.health_import_failed)
            } finally {
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.health_import_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!finished) {
                    Text(stringResource(R.string.health_import_description))
                    OutlinedTextField(fromText, {
                        fromText = it; records = null; selectedSource = null; message = null
                    }, label = { Text(stringResource(R.string.health_import_from)) }, singleLine = true, enabled = !busy)
                    OutlinedTextField(toText, {
                        toText = it; records = null; selectedSource = null; message = null
                    }, label = { Text(stringResource(R.string.health_import_to)) }, singleLine = true, enabled = !busy)
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
                        Text(stringResource(R.string.health_import_count, selected.size, selected.sumOf { it.calories ?: 0.0 }.toInt()))
                        selected.take(5).forEach { record ->
                            Text("${record.time.atZone(ZoneId.systemDefault()).toLocalDate()} · ${record.name?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.health_import_unnamed)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(R.string.health_import_local_only), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (busy) CircularProgressIndicator()
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
