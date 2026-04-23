package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlin.math.roundToInt

/**
 * Verbatim port of struct FoodResultView in
 * ios/calorietracker/Views/FoodResultView.swift.
 *
 * iOS layout (List with sections):
 *   1. Image (200dp max, 12dp rounded) OR 80sp emoji
 *   2. 'Food Details' section: Name TextField (right-aligned)
 *   3. 'Serving' section: Quantity TextField + 'g' suffix
 *   4. 'Meal Type' section: segmented picker (Breakfast / Lunch / Dinner / Snack / Other)
 *   5. 'Nutrition (per N g)' section: rows for Calories / Protein / Carbs / Fat,
 *      then optional micronutrients (Sugar / Fiber / Sat fat / Sodium / Potassium /
 *      Cholesterol / Mono fat / Poly fat / Added sugar)
 *   All macros are scaled live by `scale = servingSizeGrams / baseServingSizeGrams`.
 *   Bottom: Save button (.tint pink, full width).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodResultSheet(
    analysis: FoodAnalysis,
    imageBytes: ByteArray? = null,
    onSave: (name: String, servingGrams: Double, scale: Double, mealType: MealType) -> Unit,
    onDismiss: () -> Unit
) {
    val bitmap = remember(imageBytes) {
        imageBytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(analysis.name) }
    var servingGramsText by remember { mutableStateOf(formatGrams(analysis.servingSizeGrams)) }
    val servingGrams = servingGramsText.toDoubleOrNull()?.takeIf { it > 0 } ?: analysis.servingSizeGrams
    val scale = if (analysis.servingSizeGrams > 0) servingGrams / analysis.servingSizeGrams else 1.0
    var mealType by remember { mutableStateOf(MealType.currentMeal) }
    var moreNutritionExpanded by remember { mutableStateOf(false) }
    var mealMenuExpanded by remember { mutableStateOf(false) }

    fun scaledInt(v: Int) = (v * scale).roundToInt()
    fun scaledD(v: Double?) = v?.let { ((it * scale) * 10).roundToInt() / 10.0 }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // Cancel · "Review Food" · Log toolbar — replaces iOS NavigationStack toolbar.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("Review Food", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                onSave(name.trim().ifEmpty { analysis.name }, servingGrams, scale, mealType)
            }) {
                Text("Log", color = AppColors.Calorie, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Image hero (captured photo) OR 80sp emoji fallback — matches iOS section.
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Text(analysis.emoji ?: "🍽", fontSize = 80.sp)
                    }
                }
            }

            item { SectionHeader("Food Details") }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Name", fontSize = 17.sp, modifier = Modifier.padding(end = 8.dp))
                    Spacer(Modifier.weight(1f))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                }
            }

            item { SectionHeader("Serving") }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Quantity", fontSize = 17.sp, modifier = Modifier.padding(end = 8.dp))
                    Spacer(Modifier.weight(1f))
                    OutlinedTextField(
                        value = servingGramsText,
                        onValueChange = { servingGramsText = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(96.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("g", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            // Nutrition (always-visible macros).
            item { SectionHeader("Nutrition") }
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    NutritionRow("Calories", "${scaledInt(analysis.calories)}", "kcal", isHero = true)
                    Hairline()
                    NutritionRow("Protein", "${scaledInt(analysis.protein)}", "g")
                    Hairline()
                    NutritionRow("Carbs", "${scaledInt(analysis.carbs)}", "g")
                    Hairline()
                    NutritionRow("Fat", "${scaledInt(analysis.fat)}", "g")
                }
            }

            // Collapsible "More Nutrition" disclosure — port of iOS DisclosureGroup.
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { moreNutritionExpanded = !moreNutritionExpanded }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "More Nutrition",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.Calorie,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (moreNutritionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = AppColors.Calorie
                        )
                    }
                    if (moreNutritionExpanded) {
                        val micros = listOf(
                            Triple("Sugar", scaledD(analysis.sugar), "g"),
                            Triple("Added Sugar", scaledD(analysis.addedSugar), "g"),
                            Triple("Fiber", scaledD(analysis.fiber), "g"),
                            Triple("Saturated Fat", scaledD(analysis.saturatedFat), "g"),
                            Triple("Mono Fat", scaledD(analysis.monounsaturatedFat), "g"),
                            Triple("Poly Fat", scaledD(analysis.polyunsaturatedFat), "g"),
                            Triple("Cholesterol", scaledD(analysis.cholesterol), "mg"),
                            Triple("Sodium", scaledD(analysis.sodium), "mg"),
                            Triple("Potassium", scaledD(analysis.potassium), "mg")
                        )
                        for ((label, value, unit) in micros) {
                            Hairline()
                            NutritionRow(
                                label,
                                value?.let { String.format("%.1f", it) } ?: "—",
                                unit,
                                dim = true
                            )
                        }
                    }
                }
            }

            // Meal type as inline dropdown — matches iOS Picker(.menu) inline display.
            item { SectionHeader("Meal") }
            item {
                Box {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { mealMenuExpanded = true }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Meal Type", fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text(
                            mealType.displayName,
                            fontSize = 17.sp,
                            color = AppColors.Calorie,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.UnfoldMore,
                            contentDescription = null,
                            tint = AppColors.Calorie
                        )
                    }
                    DropdownMenu(
                        expanded = mealMenuExpanded,
                        onDismissRequest = { mealMenuExpanded = false }
                    ) {
                        for (m in MealType.values()) {
                            DropdownMenuItem(
                                text = { Text(m.displayName) },
                                onClick = {
                                    mealType = m
                                    mealMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    // iOS Section() label — sentence-case, secondary color, regular weight.
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun NutritionRow(label: String, value: String, unit: String, isHero: Boolean = false, dim: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = if (isHero) 17.sp else 15.sp,
            color = if (dim) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = if (isHero) 22.sp else 15.sp,
            fontWeight = if (isHero) FontWeight.Bold else FontWeight.Medium,
            color = if (isHero) AppColors.Calorie else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(6.dp))
        Text(
            unit,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.width(36.dp)
        )
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    )
}

private fun formatGrams(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString()
    else String.format("%.1f", value)
