package com.apoorvdarshan.calorietracker.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette

/** The shared floating entry point on Home and the workout diary. */
@Composable
fun VoiceAssistantButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    flat: Boolean = false
) {
    val label = stringResource(R.string.voice_talk_to_fud_ai)
    val palette = if (flat) pinkNeoPalette() else null
    Box(
        modifier
            .size(size)
            .then(if (palette == null) Modifier else Modifier.background(palette.panel).border(1.5.dp, palette.rule))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .testTag("voiceAssistantButton"),
        contentAlignment = Alignment.Center
    ) {
        if (palette != null) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = palette.ink, modifier = Modifier.size(24.dp))
        } else {
            LiveOrb(
                mode = OrbMode.IDLE,
                tint = AppColors.Calorie,
                // Keep the Workout entry point unchanged.
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1f / 0.56f
                    scaleY = 1f / 0.56f
                },
                clearBackground = true
            )
        }
    }
}
