@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apoorvdarshan.calorietracker.ui.workouts

import android.net.Uri
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
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.data.WorkoutRepository
import com.apoorvdarshan.calorietracker.models.PlannedExercise
import com.apoorvdarshan.calorietracker.models.UserExercise
import com.apoorvdarshan.calorietracker.models.UserExerciseDraft
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import kotlinx.coroutines.launch
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
    onSaved: ((String) -> Unit)? = null
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
    var previewUri by remember(existingItemId) {
        mutableStateOf(
            existingTemplate?.imagePaths?.firstOrNull()?.let { filename ->
                imageStore.file(filename).takeIf(File::isFile)?.let(Uri::fromFile)
            }
        )
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            photoBytes = bytes
            removePhoto = false
            previewUri = uri
        }
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
                    .clickable {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center
            ) {
                if (previewUri != null) {
                    AsyncImage(
                        model = previewUri,
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

            if (previewUri != null) {
                TextButton(onClick = {
                    previewUri = null
                    photoBytes = null
                    removePhoto = true
                }) { Text("Remove photo", color = colors.accent) }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = { Text("Instructions") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                minLines = 4
            )

            DropdownField("Level", level, listOf("Beginner", "Intermediate", "Expert")) { level = it }
            DropdownField("Category", category, catalog.availableCategories.ifEmpty { listOf("Strength", "Cardio") }) { category = it }
            DropdownField("Equipment", equipment, listOf("Unspecified") + catalog.availableEquipment.filter { it != "Unspecified" }) { equipment = it }
            DropdownField("Force", force, listOf("Unspecified") + catalog.availableForces.filter { it != "Unspecified" }) { force = it }
            DropdownField("Mechanic", mechanic, listOf("Unspecified") + catalog.availableMechanics.filter { it != "Unspecified" }) { mechanic = it }

            MuscleMultiSelect("Primary muscles", catalog.availablePrimaryMuscles, primaryMuscles) { primaryMuscles = it }
            MuscleMultiSelect("Secondary muscles", catalog.availableSecondaryMuscles, secondaryMuscles) { secondaryMuscles = it }

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
                    enabled = name.trim().isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }

            if (existingItemId != null && UserExercise.isUserExercise(existingItemId)) {
                HorizontalDivider(color = colors.hairline.copy(alpha = 0.35f))
                TextButton(
                    onClick = {
                        scope.launch {
                            workoutRepository.deleteUserExercise(existingItemId, imageStore)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Delete exercise", color = colors.accent) }
            }

            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun DropdownField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
    onChange: (List<String>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = workoutsColors().mutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        options.take(24).forEach { muscle ->
            val isSelected = selected.contains(muscle)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
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
