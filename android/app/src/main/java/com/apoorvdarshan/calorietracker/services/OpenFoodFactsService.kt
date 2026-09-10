package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.BuildConfig
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import com.apoorvdarshan.calorietracker.models.SupplementalNutrient
import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.services.ai.AiErrorKind
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

object OpenFoodFactsService {
    private val FIELDS = listOf(
        "product_name", "generic_name", "brands", "quantity",
        "product_quantity", "product_quantity_unit", "serving_size", "serving_quantity",
        "nutriments", "ingredients_text", "allergens_tags", "traces_tags",
        "nutriscore_grade", "nova_group", "ecoscore_grade", "labels_tags",
        "categories_tags", "image_front_url"
    ).joinToString(",")
    private val API_BASE_URL = "https://world.openfoodfacts.org/".toHttpUrl()

    data class LookupResult(
        val analysis: FoodAnalysis,
        val productImageBytes: ByteArray?
    )

    enum class LookupFailure {
        INVALID_BARCODE,
        PRODUCT_NOT_FOUND,
        MISSING_NUTRITION,
        RATE_LIMITED,
        SERVICE_UNAVAILABLE,
        UNEXPECTED_RESPONSE,
        OFFLINE,
        TIMEOUT,
        NETWORK
    }

    class LookupException(
        val failure: LookupFailure,
        val httpStatus: Int? = null,
        cause: Throwable? = null
    ) : Exception(null, cause)

    suspend fun lookup(
        barcode: String,
        client: OkHttpClient = FoodAnalysisService.defaultClient,
        baseUrl: HttpUrl = API_BASE_URL
    ): FoodAnalysis = withContext(Dispatchers.IO) {
        val code = normalizedBarcode(barcode)
            ?: throw LookupException(LookupFailure.INVALID_BARCODE)

        val url = baseUrl.newBuilder()
            .addPathSegments("api/v2/product")
            .addPathSegment("$code.json")
            .addQueryParameter("fields", FIELDS)
            .addQueryParameter("lc", Locale.getDefault().language)
            .build()
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", userAgent)
            .build()

        val raw = try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw LookupException(
                        LookupFailure.PRODUCT_NOT_FOUND,
                        httpStatus = response.code
                    )
                    response.code == 429 -> throw LookupException(
                        LookupFailure.RATE_LIMITED,
                        httpStatus = response.code
                    )
                    response.code in 500..599 -> throw LookupException(
                        LookupFailure.SERVICE_UNAVAILABLE,
                        httpStatus = response.code
                    )
                    !response.isSuccessful -> throw LookupException(
                        LookupFailure.UNEXPECTED_RESPONSE,
                        httpStatus = response.code
                    )
                }
                response.body?.string().orEmpty()
            }
        } catch (error: LookupException) {
            throw error
        } catch (error: IOException) {
            val failure = when (AiErrorKind.fromNetwork(error)) {
                AiErrorKind.OFFLINE -> LookupFailure.OFFLINE
                AiErrorKind.TIMEOUT -> LookupFailure.TIMEOUT
                else -> LookupFailure.NETWORK
            }
            throw LookupException(failure, cause = error)
        }

        val json = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw LookupException(LookupFailure.UNEXPECTED_RESPONSE)
        val product = json["product"] as? JsonObject
        if (json.flexibleInt("status") == 0 || product == null) {
            throw LookupException(LookupFailure.PRODUCT_NOT_FOUND)
        }
        analysis(product, code)
    }

    suspend fun lookupWithImage(
        barcode: String,
        client: OkHttpClient = FoodAnalysisService.defaultClient,
        baseUrl: HttpUrl = API_BASE_URL
    ): LookupResult = withContext(Dispatchers.IO) {
        val analysis = lookup(barcode = barcode, client = client, baseUrl = baseUrl)
        LookupResult(
            analysis = analysis,
            productImageBytes = productImageBytes(analysis.productMetadata?.imageUrl, client)
        )
    }

    internal fun normalizedBarcode(raw: String): String? {
        val code = raw.trim()
        if (code.isEmpty() || code.length > 24 || code.any { it !in '0'..'9' }) return null
        return code
    }

    private val userAgent: String
        get() = "FudAI/${BuildConfig.VERSION_NAME} (https://fud-ai.app)"

    private fun analysis(product: JsonObject, barcode: String): FoodAnalysis {
        val nutriments = product["nutriments"] as? JsonObject
            ?: throw LookupException(LookupFailure.MISSING_NUTRITION)

        val servingGrams = maxOf(
            product.flexibleDouble("serving_quantity")
                ?: gramsFrom(product.string("serving_size"))
                ?: 100.0,
            1.0
        )
        val scale = servingGrams / 100.0

        fun servingValue(key: String): Double? {
            nutriments.flexibleDouble("${key}_serving")?.let { return it }
            return nutriments.flexibleDouble("${key}_100g")?.let { it * scale }
        }

        val calories = servingValue("energy-kcal")
            ?: servingValue("energy")?.let { it * 0.23900573614 }
        val protein = servingValue("proteins")
        val carbs = servingValue("carbohydrates")
        val fat = servingValue("fat")

        if (calories == null && protein == null && carbs == null && fat == null) {
            throw LookupException(LookupFailure.MISSING_NUTRITION)
        }

        val servingOption = ServingUnitOption(unit = "serving", gramsPerUnit = servingGrams, quantity = 1.0)
        val servingOptions = buildList {
            add(servingOption)
            packageGrams(product)?.takeIf { kotlin.math.abs(it - servingGrams) > 0.01 }?.let {
                add(ServingUnitOption(unit = "package", gramsPerUnit = it, quantity = 1.0))
            }
        }
        val metadata = FoodProductMetadata(
            barcode = barcode,
            packageQuantity = product.string("quantity"),
            ingredientsText = product.string("ingredients_text"),
            allergens = displayTags(product.stringList("allergens_tags"), 16),
            traces = displayTags(product.stringList("traces_tags"), 16),
            nutriScore = normalizedScore(product.string("nutriscore_grade")),
            novaGroup = product.flexibleInt("nova_group")?.takeIf { it in 1..4 },
            ecoScore = normalizedScore(product.string("ecoscore_grade")),
            labels = displayTags(product.stringList("labels_tags"), 12),
            categories = displayTags(product.stringList("categories_tags"), 8),
            imageUrl = product.string("image_front_url")
        )
        return FoodAnalysis(
            name = productName(product, barcode),
            calories = (calories ?: 0.0).roundToInt(),
            protein = protein ?: 0.0,
            carbs = carbs ?: 0.0,
            fat = fat ?: 0.0,
            servingSizeGrams = servingGrams,
            emoji = "🏷️",
            sugar = rounded(servingValue("sugars")),
            addedSugar = rounded(servingValue("added-sugars")),
            fiber = rounded(servingValue("fiber")),
            saturatedFat = rounded(servingValue("saturated-fat")),
            monounsaturatedFat = rounded(servingValue("monounsaturated-fat")),
            polyunsaturatedFat = rounded(servingValue("polyunsaturated-fat")),
            cholesterol = milligrams(servingValue("cholesterol")),
            caffeine = milligrams(servingValue("caffeine")),
            supplementalNutrients = SupplementalNutrient.values().mapNotNull { nutrient ->
                servingValue(nutrient.apiKey.replace('_', '-'))?.let { value ->
                    rounded(value)?.let { nutrient.storageKey to it }
                }
            }.toMap(),
            sodium = milligrams(servingValue("sodium")),
            potassium = milligrams(servingValue("potassium")),
            transFat = rounded(servingValue("trans-fat")),
            calcium = milligrams(servingValue("calcium")),
            iron = milligrams(servingValue("iron")),
            magnesium = milligrams(servingValue("magnesium")),
            zinc = milligrams(servingValue("zinc")),
            vitaminA = micrograms(servingValue("vitamin-a")),
            vitaminC = milligrams(servingValue("vitamin-c")),
            vitaminD = micrograms(servingValue("vitamin-d")),
            vitaminB12 = micrograms(servingValue("vitamin-b12")),
            vitaminE = milligrams(servingValue("vitamin-e")),
            vitaminK = micrograms(servingValue("vitamin-k")),
            folate = micrograms(servingValue("folates")),
            omega3 = rounded(servingValue("omega-3-fat")),
            servingUnitOptions = servingOptions,
            selectedServingUnit = servingOption.unit,
            selectedServingQuantity = 1.0,
            productMetadata = metadata
        )
    }

    private fun packageGrams(product: JsonObject): Double? {
        val quantity = product.flexibleDouble("product_quantity")
        val unit = product.string("product_quantity_unit")?.lowercase(Locale.US)
        if (quantity != null && unit != null) {
            when (unit) {
                "kg" -> return quantity * 1_000.0
                "mg" -> return quantity / 1_000.0
                "g" -> return quantity
                "l" -> return quantity * 1_000.0
                "ml" -> return quantity
            }
        }
        val displayQuantity = product.string("quantity") ?: return null
        if (!Regex("^[0-9]+(?:[.,][0-9]+)?\\s*(?:kg|mg|g|oz|ml|l)$", RegexOption.IGNORE_CASE)
                .matches(displayQuantity)
        ) return null
        return gramsFrom(displayQuantity)
    }

    private fun displayTags(tags: List<String>, limit: Int): List<String> {
        val seen = mutableSetOf<String>()
        return tags.mapNotNull { raw ->
            val display = raw
                .replace(Regex("^[a-z]{2}:"), "")
                .replace('-', ' ')
                .replace('_', ' ')
                .trim()
            val key = display.lowercase(Locale.US)
            display.takeIf { it.isNotEmpty() && seen.add(key) }
                ?.replaceFirstChar { first -> first.titlecase() }
        }.take(limit)
    }

    private fun normalizedScore(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "unknown" && it != "not-applicable" }
        ?.uppercase(Locale.US)

    private fun productImageBytes(urlString: String?, client: OkHttpClient): ByteArray? {
        val url = urlString?.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host == "images.openfoodfacts.org" }
            ?: return null
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", userAgent)
            .addHeader("Accept", "image/*")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body ?: return null
                val length = body.contentLength()
                if (!response.isSuccessful ||
                    body.contentType()?.type != "image" ||
                    length > MAX_PRODUCT_IMAGE_BYTES
                ) return null
                body.bytes().takeIf { it.size <= MAX_PRODUCT_IMAGE_BYTES }
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun productName(product: JsonObject, barcode: String): String {
        val primary = firstNonEmpty(
            product.string("product_name"),
            product.string("generic_name")
        )
        val brand = product.string("brands").orEmpty()
            .split(",")
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (primary != null && brand != null && !primary.lowercase(Locale.US).contains(brand.lowercase(Locale.US))) {
            return "$brand $primary"
        }
        return primary ?: brand ?: "Barcode $barcode"
    }

    private fun firstNonEmpty(vararg values: String?): String? =
        values.mapNotNull { it?.trim() }.firstOrNull { it.isNotEmpty() }

    private fun rounded(value: Double?): Double? =
        value?.let { round(it * 10.0) / 10.0 }

    private fun milligrams(grams: Double?): Double? =
        grams?.let { round(it * 1000.0 * 10.0) / 10.0 }

    private fun micrograms(grams: Double?): Double? =
        grams?.let { round(it * 1_000_000.0 * 10.0) / 10.0 }

    private fun gramsFrom(servingSize: String?): Double? {
        var text = servingSize?.lowercase(Locale.US) ?: return null
        text = text.replace(",", ".").replace("fl. oz", "fl oz")
        val match = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(fl oz|kg|mg|g|oz|ml|l)""")
            .find(text)
            ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2]) {
            "kg" -> value * 1000.0
            "mg" -> value / 1000.0
            "oz" -> value * 28.3495
            "fl oz" -> value * 29.5735
            "ml" -> value
            "l" -> value * 1000.0
            else -> value
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

    private fun JsonObject.flexibleInt(key: String): Int? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.intOrNull ?: value.contentOrNull?.trim()?.toIntOrNull()
    }

    private fun JsonObject.flexibleDouble(key: String): Double? {
        val value = this[key] as? JsonPrimitive ?: return null
        return (value.doubleOrNull
            ?: value.contentOrNull?.trim()?.replace(",", ".")?.toDoubleOrNull())
            ?.takeUnless { it.isNaN() || it.isInfinite() }
    }

    private const val MAX_PRODUCT_IMAGE_BYTES = 5_000_000
}
