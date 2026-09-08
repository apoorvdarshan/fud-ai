package com.apoorvdarshan.calorietracker.ui.home

import android.content.Context
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

    init {
        viewModelScope.launch {
            mutex.withLock {
                _busy.value = true
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
                    _busy.value = false
                }
            }
        }
    }

    fun append(images: List<ByteArray>) {
        viewModelScope.launch {
            mutex.withLock {
                _busy.value = true
                _error.value = null
                val addedNames = mutableListOf<String>()
                try {
                    val additions = images.filter { it.isNotEmpty() }.take((10 - _images.value.size).coerceAtLeast(0))
                    withContext(Dispatchers.IO) { additions.forEach { addedNames += files.write(it) } }
                    savedState[PHOTO_FILES] = filenames() + addedNames
                    _images.value += additions
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) { addedNames.forEach(files::delete) }
                    throw cancelled
                } catch (error: Exception) {
                    withContext(Dispatchers.IO) { addedNames.forEach(files::delete) }
                    _error.value = error.message
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    fun remove(index: Int) {
        viewModelScope.launch {
            mutex.withLock {
                val names = filenames()
                if (index !in names.indices) return@withLock
                savedState[PHOTO_FILES] = names.filterIndexed { i, _ -> i != index }
                _images.value = _images.value.filterIndexed { i, _ -> i != index }
                withContext(Dispatchers.IO) { files.delete(names[index]) }
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            mutex.withLock {
                val old = filenames()
                savedState[PHOTO_FILES] = emptyList<String>()
                _images.value = emptyList()
                _error.value = null
                withContext(Dispatchers.IO) { old.forEach(files::delete) }
            }
        }
    }

    fun dismissError() { _error.value = null }
    private fun filenames(): List<String> = savedState[PHOTO_FILES] ?: emptyList()

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
