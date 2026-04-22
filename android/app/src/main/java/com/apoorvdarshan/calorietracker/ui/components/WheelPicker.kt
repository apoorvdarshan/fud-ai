package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

private val ITEM_HEIGHT = 44.dp
private const val VISIBLE_ITEMS = 5
private val ROW_HEIGHT = ITEM_HEIGHT * VISIBLE_ITEMS

/**
 * iOS-style scrolling wheel picker. Items snap to the center row. The highlighted
 * row is styled with full opacity + semibold; rows away from center fade.
 */
@Composable
fun <T> WheelPicker(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() }
) {
    if (items.isEmpty()) return
    val initialIndex = items.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = listState)

    val centerIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (idx, scrolling) ->
                if (!scrolling) {
                    val snapped = items.getOrNull(idx) ?: return@collect
                    if (snapped != selected) onSelect(snapped)
                }
            }
    }

    Box(
        modifier = modifier.height(ROW_HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        // Center row guide lines (iOS-style)
        Box(
            Modifier
                .fillMaxWidth()
                .height(ITEM_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .align(Alignment.Center)
        ) {
            HorizontalDivider(Modifier.align(Alignment.TopCenter), color = Color.White.copy(alpha = 0.08f))
            HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = Color.White.copy(alpha = 0.08f))
        }

        LazyColumn(
            state = listState,
            flingBehavior = fling,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = ITEM_HEIGHT * (VISIBLE_ITEMS / 2)),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(items) { item ->
                val isSelected = item == items.getOrNull(centerIndex)
                val alpha by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0.35f,
                    label = "wheelAlpha"
                )
                Box(
                    modifier = Modifier
                        .height(ITEM_HEIGHT)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label(item),
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = if (isSelected) 24.sp else 20.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)
                    )
                }
            }
        }
    }
}

/**
 * Triple-column iOS-style date picker (Month / Day / Year). Updates the caller
 * any time any wheel lands on a new value.
 */
@Composable
fun DateWheelPicker(
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    minYear: Int = 1920,
    maxYear: Int = LocalDate.now().year,
    modifier: Modifier = Modifier
) {
    val months = remember { (1..12).toList() }
    val years = remember(minYear, maxYear) { (minYear..maxYear).toList().reversed() }
    val daysInMonth = remember(selected.year, selected.monthValue) {
        YearMonth.of(selected.year, selected.monthValue).lengthOfMonth()
    }
    val days = remember(daysInMonth) { (1..daysInMonth).toList() }

    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        WheelPicker(
            items = months,
            selected = selected.monthValue,
            onSelect = { m ->
                val clampedDay = selected.dayOfMonth.coerceAtMost(YearMonth.of(selected.year, m).lengthOfMonth())
                onSelect(LocalDate.of(selected.year, m, clampedDay))
            },
            label = { monthNames[it - 1] },
            modifier = Modifier.weight(1f)
        )
        WheelPicker(
            items = days,
            selected = selected.dayOfMonth.coerceAtMost(daysInMonth),
            onSelect = { d -> onSelect(LocalDate.of(selected.year, selected.monthValue, d)) },
            modifier = Modifier.weight(0.7f)
        )
        WheelPicker(
            items = years,
            selected = selected.year,
            onSelect = { y ->
                val clampedDay = selected.dayOfMonth.coerceAtMost(YearMonth.of(y, selected.monthValue).lengthOfMonth())
                onSelect(LocalDate.of(y, selected.monthValue, clampedDay))
            },
            modifier = Modifier.weight(1f)
        )
    }
}

/** Single-column wheel picker specialized for a numeric range with optional unit suffix. */
@Composable
fun NumericWheelPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
    unit: String? = null,
    modifier: Modifier = Modifier
) {
    val items = remember(min, max) { (min..max).toList() }
    val clamped = value.coerceIn(min, max)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        WheelPicker(
            items = items,
            selected = clamped,
            onSelect = onValueChange,
            modifier = Modifier.weight(1f)
        )
        if (unit != null) {
            Spacer(Modifier.size(8.dp))
            Text(
                unit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.width(48.dp).padding(start = 4.dp)
            )
        }
    }
}

/** Decimal (one-digit-precision) wheel picker. Stores as Int*10 under the hood. */
@Composable
fun DecimalWheelPicker(
    value: Double,
    onValueChange: (Double) -> Unit,
    min: Double,
    max: Double,
    step: Double = 0.1,
    unit: String? = null,
    modifier: Modifier = Modifier
) {
    val scaled = remember(step) { (1.0 / step).toInt() }
    val items = remember(min, max, scaled) {
        val start = (min * scaled).toInt()
        val end = (max * scaled).toInt()
        (start..end).toList()
    }
    val currentScaled = (value * scaled).toInt().coerceIn(items.first(), items.last())
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        WheelPicker(
            items = items,
            selected = currentScaled,
            onSelect = { onValueChange(it.toDouble() / scaled) },
            label = { String.format("%.1f", it.toDouble() / scaled) },
            modifier = Modifier.weight(1f)
        )
        if (unit != null) {
            Spacer(Modifier.size(8.dp))
            Text(
                unit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.width(48.dp).padding(start = 4.dp)
            )
        }
    }
}
