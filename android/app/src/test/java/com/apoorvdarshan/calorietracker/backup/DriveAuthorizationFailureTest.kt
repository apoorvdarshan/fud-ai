package com.apoorvdarshan.calorietracker.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveAuthorizationFailureTest {
    @Test fun identifiesGoogleAndroidRegistrationFailure() {
        assertEquals(
            DriveAuthorizationFailure.UNREGISTERED_ANDROID_CLIENT,
            classifyDriveAuthorizationFailure("Unknown error [status=UNREGISTERED_ON_API_CONSOLE]")
        )
    }

    @Test fun doesNotTreatOtherOrMissingStatusesAsRegistrationFailures() {
        assertEquals(DriveAuthorizationFailure.OTHER, classifyDriveAuthorizationFailure(null))
        assertEquals(DriveAuthorizationFailure.OTHER, classifyDriveAuthorizationFailure("NETWORK_ERROR"))
    }
}
