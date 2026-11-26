package com.apoorvdarshan.calorietracker.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.ImportedHealthWorkout
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.components.FudIconBubble
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.ui.workouts.ImportedHealthWorkoutRow

/** Opens the read-only Health Connect workout history, beside the calculated-burn history. */
@Composable
internal fun ImportedWorkoutHistoryLink(count: Int, onClick: () -> Unit) {
    FudGlassSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        cornerRadius = 18.dp,
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FudIconBubble(icon = Icons.Filled.Watch, size = 28.dp, iconSize = 16.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.health_workouts_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    pluralStringResource(R.plurals.health_workouts_history_count, count, count),
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
}

/** Every imported session, newest first. Read-only: edits and deletions happen in the recording app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportedWorkoutHistorySheet(
    workouts: List<ImportedHealthWorkout>,
    useMetricDistance: Boolean,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetSurface = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFAF3EE)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.health_workouts_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done), color = AppColors.Calorie)
                }
            }
            Spacer(Modifier.height(12.dp))
            FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 0.dp) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(vertical = 4.dp)) {
                    items(workouts, key = { it.id }) { workout ->
                        ImportedHealthWorkoutRow(
                            workout = workout,
                            useMetricDistance = useMetricDistance,
                            showDate = true,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                        Box(
                            Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.health_workouts_footnote),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
            )
        }
    }
}
