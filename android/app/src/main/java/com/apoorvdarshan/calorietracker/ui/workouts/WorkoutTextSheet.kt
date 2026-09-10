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
    initialDescription: String = "",
    analyzeOnOpen: Boolean = false,
    onStartOver: (() -> Unit)? = null,
    analyzeWorkout: suspend (String) -> WorkoutTextDraft = {
        container.foodAnalysis.analyzeWorkout(it, selectedDate, unit, library)
    },
    saveWorkout: suspend (WorkoutTextDraft) -> Unit = { container.workoutRepository.addTextWorkout(it, library) }
) {
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = remember(draftJson) { draftJson?.let { Json.decodeFromString<WorkoutTextDraft>(it) } }
    var followUpsJson by rememberSaveable { mutableStateOf("[]") }
    val followUps = remember(followUpsJson) { Json.decodeFromString<List<WorkoutFollowUp>>(followUpsJson) }
    var question by rememberSaveable { mutableStateOf<String?>(null) }
    var options by rememberSaveable { mutableStateOf(listOf<String>()) }
    var reply by rememberSaveable { mutableStateOf("") }
    var voiceReply by rememberSaveable { mutableStateOf(false) }
    var requestGeneration by remember { mutableIntStateOf(0) }
    var requestJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var started by rememberSaveable { mutableStateOf(false) }
    val dismiss = { if (!saving) onDismiss() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !saving })

    fun update(value: WorkoutTextDraft) { draftJson = Json.encodeToString(value); error = null; question = null }

    fun analyze(context: String = WorkoutConversation(description.trim(), followUps).requestDescription()) {
        keyboard?.hide()
        busy = true; error = null
        requestGeneration++
        val generation = requestGeneration
        requestJob = scope.launch {
            try {
                val result = analyzeWorkout(context)
                if (generation == requestGeneration) update(result)
            } catch (e: CancellationException) { throw e }
            catch (e: WorkoutClarification) {
                if (generation == requestGeneration) { question = e.message; options = e.options; reply = "" }
            }
            catch (e: Exception) {
                if (generation == requestGeneration) error = e.localizedMessage ?: "Could not prepare the workout. Try again."
            }
            finally { if (generation == requestGeneration) busy = false }
        }
    }
    fun answer(text: String) {
        val currentQuestion = question ?: return
        try {
            val conversation = WorkoutConversation(description, followUps).answering(currentQuestion, text)
            followUpsJson = Json.encodeToString(conversation.turns)
            question = null; options = emptyList(); reply = ""
            analyze(conversation.requestDescription())
        } catch (e: Exception) { error = e.localizedMessage }
    }
    fun startOver() {
        requestGeneration++
        requestJob?.cancel()
        description = ""; followUpsJson = "[]"; question = null; options = emptyList()
        reply = ""; draftJson = null; error = null; busy = false; voiceReply = false
        onStartOver?.invoke()
    }
    LaunchedEffect(Unit) {
        if (analyzeOnOpen && !started && draft == null) { started = true; analyze() }
    }

    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workout_text_title), style = MaterialTheme.typography.headlineSmall)
            if (draft == null && (question != null || followUps.isNotEmpty())) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                followUps.forEach { Text(it.answer, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (question != null) {
                    Text(question.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    options.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { option ->
                                OutlinedButton(onClick = { answer(option) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(option) }
                            }
                        }
                    }
                    OutlinedTextField(value = reply, onValueChange = { reply = it.take(500) },
                        label = { Text("Your answer") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { voiceReply = true }, enabled = !busy) { Text("Voice reply") }
                        Button(onClick = { answer(reply) }, enabled = !busy && reply.isNotBlank()) { Text("Continue") }
                    }
                } else {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { analyze() }, enabled = !busy) {
                        Text(stringResource(if (busy) R.string.workout_text_preparing else if (error != null) R.string.workout_text_retry else R.string.workout_text_preview))
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else if (draft == null) {
                Text(stringResource(R.string.workout_text_intro))
                OutlinedTextField(value = description, onValueChange = { description = it.take(4000); error = null; followUpsJson = "[]"; question = null },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(stringResource(R.string.workout_text_description)) },
                    placeholder = { Text(stringResource(R.string.workout_text_example)) })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        analyze()
                    }, enabled = description.isNotBlank() && !busy) { Text(stringResource(if (busy) R.string.workout_text_preparing else if (error != null) R.string.workout_text_retry else R.string.workout_text_preview)) }
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
                                            OutlinedTextField(value = set.rpe, onValueChange = { value ->
                                                edit(exercise.copy(sets = exercise.sets.map { if (it.id == set.id) it.copy(rpe = value) else it }))
                                            }, label = { Text(stringResource(R.string.workout_text_rpe)) }, enabled = !saving,
                                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
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
                    OutlinedButton(onClick = { draftJson = null; error = null; followUpsJson = "[]"; question = null }, enabled = !saving) { Text(stringResource(R.string.workout_text_edit)) }
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
            TextButton(onClick = { startOver() }, enabled = !saving) { Text("Start over") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (voiceReply) com.apoorvdarshan.calorietracker.ui.home.VoiceInputSheet(
        container = container, onDismiss = { voiceReply = false },
        onSubmit = { voiceReply = false; answer(it) }
    )
}
