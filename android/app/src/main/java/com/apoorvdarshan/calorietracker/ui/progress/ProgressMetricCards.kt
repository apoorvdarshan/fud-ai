package com.apoorvdarshan.calorietracker.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun WorkoutProgressSection(entries: List<WorkoutSession>) {
    val dailyBurns = remember(entries) { preferredDailyWorkoutBurns(entries) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(R.string.progress_workout_section),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (dailyBurns.isEmpty()) {
            ProgressEmptyText(stringResource(R.string.progress_workout_no_range))
        } else {
            val total = dailyBurns.sumOf(Pair<LocalDate, Int>::second)
            ProgressStatRow(
                listOf(
                    stringResource(R.string.progress_workout_stat_total) to
                        stringResource(R.string.kcal_value_format, total),
                    stringResource(R.string.progress_workout_stat_average) to
                        stringResource(
                            R.string.kcal_value_format,
                            dailyBurns.map(Pair<LocalDate, Int>::second).average().roundToInt()
                        ),
                    stringResource(R.string.progress_workout_stat_latest) to
                        stringResource(R.string.kcal_value_format, dailyBurns.last().second),
                    stringResource(R.string.progress_workout_stat_days) to dailyBurns.size.toString()
                )
            )
            WorkoutBurnChart(dailyBurns)
        }
    }
}

/**
 * Chooses one reliable calculated-burn snapshot per day. Local/Health Connect restore races can
 * briefly leave duplicate snapshots; the higher sync version wins, then the later completion.
 */
internal fun preferredDailyWorkoutBurns(entries: List<WorkoutSession>): List<Pair<LocalDate, Int>> =
    entries.mapNotNull { session ->
        val date = runCatching { LocalDate.parse(session.diaryDateKey) }.getOrNull()
        val calories = session.caloriesBurned?.takeIf { it in 1..5_000 }
        if (date == null || calories == null) null else date to session
    }.groupBy(Pair<LocalDate, WorkoutSession>::first)
        .mapNotNull { (date, values) ->
            values.map(Pair<LocalDate, WorkoutSession>::second)
                .maxWithOrNull(preferredWorkoutBurnComparator)
                ?.caloriesBurned
                ?.let { date to it }
        }
        .sortedBy(Pair<LocalDate, Int>::first)

private val preferredWorkoutBurnComparator =
    compareBy<WorkoutSession> { it.healthSyncVersion ?: 0 }
        .thenBy(WorkoutSession::completedAt)

@Composable
private fun ProgressEmptyText(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun ProgressStatRow(items: List<Pair<String, String>>) {
    val useTwoRows = shouldUseTwoRowProgressStats(LocalDensity.current.fontScale)
    val rows = if (useTwoRows) items.chunked(2) else listOf(items)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { (label, value) ->
                    ProgressStatItem(
                        label = label,
                        value = value,
                        allowLabelWrap = useTwoRows,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat((if (useTwoRows) 2 else items.size) - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun shouldUseTwoRowProgressStats(fontScale: Float): Boolean = fontScale >= 1.2f

@Composable
private fun ProgressStatItem(
    label: String,
    value: String,
    allowLabelWrap: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(10.dp))
            .background(AppColors.Calorie.copy(alpha = 0.055f))
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            label,
            maxLines = if (allowLabelWrap) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WorkoutBurnChart(values: List<Pair<LocalDate, Int>>) {
    val maxValue = values.maxOf(Pair<LocalDate, Int>::second).coerceAtLeast(1).toFloat()
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault())
    }
    val chartDescription = stringResource(
        R.string.progress_workout_chart_description,
        dateFormatter.format(values.first().first),
        dateFormatter.format(values.last().first),
        values.sumOf(Pair<LocalDate, Int>::second),
        values.last().second
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp))
                .background(AppColors.Calorie.copy(alpha = 0.025f))
                .semantics { contentDescription = chartDescription }
        ) {
            repeat(5) { index ->
                val y = size.height * index / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            val slots = values.size.coerceAtLeast(1)
            val slotWidth = size.width / slots
            val barWidth = (slotWidth * 0.58f).coerceAtMost(48.dp.toPx()).coerceAtLeast(3f)
            values.forEachIndexed { index, (_, calories) ->
                val height = size.height * calories / maxValue
                val left = slotWidth * index + (slotWidth - barWidth) / 2f
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(AppColors.CalorieEnd, AppColors.CalorieStart)),
                    topLeft = Offset(left, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(dateFormatter.format(values.first().first), fontSize = 11.sp, color = secondary)
            Spacer(Modifier.weight(1f))
            if (values.size > 1) {
                Text(dateFormatter.format(values.last().first), fontSize = 11.sp, color = secondary)
            }
        }
    }
}
