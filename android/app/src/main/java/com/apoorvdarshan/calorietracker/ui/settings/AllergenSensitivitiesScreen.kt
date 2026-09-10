package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextField
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlin.math.roundToInt

@Composable
fun AllergenSensitivitiesScreen(
    current: List<String>,
    onSave: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    var allergens by rememberSaveable { mutableStateOf(current) }
    var value by rememberSaveable { mutableStateOf("") }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    fun persist(next: List<String>) {
        allergens = next
        onSave(next)
    }

    fun addCurrentValue() {
        val next = value.trim()
        value = ""
        if (next.isEmpty() || allergens.any { it.equals(next, ignoreCase = true) }) return
        persist(allergens + next)
    }

    fun removeAllergen(allergen: String) {
        persist(allergens.filterNot { it == allergen })
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.nav_settings),
                        color = AppColors.Calorie,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_allergen_sensitivities),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.settings_allergen_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            item {
                FudGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.dp,
                    padding = 0.dp
                ) {
                    Column {
                        if (allergens.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_allergen_empty),
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                fontSize = 16.sp
                            )
                        } else {
                            allergens.forEachIndexed { index, allergen ->
                                key(allergen) {
                                    AllergenSwipeRow(
                                        allergen = allergen,
                                        onRequestDelete = { pendingDelete = allergen }
                                    )
                                }
                                if (index != allergens.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                FudGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.dp,
                    padding = 16.dp
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.settings_allergen_add_section),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                        FudGlassTextField(
                            value = value,
                            onValueChange = { value = it },
                            placeholder = stringResource(R.string.settings_allergen_add_placeholder),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addCurrentValue() })
                        )
                        Text(
                            stringResource(R.string.settings_allergen_add_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
    }
    pendingDelete?.let { allergen ->
        FudGlassDialog(onDismissRequest = { pendingDelete = null }) {
            Text(
                stringResource(R.string.settings_allergen_remove_title),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.settings_allergen_remove_message, allergen),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = {
                    removeAllergen(allergen)
                    pendingDelete = null
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { pendingDelete = null },
                destructive = true
            )
        }
    }
}

/**
 * Home-diary-style trailing swipe: drag past threshold then release to request delete.
 * Avoids Material SwipeToDismissBox's broken confirmValueChange snap-back.
 */
@Composable
private fun AllergenSwipeRow(
    allergen: String,
    onRequestDelete: () -> Unit
) {
    val density = LocalDensity.current
    val deleteTriggerPx = with(density) { 220.dp.toPx() }
    var offsetPx by remember(allergen) { mutableFloatStateOf(0f) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val rowBg = if (isDark) Color(0xFF17171B) else Color(0xFFFAF2EC)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxSwipePx = with(density) { maxWidth.toPx() * 0.72f }
        Box(Modifier.fillMaxWidth()) {
            if (offsetPx < 0f) {
                val widthDp = with(density) { (-offsetPx).toDp() }
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(widthDp)
                            .background(Color(0xFFD32F2F)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (-offsetPx > 24f) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .fillMaxWidth()
                    .background(rowBg)
                    .pointerInput(allergen, maxSwipePx) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                offsetPx = (offsetPx + dragAmount).coerceIn(-maxSwipePx, 0f)
                            },
                            onDragEnd = {
                                val shouldDelete = offsetPx <= -deleteTriggerPx
                                offsetPx = 0f
                                if (shouldDelete) onRequestDelete()
                            },
                            onDragCancel = { offsetPx = 0f }
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    allergen,
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
