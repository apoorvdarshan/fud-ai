package com.apoorvdarshan.calorietracker.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.toMealIngredient
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassPrimaryButton
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextField
import com.apoorvdarshan.calorietracker.ui.components.InAppCameraCaptureDialog
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.util.UUID

/**
 * Add-ingredient plus menu for review/edit sheets. Analysis and saved-meal
 * picks append a [MealIngredient] to the open draft — they do not create a
 * second diary row.
 */
@Composable
internal fun IngredientIntakeSection(
    container: AppContainer,
    analyzeText: suspend (String) -> FoodAnalysis,
    lookupBarcode: suspend (String) -> FoodAnalysis,
    analyzeImage: suspend (ByteArray) -> FoodAnalysis,
    onIngredient: (MealIngredient) -> Unit,
    onManual: () -> Unit
) {
    val ctx = LocalContext.current
    val sessionId = rememberSaveable { UUID.randomUUID().toString() }
    val intake: IngredientIntakeViewModel = viewModel(key = "ingredient-intake-$sessionId")
    val intakeState by intake.state.collectAsStateWithLifecycle()
    val currentOnIngredient by rememberUpdatedState(onIngredient)
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = ctx.ingredientIntakeActivity()
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showText by rememberSaveable { mutableStateOf(false) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var showBarcode by rememberSaveable { mutableStateOf(false) }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var savedTab by rememberSaveable { mutableStateOf<SavedTab?>(null) }

    LaunchedEffect(intake, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            intake.state.collect { state ->
                state.result?.let { ingredient ->
                    currentOnIngredient(ingredient)
                    intake.consumeResult()
                }
            }
        }
    }
    DisposableEffect(intake, activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) intake.discard()
        }
    }

    fun runAnalysis(imageBytes: ByteArray? = null, block: suspend () -> FoodAnalysis) {
        intake.analyze(imageBytes, container.imageStore, ctx.getString(R.string.error_analysis_failed), block)
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes != null) runAnalysis(imageBytes = bytes) { analyzeImage(bytes) }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCamera = true
    }

    val barcodePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showBarcode = true
    }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            showCamera = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun openBarcode() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            showBarcode = true
        } else {
            barcodePermission.launch(Manifest.permission.CAMERA)
        }
    }

    Box(Modifier.fillMaxWidth()) {
        MealIngredientsAddRow(onClick = { menuExpanded = true })
        SheetGlassDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            menuWidth = 238.dp
        ) {
            SheetGlassDropdownMenuItem(
                label = stringResource(R.string.home_menu_camera),
                leadingIcon = Icons.Filled.CameraAlt
            ) {
                menuExpanded = false
                openCamera()
            }
            SheetGlassDropdownMenuItem(
                label = stringResource(R.string.home_menu_from_photos),
                leadingIcon = Icons.Filled.PhotoLibrary
            ) {
                menuExpanded = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            SheetGlassDropdownMenuItem(
                label = stringResource(R.string.home_menu_barcode),
                leadingIcon = Icons.Filled.QrCodeScanner
            ) {
                menuExpanded = false
                openBarcode()
            }
            SheetGlassDropdownMenuItem(
                label = stringResource(R.string.home_menu_text_input),
                leadingIcon = Icons.Filled.Edit
            ) {
                menuExpanded = false
                showText = true
            }
            SheetGlassDropdownMenuItem(
                label = stringResource(R.string.home_menu_voice),
                leadingIcon = Icons.Filled.Mic
            ) {
                menuExpanded = false
                showVoice = true
            }
            SheetGlassDropdownMenuItem(
                label = stringResource(R.string.home_menu_manual_entry),
                leadingIcon = Icons.Filled.DriveFileRenameOutline
            ) {
                menuExpanded = false
                onManual()
            }
            SheetGlassDropdownMenuItem(
                label = stringResource(R.string.saved_meals_tab_favorites),
                leadingIcon = Icons.Filled.Favorite
            ) {
                menuExpanded = false
                savedTab = SavedTab.FAVORITES
            }
            SheetGlassDropdownMenuItem(
                label = stringResource(R.string.saved_meals_tab_frequent),
                leadingIcon = Icons.Filled.Repeat
            ) {
                menuExpanded = false
                savedTab = SavedTab.FREQUENT
            }
            SheetGlassDropdownMenuItem(
                label = stringResource(R.string.saved_meals_tab_recents),
                leadingIcon = Icons.Filled.History
            ) {
                menuExpanded = false
                savedTab = SavedTab.RECENTS
            }
        }
    }

    if (intakeState.busy) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = AppColors.Calorie)
        }
    }

    intakeState.error?.let { message ->
        FudGlassDialog(onDismissRequest = { intake.dismissError() }) {
            Text(message, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = { intake.dismissError() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_ok))
            }
        }
    }

    if (showText) {
        IngredientTextDialog(
            onDismiss = { showText = false },
            onSubmit = { text ->
                showText = false
                runAnalysis { analyzeText(text) }
            }
        )
    }

    if (showVoice) {
        VoiceInputSheet(
            container = container,
            onDismiss = { showVoice = false },
            onSubmit = { text ->
                showVoice = false
                runAnalysis { analyzeText(text) }
            }
        )
    }

    if (showBarcode) {
        BarcodeScannerSheet(
            onBarcode = { code ->
                showBarcode = false
                runAnalysis { lookupBarcode(code) }
            },
            onDismiss = { showBarcode = false }
        )
    }

    if (showCamera) {
        InAppCameraCaptureDialog(
            onCapture = { bytes ->
                showCamera = false
                runAnalysis(imageBytes = bytes) { analyzeImage(bytes) }
            },
            onDismiss = { showCamera = false }
        )
    }

    savedTab?.let { tab ->
        SavedMealsSheet(
            container = container,
            tab = tab,
            onDismiss = { savedTab = null },
            onRelogEntry = { entry ->
                savedTab = null
                onIngredient(entry.toMealIngredient())
            }
        )
    }
}

@Composable
private fun IngredientTextDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    FudGlassDialog(onDismissRequest = onDismiss) {
        FudGlassTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = stringResource(R.string.text_input_placeholder_1),
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
            Text(
                stringResource(R.string.action_cancel),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

private tailrec fun Context.ingredientIntakeActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.ingredientIntakeActivity()
    else -> null
}
