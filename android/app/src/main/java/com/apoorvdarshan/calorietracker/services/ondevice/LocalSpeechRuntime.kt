package com.apoorvdarshan.calorietracker.services.ondevice

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Recorded, offline transcription. Neither native recognizer accepts a language hint. */
class LocalSpeechRuntime(private val models: LocalModelManager) {
    private val mutex = Mutex()

    init {
        // A delete must wait for native inference to release the files.
        for (id in listOf(LocalModelId.PARAKEET_V3, LocalModelId.QWEN3_ASR)) {
            models.registerReleaseHook(id) { mutex.withLock { } }
        }
    }

    fun isReady(id: LocalModelId): Boolean = models.isExecutable(id)

    suspend fun transcribe(id: LocalModelId, audio: File): String = mutex.withLock {
        check(isReady(id))
        withContext(Dispatchers.IO) {
            val wave = WaveReader.readWave(audio.absolutePath)
            require(wave.sampleRate == 16_000 && wave.samples.isNotEmpty())
            val dir = checkNotNull(models.modelFile(id).parentFile)
            val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig(id, dir)))
            try {
                speechChunks(wave.samples, wave.sampleRate).mapNotNull { range ->
                    ensureActive()
                    var text = ""
                    recognizer.createStream().use { stream ->
                        stream.acceptWaveform(wave.samples.copyOfRange(range.first, range.last + 1), wave.sampleRate)
                        recognizer.decode(stream)
                        text = recognizer.getResult(stream).text.trim()
                    }
                    text.takeIf(String::isNotEmpty)
                }.joinToString(" ")
            } finally {
                recognizer.release()
            }
        }
    }

    private fun modelConfig(id: LocalModelId, dir: File): OfflineModelConfig {
        fun path(name: String) = File(dir, name).absolutePath
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        return when (id) {
            LocalModelId.PARAKEET_V3 -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path("encoder.int8.onnx"),
                    decoder = path("decoder.int8.onnx"),
                    joiner = path("joiner.int8.onnx")
                ),
                tokens = path("tokens.txt"),
                numThreads = threads
            )
            LocalModelId.QWEN3_ASR -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = path("conv_frontend.onnx"),
                    encoder = path("encoder.int8.onnx"),
                    decoder = path("decoder.int8.onnx"),
                    tokenizer = path("tokenizer"),
                    maxNewTokens = 256
                ),
                numThreads = threads
            )
            else -> error("Not a Sherpa speech model: $id")
        }
    }
}

/** Bound memory and Qwen's context without silently dropping the end of long recordings. */
internal fun speechChunks(samples: FloatArray, sampleRate: Int): List<IntRange> {
    val limit = sampleRate * 20
    val window = (sampleRate / 50).coerceAtLeast(1)
    return buildList {
        var start = 0
        while (start < samples.size) {
            val maxEnd = start + minOf(limit, samples.size - start)
            val end = if (maxEnd == samples.size) maxEnd else {
                // Prefer a quiet 20 ms window in the final 3 seconds.
                var best = maxEnd
                var quietest = Double.POSITIVE_INFINITY
                for (candidate in (maxEnd - window) downTo (maxEnd - 3 * sampleRate) step window) {
                    var energy = 0.0
                    for (i in candidate until candidate + window) energy += samples[i] * samples[i]
                    if (energy < quietest) {
                        quietest = energy
                        best = candidate + window / 2
                    }
                }
                best
            }
            add(start until end)
            start = end
        }
    }
}
