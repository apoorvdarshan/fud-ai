package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Keeps IME text/selection synchronous while repository updates arrive asynchronously. */
internal class WorkoutFieldEditor(initial: String) {
    var value by mutableStateOf(TextFieldValue(initial))
        private set
    private var focused = false
    private var persisted = initial
    private var pending: String? = null

    fun edit(proposed: TextFieldValue, sanitize: (String, String) -> String): String? {
        val previous = value.text
        val text = sanitize(proposed.text, previous)
        fun offset(position: Int): Int {
            val prefix = sanitize(proposed.text.take(position), "")
            return if (text.startsWith(prefix)) prefix.length else position.coerceAtMost(text.length)
        }
        value = if (text == proposed.text) proposed else proposed.copy(
            text = text,
            selection = TextRange(offset(proposed.selection.start), offset(proposed.selection.end)),
            composition = null
        )
        if (text == previous) return null
        pending = text
        return text
    }

    fun receive(text: String) {
        persisted = text
        if (pending == text) pending = null
        reconcile()
    }

    fun setFocused(isFocused: Boolean) {
        focused = isFocused
        reconcile()
    }

    private fun reconcile() {
        // Never move the cursor during editing or replace text with an earlier save.
        if (!focused && pending == null && value.text != persisted) {
            value = TextFieldValue(persisted, TextRange(persisted.length))
        }
    }
}
