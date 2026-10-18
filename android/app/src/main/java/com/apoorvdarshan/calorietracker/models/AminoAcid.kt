package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The nine essential amino acids (#491). With the opt-in Amino acids setting on, AI food
 * analyses also return these as gram estimates. They are stored in
 * [FoodEntry.supplementalNutrients] under [storageKey], so they persist, scale with servings,
 * export and share like the other extra nutrients; entries logged before the setting (or while
 * it was off) simply have none. Mirrors iOS AminoAcid.
 *
 * [referenceMgPerKgPerDay] is the WHO/FAO/UNU 2007 (WHO Technical Report Series 935) adult
 * requirement. Methionine uses the methionine + cysteine value and phenylalanine the
 * phenylalanine + tyrosine value.
 */
enum class AminoAcid(
    val storageKey: String,
    val displayNameRes: Int,
    val referenceMgPerKgPerDay: Double
) {
    HISTIDINE("histidine", R.string.nutrition_label_histidine, 10.0),
    ISOLEUCINE("isoleucine", R.string.nutrition_label_isoleucine, 20.0),
    LEUCINE("leucine", R.string.nutrition_label_leucine, 39.0),
    LYSINE("lysine", R.string.nutrition_label_lysine, 30.0),
    METHIONINE("methionine", R.string.nutrition_label_methionine, 15.0),
    PHENYLALANINE("phenylalanine", R.string.nutrition_label_phenylalanine, 25.0),
    THREONINE("threonine", R.string.nutrition_label_threonine, 15.0),
    TRYPTOPHAN("tryptophan", R.string.nutrition_label_tryptophan, 4.0),
    VALINE("valine", R.string.nutrition_label_valine, 26.0);

    /** Key in the AI JSON response (`<apiKey>_per_100g` for nutrition labels). */
    val apiKey: String get() = storageKey

    /** Daily reference intake in grams for this body weight; null without a usable weight. Never a goal. */
    fun referenceGrams(bodyWeightKg: Double): Double? {
        if (!bodyWeightKg.isFinite() || bodyWeightKg <= 0) return null
        return referenceMgPerKgPerDay * bodyWeightKg / 1_000.0
    }
}

object AminoAcidEstimates {
    /** No single food comes near this many grams of one amino acid. */
    const val MAX_GRAMS = 1_000.0

    /** Appended to food-analysis prompts only while the setting is on. */
    val foodPromptInstruction: String
        get() = "Also include these keys in the same JSON object: ${promptKeys("")}. They are your estimate of the grams of each essential amino acid in the analyzed amount, based on its protein sources. Use null for any amino acid you cannot estimate."

    /** Appended to the nutrition-label prompt only while the setting is on. */
    val labelPromptInstruction: String
        get() = "Also include these keys in the same JSON object: ${promptKeys("_per_100g")}. They are the grams of each essential amino acid per 100g: use printed values when the label lists them, otherwise estimate them from the product's protein sources. Use null for any amino acid you cannot estimate."

    private fun promptKeys(suffix: String): String =
        AminoAcid.values().joinToString(",") { "\"${it.apiKey}$suffix\":0.0" }

    /** Users who never opt in keep exactly the same prompt. */
    fun prompt(base: String, instruction: String?, enabled: Boolean): String =
        if (enabled && instruction != null) "$base\n$instruction" else base

    /**
     * Valid amino acid estimates from an AI response, keyed by storage key. A missing or invalid
     * value (not a number, negative, non-finite, or more grams than [limitGrams], such as the
     * food's own weight) is dropped and never fails the analysis.
     */
    fun parse(text: String, keySuffix: String = "", limitGrams: Double? = null): Map<String, Double> {
        val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return parse(json, keySuffix, limitGrams)
    }

    fun parse(json: JsonObject, keySuffix: String = "", limitGrams: Double? = null): Map<String, Double> {
        val limit = limitGrams?.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(MAX_GRAMS) ?: MAX_GRAMS
        return AminoAcid.values().mapNotNull { aminoAcid ->
            number(json[aminoAcid.apiKey + keySuffix])
                ?.takeIf { it >= 0 && it <= limit }
                ?.let { aminoAcid.storageKey to it }
        }.toMap()
    }

    /** Daily grams per amino acid. Only amino acids that at least one entry carries appear. */
    fun dailyTotals(entries: List<FoodEntry>): Map<AminoAcid, Double> {
        val totals = linkedMapOf<AminoAcid, Double>()
        for (entry in entries) {
            for (aminoAcid in AminoAcid.values()) {
                val value = entry.supplementalNutrients[aminoAcid.storageKey] ?: continue
                totals[aminoAcid] = (totals[aminoAcid] ?: 0.0) + value
            }
        }
        return totals
    }

    /** [FoodEntry.supplementalNutrients] keys that hold amino acid estimates. */
    private val storageKeys: Set<String> = AminoAcid.values().map { it.storageKey }.toSet()

    /**
     * Scales and rounds [FoodEntry.supplementalNutrients] wherever they are reviewed, edited,
     * scaled from a label or exported. The other extras keep 0.1 g, but one food often has only a
     * few hundredths of a gram of an amino acid (a banana has about 0.01 g tryptophan), so amino
     * acid estimates keep milligrams instead of rounding to 0. Mirrors iOS.
     */
    fun roundedSupplementalNutrients(values: Map<String, Double>, factor: Double = 1.0): Map<String, Double> =
        values.mapValues { (key, value) -> roundedSupplementalNutrient(key, value * factor) }

    fun roundedSupplementalNutrient(key: String, value: Double): Double {
        val steps = if (key in storageKeys) 1_000.0 else 10.0
        return (value * steps).roundToLong() / steps
    }

    /** Small amounts (tryptophan references are a few tenths of a gram) keep two decimals. */
    fun formattedGrams(grams: Double): String =
        String.format(Locale.getDefault(), if (grams < 1) "%.2f" else "%.1f", grams)

    /** JSON numbers or numeric strings; booleans, arrays, objects and non-finite values are not numbers. */
    private fun number(value: Any?): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
