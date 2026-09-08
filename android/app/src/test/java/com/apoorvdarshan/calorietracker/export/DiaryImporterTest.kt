package com.apoorvdarshan.calorietracker.export

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class DiaryImporterTest {
    @Test
    fun roundTripReplacesRangeAndPreservesMedia() {
        val date = LocalDate.of(2026, 8, 5)
        val time = date.atTime(12, 30).atZone(ZoneId.systemDefault()).toInstant()
        val id = UUID.randomUUID()
        val existing = FoodEntry(
            id = id,
            name = "Rice bowl",
            calories = 500,
            protein = 20.0,
            carbs = 70.0,
            fat = 12.0,
            timestamp = time,
            imageFilename = "meal.jpg",
            source = FoodSource.TEXT_INPUT,
            mealType = MealType.LUNCH,
            fiber = 2.0,
        )
        val outside = existing.copy(
            id = UUID.randomUUID(),
            name = "Earlier meal",
            timestamp = time.minusSeconds(3 * 86_400L),
            imageFilename = null,
        )
        val (_, exported) = requireNotNull(DiaryExporter.build(
            entries = listOf(existing),
            start = date,
            end = date,
            format = DiaryFormat.JSON,
            profile = UserProfile(customCalories = 2_000, customProtein = 120, customCarbs = 200, customFat = 60),
            mealDisplay = { it.name },
        ))
        val root = JsonParser.parseString(exported).asJsonObject
        val item = root["days"].asJsonArray[0].asJsonObject["meals"].asJsonArray[0]
            .asJsonObject["items"].asJsonArray[0].asJsonObject
        item.addProperty("fiber_g", 8.5)

        val preview = DiaryImporter.parse(root.toString())
        val result = DiaryImporter.applying(preview, listOf(outside, existing), DiaryImportMode.REPLACE_DATE_RANGE)

        assertEquals(2, result.size)
        assertTrue(result.any { it.id == outside.id })
        val imported = requireNotNull(result.firstOrNull { it.id == id })
        assertEquals(8.5, imported.fiber!!, 0.0001)
        assertEquals("meal.jpg", imported.imageFilename)
    }

    @Test
    fun legacyExportAddsWithANewIdentity() {
        val preview = DiaryImporter.parse(validLegacyDiary)
        val result = DiaryImporter.applying(preview, preview.entries, DiaryImportMode.ADD_AS_NEW)

        assertEquals(2, result.size)
        assertNotEquals(result[0].id, result[1].id)
    }

    @Test fun legacyFoodDiaryPreservesWater() {
        val preview = DiaryImporter.parse(validLegacyDiary)
        val water = com.apoorvdarshan.calorietracker.models.WaterEntry(
            date = preview.startDate.atStartOfDay(ZoneId.systemDefault()).toInstant(), milliliters = 250)
        assertTrue(!preview.includesWater)
        assertEquals(listOf(water), DiaryImporter.applyingWater(preview, listOf(water), DiaryImportMode.REPLACE_DATE_RANGE))
    }

    private val validLegacyDiary = """
        {
          "export": {
            "app": "Fud AI",
            "format_version": "1.3",
            "date_range": { "start": "2026-08-05", "end": "2026-08-05" }
          },
          "days": [{
            "date": "2026-08-05",
            "meals": [{
              "type": "lunch",
              "items": [{
                "name": "Rice bowl",
                "quantity_g": 250,
                "calories": 500,
                "protein_g": 20,
                "carbs_g": 70,
                "fat_g": 12,
                "time": "12:30",
                "source": "ai_estimated",
                "ingredients": []
              }]
            }]
          }]
        }
    """.trimIndent()
}
