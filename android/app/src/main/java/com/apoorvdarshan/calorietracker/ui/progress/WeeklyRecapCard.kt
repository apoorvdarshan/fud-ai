package com.apoorvdarshan.calorietracker.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.EnergyUnit
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WorkoutDayPlan
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.components.LocalEnergyUnit
import com.apoorvdarshan.calorietracker.ui.components.energyTextDisplayed
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Recap of the most recent completed week at the top of Progress (#431), compared
 * with the week before it. The arrows browse earlier weeks. Mirrors iOS
 * WeeklyRecapCard in ios/calorietracker/Views/WeeklyRecapViews.swift.
 */
@Composable
internal fun WeeklyRecapCard(
    foods: List<FoodEntry>,
    weights: List<WeightEntry>,
    burnSessions: List<WorkoutSession>,
    dayPlans: Map<String, WorkoutDayPlan>,
    calorieGoal: Int,
    proteinGoal: Int,
    calorieDisplayRange: Pair<Int, Int> = 0 to 0,
    useMetric: Boolean,
    /** From Settings > Week starts on, like the Home and Workouts week strips. */
    firstDayOfWeek: DayOfWeek,
    /** Re-read on each return to the app, so a week that ended meanwhile rolls over. */
    today: LocalDate,
    /** Re-read with [today]: a time zone change can move entries near midnight to
     *  another day even when the local date stays the same. */
    zone: ZoneId
) {
    // Weeks stepped back from the most recent completed one; session-only.
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    val recap = remember(foods, weights, burnSessions, dayPlans, calorieGoal, proteinGoal, weekOffset, today, firstDayOfWeek, zone) {
        // Both compared weeks: the one before the recapped week through its last day.
        val comparedStart = WeeklyRecapCalculator.weekStart(today, weekOffset + 1, firstDayOfWeek)
        WeeklyRecapCalculator.compute(
            foods = foods,
            workouts = WeeklyRecapCalculator.workoutDays(
                start = comparedStart,
                end = comparedStart.plusDays(13),
                dayPlans = dayPlans,
                burnSessions = burnSessions
            ),
            weights = weights,
            calorieGoal = calorieGoal,
            proteinGoal = proteinGoal,
            today = today,
            offset = weekOffset,
            firstDayOfWeek = firstDayOfWeek,
            zone = zone
        )
    }

    FudGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        padding = 16.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = AppColors.Calorie,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.weekly_recap_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }

            PeriodNavigator(
                start = recap.weekStart,
                end = recap.weekEnd,
                canGoForward = weekOffset > 0,
                onPrevious = { weekOffset += 1 },
                onNext = { weekOffset = (weekOffset - 1).coerceAtLeast(0) },
                previousDescription = stringResource(R.string.weekly_recap_previous_week),
                nextDescription = stringResource(R.string.weekly_recap_next_week),
                embedded = true
            )

            if (recap.current.hasActivity) {
                WeeklyRecapStats(recap.current, recap.previous, calorieGoal)
                recap.current.weightChangeKg?.let { change ->
                    WeeklyRecapRow(
                        icon = Icons.Filled.MonitorWeight,
                        text = stringResource(R.string.weekly_recap_weight_change),
                        value = formatWeightChange(change, useMetric)
                    )
                }
                if (calorieDisplayRange != (0 to 0)) {
                    val floor = calorieDisplayRange.first
                    val ceiling = calorieDisplayRange.second
                    val label = stringResource(R.string.optional_calorie_range)
                    val low = if (floor == 0) "—" else com.apoorvdarshan.calorietracker.ui.components.energyText(floor)
                    val high = if (ceiling == 0) "—" else com.apoorvdarshan.calorietracker.ui.components.energyText(ceiling)
                    Text("$label: $low – $high", fontSize = 12.sp)
                }
                if (recap.highlights.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recap.highlights.forEach { highlight ->
                            WeeklyRecapRow(icon = highlight.icon, text = highlight.text())
                        }
                    }
                }
                Text(
                    stringResource(R.string.weekly_recap_comparison_note),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.weekly_recap_empty),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklyRecapStats(
    current: WeeklyRecapWeekStats,
    previous: WeeklyRecapWeekStats,
    calorieGoal: Int
) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val dayCount = { days: Int -> "${numbers.format(days)}/${numbers.format(7)}" }
    val change = { now: Int, before: Int -> RecapTileDetail(WeeklyRecapCalculator.signedChange(now - before), now > before) }
    // Energy shows in the chosen unit (#520); the comparisons stay kcal.
    val unit = LocalEnergyUnit.current
    val energy = WeeklyRecapCalculator.energyFigures(current, previous, calorieGoal, unit)
    val tiles = listOf(
        RecapTile(
            dayCount(current.loggedDays),
            stringResource(R.string.weekly_recap_days_logged),
            change(current.loggedDays, previous.loggedDays)
        ),
        RecapTile(
            dayCount(current.calorieTargetDays),
            stringResource(R.string.weekly_recap_calorie_target_days),
            change(current.calorieTargetDays, previous.calorieTargetDays)
        ),
        RecapTile(
            dayCount(current.proteinGoalDays),
            stringResource(R.string.weekly_recap_protein_goal_days),
            change(current.proteinGoalDays, previous.proteinGoalDays)
        ),
        RecapTile(
            // kcal keeps the bare number; kJ names its unit so "Avg calories" isn't misread.
            current.averageCalories?.takeIf { unit == EnergyUnit.KJ }
                ?.let { energyTextDisplayed(unit.displayValue(it)) }
                ?: energy.averageCalories ?: "–",
            stringResource(R.string.weekly_recap_avg_calories),
            RecapTileDetail(stringResource(R.string.weekly_recap_target_format, energy.calorieGoal), false)
        ),
        RecapTile(
            numbers.format(current.workouts),
            stringResource(R.string.weekly_recap_workouts),
            change(current.workouts, previous.workouts)
        ),
        RecapTile(
            energyTextDisplayed(energy.activeBurn),
            stringResource(R.string.weekly_recap_active_burn),
            RecapTileDetail(energy.activeBurnChange, current.activeBurnCalories > previous.activeBurnCalories)
        )
    )
    // Large text gets two tiles per row, like the other Progress stat rows.
    val columns = if (shouldUseTwoRowProgressStats(LocalDensity.current.fontScale)) 2 else 3
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { tile -> WeeklyRecapTile(tile, Modifier.weight(1f)) }
            }
        }
    }
}

/** The secondary line of a tile: a change vs the previous week, or a plain caption. */
private data class RecapTileDetail(val text: String, val isImprovement: Boolean)

private data class RecapTile(val value: String, val label: String, val detail: RecapTileDetail)

@Composable
private fun WeeklyRecapTile(tile: RecapTile, modifier: Modifier = Modifier) {
    val (value, label, detail) = tile
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Calorie.copy(alpha = 0.055f))
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = "$value, $label, ${detail.text}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 15.sp, stepSize = 0.5.sp)
        )
        // Two lines reserved in every tile keep the details on one baseline.
        Text(
            label,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            detail.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (detail.isImprovement) AppColors.Calorie
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WeeklyRecapRow(icon: ImageVector, text: String, value: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val WeeklyRecapHighlight.icon: ImageVector
    get() = when (this) {
        WeeklyRecapHighlight.LoggedEveryDay -> Icons.Filled.Verified
        is WeeklyRecapHighlight.LoggingStreak -> Icons.Filled.LocalFireDepartment
        is WeeklyRecapHighlight.MoreWorkouts -> Icons.Filled.FitnessCenter
        is WeeklyRecapHighlight.MoreCalorieTargetDays -> Icons.Filled.TrackChanges
        is WeeklyRecapHighlight.MoreProteinGoalDays -> Icons.Filled.Bolt
        WeeklyRecapHighlight.AverageCaloriesOnTarget -> Icons.Filled.GpsFixed
        is WeeklyRecapHighlight.MoreLoggedDays -> Icons.Filled.EventAvailable
    }

@Composable
private fun WeeklyRecapHighlight.text(): String = when (this) {
    WeeklyRecapHighlight.LoggedEveryDay -> stringResource(R.string.weekly_recap_highlight_every_day)
    is WeeklyRecapHighlight.LoggingStreak ->
        pluralStringResource(R.plurals.weekly_recap_highlight_streak, days, days)
    is WeeklyRecapHighlight.MoreWorkouts ->
        pluralStringResource(R.plurals.weekly_recap_highlight_more_workouts, count, count)
    is WeeklyRecapHighlight.MoreCalorieTargetDays ->
        pluralStringResource(R.plurals.weekly_recap_highlight_more_calorie_days, count, count)
    is WeeklyRecapHighlight.MoreProteinGoalDays ->
        pluralStringResource(R.plurals.weekly_recap_highlight_more_protein_days, count, count)
    WeeklyRecapHighlight.AverageCaloriesOnTarget ->
        stringResource(R.string.weekly_recap_highlight_average_on_target)
    is WeeklyRecapHighlight.MoreLoggedDays ->
        pluralStringResource(R.plurals.weekly_recap_highlight_more_logged_days, count, count)
}
