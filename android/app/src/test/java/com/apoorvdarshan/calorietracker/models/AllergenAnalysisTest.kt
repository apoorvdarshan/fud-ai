package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.assertEquals
import org.junit.Test

class AllergenAnalysisTest {
    @Test
    fun declaredAllergenWinsOverWeakerEvidence() {
        val entry = entry(
            metadata = FoodProductMetadata(
                barcode = "1",
                allergens = listOf("en:milk"),
                traces = listOf("en:nuts"),
                ingredientsText = "oats"
            )
        )

        val result = entry.allergenAnalysis(listOf("milk", "nuts"))

        assertEquals(AllergenAssessment.DECLARED_ALLERGEN_MATCH, result.assessment)
        assertEquals(listOf("milk"), result.matchedSensitivities)
    }

    @Test
    fun tracesAndIngredientNamesAreNotReportedAsSafe() {
        val traces = entry(FoodProductMetadata("1", traces = listOf("en:peanuts")))
            .allergenAnalysis(listOf("peanut"))
        assertEquals(AllergenAssessment.MAY_CONTAIN, traces.assessment)

        val ingredient = entry(ingredients = listOf(MealIngredient("Sesame seeds", 1.0, 1, 0.0, 0.0, 0.0)))
            .allergenAnalysis(listOf("sesame"))
        assertEquals(AllergenAssessment.POSSIBLE_ALLERGEN, ingredient.assessment)
    }

    @Test
    fun missingEvidenceIsUnableToAssess() {
        val result = entry().allergenAnalysis(listOf("milk"))
        assertEquals(AllergenAssessment.UNABLE_TO_ASSESS, result.assessment)
    }

    @Test
    fun profileSensitivitiesRoundTripThroughLocalJson() {
        val profile = UserProfile(allergenSensitivities = listOf("milk", "peanuts"))
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString<UserProfile>(
            json.encodeToString(UserProfile.serializer(), profile)
        )
        assertEquals(listOf("milk", "peanuts"), restored.allergenSensitivities)
    }

    private fun entry(
        metadata: FoodProductMetadata? = null,
        ingredients: List<MealIngredient> = emptyList()
    ) = FoodEntry(
        name = "Plain meal",
        calories = 100,
        protein = 1.0,
        carbs = 10.0,
        fat = 1.0,
        source = FoodSource.MANUAL,
        productMetadata = metadata,
        ingredients = ingredients
    )
}
