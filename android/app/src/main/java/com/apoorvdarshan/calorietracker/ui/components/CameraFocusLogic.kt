package com.apoorvdarshan.calorietracker.ui.components

import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Focus decisions shared by every in-app camera: food, chat and exercise photos, the
 * barcode scanner and the Live Voice camera. This file is plain Kotlin over Camera2's
 * constants so it runs in unit tests; CameraFocusController applies it to CameraX.
 */

/** What one camera can do for focus, read from its characteristics. */
internal data class FocusCapabilities(
    /** The lens moves to focus at all (continuous or on request). */
    val autofocus: Boolean,
    /** It can autofocus on a chosen point: AUTO mode and at least one AF region. */
    val focusAtPoint: Boolean,
    /** It lists the close-up (MACRO) AF mode, used when a normal scan cannot focus. */
    val macro: Boolean,
    /** It can expose for a chosen point: at least one AE region. */
    val meterAtPoint: Boolean,
) {
    /** Tapping the preview does something: focus there, or at least expose for it. */
    val tapToFocus: Boolean get() = focusAtPoint || meterAtPoint

    companion object {
        val NONE = FocusCapabilities(autofocus = false, focusAtPoint = false, macro = false, meterAtPoint = false)

        private val MOVING_LENS_MODES = setOf(
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_MACRO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
        )

        /**
         * [afModes] is CONTROL_AF_AVAILABLE_MODES, the region counts come from
         * CONTROL_MAX_REGIONS_AF/AE and [minimumFocusDiopters] is
         * LENS_INFO_MINIMUM_FOCUS_DISTANCE, which is 0 for a fixed-focus lens.
         */
        fun from(
            afModes: IntArray?,
            maxAfRegions: Int?,
            maxAeRegions: Int?,
            minimumFocusDiopters: Float?,
        ): FocusCapabilities {
            val modes = afModes ?: IntArray(0)
            val lensMoves = minimumFocusDiopters == null || minimumFocusDiopters > 0f
            // CameraX only starts an AF scan for a point when the camera takes AF regions.
            val focusAtPoint = lensMoves && CameraMetadata.CONTROL_AF_MODE_AUTO in modes && (maxAfRegions ?: 0) > 0
            return FocusCapabilities(
                autofocus = lensMoves && modes.any { it in MOVING_LENS_MODES },
                focusAtPoint = focusAtPoint,
                macro = focusAtPoint && CameraMetadata.CONTROL_AF_MODE_MACRO in modes,
                meterAtPoint = (maxAeRegions ?: 0) > 0,
            )
        }
    }
}

/** Whether a CONTROL_AF_STATE value means the picture is sharp. */
internal fun isFocusedAfState(state: Int): Boolean =
    state == CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
        state == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED

/** Whether a CONTROL_AF_STATE value means focus is still being found. */
internal fun isScanningAfState(state: Int): Boolean =
    state == CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN ||
        state == CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN

internal enum class FocusPhase { FOCUSING, FOCUSED, NOT_FOCUSED, METERED }

internal enum class FocusSource { TAP, CAPTURE }

/** The point being focused (or only metered), in the preview's own pixels. */
internal data class FocusSpot(
    val id: Long,
    val x: Float,
    val y: Float,
    val source: FocusSource,
    val phase: FocusPhase,
    /** The scan moved on to the close-up (macro) range. */
    val macro: Boolean = false,
)

/**
 * The one focus point the preview shows. A newer scan replaces an older one, so a late
 * result for a replaced tap changes nothing.
 */
internal class FocusSpotState {
    var spot: FocusSpot? = null
        private set
    private var lastId = 0L

    /** A scan starts at ([x], [y]). Without autofocus the camera only exposes for it. */
    fun start(x: Float, y: Float, source: FocusSource, autofocus: Boolean): FocusSpot =
        FocusSpot(++lastId, x, y, source, if (autofocus) FocusPhase.FOCUSING else FocusPhase.METERED)
            .also { spot = it }

    fun isCurrent(id: Long): Boolean = spot?.id == id

    /** False when [id] was replaced. */
    fun retryingInMacro(id: Long): Boolean = update(id) { it.copy(macro = true) }

    /** False when [id] was replaced. A metering-only spot stays metered. */
    fun finish(id: Long, focused: Boolean): Boolean = update(id) {
        if (it.phase == FocusPhase.METERED) it
        else it.copy(phase = if (focused) FocusPhase.FOCUSED else FocusPhase.NOT_FOCUSED)
    }

    /** Continuous autofocus again: nothing to show. A null [id] clears whatever is shown. */
    fun clear(id: Long? = null): Boolean {
        val current = spot ?: return false
        if (id != null && current.id != id) return false
        spot = null
        return true
    }

    private inline fun update(id: Long, change: (FocusSpot) -> FocusSpot): Boolean {
        val current = spot?.takeIf { it.id == id } ?: return false
        spot = change(current)
        return true
    }
}

internal enum class PreCapture {
    /** Take the photo now; CameraX's MAXIMIZE_QUALITY capture still waits up to 1 s for 3A. */
    CAPTURE,

    /** Focus is still moving: let it settle, then decide again with [afterSettling]. */
    WAIT_FOR_SCAN,

    /** Continuous autofocus gave up: scan the middle of the frame first. */
    FOCUS_FIRST,
}

/**
 * What the shutter does before the photo. [afState] is the preview's latest
 * CONTROL_AF_STATE, null when the camera does not report it.
 */
internal fun preCapturePlan(capabilities: FocusCapabilities, spot: FocusSpot?, afState: Int?): PreCapture = when {
    !capabilities.focusAtPoint -> PreCapture.CAPTURE
    spot?.phase == FocusPhase.FOCUSED -> PreCapture.CAPTURE
    spot?.phase == FocusPhase.FOCUSING -> PreCapture.WAIT_FOR_SCAN
    else -> afterSettling(afState, stillScanning = PreCapture.WAIT_FOR_SCAN)
}

/**
 * The plan for continuous autofocus in [afState] (the latest state once a wait ends).
 * Sharp, unknown or INACTIVE (the camera reports no continuous progress) shoot; a scan that
 * gave up, or is still running after the wait, scans the middle first.
 */
internal fun afterSettling(afState: Int?, stillScanning: PreCapture = PreCapture.FOCUS_FIRST): PreCapture = when {
    afState == null -> PreCapture.CAPTURE
    isFocusedAfState(afState) -> PreCapture.CAPTURE
    afState == CameraMetadata.CONTROL_AF_STATE_INACTIVE -> PreCapture.CAPTURE
    isScanningAfState(afState) -> stillScanning
    else -> PreCapture.FOCUS_FIRST
}

/** A rectangle in the preview's pixels. */
internal data class PreviewRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Where the camera image sits in a [viewWidth]×[viewHeight] preview. A letterboxed
 * (FIT_CENTER) preview passes the image's shown width/height as [contentAspectRatio];
 * null means the image fills the view (FILL_CENTER).
 */
internal fun previewContentRect(viewWidth: Float, viewHeight: Float, contentAspectRatio: Float?): PreviewRect {
    val full = PreviewRect(0f, 0f, viewWidth, viewHeight)
    if (contentAspectRatio == null || contentAspectRatio <= 0f || viewWidth <= 0f || viewHeight <= 0f) return full
    return if (contentAspectRatio > viewWidth / viewHeight) {
        val height = viewWidth / contentAspectRatio
        val top = (viewHeight - height) / 2f
        PreviewRect(0f, top, viewWidth, top + height)
    } else {
        val width = viewHeight * contentAspectRatio
        val left = (viewWidth - width) / 2f
        PreviewRect(left, 0f, left + width, viewHeight)
    }
}

/** Width/height of a [cropWidth]×[cropHeight] camera frame once turned by [rotationDegrees]. */
internal fun shownAspectRatio(cropWidth: Int, cropHeight: Int, rotationDegrees: Int): Float? {
    if (cropWidth <= 0 || cropHeight <= 0) return null
    return if (rotationDegrees % 180 != 0) cropHeight.toFloat() / cropWidth else cropWidth.toFloat() / cropHeight
}

/** Moves a ring of [radius] centred on ([x], [y]) fully inside [bounds]. */
internal fun ringCenter(x: Float, y: Float, bounds: PreviewRect, radius: Float): Pair<Float, Float> {
    fun axis(value: Float, low: Float, high: Float): Float =
        if (high - low < radius * 2f) (low + high) / 2f else value.coerceIn(low + radius, high - radius)
    return axis(x, bounds.left, bounds.right) to axis(y, bounds.top, bounds.bottom)
}

/** Which sensor ends a tapped focus point when the phone moves. */
internal enum class MotionSource {
    /** A rotation vector: any turn, panning included. */
    ROTATION,

    /** Accelerometer only: tilting and sharp moves, not a level pan. */
    ACCELEROMETER,
    NONE,
}

/**
 * How long a tapped focus point holds before continuous autofocus resumes. The longer hold
 * needs a rotation sensor: without one a level pan to another subject goes unnoticed.
 */
internal fun tapHoldMillis(source: MotionSource): Long = if (source == MotionSource.ROTATION) 8_000L else 5_000L

/** Angle in degrees between two 3-axis vectors; 0 when either has no length. */
internal fun angleBetweenDegrees(a: FloatArray, b: FloatArray): Float {
    val lengths = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
    if (lengths == 0f) return 0f
    val cosine = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / lengths).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cosine).toDouble()).toFloat()
}

/**
 * Unit quaternion (x, y, z, w) from rotation-vector sensor values. The scalar part is
 * values[3] when present (always from API 18), else derived; null for unusable values.
 */
internal fun rotationQuaternion(values: FloatArray): FloatArray? {
    if (values.size < 3) return null
    val x = values[0]
    val y = values[1]
    val z = values[2]
    val w = if (values.size >= 4) values[3] else sqrt(max(0f, 1f - x * x - y * y - z * z))
    val length = sqrt(x * x + y * y + z * z + w * w)
    if (length == 0f || length.isNaN()) return null
    return floatArrayOf(x / length, y / length, z / length, w / length)
}

/** Angle in degrees of the turn between two unit quaternions (x, y, z, w). */
internal fun rotationBetweenDegrees(a: FloatArray, b: FloatArray): Float {
    val dot = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceAtMost(1f)
    return Math.toDegrees(2.0 * acos(dot.toDouble())).toFloat()
}

/**
 * Tells when the phone has been pointed at something else or moved sharply, so a tapped
 * focus point gives way to continuous autofocus. A rotation vector notices any turn; the
 * accelerometer (m/s², device axes) notices tilting and jolts. The first [settleMillis] only
 * learn the resting position, since tapping the screen shakes the phone. Gravity is smoothed
 * over [smoothingMillis] whatever the sensor rate.
 */
internal class ReframeDetector(
    private val turnDegrees: Float = 12f,
    private val jolt: Float = 3.5f,
    private val settleMillis: Long = 500L,
    private val smoothingMillis: Float = 150f,
) {
    private var gravity: FloatArray? = null
    private var resting: FloatArray? = null
    private var restingAttitude: FloatArray? = null
    private var startedAt = 0L
    private var lastAccelerationAt = 0L

    fun reset(nowMillis: Long) {
        gravity = null
        resting = null
        restingAttitude = null
        startedAt = nowMillis
        lastAccelerationAt = nowMillis
    }

    private fun settling(nowMillis: Long) = nowMillis - startedAt < settleMillis

    /** Accelerometer sample: true once the phone has tilted past [turnDegrees] or jolted past [jolt] m/s². */
    fun onAcceleration(x: Float, y: Float, z: Float, nowMillis: Long): Boolean {
        val previous = gravity
        val current = if (previous == null) floatArrayOf(x, y, z) else {
            val keep = exp(-(nowMillis - lastAccelerationAt).coerceAtLeast(0L) / smoothingMillis)
            floatArrayOf(
                previous[0] * keep + x * (1f - keep),
                previous[1] * keep + y * (1f - keep),
                previous[2] * keep + z * (1f - keep),
            )
        }
        gravity = current
        lastAccelerationAt = nowMillis
        if (settling(nowMillis)) {
            resting = current.copyOf()
            return false
        }
        val reference = resting ?: current.copyOf().also { resting = it }
        val dx = x - current[0]
        val dy = y - current[1]
        val dz = z - current[2]
        return angleBetweenDegrees(reference, current) > turnDegrees || sqrt(dx * dx + dy * dy + dz * dz) > jolt
    }

    /** Rotation sample (see [rotationQuaternion]): true once the phone has turned past [turnDegrees]. */
    fun onRotation(quaternion: FloatArray, nowMillis: Long): Boolean {
        if (settling(nowMillis)) {
            restingAttitude = quaternion.copyOf()
            return false
        }
        val reference = restingAttitude ?: quaternion.copyOf().also { restingAttitude = it }
        return rotationBetweenDegrees(reference, quaternion) > turnDegrees
    }
}

internal enum class CaptureLens { BACK, FRONT }

/** One camera CameraX can open, in CameraX's order. */
internal data class CameraOption(val lens: CaptureLens?, val autofocus: Boolean)

/**
 * Index of the camera to open: the first back camera that can autofocus, else the first
 * back camera. With [allowFront] a device without a back camera (some tablets and
 * Chromebooks) uses its front camera the same way. Null when nothing fits.
 */
internal fun chooseCamera(options: List<CameraOption>, allowFront: Boolean): Int? {
    fun pick(lens: CaptureLens): Int? {
        val candidates = options.withIndex().filter { it.value.lens == lens }
        return (candidates.firstOrNull { it.value.autofocus } ?: candidates.firstOrNull())?.index
    }
    return pick(CaptureLens.BACK) ?: if (allowFront) pick(CaptureLens.FRONT) else null
}
