package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.apoorvdarshan.calorietracker.ui.components.FlashBottomSheet as ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.ui.components.LocalEnergyUnit
import com.apoorvdarshan.calorietracker.ui.components.FlashSheetAction
import com.apoorvdarshan.calorietracker.ui.components.energyText
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette
import kotlin.math.roundToInt

/**
 * Confirmation shown when the user opens a `fudai://add-meal` link (issue #107). Lists the shared
 * meal(s) and adds them to the log on confirm — never silently, so a stray link can't add food
 * without the user seeing it first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSharedMealSheet(
    meals: List<FoodEntry>,
    onAdd: (List<FoodEntry>) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val neo = pinkNeoPalette()
    val totalCalories = meals.sumOf { it.calories }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    pluralStringResource(R.plurals.import_add_meals_title, meals.size, meals.size),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
            }

            items(meals) { meal ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(neo.paper).border(1.dp, neo.rule).padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    meal.emoji?.let {
                        Text(it, fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(meal.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${meal.protein.roundToInt()}P · ${meal.carbs.roundToInt()}C · ${meal.fat.roundToInt()}F",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            energyText(meal.calories),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = neo.ink
                        )
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.import_note),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            item {
                FlashSheetAction(onClick = { onAdd(meals) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (meals.size == 1) stringResource(R.string.import_add_to_log) else stringResource(
                            LocalEnergyUnit.current.pick(R.string.import_add_to_log_many_format, R.string.import_add_to_log_many_format_kj),
                            meals.size,
                            LocalEnergyUnit.current.displayValue(totalCalories)
                        )
                    )
                }
            }
        }
    }
}
