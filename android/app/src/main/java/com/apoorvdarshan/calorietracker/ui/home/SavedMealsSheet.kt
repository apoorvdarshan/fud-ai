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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.data.FrequentFoodGroup
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class SavedTab { RECENTS, FREQUENT, FAVORITES }

/**
 * Verbatim port of `RecentsView` in
 * ios/calorietracker/Views/RecentsView.swift.
 *
 * Layout:
 *   - "Saved Meals" navigationTitle (Title Case, inline)
 *   - segmented Picker: Recents / Frequent / Favorites (pink-tinted selection)
 *   - per segment: List of `SavedMealRow` (56dp thumb · name + heart · pink kcal +
 *     optional subtitle · 3 macro tag pills · trailing plus.circle.fill log button)
 *   - per-segment empty state: 32sp pink-tinted icon + secondary message text
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedMealsSheet(
    container: AppContainer,
    onDismiss: () -> Unit,
    onRelogEntry: (FoodEntry) -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Restore the last-selected segment from DataStore so reopening the sheet
    // remembers whether the user was on Recents / Frequent / Favorites — same
    // as iOS @AppStorage("lastRecentsSegment") in RecentsView.swift.
    val savedSegment by container.prefs.lastSavedMealsSegment.collectAsState(initial = SavedTab.RECENTS.name)
    var tab by remember(savedSegment) {
        mutableStateOf(
            runCatching { SavedTab.valueOf(savedSegment) }.getOrDefault(SavedTab.RECENTS)
        )
    }
    var recents by remember { mutableStateOf<List<FoodEntry>>(emptyList()) }
    var frequent by remember { mutableStateOf<List<FrequentFoodGroup>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<FoodEntry>>(emptyList()) }
    val favKeys by container.foodRepository.favoriteKeys.collectAsState(initial = emptySet())

    LaunchedEffect(tab, favKeys) {
        when (tab) {
            SavedTab.RECENTS -> recents = container.foodRepository.recent(50)
            SavedTab.FREQUENT -> frequent = container.foodRepository.frequent()
            SavedTab.FAVORITES -> {
                val all = container.foodRepository.entries.first()
                favorites = all.filter { it.favoriteKey in favKeys }.distinctBy { it.favoriteKey }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            // iOS: navigationTitle "Saved Meals", inline display.
            Text(
                "Saved Meals",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            SegmentedTabs(selected = tab, onSelect = { newTab ->
                tab = newTab
                scope.launch { container.prefs.setLastSavedMealsSegment(newTab.name) }
            })
            Spacer(Modifier.height(16.dp))

            when (tab) {
                SavedTab.RECENTS -> {
                    if (recents.isEmpty()) {
                        EmptyState(icon = Icons.Outlined.Schedule, text = "No foods logged yet")
                    } else {
                        SavedList(items = recents) { entry ->
                            SavedMealRow(
                                entry = entry,
                                isFavorite = entry.favoriteKey in favKeys,
                                subtitle = null,
                                onClick = { onRelogEntry(entry); onDismiss() }
                            )
                        }
                    }
                }
                SavedTab.FREQUENT -> {
                    if (frequent.isEmpty()) {
                        EmptyState(icon = Icons.Outlined.Refresh, text = "No foods logged yet")
                    } else {
                        SavedList(items = frequent) { group ->
                            SavedMealRow(
                                entry = group.template,
                                isFavorite = group.template.favoriteKey in favKeys,
                                subtitle = "${group.count}× logged",
                                onClick = { onRelogEntry(group.template); onDismiss() }
                            )
                        }
                    }
                }
                SavedTab.FAVORITES -> {
                    if (favorites.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.Favorite,
                            text = "No favorites yet\nSwipe left on any food to add it"
                        )
                    } else {
                        SavedList(items = favorites) { entry ->
                            SavedMealRow(
                                entry = entry,
                                isFavorite = true,
                                subtitle = null,
                                onClick = { onRelogEntry(entry); onDismiss() }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * iOS `.pickerStyle(.segmented)` rendered as a 3-segment pill. Selected segment
 * uses the calorie pink gradient with white text; others stay transparent on a
 * gray track.
 */
@Composable
private fun SegmentedTabs(selected: SavedTab, onSelect: (SavedTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            .padding(2.dp)
    ) {
        for (t in SavedTab.values()) {
            val isSel = t == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSel) Brush.linearGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd))
                        else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                    )
                    .clickable { onSelect(t) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when (t) {
                        SavedTab.RECENTS -> "Recents"
                        SavedTab.FREQUENT -> "Frequent"
                        SavedTab.FAVORITES -> "Favorites"
                    },
                    color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun <T> SavedList(items: List<T>, row: @Composable (T) -> Unit) {
    LazyColumn(
        Modifier.fillMaxWidth().heightConstraint(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items) { row(it) }
    }
}

/**
 * Verbatim port of `private struct SavedMealRow` in RecentsView.swift.
 *
 *   HStack(spacing: 12) {
 *     56x56 thumbnail (image -> emoji -> fork.knife fallback, RoundedRectangle 12)
 *     VStack(spacing: 3) {
 *       HStack { name (body rounded medium) ; heart.fill if favorite }
 *       HStack { "{cal} kcal" subhead semibold PINK ; optional "· {subtitle}" }
 *       HStack(spacing: 8) { MacroTag P / C / F }
 *     }
 *     Spacer
 *     plus.circle.fill PINK title3 (Log button)
 *   }
 */
@Composable
private fun SavedMealRow(
    entry: FoodEntry,
    isFavorite: Boolean,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Thumbnail(emoji = entry.emoji)

        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    entry.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                if (isFavorite) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "${entry.calories} kcal",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Calorie
                )
                if (subtitle != null) {
                    Text("·", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MacroTag("P", entry.protein.toInt())
                MacroTag("C", entry.carbs.toInt())
                MacroTag("F", entry.fat.toInt())
            }
        }

        Icon(
            Icons.Filled.AddCircle,
            contentDescription = "Log",
            tint = AppColors.Calorie,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun Thumbnail(emoji: String?) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .border(1.dp, AppColors.Calorie.copy(alpha = 0.15f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (emoji != null) {
            Text(emoji, fontSize = 28.sp)
        } else {
            Icon(
                Icons.Filled.Restaurant,
                contentDescription = null,
                tint = AppColors.Calorie,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** iOS `private struct MacroTag` — pink-tinted capsule. */
@Composable
private fun MacroTag(label: String, value: Int) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(AppColors.Calorie.copy(alpha = 0.08f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            "$label ${value}g",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/** iOS `emptySection` — 32sp pink-tinted icon above the message text. */
@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Box(
        Modifier.fillMaxWidth().heightConstraint(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = AppColors.Calorie.copy(alpha = 0.4f),
                modifier = Modifier.size(32.dp)
            )
            Text(
                text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** Bounded height so the sheet stays half-screen, matching the iOS list height. */
@Composable
private fun Modifier.heightConstraint(): Modifier = this.height(420.dp)
