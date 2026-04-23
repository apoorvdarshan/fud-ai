package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * First-time review sheet shown after photo / text / voice analysis returns
 * a [FoodAnalysis]. Visually identical to [EditFoodEntrySheet] — only the
 * top-right action differs ("Log" vs "Save"). Shared visual primitives live
 * in FoodSheetPrimitives.kt.
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
    var servingGramsText by remember { mutableStateOf(sheetFormatGrams(analysis.servingSizeGrams)) }
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
        SheetReviewToolbar(
            title = "Review Food",
            primaryLabel = "Log",
            onCancel = onDismiss,
            onPrimary = {
                onSave(name.trim().ifEmpty { analysis.name }, servingGrams, scale, mealType)
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Square hero (captured photo) OR 80sp emoji fallback — centered.
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(20.dp))
                        )
                    } else {
                        Text(analysis.emoji ?: "🍽", fontSize = 80.sp)
                    }
                }
            }

            item { SheetSectionHeader("Food Details") }
            item {
                SheetPillRow {
                    Text("Name", fontSize = 17.sp, modifier = Modifier.padding(end = 8.dp))
                    Spacer(Modifier.weight(1f))
                    androidx.compose.foundation.text.BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Calorie),
                        modifier = Modifier.weight(2f)
                    )
                }
            }

            item { SheetSectionHeader("Serving") }
            item {
                SheetPillRow {
                    Text("Quantity", fontSize = 17.sp, modifier = Modifier.padding(end = 8.dp))
                    Spacer(Modifier.weight(1f))
                    androidx.compose.foundation.text.BasicTextField(
                        value = servingGramsText,
                        onValueChange = { servingGramsText = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Calorie),
                        modifier = Modifier.width(80.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "g",
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.width(20.dp)
                    )
                }
            }

            item { SheetSectionHeader("Nutrition") }
            item {
                SheetPillCard {
                    SheetNutritionRow("Calories", "${scaledInt(analysis.calories)}", "kcal")
                    SheetHairline()
                    SheetNutritionRow("Protein", "${scaledInt(analysis.protein)}", "g")
                    SheetHairline()
                    SheetNutritionRow("Carbs", "${scaledInt(analysis.carbs)}", "g")
                    SheetHairline()
                    SheetNutritionRow("Fat", "${scaledInt(analysis.fat)}", "g")
                }
            }

            // "More Nutrition" — own pill row with chevron-right that flips to
            // chevron-down when expanded; matches iOS DisclosureGroup.
            item {
                SheetPillRow(onClick = { moreNutritionExpanded = !moreNutritionExpanded }) {
                    Text("More Nutrition", fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Icon(
                        if (moreNutritionExpanded) Icons.Filled.KeyboardArrowDown
                        else Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            if (moreNutritionExpanded) {
                item {
                    SheetPillCard {
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
                        micros.forEachIndexed { idx, (label, value, unit) ->
                            if (idx > 0) SheetHairline()
                            SheetNutritionRow(
                                label,
                                value?.let { String.format("%.1f", it) } ?: "—",
                                unit,
                                dim = true
                            )
                        }
                    }
                }
            }

            item { SheetSectionHeader("Meal") }
            item {
                Box {
                    SheetPillRow(onClick = { mealMenuExpanded = true }) {
                        Text("Meal Type", fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Icon(
                            sheetMealIcon(mealType),
                            contentDescription = null,
                            tint = AppColors.Calorie,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
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
                                leadingIcon = {
                                    Icon(sheetMealIcon(m), contentDescription = null, tint = AppColors.Calorie)
                                },
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
