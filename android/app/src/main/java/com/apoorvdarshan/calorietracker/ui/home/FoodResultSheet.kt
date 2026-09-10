package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.services.FoodImageDecoder
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MacroValueFormatter
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import com.apoorvdarshan.calorietracker.models.ServingAmountExpression
import com.apoorvdarshan.calorietracker.models.SupplementalNutrient
import com.apoorvdarshan.calorietracker.models.totals
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.allergenAnalysis
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.ui.components.FullscreenMealPhotoViewer
import com.apoorvdarshan.calorietracker.ui.components.MealPhotoViewerState
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import androidx.compose.foundation.clickable
import kotlin.math.roundToInt
import java.time.Instant

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
    imageBytesList: List<ByteArray> = emptyList(),
    preferGramsByDefault: Boolean = false,
    profile: UserProfile? = null,
    dayEntries: List<FoodEntry> = emptyList(),
    source: FoodSource = FoodSource.TEXT_INPUT,
    isSubmitting: Boolean = false,
    container: com.apoorvdarshan.calorietracker.AppContainer,
    analyzeIngredientText: suspend (String) -> FoodAnalysis,
    lookupIngredientBarcode: suspend (String) -> FoodAnalysis,
    analyzeIngredientImage: suspend (ByteArray) -> FoodAnalysis,
    onWhatIfSuggestion: (suspend (FoodEntry) -> String)? = null,
    onSave: (
        name: String,
        servingGrams: Double,
        servingSizeIsKnown: Boolean,
        scale: Double,
        mealType: MealType,
        selectedServingUnit: String?,
        selectedServingQuantity: Double?,
        editedAnalysis: FoodAnalysis
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val bitmaps = remember(imageBytesList) {
        imageBytesList.mapNotNull { FoodImageDecoder.decode(it, 720) }
    }
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden }
    )
    var name by rememberSaveable { mutableStateOf(analysis.name) }
    var servingSizeIsKnown by rememberSaveable(analysis) { mutableStateOf(analysis.servingSizeIsKnown) }
    var servingUnitOptions by rememberSaveable(analysis.servingUnitOptions, analysis.servingSizeGrams, analysis.servingSizeIsKnown, stateSaver = foodDraftSaver<List<ServingUnitOption>>()) {
        mutableStateOf(
            if (analysis.servingSizeIsKnown) {
                ServingUnitOption.normalizedOptions(analysis.servingUnitOptions, analysis.servingSizeGrams)
            } else {
                listOf(ServingUnitOption.loggedServing(analysis.servingSizeGrams))
            }
        )
    }
    val initialServingUnit = if (servingSizeIsKnown && preferGramsByDefault) {
        ServingUnitOption.grams.unit
    } else {
        analysis.selectedServingUnit
    }
    var selectedServingUnitId by rememberSaveable(analysis, servingUnitOptions, preferGramsByDefault) {
        mutableStateOf(ServingUnitOption.initialUnitId(initialServingUnit, servingUnitOptions))
    }
    var baseServingGrams by rememberSaveable(analysis) { mutableStateOf(analysis.servingSizeGrams) }
    var servingGrams by rememberSaveable(analysis) { mutableStateOf(analysis.servingSizeGrams) }
    var servingQuantityText by rememberSaveable(analysis, servingUnitOptions, preferGramsByDefault) {
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
    val selectedServingQuantity = ServingAmountExpression.evaluate(servingQuantityText)?.takeIf { it > 0 }
    val scale = if (baseServingGrams > 0) servingGrams / baseServingGrams else 1.0
    var mealType by rememberSaveable { mutableStateOf(MealType.currentMeal) }
    var moreNutritionExpanded by rememberSaveable { mutableStateOf(false) }
    var nutritionUnlocked by rememberSaveable { mutableStateOf(false) }
    var editableCalories by rememberSaveable(analysis) { mutableStateOf(analysis.calories) }
    var editableProtein by rememberSaveable(analysis) { mutableStateOf(analysis.protein) }
    var editableCarbs by rememberSaveable(analysis) { mutableStateOf(analysis.carbs) }
    var editableFat by rememberSaveable(analysis) { mutableStateOf(analysis.fat) }
    var editableSugar by rememberSaveable(analysis) { mutableStateOf(analysis.sugar) }
    var editableAddedSugar by rememberSaveable(analysis) { mutableStateOf(analysis.addedSugar) }
    var editableFiber by rememberSaveable(analysis) { mutableStateOf(analysis.fiber) }
    var editableSaturatedFat by rememberSaveable(analysis) { mutableStateOf(analysis.saturatedFat) }
    var editableMonounsaturatedFat by rememberSaveable(analysis) { mutableStateOf(analysis.monounsaturatedFat) }
    var editablePolyunsaturatedFat by rememberSaveable(analysis) { mutableStateOf(analysis.polyunsaturatedFat) }
    var editableCholesterol by rememberSaveable(analysis) { mutableStateOf(analysis.cholesterol) }
    var editableCaffeine by rememberSaveable(analysis) { mutableStateOf(analysis.caffeine) }
    var editableSupplementalNutrients by rememberSaveable(analysis, stateSaver = foodDraftSaver<Map<String, Double>>()) { mutableStateOf(analysis.supplementalNutrients) }
    var editableSodium by rememberSaveable(analysis) { mutableStateOf(analysis.sodium) }
    var editablePotassium by rememberSaveable(analysis) { mutableStateOf(analysis.potassium) }
    var editableTransFat by rememberSaveable(analysis) { mutableStateOf(analysis.transFat) }
    var editableCalcium by rememberSaveable(analysis) { mutableStateOf(analysis.calcium) }
    var editableIron by rememberSaveable(analysis) { mutableStateOf(analysis.iron) }
    var editableMagnesium by rememberSaveable(analysis) { mutableStateOf(analysis.magnesium) }
    var editableZinc by rememberSaveable(analysis) { mutableStateOf(analysis.zinc) }
    var editableVitaminA by rememberSaveable(analysis) { mutableStateOf(analysis.vitaminA) }
    var editableVitaminC by rememberSaveable(analysis) { mutableStateOf(analysis.vitaminC) }
    var editableVitaminD by rememberSaveable(analysis) { mutableStateOf(analysis.vitaminD) }
    var editableVitaminB12 by rememberSaveable(analysis) { mutableStateOf(analysis.vitaminB12) }
    var editableVitaminE by rememberSaveable(analysis) { mutableStateOf(analysis.vitaminE) }
    var editableVitaminK by rememberSaveable(analysis) { mutableStateOf(analysis.vitaminK) }
    var editableFolate by rememberSaveable(analysis) { mutableStateOf(analysis.folate) }
    var editableOmega3 by rememberSaveable(analysis) { mutableStateOf(analysis.omega3) }
    var editableIngredients by rememberSaveable(analysis, stateSaver = foodDraftSaver<List<MealIngredient>>()) { mutableStateOf(analysis.ingredients) }
    var ingredientEditor by rememberSaveable(stateSaver = foodDraftSaver<IngredientEditorTarget?>()) { mutableStateOf<IngredientEditorTarget?>(null) }
    var photoViewerState by remember { mutableStateOf<MealPhotoViewerState?>(null) }
    var mealMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var servingMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetSurface = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFAF3EE)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboard = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val emDashText = stringResource(R.string.nutrition_em_dash)

    fun scaledInt(v: Int) = (v * scale).roundToInt()
    fun scaledMacro(v: Double) = v * scale
    fun scaledD(v: Double?) = v?.let { ((it * scale) * 10).roundToInt() / 10.0 }
    fun displayD(v: Double?) = v?.let { String.format("%.1f", it) } ?: emDashText
    fun editD(v: Double?) = v?.let { String.format("%.1f", it) }.orEmpty()
    fun decimalValue(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 }
    fun baseDoubleFromText(text: String): Double = (decimalValue(text) ?: 0.0) / scale.coerceAtLeast(0.0001)
    fun baseOptionalFromText(text: String): Double? = decimalValue(text)?.let { it / scale.coerceAtLeast(0.0001) }
    fun scaledIngredients() = editableIngredients.map { it.scaled(scale) }
    fun applyIngredientChanges(displayedIngredients: List<MealIngredient>) {
        editableIngredients = displayedIngredients
        val totals = displayedIngredients.totals()
        editableCalories = totals.calories
        editableProtein = totals.protein
        editableCarbs = totals.carbs
        editableFat = totals.fat
        if (totals.grams > 0) {
            baseServingGrams = totals.grams
            servingGrams = totals.grams
            servingQuantityText = ServingUnitOption.formatQuantity(totals.grams)
            selectedServingUnitId = ServingUnitOption.grams.unit
            servingUnitOptions = emptyList()
            servingSizeIsKnown = true
        }
    }
    fun editedAnalysis() = analysis.copy(
        name = name.trim().ifEmpty { analysis.name },
        calories = editableCalories,
        protein = editableProtein,
        carbs = editableCarbs,
        fat = editableFat,
        sugar = editableSugar,
        addedSugar = editableAddedSugar,
        fiber = editableFiber,
        saturatedFat = editableSaturatedFat,
        monounsaturatedFat = editableMonounsaturatedFat,
        polyunsaturatedFat = editablePolyunsaturatedFat,
        cholesterol = editableCholesterol,
        caffeine = editableCaffeine,
        supplementalNutrients = editableSupplementalNutrients,
        sodium = editableSodium,
        potassium = editablePotassium,
        transFat = editableTransFat,
        calcium = editableCalcium,
        iron = editableIron,
        magnesium = editableMagnesium,
        zinc = editableZinc,
        vitaminA = editableVitaminA,
        vitaminC = editableVitaminC,
        vitaminD = editableVitaminD,
        vitaminB12 = editableVitaminB12,
        vitaminE = editableVitaminE,
        vitaminK = editableVitaminK,
        folate = editableFolate,
        omega3 = editableOmega3,
        servingSizeGrams = baseServingGrams,
        servingUnitOptions = if (servingSizeIsKnown) servingUnitOptions else emptyList(),
        selectedServingUnit = if (servingSizeIsKnown) {
            if (servingUnitOptions.isEmpty()) null else selectedServingOption.unit
        } else {
            "serving"
        },
        selectedServingQuantity = if (servingSizeIsKnown) {
            if (servingUnitOptions.isEmpty()) null else selectedServingQuantity
        } else {
            selectedServingQuantity
        },
        servingSizeIsKnown = servingSizeIsKnown,
        ingredients = editableIngredients,
        productMetadata = analysis.productMetadata
    )
    fun previewEntry() = FoodEntry(
        name = name.trim().ifEmpty { analysis.name },
        calories = scaledInt(editableCalories),
        protein = scaledMacro(editableProtein),
        carbs = scaledMacro(editableCarbs),
        fat = scaledMacro(editableFat),
        timestamp = Instant.now(),
        imageFilename = null,
        emoji = analysis.emoji,
        source = source,
        mealType = mealType,
        sugar = scaledD(editableSugar),
        addedSugar = scaledD(editableAddedSugar),
        fiber = scaledD(editableFiber),
        saturatedFat = scaledD(editableSaturatedFat),
        monounsaturatedFat = scaledD(editableMonounsaturatedFat),
        polyunsaturatedFat = scaledD(editablePolyunsaturatedFat),
        cholesterol = scaledD(editableCholesterol),
        caffeine = scaledD(editableCaffeine),
        supplementalNutrients = editableSupplementalNutrients.mapValues { (_, value) -> scaledD(value) ?: 0.0 },
        sodium = scaledD(editableSodium),
        potassium = scaledD(editablePotassium),
        transFat = scaledD(editableTransFat),
        calcium = scaledD(editableCalcium),
        iron = scaledD(editableIron),
        magnesium = scaledD(editableMagnesium),
        zinc = scaledD(editableZinc),
        vitaminA = scaledD(editableVitaminA),
        vitaminC = scaledD(editableVitaminC),
        vitaminD = scaledD(editableVitaminD),
        vitaminB12 = scaledD(editableVitaminB12),
        vitaminE = scaledD(editableVitaminE),
        vitaminK = scaledD(editableVitaminK),
        folate = scaledD(editableFolate),
        omega3 = scaledD(editableOmega3),
        servingSizeGrams = servingGrams.takeIf { servingSizeIsKnown },
        servingUnitOptions = if (servingSizeIsKnown) servingUnitOptions else emptyList(),
        selectedServingUnit = if (servingSizeIsKnown) {
            if (servingUnitOptions.isEmpty()) null else selectedServingOption.unit
        } else {
            "serving"
        },
        selectedServingQuantity = if (servingSizeIsKnown) {
            if (servingUnitOptions.isEmpty()) null else selectedServingQuantity
        } else {
            selectedServingQuantity
        },
        ingredients = scaledIngredients(),
        productMetadata = analysis.productMetadata
    )
    var whatIfEntry by rememberSaveable(stateSaver = foodDraftSaver<FoodEntry?>()) { mutableStateOf<FoodEntry?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetSurface
    ) {
        SheetReviewToolbar(
            title = stringResource(R.string.sheet_review_food),
            primaryLabel = stringResource(R.string.action_log),
            secondaryLabel = stringResource(R.string.action_what_if),
            primaryEnabled = !isSubmitting,
            onCancel = { if (!isSubmitting) onDismiss() },
            onPrimary = {
                onSave(
                    name.trim().ifEmpty { analysis.name },
                    servingGrams,
                    servingSizeIsKnown,
                    scale,
                    mealType,
                    if (servingUnitOptions.isEmpty()) null else selectedServingOption.unit,
                    if (servingUnitOptions.isEmpty()) null else selectedServingQuantity,
                    editedAnalysis()
                )
            },
            onSecondary = { whatIfEntry = previewEntry() }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { dismissKeyboard() })
                }
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Swipeable original-photo gallery OR 80sp emoji fallback.
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmaps.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                        ) {
                            itemsIndexed(bitmaps) { index, bitmap ->
                                Box {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Photo ${index + 1}",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier
                                            .size(240.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .clickable {
                                                photoViewerState = MealPhotoViewerState(
                                                    bitmaps = bitmaps,
                                                    startIndex = index
                                                )
                                            }
                                    )
                                    if (bitmaps.size > 1) {
                                        Text(
                                            "${index + 1}/${bitmaps.size}",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(10.dp)
                                                .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(50))
                                                .padding(horizontal = 9.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
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

            analysis.productMetadata?.takeIf { it.hasDisplayDetails }?.let { metadata ->
                item { SheetSectionHeader(stringResource(R.string.product_information)) }
                item {
                    val previewEntry = FoodEntry(
                        name = name,
                        calories = analysis.calories,
                        protein = analysis.protein,
                        carbs = analysis.carbs,
                        fat = analysis.fat,
                        source = source,
                        ingredients = analysis.ingredients,
                        productMetadata = analysis.productMetadata
                    )
                    FoodProductMetadataCard(
                        metadata,
                        previewEntry.allergenAnalysis(profile?.allergenSensitivities.orEmpty())
                    )
                }
            }
            if (analysis.productMetadata?.hasDisplayDetails != true &&
                profile?.allergenSensitivities?.isNotEmpty() == true
            ) {
                item { SheetSectionHeader(stringResource(R.string.product_allergen_check)) }
                item {
                    val previewEntry = FoodEntry(
                        name = name,
                        calories = analysis.calories,
                        protein = analysis.protein,
                        carbs = analysis.carbs,
                        fat = analysis.fat,
                        source = source,
                        ingredients = analysis.ingredients,
                        productMetadata = analysis.productMetadata
                    )
                    SheetPillCard {
                        Text(
                            previewEntry.allergenAnalysis(profile.allergenSensitivities).summary,
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                        )
                    }
                }
            }

            item { SheetSectionHeader(stringResource(R.string.sheet_serving)) }
            item {
                ServingQuantityCard(
                    quantityText = servingQuantityText,
                    onQuantityChange = { newValue ->
                        servingQuantityText = newValue
                        ServingAmountExpression.evaluate(newValue)?.takeIf { it > 0 }?.let {
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
                    allowGramUnit = servingSizeIsKnown,
                    showGramTotal = servingSizeIsKnown,
                    menuExpanded = servingMenuExpanded,
                    onMenuExpandedChange = { servingMenuExpanded = it },
                    gramUnit = stringResource(R.string.unit_g)
                )
            }

            item {
                SheetSectionHeaderWithLock(
                    title = stringResource(R.string.sheet_nutrition),
                    unlocked = nutritionUnlocked,
                    onToggle = {
                        nutritionUnlocked = !nutritionUnlocked
                        if (!nutritionUnlocked) dismissKeyboard()
                    }
                )
            }
            item {
                SheetPillCard {
                    ReviewNutritionValueRow(
                        label = stringResource(R.string.nutrition_label_calories),
                        displayValue = "${scaledInt(editableCalories)}",
                        editValue = "${scaledInt(editableCalories)}",
                        unit = stringResource(R.string.unit_kcal),
                        unlocked = nutritionUnlocked,
                        onEdit = { editableCalories = baseDoubleFromText(it).roundToInt() }
                    )
                    SheetHairline()
                    ReviewNutritionValueRow(
                        label = stringResource(R.string.nutrition_label_protein),
                        displayValue = MacroValueFormatter.string(scaledMacro(editableProtein)),
                        editValue = MacroValueFormatter.string(scaledMacro(editableProtein)),
                        unit = stringResource(R.string.unit_g),
                        unlocked = nutritionUnlocked,
                        onEdit = { editableProtein = baseDoubleFromText(it) }
                    )
                    SheetHairline()
                    ReviewNutritionValueRow(
                        label = stringResource(R.string.nutrition_label_carbs),
                        displayValue = MacroValueFormatter.string(scaledMacro(editableCarbs)),
                        editValue = MacroValueFormatter.string(scaledMacro(editableCarbs)),
                        unit = stringResource(R.string.unit_g),
                        unlocked = nutritionUnlocked,
                        onEdit = { editableCarbs = baseDoubleFromText(it) }
                    )
                    SheetHairline()
                    ReviewNutritionValueRow(
                        label = stringResource(R.string.nutrition_label_fat),
                        displayValue = MacroValueFormatter.string(scaledMacro(editableFat)),
                        editValue = MacroValueFormatter.string(scaledMacro(editableFat)),
                        unit = stringResource(R.string.unit_g),
                        unlocked = nutritionUnlocked,
                        onEdit = { editableFat = baseDoubleFromText(it) }
                    )
                }
            }

            item { SheetSectionHeader(stringResource(R.string.ingredients_title)) }
            item {
                MealIngredientsCard(
                    ingredients = scaledIngredients(),
                    onEdit = { index ->
                        ingredientEditor = IngredientEditorTarget(index, scaledIngredients()[index])
                    },
                    addMenu = {
                        IngredientIntakeSection(
                            container = container,
                            analyzeText = analyzeIngredientText,
                            lookupBarcode = lookupIngredientBarcode,
                            analyzeImage = analyzeIngredientImage,
                            onIngredient = { ingredient ->
                                applyIngredientChanges(scaledIngredients() + ingredient)
                            },
                            onManual = {
                                ingredientEditor = IngredientEditorTarget(
                                    null,
                                    MealIngredient("", 100.0, 0, 0.0, 0.0, 0.0)
                                )
                            }
                        )
                    }
                )
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
                        val mcgUnit = stringResource(R.string.unit_mcg)
                        val micros = listOf(
                            ReviewNutrientEditSpec(stringResource(R.string.sheet_micro_sugar), scaledD(editableSugar), gUnit, { editableSugar = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.sheet_micro_added_sugar), scaledD(editableAddedSugar), gUnit, { editableAddedSugar = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.sheet_micro_fiber), scaledD(editableFiber), gUnit, { editableFiber = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.sheet_micro_saturated_fat), scaledD(editableSaturatedFat), gUnit, { editableSaturatedFat = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.sheet_micro_mono_fat), scaledD(editableMonounsaturatedFat), gUnit, { editableMonounsaturatedFat = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.sheet_micro_poly_fat), scaledD(editablePolyunsaturatedFat), gUnit, { editablePolyunsaturatedFat = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.sheet_micro_cholesterol), scaledD(editableCholesterol), mgUnit, { editableCholesterol = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_caffeine), scaledD(editableCaffeine), mgUnit, { editableCaffeine = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.sheet_micro_sodium), scaledD(editableSodium), mgUnit, { editableSodium = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.sheet_micro_potassium), scaledD(editablePotassium), mgUnit, { editablePotassium = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_trans_fat), scaledD(editableTransFat), gUnit, { editableTransFat = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_calcium), scaledD(editableCalcium), mgUnit, { editableCalcium = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_iron), scaledD(editableIron), mgUnit, { editableIron = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_magnesium), scaledD(editableMagnesium), mgUnit, { editableMagnesium = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_zinc), scaledD(editableZinc), mgUnit, { editableZinc = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_vitamin_a), scaledD(editableVitaminA), mcgUnit, { editableVitaminA = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_vitamin_c), scaledD(editableVitaminC), mgUnit, { editableVitaminC = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_vitamin_d), scaledD(editableVitaminD), mcgUnit, { editableVitaminD = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_vitamin_b12), scaledD(editableVitaminB12), mcgUnit, { editableVitaminB12 = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_vitamin_e), scaledD(editableVitaminE), mgUnit, { editableVitaminE = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_vitamin_k), scaledD(editableVitaminK), mcgUnit, { editableVitaminK = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_folate), scaledD(editableFolate), mcgUnit, { editableFolate = baseOptionalFromText(it) }),
                            ReviewNutrientEditSpec(stringResource(R.string.nutrition_label_omega3), scaledD(editableOmega3), gUnit, { editableOmega3 = baseOptionalFromText(it) })
                        ) + SupplementalNutrient.values().map { nutrient ->
                            ReviewNutrientEditSpec(
                                label = stringResource(nutrient.displayNameRes),
                                value = scaledD(editableSupplementalNutrients[nutrient.storageKey]),
                                unit = gUnit,
                                onEdit = { text ->
                                    val value = baseOptionalFromText(text)
                                    editableSupplementalNutrients = if (value == null) {
                                        editableSupplementalNutrients - nutrient.storageKey
                                    } else {
                                        editableSupplementalNutrients + (nutrient.storageKey to value)
                                    }
                                }
                            )
                        }
                        micros.forEachIndexed { idx, spec ->
                            if (idx > 0) SheetHairline()
                            ReviewNutritionValueRow(
                                label = spec.label,
                                displayValue = displayD(spec.value),
                                editValue = editD(spec.value),
                                unit = spec.unit,
                                unlocked = nutritionUnlocked,
                                dim = true,
                                onEdit = spec.onEdit
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

    whatIfEntry?.let { entry ->
        WhatIfMealImpactDialog(
            entry = entry,
            dayEntries = dayEntries,
            profile = profile,
            onDismiss = { whatIfEntry = null },
            onSuggest = onWhatIfSuggestion
        )
    }
    ingredientEditor?.let { target ->
        MealIngredientEditorDialog(
            target = target,
            onSave = { ingredient ->
                val next = scaledIngredients().toMutableList()
                val index = target.index
                if (index != null && index in next.indices) next[index] = ingredient else next.add(ingredient)
                applyIngredientChanges(next)
            },
            onDelete = target.index?.let { index ->
                {
                    val next = scaledIngredients().toMutableList()
                    if (index in next.indices) next.removeAt(index)
                    applyIngredientChanges(next)
                }
            },
            onDismiss = { ingredientEditor = null }
        )
    }
    photoViewerState?.let { viewerState ->
        FullscreenMealPhotoViewer(
            state = viewerState,
            onDismiss = { photoViewerState = null }
        )
    }
}

private data class ReviewNutrientEditSpec(
    val label: String,
    val value: Double?,
    val unit: String,
    val onEdit: (String) -> Unit
)

@Composable
private fun SheetSectionHeaderWithLock(
    title: String,
    unlocked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                if (unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                contentDescription = stringResource(
                    if (unlocked) R.string.nutrition_lock_editing
                    else R.string.nutrition_unlock_editing
                ),
                tint = if (unlocked) AppColors.Calorie
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ReviewNutritionValueRow(
    label: String,
    displayValue: String,
    editValue: String,
    unit: String,
    unlocked: Boolean,
    dim: Boolean = false,
    onEdit: (String) -> Unit
) {
    var draft by rememberSaveable(unlocked) { mutableStateOf(editValue) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 16.sp,
            color = if (dim) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (unlocked) {
            BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    onEdit(it)
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Calorie),
                modifier = Modifier.width(92.dp)
            )
        } else {
            Text(
                displayValue,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            unit,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.width(36.dp)
        )
    }
}

private data class WhatIfTotals(
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double
) {
    operator fun plus(other: WhatIfTotals) = WhatIfTotals(
        calories = calories + other.calories,
        protein = protein + other.protein,
        carbs = carbs + other.carbs,
        fat = fat + other.fat
    )
}

private fun List<FoodEntry>.whatIfTotals() = WhatIfTotals(
    calories = sumOf { it.calories },
    protein = sumOf { it.protein },
    carbs = sumOf { it.carbs },
    fat = sumOf { it.fat }
)

private fun FoodEntry.whatIfTotals() = WhatIfTotals(
    calories = calories,
    protein = protein,
    carbs = carbs,
    fat = fat
)

@Composable
private fun WhatIfMealImpactDialog(
    entry: FoodEntry,
    dayEntries: List<FoodEntry>,
    profile: UserProfile?,
    onDismiss: () -> Unit,
    onSuggest: (suspend (FoodEntry) -> String)?
) {
    val before = remember(dayEntries) { dayEntries.whatIfTotals() }
    val after = remember(before, entry) { before + entry.whatIfTotals() }
    var loading by remember(entry.id) { mutableStateOf(true) }
    var suggestion by remember(entry.id) { mutableStateOf<String?>(null) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }

    val onboardingFallback = stringResource(R.string.finish_onboarding_hint)
    val suggestionError = stringResource(R.string.error_ai_suggestion)
    val errorContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(entry.id) {
        loading = true
        suggestion = null
        error = null
        runCatching { onSuggest?.invoke(entry) ?: onboardingFallback }
            .onSuccess { suggestion = it.ifBlank { null } }
            .onFailure { error = (it as? com.apoorvdarshan.calorietracker.services.ai.AiError)?.userMessage(errorContext) ?: suggestionError }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                stringResource(R.string.what_if_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    stringResource(R.string.what_if_subtitle),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    lineHeight = 19.sp
                )
                SheetPillCard {
                    WhatIfImpactRow(
                        label = stringResource(R.string.nutrition_label_calories),
                        added = "+${entry.calories} kcal",
                        total = profile?.let { "${after.calories} / ${it.effectiveCalories} kcal" }
                            ?: "${after.calories} kcal"
                    )
                    SheetHairline()
                    WhatIfImpactRow(
                        label = stringResource(R.string.nutrition_label_protein),
                        added = "+${whatIfGrams(entry.protein)}",
                        total = profile?.let { "${whatIfGrams(after.protein)} / ${it.effectiveProtein}g" }
                            ?: whatIfGrams(after.protein)
                    )
                    SheetHairline()
                    WhatIfImpactRow(
                        label = stringResource(R.string.nutrition_label_carbs),
                        added = "+${whatIfGrams(entry.carbs)}",
                        total = profile?.let { "${whatIfGrams(after.carbs)} / ${it.effectiveCarbs}g" }
                            ?: whatIfGrams(after.carbs)
                    )
                    SheetHairline()
                    WhatIfImpactRow(
                        label = stringResource(R.string.nutrition_label_fat),
                        added = "+${whatIfGrams(entry.fat)}",
                        total = profile?.let { "${whatIfGrams(after.fat)} / ${it.effectiveFat}g" }
                            ?: whatIfGrams(after.fat)
                    )
                }

                SheetPillCard {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.what_if_ai_suggestion),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                        )
                        if (loading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = AppColors.Calorie
                                )
                                Text(
                                    stringResource(R.string.what_if_loading),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                                )
                            }
                        } else {
                            Text(
                                suggestion ?: error ?: stringResource(R.string.what_if_no_suggestion),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done), color = AppColors.Calorie)
            }
        }
    )
}

@Composable
private fun WhatIfImpactRow(
    label: String,
    added: String,
    total: String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                added,
                fontSize = 13.sp,
                color = AppColors.Calorie,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            total,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
        )
    }
}

private fun whatIfGrams(value: Double): String = "${MacroValueFormatter.string(value)}g"
