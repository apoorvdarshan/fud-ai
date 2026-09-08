package com.apoorvdarshan.calorietracker.ui.workouts

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.apoorvdarshan.calorietracker.models.WorkoutRpeScale
import com.apoorvdarshan.calorietracker.models.WorkoutSetInput
import org.junit.Assert.*
import org.junit.Test

class WorkoutFieldEditorTest {
    private val weight: (String, String) -> String = { text, _ -> WorkoutSetInput.weight(text) }
    private fun input(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))

    @Test fun delayedSavesCannotReverseDigitsOrOverwriteNewerInputAfterBlur() {
        val editor = WorkoutFieldEditor("")
        editor.setFocused(true)
        assertEquals("2", editor.edit(input("2"), weight))
        assertEquals("20", editor.edit(input("20"), weight))
        editor.receive("2")
        assertEquals(input("20"), editor.value)
        editor.setFocused(false)
        assertEquals(input("20"), editor.value)
        editor.receive("20")
        editor.setFocused(true)
        assertEquals(input("20"), editor.value)
    }

    @Test fun selectionAndCompositionSurviveSaveAcknowledgements() {
        val editor = WorkoutFieldEditor("20")
        editor.setFocused(true)
        val proposed = TextFieldValue("230", TextRange(2), TextRange(1, 2))
        editor.edit(proposed, weight)
        editor.receive("230")
        assertEquals(proposed, editor.value)
        assertNull(editor.edit(proposed.copy(selection = TextRange(0, 2)), weight))
        assertEquals(TextRange(0, 2), editor.value.selection)
    }

    @Test fun pasteFiltersCharactersAndMapsCursorBeforeSuffix() {
        val editor = WorkoutFieldEditor("20")
        editor.edit(input("2x0", 2), weight)
        assertEquals(input("20", 1), editor.value)
        editor.edit(input("82,5 kg"), weight)
        assertEquals(input("82.5"), editor.value)
    }

    @Test fun deletionDecimalPrefixAndReopeningPreserveAcceptedText() {
        val editor = WorkoutFieldEditor("20")
        editor.setFocused(true)
        for (text in listOf("2", "", "8", "8.", "8.5")) {
            assertEquals(text, editor.edit(input(text), weight))
            editor.receive(text)
            assertEquals(text, editor.value.text)
        }
        editor.setFocused(false)
        assertEquals("8.5", WorkoutFieldEditor(editor.value.text).value.text)
    }

    @Test fun externalUpdatesWaitUntilFocusLeaves() {
        val editor = WorkoutFieldEditor("20")
        editor.setFocused(true)
        editor.receive("30")
        assertEquals("20", editor.value.text)
        editor.setFocused(false)
        assertEquals("30", editor.value.text)
    }

    @Test fun everyEffortScaleAcceptsBlankAndUsesItsOwnValidation() {
        for ((scale, valid) in listOf(WorkoutRpeScale.STRENGTH to "7.5", WorkoutRpeScale.CR10 to "0.5", WorkoutRpeScale.BORG to "15")) {
            val editor = WorkoutFieldEditor("")
            editor.setFocused(true)
            editor.edit(input(valid), scale::sanitize)
            editor.receive(valid)
            assertEquals(valid, editor.value.text)
            editor.edit(input(""), scale::sanitize)
            assertEquals("", editor.value.text)
        }
        assertEquals("1234", WorkoutSetInput.reps("12345 reps"))
    }
}
