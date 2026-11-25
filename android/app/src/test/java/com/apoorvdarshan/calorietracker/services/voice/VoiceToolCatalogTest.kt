package com.apoorvdarshan.calorietracker.services.voice

import com.apoorvdarshan.calorietracker.services.ai.CoachTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reads repository files from the unit test's working directory (the app module). */
internal object RepoFiles {
    fun find(relativeToApp: String): File =
        listOf(File(relativeToApp), File("app", relativeToApp), File("android/app", relativeToApp))
            .firstOrNull { it.exists() }
            ?: error("Missing $relativeToApp (working directory ${File(".").absolutePath})")

    val bundledCatalog: File get() = find("src/main/assets/${VoiceToolCatalog.ASSET_NAME}")
    val sharedCatalog: File get() = find("../../shared/voice-assistant/tools.json")

    val catalog: List<VoiceToolSpec> by lazy { VoiceToolCatalog.parse(bundledCatalog.readText()) }
}

class VoiceToolCatalogTest {
    private val catalog = RepoFiles.catalog

    private fun executor() = VoiceToolExecutor(
        ports = FakeVoiceAppPorts(),
        catalog = catalog,
        context = VoiceSessionContext(LiveProvider.GEMINI, confirmSaves = true, autoCapture = false)
    )

    @Test
    fun bundledCatalogIsTheSharedContract() {
        assertArrayEquals(RepoFiles.sharedCatalog.readBytes(), RepoFiles.bundledCatalog.readBytes())
    }

    @Test
    fun catalogParsesEveryTool() {
        assertEquals(26, catalog.size)
        val history = catalog.single { it.name == "get_coach_history" }
        assertEquals(ToolEffect.READ, history.effect)
        assertEquals(ToolConfirm.NEVER, history.confirm)
        assertEquals(catalog.size, catalog.map { it.name }.toSet().size)
        catalog.forEach { tool ->
            assertTrue("${tool.name} needs a description", tool.description.isNotBlank())
            assertEquals("object", tool.parameters["type"]!!.jsonPrimitive.content)
            if (tool.effect == ToolEffect.DESTRUCTIVE) assertEquals(ToolConfirm.ALWAYS, tool.confirm)
            if (tool.confirm != ToolConfirm.NEVER) {
                val properties = tool.parameters["properties"]!!.jsonObject
                assertTrue("${tool.name} needs user_confirmed", properties.containsKey("user_confirmed"))
            }
        }
    }

    @Test
    fun everyBundledToolHasAHandler() {
        val handled = executor().handledToolNames
        val missing = catalog.map { it.name }.filterNot { it in handled }
        assertTrue("No Android handler for $missing", missing.isEmpty())
    }

    @Test
    fun everyCoachDataToolIsDeclaredAndHandled() {
        val declared = VoiceToolCatalog.declared(catalog, workoutDiaryEnabled = true).map { it.name }
        assertTrue(declared.containsAll(CoachTools.TOOL_NAMES))
        assertTrue(executor().handledToolNames.containsAll(CoachTools.TOOL_NAMES))
        assertEquals(declared.size, declared.toSet().size)
    }

    @Test
    fun workoutToolsAreHiddenWhenTheWorkoutDiaryIsOff() {
        val on = VoiceToolCatalog.declared(catalog, workoutDiaryEnabled = true).map { it.name }.toSet()
        val off = VoiceToolCatalog.declared(catalog, workoutDiaryEnabled = false).map { it.name }.toSet()
        val workoutTools = setOf("describe_workout", "log_workout", "log_cardio") + CoachTools.WORKOUT_TOOL_NAMES
        assertTrue(on.containsAll(workoutTools))
        workoutTools.forEach { assertFalse("$it should be hidden", it in off) }
        assertTrue(off.contains("log_food"))
        assertTrue(off.contains("get_food_entries"))
    }

    @Test
    fun geminiDeclarationsWrapFunctionDeclarations() {
        val tools = VoiceToolCatalog.declared(catalog, workoutDiaryEnabled = true)
        val declarations = VoiceToolCatalog.geminiTools(tools)
        assertEquals(1, declarations.size)
        val functions = declarations[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(tools.size, functions.size)
        val logFood = functions.first { it.jsonObject["name"]!!.jsonPrimitive.content == "log_food" }.jsonObject
        assertTrue(logFood["description"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(logFood["parameters"] is JsonObject)
    }

    @Test
    fun realtimeToolsAreFlatFunctionEntries() {
        val tools = VoiceToolCatalog.declared(catalog, workoutDiaryEnabled = true)
        val realtime: JsonArray = VoiceToolCatalog.realtimeTools(tools)
        assertEquals(tools.size, realtime.size)
        realtime.forEach { entry ->
            val tool = entry.jsonObject
            assertEquals("function", tool["type"]!!.jsonPrimitive.content)
            assertTrue(tool.containsKey("name"))
            assertTrue(tool.containsKey("parameters"))
            assertFalse("No nested function object", tool.containsKey("function"))
        }
    }

    @Test
    fun declaredToolsFitTheHostedRelayLimits() {
        val tools = VoiceToolCatalog.declared(catalog, workoutDiaryEnabled = true)
        assertTrue(tools.size <= VoiceToolCatalog.MAX_HOSTED_TOOLS)
        val bytes = VoiceToolCatalog.geminiTools(tools).toString().toByteArray(Charsets.UTF_8).size
        assertTrue("Declarations are $bytes bytes", bytes <= VoiceToolCatalog.MAX_HOSTED_TOOLS_BYTES)
    }
}
