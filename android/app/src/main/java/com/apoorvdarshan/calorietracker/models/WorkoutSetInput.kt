package com.apoorvdarshan.calorietracker.models

/** Shared by the editor and persistence so a save does not rewrite accepted input. */
internal object WorkoutSetInput {
    fun reps(value: String): String = value.filter(Char::isDigit).take(4)

    fun weight(value: String): String {
        val output = StringBuilder()
        var hasDecimal = false
        for (character in value.replace(',', '.')) {
            if (character.isDigit()) {
                output.append(character)
            } else if (character == '.' && !hasDecimal) {
                hasDecimal = true
                output.append(character)
            }
            if (output.length >= 7) break
        }
        return output.toString()
    }
}
