package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.models.BarcodeLabelMemory
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.toReviewAnalysis
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis

/**
 * The barcode lookup order: Open Food Facts first, then the nutrition label the user saved for
 * that barcode (#485), then USDA FoodData Central when the user added their own key (#533).
 * Later sources are tried only when Open Food Facts has no product or no nutrition for the code.
 * Mirrors iOS BarcodeProductLookup.
 */
object BarcodeProductLookup {
    sealed interface Outcome {
        val analysis: FoodAnalysis

        data class Product(val result: OpenFoodFactsService.LookupResult) : Outcome {
            override val analysis: FoodAnalysis get() = result.analysis
        }

        data class SavedLabel(val entry: FoodEntry) : Outcome {
            override val analysis: FoodAnalysis get() = entry.toReviewAnalysis()
        }
    }

    /**
     * Throws the Open Food Facts [OpenFoodFactsService.LookupException] when no later source has
     * the product, except that a key FoodData Central refuses is reported as a
     * [FoodDataCentralService.LookupException] so it can be fixed. Every barcode flow passes the
     * diary, so saved labels always come before USDA.
     */
    suspend fun lookup(
        barcode: String,
        usdaApiKey: String?,
        savedLabelEntries: suspend () -> List<FoodEntry>,
        openFoodFacts: suspend (String) -> OpenFoodFactsService.LookupResult = {
            OpenFoodFactsService.lookupWithImage(it)
        },
        foodDataCentral: suspend (barcode: String, apiKey: String) -> FoodAnalysis? = { code, key ->
            FoodDataCentralService.lookup(code, key)
        }
    ): Outcome {
        val openFoodFactsError = try {
            return Outcome.Product(openFoodFacts(barcode))
        } catch (error: OpenFoodFactsService.LookupException) {
            if (!BarcodeLabelMemory.allowsFallback(error.failure)) throw error
            error
        }

        BarcodeLabelMemory.latestLabelEntry(barcode, savedLabelEntries())?.let { return Outcome.SavedLabel(it) }

        val apiKey = usdaApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: throw openFoodFactsError
        val usdaAnalysis = try {
            foodDataCentral(barcode, apiKey)
        } catch (error: FoodDataCentralService.LookupException) {
            if (error.failure == FoodDataCentralService.LookupFailure.INVALID_API_KEY) throw error
            null
        } ?: throw openFoodFactsError
        return Outcome.Product(OpenFoodFactsService.LookupResult(analysis = usdaAnalysis, productImageBytes = null))
    }
}
