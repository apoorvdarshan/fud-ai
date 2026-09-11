package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.ServingAmountExpression
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import com.apoorvdarshan.calorietracker.ui.components.DateWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextField
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.ui.util.clockTimePattern
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Shared visual primitives for the food review/edit sheets. Names are
// `Sheet*`-prefixed so they don't collide with the look-alike privates in
// HomeScreen.kt and NutritionDetailSheet.kt.

@Composable
internal fun SheetReviewToolbar(
    title: String,
    primaryLabel: String,
    secondaryLabel: String? = null,
    primaryEnabled: Boolean = true,
    onCancel: () -> Unit,
    onPrimary: () -> Unit,
    onSecondary: (() -> Unit)? = null
) {
    val compact = LocalConfiguration.current.screenWidthDp < 380
    val outerPadding = if (compact) 8.dp else 14.dp
    val itemGap = if (compact) 6.dp else 8.dp
    Row(
        Modifier.fillMaxWidth().padding(horizontal = outerPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetToolbarPill(stringResource(R.string.action_cancel), compact = compact, onClick = onCancel)
        Spacer(Modifier.width(itemGap))
        Text(
            title,
            fontSize = if (compact) 16.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(itemGap))
        if (secondaryLabel != null && onSecondary != null) {
            SheetToolbarPill(secondaryLabel, compact = compact, onClick = onSecondary)
            Spacer(Modifier.width(itemGap))
        }
        SheetToolbarPill(
            primaryLabel,
            bold = true,
            compact = compact,
            enabled = primaryEnabled,
            onClick = onPrimary
        )
    }
}

@Composable
private fun SheetToolbarPill(
    label: String,
    bold: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = CircleShape
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val horizontalPadding = when {
        compact && bold -> 12.dp
        compact -> 10.dp
        else -> 16.dp
    }
    val modifier = if (bold) {
        Modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd)))
    } else {
        Modifier
            .clip(shape)
            .background(if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f) else Color(0xFFEDE3DD).copy(alpha = 0.82f))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isDark) 0.08f else 0.24f),
                        Color.White.copy(alpha = if (isDark) 0.02f else 0.06f)
                    )
                )
            )
            .border(
                0.7.dp,
                Color.White.copy(alpha = if (isDark) 0.10f else 0.48f),
                shape
            )
    }
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (bold) Color.White else AppColors.Calorie,
            fontSize = if (compact) 15.sp else 16.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
internal fun SheetSectionHeader(title: String) {
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
internal fun SheetPillRow(
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val rowFill = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    } else {
        Color(0xFFE9DCD5).copy(alpha = 0.82f)
    }
    val rowSheen = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.075f else 0.18f),
            Color.White.copy(alpha = if (isDark) 0.018f else 0.04f),
            AppColors.Calorie.copy(alpha = if (isDark) 0.022f else 0.060f)
        )
    )
    val rowBorder = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.14f else 0.50f),
            AppColors.Calorie.copy(alpha = if (isDark) 0.07f else 0.18f)
        )
    )
    val base = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(rowFill)
        .background(rowSheen)
        .border(0.7.dp, rowBorder, shape)
    val withClick = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(
        withClick.padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
internal fun SheetPillCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardFill = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    } else {
        Color(0xFFE9DCD5).copy(alpha = 0.82f)
    }
    val cardSheen = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.075f else 0.18f),
            Color.White.copy(alpha = if (isDark) 0.018f else 0.04f),
            AppColors.Calorie.copy(alpha = if (isDark) 0.022f else 0.060f)
        )
    )
    val cardBorder = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.14f else 0.50f),
            AppColors.Calorie.copy(alpha = if (isDark) 0.07f else 0.18f)
        )
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardFill)
            .background(cardSheen)
            .border(0.7.dp, cardBorder, shape)
            .padding(vertical = 4.dp),
        content = content
    )
}

@Composable
internal fun ServingQuantityCard(
    quantityText: String,
    onQuantityChange: (String) -> Unit,
    selectedUnitId: String,
    onSelectedUnitChange: (String) -> Unit,
    servingSizeGrams: Double,
    unitOptions: List<ServingUnitOption>,
    allowGramUnit: Boolean = true,
    showGramTotal: Boolean = true,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    gramUnit: String
) {
    val allPickerOptions = ServingUnitOption.pickerOptions(unitOptions)
    val pickerOptions = if (allowGramUnit) allPickerOptions else allPickerOptions.filterNot { it.isGramUnit }
    val selectedOption = pickerOptions.firstOrNull { it.id == selectedUnitId }
        ?: pickerOptions.firstOrNull()
        ?: ServingUnitOption.grams
    val parsedQuantity = ServingAmountExpression.evaluate(quantityText)
    val servingLabel = stringResource(R.string.sheet_serving)
    fun displayUnit(option: ServingUnitOption, quantity: Double?): String =
        if (option.normalizedUnit == "serving") servingLabel else option.displayUnit(quantity)
    val selectedUnitLabel = displayUnit(selectedOption, parsedQuantity)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboard = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val focusRequester = remember { FocusRequester() }
    var quantityFieldValue by remember {
        mutableStateOf(TextFieldValue(quantityText, selection = TextRange(quantityText.length)))
    }
    var quantityFocused by remember { mutableStateOf(false) }

    fun updateQuantity(newValue: String) {
        quantityFieldValue = TextFieldValue(newValue, selection = TextRange(newValue.length))
        onQuantityChange(newValue)
    }

    fun finalizeQuantity() {
        val trimmed = quantityFieldValue.text.trim()
        if (trimmed.isEmpty()) return
        val result = ServingAmountExpression.evaluate(trimmed)
        val finalValue = if (result != null && result > 0) {
            ServingUnitOption.formatQuantity(result)
        } else {
            val quantity = if (selectedOption.gramsPerUnit > 0) {
                servingSizeGrams / selectedOption.gramsPerUnit
            } else {
                servingSizeGrams
            }
            ServingUnitOption.formatQuantity(quantity)
        }
        updateQuantity(finalValue)
    }

    LaunchedEffect(quantityText) {
        if (quantityText != quantityFieldValue.text) {
            quantityFieldValue = TextFieldValue(
                text = quantityText,
                selection = TextRange(quantityText.length)
            )
        }
    }

    SheetPillCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.sheet_quantity),
                fontSize = 17.sp,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clickable { dismissKeyboard() }
            )
            Spacer(
                Modifier
                    .weight(1f)
                    .clickable { dismissKeyboard() }
            )
            BasicTextField(
                value = quantityFieldValue,
                onValueChange = { newValue ->
                    updateQuantity(newValue.text)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    textAlign = TextAlign.End
                ),
                cursorBrush = SolidColor(AppColors.Calorie),
                modifier = Modifier
                    .widthIn(min = 80.dp, max = 112.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (quantityFocused && !state.isFocused) {
                            finalizeQuantity()
                        }
                        quantityFocused = state.isFocused
                    }
            )
            if (quantityText.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Cancel,
                    contentDescription = stringResource(R.string.cd_clear_quantity),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable {
                            updateQuantity("")
                            focusRequester.requestFocus()
                        }
                )
            }
            Spacer(Modifier.width(6.dp))
            if (pickerOptions.size > 1) {
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                dismissKeyboard()
                                onMenuExpandedChange(true)
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            selectedUnitLabel,
                            fontSize = 17.sp,
                            color = AppColors.Calorie,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(min = 32.dp, max = 88.dp)
                        )
                        Icon(
                            Icons.Filled.UnfoldMore,
                            contentDescription = null,
                            tint = AppColors.Calorie
                        )
                    }
                    SheetGlassDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { onMenuExpandedChange(false) },
                        menuWidth = 150.dp
                    ) {
                        for (option in pickerOptions) {
                            val optionLabel = displayUnit(
                                option,
                                if (option.id == selectedUnitId) parsedQuantity else null
                            )
                            SheetGlassDropdownMenuItem(
                                label = optionLabel,
                                selected = option.id == selectedUnitId,
                                reserveSelectionSlot = true,
                                onClick = {
                                    onSelectedUnitChange(option.id)
                                    onMenuExpandedChange(false)
                                }
                            )
                        }
                    }
                }
            } else {
                Text(
                    selectedUnitLabel,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .widthIn(min = 24.dp, max = 88.dp)
                        .clickable { dismissKeyboard() }
                )
            }
        }

        if (quantityFocused) {
            val liveResult = ServingAmountExpression.evaluate(quantityFieldValue.text)
                ?.takeIf { it > 0 && ServingAmountExpression.containsOperation(quantityFieldValue.text) }
            SheetHairline()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SheetCalculatorKey("C") {
                    updateQuantity("")
                    focusRequester.requestFocus()
                }
                for (operation in listOf('+', '−', '×', '÷')) {
                    SheetCalculatorKey(operation.toString()) {
                        updateQuantity(
                            ServingAmountExpression.appendOperator(quantityFieldValue.text, operation)
                        )
                        focusRequester.requestFocus()
                    }
                }
                val equalsLabel = liveResult
                    ?.let { "= ${ServingUnitOption.formatQuantity(it)}" }
                    ?: "="
                SheetCalculatorKey(equalsLabel, emphasized = true) {
                    ServingAmountExpression.evaluate(quantityFieldValue.text)
                        ?.takeIf { it > 0 }
                        ?.let { updateQuantity(ServingUnitOption.formatQuantity(it)) }
                    focusRequester.requestFocus()
                }
            }
        }

        if (showGramTotal && !selectedOption.isGramUnit) {
            SheetHairline()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.label_total), fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text(
                    "~${sheetFormatGrams(servingSizeGrams)} $gramUnit",
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
private fun RowScope.SheetCalculatorKey(
    label: String,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .height(36.dp)
            .clip(shape)
            .background(
                if (emphasized) AppColors.Calorie
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = if (emphasized) Color.White else MaterialTheme.colorScheme.onSurface,
            fontSize = when {
                label.length > 7 -> 12.sp
                label.length > 4 -> 14.sp
                else -> 17.sp
            },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
internal fun SheetNutritionRow(label: String, value: String, unit: String, dim: Boolean = false) {
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
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
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
internal fun SheetHairline() {
    Box(
        Modifier
            .padding(start = 18.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    )
}

@Composable
internal fun SheetGlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    menuWidth: Dp? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    val sizedModifier = if (menuWidth != null) modifier.width(menuWidth) else modifier
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val menuContainer = if (isDark) Color(0xF2141416) else Color(0xFFFAF3EE).copy(alpha = 0.98f)
    val menuSheen = Brush.verticalGradient(
        colors = if (isDark) {
            listOf(
                Color.White.copy(alpha = 0.045f),
                Color.White.copy(alpha = 0.015f),
                AppColors.Calorie.copy(alpha = 0.025f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.70f),
                Color.White.copy(alpha = 0.24f),
                AppColors.Calorie.copy(alpha = 0.040f)
            )
        }
    )
    val menuBorder = Brush.linearGradient(
        colors = if (isDark) {
            listOf(
                Color.White.copy(alpha = 0.18f),
                Color.White.copy(alpha = 0.055f),
                AppColors.Calorie.copy(alpha = 0.08f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.95f),
                Color.White.copy(alpha = 0.40f),
                AppColors.Calorie.copy(alpha = 0.14f)
            )
        }
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = shape,
        containerColor = menuContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = sizedModifier
            .background(menuSheen, shape)
            .border(0.8.dp, menuBorder, shape)
            .padding(vertical = 5.dp),
        content = content
    )
}

@Composable
internal fun SheetGlassDropdownMenuItem(
    label: String,
    selected: Boolean = false,
    leadingIcon: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingIcon: ImageVector? = null,
    reserveSelectionSlot: Boolean = false,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val checkTint = if (isDark) Color.White else AppColors.Calorie
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 7.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            // ~48dp tap target per row (Material menu guidance), matching the
            // roomier iOS add-menu rows instead of the old cramped ~36dp.
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            leadingContent != null -> {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    leadingContent()
                }
                Spacer(Modifier.width(10.dp))
            }
            leadingIcon != null -> {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = AppColors.Calorie,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            reserveSelectionSlot -> {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = checkTint,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
        }

        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.94f),
            lineHeight = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (selected && leadingIcon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = checkTint,
                modifier = Modifier.size(17.dp)
            )
        } else if (trailingIcon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                trailingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Date/time card shared by the review and edit sheets. */
@Composable
internal fun SheetDateTimeCard(
    loggedDate: LocalDate,
    loggedTime: LocalTime,
    onEditDate: () -> Unit,
    onEditTime: () -> Unit
) {
    val context = LocalContext.current
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US) }
    val timeFormatter = remember(context) { DateTimeFormatter.ofPattern(clockTimePattern(context), Locale.US) }
    SheetPillCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEditDate)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.label_date), fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(
                loggedDate.format(dateFormatter),
                fontSize = 17.sp,
                color = AppColors.Calorie,
                fontWeight = FontWeight.Medium
            )
        }
        SheetHairline()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEditTime)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.label_time), fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(
                loggedTime.format(timeFormatter),
                fontSize = 17.sp,
                color = AppColors.Calorie,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** Wheel-style logged-date picker dialog shared by the review and edit sheets. */
@Composable
internal fun SheetDatePickerDialog(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var pickedDate by remember(initialDate) { mutableStateOf(initialDate) }
    FudGlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.label_date), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        DateWheelPicker(
            selected = pickedDate,
            onSelect = { pickedDate = it },
            minYear = minOf(LocalDate.now().year - 10, initialDate.year),
            maxYear = maxOf(LocalDate.now().year, initialDate.year),
            modifier = Modifier.fillMaxWidth()
        )
        FudGlassDialogActions(
            primaryText = stringResource(R.string.action_done),
            onPrimary = { onConfirm(pickedDate) },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss
        )
    }
}

/** Hour/minute logged-time entry dialog shared by the review and edit sheets. */
@Composable
internal fun SheetTimePickerDialog(
    initialTime: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    var hourText by remember(initialTime) { mutableStateOf(initialTime.hour.toString().padStart(2, '0')) }
    var minuteText by remember(initialTime) { mutableStateOf(initialTime.minute.toString().padStart(2, '0')) }

    FudGlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.label_time), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FudGlassTextField(
                value = hourText,
                onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                placeholder = stringResource(R.string.placeholder_hour),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            FudGlassTextField(
                value = minuteText,
                onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                placeholder = stringResource(R.string.placeholder_minute),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        FudGlassDialogActions(
            primaryText = stringResource(R.string.action_done),
            onPrimary = {
                val hour = hourText.toIntOrNull()?.coerceIn(0, 23) ?: initialTime.hour
                val minute = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: initialTime.minute
                onConfirm(LocalTime.of(hour, minute))
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss
        )
    }
}

internal fun sheetMealIcon(meal: MealType): ImageVector = when (meal) {
    MealType.BREAKFAST -> Icons.Filled.WbTwilight
    MealType.LUNCH -> Icons.Filled.WbSunny
    MealType.DINNER -> Icons.Filled.Bedtime
    MealType.SNACK -> Icons.Filled.LocalCafe
    MealType.OTHER -> Icons.Filled.Restaurant
}

internal fun sheetFormatGrams(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString()
    else String.format("%.1f", value)
