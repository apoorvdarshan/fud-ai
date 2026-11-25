package com.apoorvdarshan.calorietracker.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.data.WeeklyChallengeFailureKind
import com.apoorvdarshan.calorietracker.data.WeeklyChallengeRepository
import com.apoorvdarshan.calorietracker.data.WeeklyChallengeResult
import com.apoorvdarshan.calorietracker.models.ChallengePeriod
import com.apoorvdarshan.calorietracker.models.ChallengePeriodScore
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
import com.apoorvdarshan.calorietracker.services.challenge.WeeklyChallengeAggregator
import com.apoorvdarshan.calorietracker.services.challenge.WeeklyChallengeLocalInputs
import com.apoorvdarshan.calorietracker.services.challenge.challengeLocalInputs
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

internal const val RANKING_PAGE_SIZE = 20

data class WeeklyChallengeUiState(
    val period: ChallengePeriod = ChallengePeriod.WEEKLY,
    val category: WeeklyChallengeCategory = WeeklyChallengeCategory.OVERALL,
    val weekStart: LocalDate = WeeklyChallengeWeek.startFor(LocalDate.now()),
    val aggregate: WeeklyChallengeAggregate =
        WeeklyChallengeAggregate.empty(WeeklyChallengeWeek.startFor(LocalDate.now())),
    val profile: WeeklyChallengePublicProfile? = null,
    val leaderboard: WeeklyChallengeLeaderboard? = null,
    val rankingPage: Int = 0,
    val pageResponse: WeeklyChallengeLeaderboard? = null,
    val isLoadingPage: Boolean = false,
    val pageFailed: Boolean = false,
    val periodScore: ChallengePeriodScore? = null,
    val periodLeaderboard: WeeklyChallengeLeaderboard? = null,
    val enabledPeriods: Set<ChallengePeriod> = emptySet(),
    val isPeriodChanging: Boolean = false,
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
    val currentLeaderboard get() = (if (period == ChallengePeriod.WEEKLY) leaderboard else periodLeaderboard)
        ?.takeIf { it.category == category }
    val currentStart get() = if (period == ChallengePeriod.WEEKLY) weekStart.toString()
        else periodScore?.periodStart ?: period.startFor(LocalDate.now()).toString()
    val supportsExtraPeriods get() = leaderboard?.period == ChallengePeriod.WEEKLY
}

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
                        enabledPeriods = if (joined) stored.enabledPeriods else emptySet(),
                        periodLeaderboard = if (joined && current.period in stored.enabledPeriods) {
                            stored.cached(current.category, current.currentStart, period = current.period)
                        } else null,
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

        viewModelScope.launch {
            challengeLocalInputs(container).collect { inputs ->
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
        _ui.update { it.copy(category = category, periodLeaderboard = null, rankingPage = 0, pageResponse = null,
            isLoadingPage = false, pageFailed = false, error = null, isOffline = false) }
        viewModelScope.launch {
            val stored = repository.stateSnapshot()
            val current = _ui.value
            _ui.update {
                if (it.category != category) return@update it
                it.copy(
                    leaderboard = if (current.isJoined) stored.cached(category, current.weekStart.toString())
                        ?: current.leaderboard else null,
                    periodLeaderboard = if (current.isJoined) stored.cached(category, current.currentStart, period = current.period) else null,
                    lastUpdatedEpochMillis = if (current.isJoined) {
                        stored.refreshedAt(category, current.weekStart.toString())
                    } else null
                )
            }
            refreshInternal(showSpinner = true)
        }
    }

    fun selectPeriod(period: ChallengePeriod) {
        if (period == _ui.value.period) return
        _ui.update { it.copy(period = period, rankingPage = 0, pageResponse = null, periodScore = null,
            periodLeaderboard = null, pageFailed = false, isLoadingPage = false, error = null) }
        viewModelScope.launch { refreshInternal(showSpinner = true) }
    }

    fun joinCurrentPeriod() {
        val score = _ui.value.periodScore ?: return
        if (!_ui.value.supportsExtraPeriods || _ui.value.isPeriodChanging) return
        viewModelScope.launch {
            _ui.update { it.copy(isPeriodChanging = true, error = null) }
            when (val result = repository.publishPeriodScore(score, accept = true)) {
                is WeeklyChallengeResult.Success -> {
                    _ui.update { it.copy(enabledPeriods = it.enabledPeriods + score.period) }
                    refreshInternal(showSpinner = true)
                }
                is WeeklyChallengeResult.Failure -> _ui.update { it.copy(error = result.toUiError()) }
            }
            _ui.update { it.copy(isPeriodChanging = false) }
        }
    }

    fun leaveCurrentPeriod() {
        val period = _ui.value.period
        if (period == ChallengePeriod.WEEKLY || _ui.value.isPeriodChanging) return
        viewModelScope.launch {
            _ui.update { it.copy(isPeriodChanging = true, error = null) }
            when (val result = repository.leavePeriod(period)) {
                is WeeklyChallengeResult.Success -> _ui.update {
                    if (it.period == period) it.copy(rankingPage = 0, pageResponse = null, periodLeaderboard = null) else it
                }
                is WeeklyChallengeResult.Failure -> _ui.update { it.copy(error = result.toUiError()) }
            }
            _ui.update { it.copy(isPeriodChanging = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshInternal(showSpinner = true)
            loadRankingPage(force = true)
        }
    }

    fun selectRankingPage(page: Int) {
        if (page < 0 || page == _ui.value.rankingPage) return
        _ui.update { it.copy(rankingPage = page, pageResponse = null, pageFailed = false, isLoadingPage = false) }
        viewModelScope.launch { loadRankingPage(force = false) }
    }

    fun retryRankingPage() {
        viewModelScope.launch { loadRankingPage(force = true) }
    }

    private suspend fun loadRankingPage(force: Boolean) {
        val state = _ui.value
        if (state.rankingPage <= 0 || state.currentLeaderboard?.totalCount == null || !state.isJoined) return
        val offset = state.rankingPage * RANKING_PAGE_SIZE
        val week = state.currentStart
        val period = state.period
        val category = state.category
        val cache = repository.stateSnapshot().cached(category, week, offset, period)
        fun matches(current: WeeklyChallengeUiState) = current.isJoined && current.rankingPage == state.rankingPage &&
            current.category == category && current.currentStart == week && current.period == period
        _ui.update { if (matches(it)) it.copy(pageResponse = cache, isLoadingPage = true, pageFailed = false) else it }
        when (val result = repository.leaderboardPage(category, week, offset, force, period)) {
            is WeeklyChallengeResult.Success -> {
                val count = result.value.totalCount
                if (count != null && offset >= count && matches(_ui.value)) {
                    selectRankingPage(((count - 1) / RANKING_PAGE_SIZE).coerceAtLeast(0))
                    return
                }
                _ui.update { if (matches(it)) it.copy(pageResponse = result.value, isLoadingPage = false) else it }
            }
            is WeeklyChallengeResult.Failure ->
                _ui.update { if (matches(it)) it.copy(isLoadingPage = false, pageFailed = true) else it }
        }
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
        val zoneId = ZoneId.systemDefault()
        val input = inputs.aggregationInput(LocalDate.now(zoneId), zoneId)
        val weekStart = input.weekStart
        val aggregate = WeeklyChallengeAggregator.aggregate(input)
        val selected = _ui.value.period
        val score = if (selected == ChallengePeriod.WEEKLY) null else WeeklyChallengeAggregator.periodScore(selected, input)
        val previousStart = _ui.value.currentStart
        val newStart = score?.periodStart ?: weekStart.toString()
        val oldWeek = _ui.value.weekStart
        _ui.update {
            it.copy(
                weekStart = weekStart,
                aggregate = aggregate,
                periodScore = score,
                periodLeaderboard = if (previousStart == newStart) it.periodLeaderboard else null,
                leaderboard = if (oldWeek == weekStart) it.leaderboard else null,
                rankingPage = if (previousStart == newStart) it.rankingPage else 0,
                pageResponse = if (previousStart == newStart) it.pageResponse else null,
                isLoadingPage = previousStart == newStart && it.isLoadingPage,
                pageFailed = previousStart == newStart && it.pageFailed,
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
            if (current.period != ChallengePeriod.WEEKLY) {
                refreshExtraPeriod(current, showSpinner)
                return
            }
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
                        if (it.category != current.category || it.weekStart != current.weekStart) return@update it
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
                        if (it.category != current.category || it.weekStart != current.weekStart) return@update it
                        it.copy(
                            isRefreshing = false,
                            isOffline = result.kind == WeeklyChallengeFailureKind.NETWORK,
                            error = if (showSpinner) result.toUiError() else it.error
                        )
                    }
            }
        }
    }

    private suspend fun refreshExtraPeriod(state: WeeklyChallengeUiState, force: Boolean) {
        val score = state.periodScore ?: return
        if (state.period !in state.enabledPeriods) return
        fun matches(current: WeeklyChallengeUiState) = current.period == score.period &&
            current.category == state.category && current.currentStart == score.periodStart
        if (force) _ui.update { if (matches(it)) it.copy(isRefreshing = true) else it }
        val result = repository.leaderboardPage(state.category, score.periodStart, 0, force, score.period)
        _ui.update { current ->
            if (!matches(current)) current
            else when (result) {
                is WeeklyChallengeResult.Success -> current.copy(periodLeaderboard = result.value,
                    isRefreshing = false, isOffline = false, error = null)
                is WeeklyChallengeResult.Failure -> current.copy(isRefreshing = false,
                    isOffline = result.kind == WeeklyChallengeFailureKind.NETWORK,
                    error = if (force) result.toUiError() else current.error)
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
