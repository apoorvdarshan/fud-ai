package com.apoorvdarshan.calorietracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextGeometricTransform

// 04 Flash: neutral paper and ink with a solid electric-pink accent.
internal val PinkNeoPaperLight = Color(0xFFFFFDFC)
internal val PinkNeoPaperDark = Color(0xFF131114)
internal val PinkNeoPanelLight = Color.White
internal val PinkNeoPanelDark = Color(0xFF211C23)
private val FlashRailDark = Color(0xFF221E26)
internal val FlashInkLight = Color(0xFF171219)
internal val FlashInkDark = Color(0xFFF9F5F7)
internal val FlashPink = Color(0xFFFF006A)
internal val PinkNeoCondensed = TextStyle(textGeometricTransform = TextGeometricTransform(scaleX = 0.9f))

internal fun contrastingActionInk(action: Color): Color =
    if (action.luminance() < 0.179f) Color.White else Color.Black

internal data class PinkNeoPalette(
    val paper: Color,
    val panel: Color,
    val ink: Color,
    val rule: Color,
    val accent: Color,
    val action: Color,
    val onAction: Color,
    val emptyActionSegment: Color,
    val wash: Color
)

@Composable
internal fun pinkNeoPalette(): PinkNeoPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.35f
    val gray = scheme.background == Color(0xFF202124)
    val pink = scheme.primary == AppThemeColor.FUD_PINK.start
    val ink = if (dark) FlashInkDark else FlashInkLight
    val action = if (pink) FlashPink else scheme.primary
    val actionInk = contrastingActionInk(action)
    return PinkNeoPalette(
        paper = scheme.background,
        panel = scheme.surface,
        ink = ink,
        rule = if (pink && !gray) ink else ink.copy(alpha = if (dark) 0.75f else 0.82f),
        accent = action,
        action = action,
        onAction = actionInk,
        emptyActionSegment = if (actionInk == Color.White) Color.Black else Color.White,
        wash = if (pink && dark && !gray) FlashRailDark else scheme.surface
    )
}
