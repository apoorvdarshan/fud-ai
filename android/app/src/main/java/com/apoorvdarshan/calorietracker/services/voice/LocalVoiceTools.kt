package com.apoorvdarshan.calorietracker.services.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only explicitly allowed tools can run. The model can never approve a write. */
internal class LocalVoiceTools(catalog: List<VoiceToolSpec>, workouts: Boolean, connected: Boolean) {
    private val specs = VoiceToolCatalog.declared(catalog, workouts)
        .filter { it.name in local || (connected && it.name in networked) }
        .map { if (it.effect == ToolEffect.WRITE) it.copy(confirm = ToolConfirm.ALWAYS) else it }
    private val allowed = specs.associateBy { it.name }
    val executorSpecs = specs.filterNot { it.isCoachDataTool }
    val declarations = specs.joinToString("\n") { tool ->
        val schema = tool.parameters.toMutableMap()
        val properties = (schema["properties"] as? JsonObject)?.minus("user_confirmed").orEmpty()
            .let { if (tool.name == "log_food") it - "item" else it }
        schema["properties"] = JsonObject(properties)
        buildJsonObject {
            put("name", tool.name)
            put("description", if (tool.name == "log_food") "Prepare a food save from a saved_food_id or a draft_id returned by a tool. Never invent an ID."
                else tool.description.take(190))
            put("parameters", JsonObject(schema))
        }.toString()
    }

    data class Call(val request: LiveToolCall, val wire: String) {
        fun continuation(result: JsonObject): String {
            val raw = result.toString()
            val data = if (raw.length <= 1250) raw else {
                // Keep the first few result records, but never feed a partial JSON string back.
                val compact = result.mapValues { (_, value) ->
                    if (value is kotlinx.serialization.json.JsonArray) kotlinx.serialization.json.JsonArray(value.take(3)) else value
                }.toMutableMap()
                compact["truncated"] = JsonPrimitive(true)
                JsonObject(compact).toString().takeIf { it.length <= 1250 }
                    ?: """{"truncated":true,"message":"Too much data. Ask for a narrower date range or a more specific food."}"""
            }
            return "<|im_start|>assistant\n$wire<|im_end|>\n<|im_start|>tool\n<tool_response>\n" +
                data.replace("<", "\\u003c").replace(">", "\\u003e") + "\n</tool_response><|im_end|>\n"
        }
    }

    fun parse(text: String): Call? {
        if (text.length > 1600) return null
        val match = call.matchEntire(text.trim()) ?: return null
        val name = match.groupValues[1]
        val spec = allowed[name] ?: return null
        val fields = (spec.parameters["properties"] as? JsonObject)?.keys.orEmpty()
        val args = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        val body = match.groupValues[2]
        var cursor = 0
        for (part in parameter.findAll(body)) {
            if (cursor != part.range.first) return null
            cursor = part.range.last + 1
            val key = part.groupValues[1]
            val value = part.groupValues[2].trim()
            if (key == "user_confirmed" || (name == "log_food" && key == "item") ||
                key !in fields || key in args || value.length > 500 || '<' in value) return null
            args[key] = runCatching { Json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
        }
        if (cursor != body.length) return null
        return Call(LiveToolCall("local", name, JsonObject(args)), text.trim())
    }

    companion object {
        private val call = Regex("""<tool_call>\s*<function=([a-z_]+)>\s*(.*?)</function>\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        private val parameter = Regex("""<parameter=([a-z_]+)>\s*(.*?)\s*</parameter>\s*""", RegexOption.DOT_MATCHES_ALL)
        private val local = setOf(
            "get_food_entries", "get_calorie_totals", "get_weight_history", "get_fasting_history", "get_workout_history",
            "search_foods", "log_food", "log_water", "log_weight", "log_body_fat", "start_fast", "end_fast",
            "log_cardio", "set_diary_date", "open_food_entry"
        )
        private val networked = setOf(
            "describe_food", "open_camera", "capture_photo", "close_camera", "pick_photos", "scan_barcode",
            "describe_workout", "log_workout"
        )
    }
}
