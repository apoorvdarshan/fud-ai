package com.apoorvdarshan.calorietracker.export

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DiaryExporterTest {
    @Test fun waterOnlyDaysRoundTripAndExportWithoutCalories() {
        val day = LocalDate.of(2026, 9, 1)
        val water = com.apoorvdarshan.calorietracker.models.WaterEntry(
            date = day.atTime(10, 15).atZone(ZoneId.systemDefault()).toInstant(), milliliters = 250)
        fun export(format: DiaryFormat) = requireNotNull(DiaryExporter.build(
            entries = emptyList(), start = day, end = day, format = format,
            profile = null, mealDisplay = { it.name }, waterEntries = listOf(water))).second
        val json = export(DiaryFormat.JSON)
        val data = JsonParser.parseString(json).asJsonObject["days"].asJsonArray[0].asJsonObject
        assertEquals(250, data["water_total_ml"].asInt)
        assertEquals(0, data["totals"].asJsonObject["calories"].asInt)
        val preview = DiaryImporter.parse(json)
        assertTrue(preview.entries.isEmpty())
        assertEquals(listOf(water), preview.waterEntries)
        assertEquals(day, DiaryExporter.resolveRange(DiaryRange.ALL_TIME, day, day, emptyList(), listOf(water)).first)
        assertTrue(export(DiaryFormat.MARKDOWN).contains("| 10:15 | 250 |"))
        val csv = export(DiaryFormat.CSV).trimEnd().lines().map { it.split(',') }
        assertEquals(3, csv.size)
        assertTrue(csv.all { it.size == csv[0].size })
        assertEquals("water", csv[1][35])
        assertEquals("250", csv[1][36])
        assertEquals("water_total", csv[2][35])
        assertEquals("250", csv[2][37])
        assertEquals(listOf(water), DiaryImporter.applyingWater(preview, listOf(water), DiaryImportMode.REPLACE_DATE_RANGE))
        assertTrue(DiaryImporter.applyingWater(preview.copy(waterEntries = emptyList()), listOf(water), DiaryImportMode.REPLACE_DATE_RANGE).isEmpty())
        val added = DiaryImporter.applyingWater(preview, listOf(water), DiaryImportMode.ADD_AS_NEW)
        assertEquals(2, added.map { it.id }.toSet().size)
        val legacy = preview.copy(waterEntries = emptyList(), includesWater = false)
        assertEquals(listOf(water), DiaryImporter.applyingWater(legacy, listOf(water), DiaryImportMode.REPLACE_DATE_RANGE))
        val outside = water.copy(date = water.date.minusSeconds(86400))
        val replaced = DiaryImporter.applyingWater(preview, listOf(outside), DiaryImportMode.REPLACE_DATE_RANGE)
        assertEquals(outside, replaced.first())
        assertEquals(2, replaced.map { it.id }.toSet().size)
        val invalid = json.replace("\"milliliters\": 250", "\"milliliters\": -1")
        org.junit.Assert.assertThrows(DiaryImportException::class.java) { DiaryImporter.parse(invalid) }
        org.junit.Assert.assertThrows(DiaryImportException::class.java) { DiaryImporter.parse(json.replace("10:15", "25:15")) }
    }

    @Test fun mixedDiaryFiltersWaterAndPreservesNutrition() {
        val food = nutrientEntry()
        val date = food.timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
        val water = com.apoorvdarshan.calorietracker.models.WaterEntry(date = food.timestamp, milliliters = 500)
        val outside = water.copy(date = water.date.minusSeconds(86400), milliliters = 250)
        val json = requireNotNull(DiaryExporter.build(entries = listOf(food), start = date, end = date,
            format = DiaryFormat.JSON, profile = null, mealDisplay = { it.name },
            waterEntries = listOf(outside, water))).second
        val preview = DiaryImporter.parse(json)
        assertEquals(1, preview.entries.size)
        assertEquals(food.calories, preview.entries[0].calories)
        assertEquals(listOf(500), preview.waterEntries.map { it.milliliters })
    }

    private val nutrientFields = listOf(
        "sugar_g", "added_sugar_g", "fiber_g", "saturated_fat_g",
        "monounsaturated_fat_g", "polyunsaturated_fat_g", "cholesterol_mg",
        "caffeine_mg", "sodium_mg", "potassium_mg", "trans_fat_g", "calcium_mg", "iron_mg",
        "magnesium_mg", "zinc_mg", "vitamin_a_mcg", "vitamin_c_mg",
        "vitamin_d_mcg", "vitamin_b12_mcg", "vitamin_e_mg", "vitamin_k_mcg",
        "folate_mcg", "omega3_g",
    )

    @Test
    fun jsonIncludesEveryStoredNutrient() {
        val entry = nutrientEntry()
        val date = entry.timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
        val (_, json) = requireNotNull(build(entry, date, DiaryFormat.JSON))
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("1.5", root["export"].asJsonObject["format_version"].asString)
        val item = root["days"].asJsonArray[0].asJsonObject["meals"].asJsonArray[0]
            .asJsonObject["items"].asJsonArray[0].asJsonObject

        assertEquals(entry.id.toString(), item["entry_id"].asString)
        nutrientFields.forEach { field -> assertTrue("Missing JSON nutrient field: $field", item.has(field)) }
        assertEquals(3.3, item["fiber_g"].asDouble, 0.0001)
        assertEquals(8.8, item["sodium_mg"].asDouble, 0.0001)
        assertEquals(18.8, item["vitamin_b12_mcg"].asDouble, 0.0001)
        assertEquals(5.0, item["supplemental_nutrients_g"].asJsonObject["creatine"].asDouble, 0.0001)
        assertEquals("Rice", item["ingredients"].asJsonArray[0].asJsonObject["name"].asString)
    }

    @Test
    fun csvAndMarkdownIncludeEveryStoredNutrient() {
        val entry = nutrientEntry()
        val date = entry.timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
        val (_, csv) = requireNotNull(build(entry, date, DiaryFormat.CSV))
        val lines = csv.trimEnd().lines()
        val headers = lines[0].split(',')
        val values = lines[1].split(',')
        assertEquals(headers.size, values.size)
        nutrientFields.forEach { field -> assertTrue("Missing CSV nutrient column: $field", headers.contains(field)) }
        assertEquals("3.3", values[headers.indexOf("fiber_g")])
        assertTrue(csv.contains("Rice"))

        val (_, markdown) = requireNotNull(build(entry, date, DiaryFormat.MARKDOWN))
        listOf("Fiber (g)", "Sodium (mg)", "Vitamin A (mcg)", "Vitamin B12 (mcg)", "Omega-3 (g)")
            .forEach { heading -> assertTrue("Missing Markdown nutrient heading: $heading", markdown.contains(heading)) }
        assertTrue(markdown.contains("Rice"))
    }

    private fun build(entry: FoodEntry, date: LocalDate, format: DiaryFormat) = DiaryExporter.build(
        entries = listOf(entry),
        start = date,
        end = date,
        format = format,
        profile = UserProfile(customCalories = 2_000, customProtein = 120, customCarbs = 200, customFat = 60),
        mealDisplay = { it.name },
    )

    private fun nutrientEntry() = FoodEntry(
        name = "Nutrient fixture",
        calories = 120,
        protein = 4.4,
        carbs = 5.5,
        fat = 6.6,
        timestamp = Instant.ofEpochSecond(1_752_840_000),
        source = FoodSource.MANUAL,
        mealType = MealType.LUNCH,
        sugar = 1.1,
        addedSugar = 2.2,
        fiber = 3.3,
        saturatedFat = 4.4,
        monounsaturatedFat = 5.5,
        polyunsaturatedFat = 6.6,
        cholesterol = 7.7,
        caffeine = 7.8,
        supplementalNutrients = mapOf("creatine" to 5.0),
        sodium = 8.8,
        potassium = 9.9,
        transFat = 10.1,
        calcium = 11.1,
        iron = 12.2,
        magnesium = 13.3,
        zinc = 14.4,
        vitaminA = 15.5,
        vitaminC = 16.6,
        vitaminD = 17.7,
        vitaminB12 = 18.8,
        vitaminE = 19.9,
        vitaminK = 20.1,
        folate = 21.2,
        omega3 = 22.3,
        servingSizeGrams = 100.0,
        ingredients = listOf(MealIngredient("Rice", 100.0, 120, 4.4, 5.5, 6.6)),
    )
}
