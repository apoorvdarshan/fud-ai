package com.apoorvdarshan.calorietracker.ui.settings

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import com.apoorvdarshan.calorietracker.ui.components.FlashSheetAction
import com.apoorvdarshan.calorietracker.ui.components.FlashBottomSheet as ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.util.mediumDateFormatter
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.export.DiaryExporter
import com.apoorvdarshan.calorietracker.export.DiaryFormat
import com.apoorvdarshan.calorietracker.export.DiaryRange
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.ui.theme.FlashSpacing
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportDiarySheet(
    container: AppContainer,
    profile: UserProfile?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val emptyDiaryMessage = stringResource(R.string.export_empty_diary)
    val exportTitle = stringResource(R.string.export_diary_title)
    val exportFailedMessage = stringResource(R.string.export_failed)
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val neo = pinkNeoPalette()

    var range by remember { mutableStateOf(DiaryRange.THIS_WEEK) }
    var format by remember { mutableStateOf(DiaryFormat.JSON) }
    var customStart by remember { mutableStateOf(LocalDate.now().minusDays(7)) }
    var customEnd by remember { mutableStateOf(LocalDate.now()) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end" | null
    var status by remember { mutableStateOf<String?>(null) }

    val mealNames: Map<MealType, String> = MealType.values().associateWith { stringResource(it.displayNameRes) }
    val niceDate = remember { mediumDateFormatter() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = FlashSpacing.SheetInset, vertical = FlashSpacing.ItemGap),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.SectionGap),
        ) {
            Text(stringResource(R.string.export_diary_title), fontSize = 22.sp, fontWeight = FontWeight.Black)

            Text(stringResource(R.string.export_range), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DiaryRange.values().forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = { range = r },
                        label = { Text(stringResource(r.labelRes)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = neo.action,
                            selectedLabelColor = neo.onAction,
                        ),
                    )
                }
            }

            if (range == DiaryRange.CUSTOM) {
                DateRow(stringResource(R.string.export_from), customStart.format(niceDate)) { picking = "start" }
                DateRow(stringResource(R.string.export_to), customEnd.format(niceDate)) { picking = "end" }
            }

            Text(stringResource(R.string.export_format), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiaryFormat.values().forEach { f ->
                    FilterChip(
                        selected = format == f,
                        onClick = { format = f },
                        label = { Text(f.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = neo.action,
                            selectedLabelColor = neo.onAction,
                        ),
                    )
                }
            }

            status?.let {
                Text(it, color = Color(0xFFFF3B30), fontSize = 13.sp)
            }

            FlashSheetAction(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                        status = null
                        scope.launch {
                            val entries = container.foodRepository.entries.first()
                            val water = container.waterRepository.entries.first()
                            val (lo, hi) = DiaryExporter.resolveRange(range, customStart, customEnd, entries, water)
                            val result = DiaryExporter.build(
                                entries = entries, start = lo, end = hi, format = format,
                                waterEntries = water, profile = profile, mealDisplay = { mealNames[it] ?: it.name },
                                energyUnit = container.prefs.energyUnit.first(),
                            )
                            if (result == null) {
                                status = emptyDiaryMessage
                                return@launch
                            }
                            val (name, content) = result
                            try {
                                val dir = File(context.cacheDir, "capture").apply { mkdirs() }
                                val file = File(dir, name)
                                file.writeText(content)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = format.mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, exportTitle))
                                onDismiss()
                            } catch (e: Exception) {
                                status = e.localizedMessage ?: exportFailedMessage
                            }
                        }
                }
            ) {
                Text(stringResource(R.string.export_action))
            }
        }
    }

    if (picking != null) {
        val editingStart = picking == "start"
        val initial = (if (editingStart) customStart else customEnd)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dpState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        if (editingStart) customStart = picked else customEnd = picked
                    }
                    picking = null
                }) { Text(stringResource(R.string.action_ok), color = AppColors.Calorie) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DatePicker(state = dpState)
        }
    }
}

@Composable
private fun DateRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, pinkNeoPalette().rule)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Calorie)
    }
}
