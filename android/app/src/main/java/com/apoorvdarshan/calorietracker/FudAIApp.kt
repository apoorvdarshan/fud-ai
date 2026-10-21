package com.apoorvdarshan.calorietracker

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.apoorvdarshan.calorietracker.data.BodyFatRepository
import com.apoorvdarshan.calorietracker.data.BodyMeasurementRepository
import com.apoorvdarshan.calorietracker.data.ChatRepository
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.data.FoodRepository
import com.apoorvdarshan.calorietracker.data.FastingRepository
import com.apoorvdarshan.calorietracker.data.ExerciseSessionSource
import com.apoorvdarshan.calorietracker.data.ImportedHealthWorkoutRepository
import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.data.ProfileRepository
import com.apoorvdarshan.calorietracker.data.WeightRepository
import com.apoorvdarshan.calorietracker.data.WaterRepository
import com.apoorvdarshan.calorietracker.data.WorkoutHealthSync
import com.apoorvdarshan.calorietracker.data.WorkoutRepository
import com.apoorvdarshan.calorietracker.backup.CloudBackupCoordinator
import com.apoorvdarshan.calorietracker.data.WeeklyChallengeRepository
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.services.NotificationService
import com.apoorvdarshan.calorietracker.services.StorageMaintenance
import com.apoorvdarshan.calorietracker.services.WorkoutFrameStore
import com.apoorvdarshan.calorietracker.services.WidgetSnapshotWriter
import com.apoorvdarshan.calorietracker.services.WorkoutRestAlarms
import com.apoorvdarshan.calorietracker.services.WorkoutRestTimerController
import com.apoorvdarshan.calorietracker.services.SharedPreferencesWorkoutRestTimerStore
import com.apoorvdarshan.calorietracker.services.AdaptiveGoalResult
import com.apoorvdarshan.calorietracker.services.DailyHealthEnergyEvidence
import com.apoorvdarshan.calorietracker.services.GoalEvidence
import com.apoorvdarshan.calorietracker.services.GoalEvidenceBuilder
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.CurrentMealSchedule
import com.apoorvdarshan.calorietracker.models.WorkoutRestTimer
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.services.ai.ChatService
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysisService
import com.apoorvdarshan.calorietracker.services.health.HealthCapabilities
import com.apoorvdarshan.calorietracker.services.health.HealthConnectManager
import com.apoorvdarshan.calorietracker.services.health.HealthExerciseSession
import com.apoorvdarshan.calorietracker.services.hosted.AiAccessMode
import com.apoorvdarshan.calorietracker.services.hosted.BillingManager
import com.apoorvdarshan.calorietracker.services.hosted.HostedAiAccess
import com.apoorvdarshan.calorietracker.services.hosted.HostedAiClient
import com.apoorvdarshan.calorietracker.services.hosted.HostedQuotaStore
import com.apoorvdarshan.calorietracker.services.hosted.HostedUiRouter
import com.apoorvdarshan.calorietracker.services.hosted.SharedPreferencesQuotaPersistence
import com.apoorvdarshan.calorietracker.services.ondevice.LocalGemmaRuntime
import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelId
import com.apoorvdarshan.calorietracker.services.ondevice.LocalModelManager
import com.apoorvdarshan.calorietracker.services.ondevice.LocalWhisperRuntime
import com.apoorvdarshan.calorietracker.services.speech.SpeechService
import com.apoorvdarshan.calorietracker.services.voice.VoiceAssistantController
import com.apoorvdarshan.calorietracker.services.voice.createVoicePreviewController
import com.apoorvdarshan.calorietracker.ui.navigation.AppNavigator
import com.apoorvdarshan.calorietracker.widget.WidgetRefreshScheduler
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
open class FudAIApp : Application() {

    // Instrumentation supplies a test-only Application subclass. Production
    // always starts the normal services; there is no Intent or saved reset flag.
    open val backgroundServicesEnabled: Boolean = true

    lateinit var container: AppContainer
        private set

    // Startup work (migrations, image pruning, reminder re-arming) must never
    // take the process down: an uncaught exception here would otherwise crash
    // on every launch until the user clears app data — and their diary with it.
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e("FudAIApp", "Background startup task failed", throwable)
        }
    )

    override fun onCreate() {
        super.onCreate()
        // Re-apply the AndroidX per-app locale list on API 32 and below, where
        // AppCompat stores it. Empty list = follow the system language.
        AppCompatDelegate.setApplicationLocales(AppCompatDelegate.getApplicationLocales())
        if (!backgroundServicesEnabled) {
            container = AppContainer(this)
            ExerciseRepository.warm(this)
            return
        }
        // RevenueCat must be configured before the container reads its app user id.
        runCatching { BillingManager.configure(this) }
            .onFailure { Log.e("FudAIApp", "RevenueCat configure failed", it) }
        container = AppContainer(this)
        container.billing.start()
        // A pending plan purchase (e.g. a slow UPI payment) turns Hosted on once Play confirms it.
        container.billing.entitlement
            .onEach { entitlement ->
                if (entitlement.isEntitled && container.prefs.pendingHostedSwitch.first()) {
                    container.prefs.setAiAccessMode(AiAccessMode.HOSTED)
                    container.prefs.setPendingHostedSwitch(false)
                }
            }
            .launchIn(appScope)
        container.notifications.createChannels()
        WidgetRefreshScheduler.onAppStarted(this)
        container.widgetSnapshotWriter.observe().launchIn(appScope)
        // Warm exercise catalog off the main thread before the first Workouts tab open.
        ExerciseRepository.warm(this)
        appScope.launch {
            container.prefs.reconcileLocalModelSelections()
            container.prefs.migrateAIModelSelections()
            container.prefs.migrateMatchingSpeechProviderIfNeeded()
            container.prefs.migrateFallbackBaseUrls()
            // Warm EncryptedSharedPreferences + OkHttp after migrations so the first food scan
            // does not pay keystore/TLS setup cost while the analyzing overlay is up.
            runCatching {
                val provider = container.prefs.selectedAIProvider.first()
                container.keyStore.apiKey(provider)
                FoodAnalysisService.defaultClient
            }
        }
        // Older Android builds removed food rows without removing their JPEGs.
        // Prune only unreferenced files; logged foods, saved meals, and pending
        // analysis drafts remain untouched.
        appScope.launch { container.imageStore.cleanupLegacyThumbnailDirectory() }
        // A Drive restore the system killed leaves its staged photos behind.
        appScope.launch { container.imageStore.discardAbandonedStaging() }
        appScope.launch { container.foodRepository.pruneOrphanedImages() }
        appScope.launch { container.weeklyChallengeRepository.retryPendingRemoteDeletion() }
        container.prefs.mealSchedule
            .onEach { CurrentMealSchedule.value = it }
            .launchIn(appScope)
        // Re-arm the daily weight-log alarm on every cold start. AlarmManager
        // drops scheduled alarms on device reboot and (sometimes) on app
        // updates — without this, a user who enabled Notifications once would
        // silently stop receiving the reminder after the next reboot.
        appScope.launch { container.rescheduleReminders() }
    }
}

/** Stable labels for the read types a Health Connect changes token was seeded for,
 *  persisted alongside the token so we can detect a newly-granted read capability. */
private const val HEALTH_READ_TYPE_WEIGHT = "weight"
private const val HEALTH_READ_TYPE_BODY_FAT = "bodyfat"
private const val HEALTH_READ_TYPE_HISTORY = "history"

/** Weight/body-fat backfill window — the Progress "All" range (TimeRange.ALL_TIME). */
internal const val HEALTH_BODY_BACKFILL_DAYS = 3650L

/** Labels for what the weight/body-fat changes token can see. History access is tracked
 *  too: granting it later unlocks records older than the 30-day pre-grant window, which
 *  the token (it only reports new changes) would never deliver. */
internal fun healthReadTokenTypes(caps: HealthCapabilities): Set<String> = buildSet {
    if (caps.weightRead) add(HEALTH_READ_TYPE_WEIGHT)
    if (caps.bodyFatRead) add(HEALTH_READ_TYPE_BODY_FAT)
    if (caps.historyRead && isNotEmpty()) add(HEALTH_READ_TYPE_HISTORY)
}

/** Stored with the read types by builds that remember deleted weight/body-fat ids, so every
 *  deletion since that token's backfill is on record and a full re-import is safe. */
private const val HEALTH_READ_DELETIONS_TRACKED = "deletionsTracked"

/** Token labels to store when seeding a changes token for [desiredTypes]. */
internal fun healthReadSeededTypes(desiredTypes: Set<String>): Set<String> =
    desiredTypes + HEALTH_READ_DELETIONS_TRACKED

/** How one weight/body-fat read sync uses Health Connect. */
internal enum class HealthReadSync {
    /** Consume the changes token. */
    CHANGES,
    /** No token, or a metric became readable after it was seeded: import the whole
     *  backfill window and seed a new token. */
    FULL_BACKFILL,
    /** Only history access is new, and the token was seeded by a build that did not
     *  remember deletions. Its backfill already imported everything readable then, and
     *  importing that window again would bring back readings deleted before deletions
     *  were remembered, so only records older than that window are imported (see
     *  [healthHistoryImportCutoff]). */
    OLDER_HISTORY_ONLY
}

internal fun healthReadSync(hasToken: Boolean, seededTypes: Set<String>, desiredTypes: Set<String>): HealthReadSync {
    if (!hasToken) return HealthReadSync.FULL_BACKFILL
    val missing = desiredTypes - seededTypes
    return when {
        missing.isEmpty() -> HealthReadSync.CHANGES
        missing == setOf(HEALTH_READ_TYPE_HISTORY) && HEALTH_READ_DELETIONS_TRACKED !in seededTypes ->
            HealthReadSync.OLDER_HISTORY_ONLY
        else -> HealthReadSync.FULL_BACKFILL
    }
}

/** Without history access Health Connect returns other apps' records from 30 days before
 *  the first grant on, and nothing can be granted before the app is installed, so records
 *  older than this were never readable before history access. */
internal fun healthRecordsHiddenWithoutHistoryBefore(firstInstall: Instant): Instant =
    firstInstall.minus(Duration.ofDays(30))

/**
 * Where [HealthReadSync.OLDER_HISTORY_ONLY] stops importing: records older than this.
 *
 * The earlier backfill imported every other-app reading it could read, so the oldest one
 * the history still holds ([heldExternalReadingTimes], weight and body fat together)
 * marks where its window began, however long after install the first grant came. Newer
 * readings the history lacks were readable then, so the user deleted them and they stay
 * out. With none held, the window began no earlier than 30 days before install
 * ([healthRecordsHiddenWithoutHistoryBefore]). Null when neither is known.
 */
internal fun healthHistoryImportCutoff(heldExternalReadingTimes: List<Instant>, firstInstall: Instant?): Instant? =
    heldExternalReadingTimes.minOrNull() ?: firstInstall?.let(::healthRecordsHiddenWithoutHistoryBefore)

/** Token labels after [HealthReadSync.OLDER_HISTORY_ONLY] imported the older records. */
internal fun healthReadTypesWithHistory(seededTypes: Set<String>): Set<String> =
    seededTypes + HEALTH_READ_TYPE_HISTORY

data class GoalCalculationEvidenceContext(
    val evidence: GoalEvidence,
    val measuredTdee: Int?
)

class AppContainer(app: FudAIApp) {
    val backgroundServicesEnabled = app.backgroundServicesEnabled
    val appContext = app.applicationContext
    val localModels = LocalModelManager(app, FoodAnalysisService.defaultClient)
    val prefs = PreferencesStore(
        app,
        isLocalGemmaExecutable = { localModels.isExecutable(LocalModelId.GEMMA_4_E2B) },
        isLocalWhisperExecutable = { localModels.isExecutable(LocalModelId.WHISPER_BASE) }
    )
    val keyStore = KeyStore(app)
    /** Named API-key profiles plus the credential bound to each AI routing slot. */
    val aiCredentials = keyStore.credentials.also { it.ensureMigrated() }
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
    /** Read-only workouts other apps (Garmin Connect, Strava, …) shared with Health Connect. */
    val importedHealthWorkoutRepository = ImportedHealthWorkoutRepository(
        store = prefs,
        source = object : ExerciseSessionSource {
            // Unavailable reads as a failed probe, never as revoked access (which clears imports).
            override suspend fun exerciseReadGranted(): Boolean? =
                if (health.isAvailable()) health.exerciseReadGrant() else null
            override suspend fun activeCaloriesReadable(): Boolean = health.hasActiveEnergyRead()
            override suspend fun readSessions(from: Instant, to: Instant): List<HealthExerciseSession>? =
                health.readExternalExerciseSessions(from, to)
            override suspend fun activeCalories(session: HealthExerciseSession, from: Instant) =
                health.readSessionActiveCalories(session, from)
            override fun appLabel(packageName: String): String? = health.appLabel(packageName)
        }
    )
    /** The rest countdown after a logged set; main-thread scoped so the UI and its end agree. */
    val workoutRestTimer = WorkoutRestTimerController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        alarms = object : WorkoutRestAlarms {
            override fun schedule(timer: WorkoutRestTimer) =
                notifications.scheduleWorkoutRest(timer.endsAt.toEpochMilli(), timer.exerciseName)

            override fun cancel() = notifications.cancelWorkoutRest()
        },
        store = SharedPreferencesWorkoutRestTimerStore(app)
    )
    val weeklyChallengeRepository = WeeklyChallengeRepository(app, keyStore)
    val cloudBackup = CloudBackupCoordinator(
        app, prefs, imageStore, keyStore,
        pruneUnusedPhotos = { foodRepository.pruneOrphanedImages() },
    )
    val storageMaintenance by lazy {
        StorageMaintenance(
            photos = imageStore,
            workoutFrameCacheBytes = { WorkoutFrameStore.get(appContext).cacheBytes() },
            clearWorkoutFrameCache = { WorkoutFrameStore.get(appContext).clearCache() },
            modelBytes = localModels::storedBytes
        )
    }

    val localGemma = LocalGemmaRuntime(app, localModels)
    val localWhisper = LocalWhisperRuntime(app, localModels)

    // Hosted AI (Plus/Pro): RevenueCat entitlements + the fud-ai.app Worker ledger.
    private val hostedScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e("AppContainer", "Hosted AI background task failed", throwable)
        }
    )
    val hostedQuota: HostedQuotaStore = HostedQuotaStore(SharedPreferencesQuotaPersistence(app), hostedScope) { refresh ->
        hostedAiClient.fetchQuota(refresh)
    }
    val billing = BillingManager(hostedScope, hostedQuota)
    val hostedAiClient = HostedAiClient(userId = billing::appUserId, quota = hostedQuota)
    val hostedAccess = HostedAiAccess(hostedAiClient) { billing.entitlement.value }
    val hostedUi = HostedUiRouter()

    val foodAnalysis = FoodAnalysisService(prefs, aiCredentials, localGemma = localGemma, hosted = hostedAccess)
    val chatService = ChatService(prefs, aiCredentials, localGemma = localGemma, hosted = hostedAccess)
    val speechService = SpeechService(prefs, keyStore, localWhisper = localWhisper, hosted = hostedAccess)

    val widgetSnapshotWriter = WidgetSnapshotWriter(app, prefs, foodRepository, profileRepository)

    /** Shared navigation state: what each screen shows, and requests from the voice assistant. */
    val navigator = AppNavigator()

    /** The live voice assistant (the orb). Created on first use. */
    val voiceAssistant: VoiceAssistantController by lazy { VoiceAssistantController(this) }
    /** Settings sample playback; never starts a conversation or microphone. */
    val voicePreview by lazy { createVoicePreviewController(this) }
    /**
     * App-scoped flag set by [HomeViewModel] while a food analysis request is
     * in flight. The bottom nav reads this so the bar can hide during the
     * AnalyzingOverlay (matches iOS, where the analyzing sheet covers the
     * tab bar).
     */
    val analyzingFood: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Schedules each enabled reminder and cancels the rest, from the saved settings. Runs on
     *  every cold start, and again after a Drive restore brings back another phone's settings. */
    suspend fun rescheduleReminders() {
        if (prefs.fastingTrackingEnabled.first()) {
            notifications.ensureFastingChannel()
        }
        if (!prefs.notificationsEnabled.first() || !notifications.canPostNotifications()) return
        if (prefs.streakReminderEnabled.first()) {
            notifications.scheduleStreakReminder(
                prefs.streakReminderHour.first(),
                prefs.streakReminderMinute.first()
            )
        } else {
            notifications.cancelStreakReminder()
        }
        if (prefs.dailySummaryEnabled.first()) {
            notifications.scheduleDailySummary(
                prefs.dailySummaryHour.first(),
                prefs.dailySummaryMinute.first()
            )
        } else {
            notifications.cancelDailySummary()
        }
        if (prefs.weightReminderEnabled.first()) {
            notifications.scheduleWeightReminder()
        } else {
            notifications.cancelWeightReminder()
        }
        // Body-fat reminder only fires for users who've actually opted
        // into body-fat tracking and left that notification type on.
        val profile = profileRepository.current()
        if (prefs.bodyFatReminderEnabled.first() && profile?.bodyFatPercentage != null) {
            notifications.scheduleBodyFatReminder()
        } else {
            notifications.cancelBodyFatReminder()
        }
        if (prefs.waterTrackingEnabled.first() && prefs.waterReminderEnabled.first()) {
            notifications.scheduleWaterReminder(
                prefs.waterReminderHour.first(),
                prefs.waterReminderMinute.first()
            )
        } else {
            notifications.cancelWaterReminder()
        }
        if (prefs.fastingTrackingEnabled.first() &&
            prefs.fastingGoalNotificationEnabled.first()
        ) {
            notifications.scheduleFastingGoal(fastingRepository.active())
        } else {
            notifications.cancelFastingGoal()
        }
    }

    private var adaptiveGoalsRefreshInFlight = false

    @Volatile
    private var healthReadSyncInFlight = false

    /** [syncHealthConnectReads] for a caller that shows what was imported: when another sync
     *  is already running (it returns at once then), waits for that one to finish instead. */
    suspend fun syncHealthConnectReadsAndWait(): Boolean {
        while (healthReadSyncInFlight) delay(100)
        syncHealthConnectReads()
        while (healthReadSyncInFlight) delay(100)
        return healthReadsCompleted()
    }

    /** Whether the steps a failed Health Connect read leaves undone are done: the one-shot
     *  meal restore and the weigh-in/body-fat changes token, for the types this phone can
     *  read. The sync itself doesn't report failures; it skips these so a later sync retries. */
    private suspend fun healthReadsCompleted(): Boolean {
        if (!prefs.healthConnectEnabled.first() || !health.isAvailable()) return false
        val caps = health.capabilities()
        if (caps.nutritionRead && !prefs.healthFoodRestoreDone.first()) return false
        if ((caps.weightRead || caps.bodyFatRead) && prefs.healthChangesToken.first() == null) return false
        return true
    }

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
        // Ahead of the toggle and capability guards below: revoking every Health Connect
        // permission, or Exercise while the toggle is off, returns before the import step,
        // yet other apps' workouts copied in earlier must still go (#479). Removal only.
        importedHealthWorkoutRepository.removeIfAccessRevoked()
        if (!prefs.healthConnectEnabled.first()) return
        if (!health.isAvailable()) return

        // Nutrition writes Health Connect never confirmed retry here. Deliberately ahead of
        // the read-capability guard below: a user who granted write but no reads still has a
        // queue to drain, and returning early would strand it forever.
        foodRepository.retryPendingHealthWrites()

        val caps = health.capabilities()
        val workoutBurnRead = health.hasActiveEnergyRead()
        if (!caps.weightRead && !caps.bodyFatRead && !caps.nutritionRead &&
            !workoutBurnRead && !caps.activeEnergyWrite && !caps.exerciseRead
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

            syncExternalBodyReadings(caps)

            // Other apps' exercise sessions, read-only (#479). Gated on its own optional
            // permission: it imports when granted and removes the copies once revoked. After
            // weigh-ins, since a first import can spend a whole sync's calorie-lookup budget.
            importedHealthWorkoutRepository.synchronize()
        } finally {
            healthReadSyncInFlight = false
        }
    }

    /** Weigh-ins and body-fat readings other apps (e.g. a scale) wrote to Health Connect:
     *  a changes-token pull, or a backfill when there is no token yet. */
    private suspend fun syncExternalBodyReadings(caps: HealthCapabilities) {
        if (!caps.weightRead && !caps.bodyFatRead) return

        val desiredTypes = healthReadTokenTypes(caps)
        // If a read type (or history access) was granted AFTER the token was seeded, the
        // existing token never observes the newly readable records. Drop the token so we
        // re-enter the backfill branch and import that history + re-seed a token covering
        // everything now granted — or, when only history is new, import just the older
        // records (see HealthReadSync.OLDER_HISTORY_ONLY) and keep the token.
        val seededTypes = prefs.healthChangesTokenTypes.first()
        when (healthReadSync(prefs.healthChangesToken.first() != null, seededTypes, desiredTypes)) {
            HealthReadSync.FULL_BACKFILL -> prefs.clearHealthChangesToken()
            // A failed read retries on the next sync. The token waits too: readings it
            // would import in between could move the import's cutoff.
            HealthReadSync.OLDER_HISTORY_ONLY -> if (!importHealthHistoryOlderThanToken(caps, seededTypes)) return
            HealthReadSync.CHANGES -> Unit
        }

        val token = prefs.healthChangesToken.first()
        if (token == null) {
            // First sync: backfill history so existing scale data and older weigh-ins show
            // up. Matches Progress "All"; without history access Health Connect itself
            // still caps other apps' records at 30 days before the first grant.
            val now = Instant.now()
            val from = now.minus(Duration.ofDays(HEALTH_BODY_BACKFILL_DAYS))
            var readEverything = true
            if (caps.weightRead) {
                val weights = health.readWeights(from, now)
                if (weights != null) weightRepository.importExternalWeights(weights) else readEverything = false
            }
            if (caps.bodyFatRead) {
                val bodyFats = health.readBodyFats(from, now)
                if (bodyFats != null) bodyFatRepository.importExternalBodyFats(bodyFats) else readEverything = false
            }
            // A token seeded now would never deliver what a failed read missed; leave it
            // unset so the next sync runs the backfill again (imports are deduped).
            if (!readEverything) return
            seedBodyReadingsChangesToken(caps)
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
    }

    /** Seeds a weight/body-fat changes token covering only the types [caps] can read. */
    private suspend fun seedBodyReadingsChangesToken(caps: HealthCapabilities) {
        val recordTypes = buildSet {
            if (caps.weightRead) add(androidx.health.connect.client.records.WeightRecord::class)
            if (caps.bodyFatRead) add(androidx.health.connect.client.records.BodyFatRecord::class)
        }
        health.getChangesToken(recordTypes)?.let {
            prefs.setHealthChangesToken(it)
            prefs.setHealthChangesTokenTypes(healthReadSeededTypes(healthReadTokenTypes(caps)))
        }
    }

    /**
     * Right after a Drive restore: adds what Fud AI saved to Health Connect that the backup
     * lacks (meals logged after it, newer weigh-ins), matched by the ids the regular sync
     * uses. Unlike that sync it never replaces a restored record, skips deleted ids, and
     * leaves the profile alone. The changes token it seeds keeps the next regular sync from
     * re-running its upserting backfill over the restored history. Workout burns are left to
     * the regular sync's per-day reconcile, which keeps a restored session unless Health
     * Connect holds a newer edit of that same session.
     */
    suspend fun importHealthConnectAddingOnly(): Boolean {
        if (!prefs.healthConnectEnabled.first() || !health.isAvailable()) return false
        while (healthReadSyncInFlight) delay(100)
        healthReadSyncInFlight = true
        try {
            val caps = health.capabilities()
            val now = Instant.now()
            var readEverything = true
            if (caps.nutritionRead) {
                // Ids already in the restored log are skipped.
                val records = health.readNutrition(now.minus(Duration.ofDays(730)), now)
                if (records != null) foodRepository.restoreFromHealthConnect(records) else readEverything = false
            }
            if (caps.weightRead || caps.bodyFatRead) {
                val from = now.minus(Duration.ofDays(HEALTH_BODY_BACKFILL_DAYS))
                var readBodyReadings = true
                if (caps.weightRead) {
                    val weights = health.readWeights(from, now)
                    if (weights != null) weightRepository.addMissingExternalWeights(weights) else readBodyReadings = false
                }
                if (caps.bodyFatRead) {
                    val bodyFats = health.readBodyFats(from, now)
                    if (bodyFats != null) bodyFatRepository.addMissingExternalBodyFats(bodyFats) else readBodyReadings = false
                }
                if (readBodyReadings) seedBodyReadingsChangesToken(caps) else readEverything = false
            }
            // Other apps' workouts are never in a backup; this only adds read-only copies.
            importedHealthWorkoutRepository.synchronize()
            return readEverything
        } finally {
            healthReadSyncInFlight = false
        }
    }

    /** [HealthReadSync.OLDER_HISTORY_ONLY]: imports the weigh-ins and body-fat readings
     *  that history access newly unlocked, then marks the token as covering history.
     *  Returns false when a read failed: nothing is imported or marked then, so the next
     *  sync retries with the same cutoff. */
    private suspend fun importHealthHistoryOlderThanToken(caps: HealthCapabilities, seededTypes: Set<String>): Boolean {
        val now = Instant.now()
        val from = now.minus(Duration.ofDays(HEALTH_BODY_BACKFILL_DAYS))
        // The whole window, not just the older part: the readings the history already
        // holds are what place the cutoff.
        val weights = if (caps.weightRead) health.readWeights(from, now) ?: return false else emptyList()
        val bodyFats = if (caps.bodyFatRead) health.readBodyFats(from, now) ?: return false else emptyList()
        val firstInstall = runCatching {
            Instant.ofEpochMilli(appContext.packageManager.getPackageInfo(appContext.packageName, 0).firstInstallTime)
        }.getOrNull()
        val cutoff = healthHistoryImportCutoff(
            weightRepository.heldExternalReadingTimes(weights) + bodyFatRepository.heldExternalReadingTimes(bodyFats),
            firstInstall
        ) ?: return true // No safe cutoff yet; try again next sync without holding up the token.
        weightRepository.importExternalWeights(weights.filter { it.time.isBefore(cutoff) })
        bodyFatRepository.importExternalBodyFats(bodyFats.filter { it.time.isBefore(cutoff) })
        prefs.setHealthChangesTokenTypes(healthReadTypesWithHistory(seededTypes))
        return true
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
                message = "Updated to ${prefs.energyUnit.first().format(appContext.resources, result.calories)} from your latest data." +
                    (result.reason?.let { " $it" } ?: "")
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
