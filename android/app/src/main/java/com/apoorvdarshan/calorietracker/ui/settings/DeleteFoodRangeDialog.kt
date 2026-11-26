package com.apoorvdarshan.calorietracker.ui.settings

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.FoodRepository
import com.apoorvdarshan.calorietracker.models.FoodEntry
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DeleteFoodRangeDialog(repository: FoodRepository, onDismiss: () -> Unit) {
    val entries by repository.entries.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var start by remember { mutableStateOf(LocalDate.now()) }
    var end by remember { mutableStateOf(LocalDate.now()) }
    var preview by remember { mutableStateOf<List<FoodEntry>?>(null) }
    var alsoHealth by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<Int?>(null) }
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    fun pickDate(date: LocalDate, update: (LocalDate) -> Unit) {
        DatePickerDialog(context, { _, year, month, day ->
            update(LocalDate.of(year, month + 1, day))
            preview = null
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.delete_food_by_date)) },
        text = {
            Column {
                val result = resultMessage
                if (result != null) {
                    Text(stringResource(result))
                } else if (confirming) {
                    Text(stringResource(if (alsoHealth) R.string.delete_food_health_warning else R.string.delete_food_local_warning))
                } else {
                    TextButton(onClick = { pickDate(start) { start = it } }) {
                        Text("${stringResource(R.string.export_from)}: ${start.format(dateFormat)}")
                    }
                    TextButton(onClick = { pickDate(end) { end = it } }) {
                        Text("${stringResource(R.string.export_to)}: ${end.format(dateFormat)}")
                    }
                    preview?.let { foods ->
                        Text(stringResource(R.string.delete_food_count, foods.size))
                        Text(stringResource(R.string.delete_food_scope))
                        if (foods.isNotEmpty()) {
                            androidx.compose.foundation.layout.Row {
                                Checkbox(alsoHealth, { alsoHealth = it })
                                Text(stringResource(R.string.delete_food_health_toggle))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                resultMessage != null -> TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
                confirming -> TextButton(enabled = !busy, onClick = {
                    val selected = preview ?: return@TextButton
                    busy = true
                    // Outside the dialog's scope: rotating the screen must not cancel a
                    // deletion whose local rows are already gone, leaving Health records
                    // behind with no saved request to remove them.
                    repository.deletePreviewedFoodSurvivingUi(selected, alsoHealth) { localOk, healthOk ->
                        busy = false
                        when {
                            // A failed diary write must not claim entries were deleted.
                            !localOk -> resultMessage = R.string.delete_food_failed
                            !healthOk -> resultMessage = R.string.delete_food_health_failed
                            else -> onDismiss()
                        }
                    }
                }) { Text(stringResource(R.string.action_delete)) }
                else -> {
                    if (preview?.isNotEmpty() == true) {
                        TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.action_delete)) }
                    } else {
                        TextButton(enabled = start <= end, onClick = {
                            val zone = ZoneId.systemDefault()
                            alsoHealth = false
                            preview = entries.filter { it.timestamp.atZone(zone).toLocalDate() in start..end }
                        }) { Text(stringResource(R.string.delete_food_preview)) }
                    }
                }
            }
        },
        dismissButton = {
            if (resultMessage == null) {
                TextButton(enabled = !busy, onClick = { if (confirming) confirming = false else onDismiss() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}
