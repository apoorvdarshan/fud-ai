package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ColorFilter
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.WorkoutTabMode
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.ui.workouts.AnimatedExerciseImage
import com.apoorvdarshan.calorietracker.data.ExerciseItem
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.data.ExerciseSort
import com.apoorvdarshan.calorietracker.data.ExerciseVisual
import com.apoorvdarshan.calorietracker.ui.workouts.WorkoutsViewModel

@Composable
fun WorkoutsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val baseRepository = remember { ExerciseRepository.get(context) }
    val workoutState by container.workoutRepository.state.collectAsState(initial = com.apoorvdarshan.calorietracker.models.WorkoutPersistedState())
    val repo = remember(baseRepository, workoutState.customActivities, workoutState.userExercises) {
        baseRepository.includingActivities(workoutState.customActivities + workoutState.userExercises)
    }
    var showCreateUserExercise by remember { mutableStateOf(false) }
    var editingUserExerciseId by remember { mutableStateOf<String?>(null) }
    val vm: WorkoutsViewModel = viewModel()
    val profile by container.profileRepository.profile.collectAsState(initial = null)
    val latestWeight by container.weightRepository.latest.collectAsState(initial = null)
    val weightUnitRaw by container.prefs.weightUnit.collectAsState(initial = "kg")
    val weekStartsOnMonday by container.prefs.weekStartsOnMonday.collectAsState(initial = true)
    val weightUnit = WorkoutWeightUnit.fromStorage(weightUnitRaw)
    val bodyWeightKg = latestWeight?.weightKg ?: profile?.weightKg ?: 70.0

    LaunchedEffect(container.workoutRepository, bodyWeightKg, weightUnit, profile?.gender) {
        vm.bindWorkoutRepository(
            repository = container.workoutRepository,
            currentBodyWeightKg = bodyWeightKg,
            weightUnit = weightUnit,
            profileGender = profile?.gender ?: com.apoorvdarshan.calorietracker.models.Gender.MALE
        )
    }

    val openItem = vm.openExerciseSnapshot
        ?: vm.openExerciseId?.let { id -> repo.exercises.firstOrNull { it.id == id } }
    UserExerciseEditorSheet(
        visible = showCreateUserExercise || editingUserExerciseId != null,
        existingItemId = editingUserExerciseId,
        catalog = baseRepository,
        workoutRepository = container.workoutRepository,
        imageStore = container.imageStore,
        existingTemplate = editingUserExerciseId?.let { id ->
            workoutState.userExercises.firstOrNull { it.itemId == id }
        },
        onDismiss = {
            showCreateUserExercise = false
            editingUserExerciseId = null
        }
    )

    if (openItem != null) {
        BackHandler(onBack = vm::closeExerciseDetail)
        ExerciseDetailScreen(
            item = openItem,
            visual = repo.visualFor(openItem, vm.diaryUiState.visualGender),
            onBack = vm::closeExerciseDetail,
            onEdit = if (com.apoorvdarshan.calorietracker.models.UserExercise.isUserExercise(openItem.id)) {
                { editingUserExerciseId = openItem.id }
            } else {
                null
            },
            modifier = modifier
        )
        return
    }

    val toggleMode = {
        vm.setMode(
            if (vm.diaryUiState.mode == WorkoutTabMode.LOG) WorkoutTabMode.LIBRARY else WorkoutTabMode.LOG
        )
    }

    if (vm.diaryUiState.mode == WorkoutTabMode.LOG) {
        WorkoutDiaryScreen(
            container = container,
            bodyWeightKg = bodyWeightKg,
            state = vm.diaryUiState,
            exerciseRepository = repo,
            viewModel = vm,
            weekStartsOnMonday = weekStartsOnMonday,
            onShowLibrary = toggleMode,
            onCreateExercise = { showCreateUserExercise = true },
            onEditUserExercise = { editingUserExerciseId = it },
            modifier = modifier
        )
    } else {
        WorkoutLibraryScreen(
            repo = repo,
            vm = vm,
            onShowLog = toggleMode,
            onCreateExercise = { showCreateUserExercise = true },
            modifier = modifier
        )
    }
}

@Composable
internal fun WorkoutModeToggleButton(
    mode: WorkoutTabMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    FudGlassSurface(
        modifier = modifier
            .size(48.dp)
            .clickable(onClick = onToggle),
        cornerRadius = 18.dp,
        padding = 0.dp,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (mode == WorkoutTabMode.LOG) Icons.Filled.FitnessCenter else Icons.Filled.SportsGymnastics,
            contentDescription = if (mode == WorkoutTabMode.LOG) "Show exercise library" else "Show workout log",
            tint = AppColors.Calorie,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun WorkoutLibrarySearchRow(
    value: String,
    onValueChange: (String) -> Unit,
    onShowLog: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SearchPill(value = value, onValueChange = onValueChange, modifier = Modifier.weight(1f))
        WorkoutModeToggleButton(mode = WorkoutTabMode.LIBRARY, onToggle = onShowLog)
    }
}

private fun selectedSplitMuscles(vm: WorkoutsViewModel): Set<String> = vm.diaryUiState.splitGroups
    .filter { it.title in vm.splitGroupTitles }
    .flatMapTo(mutableSetOf()) { it.muscles }

private fun filterByWorkoutSplit(items: List<ExerciseItem>, vm: WorkoutsViewModel): List<ExerciseItem> {
    val muscles = selectedSplitMuscles(vm)
    if (muscles.isEmpty()) return items
    return items.filter { item ->
        item.primaryMuscles.any(muscles::contains) || item.secondaryMuscles.any(muscles::contains)
    }
}

@Composable
private fun WorkoutLibraryScreen(
    repo: ExerciseRepository,
    vm: WorkoutsViewModel,
    onShowLog: () -> Unit,
    onCreateExercise: () -> Unit,
    modifier: Modifier = Modifier
) {

    val items = remember(
        repo, vm.debouncedSearch, vm.levels, vm.equipment, vm.primaryMuscles, vm.secondaryMuscles,
        vm.forces, vm.mechanics, vm.categories, vm.sort, vm.splitGroupTitles,
        vm.diaryUiState.splitGroups
    ) {
        filterByWorkoutSplit(repo.filtered(
            levels = vm.levels,
            equipment = vm.equipment,
            primaryMuscles = vm.primaryMuscles,
            secondaryMuscles = vm.secondaryMuscles,
            forces = vm.forces,
            mechanics = vm.mechanics,
            categories = vm.categories,
            sort = vm.sort,
            searchText = vm.debouncedSearch
        ), vm)
    }

    // Dismiss the search keyboard as soon as the list starts scrolling — matches
    // iOS, where the scroll view resigns the search field automatically.
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun dismissKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) dismissKeyboard()
    }

    // Fud AI's tab bar floats over content (no Scaffold inset like Delts), so the
    // screen paints its own background and the list keeps its tail clear of the
    // floating bar. The status-bar inset is absorbed by the ad strip above this
    // screen (TabWithBanner). Search, filter chips, and the results header stay
    // pinned; only the exercise list scrolls (matches iOS).
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(workoutsColors().background)
    ) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WorkoutLibrarySearchRow(
                value = vm.searchInput,
                onValueChange = { vm.searchInput = it },
                onShowLog = onShowLog
            )
            FilterRow(repo, vm)
        }
        ResultsHeader(
            count = items.size,
            sortTitle = stringResource(vm.sort.titleRes),
            canReset = vm.hasActiveFilters,
            onReset = vm::reset,
            selectedSort = vm.sort,
            onSort = { vm.sort = it },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = BottomNavScrollPadding)
        ) {
            if (items.isEmpty()) {
                item(key = "empty") { EmptyState(onCreateExercise = onCreateExercise) }
            } else {
                items(items, key = { it.id }) { item ->
                    ExerciseRow(
                        item = item,
                        visual = repo.visualFor(item, vm.diaryUiState.visualGender),
                        onClick = { vm.openExerciseId = item.id }
                    )
                    HorizontalDivider(
                        color = workoutsColors().hairline.copy(alpha = 0.28f),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 144.dp, end = 20.dp)
                    )
                }
            }
            item(key = "bottompad") { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
internal fun SearchPill(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = workoutsColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.panel.copy(alpha = 0.62f))
            .border(0.5.dp, colors.hairline.copy(alpha = 0.52f), RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = if (value.isEmpty()) colors.secondaryAccent else colors.accent,
            modifier = Modifier.size(18.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None
            ),
            textStyle = TextStyle(color = colors.charcoal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(stringResource(R.string.search), color = colors.mutedText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f)
        )
        if (value.isNotEmpty()) {
            Icon(
                Icons.Filled.Cancel,
                contentDescription = stringResource(R.string.clear_search),
                tint = colors.mutedText,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onValueChange("") }
            )
        }
    }
}

@Composable
private fun FilterRow(repo: ExerciseRepository, vm: WorkoutsViewModel) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        val allLabel = stringResource(R.string.filter_all)
        FilterPill(
            title = "Body Part",
            icon = Icons.Filled.GridView,
            selected = vm.splitGroupTitles,
            emptyDisplay = allLabel,
            options = vm.diaryUiState.splitGroups.map { it.title },
            onSelect = { vm.splitGroupTitles = it }
        )
        val hidePrimary = vm.diaryUiState.preferences.split == com.apoorvdarshan.calorietracker.models.WorkoutSplit.FULL_BODY &&
            vm.splitGroupTitles.isNotEmpty()
        if (!hidePrimary) {
        val selectedMuscles = selectedSplitMuscles(vm)
        val primaryOptions = if (selectedMuscles.isEmpty()) {
            repo.availablePrimaryMuscles
        } else {
            repo.availablePrimaryMuscles.filter(selectedMuscles::contains)
        }
        FilterPill(stringResource(R.string.label_primary), Icons.Filled.GpsFixed, vm.primaryMuscles,
            stringResource(R.string.filter_all_count, primaryOptions.size),
            primaryOptions, glyphFor = { muscleGlyphAsset(it) }) { vm.primaryMuscles = it }
        }
        FilterPill(stringResource(R.string.label_secondary), Icons.Filled.GpsFixed, vm.secondaryMuscles, allLabel,
            repo.availableSecondaryMuscles, glyphFor = { muscleGlyphAsset(it) }) { vm.secondaryMuscles = it }
        FilterPill(stringResource(R.string.label_equipment), Icons.Filled.FitnessCenter, vm.equipment,
            stringResource(R.string.filter_all_count, repo.availableEquipment.size),
            repo.availableEquipment) { vm.equipment = it }
        FilterPill(stringResource(R.string.label_level), Icons.Filled.BarChart, vm.levels, allLabel, repo.availableLevels) { vm.levels = it }
        FilterPill(stringResource(R.string.label_force), Icons.Filled.SwapHoriz, vm.forces, allLabel, repo.availableForces) { vm.forces = it }
        FilterPill(stringResource(R.string.label_mechanic), Icons.Filled.Settings, vm.mechanics, allLabel, repo.availableMechanics) { vm.mechanics = it }
        FilterPill(stringResource(R.string.label_category), Icons.Filled.Tag, vm.categories, allLabel, repo.availableCategoriesByCount) { vm.categories = it }
    }
}

/** Maps a muscle name to its bundled glyph asset (mirrors iOS MuscleGlyphAsset). */
fun muscleGlyphAsset(name: String): String {
    val key = when (name.lowercase()) {
        "abdominals" -> "abs"
        "abductors" -> "abductors"
        "adductors" -> "adductors"
        "biceps" -> "biceps"
        "triceps" -> "triceps"
        "forearms" -> "forearms"
        "calves" -> "calves"
        "chest" -> "chest"
        "glutes" -> "glutes"
        "hamstrings" -> "hamstrings"
        "lats" -> "lats"
        "lower back" -> "lower_back"
        "middle back" -> "middle_back"
        "neck" -> "neck"
        "quadriceps" -> "quadriceps"
        "shoulders" -> "shoulders"
        "traps" -> "traps"
        else -> "generic"
    }
    return "file:///android_asset/muscle/muscle_icon_$key.png"
}

@Composable
internal fun FilterPill(
    title: String,
    icon: ImageVector,
    selected: Set<String>,
    emptyDisplay: String,
    options: List<String>,
    glyphFor: ((String) -> String)? = null,
    onSelect: (Set<String>) -> Unit
) {
    val colors = workoutsColors()
    var expanded by remember { mutableStateOf(false) }
    val active = selected.isNotEmpty()
    val value = if (active) selected.first() else emptyDisplay
    val clearLabel = "${stringResource(R.string.filter_all)} $title"

    Box {
        Row(
            modifier = Modifier
                .heightIn(min = 46.dp)
                .widthIn(min = 112.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(colors.panel.copy(alpha = if (active) 0.46f else 0.30f))
                .border(
                    0.5.dp,
                    (if (active) colors.accent else colors.hairline).copy(alpha = if (active) 0.42f else 0.30f),
                    RoundedCornerShape(17.dp)
                )
                .clickable { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(icon, null, tint = if (active) colors.accent else colors.secondaryAccent, modifier = Modifier.size(18.dp))
            Column {
                Text(title.uppercase(), color = colors.mutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(value, color = colors.charcoal, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = colors.mutedText, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 340.dp),
            containerColor = colors.card,
            shape = RoundedCornerShape(16.dp)
        ) {
            DropdownMenuItem(
                text = { Text(clearLabel, color = if (!active) colors.accent else colors.charcoal, fontWeight = if (!active) FontWeight.Bold else FontWeight.Normal) },
                onClick = { onSelect(emptySet()); expanded = false },
                trailingIcon = if (!active) {
                    { Icon(Icons.Filled.Check, null, tint = colors.accent) }
                } else null
            )
            options.forEach { option ->
                val isSel = selected.contains(option)
                DropdownMenuItem(
                    text = { Text(option, color = if (isSel) colors.accent else colors.charcoal, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { onSelect(setOf(option)); expanded = false },
                    leadingIcon = glyphFor?.let { fn ->
                        {
                            AsyncImage(
                                model = fn(option),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(if (isSel) colors.accent else colors.secondaryAccent),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    trailingIcon = if (isSel) {
                        { Icon(Icons.Filled.Check, null, tint = colors.accent) }
                    } else null
                )
            }
        }
    }
}

@Composable
internal fun ResultsHeader(
    count: Int,
    sortTitle: String,
    canReset: Boolean,
    onReset: () -> Unit,
    selectedSort: ExerciseSort,
    onSort: (ExerciseSort) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = workoutsColors()
    var sortExpanded by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                pluralStringResource(R.plurals.exercises_count, count, count),
                color = colors.charcoal, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
            )
            Text(sortTitle, color = colors.mutedText, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CapsuleButton(
                text = stringResource(R.string.reset), icon = Icons.Filled.Refresh,
                tint = if (canReset) colors.secondaryAccent else colors.mutedText,
                enabled = canReset, active = canReset, onClick = onReset
            )
            Box {
                CapsuleButton(
                    text = stringResource(R.string.sort), icon = Icons.Filled.SwapVert,
                    tint = if (count == 0) colors.mutedText else if (selectedSort == ExerciseSort.NAME) colors.mutedText else colors.accent,
                    enabled = count > 0, active = selectedSort != ExerciseSort.NAME, onClick = { sortExpanded = true }
                )
                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false },
                    containerColor = colors.card,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    ExerciseSort.entries.forEach { sort ->
                        val isSel = selectedSort == sort
                        DropdownMenuItem(
                            text = { Text(stringResource(sort.titleRes), color = if (isSel) colors.accent else colors.charcoal, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { onSort(sort); sortExpanded = false },
                            trailingIcon = if (isSel) {
                                { Icon(Icons.Filled.Check, null, tint = colors.accent) }
                            } else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CapsuleButton(text: String, icon: ImageVector, tint: Color, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    val colors = workoutsColors()
    val bg = if (active) tint.copy(alpha = 0.12f) else colors.panel.copy(alpha = 0.30f)
    val border = if (active) tint.copy(alpha = 0.32f) else colors.hairline.copy(alpha = 0.30f)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(0.5.dp, border, CircleShape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Text(text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ExerciseRow(
    item: ExerciseItem,
    visual: ExerciseVisual,
    onClick: () -> Unit,
    trailingContent: @Composable () -> Unit = {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = workoutsColors().hairline,
            modifier = Modifier.size(20.dp)
        )
    }
) {
    val colors = workoutsColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier
                .size(104.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.panel.copy(alpha = 0.32f))
                .border(0.5.dp, colors.hairline.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
        ) {
            AnimatedExerciseImage(visual, Modifier.fillMaxSize(), animatesFrames = false)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(item.name, color = colors.charcoal, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Tag(item.primaryMusclesTitle, Icons.Filled.GpsFixed)
                Tag(item.equipment, Icons.Filled.FitnessCenter)
                Tag(item.level, Icons.Filled.BarChart)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Filled.Storage, null, tint = colors.secondaryAccent, modifier = Modifier.size(13.dp))
                Text(item.databaseMetadataSummary, color = colors.secondaryAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailingContent()
    }
}

@Composable
private fun Tag(title: String, icon: ImageVector) {
    val colors = workoutsColors()
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.panel.copy(alpha = 0.28f))
            .border(0.5.dp, colors.hairline.copy(alpha = 0.22f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = colors.mutedText, modifier = Modifier.size(11.dp))
        Text(title, color = colors.mutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyState(onCreateExercise: (() -> Unit)? = null) {
    val colors = workoutsColors()
    Column(
        Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.FilterListOff, null, tint = colors.mutedText, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.empty_title), color = colors.charcoal, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            "Try different filters — or create your own exercise.",
            color = colors.mutedText, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        onCreateExercise?.let { create ->
            Button(onClick = create, modifier = Modifier.padding(top = 8.dp)) {
                Text("Create exercise")
            }
        }
    }
}
