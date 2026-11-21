package com.apoorvdarshan.calorietracker.services.voice

import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.WorkoutTextDraft
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.ui.theme.AppThemeColor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Results

/** Error codes from the contract's tool conventions. */
enum class ToolError(val wireValue: String) {
    INVALID_ARGUMENTS("invalid_arguments"),
    NOT_FOUND("not_found"),
    UNSUPPORTED("unsupported"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    CAMERA_UNAVAILABLE("camera_unavailable"),
    PERMISSION_DENIED("permission_denied"),
    ANALYSIS_FAILED("analysis_failed"),
    QUOTA_EXCEEDED("quota_exceeded"),
    CONFIRMATION_REQUIRED("confirmation_required"),
    WORKOUT_DIARY_OFF("workout_diary_off")
}

/** `{"ok": true, ...}` or `{"ok": false, "error": "<code>", "message": "<short English text>"}`. */
object ToolResults {
    fun ok(build: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("ok", true)
        build()
    }

    fun error(error: ToolError, message: String, build: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", error.wireValue)
        put("message", message)
        build()
    }

    fun isOk(result: JsonObject): Boolean = (result["ok"] as? JsonPrimitive)?.booleanOrNull == true
}

/** Thrown inside a handler to answer with an error result. */
class ToolFailure(val error: ToolError, message: String) : Exception(message) {
    val result: JsonObject get() = ToolResults.error(error, message.orEmpty())
}

internal fun fail(error: ToolError, message: String): Nothing = throw ToolFailure(error, message)

// MARK: - Arguments

/**
 * Lenient readers over a tool call's arguments. Models sometimes send numbers as
 * strings or booleans as "true"; both are accepted.
 */
class ToolArgs(val raw: JsonObject) {
    fun string(name: String): String? = (raw[name] as? JsonPrimitive)
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun requireString(name: String): String =
        string(name) ?: fail(ToolError.INVALID_ARGUMENTS, "$name is required.")

    fun number(name: String): Double? {
        val primitive = raw[name] as? JsonPrimitive ?: return null
        if (primitive is kotlinx.serialization.json.JsonNull) return null
        return (primitive.doubleOrNull ?: primitive.content.trim().replace(',', '.').toDoubleOrNull())
            ?.takeIf { it.isFinite() }
            ?: fail(ToolError.INVALID_ARGUMENTS, "$name must be a number.")
    }

    fun requireNumber(name: String): Double =
        number(name) ?: fail(ToolError.INVALID_ARGUMENTS, "$name is required.")

    fun bool(name: String): Boolean? {
        val primitive = raw[name] as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: VoiceArguments.parseBoolean(primitive.content)
    }

    fun obj(name: String): JsonObject? = raw[name] as? JsonObject

    val userConfirmed: Boolean get() = bool("user_confirmed") == true

    /** The arguments without `user_confirmed`, in a stable order, to recognise the same change. */
    fun signature(tool: String): String = tool + ":" + JsonObject(raw.filterKeys { it != "user_confirmed" }.toSortedMap()).toString()
}

/** Pure parsing for dates, times and units. */
object VoiceArguments {
    private val isoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** `yyyy-MM-dd`, or `today` / `yesterday` on the user's local calendar. */
    fun parseDate(raw: String, today: LocalDate): LocalDate? = when (raw.trim().lowercase(Locale.ROOT)) {
        "today" -> today
        "yesterday" -> today.minusDays(1)
        else -> runCatching { LocalDate.parse(raw.trim(), isoDate) }.getOrNull()
    }

    /** `HH:mm`, 24-hour. */
    fun parseTime(raw: String): LocalTime? {
        val match = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(raw.trim()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    fun formatDate(date: LocalDate): String = date.format(isoDate)

    fun formatTime(time: LocalTime): String = String.format(Locale.US, "%02d:%02d", time.hour, time.minute)

    /** Milliliters for a water amount: ml, l, oz (fluid), cup = 240 ml, glass = 250 ml. */
    fun waterMilliliters(amount: Double, unit: String): Int? {
        if (!amount.isFinite() || amount <= 0) return null
        val factor = when (unit.trim().lowercase(Locale.ROOT)) {
            "ml", "milliliter", "milliliters" -> 1.0
            "l", "liter", "liters", "litre", "litres" -> 1_000.0
            "oz", "fl oz", "floz", "ounce", "ounces" -> FLUID_OUNCE_ML
            "cup", "cups" -> CUP_ML
            "glass", "glasses" -> GLASS_ML
            else -> return null
        }
        return (amount * factor).roundToInt().takeIf { it > 0 }
    }

    fun weightKilograms(value: Double, unit: String): Double? {
        if (!value.isFinite() || value <= 0) return null
        return when (unit.trim().lowercase(Locale.ROOT)) {
            "kg", "kgs", "kilogram", "kilograms" -> value
            "lb", "lbs", "pound", "pounds" -> value * KILOGRAMS_PER_POUND
            else -> null
        }
    }

    fun parseMeal(raw: String): MealType? = when (raw.trim().lowercase(Locale.ROOT)) {
        "breakfast" -> MealType.BREAKFAST
        "lunch" -> MealType.LUNCH
        "dinner" -> MealType.DINNER
        "snack", "snacks" -> MealType.SNACK
        else -> null
    }

    fun mealWireValue(meal: MealType): String = when (meal) {
        MealType.BREAKFAST -> "breakfast"
        MealType.LUNCH -> "lunch"
        MealType.DINNER -> "dinner"
        MealType.SNACK -> "snack"
        MealType.OTHER -> "other"
    }

    fun parseBoolean(raw: String): Boolean? = when (raw.trim().lowercase(Locale.ROOT)) {
        "true", "on", "yes", "1", "enabled" -> true
        "false", "off", "no", "0", "disabled" -> false
        else -> null
    }

    /** Grams in a serving description such as "1 bowl (250 g)". */
    fun gramsIn(serving: String): Double? =
        Regex("""(\d+(?:[.,]\d+)?)\s*(?:g|grams?)\b""", RegexOption.IGNORE_CASE)
            .find(serving)
            ?.groupValues?.get(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it > 0 }

    const val FLUID_OUNCE_ML = 29.5735295625
    const val CUP_ML = 240.0
    const val GLASS_ML = 250.0
    const val KILOGRAMS_PER_POUND = 0.45359237
}

// MARK: - Settings

/** The keys `update_setting` can change. */
enum class VoiceSettingKey(val wireValue: String) {
    THEME_COLOR("theme_color"),
    APPEARANCE("appearance"),
    UNITS("units"),
    CALORIE_GOAL("calorie_goal"),
    PROTEIN_GOAL_G("protein_goal_g"),
    CARBS_GOAL_G("carbs_goal_g"),
    FAT_GOAL_G("fat_goal_g"),
    WATER_GOAL_ML("water_goal_ml"),
    ASSISTANT_VOICE("assistant_voice"),
    ASSISTANT_AUTO_CAPTURE("assistant_auto_capture"),
    ASSISTANT_CONFIRM_SAVES("assistant_confirm_saves");

    companion object {
        fun fromWire(raw: String?): VoiceSettingKey? = entries.firstOrNull { it.wireValue == raw?.trim() }
    }
}

/** A validated value for one setting. */
sealed interface SettingValue {
    val display: String

    data class ThemeColor(val color: AppThemeColor) : SettingValue {
        override val display: String get() = color.key
    }

    /** `system`, `light`, `dark` or `darkGray` (the stored appearance values). */
    data class Appearance(val mode: String) : SettingValue {
        override val display: String get() = mode
    }

    data class Units(val metric: Boolean) : SettingValue {
        override val display: String get() = if (metric) "metric" else "imperial"
    }

    data class Amount(val value: Int) : SettingValue {
        override val display: String get() = value.toString()
    }

    data class Voice(val id: String) : SettingValue {
        override val display: String get() = id
    }

    data class Toggle(val on: Boolean) : SettingValue {
        override val display: String get() = on.toString()
    }
}

object VoiceSettingValues {
    val calorieRange = 800..6_000
    val macroRange = 10..500
    val waterRange = 50..10_000

    /** Contract keys first; Android's extra theme colours are accepted too. */
    val themeColorKeys: List<String> get() = AppThemeColor.entries.map { it.key }

    /**
     * Validates [raw] for [key]. Failures explain the accepted values so the model can
     * correct itself.
     */
    fun parse(key: VoiceSettingKey, raw: String, voices: List<String>): SettingValue {
        val value = raw.trim()
        return when (key) {
            VoiceSettingKey.THEME_COLOR -> {
                val wanted = normalized(value)
                AppThemeColor.entries.firstOrNull { normalized(it.key) == wanted }
                    ?.let { SettingValue.ThemeColor(it) }
                    ?: fail(ToolError.INVALID_ARGUMENTS, "theme_color must be one of ${themeColorKeys.joinToString(", ")}.")
            }
            VoiceSettingKey.APPEARANCE -> when (value.lowercase(Locale.ROOT)) {
                "system", "auto", "automatic" -> SettingValue.Appearance("system")
                "light" -> SettingValue.Appearance("light")
                "dark" -> SettingValue.Appearance("dark")
                "dark gray", "darkgray", "dark_gray", "dark-gray" -> SettingValue.Appearance("darkGray")
                else -> fail(ToolError.INVALID_ARGUMENTS, "appearance must be system, light, dark or dark gray.")
            }
            VoiceSettingKey.UNITS -> when (value.lowercase(Locale.ROOT)) {
                "metric" -> SettingValue.Units(metric = true)
                "imperial" -> SettingValue.Units(metric = false)
                else -> fail(ToolError.INVALID_ARGUMENTS, "units must be metric or imperial.")
            }
            VoiceSettingKey.CALORIE_GOAL -> SettingValue.Amount(wholeNumber(key, value, calorieRange))
            VoiceSettingKey.PROTEIN_GOAL_G, VoiceSettingKey.CARBS_GOAL_G, VoiceSettingKey.FAT_GOAL_G ->
                SettingValue.Amount(wholeNumber(key, value, macroRange))
            VoiceSettingKey.WATER_GOAL_ML -> SettingValue.Amount(wholeNumber(key, value, waterRange))
            VoiceSettingKey.ASSISTANT_VOICE -> voices.firstOrNull { it.equals(value, ignoreCase = true) }
                ?.let { SettingValue.Voice(it) }
                ?: fail(ToolError.INVALID_ARGUMENTS, "assistant_voice must be one of ${voices.joinToString(", ")}.")
            VoiceSettingKey.ASSISTANT_AUTO_CAPTURE, VoiceSettingKey.ASSISTANT_CONFIRM_SAVES ->
                VoiceArguments.parseBoolean(value)?.let { SettingValue.Toggle(it) }
                    ?: fail(ToolError.INVALID_ARGUMENTS, "${key.wireValue} must be true or false.")
        }
    }

    private fun normalized(value: String): String =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun wholeNumber(key: VoiceSettingKey, value: String, range: IntRange): Int {
        val compact = value.replace(" ", "").replace(" ", "")
        // "2,250" and "2.250" are thousands groupings here; "2.5" is a decimal.
        val grouped = Regex("""-?\d{1,3}(?:[,.]\d{3})+(?!\d)""").find(compact)?.value
        val number = grouped?.replace(",", "")?.replace(".", "")?.toDoubleOrNull()
            ?: Regex("""-?\d+(?:[.,]\d+)?""").find(compact)?.value?.replace(',', '.')?.toDoubleOrNull()
            ?: fail(ToolError.INVALID_ARGUMENTS, "${key.wireValue} must be a number.")
        val rounded = number.roundToInt()
        if (rounded !in range) {
            fail(ToolError.INVALID_ARGUMENTS, "${key.wireValue} must be between ${range.first} and ${range.last}.")
        }
        return rounded
    }
}

// MARK: - Confirmation

object ConfirmationPolicy {
    /** True when nothing may change yet: the user must agree first. */
    fun needsConfirmation(confirm: ToolConfirm, confirmSaves: Boolean, userConfirmed: Boolean): Boolean = when (confirm) {
        ToolConfirm.NEVER -> false
        ToolConfirm.ALWAYS -> !userConfirmed
        ToolConfirm.SETTING -> confirmSaves && !userConfirmed
    }
}

// MARK: - Changes, cards and drafts

/** What a write did or would do, for cards (localized in the UI) and for the model (English). */
sealed interface VoiceChange {
    val modelSummary: String

    data class LogFood(val name: String, val calories: Int, val meal: MealType, val date: LocalDate) : VoiceChange {
        override val modelSummary: String
            get() = "log $name ($calories kcal) to ${VoiceArguments.mealWireValue(meal)} on ${VoiceArguments.formatDate(date)}"
    }

    data class UpdateFood(val name: String, val calories: Int) : VoiceChange {
        override val modelSummary: String get() = "update $name to $calories kcal"
    }

    data class DeleteFood(val name: String, val calories: Int) : VoiceChange {
        override val modelSummary: String get() = "delete $name ($calories kcal)"
    }

    data class LogWater(val milliliters: Int) : VoiceChange {
        override val modelSummary: String get() = "log $milliliters ml of water"
    }

    data class StartFast(val goalHours: Int) : VoiceChange {
        override val modelSummary: String get() = "start a $goalHours-hour fast"
    }

    data class EndFast(val elapsedMinutes: Long) : VoiceChange {
        override val modelSummary: String get() = "end the fast after ${elapsedMinutes / 60} h ${elapsedMinutes % 60} min"
    }

    data class LogWeight(val kilograms: Double, val metric: Boolean) : VoiceChange {
        override val modelSummary: String
            get() = if (metric) String.format(Locale.US, "log a weight of %.1f kg", kilograms)
            else String.format(Locale.US, "log a weight of %.1f lb", kilograms / VoiceArguments.KILOGRAMS_PER_POUND)
    }

    data class LogBodyFat(val percent: Double) : VoiceChange {
        override val modelSummary: String get() = String.format(Locale.US, "log body fat of %.1f%%", percent)
    }

    data class LogWorkout(val exerciseCount: Int, val date: LocalDate) : VoiceChange {
        override val modelSummary: String
            get() = "save a workout with $exerciseCount exercise(s) on ${VoiceArguments.formatDate(date)}"
    }

    data class LogCardio(val activity: String, val minutes: Int) : VoiceChange {
        override val modelSummary: String get() = "log $minutes min of $activity"
    }

    data class UpdateSetting(val key: VoiceSettingKey, val value: String) : VoiceChange {
        override val modelSummary: String get() = "set ${key.wireValue} to $value"
    }
}

/** One line of a draft card. Food rows carry nutrition; workout rows carry a detail line. */
data class DraftItem(
    val name: String,
    val serving: String? = null,
    val calories: Int? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val detail: String? = null
)

data class DraftTotals(val calories: Int, val proteinG: Double, val carbsG: Double, val fatG: Double)

/** Cards in the assistant overlay. */
sealed interface VoiceCard {
    val id: String

    data class Draft(
        override val id: String,
        val draftId: String,
        val isWorkout: Boolean,
        val title: String,
        val items: List<DraftItem>,
        val totals: DraftTotals?,
        val logged: Boolean = false
    ) : VoiceCard

    data class Confirmation(override val id: String, val change: VoiceChange, val destructive: Boolean) : VoiceCard

    data class Undo(
        override val id: String,
        val change: VoiceChange,
        val expiresAtMillis: Long,
        val windowMillis: Long
    ) : VoiceCard
}

/** Drafts live for the session. */
sealed interface VoiceDraft {
    val id: String

    class Food(
        override val id: String,
        val analysis: FoodAnalysis,
        val images: List<ByteArray>,
        val source: FoodSource,
        var loggedEntryIds: List<String> = emptyList()
    ) : VoiceDraft

    class Workout(
        override val id: String,
        val draft: WorkoutTextDraft,
        var logged: Boolean = false
    ) : VoiceDraft
}

class DraftStore {
    private val drafts = LinkedHashMap<String, VoiceDraft>()
    private var next = 1

    fun nextId(): String = "draft_${next++}"

    fun add(draft: VoiceDraft) {
        drafts[draft.id] = draft
    }

    fun get(id: String): VoiceDraft? = drafts[id.trim()]

    fun clear() {
        drafts.clear()
    }
}

/** Undo cards: a restore action that is only offered for [windowMillis] after a write. */
class UndoRegistry(private val now: () -> Long, val windowMillis: Long = WINDOW_MILLIS) {
    class Entry(val id: String, val change: VoiceChange, val expiresAtMillis: Long, val restore: suspend () -> Unit)

    private val entries = LinkedHashMap<String, Entry>()
    private var next = 1

    fun register(change: VoiceChange, restore: suspend () -> Unit): Entry {
        val entry = Entry("undo_${next++}", change, now() + windowMillis, restore)
        entries[entry.id] = entry
        return entry
    }

    /** Removes and returns an entry that has not expired yet. */
    fun take(id: String): Entry? {
        val entry = entries.remove(id) ?: return null
        return entry.takeIf { now() < it.expiresAtMillis }
    }

    /** Drops expired entries and returns their ids. */
    fun expire(): List<String> {
        val current = now()
        val expired = entries.values.filter { current >= it.expiresAtMillis }.map { it.id }
        expired.forEach { entries.remove(it) }
        return expired
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        const val WINDOW_MILLIS = 8_000L
    }
}

internal fun JsonObjectBuilder.putIfNotNull(key: String, value: JsonElement?) {
    if (value != null) put(key, value)
}
