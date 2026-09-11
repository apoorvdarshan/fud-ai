package com.apoorvdarshan.calorietracker.models

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.ui.graphics.vector.ImageVector
import com.apoorvdarshan.calorietracker.R

/** Leaf actions available in the Home + food menu (excluding water and fasting). */
enum class FoodLogMethod(
    val storageKey: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector
) {
    CAMERA("camera", R.string.home_menu_camera_note, Icons.Filled.CameraAlt),
    PHOTOS("photos", R.string.home_menu_from_photos, Icons.Filled.PhotoLibrary),
    BARCODE("barcode", R.string.home_menu_barcode, Icons.Filled.QrCodeScanner),
    TEXT("text", R.string.home_menu_text_input, Icons.Filled.Edit),
    VOICE("voice", R.string.home_menu_voice, Icons.Filled.Mic),
    MANUAL("manual", R.string.home_menu_manual_entry, Icons.Filled.DriveFileRenameOutline),
    RECENT("recent", R.string.saved_meals_tab_recents, Icons.Filled.History),
    FREQUENT("frequent", R.string.saved_meals_tab_frequent, Icons.Filled.Repeat),
    FAVORITES("favorites", R.string.saved_meals_tab_favorites, Icons.Filled.Favorite),
    COPY_FROM_DAY("copy_from_day", R.string.home_menu_copy_from_day, Icons.Filled.CalendarMonth);

    val quickAction: QuickAction?
        get() = when (this) {
            CAMERA -> QuickAction.CAMERA
            PHOTOS -> QuickAction.PHOTOS
            BARCODE -> QuickAction.BARCODE
            TEXT -> QuickAction.TEXT
            VOICE -> QuickAction.VOICE
            MANUAL -> QuickAction.MANUAL
            RECENT -> QuickAction.RECENT
            FREQUENT -> QuickAction.FREQUENT
            FAVORITES -> QuickAction.FAVORITES
            COPY_FROM_DAY -> null
        }

    companion object {
        val AddMenuCases = entries.toList()

        fun fromStorage(value: String?): FoodLogMethod? =
            entries.firstOrNull { it.storageKey == value || it.name == value }

        fun fromQuickAction(action: QuickAction): FoodLogMethod? = when (action) {
            QuickAction.CAMERA -> CAMERA
            QuickAction.PHOTOS -> PHOTOS
            QuickAction.BARCODE -> BARCODE
            QuickAction.TEXT -> TEXT
            QuickAction.VOICE -> VOICE
            QuickAction.MANUAL -> MANUAL
            QuickAction.RECENT -> RECENT
            QuickAction.FREQUENT -> FREQUENT
            QuickAction.FAVORITES -> FAVORITES
            QuickAction.FASTING -> null
        }
    }
}

/** Default group icon when no better match exists. */
val FoodLogMethodDefaultGroupIcon = Icons.Filled.Bookmark
