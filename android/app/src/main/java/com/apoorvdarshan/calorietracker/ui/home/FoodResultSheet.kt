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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
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
    onSave: (
        name: String,
        servingGrams: Double,
        scale: Double,
        mealType: MealType,
        selectedServingUnit: String?,
        selectedServingQuantity: Double?
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val bitmap = remember(imageBytes) {
        imageBytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden }
    )
    var name by remember { mutableStateOf(analysis.name) }
    val servingUnitOptions = remember(analysis.servingUnitOptions, analysis.servingSizeGrams) {
        ServingUnitOption.normalizedOptions(analysis.servingUnitOptions, analysis.servingSizeGrams)
    }
    var selectedServingUnitId by remember {
        mutableStateOf(ServingUnitOption.initialUnitId(analysis.selectedServingUnit, servingUnitOptions))
    }
    var servingGrams by remember { mutableStateOf(analysis.servingSizeGrams) }
    var servingQuantityText by remember {
        mutableStateOf(
            ServingUnitOption.initialQuantityText(
                totalGrams = analysis.servingSizeGrams,
                selectedUnitId = selectedServingUnitId,
                selectedQuantity = analysis.selectedServingQuantity,
                options = servingUnitOptions
            )
        )
    }
    val selectedServingOption = ServingUnitOption.optionMatching(selectedServingUnitId, servingUnitOptions)
    val selectedServingQuantity = servingQuantityText.toDoubleOrNull()?.takeIf { it > 0 }
    val scale = if (analysis.servingSizeGrams > 0) servingGrams / analysis.servingSizeGrams else 1.0
    var mealType by remember { mutableStateOf(MealType.currentMeal) }
    var moreNutritionExpanded by remember { mutableStateOf(false) }
    var mealMenuExpanded by remember { mutableStateOf(false) }
    var servingMenuExpanded by remember { mutableStateOf(false) }

    fun scaledInt(v: Int) = (v * scale).roundToInt()
    fun scaledD(v: Double?) = v?.let { ((it * scale) * 10).roundToInt() / 10.0 }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        SheetReviewToolbar(
            title = stringResource(R.string.sheet_review_food),
            primaryLabel = stringResource(R.string.action_log),
            onCancel = onDismiss,
            onPrimary = {
                onSave(
                    name.trim().ifEmpty { analysis.name },
                    servingGrams,
                    scale,
                    mealType,
                    if (servingUnitOptions.isEmpty()) null else selectedServingOption.unit,
                    if (servingUnitOptions.isEmpty()) null else selectedServingQuantity
                )
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

            item { SheetSectionHeader(stringResource(R.string.sheet_food_details)) }
            item {
                SheetPillRow {
                    Text(stringResource(R.string.sheet_name), fontSize = 17.sp, modifier = Modifier.padding(end = 8.dp))
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

            item { SheetSectionHeader(stringResource(R.string.sheet_serving)) }
            item {
                ServingQuantityCard(
                    quantityText = servingQuantityText,
                    onQuantityChange = { newValue ->
                        servingQuantityText = newValue
                        newValue.toDoubleOrNull()?.takeIf { it > 0 }?.let {
                            servingGrams = it * selectedServingOption.gramsPerUnit
                        }
                    },
                    selectedUnitId = selectedServingUnitId,
                    onSelectedUnitChange = { optionId ->
                        selectedServingUnitId = optionId
                        val option = ServingUnitOption.optionMatching(optionId, servingUnitOptions)
                        val quantity = if (option.gramsPerUnit > 0) servingGrams / option.gramsPerUnit else servingGrams
                        servingQuantityText = ServingUnitOption.formatQuantity(quantity)
                    },
                    servingSizeGrams = servingGrams,
                    unitOptions = servingUnitOptions,
                    menuExpanded = servingMenuExpanded,
                    onMenuExpandedChange = { servingMenuExpanded = it },
                    gramUnit = stringResource(R.string.unit_g)
                )
            }

            item { SheetSectionHeader(stringResource(R.string.sheet_nutrition)) }
            item {
                SheetPillCard {
                    SheetNutritionRow(stringResource(R.string.nutrition_label_calories), "${scaledInt(analysis.calories)}", stringResource(R.string.unit_kcal))
                    SheetHairline()
                    SheetNutritionRow(stringResource(R.string.nutrition_label_protein), "${scaledInt(analysis.protein)}", stringResource(R.string.unit_g))
                    SheetHairline()
                    SheetNutritionRow(stringResource(R.string.nutrition_label_carbs), "${scaledInt(analysis.carbs)}", stringResource(R.string.unit_g))
                    SheetHairline()
                    SheetNutritionRow(stringResource(R.string.nutrition_label_fat), "${scaledInt(analysis.fat)}", stringResource(R.string.unit_g))
                }
            }

            // "More Nutrition" — own pill row with chevron-right that flips to
            // chevron-down when expanded; matches iOS DisclosureGroup.
            item {
                SheetPillRow(onClick = { moreNutritionExpanded = !moreNutritionExpanded }) {
                    Text(stringResource(R.string.sheet_more_nutrition), fontSize = 17.sp, modifier = Modifier.weight(1f))
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
                        val gUnit = stringResource(R.string.unit_g)
                        val mgUnit = stringResource(R.string.unit_mg)
                        val emDash = stringResource(R.string.nutrition_em_dash)
                        val micros = listOf(
                            Triple(stringResource(R.string.sheet_micro_sugar), scaledD(analysis.sugar), gUnit),
                            Triple(stringResource(R.string.sheet_micro_added_sugar), scaledD(analysis.addedSugar), gUnit),
                            Triple(stringResource(R.string.sheet_micro_fiber), scaledD(analysis.fiber), gUnit),
                            Triple(stringResource(R.string.sheet_micro_saturated_fat), scaledD(analysis.saturatedFat), gUnit),
                            Triple(stringResource(R.string.sheet_micro_mono_fat), scaledD(analysis.monounsaturatedFat), gUnit),
                            Triple(stringResource(R.string.sheet_micro_poly_fat), scaledD(analysis.polyunsaturatedFat), gUnit),
                            Triple(stringResource(R.string.sheet_micro_cholesterol), scaledD(analysis.cholesterol), mgUnit),
                            Triple(stringResource(R.string.sheet_micro_sodium), scaledD(analysis.sodium), mgUnit),
                            Triple(stringResource(R.string.sheet_micro_potassium), scaledD(analysis.potassium), mgUnit)
                        )
                        micros.forEachIndexed { idx, (label, value, unit) ->
                            if (idx > 0) SheetHairline()
                            SheetNutritionRow(
                                label,
                                value?.let { String.format("%.1f", it) } ?: emDash,
                                unit,
                                dim = true
                            )
                        }
                    }
                }
            }

            item { SheetSectionHeader(stringResource(R.string.sheet_meal)) }
            item {
                SheetPillRow(onClick = { mealMenuExpanded = true }) {
                    Text(stringResource(R.string.sheet_meal_type), fontSize = 17.sp, modifier = Modifier.weight(1f))
                    // Anchor the DropdownMenu inside the right-side cluster so
                    // it pops open under the value, not the row's left edge.
                    Box {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                sheetMealIcon(mealType),
                                contentDescription = null,
                                tint = AppColors.Calorie,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(mealType.displayNameRes),
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
                        SheetGlassDropdownMenu(
                            expanded = mealMenuExpanded,
                            onDismissRequest = { mealMenuExpanded = false },
                            menuWidth = 184.dp
                        ) {
                            for (m in MealType.values()) {
                                SheetGlassDropdownMenuItem(
                                    label = stringResource(m.displayNameRes),
                                    leadingIcon = sheetMealIcon(m),
                                    selected = m == mealType,
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
}
