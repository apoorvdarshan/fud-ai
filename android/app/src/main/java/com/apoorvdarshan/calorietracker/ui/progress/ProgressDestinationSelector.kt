package com.apoorvdarshan.calorietracker.ui.progress

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

internal enum class ProgressDestination(
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    MY_PROGRESS(R.string.challenge_destination_progress, Icons.AutoMirrored.Filled.ShowChart),
    WEEKLY_CHALLENGE(R.string.challenge_destination_weekly, Icons.Filled.EmojiEvents)
}

/**
 * Capsule track with equal-width segments so the selected chip fills its half
 * (avoids empty trailing track when labels are content-sized — #356).
 */
@Composable
internal fun ProgressDestinationSelector(
    selected: ProgressDestination,
    onSelect: (ProgressDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val track = if (isDark) AppColors.AppCardDark else AppColors.AppCardLight
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(track)
            .border(0.75.dp, AppColors.Calorie.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProgressDestination.entries.forEach { destination ->
            val isSelected = selected == destination
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (isSelected) Modifier.background(AppColors.CalorieGradient)
                        else Modifier.background(Color.Transparent)
                    )
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(destination) },
                        role = Role.Tab
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isSelected) Color.White
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Text(
                    text = stringResource(destination.labelRes),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 11.sp,
                        maxFontSize = 15.sp,
                        stepSize = 0.5.sp
                    ),
                    color = if (isSelected) Color.White
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}
