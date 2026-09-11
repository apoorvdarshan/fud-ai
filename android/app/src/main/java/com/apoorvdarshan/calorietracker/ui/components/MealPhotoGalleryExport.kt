package com.apoorvdarshan.calorietracker.ui.components

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.fudaiDataStore
import com.apoorvdarshan.calorietracker.services.FoodImageDecoder
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.booleanPreferencesKey

enum class MealPhotoSaveResult {
    Saved,
    PermissionDenied,
    Failed
}

private val saveMealPhotosToGalleryKey = booleanPreferencesKey("saveMealPhotosToGallery")
private val autoSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Silently copies a newly stored meal photo into the gallery when the setting is enabled. */
fun autoSaveMealPhotoIfEnabled(context: Context, bytes: ByteArray) {
    autoSaveScope.launch {
        val enabled = context.fudaiDataStore.data.first()[saveMealPhotosToGalleryKey] == true
        if (!enabled) return@launch
        val bitmap = FoodImageDecoder.decode(bytes) ?: return@launch
        runCatching { saveMealPhotoToGallery(context, bitmap) }
    }
}

fun saveMealPhotoToGallery(context: Context, bitmap: Bitmap): MealPhotoSaveResult {
    val filename = "fud-ai-${System.currentTimeMillis()}.jpg"
    val folderName = context.getString(R.string.gallery_folder_name)

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveViaMediaStore(context, bitmap, filename, folderName)
    } else {
        saveViaLegacyExternalStorage(context, bitmap, filename, folderName)
    }
}

private fun saveViaMediaStore(
    context: Context,
    bitmap: Bitmap,
    filename: String,
    folderName: String
): MealPhotoSaveResult {
    return runCatching {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$folderName"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return@runCatching MealPhotoSaveResult.Failed

        val wrote = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    error("compress failed")
                }
            } ?: error("missing output stream")
        }.isSuccess

        if (!wrote) {
            runCatching { resolver.delete(uri, null, null) }
            return@runCatching MealPhotoSaveResult.Failed
        }

        contentValues.clear()
        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)
        MealPhotoSaveResult.Saved
    }.getOrElse { MealPhotoSaveResult.Failed }
}

private fun saveViaLegacyExternalStorage(
    context: Context,
    bitmap: Bitmap,
    filename: String,
    folderName: String
): MealPhotoSaveResult {
    return runCatching {
        val picturesRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val targetDir = File(picturesRoot, folderName)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return@runCatching MealPhotoSaveResult.Failed
        }

        val targetFile = File(targetDir, filename)
        val wrote = runCatching {
            FileOutputStream(targetFile).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    error("compress failed")
                }
            }
        }.isSuccess

        if (!wrote) {
            targetFile.delete()
            return@runCatching MealPhotoSaveResult.Failed
        }

        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
        }
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return@runCatching MealPhotoSaveResult.Failed

        MealPhotoSaveResult.Saved
    }.getOrElse { MealPhotoSaveResult.Failed }
}
