package com.apoorvdarshan.calorietracker.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.ui.components.MacroCard
import com.apoorvdarshan.calorietracker.ui.components.WeekEnergyStrip
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: AppContainer) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current

    var showText by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }

    // Holds the file the next camera capture will write to. We need this outside
    // the lambda because TakePicture only gives us a Boolean, not the bytes.
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) vm.analyzePhoto(bytes)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved: Boolean ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (saved && file != null && file.exists()) {
            val bytes = file.readBytes()
            if (bytes.isNotEmpty()) vm.analyzePhoto(bytes)
        }
    }

    fun launchCamera() {
        val dir = File(ctx.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingCaptureFile = file
        cameraLauncher.launch(uri)
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchCamera() }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    val today = LocalDate.now()
    var selectedDate by remember { mutableStateOf(today) }
    val isToday = selectedDate == today

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // iOS HomeView has .navigationTitle("") + .navigationBarTitleDisplayMode(.inline),
            // so the title is intentionally empty — only the + Menu sits in the toolbar.
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    Box(modifier = Modifier.padding(end = 8.dp)) {
                        // iOS Liquid Glass + button: dark translucent disc, WHITE plus icon,
                        // no colored border. Matches the frosted dark-gray circle in iOS 26.
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.10f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                                .clickable { showAddMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add food",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false },
                            shape = RoundedCornerShape(14.dp),
                            containerColor = MaterialTheme.colorScheme.surface,
                            shadowElevation = 12.dp,
                            modifier = Modifier
                                .border(
                                    0.5.dp,
                                    Color.White.copy(alpha = 0.12f),
                                    RoundedCornerShape(14.dp)
                                )
                        ) {
                            // Mirrors iOS HomeView toolbar Menu, in the same order:
                            // Camera, Camera + Note, Nutrition Label, From Photos,
                            // Text Input, Voice, Saved Meals.
                            // Each leadingIcon is tinted pink to match iOS .tint(AppColors.calorie).
                            MenuRow(
                                label = "Camera",
                                icon = Icons.Filled.CameraAlt
                            ) { showAddMenu = false; openCamera() }
                            MenuRow(
                                label = "Camera + Note",
                                icon = Icons.Filled.Note
                            ) { showAddMenu = false; openCamera() }
                            MenuRow(
                                label = "Nutrition Label",
                                icon = Icons.Filled.QrCodeScanner
                            ) { showAddMenu = false; openCamera() }
                            MenuRow(
                                label = "From Photos",
                                icon = Icons.Filled.PhotoLibrary
                            ) {
                                showAddMenu = false
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                            MenuRow(
                                label = "Text Input",
                                icon = Icons.Filled.Edit
                            ) { showAddMenu = false; showText = true }
                            MenuRow(
                                label = "Voice",
                                icon = Icons.Filled.Mic
                            ) { showAddMenu = false; showVoice = true }
                            MenuRow(
                                label = "Saved Meals",
                                icon = Icons.Filled.Bookmark
                            ) { showAddMenu = false; showSaved = true }
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 32.dp)
        ) {
            // Week strip — verbatim port of WeekEnergyStrip in HomeComponents.swift,
            // with horizontal pagination across 53 weeks of history.
            item {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    WeekEnergyStrip(
                        selectedDate = selectedDate,
                        onSelect = { selectedDate = it }
                    )
                }
            }

            // Calorie hero
            item { Spacer(Modifier.height(4.dp)) }
            item { CalorieHero(current = ui.caloriesToday, goal = ui.profile?.effectiveCalories ?: 2000) }

            // Macro trio
            item { Spacer(Modifier.height(20.dp)) }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    MacroCard(label = "Protein", current = ui.proteinToday, goal = ui.profile?.effectiveProtein ?: 150, modifier = Modifier.weight(1f))
                    MacroCard(label = "Carbs", current = ui.carbsToday, goal = ui.profile?.effectiveCarbs ?: 220, modifier = Modifier.weight(1f))
                    MacroCard(label = "Fat", current = ui.fatToday, goal = ui.profile?.effectiveFat ?: 70, modifier = Modifier.weight(1f))
                }
            }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ViewMoreButton()
                }
            }

            // Food log
            item { Spacer(Modifier.height(8.dp)) }
            val grouped = ui.todayEntries.groupBy { it.mealType }
            val ordered = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER, MealType.SNACK, MealType.OTHER)
            val populated = ordered.filter { (grouped[it] ?: emptyList()).isNotEmpty() }

            if (populated.isEmpty()) {
                item { SectionHeader("Today's Food") }
                item {
                    SectionCardWrapper(isFirst = true, isLast = true) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Text(
                                "No foods logged",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            } else {
                for (meal in populated) {
                    val entries = grouped[meal] ?: emptyList()
                    item { MealSectionHeader(meal = meal) }
                    items(entries, key = { it.id }) { entry ->
                        val index = entries.indexOf(entry)
                        SectionCardWrapper(isFirst = index == 0, isLast = index == entries.lastIndex) {
                            FoodRow(entry = entry, onDelete = { vm.deleteEntry(entry.id) })
                            if (index != entries.lastIndex) Divider()
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

    if (showVoice) {
        VoiceInputSheet(
            container = container,
            onDismiss = { showVoice = false },
            onSubmit = { showVoice = false; vm.analyzeText(it) }
        )
    }

    if (showSaved) {
        SavedMealsSheet(
            container = container,
            onDismiss = { showSaved = false },
            onRelogEntry = { vm.relogMeal(it) }
        )
    }

    if (ui.analyzing) AnalyzingOverlay()

    ui.pendingAnalysis?.let { analysis ->
        AnalysisResultDialog(
            analysis = analysis,
            onSave = { vm.saveAnalysis() },
            onDismiss = { vm.dismissPending() }
        )
    }

    ui.error?.let { err ->
        AlertDialog(
            onDismissRequest = { vm.dismissPending() },
            title = { Text("Something went wrong") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { vm.dismissPending() }) { Text("OK") } }
        )
    }
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
    val animatedRatio by animateFloatAsState(
        targetValue = ratio,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 60f), // response 0.8 ≈ stiffness 60
        label = "calorieProgress"
    )
    val remaining = maxOf(0, goal - current)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp) // outer VStack(spacing: 20)
    ) {
        // Inner VStack(spacing: 4)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 72pt bold rounded with linear gradient .topLeading -> .bottomTrailing
            Text(
                "$current",
                style = TextStyle(
                    brush = Brush.linearGradient(
                        colors = listOf(AppColors.CalorieStart, AppColors.CalorieEnd)
                        // Compose's default linearGradient is top-left to bottom-right which matches iOS's .topLeading/.bottomTrailing.
                    ),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            // "of N kcal" .font(.callout) = 16sp .foregroundStyle(.tertiary) = ~0.3 alpha
            Text(
                "of $goal kcal",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }

        // Progress capsule 10dp tall, padding(.horizontal, 24)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(10.dp)
        ) {
            val w = maxWidth
            // Track Capsule.fill(Calorie.opacity(0.10))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(AppColors.Calorie.copy(alpha = 0.10f))
            )
            // Foreground Capsule with shadow Calorie*0.35, r=8, y=3
            val barWidth = (w * animatedRatio).coerceAtLeast(10.dp)
            Box(
                Modifier
                    .width(barWidth)
                    .height(10.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = AppColors.Calorie.copy(alpha = 0.35f),
                        spotColor = AppColors.Calorie.copy(alpha = 0.35f)
                    )
                    .clip(CircleShape)
                    .background(AppColors.CalorieGradient)
            )
        }

        // "N left" .font(.footnote) = 13sp .foregroundStyle(.secondary) = ~0.6 alpha
        Text(
            "$remaining left",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

// ── Macro card (iOS port) ────────────────────────────────────────────

// MacroCard moved to ui/components/MacroCard.kt as a verbatim port of
// HomeComponents.swift's struct MacroCard. Imported above.

/**
 * iOS-styled menu row used inside the + DropdownMenu. Pink leading icon,
 * 17sp body label, slightly larger row height than Material default to
 * match iOS Menu touch targets.
 */
@Composable
private fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        leadingIcon = {
            // iOS Menu uses white system-icon glyphs, not tinted accent — match that.
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        },
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun ViewMoreButton() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            "View More",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.Calorie.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = AppColors.Calorie.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp)
        )
    }
}

// ── Section headers / cards / rows ──────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun MealSectionHeader(meal: MealType) {
    Row(
        Modifier.padding(start = 32.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            mealIcon(meal),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            meal.displayName.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            letterSpacing = 0.8.sp
        )
    }
}

private fun mealIcon(meal: MealType): ImageVector = when (meal) {
    MealType.BREAKFAST -> Icons.Filled.LightMode
    MealType.LUNCH -> Icons.Filled.LightMode
    MealType.DINNER -> Icons.Filled.Nightlight
    MealType.SNACK -> Icons.Filled.Coffee
    MealType.OTHER -> Icons.Filled.Restaurant
}

@Composable
private fun SectionCardWrapper(isFirst: Boolean, isLast: Boolean, content: @Composable () -> Unit) {
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(14.dp)
        isFirst -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
        isLast -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
        else -> RoundedCornerShape(0.dp)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
    ) { content() }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(start = 60.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    )
}

@Composable
private fun FoodRow(entry: FoodEntry, onDelete: () -> Unit) {
    val timeFmt = DateTimeFormatter.ofPattern("h:mma", Locale.US).withZone(ZoneId.systemDefault())
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(AppColors.Calorie.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Text(entry.emoji ?: "🍽", fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${entry.calories} kcal · P${entry.protein} · C${entry.carbs} · F${entry.fat}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1
            )
        }
        Text(
            timeFmt.format(entry.timestamp).lowercase(),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

// ── Dialogs (unchanged styling polish) ──────────────────────────────

@Composable
private fun AnalyzingOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = AppColors.Calorie)
                Spacer(Modifier.height(16.dp))
                Text("Analyzing…", fontSize = 15.sp, fontWeight = FontWeight.Medium)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("${analysis.emoji ?: "🍽"}  ${analysis.name}", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${analysis.calories} kcal", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Protein: ${analysis.protein}g")
                Text("Carbs: ${analysis.carbs}g")
                Text("Fat: ${analysis.fat}g")
                if (analysis.fiber != null || analysis.sugar != null || analysis.sodium != null) {
                    Spacer(Modifier.height(4.dp))
                    analysis.fiber?.let { Text("Fiber: ${it}g", fontSize = 12.sp) }
                    analysis.sugar?.let { Text("Sugar: ${it}g", fontSize = 12.sp) }
                    analysis.saturatedFat?.let { Text("Sat fat: ${it}g", fontSize = 12.sp) }
                    analysis.sodium?.let { Text("Sodium: ${it}mg", fontSize = 12.sp) }
                    analysis.potassium?.let { Text("Potassium: ${it}mg", fontSize = 12.sp) }
                    analysis.cholesterol?.let { Text("Cholesterol: ${it}mg", fontSize = 12.sp) }
                }
                Text(
                    "Serving: ~${analysis.servingSizeGrams.toInt()}g",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        },
        confirmButton = { Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie)) { Text("Save", color = Color.White) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Discard") } }
    )
}

@Composable
private fun TextInputDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Describe your meal", fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("e.g. 2 eggs, toast, small OJ") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { if (input.isNotBlank()) onSubmit(input) },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie)
            ) { Text("Analyze", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
