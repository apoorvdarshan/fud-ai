package com.apoorvdarshan.calorietracker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apoorvdarshan.calorietracker.models.ChallengePeriod
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeAggregate
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeCategory
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeLeaderboard
import com.apoorvdarshan.calorietracker.models.WeeklyChallengePublicProfile
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.weeklyChallengeDataStore by preferencesDataStore(name = "weekly_challenge")

@Serializable
data class WeeklyChallengeStoredState(
    val profile: WeeklyChallengePublicProfile? = null,
    /** Latest aggregate only; a newer local calculation replaces, rather than appends to, this. */
    val pendingAggregate: WeeklyChallengeAggregate? = null,
    /** Public GET responses only. No private diary or health records are cached. */
    val cachedLeaderboards: Map<String, WeeklyChallengeLeaderboard> = emptyMap(),
    val lastRefreshEpochMillis: Map<String, Long> = emptyMap(),
    /** Participant id -> last public display name; blocking never uploads to the server. */
    val blockedParticipants: Map<String, String> = emptyMap(),
    /** Keeps the deletion credential alive across a local Delete Everything while offline. */
    val pendingRemoteDeletion: Boolean = false,
    val enabledPeriods: Set<ChallengePeriod> = emptySet()
) {
    fun cached(category: WeeklyChallengeCategory, weekStart: String, offset: Int = 0,
        period: ChallengePeriod = ChallengePeriod.WEEKLY): WeeklyChallengeLeaderboard? =
        cachedLeaderboards[cacheKey(category, weekStart, offset, period)]

    fun refreshedAt(category: WeeklyChallengeCategory, weekStart: String, offset: Int = 0,
        period: ChallengePeriod = ChallengePeriod.WEEKLY): Long? =
        lastRefreshEpochMillis[cacheKey(category, weekStart, offset, period)]

    fun caching(response: WeeklyChallengeLeaderboard, timestamp: Long): WeeklyChallengeStoredState {
        val key = cacheKey(response.category, response.weekStart, response.offset ?: 0, response.period ?: ChallengePeriod.WEEKLY)
        val times = (lastRefreshEpochMillis + (key to timestamp))
            .entries.sortedByDescending { it.value }.take(30).associate { it.key to it.value }
        return copy(
            cachedLeaderboards = (cachedLeaderboards + (key to response)).filterKeys { it in times },
            lastRefreshEpochMillis = times
        )
    }

    fun leaving(period: ChallengePeriod): WeeklyChallengeStoredState = copy(
        enabledPeriods = enabledPeriods - period,
        cachedLeaderboards = cachedLeaderboards.filterKeys { !it.startsWith("${period.wire}|") },
        lastRefreshEpochMillis = lastRefreshEpochMillis.filterKeys { !it.startsWith("${period.wire}|") }
    )

    companion object {
        fun cacheKey(category: WeeklyChallengeCategory, weekStart: String, offset: Int = 0,
            period: ChallengePeriod = ChallengePeriod.WEEKLY): String {
            val mode = if (period == ChallengePeriod.WEEKLY) "" else "${period.wire}|"
            val prefix = "$mode$weekStart|${category.apiValue}"
            return if (offset == 0) prefix else "$prefix|$offset"
        }
    }
}

class WeeklyChallengeStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val dataStore get() = context.weeklyChallengeDataStore

    val state: Flow<WeeklyChallengeStoredState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::decode)

    suspend fun snapshot(): WeeklyChallengeStoredState = state.first()

    suspend fun update(transform: (WeeklyChallengeStoredState) -> WeeklyChallengeStoredState) {
        dataStore.edit { preferences ->
            val next = transform(decode(preferences))
            preferences[STATE] = json.encodeToString(WeeklyChallengeStoredState.serializer(), next)
        }
    }

    suspend fun replace(state: WeeklyChallengeStoredState = WeeklyChallengeStoredState()) {
        dataStore.edit { preferences ->
            preferences[STATE] = json.encodeToString(WeeklyChallengeStoredState.serializer(), state)
        }
    }

    private fun decode(preferences: androidx.datastore.preferences.core.Preferences): WeeklyChallengeStoredState =
        preferences[STATE]?.let { raw ->
            runCatching { json.decodeFromString(WeeklyChallengeStoredState.serializer(), raw) }.getOrNull()
        } ?: WeeklyChallengeStoredState()

    private companion object {
        val STATE = stringPreferencesKey("weeklyChallengeStateV1")
    }
}
