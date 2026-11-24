package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class WorkoutDockLayout(val count: Int, val side: Dp, val gap: Dp) {
    companion object {
        val AddSide = 60.dp
        private val RoomySide = 52.dp
        private val RoomyGap = 6.dp
        private val CompactSide = 48.dp
        private val CompactGap = 4.dp

        fun fit(width: Dp, slots: Int): WorkoutDockLayout {
            val space = width - AddSide
            val count = (space / (CompactSide + CompactGap)).toInt().coerceIn(0, slots)
            // Preserve the larger squares unless tightening the row makes another slot fit.
            val roomy = space >= (RoomySide + RoomyGap) * count
            return if (roomy) WorkoutDockLayout(count, RoomySide, RoomyGap)
            else WorkoutDockLayout(count, CompactSide, CompactGap)
        }
    }
}
