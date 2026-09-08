package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoCaptureDraftRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun capturedPhotosRestoreFromFilenamesAndDiscardRemovesOnlyDraftFiles() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "capture-test-${UUID.randomUUID()}")
        val files = CapturePhotoFiles(directory)
        val state = SavedStateHandle()
        lateinit var original: PhotoCaptureDraftViewModel
        var restored: PhotoCaptureDraftViewModel? = null
        try {
            compose.runOnIdle { original = PhotoCaptureDraftViewModel(state, files) }
            val first = byteArrayOf(1, 2, 3)
            val second = byteArrayOf(4, 5, 6)
            compose.runOnIdle { original.append(listOf(first, second)) }
            compose.waitUntil(5_000) { original.images.value.size == 2 }
            val saved = mutableMapOf<String, Any?>()
            compose.runOnIdle {
                state.keys().forEach { saved[it] = state.get<Any?>(it) }
                assertTrue(saved.values.all { value -> value is List<*> && value.all { it is String } })
                original.viewModelScope.cancel()
                restored = PhotoCaptureDraftViewModel(SavedStateHandle(saved), files)
            }
            compose.waitUntil(5_000) { restored!!.images.value.size == 2 }
            assertArrayEquals(first, restored!!.images.value[0])
            assertArrayEquals(second, restored!!.images.value[1])
            compose.runOnIdle { restored!!.remove(0) }
            compose.waitUntil(5_000) { restored!!.images.value.size == 1 && directory.listFiles()?.size == 1 }
            assertArrayEquals(second, restored!!.images.value.single())
            compose.runOnIdle { restored!!.clear() }
            compose.waitUntil(5_000) { restored!!.images.value.isEmpty() && directory.listFiles()?.isEmpty() == true }
        } finally {
            compose.runOnIdle {
                original.viewModelScope.cancel()
                restored?.viewModelScope?.cancel()
            }
            directory.deleteRecursively()
        }
    }

    @Test fun removingOneDraftDoesNotDeleteAnotherDraftPhoto() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "capture-test-${UUID.randomUUID()}")
        try {
            val files = CapturePhotoFiles(directory)
            val first = files.write(byteArrayOf(1))
            val second = files.write(byteArrayOf(2))
            files.delete(first)
            assertNull(files.read(first))
            assertArrayEquals(byteArrayOf(2), files.read(second))
            assertNull(files.read("../outside.jpg"))
        } finally { directory.deleteRecursively() }
    }
}
