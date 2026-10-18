package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.ImportedHealthWorkout
import com.apoorvdarshan.calorietracker.models.importedWorkoutTypeLabel
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.components.energyText
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Read-only Health Connect sessions for the selected diary day (iOS ImportedHealthWorkoutDaySection). */
@Composable
internal fun ImportedHealthWorkoutDayCard(
    workouts: List<ImportedHealthWorkout>,
    useMetricDistance: Boolean,
    modifier: Modifier = Modifier
) {
    FudGlassSurface(modifier = modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Watch,
                    contentDescription = null,
                    tint = AppColors.Calorie,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    stringResource(R.string.settings_health_connect),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.health_workouts_imported_badge),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            workouts.forEachIndexed { index, workout ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
                ImportedHealthWorkoutRow(workout = workout, useMetricDistance = useMetricDistance)
            }
        }
    }
}

/** One imported session: type, active calories, duration · time range · distance, and the recording app. */
@Composable
internal fun ImportedHealthWorkoutRow(
    workout: ImportedHealthWorkout,
    useMetricDistance: Boolean,
    modifier: Modifier = Modifier,
    showDate: Boolean = false
) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()) }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()) }
    val duration = stringResource(R.string.outdoor_activity_minutes, workout.durationMinutes)
    val timeRange = "${timeFormatter.format(workout.startedAt.atZone(zone))} – " +
        timeFormatter.format(workout.endedAt.atZone(zone))
    val distance = workout.distanceMeters?.let { importedWorkoutDistanceText(it, useMetricDistance) }
    val secondary = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(importedWorkoutTypeLabel(workout.exerciseType)),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            workout.activeCalories?.let { kcal ->
                Text(
                    energyText(kcal),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Calorie
                )
            }
        }
        Text(
            listOfNotNull(duration, timeRange, distance).joinToString(" · "),
            fontSize = 13.sp,
            color = secondary.copy(alpha = 0.58f)
        )
        if (showDate) {
            workout.diaryDate?.let { day ->
                Text(dateFormatter.format(day), fontSize = 12.sp, color = secondary.copy(alpha = 0.5f))
            }
        }
        Text(
            workout.sourceSummary,
            fontSize = 12.sp,
            color = secondary.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "5.2 km" / "3.2 mi". */
@Composable
private fun importedWorkoutDistanceText(meters: Double, metric: Boolean): String {
    val format = remember {
        NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 1 }
    }
    val value = format.format(importedWorkoutDistanceValue(meters, metric))
    return stringResource(
        if (metric) R.string.health_workouts_distance_km else R.string.health_workouts_distance_mi,
        value
    )
}

/** Distance in kilometers, or miles when the user measures in feet and inches. */
internal fun importedWorkoutDistanceValue(meters: Double, metric: Boolean): Double =
    if (metric) meters / 1_000.0 else meters / 1_609.344
