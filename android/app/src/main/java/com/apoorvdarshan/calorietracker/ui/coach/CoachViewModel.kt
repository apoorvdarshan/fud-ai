package com.apoorvdarshan.calorietracker.ui.coach

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.ExerciseItem
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.models.CoachProposalCollector
import com.apoorvdarshan.calorietracker.models.WeightGoal
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.ai.CoachProposalApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID

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
    val suggestions: List<Int> = emptyList(),
    /** Proposal cards whose Add or Dismiss is being saved; their buttons are disabled meanwhile. */
    val resolvingProposalIds: Set<String> = emptySet()
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
        val text = trimmed.ifEmpty { container.appContext.getString(R.string.coach_analyze_image_default) }
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
                val exerciseLibrary = currentExerciseLibrary()
                val proposals = CoachProposalCollector()

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
                    workoutPlanWeightUnit = if (weightMetric) WorkoutWeightUnit.KG else WorkoutWeightUnit.LBS,
                    exerciseLibrary = exerciseLibrary,
                    proposals = proposals
                )
                container.chatRepository.append(
                    ChatMessage(role = ChatMessage.Role.ASSISTANT, content = reply.trim(), proposals = proposals.proposals)
                )
                _ui.value = _ui.value.copy(sending = false)
            } catch (e: AiError) {
                logFailure(e)
                // Hosted quota / no-plan errors open the paywall flow; others show localized text.
                val routed = container.hostedUi.handle(e)
                _ui.value = _ui.value.copy(sending = false, error = if (routed) null else e.userMessage(container.appContext))
            } catch (e: Throwable) {
                // Leaving the screen cancels the send; that is not a chat failure.
                ensureActive()
                logFailure(e)
                _ui.value = _ui.value.copy(
                    sending = false,
                    error = e.localizedMessage,
                    errorRes = if (e.localizedMessage.isNullOrBlank()) R.string.coach_chat_failed else null
                )
            }
        }
    }

    /**
     * The user confirmed a Coach proposal. The repository runs the write only while the card is
     * pending, so a second tap (or a card already added) never writes again.
     */
    fun addProposal(messageId: UUID, proposalId: String) = resolveProposal(proposalId) {
        var failure: Int? = null
        container.chatRepository.addProposal(messageId, proposalId) { proposal ->
            when (CoachProposalApplier.apply(proposal, container.workoutRepository, container.imageStore, currentExerciseLibrary())) {
                CoachProposalApplier.Outcome.APPLIED -> true
                CoachProposalApplier.Outcome.EXERCISE_MISSING -> {
                    failure = R.string.coach_proposal_exercise_missing
                    false
                }
                CoachProposalApplier.Outcome.NAME_TAKEN -> {
                    failure = R.string.coach_proposal_name_taken
                    false
                }
                CoachProposalApplier.Outcome.SAVE_FAILED -> {
                    failure = R.string.coach_proposal_save_failed
                    false
                }
            }
        }
        failure?.let { _ui.value = _ui.value.copy(error = null, errorRes = it) }
    }

    fun dismissProposal(messageId: UUID, proposalId: String) = resolveProposal(proposalId) {
        container.chatRepository.dismissProposal(messageId, proposalId)
    }

    private fun resolveProposal(proposalId: String, work: suspend () -> Unit) {
        if (proposalId in _ui.value.resolvingProposalIds) return
        _ui.value = _ui.value.copy(resolvingProposalIds = _ui.value.resolvingProposalIds + proposalId, error = null, errorRes = null)
        viewModelScope.launch {
            try {
                work()
            } catch (e: Throwable) {
                ensureActive()
                logFailure(e)
                _ui.value = _ui.value.copy(error = null, errorRes = R.string.coach_proposal_save_failed)
            } finally {
                _ui.value = _ui.value.copy(resolvingProposalIds = _ui.value.resolvingProposalIds - proposalId)
            }
        }
    }

    /** Built-in catalog plus the user's custom activities and exercises. */
    private suspend fun currentExerciseLibrary(): List<ExerciseItem> {
        val workoutState = container.workoutRepository.snapshot()
        return withContext(Dispatchers.IO) {
            ExerciseRepository.get(container.appContext)
                .includingActivities(workoutState.customActivities + workoutState.userExercises)
                .exercises
        }
    }

    fun resetConversation() {
        viewModelScope.launch { container.chatRepository.clear() }
    }

    fun dismissError() { _ui.value = _ui.value.copy(error = null, errorRes = null) }

    private fun logFailure(error: Throwable) {
        // Log is a stub in JVM unit tests; logging must never change the send's outcome.
        runCatching { Log.w(TAG, "Coach chat failed: ${coachFailureSummary(error)}") }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoachViewModel(container) as T
    }

    private companion object {
        const val TAG = "FudAICoach"
    }
}

/**
 * Why a send failed, for Logcat: exception types (with causes) and the top code locations.
 * Messages are left out because provider/HTTP errors can carry URLs, response text or keys.
 */
internal fun coachFailureSummary(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(5).joinToString(" <- ") { it.javaClass.name }
    val frames = error.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    return if (frames.isEmpty()) causes else "$causes at $frames"
}
