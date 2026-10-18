package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.models.BarcodeLabelMemory
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.services.voice.BarcodeProduct
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/** The order Open Food Facts, saved label (#485), then USDA (#533), against one stubbed server. */
class BarcodeProductLookupTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val paths = Collections.synchronizedList(mutableListOf<String>())
    // Factories, so every request gets a fresh response body.
    private var openFoodFacts: () -> MockResponse = { notFound() }
    private var foodDataCentral: () -> MockResponse = { FoodDataCentralServiceTest.json(200, """{"foods":[]}""") }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                paths += path
                return if (path.startsWith("/fdc/")) foodDataCentral() else openFoodFacts()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun openFoodFactsResultIsUsedWithoutAskingUsda() = runBlocking {
        openFoodFacts = { FoodDataCentralServiceTest.json(200, OPEN_FOOD_FACTS_PRODUCT) }

        val outcome = lookup(KEY) as BarcodeProductLookup.Outcome.Product

        assertEquals("Open Food Facts Oats", outcome.result.analysis.name)
        assertNull(outcome.result.analysis.productMetadata?.dataSource)
        assertTrue(paths.none { it.startsWith("/fdc/") })
    }

    @Test
    fun savedLabelWinsOverUsda() = runBlocking {
        val label = FoodEntry(
            name = "My label",
            calories = 150,
            protein = 5.0,
            carbs = 20.0,
            fat = 4.0,
            timestamp = Instant.ofEpochSecond(1_000),
            source = FoodSource.NUTRITION_LABEL,
            productMetadata = BarcodeLabelMemory.labelMetadata(null, "0016000275287")
        )

        val outcome = lookup(KEY, labels = listOf(label)) as BarcodeProductLookup.Outcome.SavedLabel

        assertEquals("My label", outcome.entry.name)
        assertTrue(paths.none { it.startsWith("/fdc/") })
    }

    @Test
    fun voiceAndIngredientScansGetTheSavedLabel() = runBlocking {
        val label = FoodEntry(
            name = "My label",
            calories = 150,
            protein = 5.0,
            carbs = 20.0,
            fat = 4.0,
            timestamp = Instant.ofEpochSecond(1_000),
            source = FoodSource.NUTRITION_LABEL,
            servingSizeGrams = 40.0,
            productMetadata = BarcodeLabelMemory.labelMetadata(null, "0016000275287")
        )

        val outcome = lookup(KEY, labels = listOf(label))
        val product = BarcodeProduct.from(outcome)

        assertEquals("My label", outcome.analysis.name)
        assertEquals(150, outcome.analysis.calories)
        assertEquals(40.0, outcome.analysis.servingSizeGrams, 0.001)
        assertEquals("0016000275287", outcome.analysis.productMetadata?.barcode)
        assertEquals(FoodSource.NUTRITION_LABEL, product.source)
        assertNull(product.imageBytes)
        assertTrue(paths.none { it.startsWith("/fdc/") })
    }

    @Test
    fun scannedProductsStayBarcodeLogs() = runBlocking {
        openFoodFacts = { FoodDataCentralServiceTest.json(200, OPEN_FOOD_FACTS_PRODUCT) }

        val product = BarcodeProduct.from(lookup(KEY))

        assertEquals("Open Food Facts Oats", product.analysis.name)
        assertEquals(FoodSource.BARCODE, product.source)
    }

    @Test
    fun withoutAKeyNothingIsSentToUsda() {
        for (key in listOf(null, "", "   ")) {
            assertEquals(OpenFoodFactsService.LookupFailure.PRODUCT_NOT_FOUND, openFoodFactsFailure(key).failure)
        }
        assertTrue(paths.none { it.startsWith("/fdc/") })
    }

    @Test
    fun usdaFillsInWhenOpenFoodFactsHasNoNutrition() = runBlocking {
        openFoodFacts = { FoodDataCentralServiceTest.json(200, """{"status":1,"product":{"product_name":"No nutrition"}}""") }
        foodDataCentral = { FoodDataCentralServiceTest.json(200, FoodDataCentralServiceTest.CHEERIOS_SEARCH) }

        val outcome = lookup(KEY) as BarcodeProductLookup.Outcome.Product

        assertEquals("Cheerios Cereal", outcome.result.analysis.name)
        assertEquals(FoodProductMetadata.USDA_FOOD_DATA_CENTRAL, outcome.result.analysis.productMetadata?.dataSource)
        assertNull(outcome.result.productImageBytes)
        assertEquals(2, paths.size)
        assertTrue(paths.last().startsWith("/fdc/"))
    }

    @Test
    fun usdaMissOrOutageKeepsTheOpenFoodFactsError() {
        for ((status, body) in listOf(200 to """{"foods":[]}""", 503 to "unavailable")) {
            foodDataCentral = { FoodDataCentralServiceTest.json(status, body) }
            assertEquals(OpenFoodFactsService.LookupFailure.PRODUCT_NOT_FOUND, openFoodFactsFailure(KEY).failure)
        }
    }

    @Test
    fun refusedUsdaKeyIsReported() {
        foodDataCentral = { FoodDataCentralServiceTest.json(403, """{"error":{"code":"API_KEY_INVALID"}}""") }

        try {
            runBlocking { lookup(KEY) }
            fail("Expected the refused key")
        } catch (error: FoodDataCentralService.LookupException) {
            assertEquals(FoodDataCentralService.LookupFailure.INVALID_API_KEY, error.failure)
        }
    }

    @Test
    fun otherOpenFoodFactsFailuresDoNotFallBack() {
        openFoodFacts = { FoodDataCentralServiceTest.json(429, """{"status":0}""") }

        assertEquals(OpenFoodFactsService.LookupFailure.RATE_LIMITED, openFoodFactsFailure(KEY).failure)
        assertTrue(paths.none { it.startsWith("/fdc/") })
    }

    private suspend fun lookup(key: String?, labels: List<FoodEntry> = emptyList()) =
        BarcodeProductLookup.lookup(
            barcode = CODE,
            usdaApiKey = key,
            savedLabelEntries = { labels },
            openFoodFacts = { OpenFoodFactsService.lookupWithImage(it, client, server.url("/")) },
            foodDataCentral = { code, apiKey -> FoodDataCentralService.lookup(code, apiKey, client, server.url("/")) }
        )

    private fun openFoodFactsFailure(key: String?): OpenFoodFactsService.LookupException = try {
        runBlocking { lookup(key) }
        fail("Expected the lookup to fail")
        throw AssertionError("unreachable")
    } catch (error: OpenFoodFactsService.LookupException) {
        error
    }

    private companion object {
        const val KEY = "fdc-test-key-123"
        const val CODE = "016000275287"
        const val OPEN_FOOD_FACTS_PRODUCT =
            """{"status":1,"product":{"product_name":"Open Food Facts Oats","serving_quantity":40,""" +
                """"nutriments":{"energy-kcal_100g":380,"proteins_100g":13,"carbohydrates_100g":60,"fat_100g":7}}}"""

        fun notFound(): MockResponse = FoodDataCentralServiceTest.json(404, """{"status":0}""")
    }
}
