package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.MealShareLinkStyle
import com.apoorvdarshan.calorietracker.services.MealShare
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shares meals with the chosen link style. The first share (new and existing users alike)
 * asks once which link to send, with Short Link preselected; the answer is kept on this
 * device and can be changed in Settings → App Settings.
 */
@Composable
fun rememberMealSharer(container: AppContainer): (List<FoodEntry>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<FoodEntry>?>(null) }

    pending?.let { entries ->
        MealShareLinkChoiceDialog(
            onChoose = { style ->
                pending = null
                scope.launch {
                    container.prefs.setMealShareLinkStyle(style)
                    MealShare.share(context, entries, style)
                }
            },
            onDismiss = { pending = null }
        )
    }

    return remember(container, context, scope) {
        { entries ->
            if (entries.isNotEmpty()) scope.launch {
                val style = container.prefs.mealShareLinkStyle.first()
                if (style == null) pending = entries else MealShare.share(context, entries, style)
            }
        }
    }
}

@Composable
private fun MealShareLinkChoiceDialog(onChoose: (MealShareLinkStyle) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(MealShareLinkStyle.Default) }
    val settingsPath = "${stringResource(R.string.nav_settings)} → ${stringResource(R.string.settings_section_app)}"
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("mealShare.linkChoice"),
        title = { Text(stringResource(R.string.share_link_choice_title)) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MealShareLinkStyle.entries.forEach { style ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = style == selected, role = Role.RadioButton) { selected = style }
                            .padding(vertical = 6.dp)
                            .testTag("mealShare.linkChoice.${style.storageValue}"),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(selected = style == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(style.titleRes), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(style.detailRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.share_link_choice_footer, settingsPath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(selected) }, modifier = Modifier.testTag("mealShare.linkChoice.continue")) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
