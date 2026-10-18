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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.apoorvdarshan.calorietracker.models.ImportedHealthWorkout
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.ui.components.LocalEnergyUnit
import com.apoorvdarshan.calorietracker.ui.components.energyText
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
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
                    stringResource(R.string.progress_workout_stat_total) to energyText(total),
                    stringResource(R.string.progress_workout_stat_average) to
                        energyText(dailyBurns.map(Pair<LocalDate, Int>::second).average().roundToInt()),
                    stringResource(R.string.progress_workout_stat_latest) to energyText(dailyBurns.last().second),
                    stringResource(R.string.progress_workout_stat_days) to dailyBurns.size.toString()
                )
            )
            val energyUnit = LocalEnergyUnit.current
            val shownBurns = remember(dailyBurns, energyUnit) {
                dailyBurns.map { (day, kcal) -> day to energyUnit.displayValue(kcal) }
            }
            WorkoutBurnChart(shownBurns)
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

/**
 * Sessions other apps shared with Health Connect over the selected window
 * (iOS ImportedHealthWorkoutChartSection). Display-only: these calories never feed
 * Energy Burn goals or Fud AI's own calculated burns.
 */
@Composable
internal fun ImportedWorkoutProgressSection(
    workouts: List<ImportedHealthWorkout>,
    start: LocalDate,
    end: LocalDate
) {
    val days = remember(workouts, start, end) { importedWorkoutDays(workouts, start, end) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(R.string.health_workouts_title),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (days.isEmpty()) {
            ProgressEmptyText(stringResource(R.string.health_workouts_no_range))
        } else {
            val summary = remember(days) { importedWorkoutSummary(days) }
            ProgressStatRow(
                listOf(
                    stringResource(R.string.health_workouts_stat_sessions) to summary.sessions.toString(),
                    stringResource(R.string.progress_workout_stat_total) to energyText(summary.totalCalories),
                    stringResource(R.string.progress_workout_stat_average) to energyText(summary.averageCalories),
                    stringResource(R.string.progress_workout_stat_days) to days.size.toString()
                )
            )
            val dateFormatter = remember {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault())
            }
            DailyBarChart(
                values = days.map { it.date to it.sessionCount },
                contentDescription = stringResource(
                    R.string.health_workouts_chart_description,
                    dateFormatter.format(days.first().date),
                    dateFormatter.format(days.last().date),
                    summary.sessions
                ),
                dateFormatter = dateFormatter
            )
        }
        ProgressFootnote(stringResource(R.string.health_workouts_footnote))
    }
}

internal data class ImportedWorkoutDay(val date: LocalDate, val sessionCount: Int, val totalCalories: Int)

/** Sessions and active calories per diary day from [start] through [end], oldest first;
 *  only days with sessions (iOS ImportedHealthWorkoutAggregation.daily). */
internal fun importedWorkoutDays(
    workouts: List<ImportedHealthWorkout>,
    start: LocalDate,
    end: LocalDate
): List<ImportedWorkoutDay> =
    workouts.mapNotNull { workout -> workout.diaryDate?.takeIf { it in start..end }?.let { it to workout } }
        .groupBy({ it.first }, { it.second })
        .map { (date, sessions) ->
            ImportedWorkoutDay(date, sessions.size, sessions.sumOf { it.activeCalories ?: 0 })
        }
        .sortedBy { it.date }

internal data class ImportedWorkoutSummary(val sessions: Int, val totalCalories: Int, val averageCalories: Int)

/** Totals for the chart badges. The average spans only days with calories, so a session
 *  whose app recorded none does not read as a zero-calorie day. */
internal fun importedWorkoutSummary(days: List<ImportedWorkoutDay>): ImportedWorkoutSummary {
    val withCalories = days.filter { it.totalCalories > 0 }
    val total = days.sumOf { it.totalCalories }
    val average = if (withCalories.isEmpty()) 0
        else (withCalories.sumOf { it.totalCalories }.toDouble() / withCalories.size).roundToInt()
    return ImportedWorkoutSummary(days.sumOf { it.sessionCount }, total, average)
}

@Composable
internal fun StepsProgressSection(
    days: List<Pair<LocalDate, Int>>?,
    chartStart: LocalDate?,
    chartEnd: LocalDate,
    weekly: Boolean,
    historyMayBeMissing: Boolean
) {
    val numberFormat = remember { NumberFormat.getIntegerInstance(Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(R.string.progress_metric_steps),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        when {
            days == null -> Box(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = AppColors.Calorie
                )
            }
            days.isEmpty() -> ProgressEmptyText(stringResource(R.string.progress_steps_no_range))
            else -> {
                val summary = remember(days) { stepsSummary(days) }
                ProgressStatRow(
                    listOf(
                        stringResource(R.string.progress_steps_stat_total) to numberFormat.format(summary.total),
                        stringResource(R.string.progress_steps_stat_average) to numberFormat.format(summary.average),
                        stringResource(R.string.progress_steps_stat_best_day) to
                            numberFormat.format(summary.best?.second ?: 0)
                    )
                )
                val firstDayOfWeek = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
                // All has no fixed start: its chart begins at the first day with steps.
                val start = chartStart ?: days.first().first
                val bars = remember(days, start, chartEnd, weekly, firstDayOfWeek) {
                    stepChartBars(days, start, chartEnd, weekly, firstDayOfWeek)
                }
                val dateFormatter = remember {
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault())
                }
                if (bars.isNotEmpty()) DailyBarChart(
                    values = bars,
                    contentDescription = stringResource(
                        R.string.progress_steps_chart_description,
                        dateFormatter.format(bars.first().first),
                        dateFormatter.format(bars.last().first),
                        numberFormat.format(summary.total),
                        numberFormat.format(summary.best?.second ?: 0)
                    ),
                    dateFormatter = dateFormatter
                )
                if (weekly) ProgressFootnote(stringResource(R.string.progress_steps_weekly_note))
            }
        }
        if (historyMayBeMissing) ProgressFootnote(stringResource(R.string.progress_steps_history_note))
        ProgressFootnote(stringResource(R.string.progress_steps_footnote))
    }
}

internal data class StepsSummary(val total: Int, val average: Int, val best: Pair<LocalDate, Int>?)

/** Total, mean over the days Health Connect has steps for (a day without data would
 *  otherwise read as a zero-step day), and the best single day. */
internal fun stepsSummary(days: List<Pair<LocalDate, Int>>): StepsSummary {
    val total = days.sumOf { it.second }
    val average = if (days.isEmpty()) 0 else (total.toDouble() / days.size).roundToInt()
    return StepsSummary(total, average, days.maxByOrNull { it.second })
}

/** Longer ranges chart one bar per week so 6M / 1Y / All stay readable. */
internal fun showsWeeklyStepBars(start: LocalDate, end: LocalDate): Boolean =
    end.toEpochDay() - start.toEpochDay() + 1 > 90

/** One bar per day from [start] through [end], or per calendar week when [weekly]
 *  (that week's average over its days with steps, dated at the week's first day),
 *  oldest first. Every slot is present — zero when Health Connect has no steps for
 *  it — because the chart spaces bars by position, so a skipped day would pull the
 *  later bars away from their dates. Averages elsewhere still use only the real days. */
internal fun stepChartBars(
    days: List<Pair<LocalDate, Int>>,
    start: LocalDate,
    end: LocalDate,
    weekly: Boolean,
    firstDayOfWeek: DayOfWeek
): List<Pair<LocalDate, Int>> {
    if (end < start) return emptyList()
    if (!weekly) {
        val stepsByDay = days.toMap()
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .map { day -> day to (stepsByDay[day] ?: 0) }
            .toList()
    }
    val weekOf = { day: LocalDate -> day.with(TemporalAdjusters.previousOrSame(firstDayOfWeek)) }
    val weeklyAverages = days
        .filter { (day, _) -> day in start..end }
        .groupBy { (day, _) -> weekOf(day) }
        .mapValues { (_, values) -> values.map { it.second }.average().roundToInt() }
    val lastWeek = weekOf(end)
    return generateSequence(weekOf(start)) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(lastWeek) }
        .map { week -> week to (weeklyAverages[week] ?: 0) }
        .toList()
}

/** Without the history grant, Health Connect hides records from more than ~30 days
 *  before Fud AI was first connected, so long windows may be partly empty. */
internal fun stepsHistoryMayBeMissing(start: LocalDate, today: LocalDate, hasHistoryAccess: Boolean): Boolean =
    !hasHistoryAccess && start < today.minusDays(30)

@Composable
private fun ProgressFootnote(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )
}

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
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault())
    }
    DailyBarChart(
        values = values,
        contentDescription = stringResource(
            LocalEnergyUnit.current.pick(
                R.string.progress_workout_chart_description,
                R.string.progress_workout_chart_description_kj
            ),
            dateFormatter.format(values.first().first),
            dateFormatter.format(values.last().first),
            values.sumOf(Pair<LocalDate, Int>::second),
            values.last().second
        ),
        dateFormatter = dateFormatter
    )
}

/** Gradient bar per dated value with first/last date labels; shared by the workout
 *  burn and steps cards. */
@Composable
private fun DailyBarChart(
    values: List<Pair<LocalDate, Int>>,
    contentDescription: String,
    dateFormatter: DateTimeFormatter
) {
    val maxValue = values.maxOf(Pair<LocalDate, Int>::second).coerceAtLeast(1).toFloat()
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp))
                .background(AppColors.Calorie.copy(alpha = 0.025f))
                .semantics { this.contentDescription = contentDescription }
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
