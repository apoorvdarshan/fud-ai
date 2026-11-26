package com.apoorvdarshan.calorietracker.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.models.WorkoutTabMode
import kotlinx.coroutines.launch

data class BottomTab(val route: String, val icon: ImageVector, @get:StringRes val labelRes: Int)

val BottomTabs = listOf(
    BottomTab(FudAIRoutes.HOME, Icons.Filled.Home, R.string.nav_home),
    BottomTab(FudAIRoutes.PROGRESS, Icons.Filled.BarChart, R.string.nav_progress),
    BottomTab(FudAIRoutes.COACH, Icons.Filled.Forum, R.string.nav_coach),
    BottomTab(FudAIRoutes.SETTINGS, Icons.Filled.Settings, R.string.nav_settings),
    BottomTab(FudAIRoutes.WORKOUTS, Icons.Filled.FitnessCenter, R.string.nav_workouts)
)

/** The Workouts tab icon for the current mode; Settings > Tab Bar shows the same one. */
fun workoutsTabIcon(mode: WorkoutTabMode): ImageVector =
    if (mode == WorkoutTabMode.LOG) Icons.Filled.SportsGymnastics else Icons.Filled.FitnessCenter

private val BarHeight = 72.dp
private val BarCorner = 36.dp
private val PillCorner = 26.dp
private val PillInsetH = 8.dp
private val PillInsetV = 6.dp

// The bottom bar is laid out separately, not over the content.
val BottomNavScrollPadding = 24.dp
val BottomNavDockedControlPadding = 8.dp

/**
 * Floating Liquid Glass tab bar — capsule with translucent backdrop, glassy
 * sheen, hairline border, soft shadow, and a spring-animated bright pill
 * behind the active tab.
 *
 * The pill is **draggable**: place a finger anywhere on the bar and slide
 * horizontally to drag it across tabs. Taps still work normally (drag is
 * only claimed after horizontal touch slop). On release the pill snaps to
 * the nearest tab; haptic ticks fire each time the pill crosses a boundary
 * during the drag, mirroring the iOS 26 Liquid Glass tab-bar feel.
 */
@Composable
fun FudAIBottomNavBar(
    currentRoute: String?,
    showAboutBadge: Boolean = false,
    workoutMode: WorkoutTabMode = WorkoutTabMode.Default,
    hiddenRoutes: Set<String> = emptySet(),
    navigationEnabled: Boolean = true,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val dragDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val tabs = remember(workoutMode, hiddenRoutes) {
        BottomTabs.filterNot { FudAIRoutes.isHiddenTab(it.route, hiddenRoutes) }.map { tab ->
            if (tab.route != FudAIRoutes.WORKOUTS) tab else tab.copy(icon = workoutsTabIcon(workoutMode))
        }
    }
    val isDark = MaterialTheme.colorScheme.background.let {
        (it.red + it.green + it.blue) / 3f < 0.5f
    }

    val barShape = RoundedCornerShape(BarCorner)

    val backdropColor = if (isDark) Color(0xFF15151A).copy(alpha = 0.86f)
                        else Color(0xFFFCF6F1).copy(alpha = 0.74f)

    val barSheen = Brush.verticalGradient(
        colors = if (isDark)
            listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.0f))
        else
            listOf(
                Color.White.copy(alpha = 0.76f),
                Color.White.copy(alpha = 0.22f),
                AppColors.Calorie.copy(alpha = 0.035f)
            )
    )

    val barBorder = Brush.linearGradient(
        if (isDark) {
            listOf(
                Color.White.copy(alpha = 0.28f),
                Color.White.copy(alpha = 0.06f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.95f),
                Color.White.copy(alpha = 0.32f),
                AppColors.Calorie.copy(alpha = 0.16f)
            )
        }
    )
    val shadowAlpha = if (isDark) 0.35f else 0.16f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .shadow(
                    elevation = if (isDark) 22.dp else 18.dp,
                    shape = barShape,
                    ambientColor = Color.Black.copy(alpha = shadowAlpha),
                    spotColor = Color.Black.copy(alpha = shadowAlpha)
                )
                .clip(barShape)
                .background(backdropColor)
                .background(if (isDark) Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)) else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f), AppColors.Calorie.copy(alpha = 0.020f))))
                .background(barSheen)
                .border(0.8.dp, barBorder, barShape)
        ) {
            val density = LocalDensity.current
            val haptic = LocalHapticFeedback.current
            val scope = rememberCoroutineScope()

            val barWidthDp = maxWidth
            val tabCount = tabs.size
            val tabWidthDp = barWidthDp / tabCount
            val tabWidthPx = with(density) { tabWidthDp.toPx() }
            val maxOffsetPx = tabWidthPx * (tabCount - 1)

            val selectedIndex = tabs.indexOfFirst { it.route == currentRoute }
                .coerceAtLeast(0)

            // Spring animator drives the pill when it's NOT being dragged
            // (initial mount, external route changes, settle-after-release).
            val pillAnim = remember { Animatable(0f) }
            var isDragging by remember { mutableStateOf(false) }
            var dragOffsetPx by remember { mutableFloatStateOf(0f) }
            var hoverIndex by remember { mutableIntStateOf(selectedIndex) }

            // Sync pill position once the bar's width has been measured, and on
            // any later external selectedIndex change. Skip while dragging so a
            // mid-drag recomposition doesn't yank the pill back to a snapped
            // position.
            LaunchedEffect(selectedIndex, tabWidthPx) {
                if (!isDragging) {
                    val target = selectedIndex * tabWidthPx
                    if (pillAnim.value == 0f && selectedIndex > 0) {
                        pillAnim.snapTo(target)
                    } else {
                        pillAnim.animateTo(
                            target,
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = 320f
                            )
                        )
                    }
                }
            }

            val pillPx = if (isDragging) dragOffsetPx else pillAnim.value
            val pillOffsetDp = with(density) { pillPx.toDp() }

            val pillScale by animateFloatAsState(
                targetValue = if (isDragging) 1.06f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = 380f
                ),
                label = "pillDragScale"
            )

            // Active-tab pill — the bright glass disc.
            ActivePill(
                tabWidth = tabWidthDp,
                isDark = isDark,
                modifier = Modifier
                    .offset(x = pillOffsetDp)
                    .graphicsLayer {
                        scaleX = pillScale
                        scaleY = pillScale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
            )

            // Shared drag handlers used by every TabItem. clickable on the
            // tab handles the tap (proven reliable); a sibling pointerInput
            // with detectHorizontalDragGestures handles the drag — both can
            // listen on the same pointer stream because clickable claims on
            // release-without-slop and the drag detector claims on slop.
            fun startDrag() {
                isDragging = true
                dragOffsetPx = pillAnim.value
                hoverIndex = selectedIndex
            }
            fun endDrag(cancelled: Boolean = false) {
                val landed = if (cancelled) selectedIndex else hoverIndex
                scope.launch {
                    pillAnim.snapTo(dragOffsetPx)
                    isDragging = false
                    pillAnim.animateTo(
                        landed * tabWidthPx,
                        spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = 320f
                        )
                    )
                }
                if (!cancelled && tabs[landed].route != currentRoute) {
                    currentOnTap(tabs[landed].route)
                }
            }
            fun onDragDelta(delta: Float) {
                dragOffsetPx = (dragOffsetPx + delta).coerceIn(0f, maxOffsetPx)
                val newHover = ((dragOffsetPx + tabWidthPx / 2f) / tabWidthPx)
                    .toInt().coerceIn(0, tabCount - 1)
                if (newHover != hoverIndex) {
                    hoverIndex = newHover
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }

            Row(
                Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (tab in tabs) {
                    val selected = tab.route == currentRoute
                    TabItem(
                        tab = tab,
                        selected = selected,
                        showBadge = showAboutBadge && tab.route == FudAIRoutes.SETTINGS,
                        isDark = isDark,
                        enabled = navigationEnabled,
                        modifier = Modifier
                            .width(tabWidthDp)
                            .fillMaxHeight()
                            .pointerInput(tabWidthPx, tabs, currentRoute, navigationEnabled, dragDirection) {
                                if (!navigationEnabled || tabWidthPx <= 0f) return@pointerInput
                                detectHorizontalDragGestures(
                                    onDragStart = { startDrag() },
                                    onDragEnd = { endDrag() },
                                    onDragCancel = { endDrag(cancelled = true) },
                                    onHorizontalDrag = { change, dragAmount ->
                                        onDragDelta(dragAmount * dragDirection)
                                        change.consume()
                                    }
                                )
                            }
                    ) { currentOnTap(tab.route) }
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
private fun ActivePill(tabWidth: Dp, isDark: Boolean, modifier: Modifier = Modifier) {
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
    showBadge: Boolean,
    isDark: Boolean,
    enabled: Boolean,
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

    val label = stringResource(tab.labelRes)
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.testTag("nav.${tab.route}").semantics {
            this.selected = selected
            role = Role.Tab
        }.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box {
            Icon(
                tab.icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(if (selected) 26.dp else 24.dp).scale(iconScale)
            )
            if (showBadge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AppColors.Calorie)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = tint,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
