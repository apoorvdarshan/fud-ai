package com.apoorvdarshan.calorietracker.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveConsentCompletionTest {
    @Test fun aFailedConsentResultShowsTheSignInError() = runTest {
        assertEquals(
            "sign-in failed",
            driveConsentCompletion("unregistered", "sign-in failed") { false }
        )
    }

    @Test fun aThrownConsentFailureShowsTheSignInError() = runTest {
        assertEquals(
            "sign-in failed",
            driveConsentCompletion("unregistered", "sign-in failed") {
                throw DriveAuthorizationException(DriveAuthorizationFailure.OTHER)
            }
        )
    }

    @Test fun anUnregisteredAndroidClientKeepsItsDedicatedMessage() = runTest {
        assertEquals(
            "unregistered",
            driveConsentCompletion("unregistered", "sign-in failed") {
                throw DriveAuthorizationException(DriveAuthorizationFailure.UNREGISTERED_ANDROID_CLIENT)
            }
        )
    }

    @Test fun successClearsTheError() = runTest {
        assertNull(driveConsentCompletion("unregistered", "sign-in failed") { true })
    }

    @Test fun cancellationIsRethrownNotShownAsAnError() = runTest {
        val job = launch {
            driveConsentCompletion("unregistered", "sign-in failed") {
                throw CancellationException("stop")
            }
        }
        job.join()
        assertTrue(job.isCancelled)
    }
}
