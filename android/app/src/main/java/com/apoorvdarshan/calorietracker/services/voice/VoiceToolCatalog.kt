package com.apoorvdarshan.calorietracker.services.voice

import android.content.Context
import com.apoorvdarshan.calorietracker.services.ai.CoachTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class ToolEffect(val wireValue: String) {
    READ("read"), NAVIGATE("navigate"), WRITE("write"), DESTRUCTIVE("destructive");

    companion object {
        fun fromWire(raw: String?): ToolEffect =
            entries.firstOrNull { it.wireValue == raw } ?: throw IllegalArgumentException("Unknown effect: $raw")
    }
}

enum class ToolConfirm(val wireValue: String) {
    NEVER("never"),
    /** Only while Confirm before saving is on. */
    SETTING("setting"),
    ALWAYS("always");

    companion object {
        fun fromWire(raw: String?): ToolConfirm =
            entries.firstOrNull { it.wireValue == raw } ?: throw IllegalArgumentException("Unknown confirm: $raw")
    }
}

/** One tool the live session declares: a catalog action or a Coach data tool. */
data class VoiceToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val effect: ToolEffect,
    val confirm: ToolConfirm,
    /** Hidden when the workout diary is off. */
    val workout: Boolean,
    val isCoachDataTool: Boolean = false
)

/**
 * The voice assistant's tool catalog: `assets/voice_assistant_tools.json` (an identical
 * copy of shared/voice-assistant/tools.json) plus the existing Coach data tools.
 */
object VoiceToolCatalog {
    const val ASSET_NAME = "voice_assistant_tools.json"

    /** Gemini and the hosted relay accept at most this many declarations (README). */
    const val MAX_HOSTED_TOOLS = 64
    const val MAX_HOSTED_TOOLS_BYTES = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<VoiceToolSpec>? = null

    /** The bundled catalog, parsed once per process. */
    fun bundled(context: Context): List<VoiceToolSpec> =
        cached ?: synchronized(this) {
            cached ?: parse(
                context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            ).also { cached = it }
        }

    fun parse(raw: String): List<VoiceToolSpec> {
        val root = json.parseToJsonElement(raw).jsonObject
        return root.getValue("tools").jsonArray.map { element ->
            val tool = element.jsonObject
            VoiceToolSpec(
                name = tool.getValue("name").jsonPrimitive.content,
                description = tool.getValue("description").jsonPrimitive.content,
                parameters = tool["parameters"]?.jsonObject
                    ?: buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) },
                effect = ToolEffect.fromWire(tool["effect"]?.jsonPrimitive?.content),
                confirm = ToolConfirm.fromWire(tool["confirm"]?.jsonPrimitive?.content),
                workout = tool["workout"]?.jsonPrimitive?.booleanOrNull == true
            )
        }
    }

    /** Coach's data tools, read-only, sharing Coach's descriptions and schemas. */
    fun coachDataTools(): List<VoiceToolSpec> = CoachTools.TOOL_NAMES.map { name ->
        VoiceToolSpec(
            name = name,
            description = CoachTools.TOOL_DESCRIPTIONS[name].orEmpty(),
            parameters = toJsonElement(CoachTools.parameterSchemaFor(name)).jsonObject,
            effect = ToolEffect.READ,
            confirm = ToolConfirm.NEVER,
            workout = name in CoachTools.WORKOUT_TOOL_NAMES,
            isCoachDataTool = true
        )
    }

    /**
     * What a session declares: the catalog plus Coach's data tools, with workout tools left
     * out when the workout diary is off.
     */
    fun declared(catalog: List<VoiceToolSpec>, workoutDiaryEnabled: Boolean): List<VoiceToolSpec> =
        (catalog + coachDataTools()).filter { workoutDiaryEnabled || !it.workout }

    /** Gemini Live / hosted: `tools: [{functionDeclarations: [...]}]`. */
    fun geminiTools(tools: List<VoiceToolSpec>, nonBlocking: Boolean = false): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                        if (nonBlocking) put("behavior", "NON_BLOCKING")
                    })
                }
            })
        })
    }

    /** OpenAI Realtime / xAI: `session.tools: [{type: "function", name, description, parameters}]`. */
    fun realtimeTools(tools: List<VoiceToolSpec>): JsonArray = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            })
        }
    }

    /** Converts the Map/List schema trees Coach builds into JSON. */
    internal fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJsonElement(item) })
        is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
        is Array<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}
