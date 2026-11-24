package com.apoorvdarshan.calorietracker.ui.progress

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette

internal enum class ProgressDestination(
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    MY_PROGRESS(R.string.challenge_destination_progress, Icons.AutoMirrored.Filled.ShowChart),
    WEEKLY_CHALLENGE(R.string.challenge_destination_weekly, Icons.Filled.EmojiEvents)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProgressDestinationSelector(
    selected: ProgressDestination,
    onSelect: (ProgressDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val neo = pinkNeoPalette()
    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = if (LocalDensity.current.fontScale >= 1.3f) 1 else 2
    ) {
        ProgressDestination.entries.forEach { destination ->
            val isSelected = selected == destination
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxRowHeight()
                    .heightIn(min = 48.dp)
                    .background(if (isSelected) neo.action else neo.panel)
                    .border(1.dp, neo.rule)
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(destination) },
                        role = Role.Tab
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isSelected) neo.onAction else neo.ink
                )
                Text(
                    text = stringResource(destination.labelRes),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    color = if (isSelected) neo.onAction else neo.ink
                )
            }
        }
    }
}
