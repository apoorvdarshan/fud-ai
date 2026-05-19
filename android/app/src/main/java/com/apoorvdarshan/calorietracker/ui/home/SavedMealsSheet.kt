package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.res.stringResource
import com.apoorvdarshan.calorietracker.R
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.data.FrequentFoodGroup
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
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
 *
 * Favorites segment additionally supports:
 *   - swipe-left to unfavorite
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

    // Per-tab search query — substring + case-insensitive match against
    // entry.name (or group.template.name for Frequent). Resets when the user
    // switches segments since the same word almost never matches across all
    // three contexts and the empty list reads as "your data vanished".
    var searchQuery by remember(tab) { mutableStateOf("") }
    val isSearching = searchQuery.isNotBlank()
    val filteredRecents = remember(recents, searchQuery) {
        if (searchQuery.isBlank()) recents
        else recents.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }
    val filteredFrequent = remember(frequent, searchQuery) {
        if (searchQuery.isBlank()) frequent
        else frequent.filter { it.template.name.contains(searchQuery.trim(), ignoreCase = true) }
    }
    val filteredFavorites = remember(favorites, searchQuery) {
        if (searchQuery.isBlank()) favorites
        else favorites.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

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
            Spacer(Modifier.height(12.dp))

            // Search field — filters whichever tab is active. Substring match,
            // case-insensitive. Reset on tab switch via remember(tab) above.
            androidx.compose.material3.OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.saved_meals_search_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = if (isSearching) {
                    {
                        androidx.compose.material3.IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                        }
                    }
                } else null,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(16.dp))

            when (tab) {
                SavedTab.RECENTS -> {
                    if (filteredRecents.isEmpty()) {
                        val msg = if (isSearching) stringResource(R.string.saved_meals_no_match)
                                  else "No foods logged yet"
                        EmptyState(icon = if (isSearching) Icons.Outlined.Search else Icons.Outlined.Schedule, text = msg)
                    } else {
                        SavedList(items = filteredRecents) { entry ->
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
                    if (filteredFrequent.isEmpty()) {
                        val msg = if (isSearching) stringResource(R.string.saved_meals_no_match)
                                  else "No foods logged yet"
                        EmptyState(icon = if (isSearching) Icons.Outlined.Search else Icons.Outlined.Refresh, text = msg)
                    } else {
                        SavedList(items = filteredFrequent) { group ->
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
                    } else if (filteredFavorites.isEmpty()) {
                        EmptyState(icon = Icons.Outlined.Search, text = stringResource(R.string.saved_meals_no_match))
                    } else if (isSearching) {
                        // Drag-to-reorder is hidden during search since the
                        // filtered indices don't map back to the unfiltered
                        // favorites array — letting reorder run on a filtered
                        // list would silently swap the wrong items.
                        SavedList(items = filteredFavorites) { entry ->
                            SavedMealRow(
                                entry = entry,
                                isFavorite = true,
                                subtitle = null,
                                imageStore = container.imageStore,
                                onClick = { onRelogEntry(entry); onDismiss() }
                            )
                        }
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
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightConstraint()
            .blockSheetDragAtLazyListEdges(listState),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items) { row(it) }
    }
}

/**
 * Favorites-only list with swipe-left-to-unfavorite and tap-based ↑/↓ reorder.
 *
 * The original drag-to-reorder using long-press + pointerInput was unreliable
 * because the favorites list lives inside a ModalBottomSheet (vertical drag
 * to dismiss) AND a verticalScroll Column — both compete for vertical pointer
 * events and would intermittently steal the drag. The native Android pattern
 * for manual list ordering (used by system Settings for default-app priority,
 * accessibility shortcut order, etc.) is per-row up/down arrow buttons; we
 * use that here.
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
    val scrollState = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .heightConstraint()
            .blockSheetDragAtScrollEdges(scrollState)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        favorites.forEachIndexed { idx, entry ->
            key(entry.favoriteKey) {
                val swipeState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            onRemove(entry)
                        }
                        false
                    }
                )

                SwipeToDismissBox(
                    state = swipeState,
                    backgroundContent = { FavoriteUnfavoriteBackground(swipeState) },
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
                            MoveButtons(
                                canMoveUp = idx > 0,
                                canMoveDown = idx < favorites.size - 1,
                                onMoveUp = { onMove(idx, idx - 1) },
                                onMoveDown = { onMove(idx, idx + 1) }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.blockSheetDragAtLazyListEdges(listState: LazyListState): Modifier {
    val connection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val shouldBlock =
                    (available.y > 0f && !listState.canScrollBackward) ||
                    (available.y < 0f && !listState.canScrollForward)
                return if (shouldBlock) Offset(0f, available.y) else Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val shouldBlock =
                    (available.y > 0f && !listState.canScrollBackward) ||
                    (available.y < 0f && !listState.canScrollForward)
                return if (shouldBlock) Velocity(0f, available.y) else Velocity.Zero
            }
        }
    }
    return nestedScroll(connection)
}

@Composable
private fun Modifier.blockSheetDragAtScrollEdges(scrollState: ScrollState): Modifier {
    val connection = remember(scrollState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val shouldBlock =
                    (available.y > 0f && scrollState.value <= 0) ||
                    (available.y < 0f && scrollState.value >= scrollState.maxValue)
                return if (shouldBlock) Offset(0f, available.y) else Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val shouldBlock =
                    (available.y > 0f && scrollState.value <= 0) ||
                    (available.y < 0f && scrollState.value >= scrollState.maxValue)
                return if (shouldBlock) Velocity(0f, available.y) else Velocity.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/**
 * Native Android pattern for manual list reorder — small ↑/↓ arrow buttons
 * stacked vertically. The arrow at the boundary (top row's ↑, bottom row's ↓)
 * is dimmed and non-clickable.
 */
@Composable
private fun MoveButtons(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier
                .size(width = 32.dp, height = 28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (canMoveUp) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    else Color.Transparent
                )
                .clickable(enabled = canMoveUp, onClick = onMoveUp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "Move up",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canMoveUp) 0.75f else 0.18f),
                modifier = Modifier.size(20.dp)
            )
        }
        Box(
            Modifier
                .size(width = 32.dp, height = 28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (canMoveDown) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    else Color.Transparent
                )
                .clickable(enabled = canMoveDown, onClick = onMoveDown),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Move down",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canMoveDown) 0.75f else 0.18f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * iOS Mail-style trailing reveal: the Unfavorite panel is pinned to the
 * right edge and its width tracks the swipe distance, so only the area
 * that's been "revealed" by the foreground sliding left is tinted — the
 * still-visible portion of the row stays its normal color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoriteUnfavoriteBackground(state: SwipeToDismissBoxState) {
    val active = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
    val rawOffset = runCatching { state.requireOffset() }.getOrDefault(0f)
    // For EndToStart the offset is negative — its absolute value is the
    // amount the foreground has moved left, which is exactly how wide the
    // red reveal panel should be.
    val revealWidthPx = if (active) (-rawOffset).coerceAtLeast(0f) else 0f
    val revealWidthDp = with(LocalDensity.current) { revealWidthPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(revealWidthDp)
                .background(AppColors.Calorie),
            contentAlignment = Alignment.Center
        ) {
            if (active && revealWidthPx > 24f) {
                Icon(Icons.Outlined.Favorite, contentDescription = "Unfavorite", tint = Color.White)
            }
        }
    }
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
    val bitmap = remember(imageFilename) { imageFilename?.let { imageStore.loadThumbnail(it) } }

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
