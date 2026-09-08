package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Saves small draft values as text; images stay in the image store. */
internal inline fun <reified T> foodDraftSaver(): Saver<T, String> = Saver(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<T>(it) }.getOrNull() }
)
