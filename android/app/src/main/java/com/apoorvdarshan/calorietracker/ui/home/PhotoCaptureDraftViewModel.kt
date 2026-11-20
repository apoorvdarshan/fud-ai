package com.apoorvdarshan.calorietracker.ui.home

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Capture files are separate from logged-food photos, so diary cleanup cannot delete a draft. */
internal class CapturePhotoFiles(private val directory: File) {
    fun write(bytes: ByteArray): String {
        check(directory.isDirectory || directory.mkdirs()) { "Could not save photo draft" }
        val name = "${UUID.randomUUID()}.jpg"
        val photo = File(directory, name)
        try { photo.writeBytes(bytes) } catch (error: Exception) {
            photo.delete()
            throw error
        }
        return name
    }

    fun read(name: String): ByteArray? = runCatching { file(name)?.takeIf { it.isFile }?.readBytes() }.getOrNull()
    fun delete(name: String) { file(name)?.delete() }
    private fun file(name: String): File? =
        name.takeIf { it == File(it).name && it.endsWith(".jpg") }?.let { File(directory, it) }
}

/** Retains captured bytes across rotation; saved state contains only small local filenames. */
internal class PhotoCaptureDraftViewModel(
    private val savedState: SavedStateHandle,
    private val files: CapturePhotoFiles
) : ViewModel() {
    private val mutex = Mutex()
    private val _images = MutableStateFlow<List<ByteArray>>(emptyList())
    val images = _images.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    /** Bumped on [clear] so in-flight imports from a prior session never publish. */
    private var sessionGeneration = 0L
    /** Photo cap for the active AI setup; Groq's Qwen models accept three (#712). */
    private var maxImages = 10

    fun setMaxImages(value: Int) {
        maxImages = value.coerceAtLeast(1)
    }
    /** Ops queued before they acquire [mutex], so Analyze cannot race a pending import. */
    private var pendingOps = 0

    init {
        viewModelScope.launch {
            beginOp()
            mutex.withLock {
                try {
                    val restored = withContext(Dispatchers.IO) {
                        filenames().mapNotNull { name -> files.read(name)?.let { name to it } }
                    }
                    savedState[PHOTO_FILES] = restored.map { it.first }
                    _images.value = restored.map { it.second }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _error.value = error.message
                } finally {
                    endOp()
                }
            }
        }
    }

    fun append(images: List<ByteArray>) = appendAsync { images }

    /**
     * Reads Photo Picker URIs on IO before persisting them. The picker can hand back cloud-only
     * photos that download on first open, so this must never run in the Activity Result callback.
     * Cap is applied inside the mutex against the active session's image list.
     */
    fun importUris(resolver: ContentResolver, uris: List<Uri>, failureMessage: String) {
        if (uris.isEmpty()) return
        val generation = sessionGeneration
        appendAsync {
            if (generation != sessionGeneration) return@appendAsync emptyList()
            val imported = withContext(Dispatchers.IO) {
                uris.map { uri -> ContentUriBytes.readBounded(resolver, uri) }
            }
            if (generation != sessionGeneration) return@appendAsync emptyList()
            // Reject the whole batch if any selected URI failed (oversized / unreadable)
            // so Analyze never runs on a silently truncated selection.
            if (imported.any { it == null } || imported.none { it != null && it.isNotEmpty() }) {
                throw IllegalStateException(failureMessage)
            }
            imported.filterNotNull()
        }
    }

    private fun appendAsync(load: suspend () -> List<ByteArray>) {
        beginOp()
        val generation = sessionGeneration
        viewModelScope.launch {
            mutex.withLock {
                try {
                    if (generation != sessionGeneration) return@withLock
                    val additions = load().filter { it.isNotEmpty() }
                        .take((maxImages - _images.value.size).coerceAtLeast(0))
                    if (additions.isEmpty()) return@withLock
                    val addedNames = mutableListOf<String>()
                    try {
                        withContext(Dispatchers.IO) { additions.forEach { addedNames += files.write(it) } }
                        if (generation != sessionGeneration) {
                            withContext(Dispatchers.IO) { addedNames.forEach(files::delete) }
                            return@withLock
                        }
                        savedState[PHOTO_FILES] = filenames() + addedNames
                        _images.value += additions
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable + Dispatchers.IO) { addedNames.forEach(files::delete) }
                        throw cancelled
                    } catch (error: Exception) {
                        withContext(Dispatchers.IO) { addedNames.forEach(files::delete) }
                        _error.value = error.message
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _error.value = error.message
                } finally {
                    endOp()
                }
            }
        }
    }

    fun remove(index: Int) {
        beginOp()
        viewModelScope.launch {
            mutex.withLock {
                try {
                    val names = filenames()
                    if (index !in names.indices) return@withLock
                    savedState[PHOTO_FILES] = names.filterIndexed { i, _ -> i != index }
                    _images.value = _images.value.filterIndexed { i, _ -> i != index }
                    withContext(Dispatchers.IO) { files.delete(names[index]) }
                } finally {
                    endOp()
                }
            }
        }
    }

    fun clear() {
        sessionGeneration += 1
        beginOp()
        viewModelScope.launch {
            mutex.withLock {
                try {
                    val old = filenames()
                    savedState[PHOTO_FILES] = emptyList<String>()
                    _images.value = emptyList()
                    _error.value = null
                    withContext(Dispatchers.IO) { old.forEach(files::delete) }
                } finally {
                    endOp()
                }
            }
        }
    }

    fun dismissError() { _error.value = null }
    private fun filenames(): List<String> = savedState[PHOTO_FILES] ?: emptyList()

    private fun beginOp() {
        pendingOps += 1
        _busy.value = true
    }

    private fun endOp() {
        pendingOps = (pendingOps - 1).coerceAtLeast(0)
        if (pendingOps == 0) _busy.value = false
    }

    companion object {
        private const val PHOTO_FILES = "capturePhotoFilenames"
        fun factory(context: Context) = viewModelFactory {
            initializer {
                PhotoCaptureDraftViewModel(createSavedStateHandle(),
                    CapturePhotoFiles(File(context.applicationContext.filesDir, "food-capture-drafts")))
            }
        }
    }
}
