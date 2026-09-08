package com.apoorvdarshan.calorietracker.export

import com.apoorvdarshan.calorietracker.R

import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.UserProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

enum class DiaryFormat(val label: String, val ext: String, val mime: String) {
    JSON("JSON", "json", "application/json"),
    MARKDOWN("Markdown", "md", "text/markdown"),
    CSV("CSV", "csv", "text/csv"),
}

enum class DiaryRange(val label: String, val labelRes: Int) {
    TODAY("Today", R.string.export_range_today),
    THIS_WEEK("This week", R.string.export_range_week),
    THIS_MONTH("This month", R.string.export_range_month),
    ALL_TIME("All time", R.string.export_range_all),
    CUSTOM("Custom", R.string.export_range_custom),
}

/**
 * Builds a shareable food-diary file (JSON / Markdown / CSV) from the local log.
 * Pure logic — the caller supplies the entries, the current profile, and a meal
 * display-name resolver (so string resources stay in the UI layer).
 */
object DiaryExporter {

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun LocalDate.of(entry: FoodEntry): Boolean =
        entry.timestamp.atZone(zone).toLocalDate() == this

    fun resolveRange(
        range: DiaryRange,
        customStart: LocalDate,
        customEnd: LocalDate,
        entries: List<FoodEntry>,
        waterEntries: List<WaterEntry> = emptyList(),
    ): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        return when (range) {
            DiaryRange.TODAY -> today to today
            DiaryRange.THIS_WEEK -> today.with(DayOfWeek.MONDAY) to today
            DiaryRange.THIS_MONTH -> today.withDayOfMonth(1) to today
            DiaryRange.ALL_TIME -> {
                val earliest = (entries.map { it.timestamp.atZone(zone).toLocalDate() } + waterEntries.map { it.date.atZone(zone).toLocalDate() }).minOrNull() ?: today
                earliest to today
            }
            DiaryRange.CUSTOM -> customStart to customEnd
        }
    }

    /** Returns (filename, content) or null if nothing is logged in the range. */
    fun build(
        entries: List<FoodEntry>,
        start: LocalDate,
        end: LocalDate,
        format: DiaryFormat,
        profile: UserProfile?,
        mealDisplay: (MealType) -> String,
        waterEntries: List<WaterEntry> = emptyList(),
    ): Pair<String, String>? {
        val lo = if (start.isAfter(end)) end else start
        val hi = if (start.isAfter(end)) start else end

        val byDay: Map<LocalDate, List<FoodEntry>> = entries
            .filter {
                val d = it.timestamp.atZone(zone).toLocalDate()
                !d.isBefore(lo) && !d.isAfter(hi)
            }
            .groupBy { it.timestamp.atZone(zone).toLocalDate() }
            .toSortedMap()
        val water = waterEntries.filter { it.date.atZone(zone).toLocalDate() in lo..hi }
            .sortedBy { it.date }.groupBy { it.date.atZone(zone).toLocalDate() }
        val days = (byDay.keys + water.keys).associateWith { byDay[it].orEmpty() }.toSortedMap()
        if (days.isEmpty()) return null

        val targets = Targets(
            calories = profile?.effectiveCalories ?: 0,
            protein = (profile?.effectiveProtein ?: 0).toDouble(),
            carbs = (profile?.effectiveCarbs ?: 0).toDouble(),
            fat = (profile?.effectiveFat ?: 0).toDouble(),
        )

        val content = when (format) {
            DiaryFormat.JSON -> json(days, lo, hi, targets, water)
            DiaryFormat.MARKDOWN -> markdown(days, lo, hi, targets, mealDisplay, water)
            DiaryFormat.CSV -> csv(days, water)
        }
        val name = "Fud-Food-Diary-${dayFmt.format(lo)}_to_${dayFmt.format(hi)}.${format.ext}"
        return name to content
    }

    // --- internal helpers ---

    private data class Targets(val calories: Int, val protein: Double, val carbs: Double, val fat: Double)

    /** A day's entries grouped by meal in enum order, each sorted by time ascending. */
    private fun meals(dayEntries: List<FoodEntry>): List<Pair<MealType, List<FoodEntry>>> =
        MealType.values().mapNotNull { mt ->
            val items = dayEntries.filter { it.mealType == mt }.sortedBy { it.timestamp }
            if (items.isEmpty()) null else mt to items
        }

    private fun totals(dayEntries: List<FoodEntry>): DoubleArray {
        var cal = 0.0; var p = 0.0; var c = 0.0; var f = 0.0
        for (e in dayEntries) { cal += e.calories; p += e.protein; c += e.carbs; f += e.fat }
        return doubleArrayOf(cal, p, c, f)
    }

    fun sourceLabel(source: FoodSource): String =
        if (source == FoodSource.MANUAL) "manually_edited" else "ai_estimated"

    private fun r1(x: Double): Double = (x * 10).roundToInt() / 10.0
    private fun optionalNumber(x: Double?, missing: String = ""): String = x?.let { r1(it).toString() } ?: missing
    private fun time(entry: FoodEntry): String = timeFmt.format(entry.timestamp.atZone(zone))

    // --- JSON ---

    @Serializable private data class Macro(val calories: Int, val protein_g: Double, val carbs_g: Double, val fat_g: Double)
    @Serializable private data class IngredientDto(
        val name: String, val quantity_g: Double, val calories: Int,
        val protein_g: Double, val carbs_g: Double, val fat_g: Double,
    )
    @Serializable private data class ItemDto(
        val entry_id: String, val name: String, val quantity_g: Double? = null, val calories: Int,
        val protein_g: Double, val carbs_g: Double, val fat_g: Double,
        val sugar_g: Double? = null, val added_sugar_g: Double? = null, val fiber_g: Double? = null,
        val saturated_fat_g: Double? = null, val monounsaturated_fat_g: Double? = null,
        val polyunsaturated_fat_g: Double? = null, val cholesterol_mg: Double? = null,
        val caffeine_mg: Double? = null, val sodium_mg: Double? = null, val potassium_mg: Double? = null, val trans_fat_g: Double? = null,
        val supplemental_nutrients_g: Map<String, Double> = emptyMap(),
        val calcium_mg: Double? = null, val iron_mg: Double? = null, val magnesium_mg: Double? = null,
        val zinc_mg: Double? = null, val vitamin_a_mcg: Double? = null, val vitamin_c_mg: Double? = null,
        val vitamin_d_mcg: Double? = null, val vitamin_b12_mcg: Double? = null,
        val vitamin_e_mg: Double? = null, val vitamin_k_mcg: Double? = null,
        val folate_mcg: Double? = null, val omega3_g: Double? = null,
        val time: String, val source: String, val note: String? = null,
        val ingredients: List<IngredientDto> = emptyList(),
    )
    @Serializable private data class MealDto(val type: String, val items: List<ItemDto>)
    @Serializable private data class WaterDto(val entry_id: String, val time: String, val milliliters: Int)
    @Serializable private data class DayDto(val date: String, val totals: Macro, val targets: Macro, val remaining: Macro, val meals: List<MealDto>, val water_entries: List<WaterDto>, val water_total_ml: Long)
    @Serializable private data class RangeDto(val start: String, val end: String)
    @Serializable private data class MetaDto(val app: String, val format_version: String, val date_range: RangeDto)
    @Serializable private data class Doc(val export: MetaDto, val days: List<DayDto>)

    private val jsonPretty = Json { prettyPrint = true; encodeDefaults = true }

    private fun json(byDay: Map<LocalDate, List<FoodEntry>>, lo: LocalDate, hi: LocalDate, t: Targets, water: Map<LocalDate, List<WaterEntry>>): String {
        val days = byDay.map { (date, dayEntries) ->
            val tot = totals(dayEntries)
            val mealDtos = meals(dayEntries).map { (mt, items) ->
                MealDto(
                    type = mt.name.lowercase(),
                    items = items.map { e ->
                        ItemDto(
                            entry_id = e.id.toString(),
                            name = e.name,
                            quantity_g = e.servingSizeGrams?.let { r1(it) },
                            calories = e.calories,
                            protein_g = r1(e.protein), carbs_g = r1(e.carbs), fat_g = r1(e.fat),
                            sugar_g = e.sugar?.let { r1(it) }, added_sugar_g = e.addedSugar?.let { r1(it) },
                            fiber_g = e.fiber?.let { r1(it) }, saturated_fat_g = e.saturatedFat?.let { r1(it) },
                            monounsaturated_fat_g = e.monounsaturatedFat?.let { r1(it) },
                            polyunsaturated_fat_g = e.polyunsaturatedFat?.let { r1(it) },
                            cholesterol_mg = e.cholesterol?.let { r1(it) }, caffeine_mg = e.caffeine?.let { r1(it) }, sodium_mg = e.sodium?.let { r1(it) },
                            supplemental_nutrients_g = e.supplementalNutrients.mapValues { (_, value) -> r1(value) },
                            potassium_mg = e.potassium?.let { r1(it) }, trans_fat_g = e.transFat?.let { r1(it) },
                            calcium_mg = e.calcium?.let { r1(it) }, iron_mg = e.iron?.let { r1(it) },
                            magnesium_mg = e.magnesium?.let { r1(it) }, zinc_mg = e.zinc?.let { r1(it) },
                            vitamin_a_mcg = e.vitaminA?.let { r1(it) }, vitamin_c_mg = e.vitaminC?.let { r1(it) },
                            vitamin_d_mcg = e.vitaminD?.let { r1(it) }, vitamin_b12_mcg = e.vitaminB12?.let { r1(it) },
                            vitamin_e_mg = e.vitaminE?.let { r1(it) }, vitamin_k_mcg = e.vitaminK?.let { r1(it) },
                            folate_mcg = e.folate?.let { r1(it) }, omega3_g = e.omega3?.let { r1(it) },
                            time = time(e), source = sourceLabel(e.source),
                            note = e.customNote?.takeIf { it.isNotBlank() },
                            ingredients = e.ingredients.map { ingredient ->
                                IngredientDto(
                                    name = ingredient.name,
                                    quantity_g = r1(ingredient.grams),
                                    calories = ingredient.calories,
                                    protein_g = r1(ingredient.protein),
                                    carbs_g = r1(ingredient.carbs),
                                    fat_g = r1(ingredient.fat),
                                )
                            },
                        )
                    },
                )
            }
            DayDto(
                date = dayFmt.format(date),
                totals = Macro(tot[0].roundToInt(), r1(tot[1]), r1(tot[2]), r1(tot[3])),
                targets = Macro(t.calories, t.protein, t.carbs, t.fat),
                remaining = Macro(
                    max(0, t.calories - tot[0].roundToInt()),
                    r1(max(0.0, t.protein - tot[1])),
                    r1(max(0.0, t.carbs - tot[2])),
                    r1(max(0.0, t.fat - tot[3])),
                ),
                meals = mealDtos,
                water_entries = water[date].orEmpty().map { WaterDto(it.id.toString(), timeFmt.format(it.date.atZone(zone)), it.milliliters) },
                water_total_ml = water[date].orEmpty().sumOf { it.milliliters.toLong() },
            )
        }
        val doc = Doc(
            export = MetaDto("Fud AI", "1.5", RangeDto(dayFmt.format(lo), dayFmt.format(hi))),
            days = days,
        )
        return jsonPretty.encodeToString(Doc.serializer(), doc)
    }

    // --- Markdown ---

    private fun markdown(
        byDay: Map<LocalDate, List<FoodEntry>>,
        lo: LocalDate, hi: LocalDate, t: Targets,
        mealDisplay: (MealType) -> String,
        water: Map<LocalDate, List<WaterEntry>>,
    ): String {
        val sb = StringBuilder()
        sb.append("# Food diary export\n")
        sb.append("Date range: ${dayFmt.format(lo)} to ${dayFmt.format(hi)}\n")
        sb.append("Generated by Fud AI\n")
        for ((date, dayEntries) in byDay) {
            val tot = totals(dayEntries)
            sb.append("\n## ${dayFmt.format(date)}\n")
            sb.append("Totals:\n")
            sb.append("- Calories: ${tot[0].roundToInt()} / ${t.calories} kcal\n")
            sb.append("- Protein: ${r1(tot[1])} / ${t.protein.roundToInt()} g\n")
            sb.append("- Carbs: ${r1(tot[2])} / ${t.carbs.roundToInt()} g\n")
            sb.append("- Fat: ${r1(tot[3])} / ${t.fat.roundToInt()} g\n")
            sb.append("- Water: ${water[date].orEmpty().sumOf { it.milliliters.toLong() }} ml\n")
            if (!water[date].isNullOrEmpty()) {
                sb.append("### Water\n| Time | Amount (ml) |\n|---|---:|\n")
                water[date].orEmpty().forEach { sb.append("| ${timeFmt.format(it.date.atZone(zone))} | ${it.milliliters} |\n") }
            }
            for ((mt, items) in meals(dayEntries)) {
                sb.append("### ${mealDisplay(mt)}\n")
                sb.append("| Time | Food | Weight | Calories | Protein (g) | Carbs (g) | Fat (g) | Sugar (g) | Added sugar (g) | Fiber (g) | Saturated fat (g) | Monounsaturated fat (g) | Polyunsaturated fat (g) | Cholesterol (mg) | Caffeine (mg) | Sodium (mg) | Potassium (mg) | Trans fat (g) | Calcium (mg) | Iron (mg) | Magnesium (mg) | Zinc (mg) | Vitamin A (mcg) | Vitamin C (mg) | Vitamin D (mcg) | Vitamin B12 (mcg) | Vitamin E (mg) | Vitamin K (mcg) | Folate (mcg) | Omega-3 (g) | Source | Ingredients |\n")
                sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|\n")
                for (e in items) {
                    val weight = e.servingSizeGrams?.let { "${it.roundToInt()} g" } ?: "-"
                    val food = e.name.replace("|", "/")
                    val cells = listOf(
                        time(e), food, weight, e.calories.toString(),
                        r1(e.protein).toString(), r1(e.carbs).toString(), r1(e.fat).toString(),
                        optionalNumber(e.sugar, "-"), optionalNumber(e.addedSugar, "-"), optionalNumber(e.fiber, "-"),
                        optionalNumber(e.saturatedFat, "-"), optionalNumber(e.monounsaturatedFat, "-"),
                        optionalNumber(e.polyunsaturatedFat, "-"), optionalNumber(e.cholesterol, "-"),
                        optionalNumber(e.caffeine, "-"), optionalNumber(e.sodium, "-"), optionalNumber(e.potassium, "-"), optionalNumber(e.transFat, "-"),
                        optionalNumber(e.calcium, "-"), optionalNumber(e.iron, "-"), optionalNumber(e.magnesium, "-"),
                        optionalNumber(e.zinc, "-"), optionalNumber(e.vitaminA, "-"), optionalNumber(e.vitaminC, "-"),
                        optionalNumber(e.vitaminD, "-"), optionalNumber(e.vitaminB12, "-"), optionalNumber(e.vitaminE, "-"),
                        optionalNumber(e.vitaminK, "-"), optionalNumber(e.folate, "-"), optionalNumber(e.omega3, "-"),
                        sourceLabel(e.source), ingredientsText(e),
                    )
                    sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
                }
            }
        }
        return sb.toString()
    }

    // --- CSV ---

    private fun csv(byDay: Map<LocalDate, List<FoodEntry>>, water: Map<LocalDate, List<WaterEntry>>): String {
        val sb = StringBuilder()
        sb.append("date,meal,time,food,weight_g,calories,protein_g,carbs_g,fat_g,sugar_g,added_sugar_g,fiber_g,saturated_fat_g,monounsaturated_fat_g,polyunsaturated_fat_g,cholesterol_mg,caffeine_mg,sodium_mg,potassium_mg,trans_fat_g,calcium_mg,iron_mg,magnesium_mg,zinc_mg,vitamin_a_mcg,vitamin_c_mg,vitamin_d_mcg,vitamin_b12_mcg,vitamin_e_mg,vitamin_k_mcg,folate_mcg,omega3_g,source,note,ingredients,entry_type,water_ml,water_total_ml\n")
        for ((date, dayEntries) in byDay) {
            val d = dayFmt.format(date)
            for ((mt, items) in meals(dayEntries)) {
                for (e in items) {
                    val cols = listOf(
                        d, mt.name.lowercase(), time(e), e.name,
                        e.servingSizeGrams?.roundToInt()?.toString() ?: "",
                        e.calories.toString(), r1(e.protein).toString(), r1(e.carbs).toString(), r1(e.fat).toString(),
                        optionalNumber(e.sugar), optionalNumber(e.addedSugar), optionalNumber(e.fiber),
                        optionalNumber(e.saturatedFat), optionalNumber(e.monounsaturatedFat),
                        optionalNumber(e.polyunsaturatedFat), optionalNumber(e.cholesterol), optionalNumber(e.caffeine), optionalNumber(e.sodium),
                        optionalNumber(e.potassium), optionalNumber(e.transFat), optionalNumber(e.calcium),
                        optionalNumber(e.iron), optionalNumber(e.magnesium), optionalNumber(e.zinc),
                        optionalNumber(e.vitaminA), optionalNumber(e.vitaminC), optionalNumber(e.vitaminD),
                        optionalNumber(e.vitaminB12), optionalNumber(e.vitaminE), optionalNumber(e.vitaminK),
                        optionalNumber(e.folate), optionalNumber(e.omega3),
                        sourceLabel(e.source), e.customNote ?: "", ingredientsText(e), "food", "", "",
                    )
                    sb.append(cols.joinToString(",") { csvEscape(it) }).append("\n")
                }
            }
            water[date].orEmpty().forEach { entry ->
                val cols = MutableList(38) { "" }
                cols[0] = d; cols[2] = timeFmt.format(entry.date.atZone(zone)); cols[3] = "Water"
                cols[35] = "water"; cols[36] = entry.milliliters.toString()
                sb.append(cols.joinToString(",", transform = ::csvEscape)).append("\n")
            }
            val summary = MutableList(38) { "" }
            summary[0] = d; summary[35] = "water_total"
            summary[37] = water[date].orEmpty().sumOf { it.milliliters.toLong() }.toString()
            sb.append(summary.joinToString(",")).append("\n")
        }
        return sb.toString()
    }

    private fun csvEscape(field: String): String =
        if (field.contains(',') || field.contains('"') || field.contains('\n')) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else field

    private fun ingredientsText(entry: FoodEntry): String = entry.ingredients.joinToString("; ") { ingredient ->
        "${ingredient.name} (${r1(ingredient.grams)}g · ${ingredient.calories} kcal · P ${r1(ingredient.protein)}g · C ${r1(ingredient.carbs)}g · F ${r1(ingredient.fat)}g)"
    }.replace("|", "/")
}
