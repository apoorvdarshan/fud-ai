package com.apoorvdarshan.calorietracker.ui.components

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CameraFocusLogicTest {
    private val off = CameraMetadata.CONTROL_AF_MODE_OFF
    private val auto = CameraMetadata.CONTROL_AF_MODE_AUTO
    private val macro = CameraMetadata.CONTROL_AF_MODE_MACRO
    private val video = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
    private val picture = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE

    private val fullFocus = FocusCapabilities(autofocus = true, focusAtPoint = true, macro = true, meterAtPoint = true)

    // Capabilities

    @Test
    fun lavaBackCameraFocusesAtPointsWithMacroRetry() {
        // LAVA LZG411 (Unisoc SC9863A) back camera: AF modes 0 1 2 4 3, one AF and one AE
        // region, closest focus 10 diopters (10 cm).
        val caps = FocusCapabilities.from(intArrayOf(off, auto, macro, picture, video), 1, 1, 10f)
        assertEquals(fullFocus, caps)
        assertTrue(caps.tapToFocus)
    }

    @Test
    fun fixedFocusFrontCameraOnlyExposesForTheTap() {
        // LAVA LZG411 front camera: AF off only, no AF region, one AE region, focus distance 0.
        val caps = FocusCapabilities.from(intArrayOf(off), 0, 1, 0f)
        assertEquals(FocusCapabilities(autofocus = false, focusAtPoint = false, macro = false, meterAtPoint = true), caps)
        assertTrue(caps.tapToFocus)
    }

    @Test
    fun autofocusWithoutAfRegionsCannotFocusAPoint() {
        val caps = FocusCapabilities.from(intArrayOf(off, auto, macro, picture), 0, 0, 10f)
        assertTrue(caps.autofocus)
        assertFalse(caps.focusAtPoint)
        assertFalse(caps.macro)
        assertFalse(caps.tapToFocus)
    }

    @Test
    fun continuousOnlyCameraAutofocusesButCannotScanAPoint() {
        val caps = FocusCapabilities.from(intArrayOf(picture, video), 1, 1, 8f)
        assertTrue(caps.autofocus)
        assertFalse(caps.focusAtPoint)
        assertTrue(caps.meterAtPoint)
    }

    @Test
    fun aZeroFocusDistanceMeansAFixedLensWhateverTheModesSay() {
        val caps = FocusCapabilities.from(intArrayOf(auto, picture), 1, 1, 0f)
        assertFalse(caps.autofocus)
        assertFalse(caps.focusAtPoint)
    }

    @Test
    fun unknownFocusDistanceTrustsTheModes() {
        assertTrue(FocusCapabilities.from(intArrayOf(auto), 1, 0, null).focusAtPoint)
    }

    @Test
    fun missingCharacteristicsMeanNoFocusControl() {
        assertEquals(FocusCapabilities.NONE, FocusCapabilities.from(null, null, null, null))
        assertFalse(FocusCapabilities.NONE.tapToFocus)
    }

    @Test
    fun macroNeedsAPointScan() {
        val caps = FocusCapabilities.from(intArrayOf(macro, picture), 1, 1, 12f)
        assertFalse(caps.focusAtPoint)
        assertFalse(caps.macro)
    }

    // AF states

    @Test
    fun afStatesAreClassified() {
        assertTrue(isFocusedAfState(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED))
        assertTrue(isFocusedAfState(CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED))
        assertFalse(isFocusedAfState(CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED))
        assertFalse(isFocusedAfState(CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED))
        assertFalse(isScanningAfState(CameraMetadata.CONTROL_AF_STATE_INACTIVE))
        assertTrue(isScanningAfState(CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN))
        assertTrue(isScanningAfState(CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN))
        assertFalse(isScanningAfState(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED))
        assertFalse(isScanningAfState(CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED))
    }

    // Before the photo

    @Test
    fun sharpPreviewIsCapturedRightAway() {
        assertEquals(PreCapture.CAPTURE, preCapturePlan(fullFocus, null, CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED))
        assertEquals(PreCapture.CAPTURE, preCapturePlan(fullFocus, null, CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED))
    }

    @Test
    fun huntingFocusIsAllowedToSettle() {
        assertEquals(PreCapture.WAIT_FOR_SCAN, preCapturePlan(fullFocus, null, CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN))
        assertEquals(PreCapture.WAIT_FOR_SCAN, preCapturePlan(fullFocus, null, CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN))
    }

    @Test
    fun inactiveContinuousFocusIsLeftToCameraX() {
        // Some cameras never report continuous progress; MAXIMIZE_QUALITY already waits up to 1 s.
        assertEquals(PreCapture.CAPTURE, preCapturePlan(fullFocus, null, CameraMetadata.CONTROL_AF_STATE_INACTIVE))
    }

    @Test
    fun givenUpContinuousFocusScansTheMiddleFirst() {
        assertEquals(PreCapture.FOCUS_FIRST, preCapturePlan(fullFocus, null, CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED))
        assertEquals(PreCapture.FOCUS_FIRST, preCapturePlan(fullFocus, null, CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED))
    }

    @Test
    fun aFocusedTapWinsOverTheLiveState() {
        val tapped = FocusSpot(1, 10f, 10f, FocusSource.TAP, FocusPhase.FOCUSED)
        assertEquals(PreCapture.CAPTURE, preCapturePlan(fullFocus, tapped, CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED))
        val scanning = tapped.copy(phase = FocusPhase.FOCUSING)
        assertEquals(PreCapture.WAIT_FOR_SCAN, preCapturePlan(fullFocus, scanning, CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED))
    }

    @Test
    fun camerasThatCannotScanAPointJustShoot() {
        val fixed = FocusCapabilities.from(intArrayOf(off), 0, 1, 0f)
        assertEquals(PreCapture.CAPTURE, preCapturePlan(fixed, null, CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED))
        assertEquals(PreCapture.CAPTURE, preCapturePlan(fullFocus, null, afState = null))
    }

    @Test
    fun settlingDecidesBetweenShootingAndScanning() {
        assertEquals(PreCapture.CAPTURE, afterSettling(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED))
        assertEquals(PreCapture.FOCUS_FIRST, afterSettling(CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED))
        // Still hunting when the wait ran out.
        assertEquals(PreCapture.FOCUS_FIRST, afterSettling(CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN))
        assertEquals(PreCapture.CAPTURE, afterSettling(CameraMetadata.CONTROL_AF_STATE_INACTIVE))
        assertEquals(PreCapture.CAPTURE, afterSettling(null))
    }

    // Focus spot

    @Test
    fun tapGoesFromFocusingToFocused() {
        val state = FocusSpotState()
        val spot = state.start(40f, 60f, FocusSource.TAP, autofocus = true)
        assertEquals(FocusPhase.FOCUSING, state.spot?.phase)
        assertTrue(state.finish(spot.id, focused = true))
        assertEquals(FocusPhase.FOCUSED, state.spot?.phase)
        assertEquals(40f, state.spot!!.x, 0f)
        assertEquals(60f, state.spot!!.y, 0f)
    }

    @Test
    fun failedScanThroughMacroIsMarkedNotFocused() {
        val state = FocusSpotState()
        val spot = state.start(1f, 1f, FocusSource.CAPTURE, autofocus = true)
        assertTrue(state.retryingInMacro(spot.id))
        assertTrue(state.spot!!.macro)
        assertTrue(state.finish(spot.id, focused = false))
        assertEquals(FocusPhase.NOT_FOCUSED, state.spot?.phase)
        assertEquals(FocusSource.CAPTURE, state.spot?.source)
    }

    @Test
    fun aNewerTapIgnoresTheOlderResult() {
        val state = FocusSpotState()
        val first = state.start(1f, 1f, FocusSource.TAP, autofocus = true)
        val second = state.start(2f, 2f, FocusSource.TAP, autofocus = true)
        assertNotEquals(first.id, second.id)
        assertFalse(state.finish(first.id, focused = true))
        assertFalse(state.retryingInMacro(first.id))
        assertFalse(state.clear(first.id))
        assertFalse(state.isCurrent(first.id))
        assertEquals(second, state.spot)
    }

    @Test
    fun fixedFocusTapOnlyMeters() {
        val state = FocusSpotState()
        val spot = state.start(5f, 5f, FocusSource.TAP, autofocus = false)
        assertEquals(FocusPhase.METERED, spot.phase)
        assertTrue(state.finish(spot.id, focused = false))
        assertEquals(FocusPhase.METERED, state.spot?.phase)
    }

    @Test
    fun clearingReturnsToContinuousFocus() {
        val state = FocusSpotState()
        val spot = state.start(5f, 5f, FocusSource.TAP, autofocus = true)
        assertTrue(state.clear(spot.id))
        assertNull(state.spot)
        assertFalse(state.clear())
        state.start(6f, 6f, FocusSource.TAP, autofocus = true)
        assertTrue(state.clear())
        assertNull(state.spot)
    }

    // Points on the preview

    @Test
    fun fillCenterUsesTheWholeView() {
        assertEquals(PreviewRect(0f, 0f, 1080f, 2340f), previewContentRect(1080f, 2340f, null))
    }

    @Test
    fun portraitPhotoIsLetterboxedTopAndBottomOnATallScreen() {
        // A 4:3 sensor shown upright is 3:4; a 1080×2340 screen fits it 1080×1440.
        val rect = previewContentRect(1080f, 2340f, 3f / 4f)
        assertEquals(PreviewRect(0f, 450f, 1080f, 1890f), rect)
        assertTrue(rect.contains(540f, 1170f))
        assertFalse(rect.contains(540f, 200f))
        assertFalse(rect.contains(540f, 2000f))
        assertEquals(540f, rect.centerX, 0f)
        assertEquals(1170f, rect.centerY, 0f)
    }

    @Test
    fun widerViewIsPillarboxedLeftAndRight() {
        val rect = previewContentRect(2000f, 1000f, 4f / 3f)
        assertEquals(333.33f, rect.left, 0.01f)
        assertEquals(1666.67f, rect.right, 0.01f)
        assertEquals(0f, rect.top, 0f)
        assertEquals(1000f, rect.bottom, 0f)
        assertFalse(rect.contains(100f, 500f))
        assertTrue(rect.contains(1000f, 500f))
    }

    @Test
    fun unusableSizesFallBackToTheView() {
        assertEquals(PreviewRect(0f, 0f, 100f, 200f), previewContentRect(100f, 200f, 0f))
        assertEquals(PreviewRect(0f, 0f, 0f, 0f), previewContentRect(0f, 0f, 0.75f))
    }

    @Test
    fun sensorFrameTurnsUprightForPortrait() {
        assertEquals(0.75f, shownAspectRatio(4160, 3120, 90)!!, 0.0001f)
        assertEquals(0.75f, shownAspectRatio(4160, 3120, 270)!!, 0.0001f)
        assertEquals(4f / 3f, shownAspectRatio(4160, 3120, 0)!!, 0.0001f)
        assertEquals(4f / 3f, shownAspectRatio(4160, 3120, 180)!!, 0.0001f)
        assertNull(shownAspectRatio(0, 3120, 90))
    }

    @Test
    fun ringStaysInsideTheImage() {
        val bounds = PreviewRect(0f, 450f, 1080f, 1890f)
        assertEquals(500f to 900f, ringCenter(500f, 900f, bounds, 100f))
        assertEquals(100f to 550f, ringCenter(5f, 460f, bounds, 100f))
        assertEquals(980f to 1790f, ringCenter(1079f, 1889f, bounds, 100f))
        // An image smaller than the ring centres it.
        assertEquals(50f to 25f, ringCenter(0f, 0f, PreviewRect(0f, 0f, 100f, 50f), 60f))
    }

    // Holding a tapped point

    @Test
    fun tapHoldsLongerOnlyWhenEveryTurnIsNoticed() {
        assertEquals(8_000L, tapHoldMillis(MotionSource.ROTATION))
        // An accelerometer misses a level pan to another subject, so the point holds no longer
        // than with no sensor at all.
        assertEquals(5_000L, tapHoldMillis(MotionSource.ACCELEROMETER))
        assertEquals(5_000L, tapHoldMillis(MotionSource.NONE))
    }

    @Test
    fun rotationVectorBecomesAUnitQuaternion() {
        assertQuaternion(floatArrayOf(0f, 0f, 0f, 1f), rotationQuaternion(floatArrayOf(0f, 0f, 0f, 1f))!!)
        // Without values[3] the scalar part is derived.
        val half = sin(Math.toRadians(30.0)).toFloat()
        assertQuaternion(floatArrayOf(0f, half, 0f, cos(Math.toRadians(30.0)).toFloat()), rotationQuaternion(floatArrayOf(0f, half, 0f))!!)
        // TYPE_ROTATION_VECTOR adds a heading accuracy after the scalar part.
        assertQuaternion(floatArrayOf(0f, 0f, 0.6f, 0.8f), rotationQuaternion(floatArrayOf(0f, 0f, 1.2f, 1.6f, 0.1f))!!)
        assertNull(rotationQuaternion(floatArrayOf(0f, 0f)))
        assertNull(rotationQuaternion(floatArrayOf(0f, 0f, 0f, 0f)))
    }

    @Test
    fun rotationAngleBetweenQuaternions() {
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        assertEquals(0f, rotationBetweenDegrees(identity, identity), 0.01f)
        assertEquals(90f, rotationBetweenDegrees(identity, yaw(90.0)), 0.05f)
        assertEquals(30f, rotationBetweenDegrees(yaw(10.0), yaw(40.0)), 0.05f)
        // q and -q are the same attitude.
        val q = yaw(25.0)
        // Float rounding of a unit quaternion leaves a few hundredths of a degree.
        assertEquals(0f, rotationBetweenDegrees(q, floatArrayOf(-q[0], -q[1], -q[2], -q[3])), 0.1f)
    }

    @Test
    fun panningToAnotherSubjectEndsTheLock() {
        // A level pan keeps gravity where it was: only the rotation vector sees it.
        val detector = ReframeDetector()
        detector.reset(0L)
        for (t in 0L..600L step 60L) assertFalse(detector.onRotation(yaw(0.0), t))
        assertFalse(detector.onAcceleration(0f, 9.8f, 0f, 660L))
        var moved = false
        var t = 720L
        for (step in 1..20) {
            moved = moved || detector.onRotation(yaw(1.0 * step), t)
            t += 60L
        }
        assertTrue(moved)
    }

    @Test
    fun smallRotationsKeepTheLock() {
        val detector = ReframeDetector()
        detector.reset(0L)
        var moved = false
        for (t in 0L..3_000L step 60L) {
            // Hand sway of a few degrees around where the tap was made.
            moved = moved || detector.onRotation(yaw(if ((t / 60L) % 2L == 0L) 3.0 else -3.0), t)
        }
        assertFalse(moved)
    }

    @Test
    fun rotationWhileSettlingIsLearnedNotReported() {
        val detector = ReframeDetector()
        detector.reset(0L)
        assertFalse(detector.onRotation(yaw(0.0), 0L))
        assertFalse(detector.onRotation(yaw(40.0), 300L))
        var moved = false
        for (t in 360L..2_000L step 60L) moved = moved || detector.onRotation(yaw(40.0), t)
        assertFalse(moved)
    }

    @Test
    fun angleBetweenVectors() {
        assertEquals(0f, angleBetweenDegrees(floatArrayOf(0f, 0f, 9.8f), floatArrayOf(0f, 0f, 4f)), 0.01f)
        assertEquals(90f, angleBetweenDegrees(floatArrayOf(0f, 9.8f, 0f), floatArrayOf(9.8f, 0f, 0f)), 0.01f)
        assertEquals(180f, angleBetweenDegrees(floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f)), 0.01f)
        assertEquals(0f, angleBetweenDegrees(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f)), 0f)
    }

    @Test
    fun steadyPhoneKeepsTheLock() {
        val detector = ReframeDetector()
        detector.reset(0L)
        var moved = false
        for (t in 0L..3_000L step 60L) {
            // Hand tremor: a few hundredths of g around upright portrait.
            val wobble = if ((t / 60L) % 2L == 0L) 0.15f else -0.15f
            moved = moved || detector.onAcceleration(wobble, 9.7f, 1.2f, t)
        }
        assertFalse(moved)
    }

    @Test
    fun tapJoltWhileSettlingIsIgnored() {
        val detector = ReframeDetector()
        detector.reset(0L)
        assertFalse(detector.onAcceleration(6f, 14f, -4f, 20L))
        assertFalse(detector.onAcceleration(0f, 9.7f, 1.2f, 80L))
        var moved = false
        for (t in 140L..2_000L step 60L) moved = moved || detector.onAcceleration(0f, 9.7f, 1.2f, t)
        assertFalse(moved)
    }

    @Test
    fun turningThePhoneEndsTheLock() {
        val detector = ReframeDetector()
        detector.reset(0L)
        for (t in 0L..600L step 60L) assertFalse(detector.onAcceleration(0f, 9.8f, 0f, t))
        // Tilt the phone 30° towards the table over the next second.
        var moved = false
        var t = 660L
        for (step in 1..20) {
            val angle = Math.toRadians(30.0 * step / 20).toFloat()
            moved = moved || detector.onAcceleration(0f, 9.8f * cos(angle), 9.8f * sin(angle), t)
            t += 60L
        }
        assertTrue(moved)
    }

    @Test
    fun aSharpMoveEndsTheLock() {
        val detector = ReframeDetector()
        detector.reset(0L)
        for (t in 0L..600L step 60L) assertFalse(detector.onAcceleration(0f, 9.8f, 0f, t))
        assertTrue(detector.onAcceleration(0f, 9.8f, 6f, 660L))
    }

    private fun yaw(degrees: Double): FloatArray {
        val half = Math.toRadians(degrees) / 2.0
        // Turning around the device's long (y) axis, which points up when held in portrait.
        return floatArrayOf(0f, sin(half).toFloat(), 0f, cos(half).toFloat())
    }

    private fun assertQuaternion(expected: FloatArray, actual: FloatArray) {
        assertEquals(4, actual.size)
        for (i in 0 until 4) assertEquals("component $i", expected[i], actual[i], 0.0001f)
    }

    @Test
    fun resetStartsANewSettlingPeriod() {
        val detector = ReframeDetector()
        detector.reset(0L)
        for (t in 0L..600L step 60L) detector.onAcceleration(0f, 9.8f, 0f, t)
        detector.reset(1_000L)
        // Lying flat now: learned again during settling, not reported as a turn.
        for (t in 1_000L..1_500L step 60L) assertFalse(detector.onAcceleration(0f, 0f, 9.8f, t))
        var moved = false
        for (t in 1_560L..3_000L step 60L) moved = moved || detector.onAcceleration(0f, 0f, 9.8f, t)
        assertFalse(moved)
    }
}
