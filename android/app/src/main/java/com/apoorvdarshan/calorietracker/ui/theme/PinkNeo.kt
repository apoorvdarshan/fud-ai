package com.apoorvdarshan.calorietracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextGeometricTransform

internal val PinkNeoPaperLight = Color(0xFFFCF1F3)
internal val PinkNeoPaperDark = Color(0xFF190F16)
internal val PinkNeoPanelLight = Color(0xFFFFF8F9)
internal val PinkNeoPanelDark = Color(0xFF251620)
internal val PinkNeoCondensed = TextStyle(textGeometricTransform = TextGeometricTransform(scaleX = 0.9f))

internal data class PinkNeoPalette(
    val paper: Color,
    val panel: Color,
    val ink: Color,
    val rule: Color,
    val accent: Color,
    val action: Color,
    val onAction: Color,
    val wash: Color,
    val hero: List<Color>
)

@Composable
internal fun pinkNeoPalette(): PinkNeoPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.35f
    val gray = scheme.background == Color(0xFF202124)
    val pink = scheme.primary == AppThemeColor.FUD_PINK.start
    val paper = if (pink && !gray) {
        if (dark) PinkNeoPaperDark else PinkNeoPaperLight
    } else scheme.background
    val panel = if (pink && !gray) {
        if (dark) PinkNeoPanelDark else PinkNeoPanelLight
    } else scheme.surface
    val ink = if (dark) Color(0xFFFFF2F6) else if (pink) Color(0xFF361421) else Color(0xFF222024)
    return PinkNeoPalette(
        paper = paper,
        panel = panel,
        ink = ink,
        rule = ink.copy(alpha = if (dark) 0.75f else 0.82f),
        accent = if (pink) {
            if (dark) Color(0xFFFF92AC) else Color(0xFFA81040)
        } else scheme.primary,
        action = scheme.primary,
        onAction = if (scheme.primary.luminance() < 0.179f) Color.White else Color.Black,
        wash = if (pink && !gray) {
            if (dark) Color(0xFF482337) else Color(0xFFF8D4DE)
        } else panel,
        hero = if (pink) listOf(Color(0xFFBF164E), Color(0xFF870E3C)) else {
            val tinted = lerp(Color(0xFF261620), scheme.primary, 0.22f)
            listOf(tinted, tinted)
        }
    )
}
