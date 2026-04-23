package com.apoorvdarshan.calorietracker.ui.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

data class BottomTab(val route: String, val icon: ImageVector, val label: String)

val BottomTabs = listOf(
    BottomTab(FudAIRoutes.HOME, Icons.Filled.Home, "Home"),
    BottomTab(FudAIRoutes.PROGRESS, Icons.Filled.BarChart, "Progress"),
    BottomTab(FudAIRoutes.COACH, Icons.Filled.Forum, "Coach"),
    BottomTab(FudAIRoutes.SETTINGS, Icons.Filled.Settings, "Settings"),
    BottomTab(FudAIRoutes.ABOUT, Icons.Filled.Info, "About")
)

private val BarHeight = 72.dp
private val BarCorner = 36.dp
private val PillCorner = 26.dp
private val PillInsetH = 8.dp
private val PillInsetV = 6.dp

/**
 * Floating Liquid Glass tab bar — capsule with translucent backdrop, glassy
 * sheen, hairline border, soft shadow, and a spring-animated bright pill
 * behind the active tab.
 *
 * Approximates iOS 26 Liquid Glass via:
 *   - dark / light translucent surface that floats over the underlying content
 *   - vertical white sheen overlay (top-bright → bottom-clear)
 *   - hairline white-gradient border
 *   - 22dp ambient + spot shadow for depth
 *   - active-tab pill: bright white-glass disc layered over the bar (the
 *     "glass-on-glass" effect) — clearly visible, with its own sheen + border
 *   - subtle 1.08x scale bump on the selected icon
 */
@Composable
fun FudAIBottomNavBar(
    currentRoute: String?,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.let {
        (it.red + it.green + it.blue) / 3f < 0.5f
    }

    val barShape = RoundedCornerShape(BarCorner)

    val backdropColor = if (isDark) Color(0xFF15151A).copy(alpha = 0.86f)
                        else Color(0xFFFFFFFF).copy(alpha = 0.90f)

    val barSheen = Brush.verticalGradient(
        colors = if (isDark)
            listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.0f))
        else
            listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.10f))
    )

    val barBorder = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.28f else 0.65f),
            Color.White.copy(alpha = if (isDark) 0.06f else 0.18f)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .shadow(
                    elevation = 22.dp,
                    shape = barShape,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.35f)
                )
                .clip(barShape)
                .background(backdropColor)
                .background(barSheen)
                .border(0.8.dp, barBorder, barShape)
        ) {
            val barWidthDp = maxWidth
            val tabCount = BottomTabs.size
            val tabWidthDp = barWidthDp / tabCount
            val selectedIndex = BottomTabs.indexOfFirst { it.route == currentRoute }
                .coerceAtLeast(0)

            val highlightOffset by animateDpAsState(
                targetValue = tabWidthDp * selectedIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = 320f
                ),
                label = "tabHighlightOffset"
            )

            // Active-tab pill — the bright glass disc.
            ActivePill(
                tabWidth = tabWidthDp,
                isDark = isDark,
                modifier = Modifier.offset(x = highlightOffset)
            )

            Row(
                Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (tab in BottomTabs) {
                    val selected = tab.route == currentRoute
                    TabItem(
                        tab = tab,
                        selected = selected,
                        isDark = isDark,
                        modifier = Modifier.width(tabWidthDp).fillMaxHeight()
                    ) { onTap(tab.route) }
                }
            }
        }
    }
}

/**
 * Bright "glass-on-glass" pill highlighting the active tab. Layered on top of
 * the bar so it reads like a brighter slab of glass within the larger one.
 */
@Composable
private fun ActivePill(tabWidth: androidx.compose.ui.unit.Dp, isDark: Boolean, modifier: Modifier = Modifier) {
    val pillShape = RoundedCornerShape(PillCorner)

    val fill = if (isDark) Color.White.copy(alpha = 0.16f)
               else AppColors.Calorie.copy(alpha = 0.14f)

    val sheen = Brush.verticalGradient(
        colors = if (isDark)
            listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.0f))
        else
            listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.10f))
    )

    val border = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.32f else 0.75f),
            Color.White.copy(alpha = if (isDark) 0.06f else 0.18f)
        )
    )

    Box(
        modifier
            .width(tabWidth)
            .fillMaxHeight()
            .padding(horizontal = PillInsetH, vertical = PillInsetV)
            .clip(pillShape)
            .background(fill)
            .background(sheen)
            .border(0.7.dp, border, pillShape)
    )
}

@Composable
private fun TabItem(
    tab: BottomTab,
    selected: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val activeColor = AppColors.Calorie
    val inactiveColor = if (isDark) Color.White.copy(alpha = 0.62f)
                        else Color.Black.copy(alpha = 0.55f)
    val tint = if (selected) activeColor else inactiveColor

    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 380f
        ),
        label = "tabIconScale"
    )

    Column(
        modifier = modifier.clickable(
            interactionSource = MutableInteractionSource(),
            indication = null,
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(if (selected) 26.dp else 24.dp).scale(iconScale)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
