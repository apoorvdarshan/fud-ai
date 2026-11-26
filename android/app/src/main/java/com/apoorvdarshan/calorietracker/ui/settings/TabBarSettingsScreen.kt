package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.WorkoutTabMode
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.navigation.FudAIRoutes
import com.apoorvdarshan.calorietracker.ui.navigation.workoutsTabIcon
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

/** Settings > App Settings > Tab Bar: show or hide Progress, Coach and Workouts. */
@Composable
fun TabBarSettingsScreen(
    vm: SettingsViewModel,
    workoutMode: WorkoutTabMode,
    onBack: () -> Unit
) {
    val ui by vm.ui.collectAsState()
    // The same icons the bottom bar shows, including the Workouts icon for the current mode.
    val tabs = listOf(
        Triple(FudAIRoutes.PROGRESS, R.string.nav_progress, Icons.Filled.BarChart),
        Triple(FudAIRoutes.COACH, R.string.nav_coach, Icons.Filled.Forum),
        Triple(FudAIRoutes.WORKOUTS, R.string.nav_workouts, workoutsTabIcon(workoutMode))
    )

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.nav_settings),
                        color = AppColors.Calorie,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_tab_bar),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            item {
                SectionCard(title = stringResource(R.string.settings_tab_bar_section)) {
                    tabs.forEachIndexed { index, (route, labelRes, icon) ->
                        ToggleRow(
                            label = stringResource(labelRes),
                            checked = route !in ui.hiddenTabs,
                            icon = icon,
                            switchModifier = Modifier.testTag("settings.tabBar.$route"),
                            onChange = { shown -> vm.setTabHidden(route, hidden = !shown) }
                        )
                        if (index < tabs.lastIndex) HorizontalDivider()
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_tab_bar_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}
