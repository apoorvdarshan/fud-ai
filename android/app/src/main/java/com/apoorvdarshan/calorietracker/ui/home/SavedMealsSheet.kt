package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.data.FrequentFoodGroup
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
 *
 * Favorites segment additionally supports:
 *   - swipe-left to remove (mirrors iOS `swipeActions` with destructive role)
 *   - long-press the drag handle and slide vertically to reorder (mirrors iOS
 *     EditButton + .onMove). Drag delta is converted to an index offset using
 *     a fixed row pitch — favorites lists are short so an estimated pitch is
 *     accurate enough.
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

    // Favorites are a reactive Flow now (ordered list of FoodEntry copies),
    // so the UI updates as soon as toggleFavorite/moveFavorite writes back.
    val favorites by container.foodRepository.favorites.collectAsState(initial = emptyList())
    val favKeys by container.foodRepository.favoriteKeys.collectAsState(initial = emptySet())

    // Run the legacy → ordered favorites migration once on mount so existing
    // users see their previous favorites in the new ordered list.
    LaunchedEffect(Unit) { container.foodRepository.migratedFavorites() }

    LaunchedEffect(tab, favKeys) {
        when (tab) {
            SavedTab.RECENTS -> recents = container.foodRepository.recent(50)
            SavedTab.FREQUENT -> frequent = container.foodRepository.frequent()
            SavedTab.FAVORITES -> Unit  // driven by `favorites` Flow above
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
                                imageStore = container.imageStore,
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
                                imageStore = container.imageStore,
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
                        FavoritesReorderableList(
                            favorites = favorites,
                            imageStore = container.imageStore,
                            onTap = { entry -> onRelogEntry(entry); onDismiss() },
                            onRemove = { entry ->
                                scope.launch { container.foodRepository.toggleFavorite(entry) }
                            },
                            onMove = { from, to ->
                                scope.launch { container.foodRepository.moveFavorite(from, to) }
                            }
                        )
                    }
                }
            }
        }
    }
}

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
 * Favorites-only list with swipe-left-to-remove and long-press-drag reorder.
 * Uses a plain Column inside a verticalScroll (not LazyColumn) so the drag
 * handler can apply graphicsLayer.translationY to a single dragged row
 * without LazyColumn re-keying it mid-drag.
 *
 * Reorder works by tracking a per-drag dragOffsetPx, and on release converts
 * the cumulative Y delta into an integer index delta using a fixed row pitch
 * — favorites lists are short (typically < 20) so the approximation is fine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesReorderableList(
    favorites: List<FoodEntry>,
    imageStore: FoodImageStore,
    onTap: (FoodEntry) -> Unit,
    onRemove: (FoodEntry) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val density = LocalDensity.current
    // Approximate row height + 8dp gap = ~88dp. Used for drag → index delta.
    val rowPitchPx = with(density) { 88.dp.toPx() }

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    Column(
        Modifier
            .fillMaxWidth()
            .heightConstraint()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        favorites.forEachIndexed { idx, entry ->
            val isDragged = draggingIndex == idx
            val translation = if (isDragged) dragOffsetPx else 0f
            val swipeState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) {
                        onRemove(entry); true
                    } else false
                }
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = translation
                        if (isDragged) {
                            shadowElevation = 18f
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    }
            ) {
                SwipeToDismissBox(
                    state = swipeState,
                    backgroundContent = { FavoriteRemoveBackground() },
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SavedMealRow(
                        entry = entry,
                        isFavorite = true,
                        subtitle = null,
                        imageStore = imageStore,
                        onClick = { onTap(entry) },
                        trailing = {
                            DragHandle(
                                onDragStart = {
                                    draggingIndex = idx
                                    dragOffsetPx = 0f
                                },
                                onDrag = { dy -> dragOffsetPx += dy },
                                onDragEnd = {
                                    val delta = (dragOffsetPx / rowPitchPx).roundToInt()
                                    val target = (idx + delta).coerceIn(0, favorites.size - 1)
                                    if (target != idx) onMove(idx, target)
                                    draggingIndex = null
                                    dragOffsetPx = 0f
                                },
                                onDragCancel = {
                                    draggingIndex = null
                                    dragOffsetPx = 0f
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteRemoveBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFD32F2F))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Icon(Icons.Filled.Delete, contentDescription = "Remove favorite", tint = Color.White)
    }
}

@Composable
private fun DragHandle(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = "Reorder",
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = Modifier
            .size(28.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
                    }
                )
            }
    )
}

/**
 * Verbatim port of `private struct SavedMealRow` in RecentsView.swift.
 * The optional [trailing] slot replaces the default "+ Log" button — the
 * Favorites tab uses it to inject a drag handle for reordering.
 */
@Composable
private fun SavedMealRow(
    entry: FoodEntry,
    isFavorite: Boolean,
    subtitle: String?,
    imageStore: FoodImageStore,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
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
        Thumbnail(emoji = entry.emoji, imageFilename = entry.imageFilename, imageStore = imageStore)

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

        if (trailing != null) {
            trailing()
        } else {
            Icon(
                Icons.Filled.AddCircle,
                contentDescription = "Log",
                tint = AppColors.Calorie,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 56dp thumb. Prefers the saved food photo (via [imageStore]) over the emoji
 * fallback so logged entries with photos show their actual image — same as
 * iOS RecentsView's `entry.imageData` branch.
 */
@Composable
private fun Thumbnail(emoji: String?, imageFilename: String?, imageStore: FoodImageStore) {
    val shape = RoundedCornerShape(12.dp)
    val bitmap = remember(imageFilename) { imageFilename?.let { imageStore.load(it) } }

    Box(
        Modifier
            .size(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .border(1.dp, AppColors.Calorie.copy(alpha = 0.15f), shape),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape)
            )
            emoji != null -> Text(emoji, fontSize = 28.sp)
            else -> Icon(
                Icons.Filled.Restaurant,
                contentDescription = null,
                tint = AppColors.Calorie,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

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

@Composable
private fun Modifier.heightConstraint(): Modifier = this.height(420.dp)
