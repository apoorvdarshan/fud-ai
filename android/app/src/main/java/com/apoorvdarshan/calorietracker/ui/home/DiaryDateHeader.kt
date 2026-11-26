package com.apoorvdarshan.calorietracker.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.R
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
internal fun DiaryDateHeader(date: LocalDate, onPick: (LocalDate) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val dateFormatter = remember(locale) {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale)
        }
        Text(
            if (date == today) stringResource(R.string.widget_today) else date.format(dateFormatter),
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).testTag("home.dateHeading").semantics { heading() }
        )
        IconButton(
            onClick = { showCalendar = true },
            modifier = Modifier.testTag("home.calendar").size(48.dp)
        ) {
            Icon(Icons.Filled.CalendarToday, stringResource(R.string.label_date), tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
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
