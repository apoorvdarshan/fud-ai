package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.apoorvdarshan.calorietracker.ui.components.FlashSheetAction
import com.apoorvdarshan.calorietracker.ui.components.FlashBottomSheet as ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.export.DiaryImportMode
import com.apoorvdarshan.calorietracker.export.DiaryImportPreview
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportDiarySheet(
    preview: DiaryImportPreview?,
    error: String?,
    importing: Boolean,
    onDismiss: () -> Unit,
    onImport: (DiaryImportMode) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val neo = pinkNeoPalette()
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    ModalBottomSheet(
        onDismissRequest = { if (!importing) onDismiss() },
        sheetState = state
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.import_diary_title), fontSize = 22.sp, fontWeight = FontWeight.Black)

            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 20.sp)
                SecondaryAction(stringResource(R.string.action_done), enabled = true, onClick = onDismiss)
            } else if (preview != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(neo.paper)
                        .border(1.dp, neo.rule)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SummaryRow(stringResource(R.string.import_entries), preview.entries.size.toString())
                    SummaryRow(stringResource(R.string.import_water_entries), preview.waterEntries.size.toString())
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f))
                    SummaryRow(
                        stringResource(R.string.import_date_range),
                        "${preview.startDate.format(dateFormat)} – ${preview.endDate.format(dateFormat)}",
                    )
                }

                Text(
                    stringResource(R.string.import_replace_with_water_explanation),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )

                PrimaryAction(
                    text = stringResource(R.string.import_replace_action),
                    enabled = !importing,
                    loading = importing,
                ) { onImport(DiaryImportMode.REPLACE_DATE_RANGE) }

                SecondaryAction(
                    text = stringResource(R.string.import_add_action),
                    enabled = !importing,
                ) { onImport(DiaryImportMode.ADD_AS_NEW) }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f), fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun PrimaryAction(text: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    FlashSheetAction(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        if (loading) {
            CircularProgressIndicator(color = pinkNeoPalette().ink, strokeWidth = 2.dp, modifier = Modifier.padding(1.dp))
        } else {
            Text(text)
        }
    }
}

@Composable
private fun SecondaryAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    FlashSheetAction(onClick = onClick, enabled = enabled, primary = false, modifier = Modifier.fillMaxWidth()) {
        Text(text)
    }
}
