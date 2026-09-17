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

    AsyncFunction("readSnapshot") Coroutine {
      readSnapshotOrEmpty()
    }

    AsyncFunction("copyFoodImages") Coroutine { destination: String ->
      withContext(Dispatchers.IO) {
        copyFoodImages(destination)
      }
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

  private fun copyFoodImages(destination: String): Int {
    val dest = fileFromPath(destination)
    dest.mkdirs()
    val sourcePath = foodImagesDirectoryPath() ?: return 0
    val source = File(sourcePath)
    if (!source.isDirectory) return 0
    if (source.canonicalPath == dest.canonicalPath) {
      return source.listFiles()?.count { isImage(it) } ?: 0
    }
    var copied = 0
    source.listFiles()?.forEach { file ->
      if (!isImage(file)) return@forEach
      val target = File(dest, file.name)
      if (target.exists() || runCatching { file.copyTo(target, overwrite = false) }.isSuccess) {
        copied += 1
      }
    }
    return copied
  }

  private fun fileFromPath(path: String): File =
    if (path.startsWith("file:")) File(android.net.Uri.parse(path).path ?: path) else File(path)

  private fun isImage(file: File): Boolean {
    val ext = file.extension.lowercase()
    return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp"
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
