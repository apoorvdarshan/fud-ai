package com.apoorvdarshan.calorietracker.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

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
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 8.dp)
        ) {
            for (tab in BottomTabs) {
                val selected = tab.route == currentRoute
                val tint by animateColorAsState(
                    if (selected) AppColors.Calorie else Color(0xFF8E8E93),
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
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tab.label,
                        color = tint,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(3.dp))
                    if (selected) {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .size(width = 22.dp, height = 3.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
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
