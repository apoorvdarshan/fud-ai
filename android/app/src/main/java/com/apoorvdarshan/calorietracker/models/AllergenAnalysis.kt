package com.apoorvdarshan.calorietracker.models

import android.content.Context
import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import java.text.Normalizer
import java.util.Locale

enum class AllergenAssessment {
    DECLARED_ALLERGEN_MATCH,
    MAY_CONTAIN,
    POSSIBLE_ALLERGEN,
    UNABLE_TO_ASSESS
}

data class AllergenAnalysis(
    val assessment: AllergenAssessment,
    val matchedSensitivities: List<String> = emptyList(),
    val evidence: List<String> = emptyList()
) {
    /** The assessment in the user's language, followed by the matched sensitivities. */
    fun summary(context: Context): String = buildString {
        append(context.getString(assessment.displayNameRes))
        if (matchedSensitivities.isNotEmpty()) {
            append(": ")
            append(matchedSensitivities.joinToString(", "))
        }
    }

    companion object {
        private val specificMembers = mapOf(
            "almond" to "tree nut", "cashew" to "tree nut", "hazelnut" to "tree nut",
            "macadamia" to "tree nut", "pecan" to "tree nut", "pistachio" to "tree nut", "walnut" to "tree nut",
            "shrimp" to "shellfish", "prawn" to "shellfish", "crab" to "shellfish", "lobster" to "shellfish"
        )

        private fun specificMember(normalized: String): String? = when {
            normalized in specificMembers -> normalized
            normalized.endsWith("s") -> normalized.dropLast(1).takeIf { it in specificMembers }
            else -> null
        }

        private fun isBroadCategory(value: String): Boolean {
            val id = CanonicalFoodAllergens.idFor(value)
            return (id == "tree nut" || id == "shellfish") && specificMember(normalize(value)) == null
        }

        fun evaluate(entry: FoodEntry, sensitivities: List<String>): AllergenAnalysis {
            val configured = sensitivities.map { it to normalize(it) }.filter { it.second.isNotEmpty() }
            if (configured.isEmpty()) return AllergenAnalysis(AllergenAssessment.UNABLE_TO_ASSESS)

            val sources = listOf(
                Triple(AllergenAssessment.DECLARED_ALLERGEN_MATCH, "Declared product allergens", entry.productMetadata?.allergens.orEmpty()),
                Triple(AllergenAssessment.MAY_CONTAIN, "Trace warning", entry.productMetadata?.traces.orEmpty()),
                Triple(AllergenAssessment.POSSIBLE_ALLERGEN, "Declared product allergens", entry.productMetadata?.allergens.orEmpty().filter(::isBroadCategory)),
                Triple(AllergenAssessment.POSSIBLE_ALLERGEN, "Trace warning", entry.productMetadata?.traces.orEmpty().filter(::isBroadCategory)),
                Triple(AllergenAssessment.POSSIBLE_ALLERGEN, "Ingredient label", listOfNotNull(entry.productMetadata?.ingredientsText)),
                Triple(AllergenAssessment.POSSIBLE_ALLERGEN, "Possible allergen", CanonicalFoodAllergens.clean(
                    entry.aiAllergens + entry.ingredients.flatMap { it.aiAllergens }
                )),
                Triple(AllergenAssessment.POSSIBLE_ALLERGEN, "Ingredient name", entry.ingredients.map { it.name }),
                Triple(AllergenAssessment.POSSIBLE_ALLERGEN, "Food name", listOf(entry.name))
            )

            for ((assessment, source, values) in sources) {
                val matches = configured.mapNotNull { (original, sensitivity) ->
                    if (values.any { matches(sensitivity, it, allowBroadCategory = assessment == AllergenAssessment.POSSIBLE_ALLERGEN) }) {
                        original.trim()
                    } else null
                }.distinct()
                if (matches.isNotEmpty()) {
                    return AllergenAnalysis(assessment, matches, listOf(source))
                }
            }
            return AllergenAnalysis(AllergenAssessment.UNABLE_TO_ASSESS)
        }

        /**
         * Whether [candidate] mentions any of [sensitivities], with the same aliases and plural
         * handling as [evaluate]. Used to highlight ingredient label entries.
         */
        fun mentionsAny(sensitivities: List<String>, candidate: String): Boolean =
            sensitivities.map(::normalize).filter(String::isNotEmpty).any { matches(it, candidate) }

        internal fun normalize(value: String): String {
            val folded = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace("\\p{Mn}+".toRegex(), "")
                .lowercase(Locale.ROOT)
            return folded
                .replace("^[a-z]{2}:".toRegex(), "")
                .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
                .trim()
        }

        private fun matches(sensitivity: String, candidate: String, allowBroadCategory: Boolean = true): Boolean {
            val normalizedCandidate = normalize(candidate)
            val candidateTokens = normalizedCandidate.split(" ").filter(String::isNotEmpty)
            val sensitivityTokens = normalize(sensitivity).split(" ").filter(String::isNotEmpty)
            if (sensitivityTokens.isEmpty()) return false
            val aliases = mapOf(
                "milk" to setOf("milk", "dairy", "casein", "whey", "lactose"),
                "dairy" to setOf("milk", "dairy", "casein", "whey", "lactose"),
                "soy" to setOf("soy", "soya", "soybean"),
                "soya" to setOf("soy", "soya", "soybean"),
                "peanut" to setOf("peanut", "groundnut"),
                "peanuts" to setOf("peanut", "groundnut"),
                "tree nut" to setOf("almond", "cashew", "hazelnut", "macadamia", "pecan", "pistachio", "walnut"),
                "nuts" to setOf("almond", "cashew", "hazelnut", "macadamia", "pecan", "pistachio", "walnut"),
                "wheat" to setOf("wheat", "gluten"),
                "gluten" to setOf("wheat", "gluten", "rye", "barley", "oat")
            )
            val key = sensitivityTokens.joinToString(" ")
            val specificSensitivity = specificMember(key)
            val specificCandidate = specificMember(normalizedCandidate)
            val canonical = specificSensitivity?.let(specificMembers::get) ?: CanonicalFoodAllergens.idFor(key)
            val candidateId = specificCandidate?.let(specificMembers::get) ?: CanonicalFoodAllergens.idFor(normalizedCandidate)
            if (canonical != null && canonical == candidateId) {
                if (specificSensitivity == null) return true
                // A category alone is possible evidence, not a declaration naming the food.
                return if (specificCandidate == null) allowBroadCategory else specificSensitivity == specificCandidate
            }
            if (canonical == "gluten" && candidateId == "wheat") return true
            val terms = aliases[key] ?: sensitivityTokens.toSet()
            return terms.any { term ->
                val termTokens = term.split(" ")
                if (termTokens.size == 1) {
                    candidateTokens.any { tokenMatches(termTokens[0], it) }
                } else {
                    candidateTokens.windowed(termTokens.size).any { window ->
                        window.zip(termTokens).all { (candidate, expected) -> tokenMatches(expected, candidate) }
                    }
                }
            } || sensitivityTokens.all { expected ->
                candidateTokens.any { tokenMatches(expected, it) }
            }
        }

        private fun tokenMatches(term: String, candidate: String): Boolean =
            term == candidate ||
                term + "s" == candidate ||
                (candidate.endsWith("s") && candidate.dropLast(1) == term)
    }
}

val AllergenAssessment.displayNameRes: Int
    @StringRes get() = when (this) {
        AllergenAssessment.DECLARED_ALLERGEN_MATCH -> R.string.allergen_assessment_declared
        AllergenAssessment.MAY_CONTAIN -> R.string.allergen_assessment_may_contain
        AllergenAssessment.POSSIBLE_ALLERGEN -> R.string.allergen_assessment_possible
        AllergenAssessment.UNABLE_TO_ASSESS -> R.string.allergen_assessment_unable
    }

fun FoodEntry.allergenAnalysis(sensitivities: List<String>): AllergenAnalysis =
    AllergenAnalysis.evaluate(this, sensitivities)
