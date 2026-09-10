package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class OpenFoodFactsServiceTest {
    @Test
    fun http404MapsToProductNotFoundBeforeParsingTheBody() {
        val error = lookupFailure(
            code = 404,
            body = """{"status":0,"status_verbose":"product not found"}"""
        )

        assertEquals(OpenFoodFactsService.LookupFailure.PRODUCT_NOT_FOUND, error.failure)
        assertEquals(404, error.httpStatus)
    }

    @Test
    fun successfulStatusZeroPayloadMapsToProductNotFound() {
        val error = lookupFailure(
            code = 200,
            body = """{"status":"0","product":null}"""
        )

        assertEquals(OpenFoodFactsService.LookupFailure.PRODUCT_NOT_FOUND, error.failure)
        assertNull(error.httpStatus)
    }

    @Test
    fun rateLimitAndServerFailuresRemainDistinct() {
        val rateLimit = lookupFailure(code = 429, body = "slow down")
        val serverFailure = lookupFailure(code = 503, body = "unavailable")

        assertEquals(OpenFoodFactsService.LookupFailure.RATE_LIMITED, rateLimit.failure)
        assertEquals(429, rateLimit.httpStatus)
        assertEquals(OpenFoodFactsService.LookupFailure.SERVICE_UNAVAILABLE, serverFailure.failure)
        assertEquals(503, serverFailure.httpStatus)
    }

    @Test
    fun invalidOrIncompletePayloadsHaveSpecificFailures() {
        val malformed = lookupFailure(code = 200, body = "not json")
        val missingNutrition = lookupFailure(
            code = 200,
            body = """{"status":1,"product":{"product_name":"Test"}}"""
        )
        val emptyNutrition = lookupFailure(
            code = 200,
            body = """{"status":1,"product":{"product_name":"Test","nutriments":{}}}"""
        )

        assertEquals(OpenFoodFactsService.LookupFailure.UNEXPECTED_RESPONSE, malformed.failure)
        assertEquals(OpenFoodFactsService.LookupFailure.MISSING_NUTRITION, missingNutrition.failure)
        assertEquals(OpenFoodFactsService.LookupFailure.MISSING_NUTRITION, emptyNutrition.failure)
    }

    @Test
    fun offlineAndTimeoutHaveDifferentGuidance() {
        for ((cause, expected) in listOf(
            java.net.UnknownHostException("private host") to OpenFoodFactsService.LookupFailure.OFFLINE,
            java.net.SocketTimeoutException("private host") to OpenFoodFactsService.LookupFailure.TIMEOUT,
            java.net.ConnectException("refused") to OpenFoodFactsService.LookupFailure.NETWORK
        )) {
            val client = OkHttpClient.Builder().addInterceptor { throw cause }.build()
            try {
                runBlocking { OpenFoodFactsService.lookup("3017620422003", client, TEST_BASE_URL) }
                fail("Expected network error")
            } catch (error: OpenFoodFactsService.LookupException) {
                assertEquals(expected, error.failure)
            }
        }
    }

    @Test
    fun networkFailureRemainsDistinct() {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("offline") }
            .build()

        val error = try {
            runBlocking {
                OpenFoodFactsService.lookup(
                    barcode = "3017620422003",
                    client = client,
                    baseUrl = TEST_BASE_URL
                )
            }
            fail("Expected lookup to fail")
            throw AssertionError("unreachable")
        } catch (error: OpenFoodFactsService.LookupException) {
            error
        }

        assertEquals(OpenFoodFactsService.LookupFailure.NETWORK, error.failure)
        assertTrue(error.cause is IOException)
    }

    @Test
    fun successfulLookupPreservesNumericCodeAndParsesFlexibleNutrients() = runBlocking {
        val captured = AtomicReference<Request>()
        val client = responseClient(
            code = 200,
            body = """
                {
                  "status":"1",
                  "product":{
                    "product_name":"Test Bar",
                    "brands":"Acme",
                    "quantity":"250 g",
                    "ingredients_text":"Oats, milk",
                    "allergens_tags":["en:milk"],
                    "traces_tags":["en:nuts"],
                    "nutriscore_grade":"a",
                    "nova_group":2,
                    "ecoscore_grade":"b",
                    "labels_tags":["en:organic"],
                    "categories_tags":["en:cereals"],
                    "image_front_url":"https://images.openfoodfacts.org/test.jpg",
                    "serving_quantity":"50",
                    "nutriments":{
                      "energy-kcal_100g":"200",
                      "proteins_100g":"10,0",
                      "carbohydrates_100g":30,
                      "fat_serving":"4.5",
                      "sodium_100g":0.5
                    }
                  }
                }
            """.trimIndent(),
            capturedRequest = captured
        )

        val result = OpenFoodFactsService.lookup(
            barcode = " 0012345678905 ",
            client = client,
            baseUrl = TEST_BASE_URL
        )

        assertEquals("Acme Test Bar", result.name)
        assertEquals(100, result.calories)
        assertEquals(5.0, result.protein, 0.0001)
        assertEquals(15.0, result.carbs, 0.0001)
        assertEquals(4.5, result.fat, 0.0001)
        assertEquals(50.0, result.servingSizeGrams, 0.0001)
        assertEquals(250.0, result.sodium!!, 0.0001)
        assertEquals(listOf("serving", "package"), result.servingUnitOptions.map { it.unit })
        assertEquals("0012345678905", result.productMetadata?.barcode)
        assertEquals("250 g", result.productMetadata?.packageQuantity)
        assertEquals("Oats, milk", result.productMetadata?.ingredientsText)
        assertEquals(listOf("Milk"), result.productMetadata?.allergens)
        assertEquals(listOf("Nuts"), result.productMetadata?.traces)
        assertEquals("A", result.productMetadata?.nutriScore)
        assertEquals(2, result.productMetadata?.novaGroup)
        assertEquals("B", result.productMetadata?.ecoScore)
        assertEquals(listOf("Organic"), result.productMetadata?.labels)
        assertEquals(listOf("Cereals"), result.productMetadata?.categories)

        val request = captured.get()
        assertNotNull(request)
        assertEquals("/api/v2/product/0012345678905.json", request.url.encodedPath)
        assertEquals(
            "product_name,generic_name,brands,quantity,product_quantity,product_quantity_unit," +
                "serving_size,serving_quantity,nutriments,ingredients_text,allergens_tags,traces_tags," +
                "nutriscore_grade,nova_group,ecoscore_grade,labels_tags,categories_tags,image_front_url",
            request.url.queryParameter("fields")
        )
        assertEquals(
            "FudAI/${BuildConfig.VERSION_NAME} (https://fud-ai.app)",
            request.header("User-Agent")
        )
    }

    @Test
    fun lookupWithImageDownloadsTrustedProductPhoto() = runBlocking {
        val expectedImage = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == "images.openfoodfacts.org") {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("test image")
                        .body(expectedImage.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("test product")
                        .body(
                            """{"status":1,"product":{"product_name":"Test","serving_quantity":100,"image_front_url":"https://images.openfoodfacts.org/test.jpg","nutriments":{"energy-kcal_100g":100,"proteins_100g":1,"carbohydrates_100g":2,"fat_100g":3}}}"""
                                .toResponseBody(JSON_MEDIA_TYPE)
                        )
                        .build()
                }
            }
            .build()

        val result = OpenFoodFactsService.lookupWithImage(
            barcode = "0012345678901",
            client = client,
            baseUrl = TEST_BASE_URL
        )

        assertArrayEquals(expectedImage, result.productImageBytes)
    }

    @Test
    fun barcodeValidationAcceptsOnlyBoundedAsciiDigits() {
        assertEquals("0012345678905", OpenFoodFactsService.normalizedBarcode(" 0012345678905 "))
        assertNull(OpenFoodFactsService.normalizedBarcode(""))
        assertNull(OpenFoodFactsService.normalizedBarcode("123 456"))
        assertNull(OpenFoodFactsService.normalizedBarcode("123/456"))
        assertNull(OpenFoodFactsService.normalizedBarcode("١٢٣٤٥٦٧٨"))
        assertNull(OpenFoodFactsService.normalizedBarcode("1".repeat(25)))
    }

    private fun lookupFailure(code: Int, body: String): OpenFoodFactsService.LookupException {
        val client = responseClient(code = code, body = body)
        return try {
            runBlocking {
                OpenFoodFactsService.lookup(
                    barcode = "3017620422003",
                    client = client,
                    baseUrl = TEST_BASE_URL
                )
            }
            fail("Expected lookup to fail")
            throw AssertionError("unreachable")
        } catch (error: OpenFoodFactsService.LookupException) {
            error
        }
    }

    private fun responseClient(
        code: Int,
        body: String,
        capturedRequest: AtomicReference<Request>? = null
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            capturedRequest?.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test response")
                .body(body.toResponseBody(JSON_MEDIA_TYPE))
                .build()
        }
        .build()

    private companion object {
        val TEST_BASE_URL = "https://example.test/".toHttpUrl()
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
