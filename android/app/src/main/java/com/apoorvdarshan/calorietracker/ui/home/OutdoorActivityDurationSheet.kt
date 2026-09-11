package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.OutdoorActivitySettings
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

enum class OutdoorActivityKind {
    WALKING,
    RUNNING
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutdoorActivityDurationSheet(
    activity: OutdoorActivityKind,
    onDismiss: () -> Unit,
    onLog: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customMinutes by remember { mutableStateOf("") }
    val parsedMinutes = customMinutes.toIntOrNull()?.takeIf { it in 1..600 }

    val title = when (activity) {
        OutdoorActivityKind.WALKING -> stringResource(R.string.outdoor_activity_walking)
        OutdoorActivityKind.RUNNING -> stringResource(R.string.outdoor_activity_running)
    }
    val icon: ImageVector = when (activity) {
        OutdoorActivityKind.WALKING -> Icons.AutoMirrored.Outlined.DirectionsWalk
        OutdoorActivityKind.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.weight(1f))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.padding(horizontal = 31.dp))
            }

            Text(stringResource(R.string.outdoor_activity_how_long), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(OutdoorActivitySettings.durationPresets) { minutes ->
                    OutlinedButton(
                        onClick = {
                            onLog(minutes)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Calorie)
                    ) {
                        Text(stringResource(R.string.outdoor_activity_minutes, minutes), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            OutlinedTextField(
                value = customMinutes,
                onValueChange = { value ->
                    customMinutes = value.filter { it.isDigit() }.take(3)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.outdoor_activity_custom_minutes)) },
                suffix = { Text(stringResource(R.string.outdoor_activity_min_suffix)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Button(
                onClick = {
                    parsedMinutes?.let(onLog)
                    onDismiss()
                },
                enabled = parsedMinutes != null,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie)
            ) {
                Icon(icon, contentDescription = null)
                Text(
                    stringResource(R.string.outdoor_activity_log, title),
                    modifier = Modifier.padding(start = 8.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
