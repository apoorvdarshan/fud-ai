@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apoorvdarshan.calorietracker.ui.workouts

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.scrollableArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.data.ExerciseItem
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.data.ExerciseSort
import com.apoorvdarshan.calorietracker.data.ExerciseVisual
import com.apoorvdarshan.calorietracker.models.Gender
import com.apoorvdarshan.calorietracker.models.WorkoutSplitGroup
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextButton
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PICKER_SEARCH_DEBOUNCE_MS = 175L
private const val PICKER_FILTER_PERSIST_MS = 400L

internal enum class WorkoutPickerSource {
    DATASET,
    SAVED
}

internal data class WorkoutPickerRequest(
    val title: String,
    val muscles: Set<String>,
    val initialSource: WorkoutPickerSource
) {
    val contextId: String
        get() = buildString {
            append(title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-"))
            if (muscles.isNotEmpty()) append(":" + muscles.sorted().joinToString("|"))
        }

    val isSavedContext: Boolean
        get() = initialSource == WorkoutPickerSource.SAVED && title == "Saved exercises"

    companion object {
        fun all() = WorkoutPickerRequest("All exercises", emptySet(), WorkoutPickerSource.DATASET)
        fun saved() = WorkoutPickerRequest("Saved exercises", emptySet(), WorkoutPickerSource.SAVED)
        fun forSplit(title: String, groups: List<WorkoutSplitGroup>) = WorkoutPickerRequest(
            title = title,
            muscles = groups.flatMapTo(mutableSetOf()) { it.muscles },
            initialSource = WorkoutPickerSource.DATASET
        )
        fun group(group: WorkoutSplitGroup) = WorkoutPickerRequest(
            title = group.title,
            muscles = group.muscles,
            initialSource = WorkoutPickerSource.DATASET
        )
    }
}

@Composable
internal fun WorkoutPickerSheet(
    request: WorkoutPickerRequest,
    repository: ExerciseRepository,
    selectedExerciseIds: Set<String>,
    savedExerciseIds: Set<String>,
    initialSource: WorkoutPickerSource,
    initialFilterState: WorkoutPickerFilterState,
    visualGender: Gender,
    preferredEquipment: Set<String>,
    hidePrimaryFilter: Boolean,
    onSourceChange: (WorkoutPickerSource) -> Unit,
    onFilterStateChange: (WorkoutPickerFilterState) -> Unit,
    onToggleExercise: (ExerciseItem) -> Unit,
    onToggleSaved: (String) -> Unit,
    onCreateExercise: (() -> Unit)? = null,
    onEditUserExercise: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var source by remember(request.contextId, initialSource) {
        mutableStateOf(if (request.isSavedContext) WorkoutPickerSource.SAVED else initialSource)
    }
    var filter by remember(request.contextId) { mutableStateOf(initialFilterState) }
    var debouncedSearch by remember(request.contextId) { mutableStateOf(initialFilterState.search) }
    var previewItem by remember(request.contextId) { mutableStateOf<ExerciseItem?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val view = LocalView.current
    val inputMethodManager = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    val listState = rememberLazyListState()
    val sheetFocusRequester = remember { FocusRequester() }

    fun dismissKeyboard() {
        focus.clearFocus(force = true)
        sheetFocusRequester.requestFocus()
        keyboard?.hide()
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }
    // The modal sheet owns the pointer chain before its nested LazyColumn does.
    // Drive that list from the sheet-level scroll surface so the IME is dismissed
    // on the same drag without losing the list's normal direction or fling.
    val pickerScrollState = rememberScrollableState { delta ->
        view.post(::dismissKeyboard)
        listState.dispatchRawDelta(delta)
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) dismissKeyboard()
    }

    fun updateFilter(transform: (WorkoutPickerFilterState) -> WorkoutPickerFilterState) {
        filter = transform(filter)
    }

    LaunchedEffect(filter.search) {
        delay(PICKER_SEARCH_DEBOUNCE_MS)
        debouncedSearch = filter.search
    }

    LaunchedEffect(filter) {
        delay(PICKER_FILTER_PERSIST_MS)
        onFilterStateChange(filter)
    }

    DisposableEffect(request.contextId) {
        onDispose { onFilterStateChange(filter) }
    }

    val items = remember(
        repository,
        source,
        debouncedSearch,
        filter.level,
        filter.primaryMuscle,
        filter.secondaryMuscle,
        filter.equipment,
        filter.force,
        filter.mechanic,
        filter.category,
        filter.sort,
        savedExerciseIds,
        preferredEquipment,
        request.muscles
    ) {
        repository.filtered(
            levels = filter.level?.let(::setOf).orEmpty(),
            equipment = filter.equipment?.let(::setOf) ?: preferredEquipment,
            primaryMuscles = filter.primaryMuscle?.let(::setOf).orEmpty(),
            secondaryMuscles = filter.secondaryMuscle?.let(::setOf).orEmpty(),
            forces = filter.force?.let(::setOf).orEmpty(),
            mechanics = filter.mechanic?.let(::setOf).orEmpty(),
            categories = filter.category?.let(::setOf).orEmpty(),
            sort = filter.sort,
            searchText = debouncedSearch
        ).filter { item ->
            val matchesSource = source == WorkoutPickerSource.DATASET || item.id in savedExerciseIds
            val matchesContext = request.muscles.isEmpty() ||
                item.primaryMuscles.any(request.muscles::contains) ||
                item.secondaryMuscles.any(request.muscles::contains)
            matchesSource && matchesContext
        }
    }
    val muscleOptions = remember(repository, request.muscles) {
        if (request.muscles.isEmpty()) repository.availablePrimaryMuscles
        else repository.availablePrimaryMuscles.filter(request.muscles::contains)
    }
    val equipmentOptions = remember(repository, preferredEquipment) {
        if (preferredEquipment.isEmpty()) repository.availableEquipment
        else repository.availableEquipment.filter(preferredEquipment::contains)
    }
    val hasActiveFilters = filter.search.isNotEmpty() || filter.primaryMuscle != null ||
        filter.secondaryMuscle != null || filter.equipment != null || filter.level != null ||
        filter.force != null || filter.mechanic != null || filter.category != null ||
        filter.sort != ExerciseSort.NAME

    LaunchedEffect(request.contextId, hidePrimaryFilter, muscleOptions, equipmentOptions) {
        val normalized = filter.copy(
            primaryMuscle = filter.primaryMuscle?.takeIf { !hidePrimaryFilter && it in muscleOptions },
            equipment = filter.equipment?.takeIf(equipmentOptions::contains)
        )
        if (normalized != filter) updateFilter { normalized }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (previewItem != null) previewItem = null else onDismiss()
        },
        sheetState = sheetState,
        sheetGesturesEnabled = previewItem == null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = previewItem == null,
            shouldDismissOnClickOutside = previewItem == null
        )
    ) {
        val preview = previewItem
        BackHandler(enabled = preview != null) { previewItem = null }
        if (preview != null) {
            ExercisePickerPreview(
                item = preview,
                visual = repository.visualFor(preview, visualGender),
                isSelected = preview.id in selectedExerciseIds,
                onToggle = { onToggleExercise(preview) },
                onEdit = if (com.apoorvdarshan.calorietracker.models.UserExercise.isUserExercise(preview.id)) {
                    {
                        previewItem = null
                        onEditUserExercise?.invoke(preview.id)
                    }
                } else {
                    null
                },
                onBack = { previewItem = null }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .background(workoutsColors().background)
                    .focusRequester(sheetFocusRequester)
                    .focusable()
            ) {
            PickerHeader(
                title = request.title,
                count = items.size,
                showCreate = source == WorkoutPickerSource.DATASET,
                onCreate = onCreateExercise,
                onDismiss = onDismiss
            )

            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!request.isSavedContext) {
                    PickerSourceControl(
                        source = source,
                        onSelect = {
                            focus.clearFocus()
                            source = it
                            onSourceChange(it)
                        }
                    )
                }

                SearchPill(
                    value = filter.search,
                    onValueChange = { value -> updateFilter { it.copy(search = value) } }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!hidePrimaryFilter) {
                        FilterPill(
                            title = "Primary",
                            icon = Icons.Filled.GpsFixed,
                            selected = filter.primaryMuscle?.let(::setOf).orEmpty(),
                            emptyDisplay = "All ${muscleOptions.size}",
                            options = muscleOptions,
                            glyphFor = { muscleGlyphAsset(it) },
                            onSelect = { selected -> updateFilter { it.copy(primaryMuscle = selected.firstOrNull()) } }
                        )
                    }
                    FilterPill(
                        title = "Secondary",
                        icon = Icons.Filled.GpsFixed,
                        selected = filter.secondaryMuscle?.let(::setOf).orEmpty(),
                        emptyDisplay = "All",
                        options = repository.availableSecondaryMuscles,
                        glyphFor = { muscleGlyphAsset(it) },
                        onSelect = { selected -> updateFilter { it.copy(secondaryMuscle = selected.firstOrNull()) } }
                    )
                    FilterPill(
                        title = "Equipment",
                        icon = Icons.Filled.FitnessCenter,
                        selected = filter.equipment?.let(::setOf).orEmpty(),
                        emptyDisplay = "All ${equipmentOptions.size}",
                        options = equipmentOptions,
                        onSelect = { selected -> updateFilter { it.copy(equipment = selected.firstOrNull()) } }
                    )
                    FilterPill(
                        title = "Level",
                        icon = Icons.Filled.BarChart,
                        selected = filter.level?.let(::setOf).orEmpty(),
                        emptyDisplay = "All",
                        options = repository.availableLevels,
                        onSelect = { selected -> updateFilter { it.copy(level = selected.firstOrNull()) } }
                    )
                    FilterPill(
                        title = "Force",
                        icon = Icons.Filled.SwapHoriz,
                        selected = filter.force?.let(::setOf).orEmpty(),
                        emptyDisplay = "All",
                        options = repository.availableForces,
                        onSelect = { selected -> updateFilter { it.copy(force = selected.firstOrNull()) } }
                    )
                    FilterPill(
                        title = "Mechanic",
                        icon = Icons.Filled.Settings,
                        selected = filter.mechanic?.let(::setOf).orEmpty(),
                        emptyDisplay = "All",
                        options = repository.availableMechanics,
                        onSelect = { selected -> updateFilter { it.copy(mechanic = selected.firstOrNull()) } }
                    )
                    FilterPill(
                        title = "Category",
                        icon = Icons.Filled.Tag,
                        selected = filter.category?.let(::setOf).orEmpty(),
                        emptyDisplay = "All",
                        options = repository.availableCategoriesByCount,
                        onSelect = { selected -> updateFilter { it.copy(category = selected.firstOrNull()) } }
                    )
                }
            }

            ResultsHeader(
                count = items.size,
                sortTitle = stringResource(filter.sort.titleRes),
                canReset = hasActiveFilters,
                onReset = {
                    focus.clearFocus()
                    updateFilter { WorkoutPickerFilterState() }
                },
                selectedSort = filter.sort,
                onSort = { selected -> updateFilter { it.copy(sort = selected) } },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            Box(
                modifier = Modifier
                    .scrollableArea(
                        state = pickerScrollState,
                        orientation = Orientation.Vertical
                    )
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)
                ) {
                    if (items.isEmpty()) {
                        item(key = "empty-picker") {
                            PickerEmptyState(source = source, onCreateExercise = onCreateExercise)
                        }
                    } else {
                        items(items.take(120), key = { it.id }) { item ->
                            val isSaved = item.id in savedExerciseIds
                            SwipeablePickerBookmarkRow(
                                itemId = item.id,
                                isSaved = isSaved,
                                onToggleSaved = { onToggleSaved(item.id) }
                            ) {
                                ExerciseRow(
                                    item = item,
                                    visual = repository.visualFor(item, visualGender),
                                    onClick = { onToggleExercise(item) },
                                    trailingContent = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            IconButton(onClick = { onToggleSaved(item.id) }, modifier = Modifier.size(36.dp)) {
                                                Icon(
                                                    if (isSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                                    contentDescription = if (isSaved) "Unsave exercise" else "Save exercise",
                                                    tint = if (isSaved) AppColors.Calorie else workoutsColors().mutedText,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (item.id in selectedExerciseIds) AppColors.Calorie
                                                        else workoutsColors().panel.copy(alpha = 0.52f)
                                                    )
                                                    .clickable { onToggleExercise(item) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    if (item.id in selectedExerciseIds) Icons.Filled.Check else Icons.Filled.AddCircle,
                                                    contentDescription = if (item.id in selectedExerciseIds) "Remove from day" else "Add to day",
                                                    tint = if (item.id in selectedExerciseIds) Color.White else workoutsColors().mutedText,
                                                    modifier = Modifier.size(if (item.id in selectedExerciseIds) 18.dp else 23.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { previewItem = item },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Info,
                                                    contentDescription = "Preview ${item.name}",
                                                    tint = workoutsColors().accent,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                            HorizontalDivider(
                                color = workoutsColors().hairline.copy(alpha = 0.28f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 144.dp, end = 20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun ExercisePickerPreview(
    item: ExerciseItem,
    visual: ExerciseVisual,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    val colors = workoutsColors()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .background(colors.background)
    ) {
        ExerciseDetailScreen(
            item = item,
            visual = visual,
            onBack = onBack,
            onEdit = onEdit,
            modifier = Modifier.fillMaxSize()
        )

        FudGlassSurface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            cornerRadius = 30.dp,
            padding = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(if (isSelected) colors.panel else AppColors.Calorie)
                    .clickable { onToggle() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (isSelected) Icons.Filled.Check else Icons.Filled.AddCircle,
                    contentDescription = null,
                    tint = if (isSelected) colors.accent else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(21.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (isSelected) "Remove from day" else "Add exercise",
                    color = if (isSelected) colors.charcoal else androidx.compose.ui.graphics.Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun PickerHeader(
    title: String,
    count: Int,
    showCreate: Boolean = false,
    onCreate: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showCreate && onCreate != null) {
            IconButton(onClick = onCreate) {
                Icon(Icons.Filled.AddCircle, contentDescription = "Create exercise", tint = workoutsColors().accent)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$count ${if (count == 1) "exercise" else "exercises"}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        FudGlassTextButton(text = "Done", onClick = onDismiss)
    }
}

@Composable
private fun PickerSourceControl(
    source: WorkoutPickerSource,
    onSelect: (WorkoutPickerSource) -> Unit,
    modifier: Modifier = Modifier
) {
    FudGlassSurface(modifier = modifier.fillMaxWidth(), cornerRadius = 18.dp, padding = 4.dp) {
        Row(Modifier.fillMaxWidth()) {
            listOf(
                WorkoutPickerSource.DATASET to "Dataset",
                WorkoutPickerSource.SAVED to "Saved"
            ).forEach { (option, label) ->
                val selected = source == option
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) AppColors.Calorie.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onSelect(option) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (option == WorkoutPickerSource.DATASET) Icons.Filled.Storage else Icons.Filled.Bookmark,
                        contentDescription = null,
                        tint = if (selected) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        label,
                        color = if (selected) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeablePickerBookmarkRow(
    itemId: String,
    isSaved: Boolean,
    onToggleSaved: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val saveTriggerPx = with(density) { 120.dp.toPx() }
    var offsetPx by remember(itemId) { mutableFloatStateOf(0f) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxSwipePx = with(density) { maxWidth.toPx() * 0.58f }
        Box(Modifier.fillMaxWidth()) {
            PickerSwipeBackground(offsetPx = offsetPx, isSaved = isSaved)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .background(workoutsColors().background)
                    .pointerInput(itemId, isSaved, maxSwipePx) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                offsetPx = (offsetPx + dragAmount).coerceIn(-maxSwipePx, maxSwipePx)
                            },
                            onDragEnd = {
                                val finalOffset = offsetPx
                                offsetPx = 0f
                                if (abs(finalOffset) >= saveTriggerPx) onToggleSaved()
                            },
                            onDragCancel = { offsetPx = 0f }
                        )
                    }
            ) {
                content()
            }
        }
    }
}

@Composable
private fun BoxScope.PickerSwipeBackground(offsetPx: Float, isSaved: Boolean) {
    if (offsetPx == 0f) {
        Box(Modifier.matchParentSize())
        return
    }
    val density = LocalDensity.current
    val trailing = offsetPx < 0f
    val revealWidth = with(density) { abs(offsetPx).toDp() }
    Box(Modifier.matchParentSize()) {
        Box(
            modifier = Modifier
                .align(if (trailing) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight()
                .width(revealWidth)
                .background(Color(0xFF2E7D32)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (isSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                if (revealWidth >= 76.dp) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        if (isSaved) "Unsave" else "Save",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerEmptyState(source: WorkoutPickerSource, onCreateExercise: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(
            if (source == WorkoutPickerSource.SAVED) Icons.Filled.BookmarkBorder else Icons.Filled.FitnessCenter,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(38.dp)
        )
        Text(
            if (source == WorkoutPickerSource.SAVED) "No saved exercises" else "No matching exercises",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (source == WorkoutPickerSource.SAVED) {
                "Bookmark exercises from the dataset to keep them here."
            } else {
                "Try clearing filters — or create your own exercise."
            },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            fontSize = 13.sp
        )
        if (source == WorkoutPickerSource.DATASET && onCreateExercise != null) {
            FudGlassTextButton(text = "Create exercise", onClick = onCreateExercise)
        }
    }
}

@Composable
internal fun WorkoutCopySheet(
    targetDate: LocalDate,
    days: List<WorkoutCopyDayUi>,
    onCopy: (LocalDate, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var includeSetDetails by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Copy from day", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Add a previous plan to ${selectedDateTitle(targetDate)}",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.54f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close copy picker")
                }
            }
            Text("What to copy", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !includeSetDetails,
                    onClick = { includeSetDetails = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Without details", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SegmentedButton(
                    selected = includeSetDetails,
                    onClick = { includeSetDetails = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("With set details", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                if (includeSetDetails) {
                    "Copies sets, weights, reps, RPE, units, and timers."
                } else {
                    "Adds exercise names only, with blank sets."
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.54f),
                fontSize = 12.sp
            )
            if (days.isEmpty()) {
                Text(
                    "No earlier workout days to copy.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 38.dp),
                    fontSize = 14.sp
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(days, key = { it.date }) { day ->
                        WorkoutCopyDayRow(
                            day = day,
                            onClick = { onCopy(day.date, includeSetDetails) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutCopyDayRow(
    day: WorkoutCopyDayUi,
    onClick: () -> Unit
) {
    val previewNames = day.exerciseNames.take(3).joinToString()
    val subtitle = if (day.exerciseNames.size > 3) {
        "$previewNames +${day.exerciseNames.size - 3}"
    } else {
        previewNames
    }
    FudGlassSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        cornerRadius = 18.dp,
        padding = 13.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Icon(Icons.Filled.EventRepeat, contentDescription = null, tint = AppColors.Calorie)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    day.date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "${day.exerciseNames.size}",
                color = AppColors.Calorie,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
