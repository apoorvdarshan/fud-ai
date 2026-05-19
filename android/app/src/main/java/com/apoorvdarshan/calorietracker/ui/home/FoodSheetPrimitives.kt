package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

// Shared visual primitives for the food review/edit sheets. Names are
// `Sheet*`-prefixed so they don't collide with the look-alike privates in
// HomeScreen.kt and NutritionDetailSheet.kt.

@Composable
internal fun SheetReviewToolbar(
    title: String,
    primaryLabel: String,
    onCancel: () -> Unit,
    onPrimary: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetToolbarPill("Cancel", onClick = onCancel)
        Spacer(Modifier.weight(1f))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        SheetToolbarPill(primaryLabel, bold = true, onClick = onPrimary)
    }
}

@Composable
private fun SheetToolbarPill(label: String, bold: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = AppColors.Calorie,
            fontSize = 16.sp,
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
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(28.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    val withClick = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(
        withClick.padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
internal fun SheetPillCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    gramUnit: String
) {
    val pickerOptions = ServingUnitOption.pickerOptions(unitOptions)
    val selectedOption = ServingUnitOption.optionMatching(selectedUnitId, unitOptions)
    val parsedQuantity = quantityText.toDoubleOrNull()
    val selectedUnitLabel = selectedOption.displayUnit(parsedQuantity)

    SheetPillCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Quantity", fontSize = 17.sp, modifier = Modifier.padding(end = 8.dp))
            Spacer(Modifier.weight(1f))
            BasicTextField(
                value = quantityText,
                onValueChange = onQuantityChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    textAlign = TextAlign.End
                ),
                cursorBrush = SolidColor(AppColors.Calorie),
                modifier = Modifier.width(80.dp)
            )
            Spacer(Modifier.width(6.dp))
            if (pickerOptions.size > 1) {
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onMenuExpandedChange(true) }
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
                            val optionLabel = option.displayUnit(
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
                    gramUnit,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.width(24.dp)
                )
            }
        }

        if (!selectedOption.isGramUnit) {
            SheetHairline()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Total", fontSize = 17.sp, modifier = Modifier.weight(1f))
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

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = shape,
        containerColor = Color(0xF2141416),
        shadowElevation = 22.dp,
        modifier = sizedModifier
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.045f),
                        Color.White.copy(alpha = 0.015f),
                        AppColors.Calorie.copy(alpha = 0.025f)
                    )
                ),
                shape
            )
            .border(
                0.8.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.18f),
                        Color.White.copy(alpha = 0.055f),
                        AppColors.Calorie.copy(alpha = 0.08f)
                    )
                ),
                shape
            )
            .padding(vertical = 5.dp),
        content = content
    )
}

@Composable
internal fun SheetGlassDropdownMenuItem(
    label: String,
    selected: Boolean = false,
    leadingIcon: ImageVector? = null,
    reserveSelectionSlot: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 7.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
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
                            tint = Color.White,
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
            color = Color.White.copy(alpha = 0.94f),
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
                tint = Color.White,
                modifier = Modifier.size(17.dp)
            )
        }
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
