package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.runtime.saveable.SaverScope
import com.apoorvdarshan.calorietracker.models.SpeechLanguage
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputDraftStateTest {
    @Test
    fun nativeRecordingRestoresDisplayedPartialAndCommittedTextWithoutListeningAgain() {
        val draft = VoiceInputDraftState(SpeechProvider.NATIVE, SpeechLanguage.HINDI).apply {
            phase = VoicePhase.RECORDING
            autoStartAttempted = true
            committed = "Two apples"
            transcript = "Two apples and one banana"
        }

        val restored = restore(draft)

        assertEquals("Two apples and one banana", restored.transcript)
        assertEquals("Two apples", restored.committed)
        assertEquals(VoicePhase.REVIEWING, restored.phase)
        assertEquals(SpeechProvider.NATIVE, restored.provider)
        assertEquals(SpeechLanguage.HINDI, restored.speechLanguage)
        assertTrue(restored.autoStartAttempted)
    }

    @Test
    fun editedReviewTextSurvivesRepeatedRecreation() {
        val draft = VoiceInputDraftState(SpeechProvider.NATIVE, SpeechLanguage.DEVICE).apply {
            phase = VoicePhase.REVIEWING
            autoStartAttempted = true
            committed = "One apple"
            transcript = "Three apples and milk"
        }

        val restored = restore(restore(draft))

        assertEquals("Three apples and milk", restored.transcript)
        assertEquals(VoicePhase.REVIEWING, restored.phase)
        assertTrue(restored.autoStartAttempted)
    }

    @Test
    fun remoteAudioRetainsFilenameForExplicitTranscriptionAfterRecordingOrUpload() {
        for (previousPhase in listOf(VoicePhase.RECORDING, VoicePhase.TRANSCRIBING)) {
            val draft = VoiceInputDraftState(SpeechProvider.OPENAI, SpeechLanguage.ENGLISH).apply {
                phase = previousPhase
                autoStartAttempted = true
                recordedFilePath = "/cache/fudai-stt/rec-123.wav"
            }

            val restored = restore(draft)

            assertEquals("/cache/fudai-stt/rec-123.wav", restored.recordedFilePath)
            assertEquals("", restored.transcript)
            assertEquals(VoicePhase.REVIEWING, restored.phase)
            assertEquals(SpeechProvider.OPENAI, restored.provider)
            assertEquals(SpeechLanguage.ENGLISH, restored.speechLanguage)
            assertTrue(restored.autoStartAttempted)
        }
    }

    @Test
    fun interruptedEmptyRecordingReturnsToIdleWithoutAnotherAutomaticStart() {
        val draft = VoiceInputDraftState(SpeechProvider.NATIVE, SpeechLanguage.DEVICE).apply {
            phase = VoicePhase.RECORDING
            autoStartAttempted = true
        }

        val restored = restore(draft)

        assertEquals(VoicePhase.IDLE, restored.phase)
        assertTrue(restored.autoStartAttempted)
        assertNull(restored.recordedFilePath)
    }

    @Test
    fun onlyInitialPresentationCanAutomaticallyStartRecording() {
        val initial = VoiceInputDraftState(SpeechProvider.NATIVE, SpeechLanguage.DEVICE)
        assertFalse(initial.autoStartAttempted)

        val restored = restore(initial)

        assertTrue(restored.autoStartAttempted)
        assertEquals(VoicePhase.IDLE, restored.phase)
    }

    private fun restore(draft: VoiceInputDraftState): VoiceInputDraftState {
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = value is String || value is Boolean
        }
        val saved = with(VoiceInputDraftState.Saver) { scope.save(draft) }
        return requireNotNull(VoiceInputDraftState.Saver.restore(requireNotNull(saved)))
    }
}
