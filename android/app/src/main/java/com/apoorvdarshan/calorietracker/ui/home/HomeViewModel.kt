package com.apoorvdarshan.calorietracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class HomeUiState(
    val date: LocalDate = LocalDate.now(),
    val profile: UserProfile? = null,
    val todayEntries: List<FoodEntry> = emptyList(),
    val favoriteKeys: Set<String> = emptySet(),
    val pendingAnalysis: FoodAnalysis? = null,
    val pendingImageBytes: ByteArray? = null,
    val analyzing: Boolean = false,
    val error: String? = null
) {
    val caloriesToday: Int get() = todayEntries.sumOf { it.calories }
    val proteinToday: Int get() = todayEntries.sumOf { it.protein }
    val carbsToday: Int get() = todayEntries.sumOf { it.carbs }
    val fatToday: Int get() = todayEntries.sumOf { it.fat }
    fun isFavorite(entry: FoodEntry): Boolean = entry.favoriteKey in favoriteKeys
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()
    private val _selectedDate = MutableStateFlow(LocalDate.now())

    init {
        combine(
            container.profileRepository.profile,
            container.foodRepository.entries,
            container.foodRepository.favoriteKeys,
            _selectedDate
        ) { p, entries, favKeys, day ->
            val zone = ZoneId.systemDefault()
            val dayEntries = entries
                .filter { it.timestamp.atZone(zone).toLocalDate() == day }
                .sortedByDescending { it.timestamp }
            _ui.value.copy(
                profile = p,
                date = day,
                todayEntries = dayEntries,
                favoriteKeys = favKeys
            )
        }
            .onEach { _ui.value = it }
            .launchIn(viewModelScope)
    }

    fun setSelectedDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun analyzeText(description: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(analyzing = true, error = null, pendingAnalysis = null, pendingImageBytes = null)
            try {
                val analysis = container.foodAnalysis.analyzeText(description)
                _ui.value = _ui.value.copy(analyzing = false, pendingAnalysis = analysis)
            } catch (e: AiError) {
                _ui.value = _ui.value.copy(analyzing = false, error = e.message)
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(analyzing = false, error = e.localizedMessage ?: "Analysis failed")
            }
        }
    }

    fun analyzePhoto(bytes: ByteArray) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(analyzing = true, error = null, pendingAnalysis = null, pendingImageBytes = bytes)
            try {
                val analysis = container.foodAnalysis.analyzeAuto(bytes)
                _ui.value = _ui.value.copy(analyzing = false, pendingAnalysis = analysis)
            } catch (e: AiError) {
                _ui.value = _ui.value.copy(analyzing = false, error = e.message)
            } catch (e: Throwable) {
                _ui.value = _ui.value.copy(analyzing = false, error = e.localizedMessage ?: "Analysis failed")
            }
        }
    }

    fun saveAnalysis(
        name: String? = null,
        servingGrams: Double? = null,
        scale: Double = 1.0,
        mealType: MealType = MealType.currentMeal
    ) {
        val analysis = _ui.value.pendingAnalysis ?: return
        viewModelScope.launch {
            val imageBytes = _ui.value.pendingImageBytes
            val id = UUID.randomUUID()
            val filename = imageBytes?.let { container.imageStore.storeBytes(it, id) }
            fun s(v: Int) = (v * scale).toInt()
            fun s(v: Double?) = v?.let { it * scale }
            val entry = FoodEntry(
                id = id,
                name = name?.takeIf { it.isNotBlank() } ?: analysis.name,
                calories = s(analysis.calories),
                protein = s(analysis.protein),
                carbs = s(analysis.carbs),
                fat = s(analysis.fat),
                timestamp = timestampForSelectedDay(),
                imageFilename = filename,
                emoji = analysis.emoji,
                source = if (imageBytes != null) FoodSource.SNAP_FOOD else FoodSource.TEXT_INPUT,
                mealType = mealType,
                sugar = s(analysis.sugar),
                fiber = s(analysis.fiber),
                saturatedFat = s(analysis.saturatedFat),
                monounsaturatedFat = s(analysis.monounsaturatedFat),
                polyunsaturatedFat = s(analysis.polyunsaturatedFat),
                cholesterol = s(analysis.cholesterol),
                sodium = s(analysis.sodium),
                potassium = s(analysis.potassium),
                servingSizeGrams = servingGrams ?: analysis.servingSizeGrams
            )
            container.foodRepository.addEntry(entry)
            _ui.value = _ui.value.copy(pendingAnalysis = null, pendingImageBytes = null)
        }
    }

    fun dismissPending() {
        _ui.value = _ui.value.copy(pendingAnalysis = null, pendingImageBytes = null, error = null)
    }

    fun deleteEntry(id: UUID) {
        viewModelScope.launch {
            container.foodRepository.deleteEntry(id)
        }
    }

    fun toggleFavorite(entry: FoodEntry) {
        viewModelScope.launch {
            container.foodRepository.toggleFavorite(entry)
        }
    }

    fun updateEntry(entry: FoodEntry) {
        viewModelScope.launch {
            container.foodRepository.updateEntry(entry)
        }
    }

    /** Re-log a saved meal (from Saved Meals sheet) as a new entry timestamped to the selected day. */
    fun relogMeal(template: FoodEntry) {
        viewModelScope.launch {
            container.foodRepository.addEntry(template.duplicatedForLogging(timestampForSelectedDay()))
        }
    }

    /**
     * Mirrors iOS `logDate: selectedDate` behavior. When viewing today, returns now.
     * When viewing a past or future day, combines that day with the current wall-clock
     * time so the entry shows a sensible time and lands on the correct calendar day.
     */
    private fun timestampForSelectedDay(): Instant {
        val day = _selectedDate.value
        val today = LocalDate.now()
        if (day == today) return Instant.now()
        val zone = ZoneId.systemDefault()
        val nowTime = java.time.LocalTime.now()
        return day.atTime(nowTime).atZone(zone).toInstant()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(container) as T
    }
}
