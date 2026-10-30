package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.apoorvdarshan.calorietracker.FudAIApp
import com.apoorvdarshan.calorietracker.ui.util.gramsText
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.ui.components.LocalEnergyUnit
import com.apoorvdarshan.calorietracker.ui.components.energyText
import com.apoorvdarshan.calorietracker.ui.components.energyUnitLabel
import com.apoorvdarshan.calorietracker.ui.components.rememberFoodThumbnail
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.MacroValueFormatter
import kotlin.math.roundToInt
import com.apoorvdarshan.calorietracker.models.IngredientPortion
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.RecipeServings
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.serialization.Serializable

@Serializable
internal data class IngredientEditorTarget(
    val index: Int?,
    val ingredient: MealIngredient
)

@Composable
internal fun MealIngredientsCard(
    ingredients: List<MealIngredient>,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit = {},
    addMenu: (@Composable () -> Unit)? = null,
    imageStore: FoodImageStore? = null,
    /** "Servings in recipe" value; null hides the row (#458). */
    recipeServings: Int? = null,
    onRecipeServingsChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val store = imageStore ?: remember(context) {
        (context.applicationContext as FudAIApp).container.imageStore
    }
    SheetPillCard {
        if (ingredients.isEmpty()) {
            Text(
                stringResource(R.string.ingredients_empty),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
            )
        } else {
            ingredients.forEachIndexed { index, ingredient ->
                if (index > 0) SheetHairline()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(index) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val thumbnail = rememberFoodThumbnail(store, ingredient.imageFilename)
                        if (thumbnail != null) {
                            Image(thumbnail.asImageBitmap(), contentDescription = null,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop)
                        } else {
                            Text(ingredient.emoji ?: "🍽️", fontSize = 26.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            ingredient.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${gramsText(ingredient.grams)} · ${energyText(ingredient.calories)}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IngredientMacro(stringResource(R.string.macro_abbrev_protein), ingredient.protein, AppColors.Protein)
                        IngredientMacro(stringResource(R.string.macro_abbrev_carbs), ingredient.carbs, AppColors.Carbs)
                        IngredientMacro(stringResource(R.string.macro_abbrev_fat), ingredient.fat, AppColors.Fat)
                    }
                }
            }
        }
        SheetHairline()
        if (addMenu != null) {
            addMenu()
        } else {
            MealIngredientsAddRow(onClick = onAdd)
        }
        if (recipeServings != null) {
            SheetHairline()
            RecipeServingsRow(recipeServings, onRecipeServingsChange)
        }
    }
}

@Composable
internal fun RecipeServingsRow(servings: Int, onChange: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.recipe_servings_title),
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            RecipeServingsStepButton(
                icon = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.cd_recipe_servings_fewer),
                enabled = servings > RecipeServings.MIN,
                onClick = { onChange(servings - 1) }
            )
            // A polite live region, so TalkBack reads the new count after - or +.
            Text(
                servings.toString(),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            RecipeServingsStepButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.cd_recipe_servings_more),
                enabled = servings < RecipeServings.MAX,
                onClick = { onChange(servings + 1) }
            )
        }
        Text(
            stringResource(R.string.recipe_servings_footer),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

/** A real button with a 48dp touch target, so TalkBack and switch access treat it as one. */
@Composable
private fun RecipeServingsStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = AppColors.Calorie,
            disabledContentColor = AppColors.Calorie.copy(alpha = 0.35f)
        )
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

@Composable
internal fun MealIngredientsAddRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.AddCircle, contentDescription = null, tint = AppColors.Calorie)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.ingredients_add),
            color = AppColors.Calorie,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun IngredientMacro(label: String, value: Double, color: androidx.compose.ui.graphics.Color) {
    Text(
        "$label ${gramsText(value)}",
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
}

internal val IngredientPortionSaver = listSaver<IngredientPortion, Double>(
    save = { listOf(it.grams, it.calories, it.protein, it.carbs, it.fat) },
    restore = { IngredientPortion(it[0], it[1], it[2], it[3], it[4]) }
)

@Composable
internal fun MealIngredientEditorDialog(
    target: IngredientEditorTarget,
    onSave: (MealIngredient) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    // Calories here (text and base) are in the energy unit in use; save converts to kcal.
    val energyUnit = LocalEnergyUnit.current
    var name by rememberSaveable(target) { mutableStateOf(target.ingredient.name) }
    var grams by rememberSaveable(target) { mutableStateOf(MacroValueFormatter.string(target.ingredient.grams)) }
    var calories by rememberSaveable(target) { mutableStateOf(energyUnit.displayValue(target.ingredient.calories).toString()) }
    var protein by rememberSaveable(target) { mutableStateOf(MacroValueFormatter.string(target.ingredient.protein)) }
    var carbs by rememberSaveable(target) { mutableStateOf(MacroValueFormatter.string(target.ingredient.carbs)) }
    var fat by rememberSaveable(target) { mutableStateOf(MacroValueFormatter.string(target.ingredient.fat)) }
    var nutritionBase by rememberSaveable(target, stateSaver = IngredientPortionSaver) { mutableStateOf(IngredientPortion(
        target.ingredient.grams, energyUnit.displayAmount(target.ingredient.calories.toDouble()),
        target.ingredient.protein, target.ingredient.carbs, target.ingredient.fat
    )) }
    fun number(text: String) = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    fun rebaseNutrition() {
        val weight = number(grams)?.takeIf { it > 0 } ?: return
        nutritionBase = IngredientPortion(weight, number(calories) ?: return,
            number(protein) ?: return, number(carbs) ?: return, number(fat) ?: return)
    }
    fun changeWeight(text: String) {
        grams = text
        val weight = number(text) ?: return
        val scaled = nutritionBase.resized(weight) ?: return
        calories = scaled.calories.roundToInt().toString()
        protein = MacroValueFormatter.string(scaled.protein)
        carbs = MacroValueFormatter.string(scaled.carbs)
        fat = MacroValueFormatter.string(scaled.fat)
    }
    val parsed = name.trim().takeIf { it.isNotEmpty() }?.let { validName ->
        val parsedGrams = number(grams)?.takeIf { it > 0 && nutritionBase.resized(it) != null } ?: return@let null
        val parsedCalories = number(calories)?.takeIf { it < Int.MAX_VALUE.toDouble() } ?: return@let null
        val parsedProtein = number(protein) ?: return@let null
        val parsedCarbs = number(carbs) ?: return@let null
        val parsedFat = number(fat) ?: return@let null
        MealIngredient(
            name = validName,
            grams = parsedGrams,
            calories = energyUnit.toKcal(parsedCalories),
            protein = parsedProtein,
            carbs = parsedCarbs,
            fat = parsedFat,
            imageFilename = target.ingredient.imageFilename,
            additionalImageFilenames = target.ingredient.additionalImageFilenames,
            emoji = target.ingredient.emoji
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (target.index == null) R.string.ingredients_add else R.string.ingredients_edit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.sheet_name)) }, singleLine = true)
                IngredientNumberField(stringResource(R.string.ingredients_weight), grams, ::changeWeight, stringResource(R.string.unit_g))
                IngredientNumberField(stringResource(R.string.nutrition_label_calories), calories, { calories = it; rebaseNutrition() }, energyUnitLabel())
                IngredientNumberField(stringResource(R.string.nutrition_label_protein), protein, { protein = it; rebaseNutrition() }, stringResource(R.string.unit_g))
                IngredientNumberField(stringResource(R.string.nutrition_label_carbs), carbs, { carbs = it; rebaseNutrition() }, stringResource(R.string.unit_g))
                IngredientNumberField(stringResource(R.string.nutrition_label_fat), fat, { fat = it; rebaseNutrition() }, stringResource(R.string.unit_g))
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = {
                parsed?.let(onSave)
                onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { onDelete(); onDismiss() }) {
                        Text(stringResource(R.string.ingredients_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )
}

@Composable
private fun IngredientNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suffix: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
}
