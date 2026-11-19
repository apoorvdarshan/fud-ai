package com.apoorvdarshan.calorietracker.backup

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

data class CloudBackupUi(
    val enabled: Boolean = false,
    val lastAt: String? = null,
    val accountEmail: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val existingCloudBackup: Boolean = false,
)

/** The signed-in account has no Fud AI backup in Drive. */
class CloudBackupNotFoundException : IllegalStateException("No Drive backup found")

/** No Drive access token on this phone; the user has to sign in again. */
class CloudBackupSignInRequiredException : IllegalStateException("Sign in to Google Drive first")

/**
 * Text for the backup error dialog. VM errors such as OutOfMemoryError (#484)
 * and blank messages mean nothing to users, so they get [fallback] instead.
 */
fun cloudBackupErrorMessage(failure: Throwable, fallback: String, noSpace: String): String =
    if (failure is CloudBackupNoSpaceException) {
        noSpace
    } else {
        failure.message?.takeIf { failure !is Error && it.isNotBlank() } ?: fallback
    }

class CloudBackupCoordinator(
    private val context: Context,
    private val prefs: PreferencesStore,
    private val images: FoodImageStore,
    private val keyStore: KeyStore,
    /** The reference-checked photo cleanup, run once a restore has written the restored diary. */
    private val pruneUnusedPhotos: suspend () -> Unit = {},
    private val drive: DriveCloudBackupClient = DriveCloudBackupClient(),
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

    suspend fun authorize(
        activity: Activity,
        account: android.accounts.Account,
    ): DriveCloudBackupClient.AuthOutcome {
        val outcome = drive.authorize(activity, account)
        if (outcome is DriveCloudBackupClient.AuthOutcome.Token) {
            onAccessToken(outcome.accessToken)
        }
        return outcome
    }

    fun accountPickerIntent(): android.content.Intent = drive.accountPickerIntent()

    fun accountFromPickerResult(data: android.content.Intent?): android.accounts.Account? =
        drive.accountFromPickerResult(data)

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
            // A restore turns backup on only when it succeeds. If it failed with
            // backup already on, the next auto-backup would replace the Drive
            // copy with this phone's data.
            if (!restoreIfPresent) prefs.setCloudBackupEnabled(true)
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

    /** Turn backup off and disconnect Google. Leaves the Drive file in place. */
    suspend fun disable(activity: Activity? = null) = signOut(activity)

    /**
     * Revoke Google access and clear local Drive backup session state.
     * Does not delete the backup file in Drive (use [deleteCloudBackup] for that).
     */
    suspend fun signOut(activity: Activity? = null) {
        accessToken?.let { runCatching { drive.revoke(it) } }
        accessToken = null
        keyStore.setCloudBackupAccessToken(null)
        prefs.setCloudBackupEnabled(false)
        prefs.setCloudBackupAccountEmail(null)
        prefs.setCloudBackupFileId(null)
        prefs.setCloudBackupLastAt(null)
        prefs.setCloudBackupLastHash(null)
        _ui.value = _ui.value.copy(existingCloudBackup = false)
        val sessionContext = activity ?: context
        drive.clearSignInSession(sessionContext)
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
                ?: throw CloudBackupNotFoundException()
            restoreFromDrive(token, fileId)
            refresh()
        }.also { busy(false) }
    }

    /**
     * Looks up the signed-in account's backup without restoring or uploading anything, and
     * remembers its file for [restoreNow]. Unlike the lookup right after sign-in, a failed
     * request is a failure here, never "no backup".
     */
    suspend fun findBackup(): Result<DriveCloudBackupClient.BackupFile?> = mutex.withLock {
        runCatching {
            val file = drive.findBackupFile(requireToken())
            if (file != null) prefs.setCloudBackupFileId(file.id)
            _ui.value = _ui.value.copy(existingCloudBackup = file != null)
            file
        }
    }

    /**
     * Turns backup off and forgets this phone's Drive session (token, account, file and last
     * backup), leaving the Drive file and the Google grant alone. Waits for a running backup:
     * an upload ends by turning backup back on, which after Delete All Data would let the next
     * auto-backup replace the user's Drive copy with the emptied phone.
     */
    suspend fun forgetSession() = mutex.withLock {
        accessToken = null
        keyStore.setCloudBackupAccessToken(null)
        prefs.setCloudBackupEnabled(false)
        prefs.setCloudBackupAccountEmail(null)
        prefs.setCloudBackupFileId(null)
        prefs.setCloudBackupLastAt(null)
        prefs.setCloudBackupLastHash(null)
        _ui.value = _ui.value.copy(existingCloudBackup = false, message = null)
        refresh()
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
            prefs.setCloudBackupAccountEmail(null)
            prefs.setCloudBackupEnabled(false)
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
            // Checked again under the lock: a restore, delete or sign-out that
            // ran while this waited may have turned backup off.
            if (prefs.cloudBackupEnabled.first()) {
                runCatching { upload(requireToken(), skipIfUnchanged = true) }
            }
        }
        refresh()
    }

    // The archive goes through a temp file so photo-heavy diaries never sit in
    // the heap all at once (#484). Runs on IO: callers launch from the main thread.
    private suspend fun upload(token: String, skipIfUnchanged: Boolean = false) = withContext(Dispatchers.IO) {
        val values = prefs.snapshotCloudBackupValues()
        val photos = snapshotPhotos()
        val hash = CloudBackupArchive.contentHash(values, photos)
        if (skipIfUnchanged && hash == prefs.cloudBackupLastHash.first()) return@withContext
        val fileId = withTempArchive { zip ->
            CloudBackupArchive.pack(
                destination = zip,
                values = values,
                photos = photos,
                exportedAt = Instant.now().toString(),
                appVersion = BuildConfig.VERSION_NAME,
            )
            drive.upload(token, zip, prefs.cloudBackupFileId.first())
        }
        prefs.setCloudBackupFileId(fileId)
        prefs.setCloudBackupLastHash(hash)
        prefs.setCloudBackupLastAt(Instant.now().toString())
        prefs.setCloudBackupEnabled(true)
    }

    private suspend fun restoreFromDrive(token: String, fileId: String) {
        withContext(Dispatchers.IO) {
            withTempArchive { zip ->
                drive.download(token, fileId, zip)
                applyArchive(zip, fileId)
            }
            // The restore leaves photos the restored diary does not use in
            // place; remove them now so the next backup does not carry them.
            // If this fails, the startup prune removes them instead.
            runCatching { pruneUnusedPhotos() }
        }
    }

    private suspend fun applyArchive(zip: File, fileId: String) {
        val email = prefs.cloudBackupAccountEmail.first()
        CloudBackupRestore.apply(zip, images) { document ->
            prefs.restoreCloudBackupValues(document.payload.values)
            // Device-specific Health Connect tokens must not transfer. Mark food
            // restore done so Health Connect skips a second import of the same IDs.
            prefs.clearHealthChangesToken()
            prefs.setHealthFoodRestoreDone(true)
            prefs.setCloudBackupEnabled(true)
            prefs.setCloudBackupAccountEmail(email)
            prefs.setCloudBackupFileId(fileId)
            prefs.setCloudBackupLastHash(document.content_sha256)
            prefs.setCloudBackupLastAt(document.exported_at)
            refresh()
        }
    }

    private fun snapshotPhotos(): List<CloudBackupPhoto> =
        images.listedFilenames().mapNotNull { name ->
            val safe = CloudBackupPolicy.safePhotoName(name) ?: return@mapNotNull null
            val file = images.file(safe)
            if (file.isFile) CloudBackupPhoto.fromFile(safe, file) else null
        }

    private inline fun <T> withTempArchive(block: (File) -> T): T {
        // Operations hold [mutex], so anything already here is an orphan from a killed process.
        val dir = File(context.cacheDir, TEMP_DIR)
        CloudBackupArchive.deleteTempArchives(dir)
        return CloudBackupArchive.withTempArchive(dir, block)
    }

    private suspend fun requireToken(): String =
        accessToken ?: keyStore.cloudBackupAccessToken()?.also { accessToken = it }
        ?: throw CloudBackupSignInRequiredException()

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

    private companion object {
        const val TEMP_DIR = "cloud-backup"
    }
}
