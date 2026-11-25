package com.apoorvdarshan.calorietracker.services.ondevice

import androidx.annotation.Keep
import java.io.File

@Keep
internal object OfflineVoiceNative {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("fud_offline_voice")
            true
        } catch (_: LinkageError) {
            false
        }
    }

    external fun create(): Long
    external fun cancel(turn: Long)
    external fun destroy(turn: Long)
    external fun reply(turn: Long, model: String, projector: String, input: String, output: String): Int
    external fun agentReply(turn: Long, model: String, projector: String, input: String, output: String,
                            tools: String, continuation: String, text: String): Int
}

/** Close after reply returns; cancellation is safe from another thread. */
internal class OfflineVoiceTurn : AutoCloseable {
    private var turn = OfflineVoiceNative.create()
    init { check(turn != 0L) }

    @Synchronized fun cancel() {
        if (turn != 0L) OfflineVoiceNative.cancel(turn)
    }

    override fun close() = synchronized(this) {
        if (turn != 0L) {
            OfflineVoiceNative.destroy(turn)
            turn = 0L
        }
    }

    fun reply(files: List<File>, input: File, output: File): Int =
        OfflineVoiceNative.reply(turn, files[0].path, files[1].path, input.path, output.path)

    fun agentReply(files: List<File>, input: File, output: File, tools: String, continuation: String, text: File): Int =
        OfflineVoiceNative.agentReply(turn, files[0].path, files[1].path, input.path, output.path,
            tools, continuation, text.path)
}
