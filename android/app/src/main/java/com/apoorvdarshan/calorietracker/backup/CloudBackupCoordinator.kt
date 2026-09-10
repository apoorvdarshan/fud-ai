package com.apoorvdarshan.calorietracker.backup

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.apoorvdarshan.calorietracker.BuildConfig
import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

data class CloudBackupUi(
    val enabled: Boolean = false,
    val lastAt: String? = null,
    val accountEmail: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val existingCloudBackup: Boolean = false,
)

class CloudBackupCoordinator(
    private val context: Context,
    private val prefs: PreferencesStore,
    private val images: FoodImageStore,
    private val keyStore: KeyStore,
    private val drive: DriveCloudBackupClient = DriveCloudBackupClient(
        BuildConfig.CLOUD_BACKUP_WEB_CLIENT_ID
    ),
) {
    private val mutex = Mutex()
    private val _ui = MutableStateFlow(CloudBackupUi())
    val ui: StateFlow<CloudBackupUi> = _ui.asStateFlow()
    @Volatile private var accessToken: String? = keyStore.cloudBackupAccessToken()

    suspend fun refresh() {
        _ui.value = _ui.value.copy(
            enabled = prefs.cloudBackupEnabled.first(),
            lastAt = prefs.cloudBackupLastAt.first(),
            accountEmail = prefs.cloudBackupAccountEmail.first(),
        )
    }

    suspend fun authorize(activity: Activity): DriveCloudBackupClient.AuthOutcome {
        val outcome = drive.authorize(activity)
        if (outcome is DriveCloudBackupClient.AuthOutcome.Token) {
            onAccessToken(outcome.accessToken)
        }
        return outcome
    }

    suspend fun finishAuthorization(activity: Activity, data: android.content.Intent?): Boolean {
        val token = drive.parseAuthorizationResult(activity, data) ?: return false
        onAccessToken(token)
        return true
    }

    private suspend fun onAccessToken(token: String) {
        accessToken = token
        keyStore.setCloudBackupAccessToken(token)
        val email = runCatching { drive.accountEmail(token) }.getOrNull()
        prefs.setCloudBackupAccountEmail(email)
        val fileId = runCatching { drive.findBackupFileId(token) }.getOrNull()
        if (fileId != null) prefs.setCloudBackupFileId(fileId)
        _ui.value = _ui.value.copy(
            accountEmail = email,
            existingCloudBackup = fileId != null,
        )
    }

    suspend fun enableAfterAuth(restoreIfPresent: Boolean): Result<Unit> = mutex.withLock {
        runCatching {
            busy(true)
            prefs.setCloudBackupEnabled(true)
            val token = requireToken()
            val fileId = prefs.cloudBackupFileId.first() ?: drive.findBackupFileId(token)
            if (fileId != null && restoreIfPresent) {
                restoreFromDrive(token, fileId)
            } else {
                upload(token)
            }
            refresh()
        }.also { busy(false) }
    }

    suspend fun disable() {
        accessToken?.let { runCatching { drive.revoke(it) } }
        prefs.setCloudBackupEnabled(false)
        accessToken = null
        keyStore.setCloudBackupAccessToken(null)
        refresh()
    }

    suspend fun backupNow(): Result<Unit> = mutex.withLock {
        runCatching {
            busy(true)
            upload(requireToken())
            refresh()
        }.also { busy(false) }
    }

    suspend fun restoreNow(): Result<Unit> = mutex.withLock {
        runCatching {
            busy(true)
            val token = requireToken()
            val fileId = prefs.cloudBackupFileId.first()
                ?: drive.findBackupFileId(token)
                ?: error("No Drive backup found")
            restoreFromDrive(token, fileId)
            refresh()
        }.also { busy(false) }
    }

    suspend fun deleteCloudBackup(): Result<Unit> = mutex.withLock {
        runCatching {
            busy(true)
            val token = requireToken()
            val fileId = prefs.cloudBackupFileId.first() ?: drive.findBackupFileId(token)
            if (fileId != null) drive.delete(token, fileId)
            prefs.setCloudBackupFileId(null)
            prefs.setCloudBackupLastAt(null)
            prefs.setCloudBackupLastHash(null)
            accessToken?.let { runCatching { drive.revoke(it) } }
            accessToken = null
            keyStore.setCloudBackupAccessToken(null)
            refresh()
        }.also { busy(false) }
    }

    suspend fun autoBackupIfNeeded() {
        if (!prefs.cloudBackupEnabled.first()) return
        if (!onUnmeteredNetwork()) return
        val lastAt = prefs.cloudBackupLastAt.first()
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (lastAt != null &&
            Instant.now().toEpochMilli() - lastAt.toEpochMilli() < CloudBackupPolicy.MIN_AUTO_BACKUP_INTERVAL_MS
        ) return
        mutex.withLock {
            runCatching { upload(requireToken(), skipIfUnchanged = true) }
        }
        refresh()
    }

    private suspend fun upload(token: String, skipIfUnchanged: Boolean = false) {
        val values = prefs.snapshotCloudBackupValues()
        val photos = snapshotPhotos()
        val hash = CloudBackupArchive.contentHash(values, photos)
        if (skipIfUnchanged && hash == prefs.cloudBackupLastHash.first()) return
        val zip = CloudBackupArchive.pack(
            values = values,
            photos = photos,
            exportedAt = Instant.now().toString(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        val fileId = drive.upload(token, zip, prefs.cloudBackupFileId.first())
        prefs.setCloudBackupFileId(fileId)
        prefs.setCloudBackupLastHash(hash)
        prefs.setCloudBackupLastAt(Instant.now().toString())
        prefs.setCloudBackupEnabled(true)
    }

    private suspend fun restoreFromDrive(token: String, fileId: String) {
        val zip = drive.download(token, fileId)
        applyArchive(zip)
        prefs.setCloudBackupFileId(fileId)
        prefs.setCloudBackupEnabled(true)
    }

    suspend fun applyArchive(zip: ByteArray) {
        val unpack = CloudBackupArchive.unpack(zip)
        val enabled = true
        val email = prefs.cloudBackupAccountEmail.first()
        val fileId = prefs.cloudBackupFileId.first()
        images.clearAll()
        unpack.photos.forEach { (name, bytes) -> images.restoreBytes(name, bytes) }
        prefs.restoreCloudBackupValues(unpack.document.payload.values)
        // Device-specific Health Connect tokens must not transfer. Mark food
        // restore done so Health Connect skips a second import of the same IDs.
        prefs.clearHealthChangesToken()
        prefs.setHealthFoodRestoreDone(true)
        prefs.setCloudBackupEnabled(enabled)
        prefs.setCloudBackupAccountEmail(email)
        prefs.setCloudBackupFileId(fileId)
        prefs.setCloudBackupLastHash(unpack.document.content_sha256)
        prefs.setCloudBackupLastAt(unpack.document.exported_at)
    }

    private fun snapshotPhotos(): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        for (name in images.listedFilenames()) {
            val safe = CloudBackupPolicy.safePhotoName(name) ?: continue
            images.loadBytes(safe)?.let { out[safe] = it }
        }
        return out
    }

    private suspend fun requireToken(): String =
        accessToken ?: keyStore.cloudBackupAccessToken()?.also { accessToken = it }
        ?: error("Sign in to Google Drive first")

    private fun busy(value: Boolean) {
        _ui.value = _ui.value.copy(busy = value, message = if (value) null else _ui.value.message)
    }

    private fun onUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
