package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.apoorvdarshan.calorietracker.models.SpeechLanguage
import com.apoorvdarshan.calorietracker.models.SpeechProvider

internal enum class VoicePhase { IDLE, RECORDING, REVIEWING, TRANSCRIBING }

/** Only draft text and a cache-file path enter saved state; microphone resources never do. */
@Stable
internal class VoiceInputDraftState(
    val provider: SpeechProvider,
    val speechLanguage: SpeechLanguage,
    transcript: String = "",
    committed: String = "",
    phase: VoicePhase = VoicePhase.IDLE,
    autoStartAttempted: Boolean = false,
    recordedFilePath: String? = null,
    error: String? = null
) {
    var transcript by mutableStateOf(transcript)
    var committed by mutableStateOf(committed)
    var phase by mutableStateOf(phase)
    var autoStartAttempted by mutableStateOf(autoStartAttempted)
    var recordedFilePath by mutableStateOf(recordedFilePath)
    var error by mutableStateOf(error)

    companion object {
        val Saver = listSaver<VoiceInputDraftState, Any>(
            save = {
                listOf(
                    it.provider.name,
                    it.speechLanguage.name,
                    it.transcript,
                    it.committed,
                    it.phase.name,
                    it.recordedFilePath.orEmpty(),
                    it.error.orEmpty()
                )
            },
            restore = { saved ->
                val transcript = saved[2] as String
                val recordedFilePath = (saved[5] as String).ifEmpty { null }
                val previousPhase = VoicePhase.valueOf(saved[4] as String)
                // A new Activity owns no live recorder or transcription coroutine.
                // Preserve the captured material for review or an explicit retry.
                val phase = when (previousPhase) {
                    VoicePhase.RECORDING, VoicePhase.TRANSCRIBING -> {
                        if (transcript.isNotBlank() || recordedFilePath != null) VoicePhase.REVIEWING
                        else VoicePhase.IDLE
                    }
                    else -> previousPhase
                }
                VoiceInputDraftState(
                    provider = SpeechProvider.valueOf(saved[0] as String),
                    speechLanguage = SpeechLanguage.valueOf(saved[1] as String),
                    transcript = transcript,
                    committed = saved[3] as String,
                    phase = phase,
                    // Only the original presentation may auto-start, even if it
                    // was recreated before its first launch effect could run.
                    autoStartAttempted = true,
                    recordedFilePath = recordedFilePath,
                    error = (saved[6] as String).ifEmpty { null }
                )
            }
        )
    }
}
