package com.apoorvdarshan.calorietracker.ui.voice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascotProfile

/** The shared floating entry point on Home and the workout diary. */
@Composable
fun VoiceAssistantButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 60.dp, profile: VoiceMascotProfile = VoiceMascotProfile()) {
    val label = stringResource(R.string.voice_talk_to_fud_ai)
    Box(
        modifier
            .size(size)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = profile.name
                role = Role.Button
            }
            .testTag("voiceAssistantButton")
    ) {
        VoiceMascot(
            mode = OrbMode.IDLE,
            profile = profile,
            modifier = Modifier.fillMaxSize()
        )
    }
}
