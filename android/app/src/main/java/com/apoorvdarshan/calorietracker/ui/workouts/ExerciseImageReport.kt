package com.apoorvdarshan.calorietracker.ui.workouts

import java.net.URLEncoder

internal object ExerciseImageReport {
    const val NOTE_LIMIT = 400

    fun trimNote(note: String): String {
        val count = note.codePointCount(0, note.length)
        return if (count <= NOTE_LIMIT) note else note.substring(0, note.offsetByCodePoints(0, NOTE_LIMIT))
    }

    fun text(exercise: String, id: String, visual: String, appVersion: String, note: String): String =
        "Exercise image: $exercise\nCatalog ID: $id\nPlatform: Android · Fud AI $appVersion\n" +
            "Visual: $visual\n\n${trimNote(note.trim())}"

    fun issueUrl(exercise: String, id: String, visual: String, appVersion: String, note: String): String {
        val body = text(exercise, id, visual, appVersion, note).lines().joinToString("\n") { "    $it" }
        fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        return "https://github.com/apoorvdarshan/fud-ai/issues/new?template=exercise_visual.md" +
            "&title=${encode("Exercise image: $exercise")}&body=${encode(body)}"
    }
}
