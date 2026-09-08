package com.apoorvdarshan.calorietracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.toMealIngredient
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class IngredientIntakeState(
    val busy: Boolean = false,
    val result: MealIngredient? = null,
    val error: String? = null
)

/** Keeps an ingredient request alive without retaining callbacks to a recreated sheet. */
internal class IngredientIntakeViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(IngredientIntakeState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null

    fun analyze(
        imageBytes: ByteArray?,
        imageStore: FoodImageStore,
        failureMessage: String,
        block: suspend () -> FoodAnalysis
    ) {
        if (mutableState.value.busy || mutableState.value.result != null) return
        mutableState.value = IngredientIntakeState(busy = true)
        request = viewModelScope.launch {
            try {
                val ingredient = block().toMealIngredient()
                val filename = imageBytes?.let { imageStore.storeBytes(it, UUID.randomUUID()) }
                mutableState.value = IngredientIntakeState(result = ingredient.copy(imageFilename = filename))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = IngredientIntakeState(
                    error = error.message?.takeIf { it.isNotBlank() } ?: failureMessage
                )
            }
        }
    }

    fun consumeResult() {
        mutableState.value = mutableState.value.copy(result = null)
    }

    fun dismissError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun discard() {
        request?.cancel()
        request = null
        mutableState.value = IngredientIntakeState()
    }
}
