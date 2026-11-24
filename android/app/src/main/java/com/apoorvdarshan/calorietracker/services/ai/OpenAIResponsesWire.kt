package com.apoorvdarshan.calorietracker.services.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Stateless Responses turns. Nothing is stored on the provider for conversation continuation. */
internal object OpenAIResponsesWire {
    data class Call(val id: String, val name: String, val arguments: JsonObject)
    data class Turn(val items: List<JsonObject>, val calls: List<Call>, val text: String?)

    fun request(model: String, instructions: String, input: JsonArray, tools: JsonArray, maxTokens: Int): JsonObject =
        buildJsonObject {
            put("model", model)
            put("instructions", instructions)
            put("input", input)
            put("tools", tools)
            put("tool_choice", "auto")
            put("max_output_tokens", maxTokens.coerceAtLeast(16))
            putJsonObject("reasoning") { put("effort", "low") }
            put("store", false)
            putJsonArray("include") { add("reasoning.encrypted_content") }
        }

    fun toolOutput(callId: String, result: String): JsonObject = buildJsonObject {
        put("type", "function_call_output")
        put("call_id", callId)
        put("output", result)
    }

    fun decode(raw: String): Turn {
        val body = jsonObject(raw) ?: throw AiError.InvalidResponse
        if (body.string("status") == "incomplete") throw AiError.Failure(AiErrorKind.TRUNCATED)
        if (body.string("status") != "completed") throw AiError.InvalidResponse
        val output = body["output"] as? JsonArray ?: throw AiError.InvalidResponse
        val items = output.map { it as? JsonObject ?: throw AiError.InvalidResponse }
        val calls = mutableListOf<Call>()
        val text = mutableListOf<String>()
        for (item in items) when (item.string("type")) {
            "function_call" -> {
                val id = item.string("call_id") ?: throw AiError.InvalidResponse
                val name = item.string("name") ?: throw AiError.InvalidResponse
                val arguments = item.string("arguments")?.let(::jsonObject) ?: throw AiError.InvalidResponse
                if (calls.any { it.id == id }) throw AiError.InvalidResponse
                calls += Call(id, name, arguments)
            }
            "message" -> {
                val content = item["content"] as? JsonArray ?: continue
                for (element in content) {
                    val block = element as? JsonObject ?: continue
                    if (block.string("type") == "output_text") block.string("text")?.trim()?.let(text::add)
                }
            }
            else -> Unit
        }
        return Turn(items, calls, text.joinToString("\n").takeIf { it.isNotEmpty() })
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun jsonObject(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}
