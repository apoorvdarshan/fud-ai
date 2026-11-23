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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.apoorvdarshan.calorietracker.ui.components.FlashBottomSheet as ModalBottomSheet
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.AminoAcid
import com.apoorvdarshan.calorietracker.models.AminoAcidEstimates
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.HomeTopNutrient
import com.apoorvdarshan.calorietracker.models.MacroValueFormatter
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import com.apoorvdarshan.calorietracker.models.SupplementalNutrient
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.LocalEnergyUnit
import com.apoorvdarshan.calorietracker.ui.components.energyUnitLabel
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette

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
    aminoAcidsEnabled: Boolean = false,
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
    val aminoAcidTotals = AminoAcidEstimates.dailyTotals(entries)
    val neo = pinkNeoPalette()

    fun fmt(v: Double): String = if (v == 0.0) "—" else String.format("%.1f", v)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.nutrition_details_title),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = neo.accent) }
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
                    DetailRow(Icons.Filled.LocalFireDepartment, stringResource(R.string.nutrition_label_calories), "${LocalEnergyUnit.current.displayValue(calories)}", energyUnitLabel(), goal = "${LocalEnergyUnit.current.displayValue(profile?.effectiveCalories ?: 2000)}")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_protein), MacroValueFormatter.display(protein), stringResource(R.string.unit_g), goal = "${profile?.effectiveProtein ?: 150}", labelGlyph = "P")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_carbs), MacroValueFormatter.display(carbs), stringResource(R.string.unit_g), goal = "${profile?.effectiveCarbs ?: 220}", labelGlyph = "C")
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_fat), MacroValueFormatter.display(fat), stringResource(R.string.unit_g), goal = "${profile?.effectiveFat ?: 70}", labelGlyph = "F")
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

            if (aminoAcidsEnabled) {
                item { SectionHeader(stringResource(R.string.nutrition_section_amino_acids)) }
                item {
                    Card {
                        AminoAcid.values().forEachIndexed { index, aminoAcid ->
                            if (index > 0) Hairline()
                            AminoAcidDetailRow(
                                label = stringResource(aminoAcid.displayNameRes),
                                grams = aminoAcidTotals[aminoAcid],
                                referenceGrams = profile?.weightKg?.let(aminoAcid::referenceGrams),
                                referenceLabelRes = aminoAcid.referenceLabelRes
                            )
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.nutrition_amino_acids_note),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
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
    val neo = pinkNeoPalette()
    Column(Modifier.fillMaxWidth().background(neo.panel).border(1.dp, neo.rule)) { content() }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Black,
        color = pinkNeoPalette().ink,
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
            Text(stringResource(R.string.home_nutrient_cards), fontSize = 17.sp, fontWeight = FontWeight.Black)
            val nutrientNames = selected
                .take(4)
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
    val selectionLimit = 4
    val neo = pinkNeoPalette()
    var draft by remember(selected) {
        mutableStateOf(normalizedSelection.take(selectionLimit))
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(neo.paper)
                            .border(1.dp, neo.rule)
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(neo.panel)
                        .border(if (checked) 2.dp else 1.dp, if (checked) neo.accent else neo.rule)
                        .toggleable(value = checked, role = Role.Checkbox) { toggle(nutrient) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(if (checked) neo.action else neo.panel)
                            .border(1.dp, neo.rule),
                        contentAlignment = Alignment.Center
                    ) {
                        if (checked) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = neo.onAction,
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
                onSave(HomeTopNutrient.normalized(draft))
                onDismiss()
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryEnabled = draft.size in 1..selectionLimit
        )
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector?,
    label: String,
    value: String,
    unit: String,
    goal: String? = null,
    labelGlyph: String? = null
) {
    val neo = pinkNeoPalette()
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val reading: @Composable () -> Unit = {
        Text(
            "$value $unit",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = neo.accent
        )
        goal?.let {
            Text("/ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, tint = neo.accent, modifier = Modifier.size(20.dp))
        } else if (labelGlyph != null) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(neo.action),
                contentAlignment = Alignment.Center
            ) {
                Text(labelGlyph, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = neo.onAction)
            }
        } else {
            Spacer(Modifier.width(20.dp))
        }
        if (largeText) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                reading()
            }
        } else {
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                reading()
            }
        }
    }
}

/** Daily amino acid estimate with its WHO/FAO/UNU reference (#491), never shown as a goal. */
@Composable
private fun AminoAcidDetailRow(label: String, grams: Double?, referenceGrams: Double?, referenceLabelRes: Int) {
    val gramUnit = stringResource(R.string.unit_g)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Filled.Science, null, tint = AppColors.Calorie, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 17.sp)
            if (referenceGrams != null) {
                Text(
                    stringResource(
                        referenceLabelRes,
                        "${AminoAcidEstimates.formattedGrams(referenceGrams)} $gramUnit"
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                grams?.let(AminoAcidEstimates::formattedGrams) ?: stringResource(R.string.nutrition_em_dash),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Calorie
            )
            Text(gramUnit, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
            .background(pinkNeoPalette().rule.copy(alpha = 0.24f))
    )
}
