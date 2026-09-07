package com.apoorvdarshan.calorietracker.models

enum class QuickAction(val title: String) {
    CAMERA("Camera + Note"),
    PHOTOS("Photos"),
    VOICE("Voice"),
    TEXT("Text"),
    BARCODE("Barcode"),
    FAVORITES("Favorites"),
    FREQUENT("Frequent"),
    RECENT("Recent"),
    MANUAL("Manual"),
    FASTING("Fasting");

    companion object {
        val Defaults = listOf(CAMERA, VOICE, BARCODE)

        fun fromStorage(value: String?, fallback: QuickAction = CAMERA): QuickAction =
            entries.firstOrNull { it.name == value } ?: fallback
    }
}

data class QuickActionRequest(
    val action: QuickAction,
    val id: Long = System.nanoTime()
)
