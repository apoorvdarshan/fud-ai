package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.apoorvdarshan.calorietracker.R
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.data.ExerciseItem
import com.apoorvdarshan.calorietracker.models.*
import com.apoorvdarshan.calorietracker.ui.home.VoiceInputSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutTextSheet(
    container: AppContainer,
    library: List<ExerciseItem>,
    selectedDate: LocalDate,
    unit: WorkoutWeightUnit,
    bodyWeightKg: Double,
    rpeScale: WorkoutRpeScale,
    onAdded: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    analyzeWorkout: suspend (String) -> WorkoutTextDraft = {
        container.foodAnalysis.analyzeWorkout(it, selectedDate, unit, library)
    },
    saveWorkout: suspend (WorkoutTextDraft) -> Unit = { container.workoutRepository.addTextWorkout(it, library) }
) {
    var description by rememberSaveable { mutableStateOf("") }
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = remember(draftJson) { draftJson?.let { Json.decodeFromString<WorkoutTextDraft>(it) } }
    var voice by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dismiss = { if (!saving) onDismiss() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !saving })

    fun update(value: WorkoutTextDraft) { draftJson = Json.encodeToString(value); error = null }

    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workout_text_title), style = MaterialTheme.typography.headlineSmall)
            if (draft == null) {
                Text(stringResource(R.string.workout_text_intro))
                OutlinedTextField(value = description, onValueChange = { description = it.take(4000); error = null },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 4,
                    label = { Text(stringResource(R.string.workout_text_description)) },
                    placeholder = { Text(stringResource(R.string.workout_text_example)) })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { voice = true }, enabled = !busy) { Text(stringResource(R.string.workout_text_voice)) }
                    Button(onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                update(analyzeWorkout(description.trim()))
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.localizedMessage ?: "Could not prepare the workout. Try again." }
                            finally { busy = false }
                        }
                    }, enabled = description.isNotBlank() && !busy) { Text(stringResource(if (busy) R.string.workout_text_preparing else R.string.workout_text_preview)) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.workout_text_review), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = draft.date, onValueChange = { update(draft.copy(date = it)) },
                    enabled = !saving, label = { Text(stringResource(R.string.workout_text_date)) }, singleLine = true)
                draft.exercises.forEach { exercise ->
                    key(exercise.id) {
                        fun edit(value: WorkoutTextExercise) = update(draft.copy(exercises = draft.exercises.map {
                            if (it.id == value.id) value else it
                        }))
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(exercise.name, style = MaterialTheme.typography.titleMedium)
                                if (exercise.exerciseId == null) Text(stringResource(R.string.workout_text_custom),
                                    style = MaterialTheme.typography.bodySmall)
                                if (exercise.minutes.isNotBlank() || exercise.sets.isEmpty()) {
                                    OutlinedTextField(value = exercise.minutes, onValueChange = { edit(exercise.copy(minutes = it)) },
                                        label = { Text(stringResource(R.string.workout_text_minutes)) }, enabled = !saving, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                }
                                if (exercise.minutes.isNotBlank()) {
                                    Text(stringResource(R.string.workout_text_effort))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("light" to R.string.workout_text_light, "moderate" to R.string.workout_text_moderate,
                                            "vigorous" to R.string.workout_text_vigorous).forEach { (value, label) ->
                                            FilterChip(selected = exercise.intensity == value,
                                                onClick = { edit(exercise.copy(intensity = value)) }, enabled = !saving,
                                                label = { Text(stringResource(label)) })
                                        }
                                    }
                                }
                                if (exercise.sets.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("kg", "lbs").forEach { option ->
                                            FilterChip(selected = exercise.unit == option, onClick = { edit(exercise.copy(unit = option)) },
                                                enabled = !saving, label = { Text(option) })
                                        }
                                    }
                                    exercise.sets.forEachIndexed { index, set ->
                                        key(set.id) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedTextField(value = set.weight, onValueChange = { value ->
                                                    edit(exercise.copy(sets = exercise.sets.map { if (it.id == set.id) it.copy(weight = value) else it }))
                                                }, label = { Text(stringResource(R.string.workout_text_set_weight, index + 1, exercise.unit)) }, enabled = !saving,
                                                    singleLine = true, modifier = Modifier.weight(1f),
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                                OutlinedTextField(value = set.reps, onValueChange = { value ->
                                                    edit(exercise.copy(sets = exercise.sets.map { if (it.id == set.id) it.copy(reps = value) else it }))
                                                }, label = { Text(stringResource(R.string.workout_text_reps)) }, enabled = !saving, singleLine = true,
                                                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                            }
                                        }
                                    }
                                }
                                TextButton(onClick = { update(draft.copy(exercises = draft.exercises.filterNot { it.id == exercise.id })) },
                                    enabled = !saving) { Text(stringResource(R.string.workout_text_remove)) }
                            }
                        }
                    }
                }
                val estimate = remember(draft, bodyWeightKg, unit, rpeScale) {
                    runCatching { WorkoutBurnEstimator.estimate(draft.planned(library), bodyWeightKg, unit, rpeScale) }.getOrNull()
                }
                estimate?.let { Text(stringResource(R.string.workout_text_estimate, it.calories), style = MaterialTheme.typography.titleMedium) }
                Text(stringResource(R.string.workout_text_calories_note), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { draftJson = null; error = null }, enabled = !saving) { Text(stringResource(R.string.workout_text_edit)) }
                    Button(onClick = {
                        saving = true; error = null
                        scope.launch {
                            try {
                                draft.planned(library)
                                saveWorkout(draft)
                                onAdded(LocalDate.parse(draft.date))
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.localizedMessage ?: "Could not save the workout. Try again." }
                            finally { saving = false }
                        }
                    }, enabled = !saving && draft.exercises.isNotEmpty()) { Text(stringResource(if (saving) R.string.workout_text_adding else R.string.workout_text_add)) }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (voice) VoiceInputSheet(container = container, onDismiss = { voice = false }, onSubmit = {
        description = listOf(description, it).filter(String::isNotBlank).joinToString("\n").take(4000)
        error = null; voice = false
    })
}
