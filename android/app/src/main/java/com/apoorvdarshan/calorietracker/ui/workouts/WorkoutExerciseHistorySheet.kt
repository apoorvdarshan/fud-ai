package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.models.ExerciseLiftDay
import com.apoorvdarshan.calorietracker.models.ExerciseLiftHistory
import com.apoorvdarshan.calorietracker.models.WorkoutDate
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class WorkoutExerciseHistoryRequest(
    val itemId: String,
    val name: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutExerciseHistorySheet(
    request: WorkoutExerciseHistoryRequest,
    selectedDate: LocalDate,
    weightUnit: WorkoutWeightUnit,
    history: List<ExerciseLiftDay>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                request.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (history.isEmpty()) {
                Text(
                    "No lift history yet",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(history, key = { it.dateKey }) { day ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                dayTitle(day.dateKey, selectedDate),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                ExerciseLiftHistory.formatSummary(day.sets, weightUnit),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun dayTitle(dateKey: String, selectedDate: LocalDate): String {
    val date = WorkoutDate.parse(dateKey) ?: return dateKey
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date == selectedDate -> "Selected day"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault()))
    }
}
