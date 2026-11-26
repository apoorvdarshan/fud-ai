package com.apoorvdarshan.calorietracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle

// 04 Flash: neutral paper and ink with a solid electric-pink accent.
internal val PinkNeoPaperLight = Color(0xFFFFFDFC)
internal val PinkNeoPaperDark = Color(0xFF121214)
internal val PinkNeoPanelLight = Color.White
internal val PinkNeoPanelDark = Color(0xFF19191B)
private val FlashRailDark = Color(0xFF18181A)
private val FlashInsetLight = Color(0xFFF6F4F3)
private val FlashInsetDark = Color(0xFF141416)
internal val FlashInkLight = Color(0xFF171219)
internal val FlashInkDark = Color(0xFFF9F5F7)
internal val FlashPink = Color(0xFFFF006A)
internal val PinkNeoCondensed = TextStyle.Default

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
    val wash: Color,
    val inset: Color
)

@Composable
internal fun pinkNeoPalette(): PinkNeoPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.35f
    val gray = scheme.background == Color(0xFF202124)
    val pink = scheme.primary == AppThemeColor.FUD_PINK.start
    val ink = if (dark) FlashInkDark else FlashInkLight
    val action = scheme.primary
    val actionInk = contrastingActionInk(action)
    return PinkNeoPalette(
        paper = scheme.background,
        panel = scheme.surface,
        ink = ink,
        rule = ink.copy(alpha = if (dark) 0.24f else 0.18f),
        accent = if (!pink) action else if (dark) Color(0xFFFF6B8A) else Color(0xFFB71645),
        action = action,
        onAction = actionInk,
        emptyActionSegment = if (actionInk == Color.White) Color.Black else Color.White,
        wash = scheme.surface,
        inset = when {
            gray -> Color(0xFF26272B)
            else -> scheme.background
        }
    )
}
