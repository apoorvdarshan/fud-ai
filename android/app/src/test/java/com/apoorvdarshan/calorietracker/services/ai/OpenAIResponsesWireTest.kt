package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.services.speech.DeepgramClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class OpenAIResponsesWireTest {
    @Test fun responsesPreservesReasoningItemsAndLinksToolResultByCallId() {
        val turn = OpenAIResponsesWire.decode("""
            {"status":"completed","output":[
              {"type":"reasoning","encrypted_content":"opaque"},
              {"type":"function_call","id":"fc_output","call_id":"call_fixture","name":"get_app_state","arguments":"{}"}
            ]}
        """.trimIndent())
        val call = turn.calls.single()
        assertEquals("get_app_state", call.name)
        assertEquals("opaque", turn.items.first()["encrypted_content"]?.jsonPrimitive?.contentOrNull)
        val input = JsonArray(turn.items + OpenAIResponsesWire.toolOutput(call.id, """{"ready":true}"""))
        val request = OpenAIResponsesWire.request("gpt-6.2-sol", "Be concise.", input, JsonArray(emptyList()), 1024)
        assertEquals("false", request["store"]?.jsonPrimitive?.content)
        assertEquals("reasoning.encrypted_content", request.getValue("include").jsonArray[0].jsonPrimitive.content)
        assertEquals("call_fixture", request.getValue("input").jsonArray[2].jsonObject.getValue("call_id").jsonPrimitive.content)
        assertTrue(AIProvider.OPENAI.needsResponsesForTools("gpt-6.2-sol"))
        assertTrue(AIProvider.OPENAI.needsResponsesForTools("gpt-6.1-terra"))
        assertFalse(AIProvider.OPENAI.needsResponsesForTools("gpt-6-luna"))
    }

    @Test fun incompleteAndDuplicatedToolCallsCannotExecute() {
        assertTrue(runCatching { OpenAIResponsesWire.decode("""{"status":"incomplete","output":[]}""") }.isFailure)
        val call = """{"type":"function_call","call_id":"same","name":"get_app_state","arguments":"{}"}"""
        assertTrue(runCatching {
            OpenAIResponsesWire.decode("""{"status":"completed","output":[$call,$call]}""")
        }.isFailure)
    }

    @Test fun reasoningChunksAreNotVisibleTextAndDeepgramAutoIsExplicit() {
        val result = OpenAIResponseParser.parse("""
            {"choices":[{"finish_reason":"stop","message":{"content":[
              {"type":"thinking","text":"internal"},
              {"type":"text","text":"Two eggs"}
            ]}}]}
        """.trimIndent())
        assertEquals("Two eggs", result.text)
        val detected = DeepgramClient.listenUrl("nova-3", null)
        assertEquals("true", detected.queryParameter("detect_language"))
        assertNull(detected.queryParameter("language"))
        val fixed = DeepgramClient.listenUrl("nova-3", "zh-TW")
        assertNull(fixed.queryParameter("detect_language"))
        assertEquals("zh-TW", fixed.queryParameter("language"))
    }
}
