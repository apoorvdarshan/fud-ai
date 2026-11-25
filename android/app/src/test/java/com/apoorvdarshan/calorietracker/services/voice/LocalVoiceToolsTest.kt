package com.apoorvdarshan.calorietracker.services.voice

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LocalVoiceToolsTest {
    private val bundled = VoiceToolCatalog.parse(File("src/main/assets/voice_assistant_tools.json").readText())

    @Test fun localRoutesDoNotLookUpCloudKeysOrBilling() = runBlocking {
        for ((choice, connected) in listOf(VoiceProviderChoice.OFFLINE to false, VoiceProviderChoice.LOCAL_CONNECTED to true)) {
            val result = VoiceProviderResolver.resolveStartup(choice, { error("cloud model") },
                { error("cloud key") }, { error("billing") })
            assertEquals(VoiceAccess.Local(connected), result.access)
            assertNull(result.key)
            assertEquals(choice, VoiceProviderChoice.fromStorage(choice.storageValue))
        }
    }

    @Test fun modelCannotApproveWritesOrInvokeUndeclaredTools() {
        val offline = LocalVoiceTools(bundled, workouts = false, connected = false)
        val connected = LocalVoiceTools(bundled, workouts = true, connected = true)
        assertEquals(ToolConfirm.ALWAYS, offline.executorSpecs.single { it.name == "log_water" }.confirm)
        assertEquals(JsonPrimitive(200), offline.parse(call("log_water", parameter("amount", "200")
            + parameter("unit", "ml")))?.request?.arguments?.get("amount"))
        assertNull(offline.parse(call("log_water", parameter("user_confirmed", "true"))))
        assertNull(offline.parse(call("log_food", parameter("item", """{"name":"Egg","calories":80}"""))))
        assertNull(offline.parse(call("scan_barcode")))
        assertNotNull(connected.parse(call("scan_barcode")))
        assertNull(connected.parse(call("delete_food_entry")))
        assertNull(offline.parse(call("get_workout_history")))
        assertNotNull(connected.parse(call("get_workout_history")))
        assertNull(offline.parse(call("log_water") + call("log_water")))
        assertTrue(connected.declarations.toByteArray().size < 12_000)
    }

    @Test fun spokenWriteRunsOnlyFromItsConfirmationCard() = runBlocking {
        val policy = LocalVoiceTools(bundled, workouts = true, connected = false)
        val ports = FakeVoiceAppPorts()
        val executor = VoiceToolExecutor(ports, policy.executorSpecs,
            VoiceSessionContext(LiveProvider.GEMINI, confirmSaves = false, autoCapture = false, local = true))
        val request = checkNotNull(policy.parse(call("log_water", parameter("amount", "200") + parameter("unit", "ml"))))
        assertEquals(JsonPrimitive("confirmation_required"), executor.execute(request.request)["error"])
        assertTrue(ports.water.isEmpty())
        val card = executor.cards.value.single() as VoiceCard.Confirmation
        executor.confirmCard(card.id)
        assertEquals(200, ports.water.single().milliliters)
    }

    private fun call(name: String, parameters: String = "") =
        "<tool_call>\n<function=$name>\n$parameters</function>\n</tool_call>"
    private fun parameter(name: String, value: String) = "<parameter=$name>\n$value\n</parameter>\n"
}
