@preconcurrency import AVFoundation
import CoreGraphics
import Foundation
import os

nonisolated private let logger = Logger(subsystem: "com.apoorvdarshan.calorietracker", category: "CameraFocus")

/// Where a camera focuses. Like the system camera it starts with continuous autofocus
/// and exposure at the centre, and a tap focuses and exposes once at that point and
/// holds there. When the scene changes (the device's subject-area notification) the
/// camera goes back to continuous autofocus at the centre. If the hold lasts
/// `userFocusTimeout`, autofocus turns continuous again but stays on the tapped point,
/// so a photo still being framed doesn't lose its subject.
nonisolated struct CameraFocusState: Equatable, Sendable {
    enum Mode: Equatable, Sendable {
        /// Continuous autofocus and exposure at the centre.
        case continuous
        /// Focused and exposed once at the tapped point, holding.
        case held(CGPoint)
        /// Continuous autofocus and exposure at the tapped point, after the hold timed out.
        case following(CGPoint)
    }

    static let userFocusTimeout: TimeInterval = 5
    static let center = CGPoint(x: 0.5, y: 0.5)

    private(set) var mode: Mode = .continuous
    /// Grows with every tap and reset, so a timer started by an older tap does nothing.
    private(set) var generation = 0

    var isUserFocused: Bool { mode != .continuous }

    /// A tap at `devicePoint` (0...1, the capture device's landscape coordinates).
    mutating func userFocus(at devicePoint: CGPoint) -> CameraFocusRequest {
        generation += 1
        let point = Self.clamped(devicePoint)
        mode = .held(point)
        return .hold(at: point)
    }

    /// The hold from tap `generation` reached `userFocusTimeout`. Nil when a newer tap,
    /// a scene change or a reset came first.
    mutating func holdTimedOut(generation: Int) -> CameraFocusRequest? {
        guard generation == self.generation, case .held(let point) = mode else { return nil }
        mode = .following(point)
        return .follow(point)
    }

    /// The scene changed: continuous autofocus at the centre again. Nil if already there.
    mutating func sceneChanged() -> CameraFocusRequest? {
        guard mode != .continuous else { return nil }
        mode = .continuous
        return .continuous
    }

    /// Forgets any tap without a device change, e.g. when the camera restarts in continuous mode.
    mutating func reset() {
        mode = .continuous
        generation += 1
    }

    static func clamped(_ point: CGPoint) -> CGPoint {
        func unit(_ value: CGFloat) -> CGFloat { value.isFinite ? min(max(value, 0), 1) : 0.5 }
        return CGPoint(x: unit(point.x), y: unit(point.y))
    }
}

/// One focus and exposure change for a capture device.
nonisolated struct CameraFocusRequest: Equatable, Sendable {
    let point: CGPoint
    let focusMode: AVCaptureDevice.FocusMode
    let exposureMode: AVCaptureDevice.ExposureMode
    /// While a tap is in charge the device reports scene changes, which hand focus back to the centre.
    let monitorsSubjectArea: Bool

    static let continuous = CameraFocusRequest(
        point: CameraFocusState.center,
        focusMode: .continuousAutoFocus,
        exposureMode: .continuousAutoExposure,
        monitorsSubjectArea: false
    )

    static func hold(at point: CGPoint) -> CameraFocusRequest {
        CameraFocusRequest(point: point, focusMode: .autoFocus, exposureMode: .autoExpose, monitorsSubjectArea: true)
    }

    static func follow(_ point: CGPoint) -> CameraFocusRequest {
        CameraFocusRequest(point: point, focusMode: .continuousAutoFocus, exposureMode: .continuousAutoExposure, monitorsSubjectArea: true)
    }

    /// The mode to set, falling back to the other autofocus mode when a device lacks the preferred one.
    func focusMode(supported: (AVCaptureDevice.FocusMode) -> Bool) -> AVCaptureDevice.FocusMode? {
        let fallback: AVCaptureDevice.FocusMode = focusMode == .autoFocus ? .continuousAutoFocus : .autoFocus
        return [focusMode, fallback].first(where: supported)
    }

    func exposureMode(supported: (AVCaptureDevice.ExposureMode) -> Bool) -> AVCaptureDevice.ExposureMode? {
        let fallback: AVCaptureDevice.ExposureMode = exposureMode == .autoExpose ? .continuousAutoExposure : .autoExpose
        return [exposureMode, fallback].first(where: supported)
    }
}

nonisolated enum CameraFocusGeometry {
    /// A point in an AR camera view as a focus point of interest. ARKit draws the
    /// camera image into the view through `displayTransform` (normalised image to
    /// normalised view coordinates), so its inverse maps a tap back into the image,
    /// whose normalised coordinates are the capture device's.
    static func devicePoint(forViewPoint point: CGPoint, viewSize: CGSize, displayTransform: CGAffineTransform) -> CGPoint? {
        guard viewSize.width > 0, viewSize.height > 0 else { return nil }
        let determinant = displayTransform.a * displayTransform.d - displayTransform.b * displayTransform.c
        guard determinant.isFinite, abs(determinant) > 1e-9 else { return nil }
        let normalized = CGPoint(x: point.x / viewSize.width, y: point.y / viewSize.height)
        return CameraFocusState.clamped(normalized.applying(displayTransform.inverted()))
    }
}

/// Decides when the AR food camera, which only has the wide lens, should hand off to a
/// camera that can focus closer: the switch the system camera makes to the ultra-wide
/// lens for close-ups. It fires once the subject has stayed too close for
/// `sustainDuration`: either autofocus has run the lens to its nearest position, or
/// ARKit measures the surface under the focus point closer than the lens can focus.
nonisolated struct CloseUpFocusDetector: Sendable {
    /// `AVCaptureDevice.lensPosition` runs from 0 (nearest focus) to 1.
    static let nearLimitLensPosition: Float = 0.05
    /// Ignores the lens while the camera starts and autofocus finds its first subject.
    static let warmUpDuration: TimeInterval = 0.6
    static let sustainDuration: TimeInterval = 0.5

    /// The AR camera's minimum focus distance.
    let minimumFocusDistanceMM: Double
    private var firstSampleAt: TimeInterval?
    private var tooCloseSince: TimeInterval?

    /// Nil when no other camera focuses meaningfully closer, so handing off can't help.
    init?(arMinimumFocusDistanceMM: Int, alternativeMinimumFocusDistanceMM: Int) {
        guard BackCameraSelection.focusesCloser(alternativeMinimumFocusDistanceMM, than: arMinimumFocusDistanceMM) else { return nil }
        minimumFocusDistanceMM = Double(arMinimumFocusDistanceMM)
    }

    /// Feeds one sample; returns true when the camera should hand off.
    mutating func update(now: TimeInterval, lensPosition: Float, subjectDistanceMM: Double?) -> Bool {
        let startedAt = firstSampleAt ?? now
        firstSampleAt = startedAt
        let lensAtNearLimit = lensPosition.isFinite && lensPosition <= Self.nearLimitLensPosition
        let measuredTooClose = subjectDistanceMM.map { $0.isFinite && $0 > 0 && $0 < minimumFocusDistanceMM } ?? false
        guard now - startedAt >= Self.warmUpDuration, lensAtNearLimit || measuredTooClose else {
            tooCloseSince = nil
            return false
        }
        let since = tooCloseSince ?? now
        tooCloseSince = since
        return now - since >= Self.sustainDuration
    }
}

/// Applies focus, exposure and zoom to a capture device, skipping what it doesn't support.
nonisolated enum CameraDeviceConfigurator {
    /// The starting state for a photo or barcode camera: the given zoom, continuous
    /// autofocus and exposure at the centre, and fast focusing.
    static func configureForCapture(
        _ device: AVCaptureDevice,
        zoomFactor: CGFloat,
        rangeRestriction: AVCaptureDevice.AutoFocusRangeRestriction
    ) {
        withLockedConfiguration(device) {
            applyZoomLocked(zoomFactor, to: device)
            applyFocusSettings(to: device, rangeRestriction: rangeRestriction)
            applyLocked(.continuous, to: device)
            if device.isLowLightBoostSupported {
                device.automaticallyEnablesLowLightBoostWhenAvailable = true
            }
        }
    }

    /// Changes only the zoom, e.g. once a preview knows its size.
    static func setZoomFactor(_ zoomFactor: CGFloat, on device: AVCaptureDevice) {
        withLockedConfiguration(device) {
            applyZoomLocked(zoomFactor, to: device)
        }
    }

    /// Continuous autofocus with no range restriction, leaving zoom and exposure alone;
    /// for the camera ARKit runs.
    static func configureContinuousFocus(_ device: AVCaptureDevice) {
        withLockedConfiguration(device) {
            applyFocusSettings(to: device, rangeRestriction: .none)
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = CameraFocusState.center
            }
            if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
            }
        }
    }

    static func apply(_ request: CameraFocusRequest, to device: AVCaptureDevice) {
        withLockedConfiguration(device) {
            applyLocked(request, to: device)
        }
    }

    /// The caller holds the device's configuration lock.
    private static func applyLocked(_ request: CameraFocusRequest, to device: AVCaptureDevice) {
        // A new point of interest takes effect when the mode is set after it.
        if device.isFocusPointOfInterestSupported {
            device.focusPointOfInterest = request.point
        }
        if let mode = request.focusMode(supported: device.isFocusModeSupported) {
            device.focusMode = mode
        }
        if device.isExposurePointOfInterestSupported {
            device.exposurePointOfInterest = request.point
        }
        if let mode = request.exposureMode(supported: device.isExposureModeSupported) {
            device.exposureMode = mode
        }
        device.isSubjectAreaChangeMonitoringEnabled = request.monitorsSubjectArea
    }

    private static func applyZoomLocked(_ zoomFactor: CGFloat, to device: AVCaptureDevice) {
        let zoom = BackCameraSelection.clampedZoomFactor(
            zoomFactor,
            minimum: device.minAvailableVideoZoomFactor,
            maximum: min(device.maxAvailableVideoZoomFactor, device.activeFormat.videoMaxZoomFactor)
        )
        if abs(device.videoZoomFactor - zoom) > 0.001 {
            device.videoZoomFactor = zoom
        }
    }

    private static func applyFocusSettings(to device: AVCaptureDevice, rangeRestriction: AVCaptureDevice.AutoFocusRangeRestriction) {
        if device.isAutoFocusRangeRestrictionSupported {
            device.autoFocusRangeRestriction = rangeRestriction
        }
        // Smooth autofocus slows refocusing to hide it in recorded video; stills and
        // scanning want the fastest focus.
        if device.isSmoothAutoFocusSupported {
            device.isSmoothAutoFocusEnabled = false
        }
    }

    private static func withLockedConfiguration(_ device: AVCaptureDevice, _ body: () -> Void) {
        do {
            try device.lockForConfiguration()
        } catch {
            // The camera keeps working with its current settings.
            logger.error("[CameraFocus] Couldn't lock the camera: \(error.localizedDescription, privacy: .public)")
            return
        }
        defer { device.unlockForConfiguration() }
        body()
    }
}
