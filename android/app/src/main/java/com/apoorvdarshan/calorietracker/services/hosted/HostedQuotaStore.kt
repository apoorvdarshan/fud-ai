package com.apoorvdarshan.calorietracker.services.hosted

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The Worker's ledger state for this subscriber, as last reported. Display only:
 * the Worker meters every request, the app never counts or grants quota.
 */
@Serializable
data class HostedQuotaSnapshot(
    val plan: String,
    val day: String,
    val dailyUsed: Int,
    val dailyLimit: Int,
    val creditBank: Int
) {
    val dailyRemaining: Int get() = (dailyLimit - dailyUsed).coerceAtLeast(0)
    val availableActions: Int get() = dailyRemaining + creditBank

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String): HostedQuotaSnapshot? =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()?.takeIf { it.isValid() }

        fun encode(snapshot: HostedQuotaSnapshot): String = json.encodeToString(serializer(), snapshot)

        /** Reads the five `X-Fud-Quota-*` headers; null unless all are present and well-formed. */
        fun fromHeaders(header: (String) -> String?): HostedQuotaSnapshot? {
            val plan = header("X-Fud-Quota-Plan") ?: return null
            val day = header("X-Fud-Quota-Day") ?: return null
            val used = header("X-Fud-Quota-Daily-Used")?.toIntOrNull() ?: return null
            val limit = header("X-Fud-Quota-Daily-Limit")?.toIntOrNull() ?: return null
            val credits = header("X-Fud-Quota-Credits")?.toIntOrNull() ?: return null
            return HostedQuotaSnapshot(plan, day, used, limit, credits).takeIf { it.isValid() }
        }

        fun utcDayKey(now: Instant): String = DateTimeFormatter.ISO_LOCAL_DATE.format(now.atOffset(ZoneOffset.UTC))
    }

    private fun isValid(): Boolean =
        HostedPlan.fromWire(plan) != null && DAY_PATTERN.matches(day) &&
            dailyUsed >= 0 && dailyLimit >= 0 && creditBank >= 0
}

private val DAY_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

/** Persists the last snapshot outside DataStore, so cloud backups never carry another device's ledger. */
interface QuotaSnapshotPersistence {
    fun load(): String?
    fun save(value: String)
}

class SharedPreferencesQuotaPersistence(context: Context) : QuotaSnapshotPersistence {
    private val prefs = context.getSharedPreferences("fudai_hosted_ai", Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val KEY = "hostedAI.serverQuotaSnapshot.v2"
    }
}

/**
 * Caches the Worker's quota for display and coalesces refreshes.
 *
 * - Snapshots carry the sequence number of the request that produced them; an
 *   older request finishing late can never overwrite a newer balance.
 * - Concurrent refreshes share one request. A forced refresh (after a purchase
 *   or restore) is only satisfied by a forced request that *started after* the
 *   caller arrived, so it always reflects the new transaction.
 */
class HostedQuotaStore(
    private val persistence: QuotaSnapshotPersistence,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val fetch: suspend (refresh: Boolean) -> HostedQuotaSnapshot
) {
    private val _snapshot = MutableStateFlow(persistence.load()?.let(HostedQuotaSnapshot::decode))
    val snapshot: StateFlow<HostedQuotaSnapshot?> = _snapshot.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val applyLock = Any()
    private var nextSequence = 1L
    private var lastAppliedSequence = 0L

    /** Stamps an outgoing request; pass the value back to [apply] with its response. */
    fun nextRequestSequence(): Long = synchronized(applyLock) { nextSequence++ }

    fun apply(snapshot: HostedQuotaSnapshot, sequence: Long) {
        synchronized(applyLock) {
            val current = _snapshot.value
            val newerDay = current == null || snapshot.day > current.day
            if (sequence < lastAppliedSequence && !newerDay) return
            lastAppliedSequence = maxOf(lastAppliedSequence, sequence)
            _snapshot.value = snapshot
        }
        persistence.save(HostedQuotaSnapshot.encode(snapshot))
    }

    /** What to show for [plan] right now, rolling the daily pool over at UTC midnight. */
    fun snapshotFor(plan: HostedPlan): HostedQuotaSnapshot {
        val today = HostedQuotaSnapshot.utcDayKey(now())
        val cached = _snapshot.value
        if (cached == null || cached.plan != plan.wireValue) {
            return HostedQuotaSnapshot(plan.wireValue, today, 0, plan.dailyLimit, cached?.creditBank ?: 0)
        }
        return if (cached.day == today) cached else cached.copy(day = today, dailyUsed = 0)
    }

    private class Run(val id: Long, val forced: Boolean, val result: Deferred<Result<HostedQuotaSnapshot>>)

    private val runLock = Mutex()
    private var current: Run? = null
    private var nextRunId = 0L

    suspend fun refresh(force: Boolean = false): Result<HostedQuotaSnapshot> {
        val arrivalId = runLock.withLock { nextRunId }
        while (true) {
            val (run, satisfies) = runLock.withLock {
                val active = current?.takeIf { it.result.isActive }
                if (active != null) {
                    active to (!force || (active.forced && active.id >= arrivalId))
                } else {
                    val run = Run(nextRunId++, force, scope.async { runFetch(force) })
                    current = run
                    run to true
                }
            }
            val result = run.result.await()
            if (satisfies) return result
        }
    }

    private suspend fun runFetch(force: Boolean): Result<HostedQuotaSnapshot> {
        _isRefreshing.value = true
        return try {
            val sequence = nextRequestSequence()
            runCatching { fetch(force) }.onSuccess { apply(it, sequence) }
        } finally {
            // Runs never overlap (a new one starts only after the previous finished).
            _isRefreshing.value = false
        }
    }
}
