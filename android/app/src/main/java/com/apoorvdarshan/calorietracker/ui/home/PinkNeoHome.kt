package com.apoorvdarshan.calorietracker.ui.home

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

@Composable
internal fun PinkNeoDateHeader(date: LocalDate, onPick: (LocalDate) -> Unit) {
    val palette = pinkNeoPalette()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    Row(
        Modifier.fillMaxWidth().background(palette.panel).border(1.5.dp, palette.rule).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            val heading = if (date == today) stringResource(R.string.widget_today)
                else date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            Text(
                heading.uppercase(locale), color = palette.ink, fontWeight = FontWeight.Black,
                fontSize = 30.sp, style = PinkNeoCondensed, modifier = Modifier.semantics { heading() }
            )
            Text(
                date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)),
                color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp
            )
        }
        IconButton(
            onClick = {
                val initial = minOf(date, today)
                val dialog = DatePickerDialog(
                    context, { _, year, month, day -> onPick(LocalDate.of(year, month + 1, day)) },
                    initial.year, initial.monthValue - 1, initial.dayOfMonth
                )
                dialog.datePicker.maxDate = today.plusDays(1).atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli() - 1
                dialog.show()
            },
            modifier = Modifier.testTag("home.calendar").size(48.dp).background(palette.wash).border(1.dp, palette.rule)
        ) {
            Icon(Icons.Filled.CalendarToday, stringResource(R.string.label_date), tint = palette.ink,
                modifier = Modifier.size(18.dp))
        }
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
                modifier.background(Brush.linearGradient(palette.hero)).border(1.5.dp, palette.rule).padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
            ) {
                Text(heading, color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp, style = PinkNeoCondensed)
                Text(amount, color = Color.White, fontWeight = FontWeight.Black, style = PinkNeoCondensed,
                    maxLines = if (largeType) 2 else 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 34.sp, stepSize = 1.sp))
                Text(status, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, style = PinkNeoCondensed)
                Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (index in 0 until 20) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color.White.copy(alpha = 0.24f))) {
                            val fill = (progress() * 20 - index).coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(Color.White.copy(alpha = 0.94f)))
                        }
                    }
                }
            }
        }
        val goalPanel: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier.background(palette.panel).border(1.5.dp, palette.rule).padding(7.dp),
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
            currentPanel(Modifier.fillMaxWidth())
            goalPanel(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                currentPanel(Modifier.weight(1f).fillMaxHeight())
                goalPanel(Modifier.width(85.dp).fillMaxHeight())
            }
        }
        if (burnLine != null) {
            Text(burnLine, color = palette.ink, fontSize = 11.sp)
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
