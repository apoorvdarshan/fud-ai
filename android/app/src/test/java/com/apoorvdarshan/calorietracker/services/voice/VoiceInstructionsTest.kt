package com.apoorvdarshan.calorietracker.services.voice

import com.apoorvdarshan.calorietracker.services.hosted.HostedVoiceQuota
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class VoiceInstructionsTest {
    private fun input(
        provider: LiveProvider = LiveProvider.GEMINI,
        autoCapture: Boolean = false,
        confirmSaves: Boolean = true,
        language: String? = null,
        userContext: String = ""
    ) = VoiceInstructionInput(
        context = VoiceSessionContext(provider, confirmSaves = confirmSaves, autoCapture = autoCapture),
        today = LocalDate.parse("2026-10-16"),
        time = LocalTime.of(13, 5),
        timeZone = "Asia/Kolkata",
        replyLanguage = language,
        metric = true,
        coachSummary = listOf("## User profile", "- Age: 30"),
        todayLine = "1200 of 2000 kcal.",
        userContext = userContext
    )

    @Test
    fun instructionsCarryPersonaRulesDataAndDate() {
        val text = VoiceInstructions.build(input())
        assertTrue(text.startsWith("You are Fud AI's voice assistant and coach"))
        assertTrue(text.contains("Your name for this session is \"Brulee\""))
        assertTrue(text.contains("Reply in the language the user speaks."))
        assertTrue(text.contains("Never invent numbers"))
        assertTrue(text.contains("user_confirmed: true unless the user agreed"))
        assertTrue(text.contains("Ask one question at a time."))
        assertTrue(text.contains("read back the main items and the total calories"))
        assertTrue(text.contains("2026-10-16 (Asia/Kolkata), time 13:05"))
        assertTrue(text.contains("So far today: 1200 of 2000 kcal."))
        assertTrue(text.contains("## User profile"))
        assertTrue(text.contains(VoiceToolExecutor.APP_NOTE))
        assertFalse(text.contains("User-provided context"))
    }

    @Test
    fun mascotSettingsRoundTripAndReachTheSessionWithoutChangingConfirmation() {
        val defaults = VoiceMascotPreferences()
        assertEquals("Brulee", defaults.profile.name)
        assertEquals(VoiceMascotColor.ORIGINAL, defaults.profile.options.color)
        assertEquals(8, VoiceMascot.entries.size)

        val changed = defaults.editing(VoiceMascot.ALIEN) {
            it.copy(name = "  Nova\n\t星  ", color = VoiceMascotColor.PURPLE, personality = VoiceMascotPersonality.PLAYFUL)
        }.editing(VoiceMascot.JAM) { it.copy(name = "Toastie") }.copy(selected = VoiceMascot.ALIEN)
        val reloaded = VoiceMascotPreferences.decode(changed.encode())
        assertEquals("Nova 星", reloaded.profile.name)
        assertEquals(VoiceMascotColor.PURPLE, reloaded.profile.options.color)
        assertEquals("Toastie", reloaded.copy(selected = VoiceMascot.JAM).profile.name)
        assertEquals(VoiceMascotColor.ORIGINAL, reloaded.copy(selected = VoiceMascot.JAM).profile.options.color)
        assertEquals(defaults, VoiceMascotPreferences.decode("invalid"))

        val text = VoiceInstructions.build(input().copy(mascot = reloaded.profile))
        assertTrue(text.contains("Your name for this session is \"Nova 星\""))
        assertTrue(text.contains("lighthearted and energetic"))
        assertTrue(text.contains("“How am I doing?”"))
        assertTrue(text.contains("call get_app_state and wait for its result before answering"))
        assertTrue(text.contains("do not treat an empty diary as proof the user consumed nothing"))
        assertTrue(text.contains("Say it only when the user explicitly asks your name or who you are"))
        assertTrue(text.contains("Never call a tool with user_confirmed: true unless the user agreed"))
    }

    @Test
    fun capabilitiesFollowTheProviderAndSettings() {
        assertTrue(VoiceInstructions.build(input(LiveProvider.HOSTED)).contains("about one frame a second"))
        assertTrue(VoiceInstructions.build(input(LiveProvider.OPENAI)).contains("latest frame each time the user speaks"))
        val xai = VoiceInstructions.build(input(LiveProvider.XAI, autoCapture = true))
        assertTrue(xai.contains("can't see camera frames"))
        assertFalse(xai.contains("Auto-capture is on"))
        assertTrue(VoiceInstructions.build(input(autoCapture = true)).contains("Auto-capture is on"))
        assertTrue(VoiceInstructions.build(input(autoCapture = false)).contains("Auto-capture is off"))
        assertTrue(VoiceInstructions.build(input(confirmSaves = false)).contains("Undo for 8 seconds"))
    }

    @Test
    fun languageAndUserContextAreIncluded() {
        val text = VoiceInstructions.build(input(language = "German", userContext = "I'm vegetarian."))
        assertTrue(text.contains("Reply in German unless the user asks for another language."))
        assertTrue(text.contains("## User-provided context\nI'm vegetarian."))
        assertEquals("Portuguese (Brazil)", VoiceInstructions.languageName("pt-BR"))
        assertEquals("German", VoiceInstructions.languageName("de"))
    }

    @Test
    fun instructionsFitTheHostedLimit() {
        val long = VoiceInstructions.build(input(userContext = "x".repeat(80_000)))
        assertEquals(VoiceInstructions.MAX_CHARS, long.length)
    }

    @Test
    fun todayLineShowsProgressAgainstGoals() {
        assertEquals(
            "1240 of 2100 kcal; protein 80 of 150 g; carbs 120 of 220 g; fat 40 of 70 g; water 1000 of 2000 ml.",
            VoiceInstructions.todayLine(1240, 80.4, 120.0, 40.9, 2100, 150, 220, 70, 1000, 2000)
        )
        assertEquals(
            "0 kcal; protein 0 g; carbs 0 g; fat 0 g.",
            VoiceInstructions.todayLine(0, 0.0, 0.0, 0.0, null, null, null, null, null, null)
        )
    }

    @Test
    fun hostedVoiceQuotaIsReadWhereverTheWorkerPutsIt() {
        val top = HostedVoiceQuota.fromQuotaResponse(
            """{"quota":{"plan":"plus"},"voice":{"month":"2026-10","secondsUsed":300,"secondsLimit":1200,"creditBank":8}}"""
        )!!
        assertEquals(900, top.allowanceSecondsLeft)
        assertEquals(120, top.creditSeconds)
        assertEquals(1_020, top.totalSecondsLeft)
        assertEquals("2026-10", top.month)

        val nested = HostedVoiceQuota.fromQuotaResponse("""{"quota":{"voice":{"secondsUsed":1300,"secondsLimit":1200,"creditBank":0}}}""")!!
        assertEquals(0, nested.totalSecondsLeft)
        assertNull(HostedVoiceQuota.fromQuotaResponse("""{"quota":{"plan":"pro"}}"""))
        assertNull(HostedVoiceQuota.fromQuotaResponse("not json"))
    }
}
