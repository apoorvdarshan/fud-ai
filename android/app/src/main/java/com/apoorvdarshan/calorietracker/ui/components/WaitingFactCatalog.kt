package com.apoorvdarshan.calorietracker.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
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
    private data class Deck(var seed: Long, var order: List<Int>, var offset: Int)

    private var catalog: WaitingFactCatalog? = null
    private val ready = ConcurrentHashMap<String, Prepared>()

    /** On the IO dispatcher: parse the asset and load this language's saved deck before publishing it. */
    @Synchronized
    fun prepare(context: Context, language: String) {
        if (ready.containsKey(language)) return
        val data = catalog ?: runCatching {
            context.assets.open("analysis_nutrition_facts.json").bufferedReader().use {
                WaitingFactCatalog.decode(it.readText())
            }
        }.getOrDefault(WaitingFactCatalog.empty).also { catalog = it }
        if (data.foods.isEmpty()) return
        val measured = data.measurements(language)
        val prefs = context.getSharedPreferences("waiting_fact_deck", Context.MODE_PRIVATE)
        val key = "v1.$language"
        val previous = prefs.getString(key, null)?.split(':')
            ?.takeIf { it.size == 3 && it[0] == data.revision }
        val savedSeed = previous?.get(1)?.toLongOrNull()
        val savedOffset = previous?.get(2)?.toIntOrNull()
        val count = WaitingFactDeck.EDITORIAL_COUNT + measured.size
        val resuming = savedSeed != null && savedOffset != null && savedOffset in 0 until count
        val seed = if (resuming) checkNotNull(savedSeed) else kotlin.random.Random.nextLong()
        val deck = Deck(seed, WaitingFactDeck.order(measured.size, seed), if (resuming) checkNotNull(savedOffset) else 0)
        ready[language] = Prepared(data.revision, language, prefs, measured, deck)
    }

    /** Returns at once; a cold fallback neither blocks nor advances the full deck. */
    fun pick(language: String, savedToken: String): Selection {
        val fallback = savedToken.removePrefix("fallback:")
            .takeIf { savedToken.startsWith("fallback:") }?.toIntOrNull()
            ?.takeIf { it in 0 until WaitingFactDeck.EDITORIAL_COUNT }
        if (fallback != null) return Selection(savedToken, fallback, null)
        return ready[language]?.pick(savedToken) ?: run {
            val index = kotlin.random.Random.nextInt(WaitingFactDeck.EDITORIAL_COUNT)
            Selection("fallback:$index", index, null)
        }
    }

    private class Prepared(
        private val revision: String,
        private val language: String,
        private val prefs: SharedPreferences,
        private val measured: List<WaitingFactCatalog.Measurement>,
        private val deck: Deck
    ) {
        private val prefix = "$revision:$language:"

        @Synchronized
        fun pick(savedToken: String): Selection {
            val restored = savedToken.takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)?.toIntOrNull()?.takeIf { it in deck.order.indices }
            if (restored != null) return selection(restored)
            if (deck.offset >= deck.order.size) {
                deck.seed = kotlin.random.Random.nextLong()
                deck.order = WaitingFactDeck.order(measured.size, deck.seed)
                deck.offset = 0
            }
            val index = deck.order[deck.offset++]
            prefs.edit().putString("v1.$language", "$revision:${deck.seed}:${deck.offset}").apply()
            return selection(index)
        }

        private fun selection(index: Int) =
            Selection("$prefix$index", index, measured.getOrNull(index - WaitingFactDeck.EDITORIAL_COUNT))
    }
}
