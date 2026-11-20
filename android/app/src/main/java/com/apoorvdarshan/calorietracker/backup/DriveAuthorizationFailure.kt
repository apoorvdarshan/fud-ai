package com.apoorvdarshan.calorietracker.backup

internal enum class DriveAuthorizationFailure { UNREGISTERED_ANDROID_CLIENT, OTHER }

internal class DriveAuthorizationException(val kind: DriveAuthorizationFailure) : IllegalStateException()

internal fun classifyDriveAuthorizationFailure(statusMessage: String?): DriveAuthorizationFailure =
    if (statusMessage?.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) == true) {
        DriveAuthorizationFailure.UNREGISTERED_ANDROID_CLIENT
    } else {
        DriveAuthorizationFailure.OTHER
    }

/**
 * One Drive consent completion. Returns the message Settings should show, or null
 * when sign-in succeeded. Cancellation is rethrown, never converted to an error,
 * and an unregistered Android client keeps its dedicated message.
 */
internal suspend fun driveConsentCompletion(
    unregisteredMessage: String,
    signInFailedMessage: String,
    finish: suspend () -> Boolean,
): String? = try {
    if (finish()) null else signInFailedMessage
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    if ((failure as? DriveAuthorizationException)?.kind == DriveAuthorizationFailure.UNREGISTERED_ANDROID_CLIENT) {
        unregisteredMessage
    } else {
        signInFailedMessage
    }
}
