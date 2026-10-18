package com.apoorvdarshan.calorietracker.models

import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import kotlinx.serialization.Serializable

/** Optional product-label details returned by Open Food Facts for barcode logs. */
@Serializable
data class FoodProductMetadata(
    val barcode: String,
    val packageQuantity: String? = null,
    val ingredientsText: String? = null,
    val allergens: List<String> = emptyList(),
    val traces: List<String> = emptyList(),
    val nutriScore: String? = null,
    val novaGroup: Int? = null,
    val ecoScore: String? = null,
    val labels: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val imageUrl: String? = null,
    /**
     * Where the product came from when it was not Open Food Facts (#533): [USDA_FOOD_DATA_CENTRAL].
     * Stored as text so an unknown value from a newer app version still decodes. Mirrors iOS.
     */
    val dataSource: String? = null
) {
    /** Display name of [dataSource]; null for Open Food Facts and unknown values. */
    @get:StringRes
    val dataSourceNameRes: Int?
        get() = when (dataSource) {
            USDA_FOOD_DATA_CENTRAL -> R.string.usda_fooddata_central
            else -> null
        }

    val hasDisplayDetails: Boolean
        get() = barcode.isNotEmpty() ||
            packageQuantity != null ||
            ingredientsText != null ||
            allergens.isNotEmpty() ||
            traces.isNotEmpty() ||
            nutriScore != null ||
            novaGroup != null ||
            ecoScore != null ||
            labels.isNotEmpty() ||
            categories.isNotEmpty() ||
            dataSourceNameRes != null

    companion object {
        /** [dataSource] for products found in USDA FoodData Central; matches iOS. */
        const val USDA_FOOD_DATA_CENTRAL = "usdaFoodDataCentral"
    }
}
