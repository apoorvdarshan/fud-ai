package com.apoorvdarshan.calorietracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureLensTest {
    private val backAf = CameraOption(CaptureLens.BACK, autofocus = true)
    private val backFixed = CameraOption(CaptureLens.BACK, autofocus = false)
    private val frontAf = CameraOption(CaptureLens.FRONT, autofocus = true)
    private val frontFixed = CameraOption(CaptureLens.FRONT, autofocus = false)
    private val external = CameraOption(lens = null, autofocus = true)

    @Test
    fun backCameraIsPreferredWhenPresent() {
        assertEquals(0, chooseCamera(listOf(backAf, frontFixed), allowFront = true))
        assertEquals(1, chooseCamera(listOf(frontFixed, backAf), allowFront = true))
        assertEquals(0, chooseCamera(listOf(backAf), allowFront = true))
    }

    @Test
    fun backCameraThatAutofocusesBeatsAnEarlierFixedFocusOne() {
        assertEquals(1, chooseCamera(listOf(backFixed, backAf, frontAf), allowFront = true))
    }

    @Test
    fun cameraXOrderDecidesBetweenEqualCameras() {
        // The first listed back camera is the main (or logical multi-camera) one.
        assertEquals(0, chooseCamera(listOf(backAf, backAf), allowFront = true))
        assertEquals(0, chooseCamera(listOf(backFixed, backFixed), allowFront = true))
    }

    @Test
    fun frontOnlyDeviceUsesFrontCamera() {
        assertEquals(0, chooseCamera(listOf(frontFixed), allowFront = true))
        assertEquals(1, chooseCamera(listOf(frontFixed, frontAf), allowFront = true))
    }

    @Test
    fun backOnlyScreensIgnoreFrontAndExternalCameras() {
        assertNull(chooseCamera(listOf(frontAf, external), allowFront = false))
        assertEquals(2, chooseCamera(listOf(frontAf, external, backFixed), allowFront = false))
    }

    @Test
    fun noCameraYieldsNoLens() {
        assertNull(chooseCamera(emptyList(), allowFront = true))
        assertNull(chooseCamera(listOf(external), allowFront = true))
    }
}
