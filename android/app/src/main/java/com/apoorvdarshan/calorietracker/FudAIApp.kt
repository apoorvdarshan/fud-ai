package com.apoorvdarshan.calorietracker

import android.app.Application
import com.apoorvdarshan.calorietracker.data.BodyFatRepository
import com.apoorvdarshan.calorietracker.data.BodyMeasurementRepository
import com.apoorvdarshan.calorietracker.data.ChatRepository
import com.apoorvdarshan.calorietracker.data.FoodRepository
import com.apoorvdarshan.calorietracker.data.FastingRepository
import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.data.ProfileRepository
import com.apoorvdarshan.calorietracker.data.WeightRepository
import com.apoorvdarshan.calorietracker.data.WaterRepository
import com.apoorvdarshan.calorietracker.data.WorkoutHealthSync
import com.apoorvdarshan.calorietracker.data.WorkoutRepository
import com.apoorvdarshan.calorietracker.data.WeeklyChallengeRepository
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.services.NotificationService
import com.apoorvdarshan.calorietracker.services.WidgetSnapshotWriter
import com.apoorvdarshan.calorietracker.services.AdaptiveGoalResult
import com.apoorvdarshan.calorietracker.services.DailyHealthEnergyEvidence
import com.apoorvdarshan.calorietracker.services.GoalEvidence
import com.apoorvdarshan.calorietracker.services.GoalEvidenceBuilder
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.CurrentMealSchedule
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.services.ai.ChatService
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysisService
import com.apoorvdarshan.calorietracker.services.health.HealthConnectManager
import com.apoorvdarshan.calorietracker.services.ondevice.LocalGemmaRuntime
import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelId
import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelManager
import com.apoorvdarshan.calorietracker.services.ondevice.LocalWhisperRuntime
import com.apoorvdarshan.calorietracker.services.speech.SpeechService
import com.apoorvdarshan.calorietracker.widget.WidgetRefreshScheduler
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Application-scoped singleton wiring. Manual DI (no Hilt) — repositories and
 * services are instantiated once and handed to ViewModels via [container].
 */
class FudAIApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
        WidgetRefreshScheduler.onAppStarted(this)
        container.widgetSnapshotWriter.observe().launchIn(appScope)
        appScope.launch {
            container.prefs.reconcileLocalModelSelections()
            container.prefs.migrateAIModelSelections()
            container.prefs.migrateMatchingSpeechProviderIfNeeded()
        }
        // Older Android builds removed food rows without removing their JPEGs.
        // Prune only unreferenced files; logged foods, saved meals, and pending
        // analysis drafts remain untouched.
        appScope.launch { container.foodRepository.pruneOrphanedImages() }
        appScope.launch { container.weeklyChallengeRepository.retryPendingRemoteDeletion() }
        container.prefs.mealSchedule
            .onEach { CurrentMealSchedule.value = it }
            .launchIn(appScope)
        // Re-arm the daily weight-log alarm on every cold start. AlarmManager
        // drops scheduled alarms on device reboot and (sometimes) on app
        // updates — without this, a user who enabled Notifications once would
        // silently stop receiving the reminder after the next reboot.
        appScope.launch {
            if (container.prefs.fastingTrackingEnabled.first()) {
                container.notifications.ensureFastingChannel()
            }
            if (container.prefs.notificationsEnabled.first() &&
                container.notifications.canPostNotifications()
            ) {
                if (container.prefs.streakReminderEnabled.first()) {
                    container.notifications.scheduleStreakReminder(
                        container.prefs.streakReminderHour.first(),
                        container.prefs.streakReminderMinute.first()
                    )
                } else {
                    container.notifications.cancelStreakReminder()
                }
                if (container.prefs.dailySummaryEnabled.first()) {
                    container.notifications.scheduleDailySummary(
                        container.prefs.dailySummaryHour.first(),
                        container.prefs.dailySummaryMinute.first()
                    )
                } else {
                    container.notifications.cancelDailySummary()
                }
                if (container.prefs.weightReminderEnabled.first()) {
                    container.notifications.scheduleWeightReminder()
                } else {
                    container.notifications.cancelWeightReminder()
                }
                // Body-fat reminder only fires for users who've actually opted
                // into body-fat tracking and left that notification type on.
                val profile = container.profileRepository.current()
                if (container.prefs.bodyFatReminderEnabled.first() && profile?.bodyFatPercentage != null) {
                    container.notifications.scheduleBodyFatReminder()
                } else {
                    container.notifications.cancelBodyFatReminder()
                }
                if (container.prefs.waterTrackingEnabled.first() && container.prefs.waterReminderEnabled.first()) {
                    container.notifications.scheduleWaterReminder(
                        container.prefs.waterReminderHour.first(),
                        container.prefs.waterReminderMinute.first()
                    )
                } else {
                    container.notifications.cancelWaterReminder()
                }
                if (container.prefs.fastingTrackingEnabled.first() &&
                    container.prefs.fastingGoalNotificationEnabled.first()
                ) {
                    container.notifications.scheduleFastingGoal(container.fastingRepository.active())
                } else {
                    container.notifications.cancelFastingGoal()
                }
            }
        }
    }
}

/** Stable labels for the read types a Health Connect changes token was seeded for,
 *  persisted alongside the token so we can detect a newly-granted read capability. */
private const val HEALTH_READ_TYPE_WEIGHT = "weight"
private const val HEALTH_READ_TYPE_BODY_FAT = "bodyfat"

data class GoalCalculationEvidenceContext(
    val evidence: GoalEvidence,
    val measuredTdee: Int?
)

class AppContainer(app: FudAIApp) {
    val appContext = app.applicationContext
    val localModels = LocalModelManager(app, FoodAnalysisService.defaultClient)
    val prefs = PreferencesStore(
        app,
        isLocalGemmaExecutable = { localModels.isExecutable(LocalModelId.GEMMA_4_E2B) },
        isLocalWhisperExecutable = { localModels.isExecutable(LocalModelId.WHISPER_BASE) }
    )
    val keyStore = KeyStore(app)
    val imageStore = FoodImageStore(app)
    val notifications = NotificationService(app)
    val health = HealthConnectManager(app)

    private val workoutHealthSync = object : WorkoutHealthSync {
        override suspend fun upsertBurn(session: WorkoutSession): Boolean {
            if (!prefs.healthConnectEnabled.first() || !health.hasActiveEnergyWrite()) return false
            val calories = session.caloriesBurned ?: return false
            val version = session.healthSyncVersion ?: return false
            return health.upsertWorkoutBurn(
                sessionId = session.id,
                diaryDateKey = session.diaryDateKey,
                caloriesBurned = calories,
                healthSyncVersion = version
            )
        }

        override suspend fun deleteBurn(sessionId: UUID, diaryDateKey: String): Boolean {
            // Keep deletion best-effort even after the user disables syncing so
            // an old Fud AI record cannot be restored on the next connection.
            if (!health.isAvailable() || !health.hasActiveEnergyWrite()) return false
            return health.deleteWorkoutBurn(sessionId, diaryDateKey)
        }

        override suspend fun readOwnedBurns(): List<WorkoutSession>? {
            if (!prefs.healthConnectEnabled.first() || !health.hasActiveEnergyRead()) return null
            val now = Instant.now()
            return health.readOwnedWorkoutBurns(now.minus(Duration.ofDays(7_300)), now.plus(Duration.ofDays(2)))
                ?.map { burn ->
                    WorkoutSession(
                        id = burn.sessionId,
                        diaryDateKey = burn.diaryDateKey,
                        startedAt = burn.startTime,
                        completedAt = burn.endTime,
                        durationSeconds = 0,
                        exercises = emptyList(),
                        caloriesBurned = burn.caloriesBurned,
                        healthSyncVersion = burn.healthSyncVersion
                    )
                }
        }
    }

    val profileRepository = ProfileRepository(prefs)
    val foodRepository = FoodRepository(prefs, health, imageStore)
    val weightRepository = WeightRepository(prefs, profileRepository, health)
    val bodyFatRepository = BodyFatRepository(prefs, profileRepository, health)
    val bodyMeasurementRepository = BodyMeasurementRepository(prefs)
    val chatRepository = ChatRepository(prefs)
    val waterRepository = WaterRepository(prefs)
    val fastingRepository = FastingRepository(prefs)
    val workoutRepository = WorkoutRepository(prefs, workoutHealthSync)
    val weeklyChallengeRepository = WeeklyChallengeRepository(app, keyStore)

    val localGemma = LocalGemmaRuntime(app, localModels)
    val localWhisper = LocalWhisperRuntime(app, localModels)

    val foodAnalysis = FoodAnalysisService(prefs, keyStore, localGemma = localGemma)
    val chatService = ChatService(prefs, keyStore, localGemma = localGemma)
    val speechService = SpeechService(prefs, keyStore, localWhisper = localWhisper)

    val widgetSnapshotWriter = WidgetSnapshotWriter(app, prefs, foodRepository, profileRepository)
    /**
     * App-scoped flag set by [HomeViewModel] while a food analysis request is
     * in flight. The bottom nav reads this so the bar can hide during the
     * AnalyzingOverlay (matches iOS, where the analyzing sheet covers the
     * tab bar).
     */
    val analyzingFood: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private var adaptiveGoalsRefreshInFlight = false

    @Volatile
    private var healthReadSyncInFlight = false

    /**
     * Pull external weight + body-fat readings FROM Health Connect into the app (e.g. a
     * Withings scale that writes weigh-ins to Health Connect). Runs on app foreground and
     * right after the user connects/grants. Read-direction only — gated per metric on READ
     * permission, so a user who granted read but not write still gets their data imported
     * (issue #91). Incremental via a persisted changes token, with a one-time historical
     * backfill when there's no token yet; imports are deduped, so re-runs are harmless.
     */
    suspend fun syncHealthConnectReads() {
        if (healthReadSyncInFlight) return
        if (!prefs.healthConnectEnabled.first()) return
        if (!health.isAvailable()) return
        val caps = health.capabilities()
        val workoutBurnRead = health.hasActiveEnergyRead()
        if (!caps.weightRead && !caps.bodyFatRead && !caps.nutritionRead &&
            !workoutBurnRead && !caps.activeEnergyWrite
        ) return

        healthReadSyncInFlight = true
        try {
            // One-shot food-log restore: after a reinstall or new phone the local
            // store is empty but our own NutritionRecords survive in Health Connect.
            // Ids already in the log are skipped, so this is a no-op for intact users.
            // A null read means a page failed mid-pagination — leave the flag unset
            // so the restore retries on a later foreground instead of permanently
            // accepting a partial history.
            if (caps.nutritionRead && !prefs.healthFoodRestoreDone.first()) {
                val now = Instant.now()
                val records = health.readNutrition(now.minus(Duration.ofDays(730)), now)
                if (records != null) {
                    foodRepository.restoreFromHealthConnect(records)
                    prefs.setHealthFoodRestoreDone(true)
                }
            }

            // Reconcile app-owned calculated workout burns independently from
            // nutrition and weigh-ins. Local calculation remains available even
            // without Health permission; deferred writes/deletes retry here.
            if (workoutBurnRead || caps.activeEnergyWrite) {
                workoutRepository.synchronizeWithHealth()
            }

            if (!caps.weightRead && !caps.bodyFatRead) return

            val desiredTypes = buildSet {
                if (caps.weightRead) add(HEALTH_READ_TYPE_WEIGHT)
                if (caps.bodyFatRead) add(HEALTH_READ_TYPE_BODY_FAT)
            }
            // If a read type was granted AFTER the token was seeded, the existing token never
            // observes it. Drop the token so we re-enter the backfill branch and import that
            // metric's history + re-seed a token covering everything now granted.
            if (!prefs.healthChangesTokenTypes.first().containsAll(desiredTypes)) {
                prefs.clearHealthChangesToken()
            }

            val token = prefs.healthChangesToken.first()
            if (token == null) {
                // First sync: backfill recent history (two years) so existing scale data shows up.
                val now = Instant.now()
                val from = now.minus(Duration.ofDays(730))
                if (caps.weightRead) {
                    weightRepository.importExternalWeights(health.readWeights(from, now))
                }
                if (caps.bodyFatRead) {
                    bodyFatRepository.importExternalBodyFats(health.readBodyFats(from, now))
                }
                // Seed a token covering only the types we can actually read.
                val recordTypes = buildSet {
                    if (caps.weightRead) add(androidx.health.connect.client.records.WeightRecord::class)
                    if (caps.bodyFatRead) add(androidx.health.connect.client.records.BodyFatRecord::class)
                }
                health.getChangesToken(recordTypes)?.let {
                    prefs.setHealthChangesToken(it)
                    prefs.setHealthChangesTokenTypes(desiredTypes)
                }
            } else {
                var next: String? = null
                if (caps.weightRead) {
                    val result = health.consumeWeightChanges(token)
                    if (result == null) { prefs.clearHealthChangesToken(); return }
                    weightRepository.importExternalWeights(result.first)
                    next = result.second
                }
                if (caps.bodyFatRead) {
                    val result = health.consumeBodyFatChanges(token)
                    if (result == null) { prefs.clearHealthChangesToken(); return }
                    bodyFatRepository.importExternalBodyFats(result.first)
                    next = result.second ?: next
                }
                next?.let { prefs.setHealthChangesToken(it) }
            }
        } finally {
            healthReadSyncInFlight = false
        }
    }

    /** One privacy-safe evidence snapshot shared by manual Recalculate and Adaptive Goals. Health
     *  Connect is queried once: its daily values both form the measured maintenance anchor and go
     *  into the evidence pack. App-estimated workout calories are excluded by HealthConnectManager. */
    suspend fun goalCalculationEvidence(profile: UserProfile): GoalCalculationEvidenceContext {
        val foods = foodRepository.entries.first()
        val weights = weightRepository.entries.first()
        val energy = measuredGoalEnergyIfEnabled(profile)
        return GoalCalculationEvidenceContext(
            evidence = GoalEvidenceBuilder.build(
                profile = profile,
                foods = foods,
                weights = weights,
                bodyFatEntries = bodyFatRepository.entries.first(),
                workouts = workoutRepository.completedSessions.first(),
                measurements = bodyMeasurementRepository.entries.first(),
                healthEnergyDays = energy.second
            ),
            measuredTdee = energy.first
        )
    }

    /** Compatibility helper for callers that only need the Health Connect maintenance anchor. */
    suspend fun measuredEnergyTdeeIfEnabled(profile: UserProfile): Int? =
        measuredGoalEnergyIfEnabled(profile).first

    private suspend fun measuredGoalEnergyIfEnabled(
        profile: UserProfile
    ): Pair<Int?, List<DailyHealthEnergyEvidence>> {
        if (!prefs.healthEnergyGoalsEnabled.first() || !prefs.healthConnectEnabled.first()) {
            return null to emptyList()
        }
        if (!health.isAvailable() || !health.hasEnergyRead()) return null to emptyList()
        val daily = runCatching { health.readRecentDailyEnergy(days = 14) }.getOrNull().orEmpty()
        // Reading Health Connect suspends. Respect an opt-out that happened while it was in flight
        // before constructing either the provider evidence or measured-maintenance anchor.
        if (!prefs.healthEnergyGoalsEnabled.first() || !prefs.healthConnectEnabled.first()) {
            return null to emptyList()
        }
        val evidence = daily.map {
            DailyHealthEnergyEvidence(
                date = it.date,
                externalActiveCalories = it.activeCalories,
                totalCalories = it.totalCalories
            )
        }
        if (daily.size < 3) return null to evidence
        val activeAverage = daily.map { it.activeCalories }.average().roundToInt()
        val totalAverage = daily.mapNotNull { it.totalCalories }
            // Match iOS: a measured-total anchor needs at least three complete total-energy
            // days. A lone anomalous total must not override the formula maintenance estimate.
            .takeIf { it.size >= 3 }
            ?.average()
            ?.roundToInt()
        return (totalAverage ?: (profile.bmr.roundToInt() + activeAverage)) to evidence
    }

    /**
     * Adaptive Goals: automatically re-runs the FULL AI goal calculation (the same one the
     * Recalculate button uses) about once a week, from the latest logged food + weight trend
     * (hit-and-trial) and — when Energy Burn is on — the measured Health maintenance anchor.
     * Silent and non-destructive on AI failure (keeps existing goals; marks checked so it does not
     * retry on every app open). Returned targets are validated against the same formula references
     * and plausibility bounds used by manual recalculation.
     */
    suspend fun refreshAdaptiveGoalsIfNeeded(force: Boolean = false): AdaptiveGoalResult? {
        if (adaptiveGoalsRefreshInFlight) return null
        adaptiveGoalsRefreshInFlight = true
        try {
            if (!prefs.adaptiveGoalsEnabled.first()) return null

            val today = LocalDate.now()
            if (!force && !shouldCheckAdaptiveGoals(prefs.adaptiveGoalsLastCheckDay.first(), today)) {
                return null
            }

            val profile = profileRepository.current() ?: return null
            val heightMetric = prefs.heightUnit.first() == "cm"
            val weightMetric = prefs.weightUnit.first() == "kg"
            val healthEnabledAtStart = prefs.healthConnectEnabled.first()
            val energyEnabledAtStart = prefs.healthEnergyGoalsEnabled.first()
            val result = runCatching {
                val context = goalCalculationEvidence(profile)
                if (prefs.healthConnectEnabled.first() != healthEnabledAtStart ||
                    prefs.healthEnergyGoalsEnabled.first() != energyEnabledAtStart ||
                    !prefs.adaptiveGoalsEnabled.first()
                ) return null
                foodAnalysis.calculateGoals(
                    profile = profile,
                    heightMetric = heightMetric,
                    weightMetric = weightMetric,
                    measuredTdee = context.measuredTdee,
                    measurement = bodyMeasurementRepository.latestSnapshot(),
                    evidence = context.evidence
                )
            }.getOrNull()
            if (result == null) {
                // Mark provider failure checked so a bad key is not hit on every foreground.
                if (prefs.adaptiveGoalsEnabled.first() &&
                    prefs.healthConnectEnabled.first() == healthEnabledAtStart &&
                    prefs.healthEnergyGoalsEnabled.first() == energyEnabledAtStart
                ) {
                    prefs.setAdaptiveGoalsLastCheckDay(today.toString())
                }
                return null
            }

            // Never overwrite profile/target edits made while the provider request was in flight.
            val latest = profileRepository.current() ?: return null
            if (latest != profile || !prefs.adaptiveGoalsEnabled.first() ||
                prefs.healthConnectEnabled.first() != healthEnabledAtStart ||
                prefs.healthEnergyGoalsEnabled.first() != energyEnabledAtStart
            ) return null
            prefs.setAdaptiveGoalsLastCheckDay(today.toString())
            prefs.saveAdaptiveGoalPreviousTargetsIfNeeded(latest)
            val next = latest.recalculatedFromFormulas().copy(
                customCalories = result.calories,
                customProtein = result.protein,
                customCarbs = result.carbs,
                customFat = result.fat
            )
            profileRepository.save(next)
            return AdaptiveGoalResult(
                profile = next,
                changed = true,
                updatedCalories = result.calories,
                message = "Updated to ${result.calories} kcal from your latest data." + (result.reason?.let { " $it" } ?: "")
            )
        } finally {
            adaptiveGoalsRefreshInFlight = false
        }
    }

    private fun shouldCheckAdaptiveGoals(lastCheckDay: String?, today: LocalDate): Boolean {
        val lastCheck = lastCheckDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return true
        return !lastCheck.plusDays(7).isAfter(today)
    }
}
