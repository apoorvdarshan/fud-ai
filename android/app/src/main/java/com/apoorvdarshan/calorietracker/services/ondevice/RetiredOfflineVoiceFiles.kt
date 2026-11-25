package com.apoorvdarshan.calorietracker.services.ondevice

import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** Removes only the retired LFM3 download and its private temporary audio. */
internal object RetiredOfflineVoiceFiles {
    @OptIn(ExperimentalPathApi::class)
    fun cleanUp(noBackupFiles: File, cache: File) {
        listOf(File(noBackupFiles, "local-models/lfm3_audio"), File(cache, "fud-offline-voice"))
            .forEach { folder -> runCatching { folder.toPath().deleteRecursively() } }
    }
}
