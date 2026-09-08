package com.apoorvdarshan.calorietracker.services

import android.net.Uri
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
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

    @Test fun failuresPreserveLongLink() = runBlocking {
        val fallback = MealShare.link(entries)
        assertEquals(fallback, MealShare.preferredLink(entries) { throw IOException("offline") })
        assertEquals(fallback, MealShare.preferredLink(entries) { null })
        assertEquals(fallback, MealShare.preferredLink(entries) { "https://evil.example/m/abcdefghijklmnopqrstuv" })
        assertEquals(fallback, MealShare.preferredLink(entries) { "https://www.fud-ai.app/m/invalid" })
    }
}
