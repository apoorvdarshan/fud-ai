package expo.modules.nativestorage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Read-only bridge over the native Android Preferences DataStore `fudai_prefs`.
 * Same file [FudaiDataStore] / [PreferencesStore] write — never a second store, never a wipe.
 */
class NativeStorageModule : Module() {
  private val storeMutex = Mutex()
  @Volatile private var store: DataStore<Preferences>? = null

  override fun definition() = ModuleDefinition {
    Name("NativeStorage")

    Constants {
      mapOf(
        "isAvailable" to true,
        "platform" to "android",
      )
    }

    AsyncFunction("readSnapshot") {
      val snapshot = readSnapshotOrEmpty()
      snapshot
    }
  }

  private suspend fun readSnapshotOrEmpty(): Map<String, Any?> = withContext(Dispatchers.IO) {
    val dataStore = dataStoreOrNull()
    if (dataStore == null) {
      return@withContext mapOf(
        "available" to false,
        "platform" to "android",
        "blobs" to emptyMap<String, String>(),
        "prefs" to emptyMap<String, Any>(),
        "foodImagesDirectory" to foodImagesDirectoryPath(),
      )
    }

    val prefs = try {
      dataStore.data.first()
    } catch (_: Exception) {
      // Leave the on-disk file untouched — do not install a replace-on-corrupt handler.
      return@withContext mapOf(
        "available" to false,
        "platform" to "android",
        "blobs" to emptyMap<String, String>(),
        "prefs" to emptyMap<String, Any>(),
        "foodImagesDirectory" to foodImagesDirectoryPath(),
      )
    }

    val blobs = linkedMapOf<String, String>()
    val scalars = linkedMapOf<String, Any>()
    for ((key, value) in prefs.asMap()) {
      val name = key.name
      when (value) {
        is String -> {
          if (name in blobKeys || looksLikeJson(value)) blobs[name] = value
          else scalars[name] = value
        }
        is Boolean -> scalars[name] = value
        is Int -> scalars[name] = value
        is Long -> scalars[name] = value.toDouble()
        is Float -> scalars[name] = value.toDouble()
        is Double -> scalars[name] = value
        is Set<*> -> {
          val strings = value.mapNotNull { it as? String }
          blobs[name] = org.json.JSONArray(strings).toString()
        }
      }
    }

    mapOf(
      "available" to true,
      "platform" to "android",
      "blobs" to blobs,
      "prefs" to scalars,
      "foodImagesDirectory" to foodImagesDirectoryPath(),
    )
  }

  private suspend fun dataStoreOrNull(): DataStore<Preferences>? {
    val context = appContext.reactContext ?: return null
    store?.let { return it }
    return storeMutex.withLock {
      store?.let { return@withLock it }
      val created = PreferenceDataStoreFactory.create(
        produceFile = { context.applicationContext.preferencesDataStoreFile(DATASTORE_NAME) },
      )
      store = created
      created
    }
  }

  private fun foodImagesDirectoryPath(): String? {
    val context = appContext.reactContext ?: return null
    return File(context.filesDir, FOOD_IMAGES_DIR).absolutePath
  }

  private companion object {
    const val DATASTORE_NAME = "fudai_prefs"
    const val FOOD_IMAGES_DIR = "fudai-food-images"

    val blobKeys = setOf(
      "foodEntries",
      "favoriteFoodEntries",
      "favorites",
      "waterEntries",
      "fastingSessions",
      "weightEntries",
      "bodyFatEntries",
      "userProfile",
      "coachChatHistory",
      "workoutDiaryStateV1",
      "fudai.workouts.diary.state.v1",
      "adaptiveGoalsPreviousTargets",
    )

    fun looksLikeJson(value: String): Boolean {
      val trimmed = value.trim()
      return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
        (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }
  }
}
