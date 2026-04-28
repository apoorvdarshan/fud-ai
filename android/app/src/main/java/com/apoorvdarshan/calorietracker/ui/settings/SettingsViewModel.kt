package com.apoorvdarshan.calorietracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WeightGoal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val selectedAI: AIProvider = AIProvider.GEMINI,
    val selectedModel: String = AIProvider.GEMINI.defaultModel,
    val selectedSpeech: SpeechProvider = SpeechProvider.NATIVE,
    val useMetric: Boolean = true,
    val profile: UserProfile? = null,
    val notificationsEnabled: Boolean = false,
    val healthConnectEnabled: Boolean = false,
    val apiKeyMasked: String = "",
    val speechApiKeyMasked: String = "",
    val appearanceMode: String = "system",
    val weekStartsOnMonday: Boolean = false,
    val userContext: String = "",
    val fallbackEnabled: Boolean = false,
    val fallbackProvider: AIProvider = AIProvider.GEMINI,
    val fallbackModel: String = AIProvider.GEMINI.defaultModel,
    val fallbackApiKeyMasked: String = ""
)

class SettingsViewModel(val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val provider = container.prefs.selectedAIProvider.first()
            val model = container.prefs.selectedAIModel.first() ?: provider.defaultModel
            val speech = container.prefs.selectedSpeechProvider.first()
            val useMetric = container.prefs.useMetric.first()
            val profile = container.profileRepository.current()
            val notif = container.prefs.notificationsEnabled.first()
            val hc = container.prefs.healthConnectEnabled.first()
            val masked = maskKey(container.keyStore.apiKey(provider))
            val speechMasked = maskKey(container.keyStore.speechApiKey(speech))
            val appearance = container.prefs.appearanceMode.first()
            val weekMon = container.prefs.weekStartsOnMonday.first()
            val userContext = container.prefs.userContext.first()
            val fbEnabled = container.prefs.fallbackEnabled.first()
            val fbProvider = container.prefs.selectedFallbackProvider.first()
            val fbModel = container.prefs.selectedFallbackModel.first() ?: fbProvider.defaultModel
            val fbMasked = maskKey(container.keyStore.apiKey(fbProvider))
            _ui.value = SettingsUiState(
                selectedAI = provider,
                selectedModel = model,
                selectedSpeech = speech,
                useMetric = useMetric,
                profile = profile,
                notificationsEnabled = notif,
                healthConnectEnabled = hc,
                apiKeyMasked = masked,
                speechApiKeyMasked = speechMasked,
                appearanceMode = appearance,
                weekStartsOnMonday = weekMon,
                userContext = userContext,
                fallbackEnabled = fbEnabled,
                fallbackProvider = fbProvider,
                fallbackModel = fbModel,
                fallbackApiKeyMasked = fbMasked
            )
        }
    }

    fun setUserContext(value: String) {
        viewModelScope.launch {
            container.prefs.setUserContext(value)
            _ui.value = _ui.value.copy(userContext = value.trim())
        }
    }

    fun setFallbackEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setFallbackEnabled(v)
            _ui.value = _ui.value.copy(fallbackEnabled = v)
        }
    }

    fun selectFallbackProvider(p: AIProvider) {
        viewModelScope.launch {
            container.prefs.setSelectedFallbackProvider(p)
            // Reset model to provider default if old model isn't in the new provider's list.
            val current = _ui.value.fallbackModel
            val newModel = if (p.supportsCustomModelName || p.models.contains(current)) current else p.defaultModel
            container.prefs.setSelectedFallbackModel(newModel)
            val masked = maskKey(container.keyStore.apiKey(p))
            _ui.value = _ui.value.copy(fallbackProvider = p, fallbackModel = newModel, fallbackApiKeyMasked = masked)
        }
    }

    fun selectFallbackModel(m: String) {
        viewModelScope.launch {
            container.prefs.setSelectedFallbackModel(m)
            _ui.value = _ui.value.copy(fallbackModel = m)
        }
    }

    fun setFallbackApiKey(raw: String) {
        viewModelScope.launch {
            val p = _ui.value.fallbackProvider
            container.keyStore.setApiKey(p, raw.takeIf { it.isNotBlank() })
            _ui.value = _ui.value.copy(fallbackApiKeyMasked = maskKey(raw.takeIf { it.isNotBlank() }))
        }
    }

    fun setAppearanceMode(mode: String) {
        viewModelScope.launch {
            container.prefs.setAppearanceMode(mode)
            _ui.value = _ui.value.copy(appearanceMode = mode)
        }
    }

    fun setWeekStartsOnMonday(monday: Boolean) {
        viewModelScope.launch {
            container.prefs.setWeekStartsOnMonday(monday)
            _ui.value = _ui.value.copy(weekStartsOnMonday = monday)
        }
    }

    fun selectProvider(p: AIProvider) {
        viewModelScope.launch {
            container.prefs.setSelectedAIProvider(p)
            container.prefs.setSelectedAIModel(p.defaultModel)
            val masked = maskKey(container.keyStore.apiKey(p))
            _ui.value = _ui.value.copy(selectedAI = p, selectedModel = p.defaultModel, apiKeyMasked = masked)
        }
    }

    fun selectModel(m: String) {
        viewModelScope.launch {
            container.prefs.setSelectedAIModel(m)
            _ui.value = _ui.value.copy(selectedModel = m)
        }
    }

    fun setApiKey(raw: String) {
        viewModelScope.launch {
            val p = _ui.value.selectedAI
            container.keyStore.setApiKey(p, raw.takeIf { it.isNotBlank() })
            _ui.value = _ui.value.copy(apiKeyMasked = maskKey(raw.takeIf { it.isNotBlank() }))
        }
    }

    fun selectSpeech(p: SpeechProvider) {
        viewModelScope.launch {
            container.prefs.setSelectedSpeechProvider(p)
            // Re-pull the masked key for the new provider so the API Key row
            // reflects whether the freshly selected provider has a key saved.
            val masked = maskKey(container.keyStore.speechApiKey(p))
            _ui.value = _ui.value.copy(selectedSpeech = p, speechApiKeyMasked = masked)
        }
    }

    fun setSpeechApiKey(raw: String) {
        viewModelScope.launch {
            val p = _ui.value.selectedSpeech
            container.keyStore.setSpeechApiKey(p, raw.takeIf { it.isNotBlank() })
            _ui.value = _ui.value.copy(speechApiKeyMasked = maskKey(raw.takeIf { it.isNotBlank() }))
        }
    }

    fun setUseMetric(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setUseMetric(v)
            _ui.value = _ui.value.copy(useMetric = v)
        }
    }

    fun setNotificationsEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setNotificationsEnabled(v)
            // Arm/disarm the weight-log reminder alongside the master toggle.
            // canPostNotifications() guards against scheduling alarms whose
            // posted notification would be silently dropped on API 33+ when
            // POST_NOTIFICATIONS hasn't been granted.
            if (v && container.notifications.canPostNotifications()) {
                container.notifications.scheduleWeightReminder()
                // Body-fat reminder only arms for users who've opted into
                // body-fat tracking — gated on profile.bodyFatPercentage so
                // we don't ping users who never entered one.
                val profile = container.profileRepository.current()
                if (profile?.bodyFatPercentage != null) {
                    container.notifications.scheduleBodyFatReminder()
                }
            } else {
                container.notifications.cancelWeightReminder()
                container.notifications.cancelBodyFatReminder()
            }
            _ui.value = _ui.value.copy(notificationsEnabled = v)
        }
    }

    fun setHealthConnectEnabled(v: Boolean) {
        viewModelScope.launch {
            container.prefs.setHealthConnectEnabled(v)
            _ui.value = _ui.value.copy(healthConnectEnabled = v)
        }
    }

    fun deleteAllData(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            container.prefs.clearAll()
            container.keyStore.clearAll()
            container.imageStore.clearAll()
            onComplete()
        }
    }

    fun clearFoodLog() {
        viewModelScope.launch {
            container.foodRepository.clear()
            container.imageStore.clearAll()
        }
    }

    fun recalculateGoals() {
        viewModelScope.launch {
            val current = container.profileRepository.current() ?: return@launch
            container.profileRepository.save(current.recalculatedFromFormulas())
            _ui.value = _ui.value.copy(profile = current.recalculatedFromFormulas())
        }
    }

    /**
     * Settings → Weight save: writes a WeightEntry (so the chart, Coach forecast,
     * and Health Connect sync see the change), clears goalWeightKg if the new
     * current weight makes the goal direction impossible, and recomputes calories
     * + macros from formulas (since BMR/TDEE depend on weight). Mirrors iOS
     * ContentView.swift `case .editWeight` which also calls resetCustomGoalsAndSave.
     */
    fun saveCurrentWeight(newKg: Double) {
        viewModelScope.launch {
            val current = container.profileRepository.current() ?: return@launch
            val gw = current.goalWeightKg
            val mismatch = gw != null && (
                (current.goal == WeightGoal.LOSE && gw >= newKg) ||
                (current.goal == WeightGoal.GAIN && gw <= newKg)
            )
            // WeightRepository.addEntry syncs profile.weightKg to the new value internally.
            container.weightRepository.addEntry(WeightEntry(weightKg = newKg))
            val refreshed = container.profileRepository.current() ?: return@launch
            val next = refreshed.copy(
                goalWeightKg = if (mismatch) null else refreshed.goalWeightKg
            ).recalculatedFromFormulas()
            container.profileRepository.save(next)
            _ui.value = _ui.value.copy(profile = next)
        }
    }

    fun updateProfile(update: (com.apoorvdarshan.calorietracker.models.UserProfile) -> com.apoorvdarshan.calorietracker.models.UserProfile) {
        viewModelScope.launch {
            val current = container.profileRepository.current() ?: return@launch
            val next = update(current)
            container.profileRepository.save(next)
            _ui.value = _ui.value.copy(profile = next)
        }
    }

    /**
     * Like [updateProfile] but also resets custom calories + macros to formula defaults.
     * Use this for changes to inputs that feed BMR/TDEE/protein formulas (gender, height,
     * body fat, activity level, goal, weekly change). Mirrors iOS resetCustomGoalsAndSave.
     */
    fun updateProfileAndRecompute(update: (com.apoorvdarshan.calorietracker.models.UserProfile) -> com.apoorvdarshan.calorietracker.models.UserProfile) {
        viewModelScope.launch {
            val current = container.profileRepository.current() ?: return@launch
            val next = update(current).recalculatedFromFormulas()
            container.profileRepository.save(next)
            _ui.value = _ui.value.copy(profile = next)
        }
    }

    fun setCustomBaseUrl(provider: AIProvider, url: String) {
        viewModelScope.launch {
            container.prefs.setCustomBaseUrl(provider, url.takeIf { it.isNotBlank() })
        }
    }

    private fun maskKey(key: String?): String =
        if (key.isNullOrBlank()) "" else key.take(4) + "..." + key.takeLast(4)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
