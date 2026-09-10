package com.apoorvdarshan.calorietracker.backup

import android.app.Activity
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Drive appData folder client. OAuth is requested only when the user
 * turns on Google Drive Backup — never at install or first launch.
 */
class DriveCloudBackupClient(
    private val webClientId: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    sealed class AuthOutcome {
        data class Token(val accessToken: String) : AuthOutcome()
        data class Resolution(val intentSender: IntentSender) : AuthOutcome()
    }

    suspend fun authorize(activity: Activity): AuthOutcome {
        val scopes = listOf(
            Scope(DRIVE_APPDATA_SCOPE),
            Scope(EMAIL_SCOPE),
        )
        val builder = AuthorizationRequest.builder().setRequestedScopes(scopes)
        if (webClientId.isNotBlank()) {
            builder.requestOfflineAccess(webClientId)
        }
        val result = Identity.getAuthorizationClient(activity)
            .authorize(builder.build())
            .await()
        return outcome(result)
    }

    fun parseAuthorizationResult(activity: Activity, data: android.content.Intent?): String? {
        if (data == null) return null
        val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        return result.accessToken
    }

    private fun outcome(result: AuthorizationResult): AuthOutcome {
        val pending = result.pendingIntent
        return if (pending != null) {
            AuthOutcome.Resolution(pending.intentSender)
        } else {
            val token = result.accessToken ?: error("Google did not return an access token")
            AuthOutcome.Token(token)
        }
    }

    suspend fun accountEmail(accessToken: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v2/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            JSONObject(body).optString("email").takeIf { it.isNotBlank() }
        }
    }

    suspend fun findBackupFileId(accessToken: String): String? = withContext(Dispatchers.IO) {
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?spaces=appDataFolder" +
            "&q=${DRIVE_FILE_QUERY}" +
            "&fields=files(id,modifiedTime,name)" +
            "&pageSize=1"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive list failed (${response.code})")
            val body = response.body?.string() ?: return@use null
            val files = JSONObject(body).optJSONArray("files") ?: return@use null
            if (files.length() == 0) null else files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
        }
    }

    suspend fun upload(accessToken: String, zip: ByteArray, existingFileId: String?): String =
        withContext(Dispatchers.IO) {
            if (existingFileId != null) {
                update(accessToken, existingFileId, zip)
                existingFileId
            } else {
                create(accessToken, zip)
            }
        }

    suspend fun download(accessToken: String, fileId: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive download failed (${response.code})")
            response.body?.bytes() ?: error("Empty backup file")
        }
    }

    suspend fun delete(accessToken: String, fileId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                error("Drive delete failed (${response.code})")
            }
        }
    }

    suspend fun revoke(accessToken: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/revoke?token=$accessToken")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 400) {
                error("Google token revoke failed (${response.code})")
            }
        }
    }

    private fun create(accessToken: String, zip: ByteArray): String {
        val metadata = JSONObject()
            .put("name", CloudBackupPolicy.FILE_NAME)
            .put("parents", org.json.JSONArray().put("appDataFolder"))
            .toString()
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                MultipartBody.Part.create(
                    metadata.toRequestBody("application/json; charset=UTF-8".toMediaType())
                )
            )
            .addPart(
                MultipartBody.Part.create(
                    zip.toRequestBody("application/zip".toMediaType())
                )
            )
            .build()
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive upload failed (${response.code})")
            val json = JSONObject(response.body?.string() ?: error("Empty Drive create response"))
            return json.getString("id")
        }
    }

    private fun update(accessToken: String, fileId: String, zip: ByteArray) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $accessToken")
            .patch(zip.toRequestBody("application/zip".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive update failed (${response.code})")
        }
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
        private const val DRIVE_FILE_QUERY = "name%3D%27fudai-backup.zip%27"
    }
}
