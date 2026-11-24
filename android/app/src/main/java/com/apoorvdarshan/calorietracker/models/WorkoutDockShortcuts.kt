package com.apoorvdarshan.calorietracker.models

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.graphics.vector.ImageVector
import com.apoorvdarshan.calorietracker.R

enum class WorkoutDockAction(val key: String, @StringRes val titleRes: Int, val icon: ImageVector) {
    VOICE("voice", R.string.workout_text_voice, Icons.Filled.Mic),
    SAVED("saved", R.string.workout_source_saved, Icons.Filled.Bookmark),
    WALKING("walking", R.string.outdoor_activity_walking, Icons.AutoMirrored.Outlined.DirectionsWalk),
    RUNNING("running", R.string.outdoor_activity_running, Icons.AutoMirrored.Filled.DirectionsRun),
    TEXT("text", R.string.workout_text_menu, Icons.Filled.Edit),
    ROUTINE("routine", R.string.workout_routine_start, Icons.AutoMirrored.Outlined.ListAlt),
    COPY("copy", R.string.home_menu_copy_from_day, Icons.Filled.ContentCopy)
}

data class WorkoutDockShortcuts private constructor(val actions: List<WorkoutDockAction>) {
    fun assigning(slot: Int, action: WorkoutDockAction): WorkoutDockShortcuts {
        if (slot !in actions.indices) return this
        val other = actions.indexOf(action)
        val updated = actions.toMutableList()
        if (other >= 0) updated[other] = actions[slot]
        updated[slot] = action
        return WorkoutDockShortcuts(updated)
    }

    fun encode(): String = actions.joinToString(",") { it.key }

    companion object {
        const val STORAGE_KEY = "workout.dockShortcuts"
        val Default = WorkoutDockShortcuts(listOf(
            WorkoutDockAction.VOICE, WorkoutDockAction.SAVED, WorkoutDockAction.WALKING, WorkoutDockAction.RUNNING
        ))

        fun decode(raw: String?): WorkoutDockShortcuts {
            val keys = raw?.split(',') ?: return Default
            val actions = keys.mapNotNull { key -> WorkoutDockAction.entries.firstOrNull { it.key == key } }
            if (keys.size != Default.actions.size || actions.distinct().size != Default.actions.size) return Default
            return WorkoutDockShortcuts(actions)
        }
    }
}
