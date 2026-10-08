package com.apoorvdarshan.calorietracker.services.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Shrinks a Coach tool result (a JSON object) to at most `maxChars` of JSON.
 * The largest array (e.g. food entries) keeps its leading items and the result
 * is marked `truncated`, so the model can ask for a narrower range.
 */
internal object ToolResultLimiter {
    private const val NOTE = "Result trimmed to fit. Ask for a narrower date range to see the rest."

    fun fit(resultJson: String, maxChars: Int): String {
        if (resultJson.length <= maxChars) return resultJson
        val result = runCatching { Json.parseToJsonElement(resultJson).jsonObject }.getOrNull()
            ?: return tooLarge()
        val (key, items) = result.entries
            .filter { it.value is JsonArray }
            .maxByOrNull { it.value.toString().length }
            ?.let { it.key to (it.value as JsonArray) }
            ?: return tooLarge()

        fun trimmed(keep: Int) = JsonObject(
            result + mapOf(
                key to JsonArray(items.take(keep)),
                "truncated" to JsonPrimitive(true),
                "omitted_items" to JsonPrimitive(items.size - keep),
                "note" to JsonPrimitive(NOTE)
            )
        ).toString()

        // Largest prefix that fits.
        var low = 0
        var high = items.size - 1
        var best: String? = null
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = trimmed(mid)
            if (candidate.length <= maxChars) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best ?: tooLarge()
    }

    private fun tooLarge(): String = JsonObject(
        mapOf("error" to JsonPrimitive("result_too_large"), "note" to JsonPrimitive(NOTE))
    ).toString()
}
