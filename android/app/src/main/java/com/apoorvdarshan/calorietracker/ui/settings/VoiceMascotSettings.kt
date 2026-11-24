package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascot as Mascot
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascotColor
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascotOptions
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascotPersonality
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascotPreferences
import com.apoorvdarshan.calorietracker.ui.voice.VoiceMascot

@Composable
internal fun VoiceMascotSettingsContent(
    preferences: VoiceMascotPreferences,
    onSelect: (Mascot) -> Unit,
    onEdit: (Mascot, (VoiceMascotOptions) -> VoiceMascotOptions) -> Unit
) {
    val profile = preferences.profile
    var name by rememberSaveable(profile.mascot) { mutableStateOf(profile.options.name) }
    val focus = LocalFocusManager.current
    fun saveName() {
        val clean = VoiceMascotPreferences.cleanName(name)
        name = clean
        if (clean != profile.options.name) onEdit(profile.mascot) { it.copy(name = clean) }
        focus.clearFocus()
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            VoiceMascot(OrbMode.IDLE, Modifier.size(132.dp), profile, motionEnabled = false)
            Text(profile.name, style = MaterialTheme.typography.titleLarge)
        }
        SettingChoiceRow(
            label = stringResource(R.string.voice_mascot),
            selection = profile.mascot,
            options = Mascot.entries,
            icon = Icons.Outlined.Face,
            modifier = Modifier.testTag("settings.voiceMascot.character"),
            optionLabel = { it.displayName },
            onSelect = { saveName(); onSelect(it) }
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(100) },
            label = { Text(stringResource(R.string.label_name)) },
            placeholder = { Text(profile.mascot.displayName) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveName() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .testTag("settings.voiceMascot.name")
        )
        if (name != profile.options.name) {
            TextButton(onClick = { saveName() }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_save))
            }
        }
        SettingChoiceRow(
            label = stringResource(R.string.voice_mascot_color),
            selection = profile.options.color,
            options = VoiceMascotColor.entries,
            icon = Icons.Outlined.Palette,
            modifier = Modifier.testTag("settings.voiceMascot.color"),
            optionLabel = { stringResource(it.titleRes) },
            onSelect = { color -> onEdit(profile.mascot) { it.copy(color = color) } }
        )
        SettingChoiceRow(
            label = stringResource(R.string.voice_mascot_personality),
            selection = profile.options.personality,
            options = VoiceMascotPersonality.entries,
            icon = Icons.Outlined.RecordVoiceOver,
            modifier = Modifier.testTag("settings.voiceMascot.personality"),
            optionLabel = { stringResource(it.titleRes) },
            onSelect = { personality -> onEdit(profile.mascot) { it.copy(personality = personality) } }
        )
        Text(
            stringResource(R.string.voice_mascot_next_session),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
