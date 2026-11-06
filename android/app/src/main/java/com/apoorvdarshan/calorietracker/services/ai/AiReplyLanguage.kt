package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.AppLanguage

/**
 * Asks the model to answer in the language the app is shown in, so a Czech user gets Czech
 * food names, reasons and Coach replies. English — the default resources — adds nothing, so
 * English users' prompts are unchanged. Identifiers the app matches in code (unit ids,
 * exercise ids, library search queries) are explicitly kept in English.
 */
object AiReplyLanguage {
    /** The instruction for the language the app is shown in, or null when that is English. */
    fun instruction(): String? = instruction(AppLanguage.shownTag())

    fun instruction(languageTag: String): String? {
        if (isEnglish(languageTag)) return null
        val language = AppLanguage.englishName(languageTag)
        return "Reply language: write every human-readable text value — food and ingredient names, reasons, advice, questions, instructions — in $language. " +
            "Keep JSON keys, numbers and every identifier or enumerated value exactly as the format specifies, in English: " +
            "unit identifiers (slice, cup, tbsp, ml, kg, lbs), kind, intensity, exercise_id, category, equipment and muscle values, " +
            "and exercise-library search queries."
    }

    /** [prompt] with [instruction] in front; the prompt itself when the app is shown in English. */
    fun prompt(prompt: String, instruction: String? = instruction()): String =
        if (instruction == null) prompt else "$instruction\n\n$prompt"

    /** The language name for "Answer in plain …" in the Coach system prompt. */
    fun plainLanguageName(languageTag: String = AppLanguage.shownTag()): String =
        if (isEnglish(languageTag)) "English" else AppLanguage.englishName(languageTag)

    private fun isEnglish(languageTag: String): Boolean =
        languageTag.substringBefore('-').substringBefore('_').equals("en", ignoreCase = true)
}
