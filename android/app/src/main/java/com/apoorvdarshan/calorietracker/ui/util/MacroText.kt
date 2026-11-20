package com.apoorvdarshan.calorietracker.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.MacroValueFormatter

/** A displayed gram value — "27.5g" in English, "27,5 g" in Czech. */
@Composable
fun gramsText(value: Double): String =
    stringResource(R.string.macro_grams_format, MacroValueFormatter.display(value))

/** [gramsText] for widgets and other non-composable callers. */
fun Context.gramsText(value: Double): String =
    getString(R.string.macro_grams_format, MacroValueFormatter.display(value))

/** A displayed value followed by its unit — "250ml" / "64 fl oz" in English, "250 ml" / "64 fl oz" in Czech. */
@Composable
fun valueWithUnit(value: String, unit: String): String =
    joinValueAndUnit(value, unit, stringResource(R.string.macro_value_unit_format, value, unit.trimStart()))

/** [valueWithUnit] for widgets and other non-composable callers. */
fun Context.valueWithUnit(value: String, unit: String): String =
    joinValueAndUnit(value, unit, getString(R.string.macro_value_unit_format, value, unit.trimStart()))

/**
 * A unit that carries its own leading space (" fl oz") keeps it where the locale's format adds
 * none, so English still reads "64 fl oz" while Czech gets its single space from the format.
 */
internal fun joinValueAndUnit(value: String, unit: String, formatted: String): String =
    if (unit.startsWith(" ") && formatted == value + unit.trimStart()) value + unit else formatted
