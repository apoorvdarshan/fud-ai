package com.apoorvdarshan.calorietracker.services.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultLimiterTest {
    private fun entries(count: Int): String {
        val items = (1..count).joinToString(",") { i ->
            """{"date":"2026-10-${(i % 28) + 1}","name":"Meal $i with a fairly long descriptive name","kcal":$i,"protein_g":12.5,"carbs_g":40.1,"fat_g":9.8}"""
        }
        return """{"range":"2026-07-01..2026-10-08","count":$count,"entries":[$items]}"""
    }

    @Test
    fun `small results pass through unchanged`() {
        val json = entries(3)
        assertEquals(json, ToolResultLimiter.fit(json, 60_000))
    }

    @Test
    fun `large results keep the leading entries and fit the limit`() {
        val json = entries(2_000)
        assertTrue(json.length > 60_000)

        val fitted = ToolResultLimiter.fit(json, 60_000)
        val obj = Json.parseToJsonElement(fitted).jsonObject

        assertTrue(fitted.length <= 60_000)
        val kept = obj["entries"]!!.jsonArray.size
        assertTrue(kept > 100)
        assertEquals("Meal 1 with a fairly long descriptive name", obj["entries"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(obj["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(2_000 - kept, obj["omitted_items"]!!.jsonPrimitive.int)
        assertEquals(2_000, obj["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `oversized results without an array become an error`() {
        val json = """{"text":"${"x".repeat(70_000)}"}"""
        val obj = Json.parseToJsonElement(ToolResultLimiter.fit(json, 60_000)).jsonObject
        assertEquals("result_too_large", obj["error"]!!.jsonPrimitive.content)
    }
}
