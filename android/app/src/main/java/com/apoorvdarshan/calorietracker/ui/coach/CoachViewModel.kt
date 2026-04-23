package com.apoorvdarshan.calorietracker.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.models.WeightGoal
import com.apoorvdarshan.calorietracker.services.ai.AiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class CoachUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val suggestions: List<String> = emptyList()
)

class CoachViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(CoachUiState())
    val ui: StateFlow<CoachUiState> = _ui.asStateFlow()

    init {
        container.chatRepository.messages
            .onEach { _ui.value = _ui.value.copy(messages = it) }
            .launchIn(viewModelScope)

        // Live-subscribe to profile so chips update when the user changes goal in Settings.
        container.profileRepository.profile
            .onEach { p -> _ui.value = _ui.value.copy(suggestions = chipsFor(p?.goal)) }
            .launchIn(viewModelScope)
    }

    private fun chipsFor(goal: WeightGoal?): List<String> = when (goal) {
        WeightGoal.LOSE -> listOf(
            "What's my expected weight in 30 days?",
            "How do I lose weight faster safely?",
            "Am I eating too much?",
            "What should I eat for dinner?"
        )
        WeightGoal.GAIN -> listOf(
            "What's my expected weight in 30 days?",
            "How do I gain weight healthily?",
            "Am I eating enough?",
            "High-protein foods I can add?"
        )
        WeightGoal.MAINTAIN -> listOf(
            "Am I holding my weight?",
            "What's my average intake?",
            "Macro suggestions?",
            "How's my trend?"
        )
        else -> listOf(
            "How am I doing this week?",
            "What's my expected weight in 30 days?",
            "Any advice based on my log?"
        )
    }

    fun send(userText: String) {
        if (userText.isBlank() || _ui.value.sending) return
        viewModelScope.launch {
            val userMsg = ChatMessage(role = ChatMessage.Role.USER, content = userText)
            container.chatRepository.append(userMsg)
            _ui.value = _ui.value.copy(sending = true, error = null)
            try {
                val history = container.chatRepository.contextMessages(limit = 20).dropLast(1) // exclude the just-appended user msg — it's passed separately
                val profile = container.profileRepository.current()
                    ?: return@launch run {
                        _ui.value = _ui.value.copy(
                            sending = false,
                            error = "No profile yet. Finish onboarding first."
                        )
                    }
                val weights = container.weightRepository.entries.first()
                val foods = container.foodRepository.entries.first()
                val useMetric = container.prefs.useMetric.first()

                val reply = container.chatService.sendMessage(
                    history = history,
                    newUserMessage = userText,
                    profile = profile,
                    weights = weights,
                    foods = foods,
                    useMetric = useMetric
                )
                container.chatRepository.append(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = reply.trim()))
                _ui.value = _ui.value.copy(sending = false)
            } catch (e: AiError) {
                _ui.value = _ui.value.copy(sending = false, error = e.message)
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(sending = false, error = e.localizedMessage ?: "Chat failed")
            }
        }
    }

    fun resetConversation() {
        viewModelScope.launch { container.chatRepository.clear() }
    }

    fun dismissError() { _ui.value = _ui.value.copy(error = null) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoachViewModel(container) as T
    }
}
