package com.apoorvdarshan.calorietracker.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.MacroValueFormatter
import com.apoorvdarshan.calorietracker.ui.theme.PinkNeoCondensed
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette
import com.apoorvdarshan.calorietracker.ui.util.valueWithUnit
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
internal fun PinkNeoDateHeader(date: LocalDate, onPick: (LocalDate) -> Unit) {
    val palette = pinkNeoPalette()
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val dateFormatter = remember(locale) {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale)
        }
        Text(
            if (date == today) stringResource(R.string.widget_today) else date.format(dateFormatter),
            color = palette.ink, fontWeight = FontWeight.Black, fontSize = 18.sp,
            lineHeight = 22.sp, style = PinkNeoCondensed,
            modifier = Modifier.weight(1f).testTag("home.dateHeading").semantics { heading() }
        )
        IconButton(
            onClick = { showCalendar = true },
            modifier = Modifier.testTag("home.calendar").size(48.dp)
        ) {
            Icon(Icons.Filled.CalendarToday, stringResource(R.string.label_date), tint = palette.ink,
                modifier = Modifier.size(18.dp))
        }
    }
    if (showCalendar) {
        HomeCalendarPopup(
            initialDate = minOf(date, today),
            today = today,
            onDismiss = { showCalendar = false },
            onPick = {
                onPick(it)
                showCalendar = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeCalendarPopup(
    initialDate: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit
) {
    // Material date-picker values represent calendar days at UTC midnight.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = minOf(1900, initialDate.year)..today.year,
        selectableDates = remember(today) {
            val max = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= max
            }
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("home.calendarPopup"),
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onPick(java.time.Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate())
                }
            }, enabled = state.selectedDateMillis != null) {
                Text(stringResource(R.string.action_done))
            }
        }
    ) {
        DatePicker(state, title = null, headline = null, showModeToggle = false)
    }
}

@Composable
internal fun PinkNeoEnergyPanels(
    heading: String,
    amount: String,
    goal: String,
    status: String,
    progress: () -> Float,
    burnLine: String?
) {
    val palette = pinkNeoPalette()
    val largeType = LocalDensity.current.fontScale >= 1.45f
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).testTag("home.calorieSummary"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val currentPanel: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier.padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
            ) {
                Text(heading, color = palette.onAction, fontWeight = FontWeight.Black, fontSize = 12.sp, style = PinkNeoCondensed)
                Text(amount, color = palette.onAction, fontWeight = FontWeight.Black, style = PinkNeoCondensed,
                    maxLines = if (largeType) 2 else 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 34.sp, stepSize = 1.sp))
                Text(status, color = palette.onAction, fontWeight = FontWeight.Bold, fontSize = 13.sp, style = PinkNeoCondensed)
                PinkNeoEnergySegments(progress)
            }
        }
        val goalPanel: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier.padding(7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
            ) {
                val locale = LocalConfiguration.current.locales[0]
                Text(stringResource(R.string.progress_stat_goal).uppercase(locale),
                    color = palette.ink, fontWeight = FontWeight.Black, fontSize = 11.sp, style = PinkNeoCondensed)
                Icon(Icons.Filled.GpsFixed, null, tint = palette.ink, modifier = Modifier.size(21.dp))
                Text(goal, color = palette.ink, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    fontSize = 15.sp, style = PinkNeoCondensed)
            }
        }
        if (largeType) {
            currentPanel(Modifier.fillMaxWidth().background(palette.action).border(1.5.dp, palette.rule))
            goalPanel(Modifier.fillMaxWidth().background(palette.panel).border(1.5.dp, palette.rule))
        } else {
            val gap = Arrangement.spacedBy(7.dp)
            val goalWidth = 85.dp
            Box(Modifier.fillMaxWidth()) {
                // TextAutoSize can need more height than its intrinsic estimate.
                // Measure the foreground first; the two frames follow its height.
                Row(Modifier.matchParentSize(), horizontalArrangement = gap) {
                    Spacer(Modifier.weight(1f).fillMaxHeight().background(palette.action).border(1.5.dp, palette.rule))
                    Spacer(Modifier.width(goalWidth).fillMaxHeight().background(palette.panel).border(1.5.dp, palette.rule))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = gap,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    currentPanel(Modifier.weight(1f))
                    goalPanel(Modifier.width(goalWidth))
                }
            }
        }
        if (burnLine != null) {
            Text(burnLine, color = palette.ink, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PinkNeoEnergySegments(progress: () -> Float) {
    val palette = pinkNeoPalette()
    Canvas(Modifier.fillMaxWidth().height(10.dp).testTag("home.calorieSegments")) {
        val count = 20
        val gap = minOf(2.dp.toPx(), size.width / (2 * count))
        val width = (size.width - gap * (count - 1)) / count
        // Read the animation in the draw phase. It invalidates this canvas, not
        // composition and measurement of the surrounding panels and text.
        val filled = progress().coerceIn(0f, 1f) * count
        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        for (index in 0 until count) {
            val x = (if (rtl) count - 1 - index else index) * (width + gap)
            drawRect(palette.emptyActionSegment, Offset(x, 0f), Size(width, size.height))
            val fillWidth = width * (filled - index).coerceIn(0f, 1f)
            if (fillWidth > 0f) {
                val start = if (rtl) x + width - fillWidth else x
                drawRect(palette.onAction, Offset(start, 0f), Size(fillWidth, size.height))
            }
        }
    }
}

@Composable
internal fun PinkNeoNutrient(
    label: String,
    current: Double,
    goal: Double,
    unit: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val palette = pinkNeoPalette()
    val fraction = if (goal > 0) (current / goal).toFloat().coerceIn(0f, 1f) else 0f
    val status = when {
        goal <= 0 -> stringResource(R.string.home_macro_no_goal)
        current == goal -> stringResource(R.string.home_macro_goal_reached)
        else -> stringResource(
            if (current < goal) R.string.home_macro_left_format else R.string.home_macro_over_format,
            valueWithUnit(MacroValueFormatter.display(kotlin.math.abs(goal - current)), unit)
        )
    }
    val locale = LocalConfiguration.current.locales[0]
    Column(
        modifier.background(palette.panel).border(0.5.dp, palette.rule).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val name: @Composable () -> Unit = {
            Text(label.uppercase(locale), color = palette.ink, fontWeight = FontWeight.Black, maxLines = 2,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 1.sp),
                overflow = TextOverflow.Ellipsis)
        }
        val amount: @Composable () -> Unit = {
            Text(valueWithUnit(MacroValueFormatter.display(current), unit), color = palette.ink,
                fontWeight = FontWeight.Black, maxLines = 2,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 17.sp, stepSize = 1.sp))
        }
        if (compact && LocalDensity.current.fontScale < 1.45f) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                name()
                amount()
            }
        } else {
            name()
            amount()
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(palette.ink.copy(alpha = 0.14f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(palette.accent))
        }
        Text(status, color = palette.ink, fontWeight = FontWeight.Medium, maxLines = 2,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 1.sp),
            overflow = TextOverflow.Ellipsis)
    }
}
