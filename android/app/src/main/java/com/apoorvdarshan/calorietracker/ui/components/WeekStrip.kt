package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

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
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // .font(.system(.caption2, design: .rounded, weight: .medium))
        // .foregroundStyle(isSelected ? AppColors.calorie : Color.secondary.opacity(0.6))
        Text(
            narrowDay(date.dayOfWeek),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (isSelected) AppColors.Calorie
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * 0.6f)
        )

        // .font(.system(.body, design: .rounded, weight: .semibold))
        // .foregroundStyle(isSelected ? .white : (isToday ? AppColors.calorie : .primary))
        // .frame(width: 36, height: 36)
        // .background { if isSel Circle.fill(gradient).shadow(...)
        //               else if isToday Circle.strokeBorder(Calorie*0.35, 1.5) }
        Box(
            modifier = Modifier
                .size(36.dp)
                .let {
                    if (isSelected) {
                        it
                            .shadow(
                                elevation = 6.dp,
                                shape = CircleShape,
                                ambientColor = AppColors.Calorie.copy(alpha = 0.35f),
                                spotColor = AppColors.Calorie.copy(alpha = 0.35f)
                            )
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd)))
                    } else if (isToday) {
                        it
                            .clip(CircleShape)
                            .border(1.5.dp, AppColors.Calorie.copy(alpha = 0.35f), CircleShape)
                    } else it
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                date.dayOfMonth.toString(),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    isSelected -> Color.White
                    isToday -> AppColors.Calorie
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

/** SwiftUI's `.dateTime.weekday(.narrow)` — single-letter short day name. */
private fun narrowDay(dow: DayOfWeek): String = when (dow) {
    DayOfWeek.MONDAY -> "M"
    DayOfWeek.TUESDAY -> "T"
    DayOfWeek.WEDNESDAY -> "W"
    DayOfWeek.THURSDAY -> "T"
    DayOfWeek.FRIDAY -> "F"
    DayOfWeek.SATURDAY -> "S"
    DayOfWeek.SUNDAY -> "S"
}
