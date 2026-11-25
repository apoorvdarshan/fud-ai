package com.apoorvdarshan.calorietracker.data

import android.content.Context
import com.apoorvdarshan.calorietracker.models.ChallengePeriod
import com.apoorvdarshan.calorietracker.models.ChallengePeriodScore
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeAggregate
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeCategory
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeCreateProfileRequest
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeLeaderboard
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeProfileRequest
import com.apoorvdarshan.calorietracker.models.WeeklyChallengePublicProfile
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeReportRequest
import com.apoorvdarshan.calorietracker.services.challenge.WeeklyChallengeApi
import com.apoorvdarshan.calorietracker.services.challenge.WeeklyChallengeApiException
import com.apoorvdarshan.calorietracker.services.challenge.WeeklyChallengeNetworkException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WeeklyChallengeFailureKind {
    NETWORK,
    AUTH,
    SERVER
}

sealed interface WeeklyChallengeResult<out T> {
    data class Success<T>(val value: T) : WeeklyChallengeResult<T>
    data class Failure(
        val kind: WeeklyChallengeFailureKind,
        val code: String? = null,
        /** Never rendered directly; retained so localized clients can diagnose validation drift. */
        val serverMessage: String? = null
    ) : WeeklyChallengeResult<Nothing>
}

data class WeeklyChallengeRefresh(
    val leaderboard: WeeklyChallengeLeaderboard,
    val refreshedAtEpochMillis: Long
)

class WeeklyChallengeRepository internal constructor(
    context: Context,
    private val keyStore: KeyStore,
    private val api: WeeklyChallengeApi = WeeklyChallengeApi(),
    private val store: WeeklyChallengeStore = WeeklyChallengeStore(context)
) {
    private val mutationMutex = Mutex()
    private val publishedPeriodScores = mutableMapOf<ChallengePeriod, ChallengePeriodScore>()

    val state: Flow<WeeklyChallengeStoredState> = store.state

    suspend fun stateSnapshot(): WeeklyChallengeStoredState = store.snapshot()

    fun hasBearerToken(): Boolean = !keyStore.weeklyChallengeBearerToken().isNullOrBlank()

    suspend fun createProfile(
        request: WeeklyChallengeCreateProfileRequest,
        aggregate: WeeklyChallengeAggregate
    ): WeeklyChallengeResult<WeeklyChallengePublicProfile> = mutationMutex.withLock {
        try {
            val response = api.createProfile(request)
            keyStore.setWeeklyChallengeBearerToken(response.bearerToken)
            store.update {
                it.copy(profile = response.profile, pendingAggregate = aggregate)
            }
            // Joining succeeded even if the first aggregate upload is temporarily offline.
            runCatching { retryPendingLocked(response.bearerToken) }
            WeeklyChallengeResult.Success(response.profile)
        } catch (error: Exception) {
            error.asChallengeFailure()
        }
    }

    suspend fun updateProfile(
        request: WeeklyChallengeProfileRequest
    ): WeeklyChallengeResult<WeeklyChallengePublicProfile> = mutationMutex.withLock {
        val token = keyStore.weeklyChallengeBearerToken()
            ?: return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.AUTH)
        try {
            val profile = api.updateProfile(token, request)
            store.update { it.copy(profile = profile) }
            WeeklyChallengeResult.Success(profile)
        } catch (error: Exception) {
            if (error.isExpiredSessionResponse()) clearLocalEnrollment()
            error.asChallengeFailure()
        }
    }

    /** Local participation is cleared only after the server confirms remote deletion. */
    suspend fun leaveAndDelete(): WeeklyChallengeResult<Unit> = mutationMutex.withLock {
        val token = keyStore.weeklyChallengeBearerToken()
        if (token == null) {
            clearLocalEnrollment(preserveBlockedParticipants = false)
            return@withLock WeeklyChallengeResult.Success(Unit)
        }
        try {
            if (!api.deleteProfile(token)) {
                markPendingRemoteDeletion()
                return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.SERVER)
            }
            clearLocalEnrollment(preserveBlockedParticipants = false)
            WeeklyChallengeResult.Success(Unit)
        } catch (error: Exception) {
            if (error.isCompletedDeleteResponse()) {
                clearLocalEnrollment(preserveBlockedParticipants = false)
                WeeklyChallengeResult.Success(Unit)
            } else {
                markPendingRemoteDeletion()
                error.asChallengeFailure()
            }
        }
    }

    /**
     * Called before the app's broad local wipe. False means the encrypted bearer
     * must be preserved so [retryPendingRemoteDeletion] can finish later.
     */
    suspend fun prepareForDeleteEverything(): Boolean = mutationMutex.withLock {
        val token = keyStore.weeklyChallengeBearerToken()
        if (token == null) {
            store.replace()
            return@withLock true
        }
        return@withLock try {
            if (api.deleteProfile(token)) {
                clearLocalEnrollment(preserveBlockedParticipants = false)
                true
            } else {
                markPendingRemoteDeletion()
                false
            }
        } catch (error: Exception) {
            if (error.isCompletedDeleteResponse()) {
                clearLocalEnrollment(preserveBlockedParticipants = false)
                true
            } else {
                markPendingRemoteDeletion()
                false
            }
        }
    }

    suspend fun retryPendingRemoteDeletion(): WeeklyChallengeResult<Unit> = mutationMutex.withLock {
        if (!store.snapshot().pendingRemoteDeletion) {
            return@withLock WeeklyChallengeResult.Success(Unit)
        }
        val token = keyStore.weeklyChallengeBearerToken()
        if (token == null) {
            // An encrypted-store reset can orphan the DataStore marker. Without the bearer
            // no authenticated retry is possible; clear the trap and rely on server expiry.
            clearLocalEnrollment(preserveBlockedParticipants = false)
            return@withLock WeeklyChallengeResult.Success(Unit)
        }
        return@withLock try {
            if (api.deleteProfile(token)) {
                clearLocalEnrollment(preserveBlockedParticipants = false)
                WeeklyChallengeResult.Success(Unit)
            } else {
                WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.SERVER)
            }
        } catch (error: Exception) {
            if (error.isCompletedDeleteResponse()) {
                clearLocalEnrollment(preserveBlockedParticipants = false)
                WeeklyChallengeResult.Success(Unit)
            } else {
                // Expected while offline. The marker and encrypted credential stay intact.
                error.asChallengeFailure()
            }
        }
    }

    /** Persist first, then best-effort PUT. A later refresh retries the latest value only. */
    suspend fun queueLatestAggregate(aggregate: WeeklyChallengeAggregate): WeeklyChallengeResult<Unit> =
        mutationMutex.withLock {
            if (store.snapshot().pendingRemoteDeletion) {
                return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.AUTH)
            }
            store.update { it.copy(pendingAggregate = aggregate) }
            val token = keyStore.weeklyChallengeBearerToken()
                ?: return@withLock WeeklyChallengeResult.Success(Unit)
            try {
                retryPendingLocked(token)
                WeeklyChallengeResult.Success(Unit)
            } catch (error: Exception) {
                if (error.isExpiredSessionResponse()) clearLocalEnrollment()
                error.asChallengeFailure()
            }
        }

    suspend fun refresh(
        category: WeeklyChallengeCategory,
        weekStart: String,
        aggregate: WeeklyChallengeAggregate?
    ): WeeklyChallengeResult<WeeklyChallengeRefresh> = mutationMutex.withLock {
        if (store.snapshot().pendingRemoteDeletion) {
            return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.AUTH)
        }
        val token = keyStore.weeklyChallengeBearerToken()
            ?: return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.AUTH)
        if (aggregate != null) {
            store.update { it.copy(pendingAggregate = aggregate) }
            try {
                retryPendingLocked(token)
            } catch (error: Exception) {
                if (error.isExpiredSessionResponse()) {
                    clearLocalEnrollment()
                    return@withLock error.asChallengeFailure()
                }
                // Keep the latest aggregate queued while still attempting a cached GET.
            }
        }
        try {
            val leaderboard = api.leaderboard(category, weekStart, token)
            val refreshedAt = System.currentTimeMillis()
            store.update { it.caching(leaderboard, refreshedAt) }
            WeeklyChallengeResult.Success(WeeklyChallengeRefresh(leaderboard, refreshedAt))
        } catch (error: Exception) {
            if (error.isExpiredSessionResponse()) clearLocalEnrollment()
            error.asChallengeFailure()
        }
    }

    suspend fun leaderboardPage(
        category: WeeklyChallengeCategory,
        weekStart: String,
        offset: Int,
        force: Boolean,
        period: ChallengePeriod = ChallengePeriod.WEEKLY
    ): WeeklyChallengeResult<WeeklyChallengeLeaderboard> = mutationMutex.withLock {
        val snapshot = store.snapshot()
        val token = keyStore.weeklyChallengeBearerToken()
        if (snapshot.pendingRemoteDeletion || token == null ||
            (period != ChallengePeriod.WEEKLY && period !in snapshot.enabledPeriods)) {
            return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.AUTH)
        }
        val savedAt = snapshot.refreshedAt(category, weekStart, offset, period)
        val cached = snapshot.cached(category, weekStart, offset, period)
        if (!force && cached != null && savedAt != null && System.currentTimeMillis() - savedAt in 0L..60_000L) {
            return@withLock WeeklyChallengeResult.Success(cached)
        }
        try {
            val page = api.leaderboard(category, weekStart, token, offset, period = period)
            if (page.category != category || page.weekStart != weekStart || page.offset != offset ||
                (page.period ?: ChallengePeriod.WEEKLY) != period) {
                return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.SERVER)
            }
            store.update { it.caching(page, System.currentTimeMillis()) }
            WeeklyChallengeResult.Success(page)
        } catch (error: Exception) {
            if (error.isExpiredSessionResponse()) clearLocalEnrollment()
            error.asChallengeFailure()
        }
    }

    suspend fun publishPeriodScore(score: ChallengePeriodScore, accept: Boolean): WeeklyChallengeResult<Unit> =
        mutationMutex.withLock {
            val snapshot = store.snapshot()
            val token = keyStore.weeklyChallengeBearerToken()
            if (token == null || snapshot.pendingRemoteDeletion || score.period == ChallengePeriod.WEEKLY ||
                (!accept && score.period !in snapshot.enabledPeriods)) {
                return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.AUTH)
            }
            if (!accept && publishedPeriodScores[score.period] == score) return@withLock WeeklyChallengeResult.Success(Unit)
            try {
                if (!api.putPeriodScore(token, score)) return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.SERVER)
                store.update {
                    val stale = it.markingStale(score.period, score.periodStart)
                    if (accept) stale.copy(enabledPeriods = stale.enabledPeriods + score.period) else stale
                }
                publishedPeriodScores[score.period] = score
                WeeklyChallengeResult.Success(Unit)
            } catch (error: Exception) {
                if (error.isExpiredSessionResponse()) clearLocalEnrollment()
                error.asChallengeFailure()
            }
        }

    suspend fun leavePeriod(period: ChallengePeriod): WeeklyChallengeResult<Unit> = mutationMutex.withLock {
        val token = keyStore.weeklyChallengeBearerToken()
            ?: return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.AUTH)
        try {
            if (!api.deletePeriod(token, period)) return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.SERVER)
            publishedPeriodScores.remove(period)
            store.update { it.leaving(period) }
            WeeklyChallengeResult.Success(Unit)
        } catch (error: Exception) {
            if (error.isExpiredSessionResponse()) clearLocalEnrollment()
            error.asChallengeFailure()
        }
    }

    suspend fun report(request: WeeklyChallengeReportRequest): WeeklyChallengeResult<Unit> =
        mutationMutex.withLock {
            val token = keyStore.weeklyChallengeBearerToken()
                ?: return@withLock WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.AUTH)
            try {
                api.report(token, request)
                WeeklyChallengeResult.Success(Unit)
            } catch (error: Exception) {
                if (error.isExpiredSessionResponse()) clearLocalEnrollment()
                error.asChallengeFailure()
            }
        }

    suspend fun block(participantId: String, displayName: String) {
        store.update { current ->
            current.copy(
                blockedParticipants = current.blockedParticipants +
                    (participantId to displayName.take(80))
            )
        }
    }

    suspend fun unblock(participantId: String) {
        store.update { current ->
            current.copy(blockedParticipants = current.blockedParticipants - participantId)
        }
    }

    suspend fun clearStaleSessionIfNeeded() {
        if (store.snapshot().pendingRemoteDeletion) return
        if (hasBearerToken()) return
        store.update {
            it.copy(
                profile = null,
                pendingAggregate = null,
                cachedLeaderboards = emptyMap(),
                lastRefreshEpochMillis = emptyMap(),
                enabledPeriods = emptySet()
            )
        }
    }

    private suspend fun markPendingRemoteDeletion() {
        store.replace(WeeklyChallengeStoredState(pendingRemoteDeletion = true))
    }

    private suspend fun clearLocalEnrollment(preserveBlockedParticipants: Boolean = true) {
        val blocked = if (preserveBlockedParticipants) store.snapshot().blockedParticipants else emptyMap()
        keyStore.clearWeeklyChallengeBearerToken()
        publishedPeriodScores.clear()
        store.replace(WeeklyChallengeStoredState(blockedParticipants = blocked))
    }

    private suspend fun retryPendingLocked(token: String) {
        val pending = store.snapshot().pendingAggregate ?: return
        api.putWeeklyScore(token, pending)
        store.update { current ->
            if (current.pendingAggregate == pending) current.copy(pendingAggregate = null) else current
        }
    }

    private fun Exception.asChallengeFailure(): WeeklyChallengeResult.Failure = when (this) {
        is WeeklyChallengeNetworkException ->
            WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.NETWORK)
        is WeeklyChallengeApiException ->
            WeeklyChallengeResult.Failure(
                kind = if (statusCode == 401 || statusCode == 403) {
                    WeeklyChallengeFailureKind.AUTH
                } else {
                    WeeklyChallengeFailureKind.SERVER
                },
                code = errorCode,
                serverMessage = serverMessage
            )
        else -> WeeklyChallengeResult.Failure(WeeklyChallengeFailureKind.SERVER)
    }
}

internal fun Exception.isCompletedDeleteResponse(): Boolean =
    this is WeeklyChallengeApiException && statusCode in setOf(401, 404)

internal fun Exception.isExpiredSessionResponse(): Boolean =
    this is WeeklyChallengeApiException && statusCode == 401
