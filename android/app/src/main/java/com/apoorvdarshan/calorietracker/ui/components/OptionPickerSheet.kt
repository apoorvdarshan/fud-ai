package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

/**
 * Polished selection bottom sheet shared by Settings and Onboarding so the two stay
 * pixel-identical: rounded top corners, the app's surface tint, rounded-card option rows
 * with a glass sheen + accent border, and an accent checkmark on the selected item.
 * Optionally accepts a free-form custom value (e.g. a custom model ID).
 *
 * Mirrors the styling of SettingsScreen's internal ListSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> OptionPickerSheet(
    title: String,
    items: List<T>,
    label: @Composable (T) -> String,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: (@Composable (T) -> String?)? = null,
    footer: String? = null,
    customPlaceholder: String? = null,
    onCustomSubmit: ((String) -> Unit)? = null
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = if (isDark) Color(0xF2141416) else Color(0xFFFAF3EE)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items) { item ->
                    OptionPickerRow(
                        label = label(item),
                        subtitle = subtitle?.invoke(item),
                        isSelected = selected(item),
                        isDark = isDark,
                        onClick = { onSelect(item) }
                    )
                }
            }
            if (onCustomSubmit != null) {
                footer?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                var custom by remember { mutableStateOf("") }
                Spacer(Modifier.height(8.dp))
                FudGlassTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    placeholder = customPlaceholder.orEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                FudGlassPrimaryButton(
                    text = stringResource(R.string.action_save),
                    onClick = { if (custom.isNotBlank()) onCustomSubmit(custom.trim()) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun OptionPickerRow(
    label: String,
    subtitle: String?,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isSelected) AppColors.Calorie.copy(alpha = 0.13f)
                else if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                else Color(0xFFEDE3DD).copy(alpha = 0.76f)
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isDark) 0.08f else 0.18f),
                        Color.White.copy(alpha = if (isDark) 0.02f else 0.04f),
                        AppColors.Calorie.copy(alpha = if (isSelected) 0.065f else if (isDark) 0.025f else 0.050f)
                    )
                )
            )
            .border(
                0.7.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (isDark) 0.16f else 0.46f),
                        AppColors.Calorie.copy(alpha = if (isSelected) 0.22f else if (isDark) 0.08f else 0.16f)
                    )
                ),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.sheet_selected_a11y),
                tint = AppColors.Calorie,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
