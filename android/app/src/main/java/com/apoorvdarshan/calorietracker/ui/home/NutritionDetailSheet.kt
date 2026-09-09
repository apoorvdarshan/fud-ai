package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.HomeTopNutrient
import com.apoorvdarshan.calorietracker.models.MacroValueFormatter
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import com.apoorvdarshan.calorietracker.models.SupplementalNutrient
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

/**
 * Verbatim port of struct NutritionDetailView in
 * ios/calorietracker/ContentView.swift (line ~720).
 *
 * Two sections:
 *   Macros: Calories / Protein / Carbs / Fat — each row shows icon +
 *     label + value + unit + '/ goal'.
 *   Detailed Nutrition: Sugar / Added Sugar / Fiber / Saturated Fat /
 *     Mono Unsat. Fat / Poly Unsat. Fat / Cholesterol / Sodium /
 *     Potassium — same icon+label+value+unit pattern, no goal column.
 *
 * Computes the per-day sum from the entries list passed in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionDetailSheet(
    entries: List<FoodEntry>,
    profile: UserProfile?,
    homeTopNutrients: List<HomeTopNutrient>,
    optionalGoals: OptionalNutrientGoals,
    waterTrackingEnabled: Boolean,
    waterCurrentMl: Int,
    waterGoalMl: Int,
    waterUnit: WaterUnit,
    onHomeTopNutrientsChange: (List<HomeTopNutrient>) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showHomeCardsPicker by remember { mutableStateOf(false) }
    val calories = entries.sumOf { it.calories }
    val protein = entries.sumOf { it.protein }
    val carbs = entries.sumOf { it.carbs }
    val fat = entries.sumOf { it.fat }
    val sugar = entries.sumOf { it.sugar ?: 0.0 }
    val addedSugar = entries.sumOf { it.addedSugar ?: 0.0 }
    val fiber = entries.sumOf { it.fiber ?: 0.0 }
    val satFat = entries.sumOf { it.saturatedFat ?: 0.0 }
    val monoFat = entries.sumOf { it.monounsaturatedFat ?: 0.0 }
    val polyFat = entries.sumOf { it.polyunsaturatedFat ?: 0.0 }
    val cholesterol = entries.sumOf { it.cholesterol ?: 0.0 }
    val caffeine = entries.sumOf { it.caffeine ?: 0.0 }
    val supplementalNutrients = SupplementalNutrient.values().associateWith { nutrient ->
        entries.sumOf { it.supplementalNutrients[nutrient.storageKey] ?: 0.0 }
    }
    val sodium = entries.sumOf { it.sodium ?: 0.0 }
    val potassium = entries.sumOf { it.potassium ?: 0.0 }
    val transFat = entries.sumOf { it.transFat ?: 0.0 }
    val calcium = entries.sumOf { it.calcium ?: 0.0 }
    val iron = entries.sumOf { it.iron ?: 0.0 }
    val magnesium = entries.sumOf { it.magnesium ?: 0.0 }
    val zinc = entries.sumOf { it.zinc ?: 0.0 }
    val vitaminA = entries.sumOf { it.vitaminA ?: 0.0 }
    val vitaminC = entries.sumOf { it.vitaminC ?: 0.0 }
    val vitaminD = entries.sumOf { it.vitaminD ?: 0.0 }
    val vitaminB12 = entries.sumOf { it.vitaminB12 ?: 0.0 }
    val vitaminE = entries.sumOf { it.vitaminE ?: 0.0 }
    val vitaminK = entries.sumOf { it.vitaminK ?: 0.0 }
    val folate = entries.sumOf { it.folate ?: 0.0 }
    val omega3 = entries.sumOf { it.omega3 ?: 0.0 }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetSurface = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFAF3EE)

    fun fmt(v: Double): String = if (v == 0.0) "—" else String.format("%.1f", v)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetSurface
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nutrition_details_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = AppColors.Calorie) }
                }
            }

            item { SectionHeader(stringResource(R.string.nutrition_section_home_cards)) }
            item {
                Card {
                    HomeCardsRow(
                        selected = homeTopNutrients,
                        waterTrackingEnabled = waterTrackingEnabled,
                        onClick = { showHomeCardsPicker = true }
                    )
                }
            }

            if (waterTrackingEnabled) {
                item { SectionHeader(stringResource(R.string.nutrition_section_water)) }
                item {
                    Card {
                        DetailRow(
                            Icons.Filled.WaterDrop,
                            stringResource(R.string.water),
                            waterUnit.displayValue(waterCurrentMl),
                            waterUnit.symbol,
                            goal = waterUnit.displayValue(waterGoalMl)
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.home_nutrients_water_fixed),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.nutrition_section_macros)) }
            item {
                Card {
                    DetailRow(Icons.Filled.LocalFireDepartment, stringResource(R.string.nutrition_label_calories), "$calories", stringResource(R.string.unit_kcal), goal = "${profile?.effectiveCalories ?: 2000}")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_protein), MacroValueFormatter.string(protein), stringResource(R.string.unit_g), goal = "${profile?.effectiveProtein ?: 150}", labelGlyph = "P")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_carbs), MacroValueFormatter.string(carbs), stringResource(R.string.unit_g), goal = "${profile?.effectiveCarbs ?: 220}", labelGlyph = "C")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_fat), MacroValueFormatter.string(fat), stringResource(R.string.unit_g), goal = "${profile?.effectiveFat ?: 70}", labelGlyph = "F")
                }
            }

            item { SectionHeader(stringResource(R.string.nutrition_section_detailed)) }
            item {
                Card {
                    DetailRow(null, stringResource(R.string.nutrition_label_sugar), fmt(sugar), stringResource(R.string.unit_g), goal = "${optionalGoals.sugar}", labelGlyph = "S")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_added_sugar), fmt(addedSugar), stringResource(R.string.unit_g), goal = "${optionalGoals.addedSugar}", labelGlyph = "+")
                    Hairline()
                    DetailRow(Icons.Filled.Spa, stringResource(R.string.nutrition_label_fiber), fmt(fiber), stringResource(R.string.unit_g), goal = "${optionalGoals.fiber}")
                    Hairline()
                    DetailRow(Icons.Filled.WaterDrop, stringResource(R.string.nutrition_label_saturated_fat), fmt(satFat), stringResource(R.string.unit_g), goal = "${optionalGoals.saturatedFat}")
                    Hairline()
                    DetailRow(Icons.Filled.WaterDrop, stringResource(R.string.nutrition_label_mono_fat), fmt(monoFat), stringResource(R.string.unit_g))
                    Hairline()
                    DetailRow(Icons.Filled.WaterDrop, stringResource(R.string.nutrition_label_poly_fat), fmt(polyFat), stringResource(R.string.unit_g))
                    Hairline()
                    DetailRow(Icons.Filled.Favorite, stringResource(R.string.nutrition_label_cholesterol), fmt(cholesterol), stringResource(R.string.unit_mg), goal = "${optionalGoals.cholesterol}")
                    Hairline()
                    DetailRow(Icons.Filled.Bolt, stringResource(R.string.nutrition_label_caffeine), fmt(caffeine), stringResource(R.string.unit_mg), goal = "${optionalGoals.caffeine}")
                    Hairline()
                    DetailRow(Icons.Filled.Bolt, stringResource(R.string.nutrition_label_sodium), fmt(sodium), stringResource(R.string.unit_mg), goal = "${optionalGoals.sodium}")
                    Hairline()
                    DetailRow(Icons.Filled.Bolt, stringResource(R.string.nutrition_label_potassium), fmt(potassium), stringResource(R.string.unit_mg), goal = "${optionalGoals.potassium}")
                    Hairline()
                    DetailRow(Icons.Filled.WaterDrop, stringResource(R.string.nutrition_label_trans_fat), fmt(transFat), stringResource(R.string.unit_g), goal = "${optionalGoals.transFat}")
                    Hairline()
                    DetailRow(Icons.Filled.Bolt, stringResource(R.string.nutrition_label_calcium), fmt(calcium), stringResource(R.string.unit_mg), goal = "${optionalGoals.calcium}")
                    Hairline()
                    DetailRow(Icons.Filled.Bolt, stringResource(R.string.nutrition_label_iron), fmt(iron), stringResource(R.string.unit_mg), goal = "${optionalGoals.iron}")
                    Hairline()
                    DetailRow(Icons.Filled.Bolt, stringResource(R.string.nutrition_label_magnesium), fmt(magnesium), stringResource(R.string.unit_mg), goal = "${optionalGoals.magnesium}")
                    Hairline()
                    DetailRow(Icons.Filled.Bolt, stringResource(R.string.nutrition_label_zinc), fmt(zinc), stringResource(R.string.unit_mg), goal = "${optionalGoals.zinc}")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_vitamin_a), fmt(vitaminA), stringResource(R.string.unit_mcg), goal = "${optionalGoals.vitaminA}", labelGlyph = "A")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_vitamin_c), fmt(vitaminC), stringResource(R.string.unit_mg), goal = "${optionalGoals.vitaminC}", labelGlyph = "C")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_vitamin_d), fmt(vitaminD), stringResource(R.string.unit_mcg), goal = "${optionalGoals.vitaminD}", labelGlyph = "D")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_vitamin_b12), fmt(vitaminB12), stringResource(R.string.unit_mcg), goal = "${optionalGoals.vitaminB12}", labelGlyph = "B")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_vitamin_e), fmt(vitaminE), stringResource(R.string.unit_mg), goal = "${optionalGoals.vitaminE}", labelGlyph = "E")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_vitamin_k), fmt(vitaminK), stringResource(R.string.unit_mcg), goal = "${optionalGoals.vitaminK}", labelGlyph = "K")
                    Hairline()
                    DetailRow(Icons.Filled.Spa, stringResource(R.string.nutrition_label_folate), fmt(folate), stringResource(R.string.unit_mcg), goal = "${optionalGoals.folate}")
                    Hairline()
                    DetailRow(Icons.Filled.WaterDrop, stringResource(R.string.nutrition_label_omega3), fmt(omega3), stringResource(R.string.unit_g), goal = "${optionalGoals.omega3}")
                    SupplementalNutrient.values().forEach { nutrient ->
                        Hairline()
                        DetailRow(
                            Icons.Filled.Bolt,
                            stringResource(nutrient.displayNameRes),
                            fmt(supplementalNutrients[nutrient] ?: 0.0),
                            stringResource(R.string.unit_g),
                            goal = "${optionalGoals.valueFor(nutrient.optionalNutrient)}"
                        )
                    }
                }
            }
        }
    }

    if (showHomeCardsPicker) {
        HomeTopNutrientPickerDialog(
            selected = homeTopNutrients,
            waterTrackingEnabled = waterTrackingEnabled,
            onSave = onHomeTopNutrientsChange,
            onDismiss = { showHomeCardsPicker = false }
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    FudGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        padding = 0.dp
    ) {
        Column { content() }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        letterSpacing = 0.sp,
        modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun HomeCardsRow(
    selected: List<HomeTopNutrient>,
    waterTrackingEnabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Filled.Spa, null, tint = AppColors.Calorie, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_nutrient_cards), fontSize = 17.sp)
            val nutrientNames = selected
                .take(if (waterTrackingEnabled) 3 else 4)
                .map { stringResource(it.displayNameRes) }
            val displayedNames = if (waterTrackingEnabled) {
                nutrientNames + stringResource(R.string.water)
            } else {
                nutrientNames
            }
            Text(
                displayedNames.joinToString(", "),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun HomeTopNutrientPickerDialog(
    selected: List<HomeTopNutrient>,
    waterTrackingEnabled: Boolean,
    onSave: (List<HomeTopNutrient>) -> Unit,
    onDismiss: () -> Unit
) {
    val normalizedSelection = remember(selected) { HomeTopNutrient.normalized(selected) }
    val selectionLimit = if (waterTrackingEnabled) 3 else 4
    var draft by remember(selected, waterTrackingEnabled) {
        mutableStateOf(normalizedSelection.take(selectionLimit))
    }
    val hiddenFourthNutrient = remember(selected, waterTrackingEnabled) {
        if (waterTrackingEnabled) normalizedSelection.getOrNull(3) else null
    }

    fun toggle(nutrient: HomeTopNutrient) {
        draft = if (nutrient in draft) {
            if (draft.size <= 1) draft else draft - nutrient
        } else {
            // iOS swaps out the last when full (removeLast + append) rather than ignoring.
            if (draft.size >= selectionLimit) draft.dropLast(1) + nutrient else draft + nutrient
        }
    }

    FudGlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.home_nutrients), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(
                if (waterTrackingEnabled) R.string.home_nutrients_pick_three_water
                else R.string.home_nutrients_pick_four
            ),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 430.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (waterTrackingEnabled) {
                item(key = "fixed-water") {
                    val shape = RoundedCornerShape(16.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(AppColors.Calorie.copy(alpha = 0.11f))
                            .border(0.7.dp, AppColors.Calorie.copy(alpha = 0.22f), shape)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.WaterDrop,
                            contentDescription = null,
                            tint = AppColors.Calorie,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.water), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                stringResource(R.string.home_nutrients_water_fixed_short),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            items(HomeTopNutrient.values().toList()) { nutrient ->
                val checked = nutrient in draft
                val shape = RoundedCornerShape(16.dp)
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            if (checked) AppColors.Calorie.copy(alpha = 0.11f)
                            else if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
                            else Color(0xFFEDE3DD).copy(alpha = 0.76f)
                        )
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (isDark) 0.08f else 0.18f),
                                    Color.White.copy(alpha = if (isDark) 0.02f else 0.04f),
                                    AppColors.Calorie.copy(alpha = if (checked) 0.065f else if (isDark) 0.025f else 0.050f)
                                )
                            )
                        )
                        .border(
                            0.7.dp,
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = if (isDark) 0.16f else 0.46f),
                                    AppColors.Calorie.copy(alpha = if (checked) 0.22f else if (isDark) 0.08f else 0.16f)
                                )
                            ),
                            shape
                        )
                        .clickable { toggle(nutrient) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (checked) Brush.linearGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd))
                                else Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                                    )
                                )
                            )
                            .border(
                                1.dp,
                                if (checked) AppColors.Calorie.copy(alpha = 0.40f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (checked) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(nutrient.displayNameRes), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            stringResource(nutrient.unitRes),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
        FudGlassDialogActions(
            primaryText = stringResource(R.string.action_done),
            onPrimary = {
                // Keep an existing hidden choice only when all three visible slots are used.
                val savedSelection = if (waterTrackingEnabled && draft.size == 3 &&
                    hiddenFourthNutrient != null && hiddenFourthNutrient !in draft
                ) draft + hiddenFourthNutrient else draft
                onSave(HomeTopNutrient.normalized(savedSelection))
                onDismiss()
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryEnabled = draft.size in 1..selectionLimit
        )
    }
}

/**
 * Row layout: icon (24dp pink, optional) + label (17sp) + value (17sp pink semibold)
 * + unit (13sp secondary) + optional '/ goal' (12sp tertiary).
 *
 * iOS uses LinearGradient on the SF Symbol; Compose uses a flat tint
 * since Material icons aren't text-paintable.
 */
@Composable
private fun DetailRow(
    icon: ImageVector?,
    label: String,
    value: String,
    unit: String,
    goal: String? = null,
    labelGlyph: String? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, tint = AppColors.Calorie, modifier = Modifier.size(20.dp))
        } else if (labelGlyph != null) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.Calorie),
                contentAlignment = Alignment.Center
            ) {
                Text(labelGlyph, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
            }
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Calorie)
            Text(unit, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        goal?.let {
            Text(
                "/ $it",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .padding(start = 14.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    )
}
