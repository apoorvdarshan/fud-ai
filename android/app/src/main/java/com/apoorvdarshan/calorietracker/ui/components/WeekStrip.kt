package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Verbatim port of struct WeekEnergyStrip in
 * ios/calorietracker/Views/HomeComponents.swift.
 *
 * SwiftUI:
 *   ScrollViewReader { proxy in
 *     ScrollView(.horizontal) {
 *       LazyHStack(spacing: 0) {
 *         ForEach(0..<53) { weekIndex in weekRow(for: weekIndex) }
 *       }
 *       .scrollTargetLayout()
 *     }
 *     .scrollTargetBehavior(.paging)
 *     .onAppear { proxy.scrollTo(weekIndex(for: selectedDate), anchor: .trailing) }
 *   }
 *
 *   weekRow = HStack(spacing: 0) { ForEach(0..<7) { dayTile } }
 *
 *   dayTile = Button {
 *     UIImpactFeedbackGenerator(style: .light).impactOccurred()
 *     withAnimation(.snappy(duration: 0.3)) { selectedDate = date }
 *   } label: VStack(spacing: 6) {
 *     Text(weekday-narrow) .font(.system(.caption2, design: .rounded, weight: .medium))
 *                          .foregroundStyle(isSel ? Calorie : .secondary.opacity(0.6))
 *     Text(day) .font(.system(.body, design: .rounded, weight: .semibold))
 *               .foregroundStyle(isSel ? .white : (isToday ? Calorie : .primary))
 *               .frame(width: 36, height: 36)
 *               .background {
 *                 if isSel { Circle().fill(LinearGradient).shadow(Calorie*0.35, r=6, y=3) }
 *                 else if isToday { Circle().strokeBorder(Calorie*0.35, lineWidth: 1.5) }
 *               }
 *   }.frame(maxWidth: .infinity)
 */
private const val TOTAL_WEEKS = 53
private val CURRENT_WEEK_INDEX = TOTAL_WEEKS - 1

@Composable
fun WeekEnergyStrip(
    selectedDate: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    weekStartsOnMonday: Boolean = true
) {
    val firstDow = remember(weekStartsOnMonday) {
        if (weekStartsOnMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    }
    val today = remember { LocalDate.now() }
    val startOfCurrentWeek = remember(today, firstDow) {
        val daysBack = ((today.dayOfWeek.value - firstDow.value) + 7) % 7
        today.minusDays(daysBack.toLong())
    }
    val targetIndex = remember(selectedDate, startOfCurrentWeek) {
        val selectedWeekStart = run {
            val daysBack = ((selectedDate.dayOfWeek.value - firstDow.value) + 7) % 7
            selectedDate.minusDays(daysBack.toLong())
        }
        val weeksDiff = ChronoUnit.WEEKS.between(startOfCurrentWeek, selectedWeekStart).toInt()
        (CURRENT_WEEK_INDEX + weeksDiff).coerceIn(0, TOTAL_WEEKS - 1)
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = targetIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // Scroll to the week containing the selected day — on first frame and whenever the selection
    // crosses into another week (e.g. the Home day-swipe steps past a week boundary).
    LaunchedEffect(targetIndex) {
        listState.animateScrollToItem(targetIndex)
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val pageWidth = maxWidth
        LazyRow(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxWidth()
        ) {
            items((0 until TOTAL_WEEKS).toList()) { weekIndex ->
                val weekStart = startOfCurrentWeek.plusWeeks((weekIndex - CURRENT_WEEK_INDEX).toLong())
                Box(modifier = Modifier.width(pageWidth)) {
                    WeekRow(
                        weekStart = weekStart,
                        selectedDate = selectedDate,
                        today = today,
                        onSelect = onSelect
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekRow(
    weekStart: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            DayTile(
                date = date,
                isSelected = date == selectedDate,
                isToday = date == today,
                onTap = { onSelect(date) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DayTile(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val neo = pinkNeoPalette()
    val locale = LocalConfiguration.current.locales[0]
    val dateDescription = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    Column(
        modifier = modifier
            .selectable(isSelected, role = Role.Button, onClick = onTap)
            .semantics { contentDescription = dateDescription }
            .heightIn(min = 48.dp)
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
    ) {
        Text(
            date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = neo.ink,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 27.dp)
                .let {
                    if (isSelected) it.background(neo.action)
                    else if (isToday) it.border(1.dp, neo.rule)
                    else it
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                java.text.NumberFormat.getIntegerInstance(locale).format(date.dayOfMonth),
                fontWeight = FontWeight.Black,
                color = if (isSelected) neo.onAction else neo.ink,
                maxLines = 1,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                    minFontSize = 8.sp, maxFontSize = 14.sp, stepSize = 0.5.sp
                )
            )
        }
    }
}
