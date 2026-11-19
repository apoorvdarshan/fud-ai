package com.apoorvdarshan.calorietracker.backup

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Google Drive appData folder client. OAuth is requested only when the user
 * turns on Google Drive Backup or taps Restore on the onboarding Welcome back
 * screen — never at install or first launch.
 */
class DriveCloudBackupClient(
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

    /** Always shows every Google account on the device (not Continue for the last one). */
    fun accountPickerIntent(): Intent {
        val options = AccountPicker.AccountChooserOptions.Builder()
            .setAllowableAccountsTypes(listOf("com.google"))
            .setAlwaysShowAccountPicker(true)
            .build()
        return AccountPicker.newChooseAccountIntent(options)
    }

    fun accountFromPickerResult(data: Intent?): Account? {
        val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.takeIf { it.isNotBlank() }
            ?: return null
        val type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)?.takeIf { it.isNotBlank() }
            ?: "com.google"
        return Account(name, type)
    }

    suspend fun authorize(activity: Activity, account: Account): AuthOutcome {
        val scopes = listOf(
            Scope(DRIVE_APPDATA_SCOPE),
            Scope(EMAIL_SCOPE),
        )
        // Drive calls run on this device. Do not request an unused server auth code.
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .setAccount(account)
            .setOptOutIncludingGrantedScopes(true)
            .build()
        return mapAuthorizationFailure {
            outcome(Identity.getAuthorizationClient(activity).authorize(request).await())
        }
    }

    /** Clears One Tap / Identity cached Google session so the next pick is fresh. */
    suspend fun clearSignInSession(context: android.content.Context) {
        runCatching { Identity.getSignInClient(context).signOut().await() }
    }

    fun parseAuthorizationResult(activity: Activity, data: Intent?): String? {
        if (data == null) return null
        return mapAuthorizationFailure {
            Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken
        }
    }

    private inline fun <T> mapAuthorizationFailure(action: () -> T): T {
        try {
            return action()
        } catch (failure: ApiException) {
            val kind = classifyDriveAuthorizationFailure(failure.statusMessage)
            // Do not print the exception or status message: it may include account details.
            android.util.Log.w("FudAIDriveAuth", "Authorization failed: ${failure.statusCode}, ${kind.name}")
            throw DriveAuthorizationException(kind)
        }
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

    /** The backup file in the appData folder; [modifiedTime] is when it was last uploaded. */
    data class BackupFile(val id: String, val modifiedTime: Instant?)

    suspend fun findBackupFileId(accessToken: String): String? = findBackupFile(accessToken)?.id

    /** Null when the account has no backup. A failed request throws, so it never reads as "none". */
    suspend fun findBackupFile(accessToken: String): BackupFile? = withContext(Dispatchers.IO) {
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
            val file = listJson.decodeFromString<DriveFileList>(response.body.string()).files.firstOrNull()
                ?: return@use null
            val id = file.id.takeIf { it.isNotBlank() } ?: return@use null
            BackupFile(id, file.modifiedTime?.let { runCatching { Instant.parse(it) }.getOrNull() })
        }
    }

    @Serializable
    private data class DriveFileList(val files: List<DriveFile> = emptyList())

    @Serializable
    private data class DriveFile(val id: String = "", val modifiedTime: String? = null)

    suspend fun upload(accessToken: String, zip: File, existingFileId: String?): String =
        withContext(Dispatchers.IO) {
            if (existingFileId != null) {
                update(accessToken, existingFileId, zip)
                existingFileId
            } else {
                create(accessToken, zip)
            }
        }

    /** Streams the backup into [destination]; it can be larger than the app's heap (#484). */
    suspend fun download(accessToken: String, fileId: String, destination: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive download failed (${response.code})")
            response.body.byteStream().use { input ->
                destination.outputStream().use { input.copyTo(it) }
            }
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

    /** The token goes in the form body: URLs end up in logs. */
    suspend fun revoke(accessToken: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/revoke")
            .post(FormBody.Builder().add("token", accessToken).build())
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 400) {
                error("Google token revoke failed (${response.code})")
            }
        }
    }

    private fun create(accessToken: String, zip: File): String {
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
                    zip.asRequestBody("application/zip".toMediaType())
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

    private fun update(accessToken: String, fileId: String, zip: File) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $accessToken")
            .patch(zip.asRequestBody("application/zip".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive update failed (${response.code})")
        }
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
        private const val DRIVE_FILE_QUERY = "name%3D%27fudai-backup.zip%27"
        private val listJson = Json { ignoreUnknownKeys = true }
    }
}
