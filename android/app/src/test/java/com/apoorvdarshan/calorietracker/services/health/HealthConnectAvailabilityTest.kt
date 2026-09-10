package com.apoorvdarshan.calorietracker.services.health

import androidx.health.connect.client.HealthConnectClient
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthConnectAvailabilityTest {
    @Test
    fun secondaryProfileExplainsWhyApprovedPermissionsAreNotUsable() {
        assertEquals(
            HealthConnectAvailability.PROFILE_UNSUPPORTED,
            resolveHealthConnectAvailability(
                isProfile = true,
                sdkAvailable = false,
                providerUpdateRequired = false
            )
        )
    }

    @Test
    fun availableSdkWinsForMainProfile() {
        assertEquals(
            HealthConnectAvailability.AVAILABLE,
            resolveHealthConnectAvailability(
                isProfile = false,
                sdkAvailable = true,
                providerUpdateRequired = false
            )
        )
    }

    @Test
    fun providerUpdateAndMissingSystemServiceRemainDistinct() {
        assertEquals(
            HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED,
            resolveHealthConnectAvailability(
                isProfile = false,
                sdkAvailable = false,
                providerUpdateRequired = true
            )
        )
        assertEquals(
            HealthConnectAvailability.UNAVAILABLE,
            resolveHealthConnectAvailability(
                isProfile = false,
                sdkAvailable = false,
                providerUpdateRequired = false
            )
        )
    }

    @Test
    fun secondaryProfileOverridesAvailableSdkStatus() {
        assertEquals(
            HealthConnectAvailability.PROFILE_UNSUPPORTED,
            resolveHealthConnectAvailability(
                isProfile = true,
                sdkAvailable = true,
                providerUpdateRequired = false
            )
        )
    }

    @Test
    fun sdkStatusCodesMapToAvailability() {
        assertEquals(
            HealthConnectAvailability.AVAILABLE,
            resolveHealthConnectAvailabilityFromSdkStatus(
                isProfile = false,
                sdkStatus = HealthConnectClient.SDK_AVAILABLE
            )
        )
        assertEquals(
            HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED,
            resolveHealthConnectAvailabilityFromSdkStatus(
                isProfile = false,
                sdkStatus = HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
            )
        )
        assertEquals(
            HealthConnectAvailability.UNAVAILABLE,
            resolveHealthConnectAvailabilityFromSdkStatus(
                isProfile = false,
                sdkStatus = HealthConnectClient.SDK_UNAVAILABLE
            )
        )
    }

    @Test
    fun healthConnectProviderPackageMatchesManifestQuery() {
        assertEquals("com.google.android.apps.healthdata", HealthConnectManager.HEALTH_CONNECT_PROVIDER_PACKAGE)
    }
}
