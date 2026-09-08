package com.apoorvdarshan.calorietracker.export

import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.MealType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

enum class DiaryImportMode { REPLACE_DATE_RANGE, ADD_AS_NEW }

data class DiaryImportPreview(
    val entries: List<FoodEntry>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val waterEntries: List<WaterEntry> = emptyList(),
    val includesWater: Boolean = false,
)

class DiaryImportException(message: String) : IllegalArgumentException(message)

object DiaryImporter {
    const val MAXIMUM_FILE_SIZE = 20 * 1_024 * 1_024

    @Serializable
    private data class Document(
        @SerialName("export") val metadata: Metadata,
        val days: List<Day>,
    )

    @Serializable
    private data class Metadata(
        val app: String,
        val format_version: String,
        val date_range: DateRange,
    )

    @Serializable
    private data class DateRange(val start: String, val end: String)

    @Serializable
    private data class Day(val date: String, val meals: List<Meal>, val water_entries: List<WaterItem>? = null)

    @Serializable
    private data class WaterItem(val entry_id: String, val time: String, val milliliters: Int)

    @Serializable
    private data class Meal(val type: String, val items: List<Item>)

    @Serializable
    private data class Item(
        val entry_id: String? = null,
        val name: String,
        val quantity_g: Double? = null,
        val calories: Int,
        val protein_g: Double,
        val carbs_g: Double,
        val fat_g: Double,
        val sugar_g: Double? = null,
        val added_sugar_g: Double? = null,
        val fiber_g: Double? = null,
        val saturated_fat_g: Double? = null,
        val monounsaturated_fat_g: Double? = null,
        val polyunsaturated_fat_g: Double? = null,
        val cholesterol_mg: Double? = null,
        val caffeine_mg: Double? = null,
        val sodium_mg: Double? = null,
        val potassium_mg: Double? = null,
        val supplemental_nutrients_g: Map<String, Double> = emptyMap(),
        val trans_fat_g: Double? = null,
        val calcium_mg: Double? = null,
        val iron_mg: Double? = null,
        val magnesium_mg: Double? = null,
        val zinc_mg: Double? = null,
        val vitamin_a_mcg: Double? = null,
        val vitamin_c_mg: Double? = null,
        val vitamin_d_mcg: Double? = null,
        val vitamin_b12_mcg: Double? = null,
        val vitamin_e_mg: Double? = null,
        val vitamin_k_mcg: Double? = null,
        val folate_mcg: Double? = null,
        val omega3_g: Double? = null,
        val time: String,
        val source: String,
        val note: String? = null,
        val ingredients: List<Ingredient> = emptyList(),
    )

    @Serializable
    private data class Ingredient(
        val name: String,
        val quantity_g: Double,
        val calories: Int,
        val protein_g: Double,
        val carbs_g: Double,
        val fat_g: Double,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun parse(content: String): DiaryImportPreview {
        val document = try {
            json.decodeFromString(Document.serializer(), content)
        } catch (_: SerializationException) {
            throw DiaryImportException("This is not a valid Fud AI food diary JSON file.")
        }
        if (!document.metadata.app.equals("Fud AI", ignoreCase = true)) {
            throw DiaryImportException("This is not a valid Fud AI food diary JSON file.")
        }
        if (document.metadata.format_version.substringBefore('.').toIntOrNull() != 1) {
            throw DiaryImportException("This food diary format is not supported.")
        }

        val start = parseDate(document.metadata.date_range.start)
        val end = parseDate(document.metadata.date_range.end)
        val lower = minOf(start, end)
        val upper = maxOf(start, end)
        val waterEntries = mutableListOf<WaterEntry>()
        val entries = buildList {
            document.days.forEach { day ->
                val date = parseDate(day.date)
                if (date.isBefore(lower) || date.isAfter(upper)) {
                    throw DiaryImportException("The diary contains a date outside its exported range: ${day.date}.")
                }
                day.water_entries.orEmpty().forEach { water ->
                    if (water.milliliters <= 0) throw DiaryImportException("The diary contains an invalid water amount.")
                    val entry = runCatching {
                        require(water.time.matches(Regex("[0-9]{2}:[0-9]{2}")))
                        WaterEntry(id = UUID.fromString(water.entry_id),
                            date = LocalDateTime.of(date, LocalTime.parse(water.time)).atZone(zone).toInstant(),
                            milliliters = water.milliliters)
                    }.getOrElse { throw DiaryImportException("The diary contains an invalid water entry.") }
                    waterEntries += entry
                }
                day.meals.forEach { meal ->
                    val mealType = MealType.values().firstOrNull { it.name.equals(meal.type, ignoreCase = true) }
                        ?: throw DiaryImportException("The diary contains an unknown meal type: ${meal.type}.")
                    meal.items.forEach { item ->
                        validate(item)
                        val timestamp = try {
                            LocalDateTime.of(date, LocalTime.parse(item.time)).atZone(zone).toInstant()
                        } catch (_: DateTimeParseException) {
                            throw DiaryImportException("The diary contains an invalid time: ${item.time}.")
                        }
                        val id = item.entry_id?.let { raw ->
                            runCatching { UUID.fromString(raw) }
                                .getOrElse { throw DiaryImportException("The diary contains an invalid entry ID.") }
                        } ?: UUID.randomUUID()
                        add(FoodEntry(
                            id = id,
                            name = item.name.trim(),
                            calories = item.calories,
                            protein = item.protein_g,
                            carbs = item.carbs_g,
                            fat = item.fat_g,
                            timestamp = timestamp,
                            source = if (item.source == "manually_edited") FoodSource.MANUAL else FoodSource.TEXT_INPUT,
                            mealType = mealType,
                            sugar = item.sugar_g,
                            addedSugar = item.added_sugar_g,
                            fiber = item.fiber_g,
                            saturatedFat = item.saturated_fat_g,
                            monounsaturatedFat = item.monounsaturated_fat_g,
                            polyunsaturatedFat = item.polyunsaturated_fat_g,
                            cholesterol = item.cholesterol_mg,
                            caffeine = item.caffeine_mg,
                            supplementalNutrients = item.supplemental_nutrients_g,
                            sodium = item.sodium_mg,
                            potassium = item.potassium_mg,
                            transFat = item.trans_fat_g,
                            calcium = item.calcium_mg,
                            iron = item.iron_mg,
                            magnesium = item.magnesium_mg,
                            zinc = item.zinc_mg,
                            vitaminA = item.vitamin_a_mcg,
                            vitaminC = item.vitamin_c_mg,
                            vitaminD = item.vitamin_d_mcg,
                            vitaminB12 = item.vitamin_b12_mcg,
                            vitaminE = item.vitamin_e_mg,
                            vitaminK = item.vitamin_k_mcg,
                            folate = item.folate_mcg,
                            omega3 = item.omega3_g,
                            servingSizeGrams = item.quantity_g,
                            customNote = item.note,
                            ingredients = item.ingredients.map { ingredient ->
                                validate(ingredient, item.name)
                                MealIngredient(
                                    name = ingredient.name,
                                    grams = ingredient.quantity_g,
                                    calories = ingredient.calories,
                                    protein = ingredient.protein_g,
                                    carbs = ingredient.carbs_g,
                                    fat = ingredient.fat_g,
                                )
                            },
                        ))
                    }
                }
            }
        }
        if (entries.isEmpty() && waterEntries.isEmpty()) throw DiaryImportException("The selected diary does not contain any food or water entries.")
        return DiaryImportPreview(entries, lower, upper, waterEntries, document.days.any { it.water_entries != null })
    }

    fun applying(
        preview: DiaryImportPreview,
        existing: List<FoodEntry>,
        mode: DiaryImportMode,
    ): List<FoodEntry> = when (mode) {
        DiaryImportMode.ADD_AS_NEW -> existing + preview.entries.map { it.copy(id = UUID.randomUUID()) }
        DiaryImportMode.REPLACE_DATE_RANGE -> {
            val outside = existing.filter { entry ->
                val day = entry.timestamp.atZone(zone).toLocalDate()
                day.isBefore(preview.startDate) || day.isAfter(preview.endDate)
            }
            val inRange = existing.filterNot { it in outside }
            val byId = inRange.associateBy { it.id }
            val byKey = inRange.groupBy(::matchKey).mapValues { it.value.toMutableList() }.toMutableMap()
            val usedIds = mutableSetOf<UUID>()
            val occupiedIds = outside.mapTo(mutableSetOf()) { it.id }

            val imported = preview.entries.map { item ->
                var match = byId[item.id]?.takeUnless { it.id in usedIds }
                if (match == null) {
                    val candidates = byKey[matchKey(item)]
                    while (!candidates.isNullOrEmpty() && candidates.first().id in usedIds) candidates.removeAt(0)
                    match = candidates?.removeFirstOrNull()
                }
                if (match != null) {
                    usedIds += match.id
                    occupiedIds += match.id
                    merge(item, match)
                } else {
                    val id = item.id.takeUnless { it in occupiedIds } ?: UUID.randomUUID()
                    occupiedIds += id
                    item.copy(id = id)
                }
            }
            outside + imported
        }
    }

    /** Preserve water when importing legacy food-only documents. */
    fun applyingWater(preview: DiaryImportPreview, existing: List<WaterEntry>, mode: DiaryImportMode): List<WaterEntry> {
        if (!preview.includesWater) return existing
        val retained = if (mode == DiaryImportMode.ADD_AS_NEW) existing else existing.filter {
            it.date.atZone(zone).toLocalDate() !in preview.startDate..preview.endDate
        }
        val occupied = retained.mapTo(mutableSetOf()) { it.id }
        return retained + preview.waterEntries.map { entry ->
            val id = if (mode == DiaryImportMode.ADD_AS_NEW || entry.id in occupied) UUID.randomUUID() else entry.id
            occupied += id
            entry.copy(id = id)
        }
    }

    private fun merge(imported: FoodEntry, old: FoodEntry): FoodEntry = old.copy(
        name = imported.name,
        calories = imported.calories,
        protein = imported.protein,
        carbs = imported.carbs,
        fat = imported.fat,
        timestamp = imported.timestamp,
        source = imported.source,
        mealType = imported.mealType,
        sugar = imported.sugar,
        addedSugar = imported.addedSugar,
        fiber = imported.fiber,
        saturatedFat = imported.saturatedFat,
        monounsaturatedFat = imported.monounsaturatedFat,
        polyunsaturatedFat = imported.polyunsaturatedFat,
        cholesterol = imported.cholesterol,
        caffeine = imported.caffeine,
        supplementalNutrients = imported.supplementalNutrients,
        sodium = imported.sodium,
        potassium = imported.potassium,
        transFat = imported.transFat,
        calcium = imported.calcium,
        iron = imported.iron,
        magnesium = imported.magnesium,
        zinc = imported.zinc,
        vitaminA = imported.vitaminA,
        vitaminC = imported.vitaminC,
        vitaminD = imported.vitaminD,
        vitaminB12 = imported.vitaminB12,
        vitaminE = imported.vitaminE,
        vitaminK = imported.vitaminK,
        folate = imported.folate,
        omega3 = imported.omega3,
        servingSizeGrams = imported.servingSizeGrams,
        customNote = imported.customNote,
        ingredients = imported.ingredients,
    )

    private fun validate(item: Item) {
        val values = listOfNotNull(
            item.quantity_g, item.protein_g, item.carbs_g, item.fat_g, item.sugar_g, item.added_sugar_g,
            item.fiber_g, item.saturated_fat_g, item.monounsaturated_fat_g, item.polyunsaturated_fat_g,
            item.cholesterol_mg, item.caffeine_mg, item.sodium_mg, item.potassium_mg, item.trans_fat_g,
            item.calcium_mg, item.iron_mg, item.magnesium_mg, item.zinc_mg, item.vitamin_a_mcg,
            item.vitamin_c_mg, item.vitamin_d_mcg, item.vitamin_b12_mcg, item.vitamin_e_mg,
            item.vitamin_k_mcg, item.folate_mcg, item.omega3_g,
        ) + item.supplemental_nutrients_g.values
        if (item.name.isBlank() || item.calories < 0 || values.any { !it.isFinite() || it < 0 }) {
            throw DiaryImportException("The diary contains an invalid food entry: ${item.name.ifBlank { "Unnamed food" }}.")
        }
    }

    private fun validate(ingredient: Ingredient, foodName: String) {
        val values = listOf(ingredient.quantity_g, ingredient.protein_g, ingredient.carbs_g, ingredient.fat_g)
        if (ingredient.name.isBlank() || ingredient.calories < 0 || values.any { !it.isFinite() || it < 0 }) {
            throw DiaryImportException("The diary contains an invalid ingredient in $foodName.")
        }
    }

    private fun parseDate(value: String): LocalDate = try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        throw DiaryImportException("The diary contains an invalid date: $value.")
    }

    private fun matchKey(entry: FoodEntry): String {
        val local = entry.timestamp.atZone(zone)
        return "${local.toLocalDate()}|${local.toLocalTime().withSecond(0).withNano(0)}|${entry.mealType.name}|${entry.name.trim().lowercase()}"
    }
}
