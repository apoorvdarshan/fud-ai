package com.apoorvdarshan.calorietracker.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WidgetSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

val Context.fudaiDataStore by preferencesDataStore(name = "fudai_prefs")

/**
 * Thin wrapper over DataStore Preferences for all app state except API keys
 * (which live in [KeyStore]). Exposes reactive Flows for reads and suspend
 * functions for writes. Complex values (profile, entries, history) are stored
 * as JSON strings via kotlinx.serialization.
 */
class PreferencesStore(private val context: Context) {

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
        ds.edit { it[Keys.ONBOARDING_COMPLETED] = value }
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

    // -- Health Connect ---------------------------------------------------
    val healthConnectEnabled: Flow<Boolean> = ds.data.map { it[Keys.HEALTH_CONNECT_ENABLED] ?: false }
    suspend fun setHealthConnectEnabled(v: Boolean) { ds.edit { it[Keys.HEALTH_CONNECT_ENABLED] = v } }

    val healthPermissionsVersion: Flow<Int> = ds.data.map { it[Keys.HEALTH_TYPES_VERSION] ?: 0 }
    suspend fun setHealthPermissionsVersion(v: Int) { ds.edit { it[Keys.HEALTH_TYPES_VERSION] = v } }

    // -- Units ------------------------------------------------------------
    val useMetric: Flow<Boolean> = ds.data.map { it[Keys.USE_METRIC] ?: true }
    suspend fun setUseMetric(v: Boolean) { ds.edit { it[Keys.USE_METRIC] = v } }

    /** "system" | "light" | "dark". Mirrors iOS @AppStorage("appearanceMode"). */
    val appearanceMode: Flow<String> = ds.data.map { it[Keys.APPEARANCE_MODE] ?: "system" }
    suspend fun setAppearanceMode(v: String) { ds.edit { it[Keys.APPEARANCE_MODE] = v } }

    /** false = Sunday, true = Monday. Mirrors iOS @AppStorage("weekStartsOnMonday"). */
    val weekStartsOnMonday: Flow<Boolean> = ds.data.map { it[Keys.WEEK_STARTS_MONDAY] ?: false }
    suspend fun setWeekStartsOnMonday(v: Boolean) { ds.edit { it[Keys.WEEK_STARTS_MONDAY] = v } }

    /** "RECENTS" | "FREQUENT" | "FAVORITES". Mirrors iOS @AppStorage("lastRecentsSegment"). */
    val lastSavedMealsSegment: Flow<String> = ds.data.map { it[Keys.LAST_SAVED_MEALS_SEGMENT] ?: "RECENTS" }
    suspend fun setLastSavedMealsSegment(v: String) { ds.edit { it[Keys.LAST_SAVED_MEALS_SEGMENT] = v } }

    // -- AI Provider selection --------------------------------------------
    val selectedAIProvider: Flow<AIProvider> = ds.data.map {
        val raw = it[Keys.SELECTED_AI_PROVIDER]
        AIProvider.values().firstOrNull { p -> p.name == raw } ?: AIProvider.GEMINI
    }
    suspend fun setSelectedAIProvider(p: AIProvider) {
        ds.edit { it[Keys.SELECTED_AI_PROVIDER] = p.name }
    }

    val selectedAIModel: Flow<String?> = ds.data.map { it[Keys.SELECTED_AI_MODEL] }
    suspend fun setSelectedAIModel(model: String) {
        ds.edit { it[Keys.SELECTED_AI_MODEL] = model }
    }

    fun customBaseUrl(provider: AIProvider): Flow<String?> = ds.data.map {
        it[stringPreferencesKey(CUSTOM_BASE_URL_PREFIX + provider.name)]
    }

    suspend fun setCustomBaseUrl(provider: AIProvider, url: String?) {
        val key = stringPreferencesKey(CUSTOM_BASE_URL_PREFIX + provider.name)
        ds.edit {
            if (url.isNullOrEmpty()) it.remove(key) else it[key] = url
        }
    }

    // -- Speech Provider selection ---------------------------------------
    val selectedSpeechProvider: Flow<SpeechProvider> = ds.data.map {
        val raw = it[Keys.SELECTED_SPEECH_PROVIDER]
        SpeechProvider.values().firstOrNull { p -> p.name == raw } ?: SpeechProvider.NATIVE
    }
    suspend fun setSelectedSpeechProvider(p: SpeechProvider) {
        ds.edit { it[Keys.SELECTED_SPEECH_PROVIDER] = p.name }
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

    // -- Test data backup (used by TestDataSeeder during dev seeding) -------
    val testSeedBackupJson: Flow<String?> = ds.data.map { it[Keys.TEST_SEED_BACKUP] }
    suspend fun setTestSeedBackupJson(json: String) {
        ds.edit { it[Keys.TEST_SEED_BACKUP] = json }
    }
    suspend fun clearTestSeedBackup() {
        ds.edit { it.remove(Keys.TEST_SEED_BACKUP) }
    }

    // -- Wipe everything --------------------------------------------------
    suspend fun clearAll() {
        ds.edit { it.clear() }
    }

    private object Keys {
        val USER_PROFILE = stringPreferencesKey("userProfile")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("hasCompletedOnboarding")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notificationsEnabled")
        val STREAK_ENABLED = booleanPreferencesKey("streakReminderEnabled")
        val STREAK_HOUR = intPreferencesKey("streakReminderHour")
        val STREAK_MINUTE = intPreferencesKey("streakReminderMinute")
        val DAILY_ENABLED = booleanPreferencesKey("dailySummaryEnabled")
        val DAILY_HOUR = intPreferencesKey("dailySummaryHour")
        val DAILY_MINUTE = intPreferencesKey("dailySummaryMinute")
        val HEALTH_CONNECT_ENABLED = booleanPreferencesKey("healthConnectEnabled")
        val HEALTH_TYPES_VERSION = intPreferencesKey("healthTypesVersion")
        val USE_METRIC = booleanPreferencesKey("useMetric")
        val APPEARANCE_MODE = stringPreferencesKey("appearanceMode")
        val WEEK_STARTS_MONDAY = booleanPreferencesKey("weekStartsOnMonday")
        val LAST_SAVED_MEALS_SEGMENT = stringPreferencesKey("lastRecentsSegment")
        val SELECTED_AI_PROVIDER = stringPreferencesKey("selectedAIProvider")
        val SELECTED_AI_MODEL = stringPreferencesKey("selectedAIModel")
        val SELECTED_SPEECH_PROVIDER = stringPreferencesKey("selectedSpeechProvider")
        val FOOD_ENTRIES = stringPreferencesKey("foodEntries")
        val FAVORITE_KEYS = stringPreferencesKey("favorites")
        val FAVORITE_ENTRIES = stringPreferencesKey("favoriteFoodEntries")
        val WEIGHT_ENTRIES = stringPreferencesKey("weightEntries")
        val BODY_FAT_ENTRIES = stringPreferencesKey("bodyFatEntries")
        val CHAT_HISTORY = stringPreferencesKey("coachChatHistory")
        val WIDGET_SNAPSHOT = stringPreferencesKey("widget_snapshot_v1")
        val TEST_SEED_BACKUP = stringPreferencesKey("test_seed_backup_v1")
    }

    companion object {
        private const val CUSTOM_BASE_URL_PREFIX = "customBaseURL_"
    }
}
