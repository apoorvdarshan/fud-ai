package com.apoorvdarshan.calorietracker.backup

import android.content.Context
import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import java.io.IOException

/** Which Google Drive call failed. The English exception message stays for logs and tests. */
enum class CloudBackupDriveFailure(@StringRes val messageRes: Int) {
    NO_ACCESS_TOKEN(R.string.cloud_backup_error_no_token),
    LIST(R.string.cloud_backup_error_list_failed),
    DOWNLOAD(R.string.cloud_backup_error_download_failed),
    DELETE(R.string.cloud_backup_error_delete_failed),
    REVOKE(R.string.cloud_backup_error_revoke_failed),
    UPLOAD(R.string.cloud_backup_error_upload_failed),
    EMPTY_RESPONSE(R.string.cloud_backup_error_empty_response),
    UPDATE(R.string.cloud_backup_error_update_failed)
}

class CloudBackupDriveException(
    val failure: CloudBackupDriveFailure,
    val httpCode: Int? = null,
    message: String
) : IllegalStateException(message)

/**
 * Text for the backup error dialog in the user's language. Typed failures resolve their own
 * copy, connectivity problems get a network hint, anything else keeps [cloudBackupErrorMessage]'s
 * behaviour (the exception message, or [fallback] for blank and VM errors).
 */
fun cloudBackupUserMessage(context: Context, failure: Throwable, fallback: String, noSpace: String): String =
    when (failure) {
        is CloudBackupFormatException -> context.getString(
            if (failure.needsNewerApp) R.string.cloud_backup_error_needs_newer_app else R.string.cloud_backup_error_not_backup
        )
        is CloudBackupNotFoundException -> context.getString(R.string.cloud_backup_error_no_backup)
        is CloudBackupSignInRequiredException -> context.getString(R.string.cloud_backup_error_sign_in_required)
        is CloudBackupDriveException -> failure.httpCode?.let { context.getString(failure.failure.messageRes, it) }
            ?: context.getString(failure.failure.messageRes)
        is CloudBackupNoSpaceException -> noSpace
        is CloudBackupPhotoWriteException -> fallback
        is IOException -> context.getString(R.string.cloud_backup_error_network)
        else -> cloudBackupErrorMessage(failure, fallback, noSpace)
    }
