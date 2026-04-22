package com.apoorvdarshan.calorietracker.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.ui.components.liquidGlass
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import dev.chrisbanes.haze.HazeState

data class BottomTab(val route: String, val icon: ImageVector, val label: String)

val BottomTabs = listOf(
    BottomTab(FudAIRoutes.HOME, Icons.Filled.Home, "Home"),
    BottomTab(FudAIRoutes.PROGRESS, Icons.Filled.ShowChart, "Progress"),
    BottomTab(FudAIRoutes.COACH, Icons.Filled.SupportAgent, "Coach"),
    BottomTab(FudAIRoutes.SETTINGS, Icons.Filled.Settings, "Settings"),
    BottomTab(FudAIRoutes.ABOUT, Icons.Filled.Info, "About")
)

@Composable
fun FudAIBottomNavBar(
    currentRoute: String?,
    onTap: (String) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(28.dp))
            .liquidGlass(
                hazeState = hazeState,
                shape = RoundedCornerShape(28.dp),
                tint = Color.White.copy(alpha = 0.08f)
            )
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 6.dp)
        ) {
            for (tab in BottomTabs) {
                val selected = tab.route == currentRoute
                val tint by animateColorAsState(
                    if (selected) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    label = "tabTint"
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = MutableInteractionSource(),
                            indication = null
                        ) { onTap(tab.route) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tab.label,
                        color = tint,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(3.dp))
                    if (selected) {
                        Box(
                            Modifier
                                .size(width = 20.dp, height = 3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(AppColors.CalorieGradient)
                        )
                    } else {
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
        }
    }
}
