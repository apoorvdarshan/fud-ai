package com.apoorvdarshan.calorietracker.services

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import com.apoorvdarshan.calorietracker.models.UserExercise
import com.apoorvdarshan.calorietracker.services.FoodImageDecoder.scaledToMaxDimension
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local food-photo cache. Port of iOS FoodImageStore.
 * JPEGs live under filesDir/fudai-food-images/{uuid}.jpg so they stay out of
 * the DataStore blob (which would otherwise inflate past quick-read limits).
 */
class FoodImageStore private constructor(
    rootDir: File,
    cleanupLegacyThumbnails: Boolean,
    thumbnailCacheKb: Int
) {
    private val dir: File = File(rootDir, DIR_NAME).apply { mkdirs() }
    private val thumbnailDir: File = File(rootDir, THUMBNAIL_DIR_NAME).apply { mkdirs() }
    private val thumbnailCache = object : LruCache<String, Bitmap>(thumbnailCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val thumbnailLocks = ConcurrentHashMap<String, Any>()

    constructor(context: Context) : this(
        rootDir = context.filesDir,
        cleanupLegacyThumbnails = true,
        thumbnailCacheKb = thumbnailCacheKbFor(context)
    )

    init {
        if (cleanupLegacyThumbnails) {
            // Legacy thumbnails lost EXIF orientation during compression. Rebuild them
            // lazily in the new cache directory; original photos remain byte-identical.
            runCatching { File(rootDir, "fudai-food-thumbnails").deleteRecursively() }
        }
    }

    /** Writes the bitmap as JPEG (quality 80) under a new filename. Returns filename or null. */
    fun store(bitmap: Bitmap, entryId: UUID): String? = runCatching {
        val filename = "${entryId}.jpg"
        FileOutputStream(File(dir, filename)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        runCatching { writeThumbnail(filename, bitmap) }
        filename
    }.getOrNull()

    fun storeBytes(bytes: ByteArray, entryId: UUID): String? = runCatching {
        val filename = "${entryId}.jpg"
        File(dir, filename).writeBytes(bytes)
        runCatching {
            FoodImageDecoder.decode(bytes, THUMBNAIL_MAX_DIMENSION)?.let { writeThumbnail(filename, it) }
        }
        filename
    }.getOrNull()

    fun load(filename: String): Bitmap? =
        runCatching { FoodImageDecoder.decode(File(dir, filename)) }.getOrNull()

    /** Bounded decode for full-screen viewer — avoids loading full-resolution on the main thread. */
    fun loadForViewer(filename: String, maxDimension: Int = VIEWER_MAX_DIMENSION): Bitmap? =
        runCatching { FoodImageDecoder.decode(File(dir, filename), maxDimension) }.getOrNull()

    fun loadThumbnail(filename: String, maxDimension: Int = THUMBNAIL_MAX_DIMENSION): Bitmap? {
        val key = "$filename:$maxDimension"
        thumbnailCache.get(key)?.takeUnless { it.isRecycled }?.let { return it }

        synchronized(thumbnailLockFor(filename)) {
            thumbnailCache.get(key)?.takeUnless { it.isRecycled }?.let { return it }

            val thumbFile = File(thumbnailDir, filename)
            val bitmap = when {
                thumbFile.exists() -> runCatching {
                    FoodImageDecoder.decode(thumbFile, maxDimension)
                }.getOrNull()
                else -> runCatching {
                    val fullFile = File(dir, filename)
                    FoodImageDecoder.decode(fullFile, maxDimension)?.also { writeThumbnail(filename, it) }
                }.getOrNull()
            }

            if (bitmap != null) thumbnailCache.put(key, bitmap)
            return bitmap
        }
    }

    /** Prefetch thumbnails into memory/disk cache. Call from a background dispatcher. */
    fun warmThumbnails(filenames: Collection<String>) {
        filenames.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { loadThumbnail(it) }
    }

    fun file(filename: String): File = File(dir, filename)

    fun listedFilenames(): List<String> =
        dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()

    fun loadBytes(filename: String): ByteArray? {
        val file = File(dir, filename)
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    fun restoreBytes(filename: String, bytes: ByteArray): Boolean = runCatching {
        File(dir, filename).writeBytes(bytes)
        runCatching {
            FoodImageDecoder.decode(bytes, THUMBNAIL_MAX_DIMENSION)?.let { writeThumbnail(filename, it) }
        }
        true
    }.getOrDefault(false)

    /** Stores a custom exercise photo under a fresh filename (never overwrites diary snapshots). */
    fun storeExercisePhoto(bytes: ByteArray): String? = runCatching {
        val filename = UserExercise.newPhotoFilename()
        val bitmap = FoodImageDecoder.decode(bytes, VIEWER_MAX_DIMENSION) ?: return null
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        bitmap.recycle()
        if (restoreBytes(filename, jpeg)) filename else null
    }.getOrNull()

    fun delete(filename: String) {
        runCatching { File(dir, filename).delete() }
        runCatching { File(thumbnailDir, filename).delete() }
        evictThumbnails(filename)
    }

    fun clearAll() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        thumbnailDir.listFiles()?.forEach { runCatching { it.delete() } }
        thumbnailCache.evictAll()
    }

    /**
     * Removes only image files that are no longer referenced by persisted app data.
     * Callers must include food-log, saved-meal, and pending-draft filenames in
     * [referencedFilenames]. This is safe to run repeatedly, including at startup,
     * and repairs orphaned files left by older builds without touching user data.
     */
    fun pruneUnreferenced(referencedFilenames: Set<String>) {
        val referenced = referencedFilenames.mapTo(mutableSetOf()) { File(it).name }

        dir.listFiles()
            ?.filter { it.isFile && it.name !in referenced }
            ?.forEach { delete(it.name) }

        // A crash can leave a thumbnail without its full-size image. Clean those
        // independently while preserving every thumbnail still referenced.
        thumbnailDir.listFiles()
            ?.filter { it.isFile && it.name !in referenced }
            ?.forEach { file ->
                runCatching { file.delete() }
                evictThumbnails(file.name)
            }
    }

    private fun writeThumbnail(filename: String, bitmap: Bitmap) {
        val thumb = bitmap.scaledToMaxDimension(THUMBNAIL_MAX_DIMENSION)
        val target = File(thumbnailDir, filename)
        val temp = File(thumbnailDir, "$filename.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 76, out)
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            thumbnailCache.put("$filename:$THUMBNAIL_MAX_DIMENSION", thumb)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun thumbnailLockFor(filename: String): Any =
        thumbnailLocks.getOrPut(filename) { Any() }

    private fun evictThumbnails(filename: String) {
        for (key in thumbnailCache.snapshot().keys) {
            if (key.startsWith("$filename:")) thumbnailCache.remove(key)
        }
    }

    companion object {
        private const val DIR_NAME = "fudai-food-images"
        private const val THUMBNAIL_DIR_NAME = "fudai-food-thumbnails-v2"
        private const val THUMBNAIL_MAX_DIMENSION = 320
        const val VIEWER_MAX_DIMENSION = 2048
        private const val THUMBNAIL_CACHE_KB_DEFAULT = 12 * 1024

        fun thumbnailCacheKbFor(context: Context): Int {
            val memoryClass =
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
            return when {
                memoryClass <= 128 -> 4 * 1024
                memoryClass <= 192 -> 8 * 1024
                else -> THUMBNAIL_CACHE_KB_DEFAULT
            }
        }

        @VisibleForTesting
        fun forTests(baseDir: File): FoodImageStore =
            FoodImageStore(
                baseDir.apply { mkdirs() },
                cleanupLegacyThumbnails = false,
                thumbnailCacheKb = THUMBNAIL_CACHE_KB_DEFAULT
            )
    }
}
