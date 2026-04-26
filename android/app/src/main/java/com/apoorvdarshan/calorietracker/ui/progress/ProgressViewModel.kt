package com.apoorvdarshan.calorietracker.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

data class ProgressUiState(
    val entries: List<WeightEntry> = emptyList(),
    val bodyFatEntries: List<BodyFatEntry> = emptyList(),
    val profile: UserProfile? = null,
    val goalReached: Boolean = false
)

class ProgressViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ProgressUiState())
    val ui: StateFlow<ProgressUiState> = _ui.asStateFlow()

    init {
        combine(
            container.profileRepository.profile,
            container.weightRepository.entries,
            container.bodyFatRepository.entries
        ) { p, weights, bodyFats ->
            _ui.value.copy(profile = p, entries = weights, bodyFatEntries = bodyFats)
        }.onEach { _ui.value = it }.launchIn(viewModelScope)
    }

    fun addWeight(kg: Double) {
        viewModelScope.launch {
            val event = container.weightRepository.addEntry(WeightEntry(weightKg = kg))
            if (event != null) {
                _ui.value = _ui.value.copy(goalReached = true)
                container.notifications.showGoalReached()
            }
        }
    }

    fun deleteWeight(id: UUID) {
        viewModelScope.launch { container.weightRepository.deleteEntry(id) }
    }

    fun addBodyFat(fraction: Double) {
        viewModelScope.launch {
            container.bodyFatRepository.addEntry(BodyFatEntry(bodyFatFraction = fraction))
        }
    }

    fun deleteBodyFat(id: UUID) {
        viewModelScope.launch { container.bodyFatRepository.deleteEntry(id) }
    }

    fun dismissGoalReached() {
        _ui.value = _ui.value.copy(goalReached = false)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProgressViewModel(container) as T
    }
}
