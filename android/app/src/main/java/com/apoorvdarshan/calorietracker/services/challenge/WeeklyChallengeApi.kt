package com.apoorvdarshan.calorietracker.services.challenge

import com.apoorvdarshan.calorietracker.services.SecureHttpClient
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeAggregate
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeCategory
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeCreateProfileRequest
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeLeaderboard
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeProfileRequest
import com.apoorvdarshan.calorietracker.models.WeeklyChallengePublicProfile
import com.apoorvdarshan.calorietracker.models.WeeklyChallengeReportRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object WeeklyChallengeJson {
    val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun encodeCreateProfile(request: WeeklyChallengeCreateProfileRequest): String =
        codec.encodeToString(WeeklyChallengeCreateProfileRequest.serializer(), request)

    fun encodeProfile(request: WeeklyChallengeProfileRequest): String =
        codec.encodeToString(WeeklyChallengeProfileRequest.serializer(), request)

    fun encodeScore(score: WeeklyChallengeAggregate): String =
        codec.encodeToString(WeeklyChallengeAggregate.serializer(), score)

    fun <T> decode(serializer: DeserializationStrategy<T>, value: String): T =
        codec.decodeFromString(serializer, value)

    fun <T> encode(serializer: SerializationStrategy<T>, value: T): String =
        codec.encodeToString(serializer, value)
}

internal class WeeklyChallengeNetworkException(cause: IOException) : IOException(cause)

internal class WeeklyChallengeApiException(
    val errorCode: String?,
    val statusCode: Int,
    /** Bounded, control-character-free server copy retained for validation drift diagnostics. */
    val serverMessage: String? = null
) : Exception(serverMessage ?: "Weekly Challenge request failed")

@Serializable
internal data class WeeklyChallengeCreateProfileResponse(
    val participantId: String,
    val bearerToken: String,
    val profile: WeeklyChallengePublicProfile
)

@Serializable
internal data class WeeklyChallengeProfileResponse(
    val profile: WeeklyChallengePublicProfile
)

@Serializable
internal data class WeeklyChallengeScoreResponse(
    val score: WeeklyChallengeAggregate
)

@Serializable
internal data class WeeklyChallengeDeleteResponse(
    val deleted: Boolean
)

@Serializable
private data class WeeklyChallengeReportResponse(
    val report: WeeklyChallengeReportReceipt
)

@Serializable
private data class WeeklyChallengeReportReceipt(
    val reportId: String,
    val reportedParticipantId: String,
    val reason: String,
    val status: String,
    val createdAt: String
)

@Serializable
private data class WeeklyChallengeErrorEnvelope(
    val error: WeeklyChallengeErrorBody? = null
)

@Serializable
private data class WeeklyChallengeErrorBody(
    val code: String? = null,
    val message: String? = null,
    val fields: List<String> = emptyList()
)

internal class WeeklyChallengeApi(
    private val baseUrl: String = BASE_URL,
    private val client: OkHttpClient = SecureHttpClient.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun createProfile(request: WeeklyChallengeCreateProfileRequest): WeeklyChallengeCreateProfileResponse =
        execute(
            request = jsonRequest(
                method = "POST",
                path = "/profile",
                body = WeeklyChallengeJson.encodeCreateProfile(request)
            ),
            serializer = WeeklyChallengeCreateProfileResponse.serializer()
        )

    suspend fun updateProfile(
        bearerToken: String,
        request: WeeklyChallengeProfileRequest
    ): WeeklyChallengePublicProfile = execute(
        request = jsonRequest(
            method = "PATCH",
            path = "/profile",
            body = WeeklyChallengeJson.encodeProfile(request),
            bearerToken = bearerToken
        ),
        serializer = WeeklyChallengeProfileResponse.serializer()
    ).profile

    suspend fun deleteProfile(bearerToken: String): Boolean = execute(
        request = Request.Builder()
            .url("$baseUrl/profile")
            .delete()
            .header("Accept", JSON_MEDIA_TYPE.toString())
            .header("Authorization", "Bearer $bearerToken")
            .build(),
        serializer = WeeklyChallengeDeleteResponse.serializer()
    ).deleted

    suspend fun putWeeklyScore(
        bearerToken: String,
        score: WeeklyChallengeAggregate
    ): WeeklyChallengeAggregate = execute(
        request = jsonRequest(
            method = "PUT",
            path = "/weekly-score",
            body = WeeklyChallengeJson.encodeScore(score),
            bearerToken = bearerToken
        ),
        serializer = WeeklyChallengeScoreResponse.serializer()
    ).score

    suspend fun leaderboard(
        category: WeeklyChallengeCategory,
        weekStart: String,
        bearerToken: String
    ): WeeklyChallengeLeaderboard {
        val url = "$baseUrl/leaderboard".toHttpUrl().newBuilder()
            .addQueryParameter("category", category.apiValue)
            .addQueryParameter("weekStart", weekStart)
            .addQueryParameter("limit", "100")
            .build()
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", JSON_MEDIA_TYPE.toString())
        builder.header("Authorization", "Bearer $bearerToken")
        return execute(builder.build(), WeeklyChallengeLeaderboard.serializer())
    }

    suspend fun report(
        bearerToken: String,
        report: WeeklyChallengeReportRequest
    ) {
        execute(
            request = jsonRequest(
                method = "POST",
                path = "/reports",
                body = WeeklyChallengeJson.encode(WeeklyChallengeReportRequest.serializer(), report),
                bearerToken = bearerToken
            ),
            serializer = WeeklyChallengeReportResponse.serializer()
        )
    }

    private fun jsonRequest(
        method: String,
        path: String,
        body: String,
        bearerToken: String? = null
    ): Request {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .method(method, body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", JSON_MEDIA_TYPE.toString())
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        return builder.build()
    }

    private suspend fun <T> execute(
        request: Request,
        serializer: DeserializationStrategy<T>
    ): T = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw WeeklyChallengeNetworkException(error)
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val error = runCatching {
                    WeeklyChallengeJson.decode(WeeklyChallengeErrorEnvelope.serializer(), body).error
                }.getOrNull()
                throw WeeklyChallengeApiException(
                    errorCode = error?.code,
                    statusCode = it.code,
                    serverMessage = error?.message.safeServerMessage()
                )
            }
            runCatching { WeeklyChallengeJson.decode(serializer, body) }
                .getOrElse { decodeError ->
                    throw WeeklyChallengeApiException(errorCode = "invalid_response", statusCode = it.code)
                }
        }
    }

    companion object {
        const val BASE_URL = "https://fud-ai.app/api/challenge/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun String?.safeServerMessage(): String? {
    val value = this?.trim().orEmpty()
    return value.takeIf {
        it.length in 1..300 && it.none { character -> Character.isISOControl(character) }
    }
}
