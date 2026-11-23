@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apoorvdarshan.calorietracker.ui.workouts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.data.WorkoutRepository
import com.apoorvdarshan.calorietracker.models.PlannedExercise
import com.apoorvdarshan.calorietracker.models.UserExercise
import com.apoorvdarshan.calorietracker.models.UserExerciseDraft
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.ai.UserExerciseAiFill
import com.apoorvdarshan.calorietracker.ui.components.InAppCameraCaptureDialog
import com.apoorvdarshan.calorietracker.ui.hosted.findActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun UserExerciseEditorSheet(
    visible: Boolean,
    existingItemId: String?,
    catalog: ExerciseRepository,
    workoutRepository: WorkoutRepository,
    imageStore: FoodImageStore,
    existingTemplate: PlannedExercise?,
    onDismiss: () -> Unit,
    onSaved: ((String) -> Unit)? = null,
    onDeleted: (() -> Unit)? = null,
    /** Identifies the exercise in a photo for "Fill with AI"; null hides the button. */
    fillWithAi: (suspend (ByteArray, UserExerciseAiFill.Options) -> UserExerciseAiFill.Suggestion)? = null
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = workoutsColors()

    var name by remember(existingItemId) { mutableStateOf(existingTemplate?.name.orEmpty()) }
    var instructions by remember(existingItemId) {
        mutableStateOf(existingTemplate?.instructions?.joinToString("\n").orEmpty())
    }
    var level by remember(existingItemId) { mutableStateOf(existingTemplate?.level ?: "Beginner") }
    var category by remember(existingItemId) { mutableStateOf(existingTemplate?.category ?: "Strength") }
    var equipment by remember(existingItemId) { mutableStateOf(existingTemplate?.equipment ?: "Unspecified") }
    var force by remember(existingItemId) { mutableStateOf(existingTemplate?.force ?: "Unspecified") }
    var mechanic by remember(existingItemId) { mutableStateOf(existingTemplate?.mechanic ?: "Unspecified") }
    var primaryMuscles by remember(existingItemId) { mutableStateOf(existingTemplate?.primaryMuscles.orEmpty()) }
    var secondaryMuscles by remember(existingItemId) { mutableStateOf(existingTemplate?.secondaryMuscles.orEmpty()) }
    var photoBytes by remember(existingItemId) { mutableStateOf<ByteArray?>(null) }
    var removePhoto by remember(existingItemId) { mutableStateOf(false) }
    // Coil model for the photo preview: a Uri (stored or picked photo) or camera JPEG bytes.
    var previewModel by remember(existingItemId) {
        mutableStateOf<Any?>(
            existingTemplate?.imagePaths?.firstOrNull()?.let { filename ->
                imageStore.file(filename).takeIf(File::isFile)?.let(Uri::fromFile)
            }
        )
    }
    var isPhotoLoading by remember(existingItemId) { mutableStateOf(false) }
    var photoLoadGeneration by remember(existingItemId) { mutableIntStateOf(0) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var isFillingWithAi by remember(existingItemId) { mutableStateOf(false) }
    var aiFillError by remember(existingItemId) { mutableStateOf<String?>(null) }
    var aiFillGeneration by remember(existingItemId) { mutableIntStateOf(0) }
    var aiFillJob by remember(existingItemId) { mutableStateOf<Job?>(null) }
    val genericAiError = stringResource(R.string.ai_error_generic)
    val categoryOptions = remember(catalog) { catalog.availableCategories.ifEmpty { listOf("Strength", "Cardio") } }
    val equipmentOptions = remember(catalog) {
        listOf("Unspecified") + catalog.availableEquipment.filter { it != "Unspecified" }
    }
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var showCameraSettingsPrompt by remember { mutableStateOf(false) }
    val activity = remember(context) { context.findActivity() }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            showCamera = true
        } else if (activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        ) {
            // Android stops showing the prompt after repeated denials, so "Take photo"
            // would otherwise do nothing; point to the system setting instead.
            showCameraSettingsPrompt = true
        }
    }

    // A new or removed photo makes a running fill and its error stale.
    fun cancelAiFill() {
        aiFillGeneration += 1
        aiFillJob?.cancel()
        aiFillJob = null
        isFillingWithAi = false
        aiFillError = null
    }

    // Prefills the form from the photo; the user reviews the fields and saves as usual.
    fun startAiFill() {
        val fill = fillWithAi ?: return
        if (isFillingWithAi) return
        val pendingBytes = photoBytes
        val storedPhoto = previewModel as? Uri
        val options = UserExerciseAiFill.Options(
            categories = categoryOptions,
            equipment = equipmentOptions.filter { it != "Unspecified" },
            primaryMuscles = catalog.availablePrimaryMuscles,
            secondaryMuscles = catalog.availableSecondaryMuscles
        )
        val generation = aiFillGeneration + 1
        aiFillGeneration = generation
        isFillingWithAi = true
        aiFillError = null
        aiFillJob = scope.launch {
            try {
                val bytes = pendingBytes ?: storedPhoto?.let { uri ->
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                } ?: throw AiError.ImageConversionFailed
                val suggestion = fill(bytes, options)
                if (generation != aiFillGeneration) return@launch
                val filled = UserExerciseAiFill.apply(
                    suggestion,
                    UserExerciseDraft(
                        name = name,
                        instructions = instructions,
                        category = category,
                        equipment = equipment,
                        primaryMuscles = primaryMuscles,
                        secondaryMuscles = secondaryMuscles
                    )
                )
                name = filled.name
                instructions = filled.instructions
                category = filled.category
                equipment = filled.equipment
                primaryMuscles = filled.primaryMuscles
                secondaryMuscles = filled.secondaryMuscles
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == aiFillGeneration) {
                    aiFillError = (e as? AiError)?.userMessage(context) ?: genericAiError
                }
            } finally {
                if (generation == aiFillGeneration) {
                    isFillingWithAi = false
                    aiFillJob = null
                }
            }
        }
    }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showCamera = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val generation = photoLoadGeneration + 1
        photoLoadGeneration = generation
        scope.launch {
            isPhotoLoading = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().takeIf { it.size <= 20 * 1024 * 1024 }
                    }
                }
                if (generation != photoLoadGeneration) return@launch
                if (bytes != null) {
                    cancelAiFill()
                    photoBytes = bytes
                    removePhoto = false
                    previewModel = uri
                }
            } finally {
                if (generation == photoLoadGeneration) {
                    isPhotoLoading = false
                }
            }
        }
    }

    if (showCamera) {
        InAppCameraCaptureDialog(
            onCapture = { bytes ->
                showCamera = false
                cancelAiFill()
                // Supersedes any library photo still loading; saving downsizes it like picked photos.
                photoLoadGeneration += 1
                photoBytes = bytes
                removePhoto = false
                previewModel = bytes
                isPhotoLoading = false
            },
            onDismiss = { showCamera = false }
        )
    }

    if (showCameraSettingsPrompt) {
        AlertDialog(
            onDismissRequest = { showCameraSettingsPrompt = false },
            title = { Text(stringResource(R.string.workout_exercise_camera_denied_title)) },
            text = { Text(stringResource(R.string.workout_exercise_camera_denied_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showCameraSettingsPrompt = false
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) {
                    Text(stringResource(R.string.voice_action_open_settings), color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCameraSettingsPrompt = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete this custom exercise?") },
            text = {
                Text("This removes the exercise from your library. Logged workouts keep their history.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    scope.launch {
                        workoutRepository.deleteUserExercise(existingItemId!!, imageStore)
                        onDeleted?.invoke()
                        onDismiss()
                    }
                }) {
                    Text("Delete", color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.background
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (existingItemId == null) "Create exercise" else "Edit exercise",
                color = colors.charcoal,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panel.copy(alpha = 0.35f))
                    .border(0.5.dp, colors.hairline.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .clickable(enabled = !isPhotoLoading && !isFillingWithAi) {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center
            ) {
                if (previewModel != null) {
                    AsyncImage(
                        model = previewModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.FitnessCenter, null, tint = colors.charcoal, modifier = Modifier.size(32.dp))
                        Text("Add photo (optional)", color = colors.mutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (hasCamera || previewModel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasCamera) {
                        TextButton(onClick = { openCamera() }, enabled = !isPhotoLoading && !isFillingWithAi) {
                            Icon(Icons.Filled.CameraAlt, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.workout_exercise_take_photo), color = colors.accent)
                        }
                    }
                    if (previewModel != null) {
                        TextButton(
                            onClick = {
                                cancelAiFill()
                                photoLoadGeneration += 1
                                previewModel = null
                                photoBytes = null
                                removePhoto = true
                                isPhotoLoading = false
                            },
                            enabled = !isPhotoLoading && !isFillingWithAi
                        ) { Text("Remove photo", color = colors.accent) }
                    }
                }
            }

            if (fillWithAi != null && previewModel != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { startAiFill() }, enabled = !isPhotoLoading && !isFillingWithAi) {
                        if (isFillingWithAi) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(Icons.Filled.AutoAwesome, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.workout_exercise_ai_fill), color = colors.accent)
                    }
                    aiFillError?.let { message ->
                        // A polite live region, so TalkBack reads a failed fill.
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    Text(
                        stringResource(R.string.workout_exercise_ai_fill_hint),
                        color = colors.mutedText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            // The fields "Fill with AI" writes are locked while it runs, so its
            // reply never overwrites an edit made in the meantime.
            val fieldsEnabled = !isFillingWithAi

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                enabled = fieldsEnabled,
                singleLine = true
            )

            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = { Text("Instructions") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                enabled = fieldsEnabled,
                minLines = 4
            )

            DropdownField("Level", level, listOf("Beginner", "Intermediate", "Expert")) { level = it }
            DropdownField("Category", category, categoryOptions, enabled = fieldsEnabled) { category = it }
            DropdownField("Equipment", equipment, equipmentOptions, enabled = fieldsEnabled) { equipment = it }
            DropdownField("Force", force, listOf("Unspecified") + catalog.availableForces.filter { it != "Unspecified" }) { force = it }
            DropdownField("Mechanic", mechanic, listOf("Unspecified") + catalog.availableMechanics.filter { it != "Unspecified" }) { mechanic = it }

            MuscleMultiSelect("Primary muscles", catalog.availablePrimaryMuscles, primaryMuscles, enabled = fieldsEnabled) {
                primaryMuscles = it
            }
            MuscleMultiSelect("Secondary muscles", catalog.availableSecondaryMuscles, secondaryMuscles, enabled = fieldsEnabled) {
                secondaryMuscles = it
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        scope.launch {
                            val draft = UserExerciseDraft(
                                name = name,
                                instructions = instructions,
                                level = level,
                                force = force,
                                mechanic = mechanic,
                                category = category,
                                equipment = equipment,
                                primaryMuscles = primaryMuscles,
                                secondaryMuscles = secondaryMuscles,
                                photoBytes = photoBytes,
                                removePhoto = removePhoto
                            )
                            val saved = workoutRepository.saveUserExercise(draft, imageStore, existingItemId)
                            saved?.let { onSaved?.invoke(it.id) }
                            onDismiss()
                        }
                    },
                    // Waits for a running AI fill, so its suggestions are never dropped.
                    enabled = name.trim().isNotEmpty() && !isPhotoLoading && !isFillingWithAi,
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }

            if (existingItemId != null && UserExercise.isUserExercise(existingItemId)) {
                HorizontalDivider(color = colors.hairline.copy(alpha = 0.35f))
                TextButton(
                    onClick = { showDeleteConfirmation = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Delete exercise", color = colors.accent) }
            }

            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it && enabled }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            modifier = Modifier
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MuscleMultiSelect(
    label: String,
    options: List<String>,
    selected: List<String>,
    enabled: Boolean = true,
    onChange: (List<String>) -> Unit
) {
    Column(
        Modifier.alpha(if (enabled) 1f else 0.38f),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = workoutsColors().mutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        options.take(24).forEach { muscle ->
            val isSelected = selected.contains(muscle)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) {
                        onChange(if (isSelected) selected - muscle else selected + muscle)
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(muscle, color = workoutsColors().charcoal, fontSize = 14.sp)
                if (isSelected) Text("✓", color = workoutsColors().accent, fontWeight = FontWeight.Bold)
            }
        }
    }
}
