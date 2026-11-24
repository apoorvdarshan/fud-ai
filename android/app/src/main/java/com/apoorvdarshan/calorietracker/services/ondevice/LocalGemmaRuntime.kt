package com.apoorvdarshan.calorietracker.services.ondevice

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Real in-process Gemma inference backed by the downloaded LiteRT-LM package. */
class LocalGemmaRuntime(
    context: Context,
    private val models: LocalModelManager
) {
    private val cacheDir = context.applicationContext.cacheDir.absolutePath
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var engineModel: LocalModelId? = null

    init {
        for (id in listOf(LocalModelId.GEMMA_4_E2B, LocalModelId.GEMMA_4_E4B)) {
            models.registerReleaseHook(id) { close(id) }
        }
    }

    suspend fun generate(
        id: LocalModelId,
        prompt: String,
        images: List<ByteArray> = emptyList(),
        maxOutputTokens: Int,
        systemInstruction: String? = null
    ): String = mutex.withLock {
        check(models.isExecutable(id)) { "This Gemma model is not installed on this device." }
        withContext(Dispatchers.IO) {
            if (engineModel != id) closeEngine()
            val activeEngine = engine ?: initializeEngine(id).also { engine = it; engineModel = id }
            val config = ConversationConfig(
                systemInstruction = systemInstruction?.takeIf(String::isNotBlank)?.let(Contents::of)
                    ?: Contents.of("You are a helpful assistant."),
                maxOutputToken = maxOutputTokens.coerceIn(1, 4_096),
                thinkingConfig = ThinkingConfig(enableThinking = false)
            )
            activeEngine.createConversation(config).use { conversation ->
                val contents = Contents.of(buildList {
                    images.forEach { add(Content.ImageBytes(it)) }
                    add(Content.Text(prompt))
                })
                val response = conversation.sendMessage(contents)
                response.contents.contents.filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
                    .trim()
                    .ifEmpty { error("Gemma returned an empty response.") }
            }
        }
    }

    private suspend fun close(id: LocalModelId) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (engineModel == id) closeEngine()
        }
    }

    private fun closeEngine() {
        val previous = engine
        engine = null
        engineModel = null
        previous?.close()
    }

    private fun initializeEngine(id: LocalModelId): Engine = try {
        // Google's Android model allowlist recommends GPU for both the Gemma 4 decoder and
        // vision encoder. Some vendor drivers still fail during initialization, so retain a
        // complete CPU multimodal fallback rather than making the downloaded model unusable.
        initializedEngine(id, Backend.GPU(), Backend.GPU())
    } catch (gpuError: Throwable) {
        Log.w(TAG, "GPU initialization failed; retrying Gemma on CPU.", gpuError)
        try {
            initializedEngine(id, Backend.CPU(), Backend.CPU())
        } catch (cpuError: Throwable) {
            cpuError.addSuppressed(gpuError)
            throw cpuError
        }
    }

    private fun initializedEngine(id: LocalModelId, backend: Backend, visionBackend: Backend): Engine {
        val candidate = Engine(
            EngineConfig(
                modelPath = models.modelFile(id).absolutePath,
                backend = backend,
                visionBackend = visionBackend,
                maxNumTokens = 4_096,
                maxNumImages = 8,
                cacheDir = cacheDir
            )
        )
        return try {
            candidate.initialize()
            candidate
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw error
        }
    }

    private companion object {
        const val TAG = "LocalGemmaRuntime"
    }
}
