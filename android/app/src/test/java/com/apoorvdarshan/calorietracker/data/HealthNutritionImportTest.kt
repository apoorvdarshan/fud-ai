package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.combineFoodEntries
import com.apoorvdarshan.calorietracker.services.health.ExternalNutrition
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HealthNutritionImportTest {
    private val own = "com.apoorvdarshan.calorietracker"
    private fun record(id: String = "hc-1", origin: String = "com.huami.watch.hmwatchmanager") = ExternalNutrition(
        time = Instant.parse("2025-03-04T12:00:00Z"), name = "Lunch", mealType = MealType.LUNCH,
        calories = 450.4, protein = 25.0, carbs = 50.0, fat = 12.0,
        fiber = 4.0, sugar = 6.0, saturatedFat = 3.0, monounsaturatedFat = 2.0,
        polyunsaturatedFat = 1.0, transFat = 0.0, cholesterol = 30.0, caffeine = 15.0,
        sodium = 500.0, potassium = 200.0, calcium = 100.0, iron = 2.0, magnesium = 20.0,
        zinc = 1.0, vitaminA = 40.0, vitaminC = 10.0, vitaminD = 5.0, vitaminB12 = 3.0,
        vitaminE = 2.0, vitaminK = 8.0, folate = 60.0, clientRecordId = "source-1",
        recordId = id, originPackage = origin
    )

    @Test fun importsFullNutritionAndPreservesSourceThroughSerializationAndEditing() {
        val r = record()
        val e = importedNutritionEntry(r, "Imported meal")
        assertEquals(450, e.calories)
        assertEquals(25.0, e.protein, 0.0)
        assertEquals(r.time, e.timestamp)
        assertEquals(r.mealType, e.mealType)
        assertEquals(r.sodium, e.sodium)
        assertEquals(r.vitaminA, e.vitaminA)
        assertEquals(r.vitaminD, e.vitaminD)
        assertEquals(r.folate, e.folate)
        assertEquals(r.originPackage, e.healthConnectOrigin)
        assertEquals(r.recordId, e.healthConnectRecordId)
        assertNull(e.servingSizeGrams)
        assertEquals("serving", e.selectedServingUnit)
        val decoded = Json.decodeFromString<FoodEntry>(Json.encodeToString(FoodEntry.serializer(), e))
        assertEquals(e, decoded)
        assertEquals(e.healthConnectOrigin, decoded.copy(calories = 500).healthConnectOrigin)
    }

    @Test fun repeatsAndDeletedImportsAreSkippedWithoutOverwritingLocalEdits() {
        val first = mergeNutritionImport(emptyList(), emptySet(), listOf(record()), own, "Imported meal")
        assertEquals(1, first.added)
        val edited = first.entries.single().copy(calories = 600)
        val repeated = mergeNutritionImport(listOf(edited), first.ledger, listOf(record().copy(calories = 700.0)), own, "Imported meal")
        assertEquals(0, repeated.added)
        assertEquals(600, repeated.entries.single().calories)
        val deleted = mergeNutritionImport(emptyList(), first.ledger, listOf(record()), own, "Imported meal")
        assertEquals(0, deleted.added)
        assertTrue(deleted.entries.isEmpty())
    }

    @Test fun duplicatePagesAreSkippedButIdenticalMealsWithDifferentIdsSurvive() {
        val merged = mergeNutritionImport(emptyList(), emptySet(), listOf(record(), record(), record("hc-2")), own, "Imported meal")
        assertEquals(2, merged.added)
        assertNotEquals(merged.entries[0].id, merged.entries[1].id)
    }

    @Test fun excludesOwnAppsAndMissingIdentitiesButAcceptsMissingNameAndMacros() {
        val records = listOf(record(origin = own), record(origin = "$own.debug"), record(id = ""), record(origin = ""), record().copy(name = null, protein = null))
        val candidates = nutritionImportCandidates(records, own, emptySet())
        assertEquals(1, candidates.size)
        val entry = importedNutritionEntry(candidates.single(), "Imported meal")
        assertEquals("Imported meal", entry.name)
        assertEquals(0.0, entry.protein, 0.0)
    }

    @Test fun sourceIdentitiesAreIndependentAndExistingEntrySurvivesMissingLedger() {
        val a = record()
        val b = record(origin = "another.app")
        assertNotEquals(importedNutritionEntry(a, "Meal").id, importedNutritionEntry(b, "Meal").id)
        val existing = importedNutritionEntry(a, "Meal")
        val merged = mergeNutritionImport(listOf(existing), emptySet(), listOf(a), own, "Meal")
        assertEquals(0, merged.added)
        assertTrue(healthImportKey(a) in merged.ledger)
    }

    @Test fun combinedImportedMealsStayLocalButDeliberateRelogsAreNewMeals() {
        val imported = importedNutritionEntry(record(), "Meal")
        val local = imported.duplicatedForLogging(Instant.now())
        assertNull(local.healthConnectOrigin)
        assertNull(local.healthConnectRecordId)
        assertNotEquals(imported.id, local.id)
        val combined = combineFoodEntries(listOf(imported, local))
        assertEquals(imported.healthConnectOrigin, combined.healthConnectOrigin)
    }
}
