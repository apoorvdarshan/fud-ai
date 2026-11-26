package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.WorkoutRestTimer
import com.apoorvdarshan.calorietracker.models.WorkoutRoutine
import com.apoorvdarshan.calorietracker.models.WorkoutRoutinePlanner
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.Instant
import kotlinx.coroutines.delay

/** The running rest countdown, pinned at the top of the workout log. */
@Composable
internal fun WorkoutRestTimerBar(
    timer: WorkoutRestTimer,
    onExtend: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    var now by remember(timer) { mutableStateOf(Instant.now()) }
    LaunchedEffect(timer) {
        while (!timer.isFinished(now)) {
            // Tick on the countdown's own second boundaries.
            val untilNextSecond = (timer.endsAt.toEpochMilli() - now.toEpochMilli()) % 1_000
            delay(if (untilNextSecond <= 0) 1_000 else untilNextSecond)
            now = Instant.now()
        }
    }
    val addDescription = stringResource(R.string.workout_rest_add_15_description)
    val skipDescription = stringResource(R.string.workout_rest_skip_description)
    val clock = WorkoutRestTimer.clockText(timer.remainingSeconds(now))
    val clockStyle = LocalTextStyle.current.copy(fontSize = 20.sp, fontWeight = FontWeight.Black)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    FudGlassSurface(modifier = modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = 0.dp) {
        BoxWithConstraints(Modifier.padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)) {
            // The countdown is what the bar is for, so it always keeps its full width: with long
            // translations or very large text the buttons and the Rest label give way instead.
            // Whole pixels, the way the row lays them out, so the clock is never a pixel short.
            val controlsMaxWidth = with(density) {
                val clockWidth = textMeasurer.measure(clock, clockStyle, softWrap = false, maxLines = 1).size.width
                val reserved = RestRingSize.roundToPx() + RestBarSpacing.roundToPx() * 2 + clockWidth
                (constraints.maxWidth - reserved).coerceAtLeast(0).toDp()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RestBarSpacing)
            ) {
                Box(Modifier.size(RestRingSize), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { 1f - timer.progress(now) },
                        modifier = Modifier.fillMaxSize(),
                        color = AppColors.Calorie,
                        trackColor = AppColors.Calorie.copy(alpha = 0.18f),
                        strokeWidth = 4.dp
                    )
                    Icon(
                        Icons.Outlined.HourglassTop,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            stringResource(R.string.workout_rest_label),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Weighted, so it is measured after the clock and only gets what is left.
                            modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp, bottom = 3.dp)
                        )
                        Text(
                            clock,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = clockStyle,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag(RestClockTestTag)
                        )
                    }
                    Text(
                        timer.exerciseName,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.widthIn(max = controlsMaxWidth),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RestBarSpacing)
                ) {
                    WorkoutRestPill(
                        text = stringResource(R.string.workout_rest_add_15),
                        description = addDescription,
                        filled = false,
                        onClick = onExtend
                    )
                    WorkoutRestPill(
                        text = stringResource(R.string.workout_rest_skip),
                        description = skipDescription,
                        filled = true,
                        onClick = onSkip,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
    }
}

private val RestRingSize = 38.dp
private val RestBarSpacing = 10.dp
internal const val RestClockTestTag = "workout.rest.clock"

@Composable
private fun WorkoutRestPill(
    text: String,
    description: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(if (filled) AppColors.Calorie else AppColors.Calorie.copy(alpha = 0.13f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (filled) Color.White else AppColors.Calorie,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Starts (or restarts) rest after one set, for sets whose reps were already filled in, such as
 * those planned by a routine.
 */
@Composable
internal fun WorkoutRestSetButton(setNumber: Int, isResting: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.workout_rest_start_after_set, setNumber)
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp).semantics { contentDescription = description }) {
        Icon(
            if (isResting) Icons.Outlined.HourglassTop else Icons.Outlined.HourglassEmpty,
            contentDescription = null,
            tint = if (isResting) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/** Lists saved routines. Tapping one starts the selected day from it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutRoutinesSheet(
    routines: List<WorkoutRoutine>,
    onApply: (WorkoutRoutine) -> Unit,
    onRename: (WorkoutRoutine, String) -> Unit,
    onDelete: (WorkoutRoutine) -> Unit,
    onDismiss: () -> Unit
) {
    var renaming by remember { mutableStateOf<WorkoutRoutine?>(null) }
    var pendingDeletion by remember { mutableStateOf<WorkoutRoutine?>(null) }
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
                Text(
                    stringResource(R.string.workout_routines_title),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_done))
                }
            }
            if (routines.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ListAlt,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        stringResource(R.string.workout_routines_empty_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.workout_routines_empty_message),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                        fontSize = 13.sp
                    )
                }
            } else {
                Text(
                    stringResource(R.string.workout_routines_footer),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.54f),
                    fontSize = 12.sp
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(routines, key = { it.id }) { routine ->
                        WorkoutRoutineRow(
                            routine = routine,
                            onClick = { onApply(routine) },
                            onRename = { renaming = routine },
                            onDelete = { pendingDeletion = routine }
                        )
                    }
                }
            }
        }
    }

    renaming?.let { routine ->
        WorkoutRoutineNameDialog(
            title = stringResource(R.string.workout_routine_rename_title),
            initialName = routine.name,
            onSave = { name ->
                onRename(routine, name)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }

    pendingDeletion?.let { routine ->
        FudGlassDialog(onDismissRequest = { pendingDeletion = null }) {
            Text(
                stringResource(R.string.workout_routine_delete_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.workout_routine_delete_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = {
                    onDelete(routine)
                    pendingDeletion = null
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { pendingDeletion = null },
                destructive = true
            )
        }
    }
}

@Composable
private fun WorkoutRoutineRow(
    routine: WorkoutRoutine,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    FudGlassSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        cornerRadius = 18.dp,
        padding = 13.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Outlined.ListAlt, contentDescription = null, tint = AppColors.Calorie)
            Column(
                modifier = Modifier.weight(1f).padding(start = 3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    routine.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    routine.exerciseSummary,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.workout_routine_rename),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.82f),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

/** Names a new routine or renames one; Save stays off until the name has text. */
@Composable
internal fun WorkoutRoutineNameDialog(
    title: String,
    initialName: String = "",
    message: String? = null,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val canSave = WorkoutRoutinePlanner.sanitizedName(name).isNotEmpty()
    FudGlassDialog(onDismissRequest = onDismiss) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        if (message != null) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(WorkoutRoutinePlanner.MAX_NAME_LENGTH) },
            label = { Text(stringResource(R.string.workout_routine_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(name) }),
            modifier = Modifier.fillMaxWidth()
        )
        FudGlassDialogActions(
            primaryText = stringResource(R.string.action_save),
            onPrimary = { onSave(name) },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryEnabled = canSave
        )
    }
}
