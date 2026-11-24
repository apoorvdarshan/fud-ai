package com.apoorvdarshan.calorietracker.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascotProfile
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette
import com.apoorvdarshan.calorietracker.ui.voice.VoiceMascot

data class BottomTab(val route: String, val icon: ImageVector, @get:StringRes val labelRes: Int)

val BottomTabs = listOf(
    BottomTab(FudAIRoutes.HOME, Icons.Outlined.Home, R.string.nav_home),
    BottomTab(FudAIRoutes.PROGRESS, Icons.Filled.ShowChart, R.string.nav_progress),
    BottomTab(FudAIRoutes.WEEKLY_CHALLENGE, Icons.Filled.EmojiEvents, R.string.challenge_destination_weekly),
    BottomTab(FudAIRoutes.COACH, Icons.Filled.AutoAwesome, R.string.nav_coach),
    BottomTab(FudAIRoutes.SETTINGS, Icons.Outlined.Settings, R.string.nav_settings),
    BottomTab(FudAIRoutes.WORKOUTS, Icons.Filled.SportsGymnastics, R.string.nav_workout_log),
    BottomTab(FudAIRoutes.EXERCISE_LIBRARY, Icons.Filled.FitnessCenter, R.string.nav_exercise_library),
    BottomTab(FudAIRoutes.ABOUT, Icons.Filled.Info, R.string.nav_about),
    BottomTab(FudAIRoutes.SUPPORT, Icons.Filled.Favorite, R.string.about_category_support)
)

// Vertical clearances are no longer sized for an overlaid bottom navigation bar.
val BottomNavScrollPadding = 24.dp
val BottomNavDockedControlPadding = 8.dp

@Composable
internal fun PinkNeoRail(
    currentRoute: String?,
    onTap: (String) -> Unit,
    onVoice: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showAboutBadge: Boolean = false,
    hiddenRoutes: Set<String> = emptySet(),
    navigationEnabled: Boolean = true,
    onUpgrade: (() -> Unit)? = null,
    voiceMascot: VoiceMascotProfile = VoiceMascotProfile(),
    voiceMode: OrbMode = OrbMode.IDLE,
    voiceLevel: () -> Float = { 0f }
) {
    val palette = pinkNeoPalette()
    val scale = LocalDensity.current.fontScale
    val width = (64 * scale.coerceIn(1f, 1.19f)).dp
    val showText = scale < 1.6f
    val railShape = RoundedCornerShape(9.dp)
    val itemShape = RoundedCornerShape(5.dp)
    Column(
        modifier
            .testTag("nav.rail")
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .absolutePadding(top = 7.dp, bottom = 7.dp, right = 7.dp)
            .width(width)
            .clip(railShape)
            .background(palette.wash)
            .border(1.dp, palette.rule, railShape)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier.padding(top = 5.dp).size(36.dp).background(Color.Black, itemShape),
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(27.dp))
            }
            Text("FUD AI", color = palette.ink, fontSize = 9.sp, fontWeight = FontWeight.Black)
            HorizontalDivider(Modifier.padding(vertical = 3.dp), color = palette.rule)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BottomTabs.filterNot { FudAIRoutes.isHiddenTab(it.route, hiddenRoutes) }.forEach { tab ->
                    val label = stringResource(tab.labelRes)
                    val selected = currentRoute == tab.route
                    val badge = showAboutBadge && tab.route == FudAIRoutes.ABOUT
                    val update = stringResource(R.string.settings_tap_to_update)
                    RailControl(
                        title = label,
                        icon = tab.icon,
                        color = if (selected) palette.onAction else palette.ink,
                        showText = showText,
                        badge = if (!badge) null else if (selected) palette.onAction else palette.action,
                        modifier = Modifier
                            .testTag("nav.${tab.route}")
                            .clip(itemShape)
                            .background(if (selected) palette.action else Color.Transparent)
                            .selectable(selected, enabled = navigationEnabled, role = Role.Tab) { onTap(tab.route) }
                            .semantics {
                                contentDescription = label
                                if (badge) stateDescription = update
                            }
                    )
                }
            }
            if (onUpgrade != null) {
                val label = stringResource(R.string.nav_upgrade_plans)
                RailControl(
                    title = label,
                    icon = Icons.Filled.WorkspacePremium,
                    color = palette.ink,
                    showText = showText,
                    modifier = Modifier
                        .testTag("nav.upgrade")
                        .clip(itemShape)
                        .clickable(enabled = navigationEnabled, role = Role.Button, onClick = onUpgrade)
                        .semantics { contentDescription = label }
                )
            }
        }
        if (onVoice != null) {
            val voiceLabel = stringResource(R.string.voice_talk_to_fud_ai)
            Box(
                Modifier
                    .testTag("nav.voice")
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(itemShape)
                    .clickable(enabled = navigationEnabled, role = Role.Button, onClick = onVoice)
                    .semantics {
                        contentDescription = voiceLabel
                        stateDescription = voiceMascot.name
                    },
                contentAlignment = Alignment.Center
            ) {
                VoiceMascot(
                    mode = voiceMode,
                    modifier = Modifier.size(44.dp),
                    profile = voiceMascot,
                    motionEnabled = navigationEnabled,
                    compact = true,
                    level = voiceLevel
                )
            }
        }
    }
}

@Composable
private fun RailControl(
    title: String,
    icon: ImageVector,
    color: Color,
    showText: Boolean,
    modifier: Modifier,
    badge: Color? = null
) {
    Column(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
            if (badge != null) {
                Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(badge, CircleShape))
            }
        }
        if (showText) {
            Text(title, color = color, fontWeight = FontWeight.SemiBold,
                maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 11.sp, stepSize = 1.sp))
        }
    }
}
