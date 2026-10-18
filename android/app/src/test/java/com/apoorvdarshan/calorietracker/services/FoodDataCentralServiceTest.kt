package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * USDA FoodData Central as the second barcode source (#533). Responses come from
 * `fdc/branded_search_cheerios.json`, a trimmed recording of a real Branded Foods search.
 */
class FoodDataCentralServiceTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun exactGtinMatchMapsTheRecordedBrandedFood() = runBlocking {
        server.enqueue(json(200, CHEERIOS_SEARCH))

        val result = lookup(" 016000275287 ")!!

        // The first food in the response has another GTIN; only the exact match is used.
        assertEquals("Cheerios Cereal", result.name)
        assertEquals(20.0, result.servingSizeGrams, 0.0)
        assertEquals(listOf(ServingUnitOption(unit = "serving", gramsPerUnit = 20.0, quantity = 1.0)), result.servingUnitOptions)
        assertEquals("serving", result.selectedServingUnit)
        // Branded values are per 100 g, scaled to the 20 g serving.
        assertEquals(72, result.calories)
        assertEquals(2.56, result.protein, 0.0001)
        assertEquals(14.88, result.carbs, 0.0001)
        assertEquals(1.282, result.fat, 0.0001)
        assertEquals(1.0, result.sugar!!, 0.0)
        assertEquals(1.0, result.addedSugar!!, 0.0)
        assertEquals(2.1, result.fiber!!, 0.0)
        assertEquals(0.3, result.saturatedFat!!, 0.0)
        assertEquals(0.0, result.transFat!!, 0.0)
        assertEquals(0.0, result.cholesterol!!, 0.0)
        assertEquals(97.4, result.sodium!!, 0.0)
        assertEquals(128.2, result.potassium!!, 0.0)
        assertEquals(66.6, result.calcium!!, 0.0)
        assertEquals(6.5, result.iron!!, 0.0)
        // FDC lists older label panels after the current one; the first entry wins.
        assertEquals(140.0, result.magnesium!!, 0.0)
        assertEquals(5.2, result.zinc!!, 0.0)
        assertEquals(18.0, result.vitaminC!!, 0.0)
        // Only International Units are given for vitamin D (205 IU x 0.025 µg, per 20 g).
        assertEquals(1.0, result.vitaminD!!, 0.0)
        assertNull(result.vitaminA)
        assertEquals(1.5, result.vitaminB12!!, 0.0)
        assertEquals(100.0, result.folate!!, 0.0)

        val metadata = result.productMetadata!!
        assertEquals("016000275287", metadata.barcode)
        assertEquals(FoodProductMetadata.USDA_FOOD_DATA_CENTRAL, metadata.dataSource)
        assertNotNull(metadata.dataSourceNameRes)
        assertEquals(listOf("Processed Cereal Products"), metadata.categories)
        assertTrue(metadata.ingredientsText!!.startsWith("WHOLE GRAIN OATS, CORN STARCH"))
        assertNull(metadata.imageUrl)
    }

    @Test
    fun keyIsSentOnlyInTheHeader() = runBlocking {
        server.enqueue(json(200, CHEERIOS_SEARCH))

        lookup("016000275287", apiKey = "  $KEY\n")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/fdc/v1/foods/search", request.path)
        assertNull(request.requestUrl?.query)
        assertFalse(request.requestLine.contains(KEY))
        assertEquals(KEY, request.getHeader("X-Api-Key"))
        val bodyText = request.body.readUtf8()
        assertFalse(bodyText.contains(KEY))
        val body = Json.parseToJsonElement(bodyText).jsonObject
        assertEquals(listOf("Branded"), body["dataType"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(body["requireAllWords"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("016000275287", "16000275287", "0016000275287", "00016000275287"),
            body["query"]!!.jsonPrimitive.content.split(" ")
        )
    }

    @Test
    fun gtinVariantsCoverStrippedAndPaddedForms() {
        assertEquals(
            listOf("016000275287", "16000275287", "0016000275287", "00016000275287"),
            FoodDataCentralService.gtinQueryVariants("016000275287")
        )
        assertEquals(
            listOf("40170725", "000040170725", "0000040170725", "00000040170725"),
            FoodDataCentralService.gtinQueryVariants("40170725")
        )
        assertEquals("123", FoodDataCentralService.gtinQueryVariants("0000000000000000123").first())
        for (invalid in listOf("", "abc", "12 34", "000000", "123456789012345", "١٢٣٤")) {
            assertTrue(invalid, FoodDataCentralService.gtinQueryVariants(invalid).isEmpty())
        }

        assertTrue(FoodDataCentralService.matchesGtin("00016000275287", "016000275287"))
        assertTrue(FoodDataCentralService.matchesGtin("016000275287", "0016000275287"))
        assertFalse(FoodDataCentralService.matchesGtin("00016000275288", "016000275287"))
        assertFalse(FoodDataCentralService.matchesGtin("160002752870", "016000275287"))
        assertFalse(FoodDataCentralService.matchesGtin(null, "016000275287"))
    }

    @Test
    fun foodsWithoutAnExactGtinAreNeverUsed() = runBlocking {
        server.enqueue(json(200, CHEERIOS_SEARCH))

        assertNull(lookup("016000275280"))
    }

    @Test
    fun blankKeyOrNonGtinCodeSendsNothing() = runBlocking {
        assertNull(lookup("016000275287", apiKey = " \n"))
        assertNull(lookup("ABC-123"))
        assertNull(lookup("0000"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refusedKeyIsReportedAsInvalid() {
        for (status in listOf(401, 403)) {
            server.enqueue(json(status, """{"error":{"code":"API_KEY_INVALID","message":"An invalid api_key was supplied."}}"""))
            val error = failure()
            assertEquals(FoodDataCentralService.LookupFailure.INVALID_API_KEY, error.failure)
            assertEquals(status, error.httpStatus)
        }
    }

    @Test
    fun rateLimitsServerErrorsBadBodiesAndNetworkFailuresAreUnavailable() {
        for ((status, body) in listOf(
            429 to """{"error":{"code":"OVER_RATE_LIMIT"}}""",
            503 to "unavailable",
            200 to "not json",
            200 to """{"totalHits":0}"""
        )) {
            server.enqueue(json(status, body))
            assertEquals(FoodDataCentralService.LookupFailure.UNAVAILABLE, failure().failure)
        }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(FoodDataCentralService.LookupFailure.UNAVAILABLE, failure().failure)
    }

    @Test
    fun labelNutrientsWinAndHouseholdTextGivesTheServingWeight() = runBlocking {
        server.enqueue(
            json(
                200,
                """
                {"foods":[{
                    "dataType":"Branded","gtinUpc":"041303020937","description":"GRANOLA BAR","brandOwner":"ACME FOODS",
                    "servingSize":1,"servingSizeUnit":"PIECE","householdServingFullText":"1 bar (40 g)",
                    "labelNutrients":{"calories":{"value":190},"protein":{"value":4},"sodium":{"value":95},"fat":{"value":7}},
                    "foodNutrients":[
                        {"nutrientId":1008,"unitName":"KCAL","value":450},
                        {"nutrientId":1003,"unitName":"G","value":10},
                        {"nutrientId":1005,"unitName":"G","value":65},
                        {"nutrientId":1093,"unitName":"MG","value":300}
                    ]
                }]}
                """.trimIndent()
            )
        )

        val result = lookup("041303020937")!!

        assertEquals("Acme Foods Granola Bar", result.name)
        assertEquals(40.0, result.servingSizeGrams, 0.0)
        assertEquals(190, result.calories)
        assertEquals(4.0, result.protein, 0.0)
        assertEquals(7.0, result.fat, 0.0)
        assertEquals(95.0, result.sodium!!, 0.0)
        // No label value for carbs, so the per-100 g value is scaled to 40 g.
        assertEquals(26.0, result.carbs, 0.0001)
    }

    @Test
    fun productMetadataKeepsTheSourceAndDecodesOlderAndNewerLogs() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString(FoodProductMetadata.serializer(), """{"barcode":"123"}""")
        assertNull(legacy.dataSource)
        assertNull(legacy.dataSourceNameRes)

        val usda = legacy.copy(dataSource = FoodProductMetadata.USDA_FOOD_DATA_CENTRAL)
        assertEquals(usda, json.decodeFromString(FoodProductMetadata.serializer(), json.encodeToString(FoodProductMetadata.serializer(), usda)))
        assertTrue(usda.hasDisplayDetails)

        val newer = json.decodeFromString(FoodProductMetadata.serializer(), """{"barcode":"123","dataSource":"someNewSource"}""")
        assertEquals("someNewSource", newer.dataSource)
        assertNull(newer.dataSourceNameRes)
    }

    private suspend fun lookup(barcode: String, apiKey: String = KEY): FoodAnalysis? =
        FoodDataCentralService.lookup(barcode, apiKey, client, server.url("/"))

    private fun failure(): FoodDataCentralService.LookupException = try {
        runBlocking { lookup("016000275287") }
        fail("Expected the lookup to fail")
        throw AssertionError("unreachable")
    } catch (error: FoodDataCentralService.LookupException) {
        error
    }

    companion object {
        const val KEY = "fdc-test-key-123"
        val CHEERIOS_SEARCH: String by lazy {
            FoodDataCentralServiceTest::class.java.classLoader!!
                .getResource("fdc/branded_search_cheerios.json")!!
                .readText()
        }

        fun json(status: Int, body: String): MockResponse = MockResponse()
            .setResponseCode(status)
            .addHeader("Content-Type", "application/json")
            .setBody(body)
    }
}
