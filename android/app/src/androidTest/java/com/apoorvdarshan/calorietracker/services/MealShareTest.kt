package com.apoorvdarshan.calorietracker.services

import android.net.Uri
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.MealShareLinkStyle
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Uses Android's real Uri and org.json implementations, not JVM stubs. */
class MealShareTest {
    private val entries = listOf(FoodEntry(name = "🥚 Eggs", calories = 124, protein = 10.0,
        carbs = 1.0, fat = 8.0, source = FoodSource.MANUAL))

    @Test fun legacyLinksStillRoundTrip() {
        val link = MealShare.link(entries)
        assertEquals("🥚 Eggs", MealShare.meals(Uri.parse(link))?.first()?.name)
        val deepLink = "fudai://add-meal?d=${link.substringAfter("?d=")}"
        assertEquals(124, MealShare.meals(Uri.parse(deepLink))?.first()?.calories)
    }

    /** Every field the app shows survives a share; only photos, ids, time and Health Connect origin stay behind. */
    @Test fun everyNutritionFieldRoundTrips() {
        val shared = FoodEntry(
            name = "Granola bowl", calories = 512, protein = 18.5, carbs = 61.25, fat = 21.75,
            emoji = "🥣", source = FoodSource.BARCODE, mealType = MealType.BREAKFAST,
            sugar = 14.5, addedSugar = 6.0, fiber = 8.25, saturatedFat = 4.5, monounsaturatedFat = 9.5,
            polyunsaturatedFat = 5.25, cholesterol = 12.0, caffeine = 40.0,
            supplementalNutrients = mapOf("creatine" to 3.0, "leucine" to 1.8),
            sodium = 220.0, potassium = 610.0, transFat = 0.1, calcium = 180.0, iron = 3.4, magnesium = 95.0,
            zinc = 2.1, vitaminA = 120.0, vitaminC = 9.5, vitaminD = 2.5, vitaminB12 = 0.9, vitaminE = 3.2,
            vitaminK = 14.0, folate = 60.0, omega3 = 1.2,
            servingSizeGrams = 135.0,
            servingUnitOptions = listOf(ServingUnitOption("cup", 90.0, 1.5), ServingUnitOption("g", 1.0)),
            selectedServingUnit = "cup", selectedServingQuantity = 1.5,
            customNote = "with oat milk", progressiveMeal = true,
            ingredients = listOf(
                MealIngredient("Granola", 60.0, 280, 7.0, 38.0, 11.0, emoji = "🌾"),
                MealIngredient("Greek yogurt", 75.0, 232, 11.5, 23.25, 10.75)
            ),
            productMetadata = FoodProductMetadata(
                barcode = "5000159407236", packageQuantity = "500 g", ingredientsText = "Oats, honey, almonds",
                allergens = listOf("en:nuts"), traces = listOf("en:milk"), nutriScore = "b", novaGroup = 3,
                ecoScore = "c", labels = listOf("en:vegetarian"), categories = listOf("en:breakfast-cereals"),
                imageUrl = "https://images.openfoodfacts.org/p.jpg", dataSource = FoodProductMetadata.USDA_FOOD_DATA_CENTRAL
            )
        )
        val imported = MealShare.meals(Uri.parse(MealShare.link(listOf(shared))))!!.single()
        // The importer logs a fresh manual entry now; everything else matches.
        assertEquals(FoodSource.MANUAL, imported.source)
        assertEquals(shared.copy(id = imported.id, timestamp = imported.timestamp, source = FoodSource.MANUAL), imported)
    }

    /** The exact keys iOS writes, so a link made on either platform imports on the other. */
    @Test fun readsTheSharedPayloadKeys() {
        val payload = """{"v":1,"meals":[{"name":"Toast","calories":180,"protein":6,"carbs":30,"fat":3,
            "mealType":"lunch","servingSizeGrams":60,"servingUnitOptions":[{"unit":"slice","gramsPerUnit":30,"quantity":2}],
            "selectedServingUnit":"slice","selectedServingQuantity":2,
            "ingredients":[{"name":"Bread","grams":60,"calories":180,"protein":6,"carbs":30,"fat":3,"emoji":"🍞"}],
            "productMetadata":{"barcode":"123","allergens":["en:gluten"],"novaGroup":4,"imageUrl":"https://x.test/i.jpg"}}]}"""
        val d = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val toast = MealShare.meals(Uri.parse("fudai://add-meal?d=$d"))!!.single()
        assertEquals(MealType.LUNCH, toast.mealType)
        assertEquals(60.0, toast.servingSizeGrams!!, 0.0)
        assertEquals(listOf(ServingUnitOption("slice", 30.0, 2.0)), toast.servingUnitOptions)
        assertEquals("slice", toast.selectedServingUnit)
        assertEquals(2.0, toast.selectedServingQuantity!!, 0.0)
        assertEquals("🍞", toast.ingredients.single().emoji)
        assertEquals(
            FoodProductMetadata(barcode = "123", allergens = listOf("en:gluten"), novaGroup = 4, imageUrl = "https://x.test/i.jpg"),
            toast.productMetadata
        )
    }

    @Test fun directLinkNeverAsksForAShortLink() = runBlocking {
        val result = MealShare.preferredLink(entries, MealShareLinkStyle.DIRECT) { fail("Direct Link must not call the short-link service"); null }
        assertEquals(MealShare.link(entries), result)
        assertTrue(result.startsWith("https://www.fud-ai.app/add-meal?d="))
    }

    @Test fun prefersShortLink() = runBlocking {
        val short = "https://www.fud-ai.app/m/abcdefghijklmnopqrstuv"
        val result = MealShare.preferredLink(entries) { body ->
            val payload = JSONObject(body)
            assertEquals(1, payload.getInt("v"))
            assertEquals("🥚 Eggs", payload.getJSONArray("meals").getJSONObject(0).getString("name"))
            short
        }
        assertEquals(short, result)
        assertFalse(MealShare.shareText(entries, result).contains("?d="))
    }

    @Test fun rateLimitFallsBackWithoutRetry() = runBlocking {
        val fallback = MealShare.link(entries)
        var attempts = 0
        val result = MealShare.preferredLink(entries) {
            attempts += 1
            null
        }
        assertEquals(1, attempts)
        assertEquals(fallback, result)
    }

    @Test fun failuresPreserveLongLink() = runBlocking {
        val fallback = MealShare.link(entries)
        assertEquals(fallback, MealShare.preferredLink(entries) { throw IOException("offline") })
        assertEquals(fallback, MealShare.preferredLink(entries) { null })
        assertEquals(fallback, MealShare.preferredLink(entries) { "https://evil.example/m/abcdefghijklmnopqrstuv" })
        assertEquals(fallback, MealShare.preferredLink(entries) { "https://www.fud-ai.app/m/invalid" })
    }
}
