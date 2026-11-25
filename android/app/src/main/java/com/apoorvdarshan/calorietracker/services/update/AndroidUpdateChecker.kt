package com.apoorvdarshan.calorietracker.services.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.apoorvdarshan.calorietracker.MainActivity
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class AndroidUpdateState {
    object Idle : AndroidUpdateState()
    object Checking : AndroidUpdateState()
    data class UpToDate(val current: String, val latest: String?) : AndroidUpdateState()
    data class Available(val current: String, val latest: String) : AndroidUpdateState()
    data class Failed(val current: String) : AndroidUpdateState()

    val isUpdateAvailable: Boolean get() = this is Available

    /** A transient failed refresh does not erase an already confirmed update. */
    fun resolving(result: AndroidUpdateState): AndroidUpdateState =
        if (this is Available && result is Failed) this else result
}

object AndroidUpdateChecker {
    const val RELEASE_PACKAGE_NAME = "com.apoorvdarshan.calorietracker"
    const val ACTION_OPEN_PLAY_STORE =
        "com.apoorvdarshan.calorietracker.action.OPEN_PLAY_STORE"
    const val PLAY_STORE_WEB_URL =
        "https://play.google.com/store/apps/details?id=$RELEASE_PACKAGE_NAME"
    const val PLAY_STORE_MARKET_URL = "market://details?id=$RELEASE_PACKAGE_NAME"

    /** Opens the Play listing (market:// with https fallback), same as About → Update. */
    fun openPlayStore(context: Context) {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(PLAY_STORE_MARKET_URL)
        ).apply {
            setPackage("com.android.vending")
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        runCatching { context.startActivity(marketIntent) }.onFailure {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEB_URL))
            )
        }
    }

    /** Explicit activity PendingIntent so update notifications stay non-implicit (CodeQL). */
    fun playStoreLaunchPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAY_STORE
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName
            ?.substringBefore("-")
            ?.ifBlank { null }
            ?: "Unknown"

    suspend fun check(context: Context, current: String): AndroidUpdateState {
        if (context.packageName != RELEASE_PACKAGE_NAME) {
            return AndroidUpdateState.UpToDate(current = current, latest = null)
        }
        return try {
            val info = AppUpdateManagerFactory.create(context).appUpdateInfo.awaitTask()
            when (info.updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE,
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                    // We open the listing, not an in-app flexible/immediate update flow.
                    AndroidUpdateState.Available(current = current, latest = info.playVersionLabel())
                UpdateAvailability.UPDATE_NOT_AVAILABLE ->
                    AndroidUpdateState.UpToDate(current = current, latest = current)
                else -> AndroidUpdateState.Failed(current)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AndroidUpdateState.Failed(current)
        }
    }

    private fun AppUpdateInfo.playVersionLabel(): String {
        val versionCode = availableVersionCode()
        return if (versionCode > 0) "build $versionCode" else "Google Play"
    }

    private suspend fun <T> Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.cancel(CancellationException("Play update check was cancelled"))
                }
            }
    }
}
