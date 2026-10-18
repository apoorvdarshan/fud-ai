package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.models.BarcodeLabelMemory
import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.models.IngredientLabelParser
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysisService
import com.apoorvdarshan.calorietracker.services.ai.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Optional second barcode source (#533): USDA FoodData Central Branded Foods. Only a food whose
 * GTIN/UPC equals the scanned code is used, and the user's key is sent only in the `X-Api-Key`
 * header, never in the URL. Mirrors iOS FoodDataCentralService.
 */
object FoodDataCentralService {
    /** Free sign-up for the api.data.gov key FoodData Central uses. */
    const val API_KEY_SIGNUP_URL = "https://fdc.nal.usda.gov/api-key-signup"
    private val API_BASE_URL = "https://api.nal.usda.gov/".toHttpUrl()
    private const val REQUEST_TIMEOUT_SECONDS = 15L
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    enum class LookupFailure {
        /** FoodData Central refused the key (HTTP 401 or 403). */
        INVALID_API_KEY,
        /** Rate limits, server errors, unreadable responses and network failures. */
        UNAVAILABLE
    }

    class LookupException(
        val failure: LookupFailure,
        val httpStatus: Int? = null,
        cause: Throwable? = null
    ) : Exception(null, cause)

    private val defaultClient: OkHttpClient by lazy {
        FoodAnalysisService.defaultClient.newBuilder()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The Branded food whose GTIN/UPC equals [barcode], mapped like an Open Food Facts product.
     * Null when FoodData Central has no exact match with nutrition, and when there is no key or
     * no valid code, in which case nothing is sent.
     */
    suspend fun lookup(
        barcode: String,
        apiKey: String,
        client: OkHttpClient = defaultClient,
        baseUrl: HttpUrl = API_BASE_URL
    ): FoodAnalysis? = withContext(Dispatchers.IO) {
        val code = barcode.trim()
        val variants = gtinQueryVariants(code)
        val key = apiKey.trim()
        if (key.isEmpty() || variants.isEmpty()) return@withContext null

        val body = buildJsonObject {
            // Words are OR'ed, so one request covers every way FDC may store the code.
            put("query", variants.joinToString(" "))
            putJsonArray("dataType") { add("Branded") }
            put("requireAllWords", false)
            put("pageSize", 25)
        }
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("fdc/v1/foods/search").build())
            .header("X-Api-Key", key)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val raw = try {
            client.newCall(request).await().use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        throw LookupException(LookupFailure.INVALID_API_KEY, httpStatus = response.code)
                    !response.isSuccessful ->
                        throw LookupException(LookupFailure.UNAVAILABLE, httpStatus = response.code)
                }
                response.body?.string().orEmpty()
            }
        } catch (error: LookupException) {
            throw error
        } catch (error: IOException) {
            throw LookupException(LookupFailure.UNAVAILABLE, cause = error)
        }

        val foods = runCatching { (Json.parseToJsonElement(raw) as? JsonObject)?.get("foods") as? JsonArray }
            .getOrNull()
            ?: throw LookupException(LookupFailure.UNAVAILABLE)
        foods.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { food ->
                (food.string("dataType")?.equals("Branded", ignoreCase = true) ?: true) &&
                    matchesGtin(food.string("gtinUpc"), code)
            }
            .mapNotNull { analysis(it, code) }
            .firstOrNull()
    }

    /**
     * The forms FDC may store a scanned code in: as scanned, without leading zeros, and
     * zero-padded to GTIN-8, -12, -13 and -14. Empty for anything that is not a GTIN, so no
     * request is made.
     */
    internal fun gtinQueryVariants(code: String): List<String> {
        if (code.isEmpty() || code.any { it !in '0'..'9' }) return emptyList()
        val stripped = code.trimStart('0')
        if (stripped.isEmpty() || stripped.length > 14) return emptyList()
        return (listOf(code, stripped) + listOf(8, 12, 13, 14).mapNotNull { width ->
            stripped.takeIf { it.length <= width }?.padStart(width, '0')
        }).filter { it.length <= 14 }.distinct()
    }

    /** GTINs compare without leading zeros, so a UPC-A stored as GTIN-14 matches. */
    internal fun matchesGtin(stored: String?, scanned: String): Boolean {
        val key = BarcodeLabelMemory.normalizedBarcode(scanned) ?: return false
        return BarcodeLabelMemory.normalizedBarcode(stored) == key
    }

    // Mapping

    private fun analysis(food: JsonObject, barcode: String): FoodAnalysis? {
        val servingGrams = maxOf(servingGrams(food) ?: 100.0, 1.0)
        val nutrients = Nutrients(
            entries = food["foodNutrients"] as? JsonArray ?: JsonArray(emptyList()),
            labelNutrients = food["labelNutrients"] as? JsonObject ?: JsonObject(emptyMap()),
            scale = servingGrams / 100.0
        )

        val calories = nutrients.calories()
        val protein = nutrients.grams(listOf(1003), label = "protein")
        val carbs = nutrients.grams(listOf(1005, 1050), label = "carbohydrates")
        val fat = nutrients.grams(listOf(1004), label = "fat")
        if (calories == null && protein == null && carbs == null && fat == null) return null

        val servingOption = ServingUnitOption(unit = "serving", gramsPerUnit = servingGrams, quantity = 1.0)
        val metadata = FoodProductMetadata(
            barcode = barcode,
            ingredientsText = IngredientLabelParser.markingAllergens(plain = food.string("ingredients"), markup = null),
            categories = listOfNotNull(food.string("foodCategory")),
            dataSource = FoodProductMetadata.USDA_FOOD_DATA_CENTRAL
        )
        return FoodAnalysis(
            name = productName(food, barcode),
            calories = (calories ?: 0.0).roundToInt(),
            protein = protein ?: 0.0,
            carbs = carbs ?: 0.0,
            fat = fat ?: 0.0,
            servingSizeGrams = servingGrams,
            emoji = "🏷️",
            sugar = rounded(nutrients.grams(listOf(2000, 1063), label = "sugars")),
            addedSugar = rounded(nutrients.grams(listOf(1235), label = "addedSugar")),
            fiber = rounded(nutrients.grams(listOf(1079), label = "fiber")),
            saturatedFat = rounded(nutrients.grams(listOf(1258), label = "saturatedFat")),
            monounsaturatedFat = rounded(nutrients.grams(listOf(1292))),
            polyunsaturatedFat = rounded(nutrients.grams(listOf(1293))),
            cholesterol = milligrams(nutrients.grams(listOf(1253), label = "cholesterol", labelUnit = 0.001)),
            caffeine = milligrams(nutrients.grams(listOf(1057))),
            sodium = milligrams(nutrients.grams(listOf(1093), label = "sodium", labelUnit = 0.001)),
            potassium = milligrams(nutrients.grams(listOf(1092), label = "potassium", labelUnit = 0.001)),
            transFat = rounded(nutrients.grams(listOf(1257), label = "transFat")),
            calcium = milligrams(nutrients.grams(listOf(1087), label = "calcium", labelUnit = 0.001)),
            iron = milligrams(nutrients.grams(listOf(1089), label = "iron", labelUnit = 0.001)),
            magnesium = milligrams(nutrients.grams(listOf(1090))),
            zinc = milligrams(nutrients.grams(listOf(1095))),
            vitaminA = micrograms(nutrients.grams(listOf(1106))),
            vitaminC = milligrams(nutrients.grams(listOf(1162))),
            vitaminD = micrograms(nutrients.grams(listOf(1114)) ?: nutrients.vitaminDFromIu()),
            vitaminB12 = micrograms(nutrients.grams(listOf(1178))),
            vitaminE = milligrams(nutrients.grams(listOf(1109))),
            vitaminK = micrograms(nutrients.grams(listOf(1185))),
            folate = micrograms(nutrients.grams(listOf(1190, 1177))),
            servingUnitOptions = listOf(servingOption),
            selectedServingUnit = servingOption.unit,
            selectedServingQuantity = 1.0,
            productMetadata = metadata
        )
    }

    /**
     * Nutrient amounts for one serving. Label values (already per serving) win; otherwise the
     * per-100 g Branded values are scaled to the serving. Only the first entry for a nutrient
     * counts, since FDC can list older panels after it.
     */
    private class Nutrients(entries: JsonArray, private val labelNutrients: JsonObject, private val scale: Double) {
        private val amounts = LinkedHashMap<Int, Pair<Double, String>>()

        init {
            entries.forEach { element ->
                val entry = element as? JsonObject ?: return@forEach
                val id = (entry["nutrientId"] as? JsonPrimitive)?.intOrNull ?: return@forEach
                val value = entry.number("value")?.takeIf { it >= 0 } ?: return@forEach
                if (id !in amounts) {
                    amounts[id] = value to entry.string("unitName").orEmpty().uppercase(Locale.US)
                }
            }
        }

        fun calories(): Double? {
            labelValue("calories")?.let { return it }
            for (id in listOf(1008, 2047, 2048)) {
                amounts[id]?.takeIf { it.second == "KCAL" }?.let { return it.first * scale }
            }
            return amounts[1062]?.takeIf { it.second == "KJ" }?.let { it.first * 0.23900573614 * scale }
        }

        /** Vitamin D given only in International Units (1 IU = 0.025 µg). */
        fun vitaminDFromIu(): Double? =
            amounts[1110]?.takeIf { it.second == "IU" }?.let { it.first * 0.025e-6 * scale }

        /** Grams per serving. [labelUnit] converts the label's unit (mg for minerals) to grams. */
        fun grams(ids: List<Int>, label: String? = null, labelUnit: Double = 1.0): Double? {
            label?.let(::labelValue)?.let { return it * labelUnit }
            for (id in ids) {
                val (value, unit) = amounts[id] ?: continue
                val factor = when (unit) {
                    "G" -> 1.0
                    "MG" -> 0.001
                    "UG", "MCG" -> 0.000_001
                    else -> continue
                }
                return value * factor * scale
            }
            return null
        }

        private fun labelValue(key: String): Double? =
            (labelNutrients[key] as? JsonObject)?.number("value")?.takeIf { it >= 0 }
    }

    private fun servingGrams(food: JsonObject): Double? {
        val size = food.number("servingSize")?.takeIf { it > 0 }
        val unit = food.string("servingSizeUnit")?.lowercase(Locale.US)
        if (size != null && unit != null) {
            when (unit) {
                "g", "grm", "gram", "grams", "ml", "mlt" -> return size
                "kg" -> return size * 1_000.0
                "mg" -> return size / 1_000.0
                "oz", "onz" -> return size * 28.3495
                "fl oz", "foz" -> return size * 29.5735
                "l", "ltr" -> return size * 1_000.0
            }
        }
        // "1 pouch (26g)": the household text often carries the weight.
        return OpenFoodFactsService.gramsFrom(food.string("householdServingFullText"))
    }

    private fun productName(food: JsonObject, barcode: String): String {
        val description = food.string("description")?.let(::displayCase)
        val brand = (food.string("brandName") ?: food.string("brandOwner"))?.let(::displayCase)
        if (description != null && brand != null &&
            !description.lowercase(Locale.US).contains(brand.lowercase(Locale.US))
        ) {
            return "$brand $description"
        }
        return description ?: brand ?: "Barcode $barcode"
    }

    /** Many Branded descriptions are all caps ("HONEY NUT CHEERIOS"); show them in title case. */
    private fun displayCase(value: String): String {
        if (value.any { it.isLowerCase() }) return value
        return value.lowercase(Locale.getDefault()).split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    private fun rounded(value: Double?): Double? = value?.let { round(it * 10.0) / 10.0 }

    private fun milligrams(grams: Double?): Double? = grams?.let { round(it * 1000.0 * 10.0) / 10.0 }

    private fun micrograms(grams: Double?): Double? = grams?.let { round(it * 1_000_000.0 * 10.0) / 10.0 }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(key: String): Double? {
        val value = this[key] as? JsonPrimitive ?: return null
        return (value.doubleOrNull ?: value.contentOrNull?.trim()?.toDoubleOrNull())
            ?.takeUnless { it.isNaN() || it.isInfinite() }
    }
}
