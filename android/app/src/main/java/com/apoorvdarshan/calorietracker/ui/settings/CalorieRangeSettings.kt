package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.EnergyUnit
import com.apoorvdarshan.calorietracker.ui.components.LocalEnergyUnit
import com.apoorvdarshan.calorietracker.ui.components.energyText
import com.apoorvdarshan.calorietracker.ui.components.energyUnitLabel
import kotlinx.coroutines.launch

@Composable
internal fun CalorieRangeSettings(prefs: PreferencesStore) {
    val range by prefs.calorieDisplayRange.collectAsState(initial = 0 to 0)
    val unit = LocalEnergyUnit.current
    val unitLabel = energyUnitLabel()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var lower by remember { mutableStateOf("") }
    var upper by remember { mutableStateOf("") }
    val title = stringResource(R.string.optional_calorie_range)
    val summary = if (range == (0 to 0)) stringResource(R.string.settings_not_set) else
        (if (range.first == 0) "—" else energyText(range.first)) + " – " +
            (if (range.second == 0) "—" else energyText(range.second))
    TextButton(onClick = {
        lower = range.first.takeIf { it > 0 }?.let { unit.displayValue(it).toString() }.orEmpty()
        upper = range.second.takeIf { it > 0 }?.let { unit.displayValue(it).toString() }.orEmpty()
        editing = true
    }) { Text("$title: $summary") }
    if (editing) {
        // kJ display needs six digits: 99,999 kcal reopens as 418,396 kJ.
        val digitLimit = if (unit == EnergyUnit.KJ) 6 else 5
        val floor = calorieBound(lower, digitLimit)
        val ceiling = calorieBound(upper, digitLimit)
        val valid = floor != null && ceiling != null && (floor == 0 || ceiling == 0 || floor <= ceiling)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(lower, { lower = it }, label = { Text(stringResource(R.string.calorie_floor, unitLabel)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(upper, { upper = it }, label = { Text(stringResource(R.string.calorie_ceiling, unitLabel)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(stringResource(R.string.calorie_range_hint))
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    if (floor != null && ceiling != null) scope.launch {
                        prefs.setCalorieDisplayRange(
                            calorieStoredKcal(floor, unit),
                            calorieStoredKcal(ceiling, unit)
                        )
                        editing = false
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

/** The range is edited in the user's display unit but stored in kcal. */
internal fun calorieStoredKcal(displayed: Int, unit: EnergyUnit): Int =
    if (displayed == 0) 0 else unit.toKcal(displayed.toDouble())

internal fun calorieBound(text: String, maxDigits: Int = 5): Int? = when {
    text.isEmpty() -> 0
    text.length > maxDigits || text.any { it.digitToIntOrNull() == null } -> null
    else -> text.map { it.digitToInt() }.joinToString("").toIntOrNull()?.takeIf { it > 0 }
}
