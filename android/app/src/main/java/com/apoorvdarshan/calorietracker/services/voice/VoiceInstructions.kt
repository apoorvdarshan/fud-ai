package com.apoorvdarshan.calorietracker.services.voice

import java.time.LocalDate
import java.time.LocalTime
import com.apoorvdarshan.calorietracker.models.AppLanguage

/** Everything one session's instructions are built from. */
data class VoiceInstructionInput(
    val context: VoiceSessionContext,
    val today: LocalDate,
    val time: LocalTime,
    val timeZone: String,
    /** English display name of the reply language, or null to follow the user. */
    val replyLanguage: String?,
    val metric: Boolean,
    /** Coach's profile, goal and forecast summary; empty before onboarding finishes. */
    val coachSummary: List<String>,
    val todayLine: String?,
    val userContext: String
)

/**
 * The live session's system instructions (shared/voice-assistant/README.md,
 * "Instructions"): persona, rules, this session's capabilities, today's totals, and the
 * same profile/goal/forecast summary Coach uses. Model input, so English.
 */
object VoiceInstructions {
    /** The hosted relay accepts up to 60,000 characters. */
    const val MAX_CHARS = 60_000

    fun build(input: VoiceInstructionInput): String {
        val lines = mutableListOf<String>()
        lines += "You are Fud AI's voice assistant and coach, inside the Fud AI calorie tracking app. " +
            "You talk with the user in real time by voice. Be warm, encouraging and brief: usually one or two short sentences. " +
            (input.replyLanguage?.let { "Reply in $it unless the user asks for another language." }
                ?: "Reply in the language the user speaks.")
        lines += ""
        lines += "## How to act"
        lines += "- Say what you are about to do in a few words, then do it."
        lines += "- Use the tools for anything in the app (logging, editing, settings, moving between screens) instead of telling the user where to tap. The user sees each step happen in the app."
        lines += "- Never invent numbers. Use the data tools (get_app_state, get_food_entries, get_calorie_totals and the others) for anything about the user's data."
        lines += "- Ask one question at a time."
        lines += "- After a draft (capture_photo, pick_photos, scan_barcode, describe_food or describe_workout), read back the main items and the total calories, then ask whether to log it."
        lines += "- Never call a tool with user_confirmed: true unless the user agreed to that exact change. When a tool answers confirmation_required, the app shows a card with Confirm and Cancel; ask the user, and call again with user_confirmed: true only after they agree."
        lines += "- Messages starting with ${VoiceToolExecutor.APP_NOTE} come from the app, not the user: they report what the user tapped on a card. Never repeat an action they say is done."
        lines += "- Dates are the user's local calendar. Use yyyy-MM-dd, or today / yesterday where a tool accepts them."
        lines += "- Avoid medical advice; suggest a doctor when it matters."
        lines += "- When the user says goodbye or asks you to stop, say a short goodbye and call end_conversation."
        lines += ""
        lines += "## This session"
        lines += "- Camera: " + cameraLine(input.context)
        lines += "- capture_photo closes the camera after taking the image. Reopen it only for another camera task. When a camera task is finished or the user moves on, call close_camera and continue talking. Finishing a tool is not a request to end_conversation."
        lines += "- Confirm before saving: " + if (input.context.confirmSaves) {
            "on. Saving tools answer confirmation_required until the user agrees."
        } else {
            "off. Saves happen right away and the user can tap Undo for 8 seconds. Deleting always needs the user's OK."
        }
        lines += "- Workout diary: " + if (input.context.workoutDiaryEnabled) "on." else "off; workout tools are not available."
        lines += "- Units: " + if (input.metric) "metric (kg, cm, ml)." else "imperial (lb, ft/in); water can still be any unit."
        lines += ""
        lines += "## Now"
        lines += "- Today: ${VoiceArguments.formatDate(input.today)} (${input.timeZone}), time ${VoiceArguments.formatTime(input.time)}."
        input.todayLine?.let { lines += "- So far today: $it" }
        if (input.coachSummary.isNotEmpty()) {
            lines += ""
            lines += input.coachSummary
        }
        val custom = input.userContext.trim()
        if (custom.isNotEmpty()) {
            lines += ""
            lines += "## User-provided context"
            lines += custom
        }
        return lines.joinToString("\n").take(MAX_CHARS)
    }

    private fun cameraLine(context: VoiceSessionContext): String {
        val frames = when (context.cameraFrames) {
            CameraFrames.CONTINUOUS -> "while it is open you see about one frame a second."
            CameraFrames.PER_TURN -> "while it is open you get the latest frame each time the user speaks."
            CameraFrames.NONE -> "you can't see camera frames, so ask the user to tell you when the food is in frame."
        }
        val capture = when {
            context.cameraFrames == CameraFrames.NONE ->
                "Call capture_photo when the user says the food is ready."
            context.autoCapture ->
                "Auto-capture is on: call capture_photo as soon as the food is clearly in frame."
            else ->
                "Auto-capture is off: wait until the user says to take the photo, then call capture_photo."
        }
        return "open_camera shows it inside the assistant; $frames $capture"
    }

    /** "1,240 of 2,100 kcal; protein 80 of 150 g; ..." for the instructions. */
    fun todayLine(
        calories: Int,
        protein: Double,
        carbs: Double,
        fat: Double,
        calorieGoal: Int?,
        proteinGoal: Int?,
        carbsGoal: Int?,
        fatGoal: Int?,
        waterMl: Int?,
        waterGoalMl: Int?
    ): String {
        fun part(label: String, value: Double, goal: Int?, unit: String): String =
            "$label ${value.toInt()}" + (goal?.let { " of $it" } ?: "") + " $unit"
        val parts = mutableListOf(
            "$calories" + (calorieGoal?.let { " of $it" } ?: "") + " kcal",
            part("protein", protein, proteinGoal, "g"),
            part("carbs", carbs, carbsGoal, "g"),
            part("fat", fat, fatGoal, "g")
        )
        if (waterMl != null) parts += "water $waterMl" + (waterGoalMl?.let { " of $it" } ?: "") + " ml"
        return parts.joinToString("; ") + "."
    }

    /** English name for a reply-language tag (`pt-BR` → "Portuguese (Brazil)"). */
    fun languageName(tag: String): String =
        AppLanguage.englishName(tag)
}
