package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.runtime.saveable.Saver
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Saves small draft values as text; images stay in the image store. */
internal inline fun <reified T> foodDraftSaver(): Saver<T, String> = Saver(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<T>(it) }.getOrNull() }
)

internal val LocalDateSaver = Saver<LocalDate, Long>(
    save = { it.toEpochDay() },
    restore = { LocalDate.ofEpochDay(it) }
)

internal val LocalTimeSaver = Saver<LocalTime, Long>(
    save = { it.toNanoOfDay() },
    restore = { LocalTime.ofNanoOfDay(it) }
)
