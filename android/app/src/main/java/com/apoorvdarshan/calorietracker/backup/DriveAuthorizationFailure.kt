package com.apoorvdarshan.calorietracker.backup

internal enum class DriveAuthorizationFailure { UNREGISTERED_ANDROID_CLIENT, OTHER }

internal class DriveAuthorizationException(val kind: DriveAuthorizationFailure) : IllegalStateException()

internal fun classifyDriveAuthorizationFailure(statusMessage: String?): DriveAuthorizationFailure =
    if (statusMessage?.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) == true) {
        DriveAuthorizationFailure.UNREGISTERED_ANDROID_CLIENT
    } else {
        DriveAuthorizationFailure.OTHER
    }
