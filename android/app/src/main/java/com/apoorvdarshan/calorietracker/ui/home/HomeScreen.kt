package com.apoorvdarshan.calorietracker.ui.home

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.saveable.rememberSaveable
import com.apoorvdarshan.calorietracker.ui.navigation.LocalLaunchFillEpoch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import java.util.UUID
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import com.apoorvdarshan.calorietracker.ui.util.clockTimePattern
import com.apoorvdarshan.calorietracker.ui.util.formattedWholeNumber
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.formatFastingDuration
import com.apoorvdarshan.calorietracker.services.MealShare
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MacroValueFormatter
import com.apoorvdarshan.calorietracker.models.CurrentMealSchedule
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.QuickAction
import com.apoorvdarshan.calorietracker.models.QuickActionRequest
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.ui.components.InAppCameraCaptureDialog
import com.apoorvdarshan.calorietracker.ui.components.MacroCard
import com.apoorvdarshan.calorietracker.ui.components.DateWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.components.FudGlassPrimaryButton
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextField
import com.apoorvdarshan.calorietracker.ui.components.WeekEnergyStrip
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private enum class AddMenuGroup {
    PhotoAndScan,
    DescribeMeal,
    ReuseMeal,
    Water,
    Fasting
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    quickActionRequest: QuickActionRequest? = null,
    onQuickActionHandled: (Long) -> Unit = {}
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val weekStartsOnMonday by container.prefs.weekStartsOnMonday.collectAsState(initial = true)
    val allEntries by container.foodRepository.entries.collectAsState(initial = emptyList())

    var showText by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var savedMealsTab by remember { mutableStateOf<SavedTab?>(null) }
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var showCopyFromDay by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var addMenuGroup by remember { mutableStateOf<AddMenuGroup?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<FoodEntry?>(null) }
    var selectedFoodIds by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    val selectionMode = selectedFoodIds.isNotEmpty()
    var showNutritionDetail by remember { mutableStateOf(false) }
    var showCustomWaterLog by remember { mutableStateOf(false) }
    var showFastingStart by remember { mutableStateOf(false) }
    var editingFast by remember { mutableStateOf<FastingSession?>(null) }
    var showFastingQuickActionDisabled by remember { mutableStateOf(false) }

    var showCameraCapture by remember { mutableStateOf(false) }
    var showMultiPhotoCapture by remember { mutableStateOf(false) }
    var pendingCaptureImageBytes by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var isImportingPhotos by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        val remaining = 10 - pendingCaptureImageBytes.size
        val imported = uris.take(remaining).mapNotNull { uri ->
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (imported.isNotEmpty()) {
            pendingCaptureImageBytes = (pendingCaptureImageBytes + imported).take(10)
        }
        if (pendingCaptureImageBytes.isNotEmpty()) showMultiPhotoCapture = true
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCaptureImageBytes = emptyList()
            showCameraCapture = true
        }
    }

    fun openCamera() {
        isImportingPhotos = false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingCaptureImageBytes = emptyList()
            showCameraCapture = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val barcodePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showBarcodeScanner = true
    }

    fun openBarcodeScanner() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            showBarcodeScanner = true
        } else {
            barcodePermission.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(
        quickActionRequest?.id,
        ui.analyzing,
        ui.pendingAnalysis,
        ui.error
    ) {
        val request = quickActionRequest ?: return@LaunchedEffect
        if (ui.analyzing || ui.pendingAnalysis != null || ui.error != null) return@LaunchedEffect

        showText = false
        showVoice = false
        showManual = false
        savedMealsTab = null
        showBarcodeScanner = false
        showCopyFromDay = false
        showAddMenu = false
        addMenuGroup = null
        editingEntry = null
        showNutritionDetail = false
        showCustomWaterLog = false
        showFastingStart = false
        editingFast = null
        showCameraCapture = false
        showMultiPhotoCapture = false
        vm.setSelectedDate(LocalDate.now())

        // Fasting shortcuts manage the fast itself; food shortcuts stay blocked while one is active.
        if (request.action != QuickAction.FASTING && ui.activeFast != null) {
            vm.reportFoodBlockedByFast()
            onQuickActionHandled(request.id)
            return@LaunchedEffect
        }

        when (request.action) {
            QuickAction.CAMERA -> openCamera()
            QuickAction.PHOTOS -> {
                isImportingPhotos = true
                pendingCaptureImageBytes = emptyList()
                photoPicker.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            }
            QuickAction.VOICE -> showVoice = true
            QuickAction.TEXT -> showText = true
            QuickAction.BARCODE -> openBarcodeScanner()
            QuickAction.FAVORITES -> savedMealsTab = SavedTab.FAVORITES
            QuickAction.FREQUENT -> savedMealsTab = SavedTab.FREQUENT
            QuickAction.RECENT -> savedMealsTab = SavedTab.RECENTS
            QuickAction.MANUAL -> showManual = true
            QuickAction.FASTING -> {
                when {
                    !ui.fastingTrackingEnabled -> showFastingQuickActionDisabled = true
                    ui.activeFast != null -> editingFast = ui.activeFast
                    else -> showFastingStart = true
                }
            }
        }
        onQuickActionHandled(request.id)
    }

    BackHandler(enabled = selectionMode) {
        selectedFoodIds = emptySet()
    }

    val today = LocalDate.now()
    val selectedDate = ui.date
    val isToday = selectedDate == today
    val completedFasts = remember(ui.fastingSessions, selectedDate) {
        ui.fastingSessions.filter { session ->
            session.endedAt?.atZone(ZoneId.systemDefault())?.toLocalDate() == selectedDate
        }.sortedByDescending { it.endedAt }
    }
    // Tracking preferences control new-entry UI, not persisted history. Existing
    // water and fasting logs remain visible after either tracker is disabled.
    val diaryFasts = remember(ui.activeFast, completedFasts, isToday) {
        completedFasts + if (isToday) listOfNotNull(ui.activeFast) else emptyList()
    }
    val diaryMealGroups = remember(
        ui.todayEntries,
        ui.waterEntriesToday,
        diaryFasts,
        ui.foodLogSortOrder
    ) {
        homeDiaryMealGroups(
            foodEntries = ui.todayEntries,
            waterEntries = ui.waterEntriesToday,
            fastingSessions = diaryFasts,
            sortOrder = ui.foodLogSortOrder
        )
    }

    // No topBar: the empty TopAppBar used to act as the status-bar spacer, but the
    // ad strip above this screen (TabWithBanner) now owns that inset.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = BottomNavScrollPadding + 72.dp)
        ) {
            // Week strip — verbatim port of WeekEnergyStrip in HomeComponents.swift,
            // with horizontal pagination across 53 weeks of history.
            item {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    WeekEnergyStrip(
                        selectedDate = selectedDate,
                        onSelect = { vm.setSelectedDate(it) },
                        weekStartsOnMonday = weekStartsOnMonday
                    )
                }
            }

            // Calorie hero + macros + View More — grouped so the day-swipe gesture covers only
            // this top region, not the food log below "View More". Swipe left/right to change day;
            // the horizontal-only detector lets the LazyColumn keep scrolling vertically.
            item {
                Column(
                    modifier = Modifier.pointerInput(selectedDate) {
                        var accum = 0f
                        val threshold = 80.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { accum = 0f },
                            onDragCancel = { accum = 0f },
                            onHorizontalDrag = { change, amount -> accum += amount; change.consume() },
                            onDragEnd = {
                                if (accum > threshold) {
                                    vm.setSelectedDate(selectedDate.minusDays(1))
                                } else if (accum < -threshold) {
                                    val next = selectedDate.plusDays(1)
                                    if (!next.isAfter(today)) vm.setSelectedDate(next)
                                }
                                accum = 0f
                            }
                        )
                    }
                ) {
                    Spacer(Modifier.height(32.dp))
                    CalorieHero(current = ui.caloriesToday, goal = ui.profile?.effectiveCalories ?: 2000)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ui.homeTopNutrients.take(if (ui.waterTrackingEnabled) 3 else 4).forEach { nutrient ->
                            MacroCard(
                                label = stringResource(nutrient.displayNameRes),
                                current = nutrient.current(ui.todayEntries),
                                goal = nutrient.goal(ui.profile, ui.optionalNutrientGoals).toDouble(),
                                unit = nutrient.unit,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (ui.waterTrackingEnabled) {
                            MacroCard(
                                label = stringResource(R.string.water),
                                current = ui.waterUnit.displayAmount(ui.waterTodayMl),
                                goal = ui.waterUnit.displayAmount(ui.waterDailyGoalMl),
                                unit = if (ui.waterUnit == WaterUnit.FLUID_OUNCES) " fl oz" else "ml",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.clickable { showNutritionDetail = true }) {
                            ViewMoreButton()
                        }
                    }
                }
            }

            // Unified diary. Water and fasting remain excluded from nutrition totals and sharing.
            item { Spacer(Modifier.height(8.dp)) }
            if (diaryMealGroups.isEmpty()) {
                item { SectionHeader(if (isToday) stringResource(R.string.home_todays_diary) else stringResource(R.string.home_diary)) }
                item {
                    SectionCardWrapper(isFirst = true, isLast = true) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Text(
                                stringResource(R.string.home_no_diary_entries),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            } else {
                for ((groupIndex, group) in diaryMealGroups.withIndex()) {
                    item(key = "header-${group.id}") {
                        val foodEntries = group.foodEntries
                        MealSectionHeader(
                            meal = group.meal,
                            totalCalories = group.totalCalories.takeIf { foodEntries.isNotEmpty() },
                            totalProtein = group.totalProtein,
                            totalCarbs = group.totalCarbs,
                            totalFat = group.totalFat,
                            onShare = if (foodEntries.isEmpty()) null else {
                                { MealShare.share(ctx, foodEntries) }
                            },
                            showSortMenu = groupIndex == 0,
                            sortOrder = ui.foodLogSortOrder,
                            sortMenuExpanded = showSortMenu,
                            onSortClick = { showSortMenu = true },
                            onSortDismiss = { showSortMenu = false },
                            onSortOrderSelected = { order ->
                                showSortMenu = false
                                vm.setFoodLogSortOrder(order)
                            }
                        )
                    }
                    items(group.items, key = { it.stableId }) { item ->
                        val index = group.items.indexOf(item)
                        val isFirst = index == 0
                        val isLast = index == group.items.lastIndex
                        val rowShape = sectionCardShape(isFirst, isLast)
                        SectionCardWrapper(isFirst = isFirst, isLast = isLast, transparent = true) {
                            when (item) {
                                is HomeDiaryItem.Food -> {
                                    val entry = item.entry
                                    // Tap row -> open EditFoodEntrySheet (matches iOS .onTapGesture).
                                    // Long-press -> multi-select combine. Swipe trailing -> delete;
                                    // swipe leading -> toggle favorite (disabled while selecting).
                                    val isFav = ui.isFavorite(entry)
                                    val isSelected = entry.id in selectedFoodIds
                                    SwipeableFoodRow(
                                        entry = entry,
                                        isFavorite = isFav,
                                        rowShape = rowShape,
                                        selectionMode = selectionMode,
                                        selected = isSelected,
                                        onTap = {
                                            if (selectionMode) {
                                                selectedFoodIds = if (isSelected) {
                                                    selectedFoodIds - entry.id
                                                } else {
                                                    selectedFoodIds + entry.id
                                                }
                                            } else {
                                                editingEntry = entry
                                            }
                                        },
                                        onLongPress = {
                                            selectedFoodIds = selectedFoodIds + entry.id
                                        },
                                        onDelete = { vm.deleteEntry(entry.id) },
                                        onToggleFavorite = { vm.toggleFavorite(entry) }
                                    )
                                }
                                is HomeDiaryItem.Water -> {
                                    SwipeableWaterRow(
                                        entry = item.entry,
                                        unit = ui.waterUnit,
                                        rowShape = rowShape,
                                        onDelete = { vm.deleteWater(item.entry.id) }
                                    )
                                }
                                is HomeDiaryItem.Fasting -> {
                                    SwipeableFastingRow(
                                        session = item.session,
                                        rowShape = rowShape,
                                        onTap = { editingFast = item.session },
                                        onDelete = { vm.deleteFast(item.session.id) }
                                    )
                                }
                            }
                            if (!isLast) Divider()
                        }
                    }
                }
            }
        }

        // Floating "+" add button — overlaid bottom-right and lifted above the docked
        // bottom nav bar. The parent Scaffold renders content full-screen behind the
        // bar, so the Scaffold FAB slot would sit hidden underneath it. Mirrors the iOS
        // ContentView FAB: .overlay(alignment: .bottomTrailing) + .padding(.bottom).
        if (selectionMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 100.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { selectedFoodIds = emptySet() },
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.combine_selected_count, selectedFoodIds.size),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    stringResource(R.string.combine_into_meal),
                    color = if (selectedFoodIds.size >= 2) AppColors.Calorie
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.clickable(enabled = selectedFoodIds.size >= 2) {
                        val ids = selectedFoodIds
                        vm.combineIntoMeal(ids) { combined ->
                            selectedFoodIds = emptySet()
                            if (combined != null) {
                                editingEntry = combined
                            }
                        }
                    }
                )
            }
        } else {
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
                    .clickable {
                        addMenuGroup = null
                        showAddMenu = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_add_food),
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            // Glass-styled, progressive add menu. Actions read in task order from
            // top to bottom, with the most common choice first.
            SheetGlassDropdownMenu(
                expanded = showAddMenu,
                onDismissRequest = {
                    showAddMenu = false
                    addMenuGroup = null
                },
                menuWidth = 238.dp
            ) {
                when (addMenuGroup) {
                    null -> {
                        if (ui.activeFast == null) {
                            SheetGlassDropdownMenuItem(label = stringResource(R.string.home_menu_photo_scan), leadingIcon = Icons.Filled.CameraAlt, trailingIcon = Icons.Filled.ChevronRight) { addMenuGroup = AddMenuGroup.PhotoAndScan }
                            SheetGlassDropdownMenuItem(label = stringResource(R.string.home_menu_describe_meal), leadingIcon = Icons.Filled.Edit, trailingIcon = Icons.Filled.ChevronRight) { addMenuGroup = AddMenuGroup.DescribeMeal }
                            SheetGlassDropdownMenuItem(label = stringResource(R.string.home_menu_reuse_meal), leadingIcon = Icons.Filled.Bookmark, trailingIcon = Icons.Filled.ChevronRight) { addMenuGroup = AddMenuGroup.ReuseMeal }
                        }
                        if (ui.waterTrackingEnabled) {
                            SheetGlassDropdownMenuItem(label = stringResource(R.string.water), leadingIcon = Icons.Filled.WaterDrop, trailingIcon = Icons.Filled.ChevronRight) { addMenuGroup = AddMenuGroup.Water }
                        }
                        if (ui.fastingTrackingEnabled) {
                            if (ui.activeFast == null) {
                                SheetGlassDropdownMenuItem(label = stringResource(R.string.fasting_start), leadingIcon = Icons.Filled.Timer) {
                                    showAddMenu = false
                                    showFastingStart = true
                                }
                            } else {
                                SheetGlassDropdownMenuItem(label = stringResource(R.string.fasting), leadingIcon = Icons.Filled.Timer, trailingIcon = Icons.Filled.ChevronRight) { addMenuGroup = AddMenuGroup.Fasting }
                            }
                        }
                    }

                    AddMenuGroup.PhotoAndScan -> {
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.home_menu_camera), leadingIcon = Icons.Filled.CameraAlt) { showAddMenu = false; addMenuGroup = null; openCamera() }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.home_menu_from_photos), leadingIcon = Icons.Filled.PhotoLibrary) {
                            showAddMenu = false
                            addMenuGroup = null
                            isImportingPhotos = true
                            pendingCaptureImageBytes = emptyList()
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.home_menu_barcode), leadingIcon = Icons.Filled.QrCodeScanner) { showAddMenu = false; addMenuGroup = null; openBarcodeScanner() }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.back), leadingIcon = Icons.Filled.ChevronLeft) { addMenuGroup = null }
                    }

                    AddMenuGroup.DescribeMeal -> {
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.home_menu_text_input), leadingIcon = Icons.Filled.Edit) { showAddMenu = false; addMenuGroup = null; showText = true }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.home_menu_voice), leadingIcon = Icons.Filled.Mic) { showAddMenu = false; addMenuGroup = null; showVoice = true }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.home_menu_manual_entry), leadingIcon = Icons.Filled.DriveFileRenameOutline) { showAddMenu = false; addMenuGroup = null; showManual = true }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.back), leadingIcon = Icons.Filled.ChevronLeft) { addMenuGroup = null }
                    }

                    AddMenuGroup.ReuseMeal -> {
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.saved_meals_tab_recents), leadingIcon = Icons.Filled.History) { showAddMenu = false; addMenuGroup = null; savedMealsTab = SavedTab.RECENTS }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.saved_meals_tab_frequent), leadingIcon = Icons.Filled.Repeat) { showAddMenu = false; addMenuGroup = null; savedMealsTab = SavedTab.FREQUENT }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.saved_meals_tab_favorites), leadingIcon = Icons.Filled.Favorite) { showAddMenu = false; addMenuGroup = null; savedMealsTab = SavedTab.FAVORITES }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.home_menu_copy_from_day), leadingIcon = Icons.Filled.CalendarMonth) { showAddMenu = false; addMenuGroup = null; showCopyFromDay = true }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.back), leadingIcon = Icons.Filled.ChevronLeft) { addMenuGroup = null }
                    }

                    AddMenuGroup.Water -> {
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.water_one_glass_dynamic, ui.waterUnit.format(250)), leadingIcon = Icons.Filled.WaterDrop) { showAddMenu = false; addMenuGroup = null; vm.addWater(250) }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.water_two_glasses_dynamic, ui.waterUnit.format(500)), leadingIcon = Icons.Filled.WaterDrop) { showAddMenu = false; addMenuGroup = null; vm.addWater(500) }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.water_three_glasses_dynamic, ui.waterUnit.format(750)), leadingIcon = Icons.Filled.WaterDrop) { showAddMenu = false; addMenuGroup = null; vm.addWater(750) }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.water_custom_amount), leadingIcon = Icons.Filled.DriveFileRenameOutline) { showAddMenu = false; addMenuGroup = null; showCustomWaterLog = true }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.back), leadingIcon = Icons.Filled.ChevronLeft) { addMenuGroup = null }
                    }

                    AddMenuGroup.Fasting -> {
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.fasting_end), leadingIcon = Icons.Filled.Stop) {
                            showAddMenu = false
                            addMenuGroup = null
                            vm.endFast()
                        }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.fasting_cancel), leadingIcon = Icons.Filled.Delete) {
                            showAddMenu = false
                            addMenuGroup = null
                            vm.cancelFast()
                        }
                        SheetGlassDropdownMenuItem(label = stringResource(R.string.back), leadingIcon = Icons.Filled.ChevronLeft) { addMenuGroup = null }
                    }
                }
            }
        }
        }
        }
    }

    if (showText) {
        TextInputDialog(
            onDismiss = { showText = false },
            onSubmit = { showText = false; vm.analyzeText(it) }
        )
    }

    if (showCustomWaterLog) {
        WaterCustomAmountSheet(
            unit = ui.waterUnit,
            onDismiss = { showCustomWaterLog = false },
            onAdd = vm::addWater
        )
    }

    if (showFastingStart) {
        FastingGoalDialog(
            title = stringResource(R.string.fasting_start),
            initialMinutes = ui.fastingDefaultGoalMinutes,
            confirmLabel = stringResource(R.string.fasting_start),
            onConfirm = {
                showFastingStart = false
                vm.startFast(it)
            },
            onDismiss = { showFastingStart = false }
        )
    }

    editingFast?.let { session ->
        FastingSessionDialog(
            session = session,
            onSave = {
                vm.updateFast(it)
                editingFast = null
            },
            onEndNow = {
                vm.endFast(it)
                editingFast = null
            },
            onDelete = {
                vm.deleteFast(session.id)
                editingFast = null
            },
            onDismiss = { editingFast = null }
        )
    }

    if (showVoice) {
        VoiceInputSheet(
            container = container,
            onDismiss = { showVoice = false },
            onSubmit = { showVoice = false; vm.analyzeText(it) }
        )
    }

    if (showManual) {
        ManualEntryDialog(
            onDismiss = { showManual = false },
            onSave = { name, kcal, p, c, f, fiber, meal ->
                showManual = false
                vm.saveManualEntry(name, kcal, p, c, f, fiber, meal)
            }
        )
    }

    savedMealsTab?.let { tab ->
        SavedMealsSheet(
            container = container,
            tab = tab,
            onDismiss = { savedMealsTab = null },
            // Tapping a Saved Meals row opens the FoodResultSheet for review
            // instead of logging immediately — same UX as the photo flow.
            onRelogEntry = { vm.reviewSavedMeal(it) }
        )
    }

    if (showCopyFromDay) {
        CopyFromDaySheet(
            targetDate = ui.date,
            allEntries = allEntries,
            onCopy = { entries ->
                vm.copyEntriesToSelectedDay(entries)
                showCopyFromDay = false
            },
            onDismiss = { showCopyFromDay = false }
        )
    }

    if (showBarcodeScanner) {
        BarcodeScannerSheet(
            onBarcode = { barcode ->
                showBarcodeScanner = false
                vm.lookupBarcode(barcode)
            },
            onDismiss = { showBarcodeScanner = false }
        )
    }

    if (showCameraCapture) {
        InAppCameraCaptureDialog(
            onCapture = { bytes ->
                showCameraCapture = false
                pendingCaptureImageBytes = (pendingCaptureImageBytes + bytes).take(10)
                showMultiPhotoCapture = true
            },
            onDismiss = {
                showCameraCapture = false
                if (pendingCaptureImageBytes.isNotEmpty()) {
                    showMultiPhotoCapture = true
                }
            }
        )
    }

    if (showMultiPhotoCapture && pendingCaptureImageBytes.isNotEmpty()) {
        MultiPhotoCaptureSheet(
            imageBytesList = pendingCaptureImageBytes,
            addsFromLibrary = isImportingPhotos,
            onAddPhoto = {
                if (pendingCaptureImageBytes.size < 10) {
                    if (isImportingPhotos) {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        showMultiPhotoCapture = false
                        showCameraCapture = true
                    }
                }
            },
            onRemove = { index ->
                pendingCaptureImageBytes = pendingCaptureImageBytes.filterIndexed { itemIndex, _ -> itemIndex != index }
                if (pendingCaptureImageBytes.isEmpty()) showMultiPhotoCapture = false
            },
            onAnalyze = { note, progressiveMeal ->
                val images = pendingCaptureImageBytes
                pendingCaptureImageBytes = emptyList()
                showMultiPhotoCapture = false
                vm.analyzePhotos(images, note, progressiveMeal)
            },
            onDismiss = {
                showMultiPhotoCapture = false
                pendingCaptureImageBytes = emptyList()
            }
        )
    }

    editingEntry?.let { entry ->
        EditFoodEntrySheet(
            entry = entry,
            preferGramsByDefault = ui.preferGramsByDefault,
            isFavorite = ui.isFavorite(entry),
            container = container,
            analyzeIngredientText = vm::analyzeIngredientText,
            lookupIngredientBarcode = vm::lookupIngredientBarcode,
            analyzeIngredientImage = vm::analyzeIngredientImage,
            onReprocess = { updatedNote ->
                vm.reprocessFoodEntry(entry, updatedNote)
            },
            onSave = { updated ->
                vm.updateEntry(updated)
                editingEntry = null
            },
            onToggleFavorite = { vm.toggleFavorite(entry) },
            onDelete = {
                vm.deleteEntry(entry.id)
                editingEntry = null
            },
            onDismiss = { editingEntry = null }
        )
    }

    if (showNutritionDetail) {
        NutritionDetailSheet(
            entries = ui.todayEntries,
            profile = ui.profile,
            homeTopNutrients = ui.homeTopNutrients,
            optionalGoals = ui.optionalNutrientGoals,
            waterTrackingEnabled = ui.waterTrackingEnabled,
            waterCurrentMl = ui.waterTodayMl,
            waterGoalMl = ui.waterDailyGoalMl,
            waterUnit = ui.waterUnit,
            onHomeTopNutrientsChange = vm::setHomeTopNutrients,
            onDismiss = { showNutritionDetail = false }
        )
    }

    if (ui.analyzing) AnalyzingOverlay(imageBytes = ui.pendingImageBytes)
    ui.pendingAnalysis?.let { analysis ->
        FoodResultSheet(
            analysis = analysis,
            imageBytesList = ui.pendingImageBytesList,
            preferGramsByDefault = ui.preferGramsByDefault,
            profile = ui.profile,
            dayEntries = ui.todayEntries,
            isSubmitting = ui.foodSaveInProgress,
            container = container,
            analyzeIngredientText = vm::analyzeIngredientText,
            lookupIngredientBarcode = vm::lookupIngredientBarcode,
            analyzeIngredientImage = vm::analyzeIngredientImage,
            source = ui.pendingReviewSource?.source
                ?: ui.pendingFoodSource
                ?: if (ui.pendingImageBytes != null) FoodSource.SNAP_FOOD else FoodSource.TEXT_INPUT,
            onWhatIfSuggestion = vm::suggestMealWhatIf,
            onSave = { name, grams, servingSizeIsKnown, scale, mealType, selectedServingUnit, selectedServingQuantity, editedAnalysis ->
                vm.saveAnalysis(
                    name = name,
                    servingGrams = grams,
                    servingSizeIsKnown = servingSizeIsKnown,
                    scale = scale,
                    mealType = mealType,
                    selectedServingUnit = selectedServingUnit,
                    selectedServingQuantity = selectedServingQuantity,
                    editedAnalysis = editedAnalysis
                )
            },
            onDismiss = { vm.dismissPending() }
        )
    }

    ui.error?.let { err ->
        FudGlassDialog(onDismissRequest = { vm.dismissPending() }) {
            Text(
                stringResource(
                    if (ui.errorOffersScanLabel) R.string.error_barcode_title else R.string.error_title
                ),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(err, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            FudGlassDialogActions(
                primaryText = stringResource(
                    if (ui.errorOffersScanLabel) R.string.action_scan_label else R.string.action_retry
                ),
                onPrimary = {
                    if (ui.errorOffersScanLabel) {
                        vm.dismissPending()
                        openCamera()
                    } else {
                        vm.retryPendingAnalysis()
                    }
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { vm.dismissPending() }
            )
        }
    }
    if (ui.foodLoggingBlocked) {
        FudGlassDialog(onDismissRequest = vm::dismissFoodBlocked) {
            Text(stringResource(R.string.food_logging_paused), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.food_blocked_by_active_fast),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = vm::dismissFoodBlocked,
                onDismiss = vm::dismissFoodBlocked
            )
        }
    }
    if (showFastingQuickActionDisabled) {
        FudGlassDialog(onDismissRequest = { showFastingQuickActionDisabled = false }) {
            Text(
                stringResource(R.string.fasting_quick_action_disabled_title),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.fasting_quick_action_disabled_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { showFastingQuickActionDisabled = false },
                onDismiss = { showFastingQuickActionDisabled = false }
            )
        }
    }
    if (ui.fastingOverlap) {
        FudGlassDialog(onDismissRequest = vm::dismissFastingOverlap) {
            Text(stringResource(R.string.fasting_overlap_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.fasting_overlap_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = vm::dismissFastingOverlap,
                onDismiss = vm::dismissFastingOverlap
            )
        }
    }
}

@Composable
private fun ActiveFastingRow(
    session: FastingSession,
    rowShape: RoundedCornerShape,
    onClick: () -> Unit
) {
    var now by remember(session.id) { mutableStateOf(Instant.now()) }
    LaunchedEffect(session.id) {
        while (true) {
            delay(1_000)
            now = Instant.now()
        }
    }
    val elapsed = session.durationSeconds(now)
    val progress = (elapsed.toFloat() / (session.goalMinutes * 60f)).coerceIn(0f, 1f)
    val context = LocalContext.current
    val timeFormatter = remember(context) {
        DateTimeFormatter.ofPattern(clockTimePattern(context), Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
            .border(
                0.7.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.035f))
                ),
                rowShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FastingEmojiTile()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.fasting_in_progress), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.fasting_started_format, timeFormatter.format(session.startedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatFastingDuration(elapsed),
                    color = AppColors.Calorie,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.fasting_goal_format, formatFastingDuration(session.goalMinutes.toLong() * 60)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        }
        Spacer(Modifier.height(9.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            color = AppColors.Calorie,
            trackColor = AppColors.Calorie.copy(alpha = 0.16f)
        )
    }
}

@Composable
private fun CompletedFastingRow(
    session: FastingSession,
    rowShape: RoundedCornerShape,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val timeFormatter = remember(context) {
        DateTimeFormatter.ofPattern(clockTimePattern(context), Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
            .border(
                0.7.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.035f))
                ),
                rowShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FastingEmojiTile()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.fasting_completed_format, formatFastingDuration(session.durationSeconds())),
                fontWeight = FontWeight.SemiBold
            )
            session.endedAt?.let { endedAt ->
                Text(
                    "${timeFormatter.format(session.startedAt)} – ${timeFormatter.format(endedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
            }
        }
        Text(
            stringResource(R.string.fasting_goal_format, formatFastingDuration(session.goalMinutes.toLong() * 60)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}

@Composable
private fun FastingEmojiTile() {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "⏳", fontSize = 28.sp)
    }
}

@Composable
private fun FastingGoalDialog(
    title: String,
    initialMinutes: Int,
    confirmLabel: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var hours by remember(initialMinutes) { mutableIntStateOf((initialMinutes / 60).coerceIn(1, 168)) }
    FudGlassDialog(onDismissRequest = onDismiss) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.fasting_choose_goal),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f)
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { hours = (hours - 1).coerceAtLeast(1) }) {
                Text("−", fontSize = 28.sp, color = AppColors.Calorie)
            }
            Text("$hours ${stringResource(R.string.fasting_hours)}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { hours = (hours + 1).coerceAtMost(168) }) {
                Text("+", fontSize = 28.sp, color = AppColors.Calorie)
            }
        }
        FudGlassDialogActions(
            primaryText = confirmLabel,
            onPrimary = { onConfirm(hours * 60) },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun FastingSessionDialog(
    session: FastingSession,
    onSave: (FastingSession) -> Unit,
    onEndNow: (FastingSession) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var startedAt by remember(session.id) { mutableStateOf(session.startedAt) }
    var endedAt by remember(session.id) { mutableStateOf(session.endedAt ?: Instant.now()) }
    var goalHours by remember(session.id) { mutableIntStateOf((session.goalMinutes / 60).coerceIn(1, 168)) }
    val dateTimeFormatter = remember(context) {
        DateTimeFormatter.ofPattern("MMM d, yyyy • ${clockTimePattern(context)}", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }

    FudGlassDialog(onDismissRequest = onDismiss) {
        Text(
            stringResource(if (session.isActive) R.string.fasting_active else R.string.fasting_edit),
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )
        FastingEditorRow(
            label = stringResource(R.string.fasting_started),
            value = dateTimeFormatter.format(startedAt),
            onClick = { showInstantPicker(context, startedAt) { startedAt = it } }
        )
        if (!session.isActive) {
            FastingEditorRow(
                label = stringResource(R.string.fasting_ended),
                value = dateTimeFormatter.format(endedAt),
                onClick = { showInstantPicker(context, endedAt) { endedAt = maxOf(it, startedAt) } }
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_fasting_goal), fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { goalHours = (goalHours - 1).coerceAtLeast(1) }) { Text("−", color = AppColors.Calorie) }
                Text(stringResource(R.string.settings_fasting_goal_value, goalHours), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { goalHours = (goalHours + 1).coerceAtMost(168) }) { Text("+", color = AppColors.Calorie) }
            }
        }

        if (session.isActive) {
            Button(
                onClick = {
                    onEndNow(session.copy(startedAt = startedAt, goalMinutes = goalHours * 60))
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fasting_end))
            }
        }
        TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFFF453A))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (session.isActive) R.string.fasting_cancel else R.string.fasting_delete),
                color = Color(0xFFFF453A)
            )
        }
        FudGlassDialogActions(
            primaryText = stringResource(R.string.action_save),
            onPrimary = {
                onSave(
                    session.copy(
                        startedAt = startedAt,
                        endedAt = if (session.isActive) null else maxOf(endedAt, startedAt),
                        goalMinutes = goalHours * 60
                    )
                )
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun FastingEditorRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}

private fun showInstantPicker(context: android.content.Context, initial: Instant, onPicked: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val value = initial.atZone(zone)
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onPicked(ZonedDateTime.of(year, month + 1, day, hour, minute, 0, 0, zone).toInstant())
                },
                value.hour,
                value.minute,
                android.text.format.DateFormat.is24HourFormat(context)
            ).show()
        },
        value.year,
        value.monthValue - 1,
        value.dayOfMonth
    ).show()
}

// ── Week strip (iOS port) ────────────────────────────────────────────

@Composable
private fun WeekStripSection(selectedDate: LocalDate, onSelect: (LocalDate) -> Unit) {
    val firstDow = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val weekStart = remember(selectedDate, firstDow) {
        val offset = ((selectedDate.dayOfWeek.value - firstDow.value) + 7) % 7
        selectedDate.minusDays(offset.toLong())
    }
    val today = remember { LocalDate.now() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            val isSel = date == selectedDate
            val isTdy = date == today
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(date) }
                    )
            ) {
                Text(
                    shortDay(date.dayOfWeek),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSel) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSel) AppColors.CalorieGradient
                            else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                        )
                        .then(
                            if (isTdy && !isSel) Modifier.border(1.5.dp, AppColors.Calorie.copy(alpha = 0.35f), CircleShape)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            isSel -> Color.White
                            isTdy -> AppColors.Calorie
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

private fun shortDay(dow: DayOfWeek): String = when (dow) {
    DayOfWeek.MONDAY -> "M"
    DayOfWeek.TUESDAY -> "T"
    DayOfWeek.WEDNESDAY -> "W"
    DayOfWeek.THURSDAY -> "T"
    DayOfWeek.FRIDAY -> "F"
    DayOfWeek.SATURDAY -> "S"
    DayOfWeek.SUNDAY -> "S"
}

// ── Calorie hero ─────────────────────────────────────────────────────

/**
 * Verbatim port of the calorie hero block in HomeView.body
 * (ios/calorietracker/ContentView.swift, lines ~322–362):
 *
 *   VStack(spacing: 20) {
 *     VStack(spacing: 4) {
 *       Text("\(selectedCalories)")
 *         .font(.system(size: 72, weight: .bold, design: .rounded))
 *         .foregroundStyle(LinearGradient(colors: AppColors.calorieGradient,
 *                                         startPoint: .topLeading,
 *                                         endPoint: .bottomTrailing))
 *         .contentTransition(.numericText())
 *         .animation(.snappy, value: selectedCalories)
 *       Text("of \(calorieGoal) kcal")
 *         .font(.system(.callout, design: .rounded, weight: .medium))
 *         .foregroundStyle(.tertiary)
 *     }
 *     GeometryReader { geo in
 *       ZStack(alignment: .leading) {
 *         Capsule().fill(AppColors.calorie.opacity(0.10)).frame(height: 10)
 *         Capsule().fill(LinearGradient(.leading, .trailing))
 *                  .frame(width: max(10, geo.size.width * progress), height: 10)
 *                  .shadow(color: AppColors.calorie.opacity(0.35), radius: 8, y: 3)
 *                  .animation(.spring(response: 0.8, dampingFraction: 0.75), value: selectedCalories)
 *       }
 *     }.frame(height: 10).padding(.horizontal, 24)
 *     Text("\(caloriesRemaining) left")
 *       .font(.system(.footnote, design: .rounded, weight: .medium))
 *       .foregroundStyle(.secondary)
 *   }
 *   .padding(.vertical, 20)
 */
@Composable
private fun CalorieHero(current: Int, goal: Int) {
    val ratio = if (goal > 0) (current.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val formattedCurrent = current.formattedWholeNumber()
    // Fill-from-zero on app open. lastEpoch is saveable so it survives tab switches
    // (where Home leaves/re-enters composition) — only a real app-open (new epoch)
    // replays the sweep; tab returns snap to the current value.
    val epoch = LocalLaunchFillEpoch.current
    var lastEpoch by rememberSaveable { mutableIntStateOf(0) }
    val animatedRatio = remember { Animatable(if (lastEpoch == epoch) ratio else 0f) }
    LaunchedEffect(epoch, ratio) {
        val spec = spring<Float>(dampingRatio = 0.85f, stiffness = 55f)
        if (lastEpoch != epoch) {
            animatedRatio.snapTo(0f)
            animatedRatio.animateTo(ratio, spec)
            lastEpoch = epoch
        } else {
            animatedRatio.animateTo(ratio, spec)
        }
    }
    val statusText = when {
        goal <= 0 -> "No goal"
        current < goal -> "${(goal - current).formattedWholeNumber()} left"
        current > goal -> "${(current - goal).formattedWholeNumber()} over"
        else -> "Goal reached"
    }
    val gradientColors = listOf(AppColors.CalorieStart, AppColors.CalorieEnd)
    val trackColor = AppColors.Calorie.copy(alpha = 0.12f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        // Segmented (dashed) semicircle speedometer arc. The compact 240dp dome keeps
        // the calorie readout dominant while returning more of the first meal to the
        // initial viewport. iOS uses the same 240pt / 14pt geometry.
        Canvas(
            modifier = Modifier
                .width(240.dp)
                .aspectRatio(2f)
        ) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.width - stroke)
            val topLeft = Offset(inset, inset)
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()), 0f)
            drawArc(
                color = trackColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt, pathEffect = dash)
            )
            drawArc(
                brush = Brush.horizontalGradient(gradientColors),
                startAngle = 180f,
                sweepAngle = 180f * animatedRatio.value,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt, pathEffect = dash)
            )
        }

        // Centered readout, sitting inside the dome
        Column(
            modifier = Modifier.padding(top = 39.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                "CALORIES",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                formattedCurrent,
                style = TextStyle(
                    brush = Brush.linearGradient(gradientColors),
                    fontSize = 50.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            // Flame + calorie status, mirroring iOS HStack(spacing: 5) { flame.fill (11pt) ;
            // Text(statusText) } tinted to AppColors.calorie — a pink monochrome
            // glyph, not a multicolor emoji.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    Icons.Filled.LocalFireDepartment,
                    contentDescription = null,
                    tint = AppColors.Calorie,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    statusText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Calorie
                )
            }
        }
    }
}

// ── Macro card (iOS port) ────────────────────────────────────────────

// MacroCard moved to ui/components/MacroCard.kt as a verbatim port of
// HomeComponents.swift's struct MacroCard. Imported above.


@Composable
private fun ViewMoreButton() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            stringResource(R.string.home_view_more),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.Calorie.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(5.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = AppColors.Calorie.copy(alpha = 0.6f),
            modifier = Modifier.size(11.dp)
        )
    }
}

// ── Section headers / cards / rows ──────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    // iOS Section header in .insetGrouped List renders the title in sentence case
    // (no uppercase transform), bold, ~22sp on the iOS calorie/food page. Match that.
    Text(
        title,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun MealSectionHeader(
    meal: MealType,
    totalCalories: Int? = null,
    totalProtein: Double = 0.0,
    totalCarbs: Double = 0.0,
    totalFat: Double = 0.0,
    onShare: (() -> Unit)? = null,
    showSortMenu: Boolean = false,
    sortOrder: FoodLogSortOrder = FoodLogSortOrder.STANDARD,
    sortMenuExpanded: Boolean = false,
    onSortClick: () -> Unit = {},
    onSortDismiss: () -> Unit = {},
    onSortOrderSelected: (FoodLogSortOrder) -> Unit = {}
) {
    // iOS layout: small dim icon + sentence-case label, regular weight ~17sp.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 30.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            mealIcon(meal),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(meal.displayNameRes),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
        )
        if (showSortMenu) {
            Spacer(Modifier.width(12.dp))
            Box {
                Row(
                    modifier = Modifier.clickable { onSortClick() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.SwapVert,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResource(R.string.sort),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.Calorie
                    )
                }
                SheetGlassDropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = onSortDismiss,
                    menuWidth = 226.dp
                ) {
                    for (order in FoodLogSortOrder.values()) {
                        SheetGlassDropdownMenuItem(
                            label = stringResource(order.displayNameRes),
                            selected = order == sortOrder,
                            reserveSelectionSlot = true,
                            onClick = { onSortOrderSelected(order) }
                        )
                    }
                }
            }
        }
        // Combined nutrients for this meal (issue #103: chicken + pasta + sauce = one total)
        if (totalCalories != null) {
            Spacer(Modifier.weight(1f))
            // Share the whole meal as a fudai://add-meal link (issue #107)
            if (onShare != null) {
                Icon(
                    Icons.Filled.IosShare,
                    contentDescription = stringResource(R.string.cd_share_meal),
                    tint = AppColors.Calorie,
                    modifier = Modifier
                        .clickable { onShare() }
                        .padding(4.dp)
                        .size(18.dp),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${totalCalories.formattedWholeNumber()} kcal",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Calorie
                )
                Text(
                    "${totalProtein.roundToInt()}P · ${totalCarbs.roundToInt()}C · ${totalFat.roundToInt()}F",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
            }
        }
    }
}

internal sealed interface HomeDiaryItem {
    val stableId: String
    val timestamp: Instant
    val meal: MealType

    data class Food(val entry: FoodEntry) : HomeDiaryItem {
        override val stableId: String = "food-${entry.id}"
        override val timestamp: Instant = entry.timestamp
        override val meal: MealType = entry.mealType
    }

    data class Water(val entry: WaterEntry) : HomeDiaryItem {
        override val stableId: String = "water-${entry.id}"
        override val timestamp: Instant = entry.date
        override val meal: MealType = CurrentMealSchedule.value.mealTypeAt(
            entry.date.atZone(ZoneId.systemDefault()).toLocalTime()
        )
    }

    data class Fasting(val session: FastingSession) : HomeDiaryItem {
        override val stableId: String = "fasting-${session.id}"
        override val timestamp: Instant = session.endedAt ?: session.startedAt
        override val meal: MealType = CurrentMealSchedule.value.mealTypeAt(
            timestamp.atZone(ZoneId.systemDefault()).toLocalTime()
        )
    }
}

internal data class HomeDiaryMealGroup(
    val id: String,
    val meal: MealType,
    val items: List<HomeDiaryItem>
) {
    val foodEntries: List<FoodEntry>
        get() = items.mapNotNull { (it as? HomeDiaryItem.Food)?.entry }
    val totalCalories: Int get() = foodEntries.sumOf { it.calories }
    val totalProtein: Double get() = foodEntries.sumOf { it.protein }
    val totalCarbs: Double get() = foodEntries.sumOf { it.carbs }
    val totalFat: Double get() = foodEntries.sumOf { it.fat }
}

internal fun homeDiaryMealGroups(
    foodEntries: List<FoodEntry>,
    waterEntries: List<WaterEntry>,
    fastingSessions: List<FastingSession> = emptyList(),
    sortOrder: FoodLogSortOrder
): List<HomeDiaryMealGroup> {
    val items = buildList {
        foodEntries.forEach { add(HomeDiaryItem.Food(it)) }
        waterEntries.forEach { add(HomeDiaryItem.Water(it)) }
        fastingSessions.forEach { add(HomeDiaryItem.Fasting(it)) }
    }.sortedByDescending { it.timestamp }

    return when (sortOrder) {
        FoodLogSortOrder.STANDARD -> {
            val grouped = items.groupBy { it.meal }
            listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER, MealType.SNACK, MealType.OTHER)
                .mapNotNull { meal ->
                    val mealItems = grouped[meal].orEmpty()
                    if (mealItems.isEmpty()) null else HomeDiaryMealGroup(
                        id = "standard-${meal.name}",
                        meal = meal,
                        items = mealItems
                    )
                }
        }
        FoodLogSortOrder.LATEST_MEALS_FIRST -> latestDiaryMealRuns(items)
    }
}

private fun latestDiaryMealRuns(items: List<HomeDiaryItem>): List<HomeDiaryMealGroup> {
    val groups = mutableListOf<HomeDiaryMealGroup>()
    var currentMeal: MealType? = null
    val currentItems = mutableListOf<HomeDiaryItem>()

    fun appendCurrentGroup() {
        val meal = currentMeal ?: return
        if (currentItems.isEmpty()) return
        groups += HomeDiaryMealGroup(
            id = "latest-${groups.size}-${meal.name}-${currentItems.first().stableId}",
            meal = meal,
            items = currentItems.toList()
        )
    }

    for (item in items) {
        if (item.meal == currentMeal) {
            currentItems += item
        } else {
            appendCurrentGroup()
            currentMeal = item.meal
            currentItems.clear()
            currentItems += item
        }
    }

    appendCurrentGroup()
    return groups
}

private data class FoodLogMealGroup(
    val id: String,
    val meal: MealType,
    val entries: List<FoodEntry>
) {
    // Combined nutrients for this meal group (issue #103: chicken + pasta + sauce = one total).
    val totalCalories: Int get() = entries.sumOf { it.calories }
    val totalProtein: Double get() = entries.sumOf { it.protein }
    val totalCarbs: Double get() = entries.sumOf { it.carbs }
    val totalFat: Double get() = entries.sumOf { it.fat }
}

private fun foodLogMealGroups(
    entries: List<FoodEntry>,
    sortOrder: FoodLogSortOrder
): List<FoodLogMealGroup> = when (sortOrder) {
    FoodLogSortOrder.STANDARD -> {
        val grouped = entries.groupBy { it.mealType }
        listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER, MealType.SNACK, MealType.OTHER)
            .mapNotNull { meal ->
                val mealEntries = grouped[meal].orEmpty()
                if (mealEntries.isEmpty()) null else FoodLogMealGroup(
                    id = "standard-${meal.name}",
                    meal = meal,
                    entries = mealEntries
                )
            }
    }
    FoodLogSortOrder.LATEST_MEALS_FIRST -> latestMealRuns(entries)
}

private fun latestMealRuns(entries: List<FoodEntry>): List<FoodLogMealGroup> {
    val sortedEntries = entries.sortedByDescending { it.timestamp }
    val groups = mutableListOf<FoodLogMealGroup>()
    var currentMeal: MealType? = null
    val currentEntries = mutableListOf<FoodEntry>()

    fun appendCurrentGroup() {
        val meal = currentMeal ?: return
        if (currentEntries.isEmpty()) return
        groups += FoodLogMealGroup(
            id = "latest-${groups.size}-${meal.name}-${currentEntries.first().id}",
            meal = meal,
            entries = currentEntries.toList()
        )
    }

    for (entry in sortedEntries) {
        if (entry.mealType == currentMeal) {
            currentEntries += entry
        } else {
            appendCurrentGroup()
            currentMeal = entry.mealType
            currentEntries.clear()
            currentEntries += entry
        }
    }

    appendCurrentGroup()
    return groups
}

private fun mealIcon(meal: MealType): ImageVector = when (meal) {
    MealType.BREAKFAST -> Icons.Filled.WbTwilight
    MealType.LUNCH -> Icons.Filled.WbSunny
    MealType.DINNER -> Icons.Filled.Bedtime
    MealType.SNACK -> Icons.Filled.Coffee
    MealType.OTHER -> Icons.Filled.Restaurant
}

private fun sectionCardShape(isFirst: Boolean, isLast: Boolean): RoundedCornerShape {
    // 22dp corners on the meal card matches the softer iOS look (was 14dp).
    return when {
        isFirst && isLast -> RoundedCornerShape(22.dp)
        isFirst -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        isLast -> RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
        else -> RoundedCornerShape(0.dp)
    }
}

@Composable
private fun SectionCardWrapper(
    isFirst: Boolean,
    isLast: Boolean,
    transparent: Boolean = false,
    content: @Composable () -> Unit
) {
    val shape = sectionCardShape(isFirst, isLast)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(if (transparent) Color.Transparent else MaterialTheme.colorScheme.surface)
    ) { content() }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(start = 102.dp, end = 14.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    )
}

/**
 * Swipe-to-action wrapper around FoodRow.
 *
 * - Swipe right-to-left (trailing) past threshold → delete (mirrors iOS swipeActions
 *   trailing destructive button).
 * - Swipe left-to-right (leading) past threshold → toggle favorite (mirrors iOS
 *   .swipeActions secondary heart button).
 * - Tap → open EditFoodEntrySheet (matches iOS .onTapGesture).
 *
 * The dismiss state is reset on a no-confirm swing-back so partial swipes don't
 * leave the row stuck mid-flight when the user releases short of the threshold.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableFoodRow(
    entry: FoodEntry,
    isFavorite: Boolean,
    rowShape: RoundedCornerShape,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val density = LocalDensity.current
    val favoriteTriggerPx = with(density) { 150.dp.toPx() }
    val deleteTriggerPx = with(density) { 220.dp.toPx() }
    var offsetPx by remember(entry.id) { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
    ) {
        val maxSwipePx = with(density) { maxWidth.toPx() * 0.72f }
        Box(Modifier.fillMaxWidth()) {
            if (!selectionMode) {
                SwipeBackground(offsetPx = offsetPx, isFavorite = isFavorite)
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(if (selectionMode) 0 else offsetPx.roundToInt(), 0) }
                    .then(
                        if (selectionMode) {
                            Modifier.clickable(onClick = onTap)
                        } else {
                            Modifier
                                .pointerInput(entry.id, maxSwipePx) {
                                    detectHorizontalDragGestures(
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()
                                            offsetPx = (offsetPx + dragAmount).coerceIn(-maxSwipePx, maxSwipePx)
                                        },
                                        onDragEnd = {
                                            val finalOffset = offsetPx
                                            offsetPx = 0f
                                            when {
                                                finalOffset <= -deleteTriggerPx -> onDelete()
                                                finalOffset >= favoriteTriggerPx -> onToggleFavorite()
                                            }
                                        },
                                        onDragCancel = {
                                            offsetPx = 0f
                                        }
                                    )
                                }
                                .combinedClickable(
                                    onClick = onTap,
                                    onLongClick = onLongPress
                                )
                        }
                    )
            ) {
                FoodRow(
                    entry = entry,
                    isFavorite = isFavorite,
                    rowShape = rowShape,
                    selectionMode = selectionMode,
                    selected = selected
                )
            }
        }
    }
}


@Composable
private fun SwipeableWaterRow(
    entry: WaterEntry,
    unit: WaterUnit,
    rowShape: RoundedCornerShape,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val deleteTriggerPx = with(density) { 220.dp.toPx() }
    var offsetPx by remember(entry.id) { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxSwipePx = with(density) { maxWidth.toPx() * 0.72f }
        Box(Modifier.fillMaxWidth()) {
            SwipeBackground(offsetPx = offsetPx, isFavorite = false)
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .pointerInput(entry.id, maxSwipePx) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                offsetPx = (offsetPx + dragAmount).coerceIn(-maxSwipePx, 0f)
                            },
                            onDragEnd = {
                                val shouldDelete = offsetPx <= -deleteTriggerPx
                                offsetPx = 0f
                                if (shouldDelete) onDelete()
                            },
                            onDragCancel = { offsetPx = 0f }
                        )
                    }
            ) {
                WaterLogRow(entry = entry, unit = unit, rowShape = rowShape)
            }
        }
    }
}

@Composable
private fun SwipeableFastingRow(
    session: FastingSession,
    rowShape: RoundedCornerShape,
    onTap: () -> Unit,
    onDelete: () -> Unit
) {
    if (session.isActive) {
        ActiveFastingRow(session = session, rowShape = rowShape, onClick = onTap)
        return
    }

    val density = LocalDensity.current
    val deleteTriggerPx = with(density) { 220.dp.toPx() }
    var offsetPx by remember(session.id) { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxSwipePx = with(density) { maxWidth.toPx() * 0.72f }
        Box(Modifier.fillMaxWidth()) {
            SwipeBackground(offsetPx = offsetPx, isFavorite = false)
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .pointerInput(session.id, maxSwipePx) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                offsetPx = (offsetPx + dragAmount).coerceIn(-maxSwipePx, 0f)
                            },
                            onDragEnd = {
                                val shouldDelete = offsetPx <= -deleteTriggerPx
                                offsetPx = 0f
                                if (shouldDelete) onDelete()
                            },
                            onDragCancel = { offsetPx = 0f }
                        )
                    }
            ) {
                CompletedFastingRow(session = session, rowShape = rowShape, onClick = onTap)
            }
        }
    }
}

@Composable
private fun BoxScope.SwipeBackground(offsetPx: Float, isFavorite: Boolean) {
    if (offsetPx == 0f) {
        Box(Modifier.matchParentSize())
        return
    }
    val (bg, icon, label) = if (offsetPx < 0f) {
        Triple(
            Color(0xFFD32F2F),
            Icons.Filled.Delete,
            stringResource(R.string.home_swipe_delete)
        )
    } else {
        Triple(
            AppColors.Calorie,
            if (isFavorite) Icons.Filled.FavoriteBorder else Icons.Filled.Favorite,
            if (isFavorite) stringResource(R.string.home_swipe_unfavorite) else stringResource(R.string.home_swipe_favorite)
        )
    }
    // iOS Mail-style trailing reveal: paint only the area the foreground has
    // moved out of, pinned to the matching edge. Width = absolute offset.
    val widthPx = kotlin.math.abs(offsetPx)
    val widthDp = with(LocalDensity.current) { widthPx.toDp() }
    val alignment = if (offsetPx < 0f) Alignment.CenterEnd else Alignment.CenterStart

    Box(Modifier.matchParentSize()) {
        Box(
            Modifier
                .align(alignment)
                .fillMaxHeight()
                .width(widthDp)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            if (widthPx > 24f) {
                Icon(icon, contentDescription = label, tint = Color.White)
            }
        }
    }
}

@Composable
private fun WaterLogRow(
    entry: WaterEntry,
    unit: WaterUnit,
    rowShape: RoundedCornerShape
) {
    val ctx = LocalContext.current
    val time = remember(entry.date, ctx) {
        DateTimeFormatter
            .ofPattern(clockTimePattern(ctx), Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(entry.date)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
            .background(AppColors.Calorie.copy(alpha = 0.025f))
            .border(
                0.7.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.035f),
                        AppColors.Calorie.copy(alpha = 0.07f)
                    )
                ),
                rowShape
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "💧",
                fontSize = 28.sp
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.water),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                time,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
            )
        }
        Text(
            unit.format(entry.milliliters),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Calorie
        )
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun FoodRow(
    entry: FoodEntry,
    isFavorite: Boolean = false,
    rowShape: RoundedCornerShape = RoundedCornerShape(22.dp),
    selectionMode: Boolean = false,
    selected: Boolean = false
) {
    val ctx = LocalContext.current
    val timeFmt = DateTimeFormatter.ofPattern(clockTimePattern(ctx), Locale.US).withZone(ZoneId.systemDefault())
    val container = (ctx.applicationContext as com.apoorvdarshan.calorietracker.FudAIApp).container
    val bitmap = remember(entry.imageFilename) {
        entry.imageFilename?.let { container.imageStore.loadThumbnail(it) }
    }
    // iOS layout: large 76dp square thumb · column with (Name + heart on left,
    // time on right) · pink kcal · serving · macro tag pills row.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
            .background(
                if (selected) AppColors.Calorie.copy(alpha = 0.12f)
                else AppColors.Calorie.copy(alpha = 0.025f)
            )
            .border(
                0.7.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.035f),
                        AppColors.Calorie.copy(alpha = 0.07f)
                    )
                ),
                rowShape
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (selected) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier
                    .padding(top = 28.dp)
                    .size(26.dp)
            )
        }
        Box(
            Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            when {
                bitmap != null -> androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = entry.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                )
                entry.emoji != null -> Text(entry.emoji ?: "", fontSize = 36.sp)
                else -> Icon(
                    Icons.Filled.Restaurant,
                    contentDescription = null,
                    tint = AppColors.Calorie,
                    modifier = Modifier.size(28.dp)
                )
            }
            if (entry.additionalImageFilenames.isNotEmpty()) {
                Text(
                    "+${entry.additionalImageFilenames.size}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        Column(
            Modifier.weight(1f).padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Name (+ heart) on the left, time on the top-right.
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        entry.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isFavorite) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = stringResource(R.string.cd_favorited),
                            tint = AppColors.Calorie,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Text(
                    timeFmt.format(entry.timestamp).lowercase(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }

            // Pink kcal · gray serving size.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "${entry.calories.formattedWholeNumber()} kcal",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Calorie
                )
                entry.servingSizeGrams?.takeIf { it > 0 }?.let { grams ->
                    Text("·", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    val gramsText = if (grams == grams.toInt().toDouble()) "${grams.toInt()}g"
                                    else String.format("%.1fg", grams)
                    Text(
                        gramsText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                } ?: run {
                    Text("·", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    val quantity = ServingUnitOption.formatQuantity(entry.reviewServingReference)
                    Text(
                        "$quantity ${stringResource(R.string.sheet_serving)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Macro pills (P / C / F) — tinted dark capsules with gray text.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MacroChip("P", entry.protein)
                MacroChip("C", entry.carbs)
                MacroChip("F", entry.fat)
            }
        }
    }
}

@Composable
private fun MacroChip(label: String, value: Double) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(AppColors.Calorie.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            "$label ${MacroValueFormatter.withUnit(value)}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopyFromDaySheet(
    targetDate: LocalDate,
    allEntries: List<FoodEntry>,
    onCopy: (List<FoodEntry>) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden }
    )
    var sourceDate by remember(targetDate) { mutableStateOf(targetDate.minusDays(1)) }
    var showDatePicker by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val dateFmt = remember { DateTimeFormatter.ofPattern("MMM d", Locale.US) }
    val sourceEntries = remember(allEntries, sourceDate) {
        allEntries
            .filter { it.timestamp.atZone(zone).toLocalDate() == sourceDate }
            .sortedByDescending { it.timestamp }
    }
    val groups = remember(sourceEntries) {
        foodLogMealGroups(sourceEntries, FoodLogSortOrder.STANDARD)
    }
    val targetText = if (targetDate == LocalDate.now()) "today" else targetDate.format(dateFmt)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        SheetReviewToolbar(
            title = stringResource(R.string.home_menu_copy_from_day),
            primaryLabel = if (sourceEntries.isEmpty()) stringResource(R.string.action_done) else stringResource(R.string.copy_all),
            onCancel = onDismiss,
            onPrimary = { if (sourceEntries.isEmpty()) onDismiss() else onCopy(sourceEntries) }
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SheetSectionHeader(stringResource(R.string.section_source))
                    SheetPillRow(onClick = { showDatePicker = true }) {
                        Text(stringResource(R.string.copy_from), fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text(
                            sourceDate.format(dateFmt),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.Calorie
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Foods will be copied to $targetText. Original entries stay unchanged.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }
            }

            if (sourceEntries.isEmpty()) {
                item {
                    SectionCardWrapper(isFirst = true, isLast = true) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Filled.CalendarMonth,
                                contentDescription = null,
                                tint = AppColors.Calorie.copy(alpha = 0.45f),
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                stringResource(R.string.copy_no_foods_on_day),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            } else {
                item {
                    FudGlassPrimaryButton(
                        text = pluralStringResource(R.plurals.copy_foods_to, sourceEntries.size, sourceEntries.size, targetText),
                        onClick = { onCopy(sourceEntries) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    )
                }

                groups.forEach { group ->
                    item(key = "copy-header-${group.id}") {
                        MealSectionHeader(meal = group.meal)
                    }
                    item(key = "copy-meal-${group.id}") {
                        FudGlassSurface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            cornerRadius = 18.dp,
                            padding = 0.dp
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onCopy(group.entries) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    stringResource(R.string.copy_meal_format, stringResource(group.meal.displayNameRes)),
                                    color = AppColors.Calorie,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    items(group.entries, key = { "copy-entry-${it.id}" }) { entry ->
                        val index = group.entries.indexOf(entry)
                        val isFirst = index == 0
                        val isLast = index == group.entries.lastIndex
                        val rowShape = sectionCardShape(isFirst, isLast)
                        SectionCardWrapper(isFirst = isFirst, isLast = isLast, transparent = true) {
                            Box(Modifier.clickable { onCopy(listOf(entry)) }) {
                                FoodRow(entry = entry, rowShape = rowShape)
                            }
                            if (index != group.entries.lastIndex) Divider()
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        var pickedDate by remember(sourceDate) { mutableStateOf(sourceDate) }
        FudGlassDialog(onDismissRequest = { showDatePicker = false }) {
            Text(stringResource(R.string.copy_from), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            DateWheelPicker(
                selected = pickedDate,
                onSelect = { pickedDate = it },
                minYear = LocalDate.now().year - 10,
                maxYear = LocalDate.now().year,
                modifier = Modifier.fillMaxWidth()
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.action_done),
                onPrimary = {
                    sourceDate = pickedDate
                    showDatePicker = false
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { showDatePicker = false }
            )
        }
    }
}

// ── Dialogs (unchanged styling polish) ──────────────────────────────

@Composable
private fun AnalyzingOverlay(imageBytes: ByteArray? = null) {
    // Verbatim port of ios/calorietracker/Views/AnalyzingView.swift:
    //   VStack { (image | text.magnifyingglass) → ProgressView(.large) → "Analyzing your food..." }
    //   filling the screen, opaque background, calorie-pink accents.
    val bitmap = remember(imageBytes) {
        imageBytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .size(250.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            } else {
                Icon(
                    Icons.Filled.ImageSearch,
                    contentDescription = null,
                    tint = AppColors.Calorie,
                    modifier = Modifier.size(64.dp)
                )
            }
            CircularProgressIndicator(
                color = AppColors.Calorie,
                strokeWidth = 4.dp,
                modifier = Modifier.size(40.dp)
            )
            // iOS uses two different copies depending on the input mode — photo flows
            // say "Analyzing your food..." while text/voice flows say
            // "Looking up nutrition..." (see ContentView.swift cases .analyzing /
            // .analyzingText). pendingImageBytes is the discriminator.
            Text(
                if (bitmap != null) stringResource(R.string.home_analyzing_food) else stringResource(R.string.home_looking_up_nutrition),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Calorie
            )
        }
    }
}

@Composable
private fun CameraPairTransitionOverlay() {
    var entered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "cameraPairTransitionScale"
    )

    LaunchedEffect(Unit) {
        entered = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        FudGlassSurface(
            modifier = Modifier
                .width(250.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            cornerRadius = 28.dp,
            padding = 22.dp,
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(AppColors.CalorieGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AddAPhoto,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    stringResource(R.string.home_first_photo_saved),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.home_take_second_shot),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                )
            }
        }
    }
}

@Composable
private fun AnalysisResultDialog(
    analysis: com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    FudGlassDialog(onDismissRequest = onDismiss) {
        Text("${analysis.emoji ?: "🍽"}  ${analysis.name}", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        FudGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, padding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${analysis.calories.formattedWholeNumber()} kcal",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.Calorie
                )
                Text(stringResource(R.string.macro_protein_format, MacroValueFormatter.withUnit(analysis.protein)))
                Text(stringResource(R.string.macro_carbs_format, MacroValueFormatter.withUnit(analysis.carbs)))
                Text(stringResource(R.string.macro_fat_format, MacroValueFormatter.withUnit(analysis.fat)))
                if (analysis.fiber != null || analysis.sugar != null || analysis.sodium != null) {
                    Spacer(Modifier.height(2.dp))
                    analysis.fiber?.let { Text(stringResource(R.string.nutrient_fiber_format, it.toString()), fontSize = 12.sp) }
                    analysis.sugar?.let { Text(stringResource(R.string.nutrient_sugar_format, it.toString()), fontSize = 12.sp) }
                    analysis.saturatedFat?.let { Text(stringResource(R.string.nutrient_sat_fat_format, it.toString()), fontSize = 12.sp) }
                    analysis.sodium?.let { Text(stringResource(R.string.nutrient_sodium_format, it.toString()), fontSize = 12.sp) }
                    analysis.potassium?.let { Text(stringResource(R.string.nutrient_potassium_format, it.toString()), fontSize = 12.sp) }
                    analysis.cholesterol?.let { Text(stringResource(R.string.nutrient_cholesterol_format, it.toString()), fontSize = 12.sp) }
                }
                Text(
                    stringResource(R.string.home_serving_format, analysis.servingSizeGrams.toInt()),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
        FudGlassDialogActions(
            primaryText = stringResource(R.string.action_save),
            onPrimary = onSave,
            dismissText = stringResource(R.string.action_discard),
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun TextInputDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    // Keep the input composable stable so rotating placeholder examples do not drop IME focus.
    val placeholders = listOf(
        stringResource(R.string.text_input_placeholder_1),
        stringResource(R.string.text_input_placeholder_2),
        stringResource(R.string.text_input_placeholder_3),
        stringResource(R.string.text_input_placeholder_4)
    )
    var input by remember { mutableStateOf("") }
    var placeholderIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            if (input.isEmpty()) placeholderIdx = (placeholderIdx + 1) % placeholders.size
        }
    }
    FudGlassDialog(onDismissRequest = onDismiss) {
        FudGlassTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = placeholders[placeholderIdx],
            singleLine = false,
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth()
        )
        FudGlassPrimaryButton(
            text = stringResource(R.string.action_analyze),
            onClick = { if (input.isNotBlank()) onSubmit(input.trim()) },
            enabled = input.isNotBlank()
        )
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ManualEntryDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, calories: Int, protein: Double, carbs: Double, fat: Double, fiber: Double?, mealType: MealType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var fiber by remember { mutableStateOf("") }
    var mealType by remember { mutableStateOf(MealType.currentMeal) }
    var mealMenuExpanded by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    val submissionGate = remember { FoodSubmissionGate() }

    val canSave = name.isNotBlank() && calories.toIntOrNull() != null && !isSubmitting

    FudGlassDialog(onDismissRequest = onDismiss) {
                Text(stringResource(R.string.manual_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

                FudGlassTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.manual_name_placeholder),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(stringResource(R.string.manual_calories), calories, { calories = it.filter(Char::isDigit) }, Modifier.weight(1f))
                    NumberField(stringResource(R.string.manual_protein), protein, { protein = filterDecimalInput(it) }, Modifier.weight(1f), decimal = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(stringResource(R.string.manual_carbs), carbs, { carbs = filterDecimalInput(it) }, Modifier.weight(1f), decimal = true)
                    NumberField(stringResource(R.string.manual_fat), fat, { fat = filterDecimalInput(it) }, Modifier.weight(1f), decimal = true)
                }
                NumberField(
                    "${stringResource(R.string.sheet_micro_fiber)} (${stringResource(R.string.unit_g)})",
                    fiber,
                    { fiber = filterDecimalInput(it) },
                    Modifier.fillMaxWidth(),
                    decimal = true
                )

                // Meal Type — DropdownMenu styled to match the FoodResultSheet /
                // EditFoodEntrySheet meal pickers (icon + label, pink, anchored
                // to the right cluster).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable { mealMenuExpanded = true }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.sheet_meal_type), fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Box {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                sheetMealIcon(mealType),
                                contentDescription = null,
                                tint = AppColors.Calorie,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(mealType.displayNameRes),
                                fontSize = 16.sp,
                                color = AppColors.Calorie,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        SheetGlassDropdownMenu(
                            expanded = mealMenuExpanded,
                            onDismissRequest = { mealMenuExpanded = false },
                            menuWidth = 184.dp
                        ) {
                            for (m in MealType.values()) {
                                SheetGlassDropdownMenuItem(
                                    label = stringResource(m.displayNameRes),
                                    leadingIcon = sheetMealIcon(m),
                                    selected = m == mealType,
                                    onClick = {
                                        mealType = m
                                        mealMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                FudGlassPrimaryButton(
                    text = stringResource(R.string.action_save),
                    onClick = {
                        if (!submissionGate.tryBegin()) return@FudGlassPrimaryButton
                        isSubmitting = true
                        onSave(
                            name.trim(),
                            calories.toIntOrNull() ?: 0,
                            ServingUnitOption.parseQuantity(protein) ?: 0.0,
                            ServingUnitOption.parseQuantity(carbs) ?: 0.0,
                            ServingUnitOption.parseQuantity(fat) ?: 0.0,
                            parseOptionalManualNutritionValue(fiber),
                            mealType
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = false) {
    FudGlassTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = label,
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (decimal) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Number
        ),
        modifier = modifier
    )
}

private fun filterDecimalInput(value: String): String =
    value.filter { it.isDigit() || it == '.' || it == ',' }

internal fun parseOptionalManualNutritionValue(value: String): Double? =
    ServingUnitOption.parseQuantity(value)?.takeIf { it >= 0 }
