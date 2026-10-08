package com.apoorvdarshan.calorietracker.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.models.WeightGoal
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import com.apoorvdarshan.calorietracker.services.ai.AiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Sealed wrapper around chip text — either a resource (for our preset chips,
 * so they translate) or a literal string (for user-typed sends, which already
 * go through the localized input path).
 */
sealed class CoachError {
    data class FromResource(val resId: Int) : CoachError()
    data class Literal(val message: String) : CoachError()
}

data class CoachUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val errorRes: Int? = null,
    val suggestions: List<Int> = emptyList()
)

class CoachViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(CoachUiState())
    val ui: StateFlow<CoachUiState> = _ui.asStateFlow()

    init {
        container.chatRepository.messages
            .onEach { _ui.value = _ui.value.copy(messages = it) }
            .launchIn(viewModelScope)

        // Keep chips current when either the user's goal or their workout history changes.
        combine(
            container.profileRepository.profile,
            container.workoutRepository.completedSessions
        ) { profile, sessions ->
            chipsFor(profile?.goal, hasWorkoutSessions = sessions.isNotEmpty())
        }
            .onEach { suggestions -> _ui.value = _ui.value.copy(suggestions = suggestions) }
            .launchIn(viewModelScope)
    }

    private fun chipsFor(goal: WeightGoal?, hasWorkoutSessions: Boolean): List<Int> {
        val goalChips = when (goal) {
            WeightGoal.LOSE -> listOf(
                R.string.coach_chip_predict_30_days,
                R.string.coach_chip_lose_faster,
                R.string.coach_chip_eating_too_much,
                R.string.coach_chip_what_dinner
            )
            WeightGoal.GAIN -> listOf(
                R.string.coach_chip_predict_30_days,
                R.string.coach_chip_gain_healthy,
                R.string.coach_chip_eating_enough,
                R.string.coach_chip_high_protein
            )
            WeightGoal.MAINTAIN -> listOf(
                R.string.coach_chip_holding_weight,
                R.string.coach_chip_average_intake,
                R.string.coach_chip_macro_suggestions,
                R.string.coach_chip_trend
            )
            else -> listOf(
                R.string.coach_chip_doing_this_week,
                R.string.coach_chip_predict_30_days,
                R.string.coach_chip_log_advice
            )
        }
        return if (hasWorkoutSessions) listOf(R.string.coach_chip_training) + goalChips else goalChips
    }

    fun send(userText: String, imageBytes: ByteArray? = null, thumbnailBytes: ByteArray? = null) {
        val trimmed = userText.trim()
        if ((trimmed.isBlank() && imageBytes == null) || _ui.value.sending) return
        val text = trimmed.ifEmpty { "Analyze this image." }
        viewModelScope.launch {
            val userMsg = ChatMessage(
                role = ChatMessage.Role.USER,
                content = text,
                attachmentImageBase64 = thumbnailBytes?.let { Base64.getEncoder().encodeToString(it) }
            )
            container.chatRepository.append(userMsg)
            _ui.value = _ui.value.copy(sending = true, error = null, errorRes = null)
            try {
                val history = container.chatRepository.contextMessages(limit = 20).dropLast(1) // exclude the just-appended user msg — it's passed separately
                val profile = container.profileRepository.current()
                    ?: return@launch run {
                        _ui.value = _ui.value.copy(
                            sending = false,
                            errorRes = R.string.coach_no_profile_error
                        )
                    }
                val weights = container.weightRepository.entries.first()
                val bodyFats = container.bodyFatRepository.entries.first()
                val measurements = container.bodyMeasurementRepository.entries.first()
                val foods = container.foodRepository.entries.first()
                val fastingSessions = container.fastingRepository.sessions.first()
                val heightMetric = container.prefs.heightUnit.first() == "cm"
                val weightMetric = container.prefs.weightUnit.first() == "kg"
                val workoutState = container.workoutRepository.snapshot()

                val reply = container.chatService.sendMessage(
                    history = history,
                    newUserMessage = text,
                    profile = profile,
                    weights = weights,
                    bodyFats = bodyFats,
                    measurements = measurements,
                    foods = foods,
                    fastingSessions = fastingSessions,
                    heightMetric = heightMetric,
                    weightMetric = weightMetric,
                    imageBytes = imageBytes,
                    workoutSessions = workoutState.completedSessions,
                    workoutPlans = workoutState.dayPlans.values.toList(),
                    workoutPreferences = workoutState.preferences,
                    workoutPlanWeightUnit = if (weightMetric) WorkoutWeightUnit.KG else WorkoutWeightUnit.LBS
                )
                container.chatRepository.append(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = reply.trim()))
                _ui.value = _ui.value.copy(sending = false)
            } catch (e: AiError) {
                // Hosted quota / no-plan errors open the paywall flow; others show localized text.
                val routed = container.hostedUi.handle(e)
                _ui.value = _ui.value.copy(sending = false, error = if (routed) null else e.userMessage(container.appContext))
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(
                    sending = false,
                    error = e.localizedMessage,
                    errorRes = if (e.localizedMessage.isNullOrBlank()) R.string.coach_chat_failed else null
                )
            }
        }
    }

    fun resetConversation() {
        viewModelScope.launch { container.chatRepository.clear() }
    }

    fun dismissError() { _ui.value = _ui.value.copy(error = null, errorRes = null) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoachViewModel(container) as T
    }
}
