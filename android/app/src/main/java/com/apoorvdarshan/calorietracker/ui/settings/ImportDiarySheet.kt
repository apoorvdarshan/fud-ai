package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.export.DiaryImportMode
import com.apoorvdarshan.calorietracker.export.DiaryImportPreview
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
    val isDark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

    ModalBottomSheet(
        onDismissRequest = { if (!importing) onDismiss() },
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = if (isDark) Color(0xF2141416) else Color(0xFFFAF3EE),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.import_diary_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)

            if (error != null) {
                Text(error, color = Color(0xFFFF3B30), fontSize = 14.sp, lineHeight = 20.sp)
                SecondaryAction(stringResource(R.string.action_done), enabled = true, onClick = onDismiss)
            } else if (preview != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f), fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun PrimaryAction(text: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) AppColors.CalorieGradient
                else Brush.linearGradient(listOf(AppColors.Calorie.copy(alpha = 0.28f), AppColors.Calorie.copy(alpha = 0.28f)))
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.padding(1.dp))
        } else {
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SecondaryAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AppColors.Calorie, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
