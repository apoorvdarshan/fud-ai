package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.OpenRouterReasoningEffort
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.AutoBalanceMacro
import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.BodyMeasurement
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.HomeTopNutrient
import com.apoorvdarshan.calorietracker.models.MealSchedule
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import com.apoorvdarshan.calorietracker.models.PendingFoodAnalysisDraft
import com.apoorvdarshan.calorietracker.models.QuickAction
import com.apoorvdarshan.calorietracker.models.SpeechLanguage
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WidgetSnapshot
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.models.WorkoutPersistedState
import com.apoorvdarshan.calorietracker.ui.theme.AppThemeColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

val Context.fudaiDataStore by preferencesDataStore(name = "fudai_prefs")

@Serializable
private data class HealthEnergyGoalTargetSnapshot(
    val customCalories: Int? = null,
    val customProtein: Int? = null,
    val customFat: Int? = null,
    val customCarbs: Int? = null,
    val autoBalanceMacro: AutoBalanceMacro? = null
)

internal fun executableAIProviderOrDefault(
    provider: AIProvider?,
    localGemmaExecutable: Boolean,
    default: AIProvider = AIProvider.GEMINI
): AIProvider = provider?.takeUnless {
    it == AIProvider.LOCAL_GEMMA && !localGemmaExecutable
} ?: default

internal fun executableAIModelOrDefault(
    provider: AIProvider?,
    model: String?,
    localGemmaExecutable: Boolean,
    defaultModel: String
): String? = if (provider == AIProvider.LOCAL_GEMMA && !localGemmaExecutable) {
    defaultModel
} else {
    model
}

internal fun executableSpeechProviderOrDefault(
    provider: SpeechProvider?,
    localWhisperExecutable: Boolean,
    default: SpeechProvider
): SpeechProvider = provider?.takeUnless {
    it == SpeechProvider.LOCAL_WHISPER && !localWhisperExecutable
} ?: default

/**
 * Thin wrapper over DataStore Preferences for all app state except API keys
 * (which live in [KeyStore]). Exposes reactive Flows for reads and suspend
 * functions for writes. Complex values (profile, entries, history) are stored
 * as JSON strings via kotlinx.serialization.
 */
class PreferencesStore(
    private val context: Context,
    private val isLocalGemmaExecutable: () -> Boolean = { false },
    private val isLocalWhisperExecutable: () -> Boolean = { false }
) : WorkoutStateStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val ds get() = context.fudaiDataStore

    // -- User profile -----------------------------------------------------
    val userProfile: Flow<UserProfile?> = ds.data.map { prefs ->
        prefs[Keys.USER_PROFILE]?.let { runCatching { json.decodeFromString<UserProfile>(it) }.getOrNull() }
    }

    suspend fun setUserProfile(profile: UserProfile) {
        ds.edit { it[Keys.USER_PROFILE] = json.encodeToString(UserProfile.serializer(), profile) }
    }

    // -- Onboarding -------------------------------------------------------
    val hasCompletedOnboarding: Flow<Boolean> = ds.data.map { it[Keys.ONBOARDING_COMPLETED] ?: false }
    suspend fun setOnboardingCompleted(value: Boolean) {
        ds.edit { prefs ->
            // Seed only new users; keep the legacy fallback and saved choices intact.
            if (value && prefs[Keys.ONBOARDING_COMPLETED] != true &&
                prefs[Keys.FOOD_LOG_SORT_ORDER] == null
            ) {
                prefs[Keys.FOOD_LOG_SORT_ORDER] = "latestMealsFirst"
            }
            prefs[Keys.ONBOARDING_COMPLETED] = value
        }
    }

    // -- Notifications ----------------------------------------------------
    val notificationsEnabled: Flow<Boolean> = ds.data.map { it[Keys.NOTIFICATIONS_ENABLED] ?: false }
    suspend fun setNotificationsEnabled(v: Boolean) { ds.edit { it[Keys.NOTIFICATIONS_ENABLED] = v } }

    val streakReminderEnabled: Flow<Boolean> = ds.data.map { it[Keys.STREAK_ENABLED] ?: false }
    suspend fun setStreakReminderEnabled(v: Boolean) { ds.edit { it[Keys.STREAK_ENABLED] = v } }

    val streakReminderHour: Flow<Int> = ds.data.map { it[Keys.STREAK_HOUR] ?: 19 }
    suspend fun setStreakReminderHour(v: Int) { ds.edit { it[Keys.STREAK_HOUR] = v } }

    val streakReminderMinute: Flow<Int> = ds.data.map { it[Keys.STREAK_MINUTE] ?: 0 }
    suspend fun setStreakReminderMinute(v: Int) { ds.edit { it[Keys.STREAK_MINUTE] = v } }

    val dailySummaryEnabled: Flow<Boolean> = ds.data.map { it[Keys.DAILY_ENABLED] ?: false }
    suspend fun setDailySummaryEnabled(v: Boolean) { ds.edit { it[Keys.DAILY_ENABLED] = v } }

    val dailySummaryHour: Flow<Int> = ds.data.map { it[Keys.DAILY_HOUR] ?: 21 }
    suspend fun setDailySummaryHour(v: Int) { ds.edit { it[Keys.DAILY_HOUR] = v } }

    val dailySummaryMinute: Flow<Int> = ds.data.map { it[Keys.DAILY_MINUTE] ?: 0 }
    suspend fun setDailySummaryMinute(v: Int) { ds.edit { it[Keys.DAILY_MINUTE] = v } }

    val weightReminderEnabled: Flow<Boolean> = ds.data.map { it[Keys.WEIGHT_REMINDER_ENABLED] ?: true }
    suspend fun setWeightReminderEnabled(v: Boolean) { ds.edit { it[Keys.WEIGHT_REMINDER_ENABLED] = v } }

    val bodyFatReminderEnabled: Flow<Boolean> = ds.data.map { it[Keys.BODY_FAT_REMINDER_ENABLED] ?: true }
    suspend fun setBodyFatReminderEnabled(v: Boolean) { ds.edit { it[Keys.BODY_FAT_REMINDER_ENABLED] = v } }

    val goalReachedNotificationsEnabled: Flow<Boolean> = ds.data.map { it[Keys.GOAL_REACHED_NOTIFICATIONS_ENABLED] ?: true }
    suspend fun setGoalReachedNotificationsEnabled(v: Boolean) { ds.edit { it[Keys.GOAL_REACHED_NOTIFICATIONS_ENABLED] = v } }

    val appUpdateNotificationsEnabled: Flow<Boolean> = ds.data.map { it[Keys.APP_UPDATE_NOTIFICATIONS_ENABLED] ?: true }
    suspend fun setAppUpdateNotificationsEnabled(v: Boolean) { ds.edit { it[Keys.APP_UPDATE_NOTIFICATIONS_ENABLED] = v } }

    // -- Water tracking --------------------------------------------------
    val waterTrackingEnabled: Flow<Boolean> = ds.data.map { it[Keys.WATER_TRACKING_ENABLED] ?: false }
    suspend fun setWaterTrackingEnabled(v: Boolean) { ds.edit { it[Keys.WATER_TRACKING_ENABLED] = v } }

    val waterDailyGoalMl: Flow<Int> = ds.data.map { it[Keys.WATER_DAILY_GOAL_ML] ?: 2_000 }
    suspend fun setWaterDailyGoalMl(v: Int) { ds.edit { it[Keys.WATER_DAILY_GOAL_ML] = v.coerceAtLeast(1) } }

    val waterUnit: Flow<WaterUnit> = ds.data.map { WaterUnit.fromStorage(it[Keys.WATER_UNIT]) }
    suspend fun setWaterUnit(v: WaterUnit) { ds.edit { it[Keys.WATER_UNIT] = v.storageValue } }

    val waterReminderEnabled: Flow<Boolean> = ds.data.map { it[Keys.WATER_REMINDER_ENABLED] ?: false }
    suspend fun setWaterReminderEnabled(v: Boolean) { ds.edit { it[Keys.WATER_REMINDER_ENABLED] = v } }

    val waterReminderHour: Flow<Int> = ds.data.map { it[Keys.WATER_REMINDER_HOUR] ?: 14 }
    val waterReminderMinute: Flow<Int> = ds.data.map { it[Keys.WATER_REMINDER_MINUTE] ?: 0 }

    val waterEntries: Flow<List<WaterEntry>> = ds.data.map { prefs ->
        prefs[Keys.WATER_ENTRIES]?.let {
            runCatching { json.decodeFromString(ListSerializer(WaterEntry.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setWaterEntries(entries: List<WaterEntry>) {
        ds.edit { it[Keys.WATER_ENTRIES] = json.encodeToString(ListSerializer(WaterEntry.serializer()), entries) }
    }

    // -- Fasting tracking -----------------------------------------------
    val fastingTrackingEnabled: Flow<Boolean> = ds.data.map { it[Keys.FASTING_TRACKING_ENABLED] ?: false }
    suspend fun setFastingTrackingEnabled(v: Boolean) { ds.edit { it[Keys.FASTING_TRACKING_ENABLED] = v } }

    val fastingDefaultGoalMinutes: Flow<Int> = ds.data.map {
        (it[Keys.FASTING_DEFAULT_GOAL_MINUTES] ?: 16 * 60).coerceIn(60, 7 * 24 * 60)
    }
    suspend fun setFastingDefaultGoalMinutes(v: Int) {
        ds.edit { it[Keys.FASTING_DEFAULT_GOAL_MINUTES] = v.coerceIn(60, 7 * 24 * 60) }
    }

    val fastingGoalNotificationEnabled: Flow<Boolean> = ds.data.map {
        it[Keys.FASTING_GOAL_NOTIFICATION_ENABLED] ?: true
    }
    suspend fun setFastingGoalNotificationEnabled(v: Boolean) {
        ds.edit { it[Keys.FASTING_GOAL_NOTIFICATION_ENABLED] = v }
    }

    val fastingSessions: Flow<List<FastingSession>> = ds.data.map { prefs ->
        prefs[Keys.FASTING_SESSIONS]?.let {
            runCatching { json.decodeFromString(ListSerializer(FastingSession.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setFastingSessions(sessions: List<FastingSession>) {
        ds.edit { it[Keys.FASTING_SESSIONS] = json.encodeToString(ListSerializer(FastingSession.serializer()), sessions) }
    }

    /// Last app version a "new update" notification was posted for — so it fires at most once per
    /// version even though the update check runs on every launch.
    val lastNotifiedUpdateVersion: Flow<String?> = ds.data.map { it[Keys.LAST_NOTIFIED_UPDATE_VERSION] }
    suspend fun setLastNotifiedUpdateVersion(v: String) { ds.edit { it[Keys.LAST_NOTIFIED_UPDATE_VERSION] = v } }

    // -- Health Connect ---------------------------------------------------
    val healthConnectEnabled: Flow<Boolean> = ds.data.map { it[Keys.HEALTH_CONNECT_ENABLED] ?: false }
    suspend fun setHealthConnectEnabled(v: Boolean) { ds.edit { it[Keys.HEALTH_CONNECT_ENABLED] = v } }

    val healthPermissionsVersion: Flow<Int> = ds.data.map { it[Keys.HEALTH_TYPES_VERSION] ?: 0 }
    suspend fun setHealthPermissionsVersion(v: Int) { ds.edit { it[Keys.HEALTH_TYPES_VERSION] = v } }

    /// Opaque Health Connect changes token for incremental weight/body-fat read-sync.
    /// Null means "no sync yet" → the coordinator does a one-time historical backfill.
    val healthChangesToken: Flow<String?> = ds.data.map { it[Keys.HEALTH_CHANGES_TOKEN] }
    suspend fun setHealthChangesToken(v: String) { ds.edit { it[Keys.HEALTH_CHANGES_TOKEN] = v } }
    suspend fun clearHealthChangesToken() {
        ds.edit { it.remove(Keys.HEALTH_CHANGES_TOKEN); it.remove(Keys.HEALTH_CHANGES_TOKEN_TYPES) }
    }

    /// Which read types the current changes token was seeded for (e.g. {"weight","bodyfat"}).
    /// If a newly-granted read type isn't covered, the coordinator drops the token and
    /// re-backfills so the new metric's history is imported.
    val healthChangesTokenTypes: Flow<Set<String>> = ds.data.map {
        it[Keys.HEALTH_CHANGES_TOKEN_TYPES]?.split(",")?.filter { s -> s.isNotBlank() }?.toSet() ?: emptySet()
    }
    suspend fun setHealthChangesTokenTypes(types: Set<String>) {
        ds.edit { it[Keys.HEALTH_CHANGES_TOKEN_TYPES] = types.joinToString(",") }
    }

    /// One-shot flag for the food-log restore from Health Connect. Cleared with the
    /// rest of the store on Delete All Data / fresh install, which is exactly when
    /// the restore should be allowed to run again.
    val healthFoodRestoreDone: Flow<Boolean> = ds.data.map { it[Keys.HEALTH_FOOD_RESTORE_DONE] ?: false }
    suspend fun setHealthFoodRestoreDone(v: Boolean) { ds.edit { it[Keys.HEALTH_FOOD_RESTORE_DONE] = v } }

    val healthEnergyGoalsEnabled: Flow<Boolean> = ds.data.map { it[Keys.HEALTH_ENERGY_GOALS_ENABLED] ?: false }
    suspend fun setHealthEnergyGoalsEnabled(v: Boolean) { ds.edit { it[Keys.HEALTH_ENERGY_GOALS_ENABLED] = v } }

    val healthEnergyGoalsLastAutoRefreshDay: Flow<String?> = ds.data.map {
        it[Keys.HEALTH_ENERGY_GOALS_LAST_AUTO_REFRESH_DAY]
    }
    suspend fun setHealthEnergyGoalsLastAutoRefreshDay(v: String) {
        ds.edit { it[Keys.HEALTH_ENERGY_GOALS_LAST_AUTO_REFRESH_DAY] = v }
    }

    val reviewPromptedAfterFirstLog: Flow<Boolean> = ds.data.map { it[Keys.REVIEW_PROMPTED_AFTER_FIRST_LOG] ?: false }
    suspend fun setReviewPromptedAfterFirstLog(v: Boolean) { ds.edit { it[Keys.REVIEW_PROMPTED_AFTER_FIRST_LOG] = v } }

    val adaptiveGoalsEnabled: Flow<Boolean> = ds.data.map { it[Keys.ADAPTIVE_GOALS_ENABLED] ?: false }
    suspend fun setAdaptiveGoalsEnabled(v: Boolean) { ds.edit { it[Keys.ADAPTIVE_GOALS_ENABLED] = v } }

    val adaptiveGoalsLastCheckDay: Flow<String?> = ds.data.map {
        it[Keys.ADAPTIVE_GOALS_LAST_CHECK_DAY]
    }
    suspend fun setAdaptiveGoalsLastCheckDay(v: String) {
        ds.edit { it[Keys.ADAPTIVE_GOALS_LAST_CHECK_DAY] = v }
    }

    suspend fun saveAdaptiveGoalPreviousTargetsIfNeeded(profile: UserProfile) {
        ds.edit { prefs ->
            if (prefs[Keys.ADAPTIVE_GOALS_PREVIOUS_TARGETS] != null) return@edit
            val snapshot = HealthEnergyGoalTargetSnapshot(
                customCalories = profile.customCalories,
                customProtein = profile.customProtein,
                customFat = profile.customFat,
                customCarbs = profile.customCarbs,
                autoBalanceMacro = profile.autoBalanceMacro
            )
            prefs[Keys.ADAPTIVE_GOALS_PREVIOUS_TARGETS] =
                json.encodeToString(HealthEnergyGoalTargetSnapshot.serializer(), snapshot)
        }
    }

    suspend fun restoreAdaptiveGoalPreviousTargets(profile: UserProfile): UserProfile {
        val snapshot = ds.data.first()[Keys.ADAPTIVE_GOALS_PREVIOUS_TARGETS]
            ?.let { runCatching { json.decodeFromString<HealthEnergyGoalTargetSnapshot>(it) }.getOrNull() }
            ?: return profile
        return profile.copy(
            customCalories = snapshot.customCalories,
            customProtein = snapshot.customProtein,
            customFat = snapshot.customFat,
            customCarbs = snapshot.customCarbs,
            autoBalanceMacro = snapshot.autoBalanceMacro
        )
    }

    suspend fun clearAdaptiveGoalPreviousTargets() {
        ds.edit { it.remove(Keys.ADAPTIVE_GOALS_PREVIOUS_TARGETS) }
    }

    suspend fun saveHealthEnergyGoalPreviousTargetsIfNeeded(profile: UserProfile) {
        ds.edit { prefs ->
            if (prefs[Keys.HEALTH_ENERGY_GOALS_PREVIOUS_TARGETS] != null) return@edit
            val snapshot = HealthEnergyGoalTargetSnapshot(
                customCalories = profile.customCalories,
                customProtein = profile.customProtein,
                customFat = profile.customFat,
                customCarbs = profile.customCarbs,
                autoBalanceMacro = profile.autoBalanceMacro
            )
            prefs[Keys.HEALTH_ENERGY_GOALS_PREVIOUS_TARGETS] =
                json.encodeToString(HealthEnergyGoalTargetSnapshot.serializer(), snapshot)
        }
    }

    suspend fun restoreHealthEnergyGoalPreviousTargets(profile: UserProfile): UserProfile {
        val snapshot = ds.data.first()[Keys.HEALTH_ENERGY_GOALS_PREVIOUS_TARGETS]
            ?.let { runCatching { json.decodeFromString<HealthEnergyGoalTargetSnapshot>(it) }.getOrNull() }
        return if (snapshot == null) {
            profile.copy(
                customCalories = null,
                customProtein = null,
                customFat = null,
                customCarbs = null,
                autoBalanceMacro = null
            )
        } else {
            profile.copy(
                customCalories = snapshot.customCalories,
                customProtein = snapshot.customProtein,
                customFat = snapshot.customFat,
                customCarbs = snapshot.customCarbs,
                autoBalanceMacro = snapshot.autoBalanceMacro
            )
        }
    }

    suspend fun clearHealthEnergyGoalPreviousTargets() {
        ds.edit { it.remove(Keys.HEALTH_ENERGY_GOALS_PREVIOUS_TARGETS) }
    }

    // -- Units ------------------------------------------------------------
    val useMetric: Flow<Boolean> = ds.data.map { it[Keys.USE_METRIC] ?: true }
    suspend fun setUseMetric(v: Boolean) { ds.edit { it[Keys.USE_METRIC] = v } }

    /** "cm" | "ftin". Falls back to the legacy useMetric flag when unset. */
    val heightUnit: Flow<String> = ds.data.map {
        it[Keys.HEIGHT_UNIT] ?: (if (it[Keys.USE_METRIC] ?: true) "cm" else "ftin")
    }
    suspend fun setHeightUnit(v: String) { ds.edit { it[Keys.HEIGHT_UNIT] = v } }

    /** "kg" | "lbs". Falls back to the legacy useMetric flag when unset. */
    val weightUnit: Flow<String> = ds.data.map {
        it[Keys.WEIGHT_UNIT] ?: (if (it[Keys.USE_METRIC] ?: true) "kg" else "lbs")
    }
    suspend fun setWeightUnit(v: String) { ds.edit { it[Keys.WEIGHT_UNIT] = v } }

    val preferGramsByDefault: Flow<Boolean> = ds.data.map { it[Keys.PREFER_GRAMS_BY_DEFAULT] ?: false }
    suspend fun setPreferGramsByDefault(v: Boolean) { ds.edit { it[Keys.PREFER_GRAMS_BY_DEFAULT] = v } }

    /** "system" | "light" | "dark". Mirrors iOS @AppStorage("appearanceMode"). */
    val appearanceMode: Flow<String> = ds.data.map { it[Keys.APPEARANCE_MODE] ?: "system" }
    suspend fun setAppearanceMode(v: String) { ds.edit { it[Keys.APPEARANCE_MODE] = v } }

    /** Mirrors iOS @AppStorage("appThemeColor"). */
    val appThemeColor: Flow<String> = ds.data.map { it[Keys.APP_THEME_COLOR] ?: AppThemeColor.DEFAULT_KEY }
    suspend fun setAppThemeColor(v: String) { ds.edit { it[Keys.APP_THEME_COLOR] = v } }

    /** false = Sunday, true = Monday (default). Mirrors iOS @AppStorage("weekStartsOnMonday"). */
    val weekStartsOnMonday: Flow<Boolean> = ds.data.map { it[Keys.WEEK_STARTS_MONDAY] ?: true }
    suspend fun setWeekStartsOnMonday(v: Boolean) { ds.edit { it[Keys.WEEK_STARTS_MONDAY] = v } }

    val quickAction1: Flow<QuickAction> = quickAction(Keys.QUICK_ACTION_1, QuickAction.Defaults[0])
    val quickAction2: Flow<QuickAction> = quickAction(Keys.QUICK_ACTION_2, QuickAction.Defaults[1])
    val quickAction3: Flow<QuickAction> = quickAction(Keys.QUICK_ACTION_3, QuickAction.Defaults[2])

    private fun quickAction(key: Preferences.Key<String>, fallback: QuickAction): Flow<QuickAction> =
        ds.data.map { QuickAction.fromStorage(it[key], fallback) }

    suspend fun setQuickAction(slot: Int, action: QuickAction) {
        val key = when (slot) {
            0 -> Keys.QUICK_ACTION_1
            1 -> Keys.QUICK_ACTION_2
            2 -> Keys.QUICK_ACTION_3
            else -> return
        }
        ds.edit { it[key] = action.name }
    }

    // -- Workout diary ---------------------------------------------------
    override val workoutState: Flow<WorkoutPersistedState> = ds.data.map { prefs ->
        prefs[Keys.WORKOUT_STATE]?.let {
            runCatching { json.decodeFromString<WorkoutPersistedState>(it) }.getOrNull()
        }?.sanitized() ?: WorkoutPersistedState()
    }

    override suspend fun setWorkoutState(state: WorkoutPersistedState) {
        ds.edit {
            it[Keys.WORKOUT_STATE] = json.encodeToString(
                WorkoutPersistedState.serializer(),
                state.sanitized()
            )
        }
    }

    override suspend fun clearWorkoutState() {
        ds.edit { it.remove(Keys.WORKOUT_STATE) }
    }

    val mealSchedule: Flow<MealSchedule> = ds.data.map { prefs ->
        MealSchedule(
            breakfastStartMinutes = prefs[Keys.MEAL_BREAKFAST_START] ?: MealSchedule.DEFAULT_BREAKFAST_START,
            lunchStartMinutes = prefs[Keys.MEAL_LUNCH_START] ?: MealSchedule.DEFAULT_LUNCH_START,
            dinnerStartMinutes = prefs[Keys.MEAL_DINNER_START] ?: MealSchedule.DEFAULT_DINNER_START,
            snackStartMinutes = prefs[Keys.MEAL_SNACK_START] ?: MealSchedule.DEFAULT_SNACK_START
        ).validatedOrDefault()
    }

    suspend fun setMealSchedule(schedule: MealSchedule) {
        val validated = schedule.validatedOrDefault()
        ds.edit {
            it[Keys.MEAL_BREAKFAST_START] = validated.breakfastStartMinutes
            it[Keys.MEAL_LUNCH_START] = validated.lunchStartMinutes
            it[Keys.MEAL_DINNER_START] = validated.dinnerStartMinutes
            it[Keys.MEAL_SNACK_START] = validated.snackStartMinutes
        }
    }

    /** "RECENTS" | "FREQUENT" | "FAVORITES". Mirrors iOS @AppStorage("lastRecentsSegment"). */
    val lastSavedMealsSegment: Flow<String> = ds.data.map { it[Keys.LAST_SAVED_MEALS_SEGMENT] ?: "RECENTS" }
    suspend fun setLastSavedMealsSegment(v: String) { ds.edit { it[Keys.LAST_SAVED_MEALS_SEGMENT] = v } }

    /** "standard" | "latestMealsFirst". Mirrors iOS @AppStorage("foodLogSortOrder"). */
    val foodLogSortOrder: Flow<String> = ds.data.map { it[Keys.FOOD_LOG_SORT_ORDER] ?: "standard" }
    suspend fun setFoodLogSortOrder(v: String) { ds.edit { it[Keys.FOOD_LOG_SORT_ORDER] = v } }

    /** Comma-separated [HomeTopNutrient.storageKey] values for the three home nutrient cards. */
    val homeTopNutrients: Flow<String> = ds.data.map {
        it[Keys.HOME_TOP_NUTRIENTS] ?: HomeTopNutrient.DefaultStorageValue
    }
    suspend fun setHomeTopNutrients(v: String) {
        ds.edit { it[Keys.HOME_TOP_NUTRIENTS] = v }
    }

    /** Goals for nutrients outside the calorie/protein/carb/fat calculator. */
    val optionalNutrientGoals: Flow<OptionalNutrientGoals> = ds.data.map { prefs ->
        prefs[Keys.OPTIONAL_NUTRIENT_GOALS]?.let {
            runCatching { json.decodeFromString<OptionalNutrientGoals>(it) }.getOrNull()
        } ?: OptionalNutrientGoals.Default
    }
    suspend fun setOptionalNutrientGoals(goals: OptionalNutrientGoals) {
        ds.edit {
            it[Keys.OPTIONAL_NUTRIENT_GOALS] =
                json.encodeToString(OptionalNutrientGoals.serializer(), goals)
        }
    }

    // -- AI Provider selection --------------------------------------------
    val selectedAIProvider: Flow<AIProvider> = ds.data.map {
        val raw = it[Keys.SELECTED_AI_PROVIDER]
        executableAIProviderOrDefault(
            AIProvider.visionProviders.firstOrNull { p -> p.name == raw },
            isLocalGemmaExecutable()
        )
    }
    suspend fun setSelectedAIProvider(p: AIProvider) {
        val resolved = executableAIProviderOrDefault(
            p.takeIf { it.supportsVision },
            isLocalGemmaExecutable()
        )
        ds.edit { it[Keys.SELECTED_AI_PROVIDER] = resolved.name }
    }

    val selectedAIModel: Flow<String?> = ds.data.map {
        executableAIModelOrDefault(
            storedAIProvider(it[Keys.SELECTED_AI_PROVIDER]),
            it[Keys.SELECTED_AI_MODEL],
            isLocalGemmaExecutable(),
            AIProvider.GEMINI.defaultModel
        )
    }
    suspend fun setSelectedAIModel(model: String) {
        ds.edit { it[Keys.SELECTED_AI_MODEL] = AIProvider.normalizeModelId(model) }
    }

    val separateTextProviderEnabled: Flow<Boolean> = ds.data.map {
        it[Keys.SEPARATE_TEXT_PROVIDER_ENABLED] ?: false
    }
    suspend fun setSeparateTextProviderEnabled(enabled: Boolean) {
        ds.edit { it[Keys.SEPARATE_TEXT_PROVIDER_ENABLED] = enabled }
    }

    val selectedTextAIProvider: Flow<AIProvider> = ds.data.map {
        val raw = it[Keys.SELECTED_TEXT_AI_PROVIDER]
        executableAIProviderOrDefault(
            AIProvider.textProviders.firstOrNull { p -> p.name == raw },
            isLocalGemmaExecutable()
        )
    }
    suspend fun setSelectedTextAIProvider(provider: AIProvider) {
        val resolved = executableAIProviderOrDefault(
            provider.takeIf { it in AIProvider.textProviders },
            isLocalGemmaExecutable()
        )
        ds.edit { it[Keys.SELECTED_TEXT_AI_PROVIDER] = resolved.name }
    }

    val selectedTextAIModel: Flow<String?> = ds.data.map {
        executableAIModelOrDefault(
            storedAIProvider(it[Keys.SELECTED_TEXT_AI_PROVIDER]),
            it[Keys.SELECTED_TEXT_AI_MODEL],
            isLocalGemmaExecutable(),
            AIProvider.GEMINI.defaultTextModel
        )
    }
    suspend fun setSelectedTextAIModel(model: String) {
        ds.edit { it[Keys.SELECTED_TEXT_AI_MODEL] = AIProvider.normalizeModelId(model) }
    }

    /** Upgrade removed AI model presets exactly once, including the fallback model. */
    suspend fun migrateAIModelSelections() {
        ds.edit { prefs ->
            if ((prefs[Keys.GEMINI_MODEL_MIGRATION_VERSION] ?: 0) < 1) {
                val primaryProvider = storedAIProvider(
                    prefs[Keys.SELECTED_AI_PROVIDER]
                ) ?: AIProvider.GEMINI
                if (primaryProvider == AIProvider.GEMINI) {
                    AIProvider.upgradedLegacyGeminiModel(prefs[Keys.SELECTED_AI_MODEL])?.let {
                        prefs[Keys.SELECTED_AI_MODEL] = it
                    }
                }

                val fallbackProvider = storedAIProvider(prefs[Keys.FALLBACK_PROVIDER])
                if (fallbackProvider == AIProvider.GEMINI) {
                    AIProvider.upgradedLegacyGeminiModel(prefs[Keys.FALLBACK_MODEL])?.let {
                        prefs[Keys.FALLBACK_MODEL] = it
                    }
                }

                // Prevent a later manual choice of a supported older model from
                // being overwritten on every app launch.
                prefs[Keys.GEMINI_MODEL_MIGRATION_VERSION] = 1
            }

            if ((prefs[Keys.AI_MODEL_REGISTRY_MIGRATION_VERSION] ?: 0) < 2) {
                val primaryProvider = storedAIProvider(
                    prefs[Keys.SELECTED_AI_PROVIDER]
                ) ?: AIProvider.GEMINI
                AIProvider.upgradedLegacyModel(
                    primaryProvider,
                    prefs[Keys.SELECTED_AI_MODEL]
                )?.let { prefs[Keys.SELECTED_AI_MODEL] = it }

                storedAIProvider(prefs[Keys.FALLBACK_PROVIDER])?.let { fallbackProvider ->
                    AIProvider.upgradedLegacyModel(
                        fallbackProvider,
                        prefs[Keys.FALLBACK_MODEL]
                    )?.let { prefs[Keys.FALLBACK_MODEL] = it }
                }

                prefs[Keys.AI_MODEL_REGISTRY_MIGRATION_VERSION] = 2
            }
        }
    }

    /**
     * One-time v7 migration. Existing users who still use Native STT move to the
     * first-party speech provider matching Primary AI. Explicit cloud choices are
     * preserved. Incomplete onboarding is intentionally left unmarked so its final
     * step can establish the new-user default.
     */
    suspend fun migrateMatchingSpeechProviderIfNeeded() {
        ds.edit { prefs ->
            if ((prefs[Keys.MATCHING_SPEECH_PROVIDER_MIGRATION_VERSION] ?: 0)
                >= MATCHING_SPEECH_PROVIDER_MIGRATION_VERSION ||
                prefs[Keys.ONBOARDING_COMPLETED] != true
            ) {
                return@edit
            }

            val currentSpeech = storedSpeechProvider(prefs[Keys.SELECTED_SPEECH_PROVIDER])
                ?: SpeechProvider.NATIVE
            val primaryAI = storedAIProvider(prefs[Keys.SELECTED_AI_PROVIDER])
                ?: AIProvider.GEMINI
            val migratedSpeech = SpeechProvider.migratedV7Selection(primaryAI, currentSpeech)
            if (migratedSpeech != currentSpeech) {
                migratedSpeech.let { matched ->
                    prefs[Keys.SELECTED_SPEECH_PROVIDER] = matched.name
                    val fallback = storedSpeechProvider(prefs[Keys.SPEECH_FALLBACK_PROVIDER])
                        ?: SpeechProvider.GROQ
                    if (fallback == matched) {
                        SpeechProvider.remoteProviders.firstOrNull { it != matched }?.let { alternate ->
                            prefs[Keys.SPEECH_FALLBACK_PROVIDER] = alternate.name
                        }
                    }
                }
            }

            prefs[Keys.MATCHING_SPEECH_PROVIDER_MIGRATION_VERSION] =
                MATCHING_SPEECH_PROVIDER_MIGRATION_VERSION
        }
    }

    /**
     * Removes persisted local routes whose verified artifact cannot execute on this device.
     * Provider flows apply the same guard synchronously, so no caller can observe a stale local
     * route while this small DataStore edit is still starting on a cold launch.
     */
    suspend fun reconcileLocalModelSelections() {
        val gemmaExecutable = isLocalGemmaExecutable()
        val whisperExecutable = isLocalWhisperExecutable()
        ds.edit { prefs ->
            if (!gemmaExecutable) {
                if (prefs[Keys.SELECTED_AI_PROVIDER] == AIProvider.LOCAL_GEMMA.name) {
                    prefs[Keys.SELECTED_AI_PROVIDER] = AIProvider.GEMINI.name
                    prefs[Keys.SELECTED_AI_MODEL] = AIProvider.GEMINI.defaultModel
                }
                if (prefs[Keys.SELECTED_TEXT_AI_PROVIDER] == AIProvider.LOCAL_GEMMA.name) {
                    prefs[Keys.SELECTED_TEXT_AI_PROVIDER] = AIProvider.GEMINI.name
                    prefs[Keys.SELECTED_TEXT_AI_MODEL] = AIProvider.GEMINI.defaultTextModel
                }
                if (prefs[Keys.FALLBACK_PROVIDER] == AIProvider.LOCAL_GEMMA.name) {
                    prefs[Keys.FALLBACK_ENABLED] = false
                    prefs[Keys.FALLBACK_PROVIDER] = AIProvider.GEMINI.name
                    prefs[Keys.FALLBACK_MODEL] = AIProvider.GEMINI.defaultModel
                }
                if (prefs[Keys.TEXT_FALLBACK_PROVIDER] == AIProvider.LOCAL_GEMMA.name) {
                    prefs[Keys.TEXT_FALLBACK_ENABLED] = false
                    prefs[Keys.TEXT_FALLBACK_PROVIDER] = AIProvider.GEMINI.name
                    prefs[Keys.TEXT_FALLBACK_MODEL] = AIProvider.GEMINI.defaultTextModel
                }
            }
            if (!whisperExecutable) {
                if (prefs[Keys.SELECTED_SPEECH_PROVIDER] == SpeechProvider.LOCAL_WHISPER.name) {
                    prefs[Keys.SELECTED_SPEECH_PROVIDER] = SpeechProvider.NATIVE.name
                }
                if (prefs[Keys.SPEECH_FALLBACK_PROVIDER] == SpeechProvider.LOCAL_WHISPER.name) {
                    prefs[Keys.SPEECH_FALLBACK_ENABLED] = false
                    prefs[Keys.SPEECH_FALLBACK_PROVIDER] = SpeechProvider.GROQ.name
                }
            }
        }
    }

    /** New-user default applied exactly once when onboarding completes. */
    suspend fun setInitialSpeechProviderForAIProvider(provider: AIProvider) {
        ds.edit { prefs ->
            val speech = SpeechProvider.matchingPrimaryAIProvider(provider) ?: SpeechProvider.NATIVE
            prefs[Keys.SELECTED_SPEECH_PROVIDER] = speech.name
            val fallback = storedSpeechProvider(prefs[Keys.SPEECH_FALLBACK_PROVIDER])
                ?: SpeechProvider.GROQ
            if (fallback == speech) {
                SpeechProvider.remoteProviders.firstOrNull { it != speech }?.let { alternate ->
                    prefs[Keys.SPEECH_FALLBACK_PROVIDER] = alternate.name
                }
            }
            prefs[Keys.MATCHING_SPEECH_PROVIDER_MIGRATION_VERSION] =
                MATCHING_SPEECH_PROVIDER_MIGRATION_VERSION
        }
    }

    private fun storedAIProvider(rawValue: String?): AIProvider? =
        AIProvider.values().firstOrNull { it.name == rawValue }

    private fun storedSpeechProvider(rawValue: String?): SpeechProvider? =
        SpeechProvider.values().firstOrNull { it.name == rawValue }

    fun customBaseUrl(provider: AIProvider): Flow<String?> = ds.data.map {
        it[stringPreferencesKey(CUSTOM_BASE_URL_PREFIX + provider.name)]
    }

    suspend fun setCustomBaseUrl(provider: AIProvider, url: String?) {
        val key = stringPreferencesKey(CUSTOM_BASE_URL_PREFIX + provider.name)
        ds.edit {
            if (url.isNullOrEmpty()) it.remove(key) else it[key] = url
        }
    }

    /** AI output-token cap sent with every request. Default 1024; raise it for local
     *  models whose replies get truncated. */
    val openRouterReasoningEffort: Flow<OpenRouterReasoningEffort> = ds.data.map {
        OpenRouterReasoningEffort.fromValue(it[Keys.OPENROUTER_REASONING_EFFORT])
    }
    suspend fun setOpenRouterReasoningEffort(value: OpenRouterReasoningEffort) {
        ds.edit { it[Keys.OPENROUTER_REASONING_EFFORT] = value.value }
    }

    val maxResponseTokens: Flow<Int> = ds.data.map { it[Keys.MAX_RESPONSE_TOKENS] ?: 1024 }
    suspend fun setMaxResponseTokens(v: Int) { ds.edit { it[Keys.MAX_RESPONSE_TOKENS] = v.coerceAtLeast(1) } }

    /** Timeout for local/custom AI endpoints. Cloud providers retain the standard client timeout. */
    val aiRequestTimeoutSeconds: Flow<Int> = ds.data.map {
        AIProvider.normalizedRequestTimeoutSeconds(
            it[Keys.AI_REQUEST_TIMEOUT_SECONDS] ?: AIProvider.DEFAULT_REQUEST_TIMEOUT_SECONDS
        )
    }
    suspend fun setAiRequestTimeoutSeconds(value: Int) {
        ds.edit {
            it[Keys.AI_REQUEST_TIMEOUT_SECONDS] = AIProvider.normalizedRequestTimeoutSeconds(value)
        }
    }

    // -- Custom AI Instructions ------------------------------------------
    /** Free-form text appended to every AI request. Empty = disabled. */
    val userContext: Flow<String> = ds.data.map { it[Keys.USER_CONTEXT].orEmpty() }
    suspend fun setUserContext(value: String) {
        val trimmed = value.trim()
        ds.edit {
            if (trimmed.isEmpty()) it.remove(Keys.USER_CONTEXT) else it[Keys.USER_CONTEXT] = trimmed
        }
    }

    // -- Recalculate nudge -----------------------------------------------
    // Fingerprint of the goal inputs at the last Recalculate. When it differs from the current
    // profile, Settings shows a soft "recalculate suggested" hint. null = no baseline yet.
    val lastRecalcGoalSignature: Flow<String?> = ds.data.map { it[Keys.LAST_RECALC_GOAL_SIGNATURE] }
    suspend fun setLastRecalcGoalSignature(value: String) {
        ds.edit { it[Keys.LAST_RECALC_GOAL_SIGNATURE] = value }
    }

    // -- Image AI fallback ------------------------------------------------
    // Keep the original storage keys so existing users retain their configured fallback.
    val fallbackEnabled: Flow<Boolean> = ds.data.map {
        (it[Keys.FALLBACK_ENABLED] ?: false) &&
            (it[Keys.FALLBACK_PROVIDER] != AIProvider.LOCAL_GEMMA.name || isLocalGemmaExecutable())
    }
    suspend fun setFallbackEnabled(v: Boolean) { ds.edit { it[Keys.FALLBACK_ENABLED] = v } }

    val selectedFallbackProvider: Flow<AIProvider> = ds.data.map {
        val raw = it[Keys.FALLBACK_PROVIDER]
        executableAIProviderOrDefault(
            AIProvider.visionProviders.firstOrNull { p -> p.name == raw },
            isLocalGemmaExecutable()
        )
    }
    suspend fun setSelectedFallbackProvider(p: AIProvider) {
        val resolved = executableAIProviderOrDefault(
            p.takeIf { it.supportsVision },
            isLocalGemmaExecutable()
        )
        ds.edit { it[Keys.FALLBACK_PROVIDER] = resolved.name }
    }

    val selectedFallbackModel: Flow<String?> = ds.data.map {
        executableAIModelOrDefault(
            storedAIProvider(it[Keys.FALLBACK_PROVIDER]),
            it[Keys.FALLBACK_MODEL],
            isLocalGemmaExecutable(),
            AIProvider.GEMINI.defaultModel
        )
    }
    suspend fun setSelectedFallbackModel(model: String) {
        ds.edit { it[Keys.FALLBACK_MODEL] = AIProvider.normalizeModelId(model) }
    }

    // -- Text AI fallback -------------------------------------------------
    val textFallbackEnabled: Flow<Boolean> = ds.data.map {
        (it[Keys.TEXT_FALLBACK_ENABLED] ?: false) &&
            (it[Keys.TEXT_FALLBACK_PROVIDER] != AIProvider.LOCAL_GEMMA.name || isLocalGemmaExecutable())
    }
    suspend fun setTextFallbackEnabled(v: Boolean) { ds.edit { it[Keys.TEXT_FALLBACK_ENABLED] = v } }

    val selectedTextFallbackProvider: Flow<AIProvider> = ds.data.map {
        val raw = it[Keys.TEXT_FALLBACK_PROVIDER]
        executableAIProviderOrDefault(
            AIProvider.textProviders.firstOrNull { provider -> provider.name == raw },
            isLocalGemmaExecutable()
        )
    }
    suspend fun setSelectedTextFallbackProvider(provider: AIProvider) {
        val resolved = executableAIProviderOrDefault(
            provider.takeIf { it in AIProvider.textProviders },
            isLocalGemmaExecutable()
        )
        ds.edit { it[Keys.TEXT_FALLBACK_PROVIDER] = resolved.name }
    }

    val selectedTextFallbackModel: Flow<String?> = ds.data.map {
        executableAIModelOrDefault(
            storedAIProvider(it[Keys.TEXT_FALLBACK_PROVIDER]),
            it[Keys.TEXT_FALLBACK_MODEL],
            isLocalGemmaExecutable(),
            AIProvider.GEMINI.defaultTextModel
        )
    }
    suspend fun setSelectedTextFallbackModel(model: String) {
        ds.edit { it[Keys.TEXT_FALLBACK_MODEL] = AIProvider.normalizeModelId(model) }
    }

    // -- Speech Provider selection ---------------------------------------
    val selectedSpeechProvider: Flow<SpeechProvider> = ds.data.map {
        val raw = it[Keys.SELECTED_SPEECH_PROVIDER]
        executableSpeechProviderOrDefault(
            SpeechProvider.values().firstOrNull { p -> p.name == raw },
            isLocalWhisperExecutable(),
            SpeechProvider.NATIVE
        )
    }
    suspend fun setSelectedSpeechProvider(p: SpeechProvider) {
        val resolved = executableSpeechProviderOrDefault(
            p,
            isLocalWhisperExecutable(),
            SpeechProvider.NATIVE
        )
        ds.edit { it[Keys.SELECTED_SPEECH_PROVIDER] = resolved.name }
    }

    // -- Speech-to-text fallback -----------------------------------------
    val speechFallbackEnabled: Flow<Boolean> = ds.data.map {
        (it[Keys.SPEECH_FALLBACK_ENABLED] ?: false) &&
            (it[Keys.SPEECH_FALLBACK_PROVIDER] != SpeechProvider.LOCAL_WHISPER.name ||
                isLocalWhisperExecutable())
    }
    suspend fun setSpeechFallbackEnabled(v: Boolean) { ds.edit { it[Keys.SPEECH_FALLBACK_ENABLED] = v } }

    val selectedSpeechFallbackProvider: Flow<SpeechProvider> = ds.data.map {
        val raw = it[Keys.SPEECH_FALLBACK_PROVIDER]
        executableSpeechProviderOrDefault(
            SpeechProvider.recordedFallbackProviders.firstOrNull { provider -> provider.name == raw },
            isLocalWhisperExecutable(),
            SpeechProvider.GROQ
        )
    }
    suspend fun setSelectedSpeechFallbackProvider(provider: SpeechProvider) {
        val resolved = executableSpeechProviderOrDefault(
            provider.takeIf { it in SpeechProvider.recordedFallbackProviders },
            isLocalWhisperExecutable(),
            SpeechProvider.GROQ
        )
        ds.edit { it[Keys.SPEECH_FALLBACK_PROVIDER] = resolved.name }
    }

    fun selectedSpeechLanguage(provider: SpeechProvider): Flow<SpeechLanguage> = ds.data.map {
        val raw = it[Keys.selectedSpeechLanguage(provider)]
        SpeechLanguage.values().firstOrNull { language -> language.name == raw }
            ?: SpeechLanguage.defaultFor(provider)
    }

    suspend fun setSelectedSpeechLanguage(provider: SpeechProvider, language: SpeechLanguage) {
        ds.edit { it[Keys.selectedSpeechLanguage(provider)] = language.name }
    }

    // -- Food entries -----------------------------------------------------
    val foodEntries: Flow<List<FoodEntry>> = ds.data.map { prefs ->
        prefs[Keys.FOOD_ENTRIES]?.let {
            runCatching { json.decodeFromString(ListSerializer(FoodEntry.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setFoodEntries(entries: List<FoodEntry>) {
        ds.edit { it[Keys.FOOD_ENTRIES] = json.encodeToString(ListSerializer(FoodEntry.serializer()), entries) }
    }

    // Keep this ledger after local deletion so repeat imports never resurrect removed meals.
    private val nutritionImportLedgerKey = stringPreferencesKey("healthNutritionImportedKeys")
    val nutritionImportedKeys: Flow<Set<String>> = ds.data.map {
        it[nutritionImportLedgerKey]?.let { raw -> json.decodeFromString<Set<String>>(raw) } ?: emptySet()
    }

    /** Commit the log and import ledger together, checking duplicates again after preview. */
    suspend fun importHealthNutrition(
        records: List<com.apoorvdarshan.calorietracker.services.health.ExternalNutrition>,
        fallbackName: String
    ): Int {
        var count = 0
        ds.edit { stored ->
            val current = stored[Keys.FOOD_ENTRIES]?.let { json.decodeFromString<List<FoodEntry>>(it) }
                ?: emptyList()
            val ledger = stored[nutritionImportLedgerKey]?.let { json.decodeFromString<Set<String>>(it) }
                ?: emptySet()
            val merged = mergeNutritionImport(current, ledger, records, context.packageName, fallbackName)
            stored[Keys.FOOD_ENTRIES] = json.encodeToString(ListSerializer(FoodEntry.serializer()), merged.entries)
            stored[nutritionImportLedgerKey] = json.encodeToString(SetSerializer(String.serializer()), merged.ledger)
            count = merged.added
        }
        return count
    }

    val favoriteKeys: Flow<Set<String>> = ds.data.map { prefs ->
        prefs[Keys.FAVORITE_KEYS]?.let {
            runCatching { json.decodeFromString(SetSerializer(String.serializer()), it) }.getOrNull()
        } ?: emptySet()
    }

    suspend fun setFavoriteKeys(keys: Set<String>) {
        ds.edit { it[Keys.FAVORITE_KEYS] = json.encodeToString(SetSerializer(String.serializer()), keys) }
    }

    /**
     * Ordered list of favorite FoodEntry copies — mirrors iOS UserDefaults
     * key "favoriteFoodEntries". Stored as a separate copy (not a reference
     * into [foodEntries]) so a favorite survives deletion of the original
     * log entry, AND so user-defined order is preserved across restarts.
     */
    val favoriteFoodEntries: Flow<List<FoodEntry>> = ds.data.map { prefs ->
        prefs[Keys.FAVORITE_ENTRIES]?.let {
            runCatching { json.decodeFromString(ListSerializer(FoodEntry.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setFavoriteFoodEntries(entries: List<FoodEntry>) {
        ds.edit { it[Keys.FAVORITE_ENTRIES] = json.encodeToString(ListSerializer(FoodEntry.serializer()), entries) }
    }

    /**
     * Returns an atomic snapshot of every persisted food-image reference. A
     * decode failure returns null so cleanup never treats unreadable user data
     * as an empty list and deletes its files.
     */
    suspend fun foodImageReferenceFilenames(): Set<String>? {
        val prefs = ds.data.first()
        val foods = prefs[Keys.FOOD_ENTRIES]?.let { raw ->
            runCatching {
                json.decodeFromString(ListSerializer(FoodEntry.serializer()), raw)
            }.getOrNull() ?: return null
        }.orEmpty()
        val favorites = prefs[Keys.FAVORITE_ENTRIES]?.let { raw ->
            runCatching {
                json.decodeFromString(ListSerializer(FoodEntry.serializer()), raw)
            }.getOrNull() ?: return null
        }.orEmpty()
        val draft = prefs[Keys.PENDING_FOOD_ANALYSIS_DRAFT]?.let { raw ->
            runCatching { json.decodeFromString<PendingFoodAnalysisDraft>(raw) }.getOrNull()
                ?: return null
        }

        return buildSet {
            foods.forEach { addAll(it.allImageFilenames) }
            favorites.forEach { addAll(it.allImageFilenames) }
            draft?.imageFilename?.let { add(it) }
            draft?.additionalImageFilenames?.let { addAll(it) }
        }
    }

    // -- Pending food analysis draft --------------------------------------
    val pendingFoodAnalysisDraft: Flow<PendingFoodAnalysisDraft?> = ds.data.map { prefs ->
        prefs[Keys.PENDING_FOOD_ANALYSIS_DRAFT]?.let {
            runCatching { json.decodeFromString<PendingFoodAnalysisDraft>(it) }.getOrNull()
        }
    }

    suspend fun setPendingFoodAnalysisDraft(draft: PendingFoodAnalysisDraft?) {
        ds.edit {
            if (draft == null) {
                it.remove(Keys.PENDING_FOOD_ANALYSIS_DRAFT)
            } else {
                it[Keys.PENDING_FOOD_ANALYSIS_DRAFT] = json.encodeToString(PendingFoodAnalysisDraft.serializer(), draft)
            }
        }
    }

    // -- Weight entries ---------------------------------------------------
    val weightEntries: Flow<List<WeightEntry>> = ds.data.map { prefs ->
        prefs[Keys.WEIGHT_ENTRIES]?.let {
            runCatching { json.decodeFromString(ListSerializer(WeightEntry.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setWeightEntries(entries: List<WeightEntry>) {
        ds.edit { it[Keys.WEIGHT_ENTRIES] = json.encodeToString(ListSerializer(WeightEntry.serializer()), entries) }
    }

    // -- Body fat entries --------------------------------------------------
    val bodyFatEntries: Flow<List<BodyFatEntry>> = ds.data.map { prefs ->
        prefs[Keys.BODY_FAT_ENTRIES]?.let {
            runCatching { json.decodeFromString(ListSerializer(BodyFatEntry.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setBodyFatEntries(entries: List<BodyFatEntry>) {
        ds.edit { it[Keys.BODY_FAT_ENTRIES] = json.encodeToString(ListSerializer(BodyFatEntry.serializer()), entries) }
    }

    // -- Body measurement (circumference) entries --------------------------
    val bodyMeasurements: Flow<List<BodyMeasurement>> = ds.data.map { prefs ->
        prefs[Keys.BODY_MEASUREMENTS]?.let {
            runCatching { json.decodeFromString(ListSerializer(BodyMeasurement.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setBodyMeasurements(entries: List<BodyMeasurement>) {
        ds.edit { it[Keys.BODY_MEASUREMENTS] = json.encodeToString(ListSerializer(BodyMeasurement.serializer()), entries) }
    }

    // -- Coach chat history ----------------------------------------------
    val chatHistory: Flow<List<ChatMessage>> = ds.data.map { prefs ->
        prefs[Keys.CHAT_HISTORY]?.let {
            runCatching { json.decodeFromString(ListSerializer(ChatMessage.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setChatHistory(history: List<ChatMessage>) {
        ds.edit { it[Keys.CHAT_HISTORY] = json.encodeToString(ListSerializer(ChatMessage.serializer()), history) }
    }

    // -- Widget snapshot --------------------------------------------------
    val widgetSnapshot: Flow<WidgetSnapshot?> = ds.data.map { prefs ->
        prefs[Keys.WIDGET_SNAPSHOT]?.let {
            runCatching { json.decodeFromString<WidgetSnapshot>(it) }.getOrNull()
        }
    }

    suspend fun setWidgetSnapshot(snapshot: WidgetSnapshot) {
        ds.edit { it[Keys.WIDGET_SNAPSHOT] = json.encodeToString(WidgetSnapshot.serializer(), snapshot) }
    }

    suspend fun clearWidgetSnapshot() {
        ds.edit { it.remove(Keys.WIDGET_SNAPSHOT) }
    }

    // -- Wipe everything --------------------------------------------------
    suspend fun clearAll() {
        ds.edit { it.clear() }
    }

    private object Keys {
        val USER_PROFILE = stringPreferencesKey("userProfile")
        val LAST_RECALC_GOAL_SIGNATURE = stringPreferencesKey("lastRecalcGoalSignature")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("hasCompletedOnboarding")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notificationsEnabled")
        val STREAK_ENABLED = booleanPreferencesKey("streakReminderEnabled")
        val STREAK_HOUR = intPreferencesKey("streakReminderHour")
        val STREAK_MINUTE = intPreferencesKey("streakReminderMinute")
        val DAILY_ENABLED = booleanPreferencesKey("dailySummaryEnabled")
        val DAILY_HOUR = intPreferencesKey("dailySummaryHour")
        val DAILY_MINUTE = intPreferencesKey("dailySummaryMinute")
        val WEIGHT_REMINDER_ENABLED = booleanPreferencesKey("weightReminderEnabled")
        val BODY_FAT_REMINDER_ENABLED = booleanPreferencesKey("bodyFatReminderEnabled")
        val GOAL_REACHED_NOTIFICATIONS_ENABLED = booleanPreferencesKey("goalReachedNotificationsEnabled")
        val APP_UPDATE_NOTIFICATIONS_ENABLED = booleanPreferencesKey("appUpdateNotificationsEnabled")
        val WATER_TRACKING_ENABLED = booleanPreferencesKey("waterTrackingEnabled")
        val WATER_DAILY_GOAL_ML = intPreferencesKey("waterDailyGoalMl")
        val WATER_UNIT = stringPreferencesKey("waterUnit")
        val WATER_REMINDER_ENABLED = booleanPreferencesKey("waterReminderEnabled")
        val WATER_REMINDER_HOUR = intPreferencesKey("waterReminderHour")
        val WATER_REMINDER_MINUTE = intPreferencesKey("waterReminderMinute")
        val WATER_ENTRIES = stringPreferencesKey("waterEntries")
        val FASTING_TRACKING_ENABLED = booleanPreferencesKey("fastingTrackingEnabled")
        val FASTING_DEFAULT_GOAL_MINUTES = intPreferencesKey("fastingDefaultGoalMinutes")
        val FASTING_GOAL_NOTIFICATION_ENABLED = booleanPreferencesKey("fastingGoalNotificationEnabled")
        val FASTING_SESSIONS = stringPreferencesKey("fastingSessions")
        val LAST_NOTIFIED_UPDATE_VERSION = stringPreferencesKey("lastNotifiedUpdateVersion")
        val HEALTH_CONNECT_ENABLED = booleanPreferencesKey("healthConnectEnabled")
        val HEALTH_TYPES_VERSION = intPreferencesKey("healthTypesVersion")
        val HEALTH_CHANGES_TOKEN = stringPreferencesKey("healthChangesToken")
        val HEALTH_CHANGES_TOKEN_TYPES = stringPreferencesKey("healthChangesTokenTypes")
        val HEALTH_FOOD_RESTORE_DONE = booleanPreferencesKey("healthFoodRestoreDone")
        val HEALTH_ENERGY_GOALS_ENABLED = booleanPreferencesKey("healthEnergyGoalsEnabled")
        val HEALTH_ENERGY_GOALS_PREVIOUS_TARGETS = stringPreferencesKey("healthEnergyGoalsPreviousTargets")
        val HEALTH_ENERGY_GOALS_LAST_AUTO_REFRESH_DAY = stringPreferencesKey("healthEnergyGoalsLastAutoRefreshDay")
        val ADAPTIVE_GOALS_ENABLED = booleanPreferencesKey("adaptiveGoalsEnabled")
        val REVIEW_PROMPTED_AFTER_FIRST_LOG = booleanPreferencesKey("reviewPromptedAfterFirstLog")
        val ADAPTIVE_GOALS_PREVIOUS_TARGETS = stringPreferencesKey("adaptiveGoalsPreviousTargets")
        val ADAPTIVE_GOALS_LAST_CHECK_DAY = stringPreferencesKey("adaptiveGoalsLastCheckDay")
        val USE_METRIC = booleanPreferencesKey("useMetric")
        val HEIGHT_UNIT = stringPreferencesKey("heightUnit")
        val WEIGHT_UNIT = stringPreferencesKey("weightUnit")
        val PREFER_GRAMS_BY_DEFAULT = booleanPreferencesKey("foodMeasurementPreferGramsByDefault")
        val APPEARANCE_MODE = stringPreferencesKey("appearanceMode")
        val APP_THEME_COLOR = stringPreferencesKey("appThemeColor")
        val WEEK_STARTS_MONDAY = booleanPreferencesKey("weekStartsOnMonday")
        val QUICK_ACTION_1 = stringPreferencesKey("quickAction.slot1")
        val QUICK_ACTION_2 = stringPreferencesKey("quickAction.slot2")
        val QUICK_ACTION_3 = stringPreferencesKey("quickAction.slot3")
        val WORKOUT_STATE = stringPreferencesKey("workoutDiaryStateV1")
        val MEAL_BREAKFAST_START = intPreferencesKey("mealBreakfastStartMinutes")
        val MEAL_LUNCH_START = intPreferencesKey("mealLunchStartMinutes")
        val MEAL_DINNER_START = intPreferencesKey("mealDinnerStartMinutes")
        val MEAL_SNACK_START = intPreferencesKey("mealSnackStartMinutes")
        val LAST_SAVED_MEALS_SEGMENT = stringPreferencesKey("lastRecentsSegment")
        val FOOD_LOG_SORT_ORDER = stringPreferencesKey("foodLogSortOrder")
        val HOME_TOP_NUTRIENTS = stringPreferencesKey("homeTopNutrients")
        val OPTIONAL_NUTRIENT_GOALS = stringPreferencesKey("optionalNutrientGoals")
        val SELECTED_AI_PROVIDER = stringPreferencesKey("selectedAIProvider")
        val SELECTED_AI_MODEL = stringPreferencesKey("selectedAIModel")
        val SEPARATE_TEXT_PROVIDER_ENABLED = booleanPreferencesKey("separateTextProviderEnabled")
        val SELECTED_TEXT_AI_PROVIDER = stringPreferencesKey("selectedTextAIProvider")
        val SELECTED_TEXT_AI_MODEL = stringPreferencesKey("selectedTextAIModel")
        val GEMINI_MODEL_MIGRATION_VERSION = intPreferencesKey("geminiModelMigrationVersion")
        val AI_MODEL_REGISTRY_MIGRATION_VERSION = intPreferencesKey("aiModelRegistryMigrationVersion")
        val OPENROUTER_REASONING_EFFORT = stringPreferencesKey("openRouterReasoningEffort")
        val MAX_RESPONSE_TOKENS = intPreferencesKey("maxResponseTokens")
        val AI_REQUEST_TIMEOUT_SECONDS = intPreferencesKey("aiRequestTimeoutSeconds")
        val USER_CONTEXT = stringPreferencesKey("userContext")
        val FALLBACK_ENABLED = booleanPreferencesKey("aiFallbackEnabled")
        val FALLBACK_PROVIDER = stringPreferencesKey("selectedFallbackAIProvider")
        val FALLBACK_MODEL = stringPreferencesKey("selectedFallbackAIModel")
        val TEXT_FALLBACK_ENABLED = booleanPreferencesKey("textAIFallbackEnabled")
        val TEXT_FALLBACK_PROVIDER = stringPreferencesKey("selectedTextFallbackAIProvider")
        val TEXT_FALLBACK_MODEL = stringPreferencesKey("selectedTextFallbackAIModel")
        val SELECTED_SPEECH_PROVIDER = stringPreferencesKey("selectedSpeechProvider")
        val SPEECH_FALLBACK_ENABLED = booleanPreferencesKey("speechFallbackEnabled")
        val SPEECH_FALLBACK_PROVIDER = stringPreferencesKey("selectedSpeechFallbackProvider")
        val MATCHING_SPEECH_PROVIDER_MIGRATION_VERSION =
            intPreferencesKey("matchingSpeechProviderMigrationVersion")
        fun selectedSpeechLanguage(provider: SpeechProvider) =
            stringPreferencesKey("selectedSpeechLanguage_${provider.name}")
        val FOOD_ENTRIES = stringPreferencesKey("foodEntries")
        val FAVORITE_KEYS = stringPreferencesKey("favorites")
        val FAVORITE_ENTRIES = stringPreferencesKey("favoriteFoodEntries")
        val PENDING_FOOD_ANALYSIS_DRAFT = stringPreferencesKey("pendingFoodAnalysisDraft")
        val WEIGHT_ENTRIES = stringPreferencesKey("weightEntries")
        val BODY_FAT_ENTRIES = stringPreferencesKey("bodyFatEntries")
        val BODY_MEASUREMENTS = stringPreferencesKey("bodyMeasurements")
        val CHAT_HISTORY = stringPreferencesKey("coachChatHistory")
        val WIDGET_SNAPSHOT = stringPreferencesKey("widget_snapshot_v1")
    }

    companion object {
        private const val CUSTOM_BASE_URL_PREFIX = "customBaseURL_"
        private const val MATCHING_SPEECH_PROVIDER_MIGRATION_VERSION = 1
    }
}
