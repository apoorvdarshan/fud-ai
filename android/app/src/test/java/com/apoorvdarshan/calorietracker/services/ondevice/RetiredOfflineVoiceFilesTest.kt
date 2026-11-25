package com.apoorvdarshan.calorietracker.services.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetiredOfflineVoiceFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun cleanupRemovesOnlyLfm3AndItsTemporaryAudio() {
        val noBackup = temp.newFolder("model-storage")
        val cache = temp.newFolder("cache")
        fun fixture(root: File, path: String) = File(root, path).apply {
            parentFile!!.mkdirs()
            writeText("test")
        }
        val model = fixture(noBackup, "local-models/lfm3_audio/model.gguf")
        val partial = fixture(noBackup, "local-models/lfm3_audio/codec.gguf.part")
        val audio = fixture(cache, "fud-offline-voice/process/input.wav")
        val whisper = fixture(noBackup, "local-models/ggml-base.bin")
        val qwen = fixture(noBackup, "local-models/qwen3_asr/tokens.txt")
        val dictation = fixture(cache, "fudai-stt/recording.wav")

        repeat(2) { RetiredOfflineVoiceFiles.cleanUp(noBackup, cache) }
        for (file in listOf(model, partial, audio)) assertFalse(file.exists())
        for (file in listOf(whisper, qwen, dictation)) assertEquals("test", file.readText())
    }
}
