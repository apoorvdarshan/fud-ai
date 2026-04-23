package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import com.apoorvdarshan.calorietracker.models.MealType
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
