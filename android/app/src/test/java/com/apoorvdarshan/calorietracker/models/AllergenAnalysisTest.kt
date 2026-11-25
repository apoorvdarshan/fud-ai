package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.services.ai.FoodJsonParser
import com.apoorvdarshan.calorietracker.ui.home.toFoodEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllergenAnalysisTest {
    @Test
    fun canonicalAiAllergensSurviveLoggingAndBackupWithoutBecomingDeclarations() {
        val analysis = FoodJsonParser.parseFood(
            """{"name":"Pokrm","calories":160,"protein":4,"carbs":27,"fat":4,"serving_size_grams":100,
               "allergens":["milk","MILK",17,"mléko","not-an-allergen"],
               "ingredients":[{"name":"Příloha","grams":100,"calories":160,"protein":4,"carbs":27,"fat":4,"allergens":["sesame"]}]}"""
        )
        assertEquals(listOf("milk"), analysis.aiAllergens)
        assertEquals(listOf("sesame"), analysis.ingredients.single().aiAllergens)
        val entry = analysis.toFoodEntry(source = FoodSource.TEXT_INPUT, mealType = MealType.OTHER)
        val restored = Json.decodeFromString<FoodEntry>(Json.encodeToString(entry))
        val result = restored.allergenAnalysis(listOf("mlékem", "芝麻"))
        assertEquals(AllergenAssessment.POSSIBLE_ALLERGEN, result.assessment)
        assertEquals(listOf("mlékem", "芝麻"), result.matchedSensitivities)
        assertEquals(AllergenAssessment.UNABLE_TO_ASSESS, restored.allergenAnalysis(listOf("بيض")).assessment)
    }

    @Test
    fun labelAllergenSurvivesServingScale() {
        val scaled = FoodJsonParser.parseLabel(
            """{"name":"Product","calories_per_100g":50,"protein_per_100g":3,"carbs_per_100g":4,"fat_per_100g":1,"allergens":["soy"]}"""
        ).scaled(60.0)
        assertEquals(listOf("soy"), scaled.aiAllergens)
    }
    @Test
    fun localizedNamesMatchCanonicalLabelWithoutLosingOriginalSensitivity() {
        val result = entry(FoodProductMetadata("1", allergens = listOf("en:milk")))
            .allergenAnalysis(listOf(" ", "mléko", "молоко"))
        assertEquals(AllergenAssessment.DECLARED_ALLERGEN_MATCH, result.assessment)
        assertEquals(listOf("mléko", "молоко"), result.matchedSensitivities)
    }
    @Test
    fun russianEggMatchesCanonicalLabelInBothDirections() {
        // Normalization folds й to и, so the alias table must use folded keys too.
        assertTrue(AllergenAnalysis.mentionsAny(listOf("яйцо"), "en:egg"))
        assertTrue(AllergenAnalysis.mentionsAny(listOf("egg"), "яйцо"))
        assertTrue(AllergenAnalysis.mentionsAny(listOf("яйцо"), "egg"))
    }

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
