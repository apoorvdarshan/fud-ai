package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.data.ExerciseVisual
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.PlannedExercise
import com.apoorvdarshan.calorietracker.models.ExerciseTimerAction
import com.apoorvdarshan.calorietracker.models.WorkoutRpeScale
import com.apoorvdarshan.calorietracker.models.WorkoutSetInput
import com.apoorvdarshan.calorietracker.models.PlannedSet
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextButton
import com.apoorvdarshan.calorietracker.ui.home.SheetGlassDropdownMenu
import com.apoorvdarshan.calorietracker.ui.home.SheetGlassDropdownMenuItem
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.ui.util.formattedWholeNumber
import coil.compose.AsyncImage
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val WORKOUT_WEEKS = 53
private const val CURRENT_WORKOUT_WEEK = WORKOUT_WEEKS - 1

@Composable
internal fun WorkoutDiaryScreen(
    state: WorkoutDiaryUiState,
    exerciseRepository: ExerciseRepository,
    viewModel: WorkoutsViewModel,
    modifier: Modifier = Modifier,
    weekStartsOnMonday: Boolean = true,
    onShowLibrary: () -> Unit
) {
    var pickerRequest by remember { mutableStateOf<WorkoutPickerRequest?>(null) }
    var copySheetVisible by remember { mutableStateOf(false) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val exerciseCardBounds = remember { mutableStateMapOf<UUID, androidx.compose.ui.geometry.Rect>() }
    var diaryRootOrigin by remember { mutableStateOf(Offset.Zero) }

    fun dismissKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) dismissKeyboard()
    }

    LaunchedEffect(state.exercises.map { it.id }) {
        exerciseCardBounds.keys.retainAll(state.exercises.mapTo(mutableSetOf()) { it.id })
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { diaryRootOrigin = it.boundsInRoot().topLeft }
            .pointerInput(diaryRootOrigin) {
                // Observe completed pointers without consuming them, so set
                // fields, buttons, scrolling, and day swipes keep their normal
                // input behavior.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.firstOrNull { it.previousPressed && !it.pressed }?.let { change ->
                            // Match iOS: blank screen chrome dismisses input,
                            // while the whole exercise card preserves focus.
                            val rootPosition = change.position + diaryRootOrigin
                            if (exerciseCardBounds.values.none { it.contains(rootPosition) }) {
                                dismissKeyboard()
                            }
                        }
                    }
                }
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 64.dp,
                end = 16.dp,
                bottom = BottomNavScrollPadding + 76.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "workout-week-strip") {
                WorkoutWeekStrip(
                    selectedDate = state.selectedDate,
                    workoutCounts = state.workoutCounts,
                    onSelect = {
                        dismissKeyboard()
                        viewModel.selectDate(it)
                    },
                    weekStartsOnMonday = weekStartsOnMonday,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }

            item(key = "workout-burn") {
                WorkoutBurnHero(
                    state = state,
                    onCalculate = {
                        dismissKeyboard()
                        viewModel.calculateBurn()
                    },
                    modifier = Modifier.workoutDaySwipe(
                        selectedDate = state.selectedDate,
                        onMove = {
                            dismissKeyboard()
                            viewModel.moveDate(it)
                        }
                    )
                )
            }

            item(key = "workout-day-title") {
                WorkoutDayHeader(
                    selectedDate = state.selectedDate,
                    workoutCount = state.exercises.size,
                    modifier = Modifier
                        .workoutDaySwipe(
                            selectedDate = state.selectedDate,
                            onMove = {
                                dismissKeyboard()
                                viewModel.moveDate(it)
                            }
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { dismissKeyboard() }
                )
            }

            if (state.exercises.isEmpty()) {
                item(key = "workout-empty") {
                    WorkoutEmptyState(
                        splitTitle = state.preferences.split.title,
                        onAdd = { addMenuExpanded = true },
                        modifier = Modifier.workoutDaySwipe(
                            selectedDate = state.selectedDate,
                            onMove = { viewModel.moveDate(it) }
                        )
                    )
                }
            } else {
                items(state.exercises, key = { it.id }) { exercise ->
                    val isSaved = exercise.itemId in state.savedExerciseIds
                    val toggleSaved = { viewModel.toggleSaved(exercise.itemId) }
                    val removeExercise = {
                        dismissKeyboard()
                        viewModel.removeExercise(exercise.id)
                    }
                    SwipeableWorkoutExerciseCard(
                        exerciseId = exercise.id,
                        isSaved = isSaved,
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            exerciseCardBounds[exercise.id] = coordinates.boundsInRoot()
                        },
                        onToggleSaved = toggleSaved,
                        onRemove = removeExercise
                    ) {
                        WorkoutExerciseCard(
                            exercise = exercise,
                            visual = exerciseRepository.visualFor(exercise.asExerciseItem(), state.visualGender),
                            weightUnit = state.weightUnit,
                            rpeScale = state.preferences.rpeScale,
                            editingDate = state.selectedDate,
                            isSaved = isSaved,
                            onOpen = { viewModel.openDiaryExercise(exercise) },
                            onToggleSaved = toggleSaved,
                            onRemove = removeExercise,
                            onSetCount = { viewModel.setSetCount(exercise.id, it) },
                            onWeight = { setId, value -> viewModel.updateWeight(exercise.id, setId, value) },
                            onReps = { setId, value -> viewModel.updateReps(exercise.id, setId, value) },
                            onRpe = { setId, value -> viewModel.updateRpe(exercise.id, setId, value) },
                            onTimerAction = { viewModel.updateTimer(exercise.id, it) }
                        )
                    }
                }
            }

            item(key = "workout-extra-space") { Spacer(Modifier.height(24.dp)) }
        }

        WorkoutModeToggleButton(
            mode = com.apoorvdarshan.calorietracker.models.WorkoutTabMode.LOG,
            onToggle = {
                dismissKeyboard()
                onShowLibrary()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 16.dp)
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 100.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(AppColors.Calorie)
                    .clickable(role = Role.Button) {
                        dismissKeyboard()
                        addMenuExpanded = true
                    }
                    .semantics { contentDescription = "Add workout" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }

            SheetGlassDropdownMenu(
                expanded = addMenuExpanded,
                onDismissRequest = { addMenuExpanded = false },
                modifier = Modifier.heightIn(max = 520.dp),
                menuWidth = 236.dp
            ) {
                if (state.splitGroups.isEmpty()) {
                    SheetGlassDropdownMenuItem(
                        label = "All exercises",
                        leadingContent = { WorkoutMenuGlyph(workoutMenuGlyphAsset("All exercises", emptySet())) },
                        onClick = {
                            addMenuExpanded = false
                            pickerRequest = WorkoutPickerRequest.all()
                        }
                    )
                } else {
                    state.splitGroups.forEach { group ->
                        SheetGlassDropdownMenuItem(
                            label = group.title,
                            leadingContent = {
                                WorkoutMenuGlyph(workoutMenuGlyphAsset(group.title, group.muscles))
                            },
                            onClick = {
                                addMenuExpanded = false
                                pickerRequest = WorkoutPickerRequest.group(group)
                            }
                        )
                    }
                }
                HorizontalDivider(color = workoutsColors().hairline.copy(alpha = 0.45f))
                SheetGlassDropdownMenuItem(
                    label = "Copy from day",
                    leadingIcon = Icons.Filled.ContentCopy,
                    onClick = {
                        addMenuExpanded = false
                        copySheetVisible = true
                    }
                )
                SheetGlassDropdownMenuItem(
                    label = "Saved",
                    leadingIcon = Icons.Filled.Bookmark,
                    onClick = {
                        addMenuExpanded = false
                        pickerRequest = WorkoutPickerRequest.saved()
                    }
                )
            }
        }
    }

    pickerRequest?.let { request ->
        WorkoutPickerSheet(
            request = request,
            repository = exerciseRepository,
            selectedExerciseIds = state.exercises.mapTo(mutableSetOf()) { it.itemId },
            savedExerciseIds = state.savedExerciseIds,
            initialSource = if (request.isSavedContext) WorkoutPickerSource.SAVED else viewModel.pickerSource(),
            initialFilterState = viewModel.pickerFilter(request.contextId),
            visualGender = state.visualGender,
            preferredEquipment = state.preferences.equipment,
            hidePrimaryFilter = request.muscles.isNotEmpty() &&
                state.preferences.split in setOf(
                    com.apoorvdarshan.calorietracker.models.WorkoutSplit.FULL_BODY,
                    com.apoorvdarshan.calorietracker.models.WorkoutSplit.CUSTOM
                ),
            onSourceChange = viewModel::setPickerSource,
            onFilterStateChange = { viewModel.setPickerFilter(request.contextId, it) },
            onToggleExercise = viewModel::toggleExercise,
            onToggleSaved = viewModel::toggleSaved,
            onDismiss = { pickerRequest = null }
        )
    }

    if (copySheetVisible) {
        WorkoutCopySheet(
            targetDate = state.selectedDate,
            days = state.copyDays,
            onCopy = {
                viewModel.copyPlan(it)
                copySheetVisible = false
            },
            onDismiss = { copySheetVisible = false }
        )
    }

    state.notice?.let { message ->
        FudGlassDialog(onDismissRequest = viewModel::dismissNotice) {
            Text(
                text = "Log your workout first",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FudGlassTextButton(text = "OK", onClick = viewModel::dismissNotice)
            }
        }
    }
}

@Composable
private fun WorkoutMenuGlyph(asset: String) {
    AsyncImage(
        model = asset,
        contentDescription = null,
        colorFilter = ColorFilter.tint(AppColors.Calorie),
        modifier = Modifier.size(20.dp)
    )
}

private fun workoutMenuGlyphAsset(title: String, muscles: Set<String>): String {
    if (muscles.size == 1) return muscleGlyphAsset(muscles.first())
    val key = when {
        title.contains("push", ignoreCase = true) -> "group_push"
        title.contains("pull", ignoreCase = true) -> "group_pull"
        title.contains("upper", ignoreCase = true) -> "group_upper"
        title.contains("lower", ignoreCase = true) ||
            title.contains("leg", ignoreCase = true) ||
            title.contains("quad", ignoreCase = true) ||
            title.contains("hamstring", ignoreCase = true) -> "group_lower"
        title.contains("core", ignoreCase = true) || title.contains("ab", ignoreCase = true) -> "abs"
        title.contains("arm", ignoreCase = true) ||
            title.contains("bicep", ignoreCase = true) ||
            title.contains("tricep", ignoreCase = true) -> "group_arms"
        title.contains("back", ignoreCase = true) ||
            title.contains("lat", ignoreCase = true) ||
            title.contains("trap", ignoreCase = true) -> "group_back"
        title.contains("chest", ignoreCase = true) -> "chest"
        title.contains("shoulder", ignoreCase = true) -> "shoulders"
        else -> "generic"
    }
    return "file:///android_asset/muscle/muscle_icon_$key.png"
}

@Composable
private fun WorkoutBurnHero(
    state: WorkoutDiaryUiState,
    onCalculate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(176.dp)) {
            WorkoutLogBurnButton(
                isCalculating = state.isCalculatingBurn,
                onCalculate = onCalculate,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        FudGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 26.dp,
            padding = 10.dp
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WorkoutMetric(
                    label = "Sets",
                    value = state.performedSetCount.formattedWholeNumber(),
                    icon = Icons.Filled.Checklist,
                    active = state.performedSetCount > 0,
                    modifier = Modifier.weight(1f)
                )
                MetricDivider()
                WorkoutMetric(
                    label = "Workouts",
                    value = state.exercises.size.formattedWholeNumber(),
                    icon = Icons.Filled.FitnessCenter,
                    active = state.exercises.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                )
                MetricDivider()
                WorkoutMetric(
                    label = "Reps",
                    value = state.repCount.formattedWholeNumber(),
                    icon = Icons.Filled.Repeat,
                    active = state.repCount > 0,
                    modifier = Modifier.weight(1f)
                )
                MetricDivider()
                WorkoutMetric(
                    label = "Burn",
                    value = state.caloriesBurned?.let { "${it.formattedWholeNumber()} kcal" } ?: "-- kcal",
                    icon = Icons.Filled.LocalFireDepartment,
                    active = state.caloriesBurned != null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WorkoutLogBurnButton(
    isCalculating: Boolean,
    onCalculate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "workout-burn-press"
    )
    Box(
        modifier = modifier
            .size(176.dp)
            .scale(scale)
            .clickable(
                enabled = !isCalculating,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onCalculate
            )
            .semantics {
                contentDescription = if (isCalculating) {
                    "Calculating calorie burn"
                } else {
                    "Calculate calorie burn"
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.timer_button_red),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier.size(156.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isCalculating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(27.dp),
                    color = Color.White,
                    strokeWidth = 2.5.dp
                )
            } else {
                Icon(
                    Icons.Filled.LocalFireDepartment,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(27.dp)
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = if (isCalculating) "Calculating…" else "Calculate",
                color = Color.White,
                fontSize = if (isCalculating) 18.sp else 22.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Text(
                text = "CALORIE BURN",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.4.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun WorkoutMetric(
    label: String,
    value: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (active) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Column(
        modifier = modifier.padding(horizontal = 7.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Text(
            text = value,
            color = color,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 14.sp,
                maxFontSize = 24.sp,
                stepSize = 0.5.sp
            )
        )
    }
}

@Composable
private fun MetricDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(44.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f))
    )
}

@Composable
private fun WorkoutDayHeader(
    selectedDate: LocalDate,
    workoutCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.FitnessCenter, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = selectedDateTitle(selectedDate),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$workoutCount ${if (workoutCount == 1) "workout" else "workouts"}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun WorkoutEmptyState(
    splitTitle: String,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    FudGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd),
        cornerRadius = 20.dp,
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "No workouts logged",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Use + to pick $splitTitle exercises for this day",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Adds iOS-style row gestures without hiding the card's visible Save and Delete buttons.
 * A leading swipe toggles Saved; a trailing swipe removes the exercise from this day.
 */
@Composable
private fun SwipeableWorkoutExerciseCard(
    exerciseId: UUID,
    isSaved: Boolean,
    onToggleSaved: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val saveTriggerPx = with(density) { 120.dp.toPx() }
    val deleteTriggerPx = with(density) { 160.dp.toPx() }
    var offsetPx by remember(exerciseId) { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxSwipePx = with(density) { maxWidth.toPx() * 0.58f }
        Box(Modifier.fillMaxWidth()) {
            WorkoutExerciseSwipeBackground(offsetPx = offsetPx, isSaved = isSaved)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .pointerInput(exerciseId, isSaved, maxSwipePx) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                offsetPx = (offsetPx + dragAmount).coerceIn(-maxSwipePx, maxSwipePx)
                            },
                            onDragEnd = {
                                val finalOffset = offsetPx
                                offsetPx = 0f
                                when {
                                    finalOffset <= -deleteTriggerPx -> onRemove()
                                    finalOffset >= saveTriggerPx -> onToggleSaved()
                                }
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
private fun BoxScope.WorkoutExerciseSwipeBackground(offsetPx: Float, isSaved: Boolean) {
    if (offsetPx == 0f) {
        Box(Modifier.matchParentSize())
        return
    }

    val density = LocalDensity.current
    val trailing = offsetPx < 0f
    val revealWidth = with(density) { abs(offsetPx).toDp() }
    val background = if (trailing) Color(0xFFD32F2F) else Color(0xFF2E7D32)
    val icon = when {
        trailing -> Icons.Filled.DeleteOutline
        isSaved -> Icons.Filled.Bookmark
        else -> Icons.Filled.Save
    }
    val label = when {
        trailing -> "Delete"
        isSaved -> "Unsave"
        else -> "Save"
    }

    Box(Modifier.matchParentSize().clip(RoundedCornerShape(24.dp))) {
        Box(
            modifier = Modifier
                .align(if (trailing) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight()
                .width(revealWidth)
                .background(background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                if (revealWidth >= 76.dp) {
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun WorkoutExerciseCard(
    exercise: PlannedExercise,
    visual: ExerciseVisual,
    modifier: Modifier = Modifier,
    weightUnit: WorkoutWeightUnit,
    rpeScale: WorkoutRpeScale,
    editingDate: LocalDate,
    isSaved: Boolean,
    onOpen: () -> Unit,
    onToggleSaved: () -> Unit,
    onRemove: () -> Unit,
    onSetCount: (Int) -> Unit,
    onWeight: (UUID, String) -> Unit,
    onReps: (UUID, String) -> Unit,
    onRpe: (UUID, String) -> Unit,
    onTimerAction: (ExerciseTimerAction) -> Unit
) {
    FudGlassSurface(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 24.dp,
        padding = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                        .border(
                            0.7.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            RoundedCornerShape(16.dp)
                        )
                ) {
                    AnimatedExerciseImage(visual, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        exercise.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(exercise.primaryMuscles.joinToString().ifBlank { "Unspecified" })
                            append(" · ")
                            append(exercise.equipment)
                        },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.57f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open exercise instructions",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f),
                    modifier = Modifier.size(20.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!exercise.isCardio) {
                    Icon(Icons.Filled.Checklist, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onSetCount(exercise.sets.size - 1) },
                        enabled = exercise.sets.size > 1,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Remove set", modifier = Modifier.size(18.dp))
                    }
                    Text(
                        "${exercise.sets.size} ${if (exercise.sets.size == 1) "set" else "sets"}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(54.dp)
                    )
                    IconButton(
                        onClick = { onSetCount(exercise.sets.size + 1) },
                        enabled = exercise.sets.size < 12,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add blank set", modifier = Modifier.size(18.dp))
                    }
                } else {
                    Text("Timed activity", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                }
                WorkoutExerciseTimer(exercise, onTimerAction)
                IconButton(onClick = onToggleSaved, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (isSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = if (isSaved) "Unsave exercise" else "Save exercise",
                        tint = if (isSaved) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Remove exercise",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.82f),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            if (!exercise.isCardio) Column {
                exercise.sets.forEachIndexed { index, set ->
                    key(editingDate, set.id) {
                        WorkoutSetRow(
                            index = index,
                            set = set,
                            weightUnit = weightUnit,
                            rpeScale = set.rpeScale ?: rpeScale,
                            onWeight = { onWeight(set.id, it) },
                            onReps = { onReps(set.id, it) },
                            onRpe = { onRpe(set.id, it) }
                        )
                    }
                    if (index < exercise.sets.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 54.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            thickness = 0.6.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutExerciseTimer(
    exercise: PlannedExercise,
    onAction: (ExerciseTimerAction) -> Unit
) {
    val timer = exercise.timer
    var now by remember { mutableStateOf(Instant.now()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<ExerciseTimerAction?>(null) }
    LaunchedEffect(timer) {
        now = Instant.now()
        while (timer?.isRunning == true) {
            delay(1_000)
            now = Instant.now()
        }
    }
    val elapsed = (timer?.elapsedSeconds(now) ?: 0.0).toLong()
    val time = if (elapsed >= 3_600) {
        String.format(Locale.US, "%d:%02d:%02d", elapsed / 3_600, elapsed / 60 % 60, elapsed % 60)
    } else {
        String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
    }
    Box {
        IconButton(
            onClick = { if (timer == null) onAction(ExerciseTimerAction.START) else menuExpanded = true },
            modifier = Modifier.size(48.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Timer,
                    contentDescription = if (timer == null) "Start timer for ${exercise.name}" else "Timer options for ${exercise.name}, $time, ${if (timer.isRunning) "running" else if (timer.isSaved) "saved" else "paused"}",
                    tint = if (timer == null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f) else AppColors.Calorie,
                    modifier = Modifier.size(19.dp)
                )
                if (timer != null) Text(time, color = AppColors.Calorie, fontSize = 9.sp, maxLines = 1)
            }
        }
        SheetGlassDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, menuWidth = 200.dp) {
            SheetGlassDropdownMenuItem(label = if (timer?.isRunning == true) "Pause" else "Resume", onClick = {
                menuExpanded = false
                onAction(if (timer?.isRunning == true) ExerciseTimerAction.PAUSE else ExerciseTimerAction.RESUME)
            })
            if (timer?.isSaved != true) SheetGlassDropdownMenuItem(label = "Stop & save", onClick = {
                menuExpanded = false
                onAction(ExerciseTimerAction.STOP)
            })
            SheetGlassDropdownMenuItem(label = "Restart timer", onClick = {
                menuExpanded = false
                pendingAction = ExerciseTimerAction.RESTART
            })
            SheetGlassDropdownMenuItem(label = "Discard timer", onClick = {
                menuExpanded = false
                pendingAction = ExerciseTimerAction.DISCARD
            })
        }
    }
    pendingAction?.let { action ->
        FudGlassDialog(onDismissRequest = { pendingAction = null }) {
            Text(
                if (action == ExerciseTimerAction.RESTART) "Restart timer?" else "Discard timer?",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text("This clears the recorded time for ${exercise.name}.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FudGlassTextButton(text = "Cancel", onClick = { pendingAction = null })
                FudGlassTextButton(text = if (action == ExerciseTimerAction.RESTART) "Restart" else "Discard", onClick = {
                    pendingAction = null
                    onAction(action)
                })
            }
        }
    }
}

@Composable
internal fun WorkoutSetRow(
    index: Int,
    set: PlannedSet,
    weightUnit: WorkoutWeightUnit,
    rpeScale: WorkoutRpeScale,
    onWeight: (String) -> Unit,
    onReps: (String) -> Unit,
    onRpe: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            "Set ${index + 1}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.width(47.dp)
        )
        WorkoutSetField(
            value = set.displayWeight(weightUnit),
            editingKey = weightUnit,
            sanitize = { proposed, _ -> WorkoutSetInput.weight(proposed) },
            onValueChange = onWeight,
            placeholder = weightUnit.storageValue,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f)
        )
        WorkoutSetField(
            value = set.reps,
            sanitize = { proposed, _ -> WorkoutSetInput.reps(proposed) },
            onValueChange = onReps,
            placeholder = "Reps",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            val help = stringResource(when (rpeScale) {
                WorkoutRpeScale.STRENGTH -> R.string.workout_rpe_help_strength
                WorkoutRpeScale.CR10 -> R.string.workout_rpe_help_cr10
                WorkoutRpeScale.BORG -> R.string.workout_rpe_help_borg
            })
            var showHelp by remember { mutableStateOf(false) }
            Text(
                text = stringResource(R.string.workout_rpe_label),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = Role.Button) { showHelp = true }
                    .padding(vertical = 8.dp)
            )
            WorkoutSetField(
                value = set.rpe,
                editingKey = rpeScale,
                sanitize = rpeScale::sanitize,
                onValueChange = onRpe,
                placeholder = rpeScale.inputPlaceholder,
                keyboardType = if (rpeScale.allowsDecimalInput) KeyboardType.Decimal else KeyboardType.Number,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = help },
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
            )
            if (showHelp) {
                FudGlassDialog(onDismissRequest = { showHelp = false }) {
                    Text(stringResource(R.string.workout_rpe_label), style = MaterialTheme.typography.titleMedium)
                    Text(help, style = MaterialTheme.typography.bodyMedium)
                    FudGlassTextButton(text = stringResource(android.R.string.ok), onClick = { showHelp = false })
                }
            }
        }
    }
}

@Composable
internal fun WorkoutSetField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    imeAction: ImeAction = ImeAction.Next,
    editingKey: Any = Unit,
    sanitize: (String, String) -> String = { proposed, _ -> proposed }
) {
    val editor = remember(editingKey) { WorkoutFieldEditor(value) }
    LaunchedEffect(value, editor) { editor.receive(value) }
    val shape = RoundedCornerShape(11.dp)
    BasicTextField(
        value = editor.value,
        onValueChange = { proposed ->
            editor.edit(proposed, sanitize)?.let(onValueChange)
        },
        singleLine = true,
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(AppColors.Calorie),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = keyboardActions,
        modifier = modifier
            .onFocusChanged { editor.setFocused(it.isFocused) }
            .height(39.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
            .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .padding(horizontal = 7.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (editor.value.text.isEmpty()) {
                    Text(
                        placeholder,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
                inner()
            }
        }
    )
}

@Composable
private fun WorkoutWeekStrip(
    selectedDate: LocalDate,
    workoutCounts: Map<LocalDate, Int>,
    onSelect: (LocalDate) -> Unit,
    weekStartsOnMonday: Boolean,
    modifier: Modifier = Modifier
) {
    val firstDay = remember(weekStartsOnMonday) {
        if (weekStartsOnMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    }
    val today = remember { LocalDate.now() }
    val currentWeekStart = remember(today, firstDay) { startOfWeek(today, firstDay) }
    val targetWeek = remember(selectedDate, currentWeekStart, firstDay) {
        val selectedStart = startOfWeek(selectedDate, firstDay)
        val difference = ChronoUnit.WEEKS.between(currentWeekStart, selectedStart).toInt()
        (CURRENT_WORKOUT_WEEK + difference).coerceIn(0, CURRENT_WORKOUT_WEEK)
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = targetWeek)
    val fling = rememberSnapFlingBehavior(lazyListState = state)

    LaunchedEffect(targetWeek) {
        if (state.firstVisibleItemIndex != targetWeek) state.animateScrollToItem(targetWeek)
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val pageWidth = maxWidth
        LazyRow(state = state, flingBehavior = fling, modifier = Modifier.fillMaxWidth()) {
            items((0 until WORKOUT_WEEKS).toList()) { weekIndex ->
                val weekStart = currentWeekStart.plusWeeks((weekIndex - CURRENT_WORKOUT_WEEK).toLong())
                Row(Modifier.width(pageWidth)) {
                    repeat(7) { dayIndex ->
                        val date = weekStart.plusDays(dayIndex.toLong())
                        WorkoutDayTile(
                            date = date,
                            isSelected = date == selectedDate,
                            isToday = date == today,
                            count = workoutCounts[date] ?: 0,
                            // The visible days in the current week are all
                            // selectable for planning, including tomorrow.
                            // Horizontal forward swipes remain capped at today.
                            enabled = true,
                            onClick = { onSelect(date) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutDayTile(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.32f)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()),
            color = if (isSelected) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .then(
                    when {
                        isSelected -> Modifier
                            .shadow(6.dp, CircleShape, ambientColor = AppColors.Calorie.copy(alpha = 0.3f))
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd)))
                        isToday -> Modifier.border(1.5.dp, AppColors.Calorie.copy(alpha = 0.35f), CircleShape)
                        else -> Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                date.dayOfMonth.toString(),
                color = when {
                    isSelected -> Color.White
                    isToday -> AppColors.Calorie
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (count > 0) AppColors.Calorie else Color.Transparent)
        )
    }
}

private fun Modifier.workoutDaySwipe(
    selectedDate: LocalDate,
    onMove: (Long) -> Unit
): Modifier = pointerInput(selectedDate) {
    var accumulated = 0f
    val threshold = 80.dp.toPx()
    detectHorizontalDragGestures(
        onDragStart = { accumulated = 0f },
        onDragCancel = { accumulated = 0f },
        onHorizontalDrag = { change, amount ->
            accumulated += amount
            change.consume()
        },
        onDragEnd = {
            when {
                accumulated > threshold -> onMove(-1L)
                accumulated < -threshold && selectedDate.isBefore(LocalDate.now()) -> onMove(1L)
            }
            accumulated = 0f
        }
    )
}

private fun startOfWeek(date: LocalDate, firstDay: DayOfWeek): LocalDate {
    val daysBack = ((date.dayOfWeek.value - firstDay.value) + 7) % 7
    return date.minusDays(daysBack.toLong())
}

internal fun selectedDateTitle(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()))
}
