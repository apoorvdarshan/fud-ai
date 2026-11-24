package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.models.CoachExerciseProposal
import com.apoorvdarshan.calorietracker.models.resolvingProposal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Coach chat conversation history. Port of iOS ChatStore.
 * The full history stays visible to the UI; the LLM payload is capped
 * at the last 20 messages elsewhere (ChatService).
 */
class ChatRepository(private val prefs: PreferencesStore) {
    val messages: Flow<List<ChatMessage>> = prefs.chatHistory

    /** Serializes read-modify-write updates so a reply and a card tap cannot overwrite each other. */
    private val mutex = Mutex()
    private val _resetRevision = MutableStateFlow(0L)
    val resetRevision = _resetRevision.asStateFlow()

    suspend fun append(message: ChatMessage, unlessResetSince: Long? = null) = mutex.withLock {
        if (unlessResetSince != null && _resetRevision.value != unlessResetSince) return@withLock
        val current = prefs.chatHistory.first()
        prefs.setChatHistory(current + message)
    }

    suspend fun replaceAll(messages: List<ChatMessage>) = mutex.withLock {
        prefs.setChatHistory(messages)
    }

    suspend fun clear() = mutex.withLock {
        prefs.setChatHistory(emptyList())
        _resetRevision.value += 1
    }

    /** A voice reply queued before reset must never repopulate the cleared chat. */
    suspend fun appendVoiceTurn(revision: Long, messages: List<ChatMessage>) = mutex.withLock {
        if (_resetRevision.value != revision || !prefs.voiceAssistantPreferences.first().saveToCoach) return@withLock
        prefs.setChatHistory(prefs.chatHistory.first() + messages)
    }

    /** Last N messages for LLM payload context, trimmed to keep token cost flat. */
    suspend fun contextMessages(limit: Int = 20): List<ChatMessage> =
        prefs.chatHistory.first().takeLast(limit)

    /**
     * The user tapped Add. [apply] runs only while the proposal is still pending, and the card is
     * marked added only after it returns true; a second tap never writes twice.
     */
    suspend fun addProposal(
        messageId: UUID,
        proposalId: String,
        apply: suspend (CoachExerciseProposal) -> Boolean
    ): Boolean = resolve(messageId, proposalId, CoachExerciseProposal.Status.ADDED, apply)

    /** The user tapped Dismiss. Nothing is written, and a resolved card keeps its outcome. */
    suspend fun dismissProposal(messageId: UUID, proposalId: String): Boolean =
        resolve(messageId, proposalId, CoachExerciseProposal.Status.DISMISSED)

    private suspend fun resolve(
        messageId: UUID,
        proposalId: String,
        status: CoachExerciseProposal.Status,
        apply: suspend (CoachExerciseProposal) -> Boolean = { true }
    ): Boolean = mutex.withLock {
        val updated = prefs.chatHistory.first().resolvingProposal(messageId, proposalId, status, apply)
            ?: return@withLock false
        prefs.setChatHistory(updated)
        true
    }
}
