package com.apoorvdarshan.calorietracker.models

import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R

/**
 * Open Food Facts fields the barcode Product information block can show (#427), plus the
 * data source when a product came from USDA FoodData Central (#533).
 * [storageKey] is persisted (and matches iOS ProductInfoField raw values), so keep it stable.
 * Allergen check is deliberately not a field: it follows the user's allergen sensitivities
 * in its own section, so this setting can never hide it.
 */
enum class ProductInfoField(val storageKey: String, @StringRes val labelRes: Int) {
    BARCODE("barcode", R.string.product_barcode),
    SOURCE("source", R.string.product_source),
    PACKAGE("package", R.string.product_package),
    NUTRI_SCORE("nutriScore", R.string.product_nutri_score),
    NOVA_GROUP("novaGroup", R.string.product_nova_group),
    ECO_SCORE("ecoScore", R.string.product_eco_score),
    ALLERGENS("allergens", R.string.product_allergens),
    TRACES("traces", R.string.product_may_contain),
    LABELS("labels", R.string.product_labels),
    CATEGORIES("categories", R.string.product_categories),
    INGREDIENT_LABEL("ingredientLabel", R.string.product_ingredient_label);

    fun hasValue(metadata: FoodProductMetadata): Boolean = when (this) {
        BARCODE -> metadata.barcode.isNotEmpty()
        SOURCE -> metadata.dataSourceNameRes != null
        PACKAGE -> metadata.packageQuantity != null
        NUTRI_SCORE -> metadata.nutriScore != null
        NOVA_GROUP -> metadata.novaGroup != null
        ECO_SCORE -> metadata.ecoScore != null
        ALLERGENS -> metadata.allergens.isNotEmpty()
        TRACES -> metadata.traces.isNotEmpty()
        LABELS -> metadata.labels.isNotEmpty()
        CATEGORIES -> metadata.categories.isNotEmpty()
        INGREDIENT_LABEL -> metadata.ingredientsText != null
    }

    companion object {
        /** Decodes the comma-separated storage keys; unknown keys are ignored. */
        fun hiddenFromStorage(raw: String?): Set<ProductInfoField> {
            if (raw.isNullOrBlank()) return emptySet()
            val keys = raw.split(",").map { it.trim() }.toSet()
            return entries.filter { it.storageKey in keys }.toSet()
        }

        fun storageValue(hidden: Set<ProductInfoField>): String =
            entries.filter { it in hidden }.joinToString(",") { it.storageKey }

        /**
         * Fields to render, in display order. Empty when the block is turned off or every
         * field with data is hidden, so callers can skip the section header too.
         */
        fun visibleFields(
            metadata: FoodProductMetadata,
            shown: Boolean,
            hidden: Set<ProductInfoField>
        ): List<ProductInfoField> {
            if (!shown) return emptyList()
            return entries.filter { it !in hidden && it.hasValue(metadata) }
        }
    }
}
