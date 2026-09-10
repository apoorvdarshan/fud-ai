package com.apoorvdarshan.calorietracker.services

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.apoorvdarshan.calorietracker.services.FoodImageDecoder.scaledToMaxDimension
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Local food-photo cache. Port of iOS FoodImageStore.
 * JPEGs live under filesDir/fudai-food-images/{uuid}.jpg so they stay out of
 * the DataStore blob (which would otherwise inflate past quick-read limits).
 */
class FoodImageStore(context: Context) {
    private val dir: File = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val thumbnailDir: File = File(context.filesDir, THUMBNAIL_DIR_NAME).apply { mkdirs() }
    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    init {
        // Legacy thumbnails lost EXIF orientation during compression. Rebuild them
        // lazily in the new cache directory; original photos remain byte-identical.
        runCatching { File(context.filesDir, "fudai-food-thumbnails").deleteRecursively() }
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

    fun loadThumbnail(filename: String, maxDimension: Int = THUMBNAIL_MAX_DIMENSION): Bitmap? {
        val key = "$filename:$maxDimension"
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
        FileOutputStream(File(thumbnailDir, filename)).use { out ->
            thumb.compress(Bitmap.CompressFormat.JPEG, 76, out)
        }
        thumbnailCache.put("$filename:$THUMBNAIL_MAX_DIMENSION", thumb)
    }

    private fun evictThumbnails(filename: String) {
        for (key in thumbnailCache.snapshot().keys) {
            if (key.startsWith("$filename:")) thumbnailCache.remove(key)
        }
    }

    companion object {
        private const val DIR_NAME = "fudai-food-images"
        private const val THUMBNAIL_DIR_NAME = "fudai-food-thumbnails-v2"
        private const val THUMBNAIL_MAX_DIMENSION = 320
        private const val THUMBNAIL_CACHE_KB = 12 * 1024
    }
}
