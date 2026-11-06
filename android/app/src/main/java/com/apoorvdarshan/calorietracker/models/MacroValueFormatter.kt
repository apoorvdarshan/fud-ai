package com.apoorvdarshan.calorietracker.models

import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

object MacroValueFormatter {
    fun string(value: Double): String {
        val rounded = round(value)
        return if (abs(rounded - value) < 0.0001) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    fun withUnit(value: Double): String = "${string(value)}g"

    /**
     * [string] for display only: the user's decimal separator ("27,5" in Czech). Keep [string]
     * for text fields and anything parsed back with toDouble(); pair this with
     * `macro_grams_format` / `macro_value_unit_format` so the unit spacing follows the locale too.
     */
    fun display(value: Double, locale: Locale = Locale.getDefault()): String {
        val rounded = round(value)
        return if (abs(rounded - value) < 0.0001) {
            rounded.toInt().toString()
        } else {
            String.format(locale, "%.1f", value)
        }
    }
}
