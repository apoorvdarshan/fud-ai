package com.apoorvdarshan.calorietracker.services.ondevice

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Real in-process Whisper Base transcription backed by whisper.cpp. */
class LocalWhisperRuntime(
    context: Context,
    private val models: LocalModelManager
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var model: WhisperModel? = null

    init {
        models.registerReleaseHook(LocalModelId.WHISPER_BASE, ::close)
    }

    fun isReady(): Boolean = models.isExecutable(LocalModelId.WHISPER_BASE)

    suspend fun transcribe(audio: File, languageCode: String?): String = mutex.withLock {
        check(isReady()) { "Whisper Base is not installed on this device." }
        withContext(Dispatchers.IO) {
            val activeModel = model ?: Whisper.loadModel(
                appContext,
                models.modelFile(LocalModelId.WHISPER_BASE).absolutePath
            ).also { model = it }
            Whisper.transcribe(
                activeModel,
                audio.absolutePath,
                WhisperConfig(
                    language = languageCode?.takeIf(String::isNotBlank) ?: "auto",
                    threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                    printTimestamps = false
                )
            ).text.trim()
        }
    }

    suspend fun close() = mutex.withLock {
        withContext(Dispatchers.IO) {
            model?.let(Whisper::releaseModel)
            model = null
        }
    }
}
