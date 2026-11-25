package com.apoorvdarshan.calorietracker.services.challenge

import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeWeek
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

internal data class WeeklyChallengeLocalInputs(
    val profile: UserProfile?,
    val foods: List<FoodEntry>,
    val water: List<WaterEntry>,
    val workoutBurns: List<WorkoutSession>,
    val waterTrackingEnabled: Boolean = false,
    val waterGoalMl: Int = 0
) {
    fun aggregationInput(today: LocalDate, zoneId: ZoneId) = WeeklyChallengeAggregationInput(
        foods = foods,
        water = water,
        workoutBurns = workoutBurns,
        calorieGoal = profile?.effectiveCalories ?: 0,
        waterTrackingEnabled = waterTrackingEnabled,
        waterGoalMl = waterGoalMl,
        weekStart = WeeklyChallengeWeek.startFor(today),
        today = today,
        zoneId = zoneId
    )
}

internal fun challengeLocalInputs(container: AppContainer) = combine(
    container.profileRepository.profile,
    container.foodRepository.entries,
    container.waterRepository.entries,
    container.workoutRepository.burnSessions
) { profile, foods, water, workouts ->
    WeeklyChallengeLocalInputs(profile, foods, water, workouts)
}.combine(combine(container.prefs.waterTrackingEnabled, container.prefs.waterDailyGoalMl, ::Pair)) { input, prefs ->
    input.copy(waterTrackingEnabled = prefs.first, waterGoalMl = prefs.second)
}.distinctUntilChanged()

/** Runs while the activity is visible, even if no leaderboard has been opened. */
internal suspend fun syncChallengePeriodsWhileForeground(container: AppContainer) {
    val repository = container.weeklyChallengeRepository
    val consent = repository.state.map {
        if (it.profile == null || it.pendingRemoteDeletion) emptySet() else it.enabledPeriods
    }.distinctUntilChanged()
    val retryTicks = flow {
        while (currentCoroutineContext().isActive) {
            emit(Unit)
            delay(60_000)
        }
    }
    consent.collectLatest { periods ->
        if (periods.isEmpty()) return@collectLatest
        combine(challengeLocalInputs(container), retryTicks) { input, _ -> input }
            .conflate()
            .collect { inputs ->
                val zoneId = ZoneId.systemDefault()
                val input = inputs.aggregationInput(LocalDate.now(zoneId), zoneId)
                for (score in WeeklyChallengeAggregator.enabledPeriodScores(periods, input)) {
                    repository.publishPeriodScore(score, accept = false)
                }
            }
    }
}
