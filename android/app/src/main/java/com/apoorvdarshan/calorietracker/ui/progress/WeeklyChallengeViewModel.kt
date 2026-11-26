package com.apoorvdarshan.calorietracker.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.data.WeeklyChallengeFailureKind
import com.apoorvdarshan.calorietracker.data.WeeklyChallengeRepository
import com.apoorvdarshan.calorietracker.data.WeeklyChallengeResult
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeAggregate
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeCategory
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeLeaderboard
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeLeaderboardRow
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeProfileRequest
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeProfileValidator
import com.apoorvdarshan.calorietracker.models.WeeklyChallengePublicProfile
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeReportReason
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeReportRequest
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeReportValidator
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeSocialPlatform
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeWeek
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.services.challenge.WeeklyChallengeAggregationInput
import com.apoorvdarshan.calorietracker.services.challenge.WeeklyChallengeAggregator
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WeeklyChallengeUiError {
    NETWORK,
    AUTH,
    SERVER,
    VALIDATION
}

data class WeeklyChallengeUiState(
    val category: WeeklyChallengeCategory = WeeklyChallengeCategory.OVERALL,
    val weekStart: LocalDate = WeeklyChallengeWeek.startFor(LocalDate.now()),
    val aggregate: WeeklyChallengeAggregate =
        WeeklyChallengeAggregate.empty(WeeklyChallengeWeek.startFor(LocalDate.now())),
    val profile: WeeklyChallengePublicProfile? = null,
    val leaderboard: WeeklyChallengeLeaderboard? = null,
    val blockedParticipants: Map<String, String> = emptyMap(),
    val lastUpdatedEpochMillis: Long? = null,
    val isRefreshing: Boolean = false,
    val isMutating: Boolean = false,
    val isOffline: Boolean = false,
    val pendingRemoteDeletion: Boolean = false,
    val error: WeeklyChallengeUiError? = null,
    val reportConfirmationName: String? = null
) {
    val isJoined: Boolean get() = profile != null && !pendingRemoteDeletion
}

private data class WeeklyChallengeLocalInputs(
    val profile: UserProfile?,
    val foods: List<FoodEntry>,
    val water: List<WaterEntry>,
    val workoutBurns: List<WorkoutSession>,
    val waterTrackingEnabled: Boolean,
    val waterGoalMl: Int
)

class WeeklyChallengeViewModel(private val container: AppContainer) : ViewModel() {
    private val repository: WeeklyChallengeRepository = container.weeklyChallengeRepository
    private val _ui = MutableStateFlow(WeeklyChallengeUiState())
    val ui: StateFlow<WeeklyChallengeUiState> = _ui.asStateFlow()
    private val refreshMutex = Mutex()
    private var visibleRefreshJob: Job? = null
    private var screenVisible: Boolean = false
    private var latestInputs: WeeklyChallengeLocalInputs? = null
    private var lastQueuedAggregate: WeeklyChallengeAggregate? = null

    init {
        viewModelScope.launch {
            repository.retryPendingRemoteDeletion()
            repository.clearStaleSessionIfNeeded()
        }
        viewModelScope.launch {
            repository.state.collect { stored ->
                val current = _ui.value
                val joined = stored.profile != null && repository.hasBearerToken() &&
                    !stored.pendingRemoteDeletion
                _ui.update {
                    it.copy(
                        profile = if (joined) stored.profile else null,
                        leaderboard = if (joined) {
                            stored.cached(current.category, current.weekStart.toString())
                        } else {
                            null
                        },
                        blockedParticipants = stored.blockedParticipants,
                        lastUpdatedEpochMillis = if (joined) {
                            stored.refreshedAt(current.category, current.weekStart.toString())
                        } else {
                            null
                        },
                        pendingRemoteDeletion = stored.pendingRemoteDeletion
                    )
                }
                if (screenVisible && joined && !current.isJoined) {
                    viewModelScope.launch { refreshInternal(showSpinner = true) }
                }
            }
        }

        val waterPreferences = combine(
            container.prefs.waterTrackingEnabled,
            container.prefs.waterDailyGoalMl
        ) { enabled, goal -> enabled to goal }
        viewModelScope.launch {
            combine(
                container.profileRepository.profile,
                container.foodRepository.entries,
                container.waterRepository.entries,
                container.workoutRepository.burnSessions
            ) { profile, foods, water, workouts ->
                WeeklyChallengeLocalInputs(
                    profile = profile,
                    foods = foods,
                    water = water,
                    workoutBurns = workouts,
                    waterTrackingEnabled = false,
                    waterGoalMl = 0
                )
            }.combine(waterPreferences) { inputs, waterPrefs ->
                inputs.copy(
                    waterTrackingEnabled = waterPrefs.first,
                    waterGoalMl = waterPrefs.second
                )
            }.distinctUntilChanged().collect { inputs ->
                latestInputs = inputs
                recomputeAndQueueIfNeeded()
            }
        }
    }

    fun setVisible(visible: Boolean) {
        screenVisible = visible
        if (!visible) {
            visibleRefreshJob?.cancel()
            visibleRefreshJob = null
            return
        }
        if (visibleRefreshJob?.isActive == true) return
        visibleRefreshJob = viewModelScope.launch {
            refreshInternal(showSpinner = true)
            while (isActive) {
                delay(VISIBLE_REFRESH_INTERVAL_MS)
                refreshInternal(showSpinner = false)
            }
        }
    }

    fun selectCategory(category: WeeklyChallengeCategory) {
        if (category == _ui.value.category) return
        _ui.update { it.copy(category = category, error = null, isOffline = false) }
        viewModelScope.launch {
            val stored = repository.stateSnapshot()
            val current = _ui.value
            _ui.update {
                it.copy(
                    leaderboard = if (current.isJoined) {
                        stored.cached(category, current.weekStart.toString())
                    } else null,
                    lastUpdatedEpochMillis = if (current.isJoined) {
                        stored.refreshedAt(category, current.weekStart.toString())
                    } else null
                )
            }
            refreshInternal(showSpinner = true)
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshInternal(showSpinner = true) }
    }

    fun join(
        displayName: String,
        platform: WeeklyChallengeSocialPlatform?,
        handle: String,
        acceptedRules: Boolean,
        eligibilityAccepted: Boolean,
        onSuccess: () -> Unit = {}
    ) {
        val request = WeeklyChallengeProfileValidator.createRequest(
            displayName = displayName,
            platform = platform,
            handle = handle,
            acceptedRules = acceptedRules,
            eligibilityAccepted = eligibilityAccepted
        ) ?: run {
            _ui.update { it.copy(error = WeeklyChallengeUiError.VALIDATION) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isMutating = true, error = null) }
            when (val result = repository.createProfile(request, _ui.value.aggregate)) {
                is WeeklyChallengeResult.Success -> {
                    _ui.update { it.copy(isMutating = false, profile = result.value) }
                    onSuccess()
                    refreshInternal(showSpinner = true)
                }
                is WeeklyChallengeResult.Failure -> {
                    _ui.update { it.copy(isMutating = false, error = result.toUiError()) }
                }
            }
        }
    }

    fun updateProfile(
        displayName: String,
        platform: WeeklyChallengeSocialPlatform?,
        handle: String,
        onSuccess: () -> Unit = {}
    ) {
        val request: WeeklyChallengeProfileRequest = WeeklyChallengeProfileValidator.request(
            displayName,
            platform,
            handle
        ) ?: run {
            _ui.update { it.copy(error = WeeklyChallengeUiError.VALIDATION) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isMutating = true, error = null) }
            when (val result = repository.updateProfile(request)) {
                is WeeklyChallengeResult.Success -> {
                    _ui.update { it.copy(isMutating = false, profile = result.value) }
                    onSuccess()
                    refreshInternal(showSpinner = true)
                }
                is WeeklyChallengeResult.Failure ->
                    _ui.update { it.copy(isMutating = false, error = result.toUiError()) }
            }
        }
    }

    fun leaveAndDelete() {
        viewModelScope.launch {
            _ui.update { it.copy(isMutating = true, error = null) }
            when (val result = repository.leaveAndDelete()) {
                is WeeklyChallengeResult.Success ->
                    _ui.update {
                        it.copy(
                            isMutating = false,
                            profile = null,
                            leaderboard = null,
                            lastUpdatedEpochMillis = null
                        )
                    }
                is WeeklyChallengeResult.Failure ->
                    _ui.update { it.copy(isMutating = false, error = result.toUiError()) }
            }
        }
    }

    fun retryPendingDeletion() {
        viewModelScope.launch {
            _ui.update { it.copy(isMutating = true, error = null) }
            when (val result = repository.retryPendingRemoteDeletion()) {
                is WeeklyChallengeResult.Success ->
                    _ui.update {
                        it.copy(
                            isMutating = false,
                            pendingRemoteDeletion = false,
                            isOffline = false
                        )
                    }
                is WeeklyChallengeResult.Failure ->
                    _ui.update {
                        it.copy(
                            isMutating = false,
                            isOffline = result.kind == WeeklyChallengeFailureKind.NETWORK,
                            error = result.toUiError()
                        )
                    }
            }
        }
    }

    fun report(
        row: WeeklyChallengeLeaderboardRow,
        reason: WeeklyChallengeReportReason,
        details: String,
        onSuccess: () -> Unit = {}
    ) {
        if (row.isViewer) return
        if (!WeeklyChallengeReportValidator.validDetails(details)) {
            _ui.update { it.copy(error = WeeklyChallengeUiError.VALIDATION) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isMutating = true, error = null) }
            val request = WeeklyChallengeReportRequest(
                reportedParticipantId = row.participantId,
                reason = reason,
                details = WeeklyChallengeReportValidator.requestDetails(details)
            )
            when (val result = repository.report(request)) {
                is WeeklyChallengeResult.Success -> {
                    _ui.update {
                        it.copy(isMutating = false, reportConfirmationName = row.displayName)
                    }
                    onSuccess()
                }
                is WeeklyChallengeResult.Failure ->
                    _ui.update { it.copy(isMutating = false, error = result.toUiError()) }
            }
        }
    }

    fun block(row: WeeklyChallengeLeaderboardRow) {
        if (row.isViewer) return
        _ui.update {
            it.copy(
                blockedParticipants = it.blockedParticipants + (row.participantId to row.displayName),
                leaderboard = it.leaderboard?.copy(
                    rankings = it.leaderboard.rankings.filterNot { participant ->
                        participant.participantId == row.participantId
                    },
                    viewer = it.leaderboard.viewer?.takeUnless { participant ->
                        participant.participantId == row.participantId
                    }
                )
            )
        }
        viewModelScope.launch { repository.block(row.participantId, row.displayName) }
    }

    fun unblock(participantId: String) {
        _ui.update { it.copy(blockedParticipants = it.blockedParticipants - participantId) }
        viewModelScope.launch {
            repository.unblock(participantId)
            refreshInternal(showSpinner = false)
        }
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
    }

    fun dismissReportConfirmation() {
        _ui.update { it.copy(reportConfirmationName = null) }
    }

    private suspend fun recomputeAndQueueIfNeeded() {
        val inputs = latestInputs ?: return
        val today = LocalDate.now()
        val weekStart = WeeklyChallengeWeek.startFor(today)
        val aggregate = WeeklyChallengeAggregator.aggregate(
            WeeklyChallengeAggregationInput(
                foods = inputs.foods,
                water = inputs.water,
                workoutBurns = inputs.workoutBurns,
                calorieGoal = inputs.profile?.effectiveCalories ?: 0,
                waterTrackingEnabled = inputs.waterTrackingEnabled,
                waterGoalMl = inputs.waterGoalMl,
                weekStart = weekStart,
                today = today,
                zoneId = ZoneId.systemDefault()
            )
        )
        val oldWeek = _ui.value.weekStart
        _ui.update {
            it.copy(
                weekStart = weekStart,
                aggregate = aggregate,
                leaderboard = if (oldWeek == weekStart) it.leaderboard else null,
                lastUpdatedEpochMillis = if (oldWeek == weekStart) it.lastUpdatedEpochMillis else null
            )
        }
        if (_ui.value.isJoined && aggregate != lastQueuedAggregate) {
            lastQueuedAggregate = aggregate
            repository.queueLatestAggregate(aggregate)
        }
    }

    private suspend fun refreshInternal(showSpinner: Boolean) {
        refreshMutex.withLock {
            recomputeAndQueueIfNeeded()
            val current = _ui.value
            if (!current.isJoined) return
            if (showSpinner) _ui.update { it.copy(isRefreshing = true, error = null) }
            when (
                val result = repository.refresh(
                    category = current.category,
                    weekStart = current.weekStart.toString(),
                    aggregate = current.aggregate.takeIf { latestInputs != null }
                )
            ) {
                is WeeklyChallengeResult.Success ->
                    _ui.update {
                        it.copy(
                            leaderboard = result.value.leaderboard,
                            lastUpdatedEpochMillis = result.value.refreshedAtEpochMillis,
                            isRefreshing = false,
                            isOffline = false,
                            error = null
                        )
                    }
                is WeeklyChallengeResult.Failure ->
                    _ui.update {
                        it.copy(
                            isRefreshing = false,
                            isOffline = result.kind == WeeklyChallengeFailureKind.NETWORK,
                            error = if (showSpinner) result.toUiError() else it.error
                        )
                    }
            }
        }
    }

    private fun WeeklyChallengeResult.Failure.toUiError(): WeeklyChallengeUiError = when (kind) {
        WeeklyChallengeFailureKind.NETWORK -> WeeklyChallengeUiError.NETWORK
        WeeklyChallengeFailureKind.AUTH -> WeeklyChallengeUiError.AUTH
        WeeklyChallengeFailureKind.SERVER -> if (code == "validation_error") {
            WeeklyChallengeUiError.VALIDATION
        } else {
            WeeklyChallengeUiError.SERVER
        }
    }

    override fun onCleared() {
        visibleRefreshJob?.cancel()
        super.onCleared()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WeeklyChallengeViewModel(container) as T
    }

    private companion object {
        const val VISIBLE_REFRESH_INTERVAL_MS = 60_000L
    }
}
