package com.apoorvdarshan.calorietracker.ui.components

import android.content.Context
import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import java.util.Locale
import java.util.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class WaitingFactCatalog(val revision: String, val foods: List<Food>) {
    @Serializable
    data class Food(
        val id: Int,
        val preparation: String,
        val names: Map<String, String>,
        val values: Map<String, Double>
    )

    enum class Nutrient(val key: String, @param:StringRes val label: Int, val unit: Unit) {
        PROTEIN("protein", R.string.nutrition_label_protein, Unit.GRAM),
        FAT("fat", R.string.nutrition_label_fat, Unit.GRAM),
        CARBS("carbs", R.string.nutrition_label_carbs, Unit.GRAM),
        WATER("water", R.string.nutrition_section_water, Unit.GRAM),
        FIBER("fiber", R.string.nutrition_label_fiber, Unit.GRAM),
        CALCIUM("calcium", R.string.nutrition_label_calcium, Unit.MILLIGRAM),
        IRON("iron", R.string.nutrition_label_iron, Unit.MILLIGRAM),
        MAGNESIUM("magnesium", R.string.nutrition_label_magnesium, Unit.MILLIGRAM),
        POTASSIUM("potassium", R.string.nutrition_label_potassium, Unit.MILLIGRAM),
        SODIUM("sodium", R.string.nutrition_label_sodium, Unit.MILLIGRAM),
        ZINC("zinc", R.string.nutrition_label_zinc, Unit.MILLIGRAM),
        VITAMIN_A("vitamin_a", R.string.nutrition_label_vitamin_a, Unit.MICROGRAM),
        VITAMIN_E("vitamin_e", R.string.nutrition_label_vitamin_e, Unit.MILLIGRAM),
        VITAMIN_D("vitamin_d", R.string.nutrition_label_vitamin_d, Unit.MICROGRAM),
        VITAMIN_C("vitamin_c", R.string.nutrition_label_vitamin_c, Unit.MILLIGRAM),
        FOLATE("folate", R.string.nutrition_label_folate, Unit.MICROGRAM),
        VITAMIN_B12("vitamin_b12", R.string.nutrition_label_vitamin_b12, Unit.MICROGRAM),
        VITAMIN_K("vitamin_k", R.string.nutrition_label_vitamin_k, Unit.MICROGRAM),
        SUGAR("sugar", R.string.nutrition_label_sugar, Unit.GRAM)
    }

    enum class Unit { GRAM, MILLIGRAM, MICROGRAM }

    data class Measurement(val food: String, val preparation: String, val nutrient: Nutrient, val amount: Double)

    fun measurements(language: String): List<Measurement> = buildList {
        for (food in foods) {
            val name = food.names[language] ?: continue
            for (nutrient in Nutrient.entries) {
                val amount = food.values[nutrient.key]?.takeIf { it.isFinite() && it > 0 } ?: continue
                add(Measurement(name, food.preparation, nutrient, amount))
            }
        }
    }

    companion object {
        private val supported = setOf("ar", "az", "cs", "de", "es", "fr", "hi", "it", "ja", "ko", "nl", "pl", "ro", "ru", "tr", "uk")
        private val json = Json { ignoreUnknownKeys = true }
        val empty = WaitingFactCatalog("fallback", emptyList())

        fun language(locale: Locale): String = when (locale.language) {
            "pt" -> "pt-BR"
            "zh" -> "zh-Hans"
            in supported -> locale.language
            else -> "en"
        }

        fun decode(text: String): WaitingFactCatalog = json.decodeFromString(text)
    }
}

internal object WaitingFactDeck {
    const val EDITORIAL_COUNT = 5

    fun order(measurementCount: Int, seed: Long): List<Int> {
        val random = Random(seed)
        fun MutableList<Int>.shuffle() {
            for (i in lastIndex downTo 1) {
                val j = random.nextInt(i + 1)
                val item = this[i]
                this[i] = this[j]
                this[j] = item
            }
        }
        val editorial = (0 until EDITORIAL_COUNT).toMutableList().apply { shuffle() }
        return (EDITORIAL_COUNT until EDITORIAL_COUNT + measurementCount).toMutableList().apply {
            shuffle()
            for ((index, fact) in editorial.withIndex()) add((index * 9).coerceAtMost(size), fact)
        }
    }
}

internal object WaitingFactFeed {
    data class Selection(val token: String, val index: Int, val measurement: WaitingFactCatalog.Measurement?)
    private data class Deck(val seed: Long, val order: List<Int>, var offset: Int)

    private var catalog: WaitingFactCatalog? = null
    private val measurements = mutableMapOf<String, List<WaitingFactCatalog.Measurement>>()
    private val decks = mutableMapOf<String, Deck>()

    /** Called on the IO dispatcher; neither the asset nor preferences are opened on the UI thread. */
    @Synchronized
    fun select(context: Context, language: String, savedToken: String): Selection {
        val data = catalog ?: runCatching {
            context.assets.open("analysis_nutrition_facts.json").bufferedReader().use {
                WaitingFactCatalog.decode(it.readText())
            }
        }.getOrDefault(WaitingFactCatalog.empty).also { catalog = it }
        val measured = measurements.getOrPut(language) { data.measurements(language) }
        val prefix = "${data.revision}:$language:"
        val count = WaitingFactDeck.EDITORIAL_COUNT + measured.size
        val restored = savedToken.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)?.toIntOrNull()?.takeIf { it in 0 until count }
        if (restored != null) return Selection(savedToken, restored, measured.getOrNull(restored - WaitingFactDeck.EDITORIAL_COUNT))

        val prefs = context.getSharedPreferences("waiting_fact_deck", Context.MODE_PRIVATE)
        val key = "v1.$language"
        var deck = decks[language]
        if (deck == null) {
            val previous = prefs.getString(key, null)?.split(':')
            if (previous?.size == 3 && previous[0] == data.revision) {
                val seed = previous[1].toLongOrNull()
                val offset = previous[2].toIntOrNull()
                if (seed != null && offset != null && offset in 0 until count) {
                    deck = Deck(seed, WaitingFactDeck.order(measured.size, seed), offset)
                }
            }
        }
        val active = deck?.takeIf { it.offset < count } ?: run {
            val seed = kotlin.random.Random.nextLong()
            Deck(seed, WaitingFactDeck.order(measured.size, seed), 0)
        }
        val index = active.order[active.offset++]
        decks[language] = active
        prefs.edit().putString(key, "${data.revision}:${active.seed}:${active.offset}").apply()
        return Selection("$prefix$index", index, measured.getOrNull(index - WaitingFactDeck.EDITORIAL_COUNT))
    }
}
