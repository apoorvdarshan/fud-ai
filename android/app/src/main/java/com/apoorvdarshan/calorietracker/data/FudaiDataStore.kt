package com.apoorvdarshan.calorietracker.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File

private const val DATASTORE_NAME = "fudai_prefs"
private const val TAG = "FudaiDataStore"

/**
 * The single app-wide Preferences DataStore.
 *
 * Replaces the `preferencesDataStore` delegate so a corrupt `fudai_prefs`
 * file no longer throws `CorruptionException` into every collector (a crash
 * loop on launch). The corruption handler copies the unreadable file aside as
 * `fudai_prefs.preferences_pb.corrupt-<timestamp>` and starts from empty
 * preferences, so the user gets a working app and support still has the bytes.
 *
 * If that copy cannot be written the handler rethrows instead: DataStore then
 * leaves the original file untouched (readers keep failing, which is the
 * pre-existing behaviour) rather than replacing the only copy of every
 * preference with an empty file.
 */
val Context.fudaiDataStore: DataStore<Preferences>
    get() = FudaiDataStoreHolder.get(this)

internal object FudaiDataStoreHolder {
    @Volatile
    private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(app: Context): DataStore<Preferences> {
        val file = app.preferencesDataStoreFile(DATASTORE_NAME)
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { exception ->
                val backup = preserveCorruptFile(file)
                if (backup == null) {
                    Log.e(TAG, "Preferences file is corrupt and could not be copied aside; keeping it in place", exception)
                    throw exception
                }
                Log.e(TAG, "Preferences file is corrupt; preserved copy at ${backup.absolutePath}", exception)
                emptyPreferences()
            },
            produceFile = { file }
        )
    }

    private fun preserveCorruptFile(file: File): File? =
        CorruptBlobArchive(File(file.parentFile ?: file, CorruptBlobArchive.DIRECTORY_NAME)).preserveFile(file)
}
