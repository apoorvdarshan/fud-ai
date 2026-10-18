package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.services.ai.NutritionLabelAnalysis
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AminoAcidTest {
    @Test
    fun validEstimatesAreKeyedByStorageKey() {
        val parsed = AminoAcidEstimates.parse(
            """
            {"name":"Chicken breast","protein":31,"serving_size_grams":100,"creatine":0.4,
             "histidine":1.2,"isoleucine":"1.6","leucine":2.5,"lysine":2.7,"methionine":0.9,
             "phenylalanine":1.2,"threonine":1.3,"tryptophan":0.35,"valine":1.5}
            """.trimIndent(),
            limitGrams = 100.0
        )

        assertEquals(AminoAcid.values().map { it.storageKey }.toSet(), parsed.keys)
        assertEquals(2.5, parsed.getValue("leucine"), 0.0)
        assertEquals(1.6, parsed.getValue("isoleucine"), 0.0)
        assertEquals(0.35, parsed.getValue("tryptophan"), 0.0)
        assertFalse("creatine" in parsed)
    }

    @Test
    fun invalidEstimatesAreDroppedInsteadOfFailing() {
        val parsed = AminoAcidEstimates.parse(
            """
            {"histidine":-0.5,"isoleucine":"lots","leucine":true,"lysine":null,"methionine":[0.2],
             "phenylalanine":300,"threonine":0.6,"tryptophan":0,"valine":"NaN"}
            """.trimIndent(),
            limitGrams = 250.0
        )

        assertEquals(mapOf("threonine" to 0.6, "tryptophan" to 0.0), parsed)
        assertEquals(emptyMap<String, Double>(), AminoAcidEstimates.parse("not json"))
        assertEquals(emptyMap<String, Double>(), AminoAcidEstimates.parse("""{"name":"Apple"}"""))
    }

    @Test
    fun labelEstimatesArePerHundredGramsAndScaleToTheServing() {
        val per100g = AminoAcidEstimates.parse(
            """{"leucine_per_100g":2.4,"lysine_per_100g":-1,"valine_per_100g":150,"leucine":9}""",
            keySuffix = "_per_100g",
            limitGrams = 100.0
        )
        assertEquals(mapOf("leucine" to 2.4), per100g)

        val serving = NutritionLabelAnalysis(
            name = "Protein bar",
            caloriesPer100g = 380.0,
            proteinPer100g = 30.0,
            carbsPer100g = 40.0,
            fatPer100g = 12.0,
            supplementalNutrientsPer100g = per100g
        ).scaled(250.0)
        assertEquals(6.0, serving.supplementalNutrients.getValue("leucine"), 0.0)
    }

    @Test
    fun servingChangesScaleAminoAcidsLikeOtherNutrients() {
        val doubled = MealMicronutrientSnapshot(
            fiber = 2.0,
            supplementalNutrients = mapOf("leucine" to 1.5, "lysine" to 0.8)
        ).stretched(150.0, 300.0)

        assertEquals(4.0, doubled.fiber!!, 0.0)
        assertEquals(3.0, doubled.supplementalNutrients.getValue("leucine"), 0.0)
        assertEquals(1.6, doubled.supplementalNutrients.getValue("lysine"), 0.0)
    }

    @Test
    fun reviewAndEditSavesKeepSmallAminoAcidEstimates() {
        // A medium banana: these all rounded to 0.0 or 0.1 when every extra used 0.1 g.
        val analyzed = mapOf(
            "tryptophan" to 0.04, "methionine" to 0.011, "histidine" to 0.09, "valine" to 0.0564, "creatine" to 0.44
        )

        val saved = AminoAcidEstimates.roundedSupplementalNutrients(analyzed)
        assertEquals(0.04, saved.getValue("tryptophan"), 0.0)
        assertEquals(0.011, saved.getValue("methionine"), 0.0)
        assertEquals(0.09, saved.getValue("histidine"), 0.0)
        assertEquals(0.056, saved.getValue("valine"), 0.0)
        assertEquals(0.4, saved.getValue("creatine"), 0.0)

        val halfServing = AminoAcidEstimates.roundedSupplementalNutrients(analyzed, factor = 0.5)
        assertEquals(0.02, halfServing.getValue("tryptophan"), 0.0)
        assertEquals(0.028, halfServing.getValue("valine"), 0.0)
        assertEquals(0.2, halfServing.getValue("creatine"), 0.0)
        assertEquals(0.02, AminoAcidEstimates.roundedSupplementalNutrient("tryptophan", 0.04 * 0.5), 0.0)
    }

    @Test
    fun labelServingsKeepSmallAminoAcidEstimates() {
        val serving = NutritionLabelAnalysis(
            name = "Banana chips",
            caloriesPer100g = 520.0,
            proteinPer100g = 2.3,
            carbsPer100g = 58.0,
            fatPer100g = 34.0,
            supplementalNutrientsPer100g = mapOf("tryptophan" to 0.011, "creatine" to 0.44)
        ).scaled(118.0)

        assertEquals(0.013, serving.supplementalNutrients.getValue("tryptophan"), 0.0)
        assertEquals(0.5, serving.supplementalNutrients.getValue("creatine"), 0.0)
    }

    @Test
    fun entriesWithoutAminoAcidsDecodeAndNewOnesRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val legacy = json.encodeToString(
            FoodEntry.serializer(),
            FoodEntry(name = "Toast", calories = 80, protein = 3.0, carbs = 15.0, fat = 1.0, source = FoodSource.MANUAL)
        )
        assertFalse(legacy.contains("supplementalNutrients"))
        assertTrue(AminoAcidEstimates.dailyTotals(listOf(json.decodeFromString(FoodEntry.serializer(), legacy))).isEmpty())

        val entry = FoodEntry(
            name = "Eggs", calories = 140, protein = 12.0, carbs = 1.0, fat = 10.0, source = FoodSource.SNAP_FOOD,
            supplementalNutrients = mapOf("leucine" to 1.1, "creatine" to 0.1)
        )
        val decoded = json.decodeFromString(FoodEntry.serializer(), json.encodeToString(FoodEntry.serializer(), entry))
        assertEquals(entry.supplementalNutrients, decoded.supplementalNutrients)
    }

    @Test
    fun dailyTotalsSumOnlyEntriesThatCarryEstimates() {
        val entries = listOf(
            FoodEntry(
                name = "Eggs", calories = 140, protein = 12.0, carbs = 1.0, fat = 10.0, source = FoodSource.SNAP_FOOD,
                supplementalNutrients = mapOf("leucine" to 1.1, "lysine" to 0.9)
            ),
            FoodEntry(
                name = "Chicken", calories = 165, protein = 31.0, carbs = 0.0, fat = 3.6, source = FoodSource.TEXT_INPUT,
                supplementalNutrients = mapOf("leucine" to 2.4, "creatine" to 0.4)
            ),
            FoodEntry(name = "Barcode bar", calories = 200, protein = 20.0, carbs = 20.0, fat = 7.0, source = FoodSource.BARCODE)
        )
        val totals = AminoAcidEstimates.dailyTotals(entries)

        assertEquals(3.5, totals.getValue(AminoAcid.LEUCINE), 0.0001)
        assertEquals(0.9, totals.getValue(AminoAcid.LYSINE), 0.0)
        assertNull(totals[AminoAcid.TRYPTOPHAN])
        assertEquals(2, totals.size)
    }

    @Test
    fun referenceIntakeUsesWhoAdultRequirementTimesBodyWeight() {
        val expected = mapOf(
            AminoAcid.HISTIDINE to 0.7, AminoAcid.ISOLEUCINE to 1.4, AminoAcid.LEUCINE to 2.73,
            AminoAcid.LYSINE to 2.1, AminoAcid.METHIONINE to 1.05, AminoAcid.PHENYLALANINE to 1.75,
            AminoAcid.THREONINE to 1.05, AminoAcid.TRYPTOPHAN to 0.28, AminoAcid.VALINE to 1.82
        )
        AminoAcid.values().forEach { aminoAcid ->
            assertEquals(aminoAcid.name, expected.getValue(aminoAcid), aminoAcid.referenceGrams(70.0)!!, 0.0001)
        }
        assertNull(AminoAcid.LEUCINE.referenceGrams(0.0))
        assertNull(AminoAcid.LEUCINE.referenceGrams(-60.0))
        assertNull(AminoAcid.LEUCINE.referenceGrams(Double.NaN))
    }

    @Test
    fun promptsOnlyAskForAminoAcidsWhenTheSettingIsOn() {
        val base = "Respond ONLY with JSON."
        val instruction = AminoAcidEstimates.foodPromptInstruction

        assertEquals(base, AminoAcidEstimates.prompt(base, instruction, enabled = false))
        assertEquals(base, AminoAcidEstimates.prompt(base, null, enabled = true))
        assertEquals("$base\n$instruction", AminoAcidEstimates.prompt(base, instruction, enabled = true))
        AminoAcid.values().forEach { aminoAcid ->
            assertTrue(instruction.contains("\"${aminoAcid.apiKey}\":0.0"))
            assertTrue(AminoAcidEstimates.labelPromptInstruction.contains("\"${aminoAcid.apiKey}_per_100g\":0.0"))
        }
    }

    @Test
    fun storageKeysNeverCollideWithSportsCompounds() {
        val sportsKeys = SupplementalNutrient.values().map { it.storageKey }.toSet()
        assertTrue(AminoAcid.values().none { it.storageKey in sportsKeys })
    }
}
