package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * Apple Liquid Glass-style modifier for Compose. Wraps the Haze library
 * (dev.chrisbanes.haze) which does the real-time backdrop blur + tint that
 * powers Apple's iOS 26 / macOS Tahoe glass surfaces.
 *
 * Usage:
 *   val hazeState = remember { HazeState() }
 *
 *   Box(Modifier.background(...).hazeSource(hazeState)) {
 *     // scrollable / decorative content sits under the glass
 *   }
 *
 *   Box(Modifier.liquidGlass(hazeState, RoundedCornerShape(24.dp))) {
 *     // foreground content that floats on the glass
 *   }
 *
 * Falls back gracefully on API 26–30 (where RenderEffect blur isn't available)
 * to a translucent tinted surface without blur.
 */
fun Modifier.liquidGlass(
    hazeState: HazeState,
    shape: Shape,
    tint: Color = Color.White.copy(alpha = 0.18f),
    borderAlpha: Float = 0.35f,
    blurRadius: androidx.compose.ui.unit.Dp = 28.dp
): Modifier = this
    .clip(shape)
    .hazeEffect(
        state = hazeState,
        style = HazeStyle(
            backgroundColor = Color.Transparent,
            tints = listOf(HazeTint(tint)),
            blurRadius = blurRadius
        )
    )
    .border(
        BorderStroke(
            0.8.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = borderAlpha),
                    Color.White.copy(alpha = borderAlpha * 0.15f),
                    Color.White.copy(alpha = borderAlpha * 0.55f)
                )
            )
        ),
        shape
    )

/** Dark-mode Liquid Glass tint — black with low alpha so edge highlight still reads. */
@Composable
@ReadOnlyComposable
fun darkGlassTint(): Color = Color(0xFF1C1C1E).copy(alpha = 0.55f)

/** Light-mode Liquid Glass tint — soft white. */
@Composable
@ReadOnlyComposable
fun lightGlassTint(): Color = Color.White.copy(alpha = 0.45f)
