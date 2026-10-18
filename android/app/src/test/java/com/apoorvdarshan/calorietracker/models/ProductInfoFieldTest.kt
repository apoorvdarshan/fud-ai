package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductInfoFieldTest {
    @Test
    fun emptyStorageHidesNothing() {
        assertTrue(ProductInfoField.hiddenFromStorage(null).isEmpty())
        assertTrue(ProductInfoField.hiddenFromStorage("").isEmpty())
    }

    @Test
    fun hiddenFieldsRoundTripInDisplayOrderAndDropUnknownKeys() {
        val stored = ProductInfoField.storageValue(
            setOf(ProductInfoField.INGREDIENT_LABEL, ProductInfoField.BARCODE, ProductInfoField.TRACES)
        )
        assertEquals("barcode,traces,ingredientLabel", stored)
        assertEquals(
            setOf(ProductInfoField.BARCODE, ProductInfoField.TRACES, ProductInfoField.INGREDIENT_LABEL),
            ProductInfoField.hiddenFromStorage(stored)
        )
        assertEquals(
            setOf(ProductInfoField.LABELS, ProductInfoField.NUTRI_SCORE),
            ProductInfoField.hiddenFromStorage("labels, retiredField,,nutriScore")
        )
    }

    @Test
    fun storageKeysMatchIos() {
        assertEquals(
            listOf(
                "barcode", "source", "package", "nutriScore", "novaGroup", "ecoScore",
                "allergens", "traces", "labels", "categories", "ingredientLabel"
            ),
            ProductInfoField.entries.map { it.storageKey }
        )
    }

    @Test
    fun defaultsShowEveryFieldWithData() {
        assertEquals(
            ProductInfoField.entries.toList(),
            ProductInfoField.visibleFields(fullMetadata, shown = true, hidden = emptySet())
        )
    }

    @Test
    fun fieldsWithoutDataAreSkipped() {
        val metadata = FoodProductMetadata(barcode = "123", ingredientsText = "Oats, salt")
        assertEquals(
            listOf(ProductInfoField.BARCODE, ProductInfoField.INGREDIENT_LABEL),
            ProductInfoField.visibleFields(metadata, shown = true, hidden = emptySet())
        )
    }

    @Test
    fun hiddenFieldsAreDroppedAndOrderIsKept() {
        val fields = ProductInfoField.visibleFields(
            fullMetadata,
            shown = true,
            hidden = setOf(ProductInfoField.BARCODE, ProductInfoField.CATEGORIES, ProductInfoField.ECO_SCORE)
        )
        assertEquals(
            listOf(
                ProductInfoField.SOURCE, ProductInfoField.PACKAGE, ProductInfoField.NUTRI_SCORE, ProductInfoField.NOVA_GROUP,
                ProductInfoField.ALLERGENS, ProductInfoField.TRACES, ProductInfoField.LABELS,
                ProductInfoField.INGREDIENT_LABEL
            ),
            fields
        )
    }

    @Test
    fun turningTheBlockOffOrHidingEveryPresentFieldLeavesNothingToShow() {
        assertTrue(ProductInfoField.visibleFields(fullMetadata, shown = false, hidden = emptySet()).isEmpty())
        assertTrue(
            ProductInfoField.visibleFields(
                FoodProductMetadata(barcode = "123"),
                shown = true,
                hidden = setOf(ProductInfoField.BARCODE)
            ).isEmpty()
        )
    }

    @Test
    fun sourceShowsOnlyForUsdaProducts() {
        fun shows(dataSource: String?, hidden: Set<ProductInfoField> = emptySet()) =
            ProductInfoField.SOURCE in ProductInfoField.visibleFields(
                fullMetadata.copy(dataSource = dataSource),
                shown = true,
                hidden = hidden
            )
        assertFalse(shows(null))
        assertFalse(shows("someNewSource"))
        assertTrue(shows(FoodProductMetadata.USDA_FOOD_DATA_CENTRAL))
        assertFalse(shows(FoodProductMetadata.USDA_FOOD_DATA_CENTRAL, hidden = setOf(ProductInfoField.SOURCE)))
    }

    private val fullMetadata = FoodProductMetadata(
        barcode = "4006381333931",
        packageQuantity = "400 g",
        ingredientsText = "Sugar, _hazelnuts_ (13%), skimmed _milk_ powder",
        allergens = listOf("Milk", "Nuts"),
        traces = listOf("Gluten"),
        nutriScore = "E",
        novaGroup = 4,
        ecoScore = "D",
        labels = listOf("Vegetarian"),
        categories = listOf("Spreads"),
        dataSource = FoodProductMetadata.USDA_FOOD_DATA_CENTRAL
    )
}
