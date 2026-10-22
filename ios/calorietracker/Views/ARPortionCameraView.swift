import ARKit
import AVFoundation
import CoreImage
import SwiftUI
import UIKit

/// The food camera, running ARKit in the background so a photo can carry the
/// real-world scale of the table under the food (#601). It looks and works like
/// the standard camera; nothing about AR is shown. A photo is measured only when
/// tracking is solid and a table plane is under the centre of the frame;
/// otherwise it is a normal photo.
struct ARPortionCameraView: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    let title: String?
    let onCancel: (() -> Void)?
    /// ARKit couldn't run (camera access, hardware); the caller shows the standard camera.
    let onSessionFailed: () -> Void
    @Environment(\.dismiss) private var dismiss

    static var isSupported: Bool { ARWorldTrackingConfiguration.isSupported }

    func makeUIViewController(context: Context) -> ARPortionCameraController {
        let controller = ARPortionCameraController(title: title)
        controller.onCapture = { image, scale in
            if let scale {
                PortionScaleRegistry.attach(scale, to: image)
            }
            self.image = image
            dismiss()
        }
        controller.onCancel = {
            onCancel?()
            dismiss()
        }
        controller.onSessionFailed = onSessionFailed
        return controller
    }

    func updateUIViewController(_ uiViewController: ARPortionCameraController, context: Context) {}
}

/// ARKit streams only the wide lens and picks focus on its own. This camera sets
/// that lens to continuous autofocus and adds tap to focus through ARKit's capture
/// device. The wide lens still can't focus as close as the ultra-wide macro lens the
/// system camera switches to, so when the subject comes closer than it can focus, on
/// an iPhone with a closer-focusing lens, the camera hands off to the standard camera
/// (`FoodCameraController`) for the rest of the shot. That photo is not measured.
final class ARPortionCameraController: FoodCameraController, ARSessionDelegate {
    var onSessionFailed: (() -> Void)?

    private let sceneView = ARSCNView()
    private let ciContext = CIContext()
    private var sessionFailed = false
    /// False once the camera has handed off to the standard camera for a close-up.
    private var isARActive = true
    /// The capture device ARKit runs, configured for focus.
    private var arDevice: AVCaptureDevice?
    private var didConfigureARFocus = false
    private var closeUpDetector: CloseUpFocusDetector?
    private var lastCloseUpCheck: TimeInterval = 0
    /// The last AR frame, covering the switch to the standard camera.
    private var handOffSnapshot: UIView?
    /// Standard-camera starts tried for the current hand-off.
    private var handOffStartAttempts = 0
    /// The standard camera couldn't start for a close-up, so ARKit stays on for this shot.
    private var handOffUnavailable = false

    /// How often the close-up check samples the lens and the scene.
    private static let closeUpCheckInterval: TimeInterval = 0.15

    override func viewDidLoad() {
        super.viewDidLoad()
        sceneView.session.delegate = self
        sceneView.automaticallyUpdatesLighting = false
        sceneView.translatesAutoresizingMaskIntoConstraints = false
        view.insertSubview(sceneView, aboveSubview: capturePreviewView)

        // Same layout as the standard camera: the full 4:3 frame, letterboxed
        // above the bottom bar, so the preview matches the saved photo.
        let screenSize = UIScreen.main.bounds.size
        let previewHeight = screenSize.width * 4.0 / 3.0
        let availablePreviewHeight = max(0, screenSize.height - Self.bottomBarHeight)
        let previewOffset = max(0, (availablePreviewHeight - previewHeight) / 2)
        NSLayoutConstraint.activate([
            sceneView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            sceneView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            sceneView.topAnchor.constraint(equalTo: view.topAnchor, constant: previewOffset),
            sceneView.heightAnchor.constraint(equalToConstant: previewHeight),
        ])
    }

    // MARK: - Camera lifecycle

    override func startCamera() {
        guard isARActive else {
            super.startCamera()
            return
        }
        didConfigureARFocus = false
        closeUpDetector = nil
        lastCloseUpCheck = 0
        let configuration = ARWorldTrackingConfiguration()
        configuration.planeDetection = [.horizontal]
        // A full-resolution still for the AI instead of a video frame.
        if let format = ARWorldTrackingConfiguration.recommendedVideoFormatForHighResolutionFrameCapturing {
            configuration.videoFormat = format
        }
        sceneView.session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
    }

    override func stopCamera() {
        if isARActive {
            sceneView.session.pause()
        }
        super.stopCamera()
    }

    override func cameraDidStart() {
        super.cameraDidStart()
        guard let snapshot = handOffSnapshot else { return }
        handOffSnapshot = nil
        // Gives the standard camera a moment to show its first frames.
        UIView.animate(withDuration: 0.25, delay: 0.15, options: []) {
            snapshot.alpha = 0
        } completion: { _ in
            snapshot.removeFromSuperview()
        }
    }

    /// ARKit may not have released the camera yet, so the standard camera gets a second
    /// try; if that fails too, ARKit takes over again rather than leaving an error.
    override func handleCameraFailure() -> Bool {
        guard handOffSnapshot != nil, !hasLeft else { return super.handleCameraFailure() }
        if handOffStartAttempts < 2 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
                self?.startStandardCamera()
            }
        } else {
            returnToARCamera()
        }
        return true
    }

    private func returnToARCamera() {
        handOffSnapshot?.removeFromSuperview()
        handOffSnapshot = nil
        handOffUnavailable = true
        isARActive = true
        sceneView.isHidden = false
        startCamera()
    }

    // MARK: - ARSessionDelegate

    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        guard isARActive, !hasLeft else { return }
        if !didConfigureARFocus {
            configureARFocus()
        }
        checkForCloseUp(in: frame)
    }

    func session(_ session: ARSession, didFailWithError error: Error) {
        guard isARActive, !sessionFailed, !hasLeft else { return }
        sessionFailed = true
        onSessionFailed?()
    }

    // MARK: - Focus

    private func configureARFocus() {
        didConfigureARFocus = true
        guard let device = ARWorldTrackingConfiguration.configurableCaptureDeviceForPrimaryCamera else { return }
        arDevice = device
        CameraDeviceConfigurator.configureContinuousFocus(device)
        trackFocus(of: device)
        if !handOffUnavailable, let alternative = BackCameraSelection.preferredBackVideoCaptureDevice() {
            closeUpDetector = CloseUpFocusDetector(
                arMinimumFocusDistanceMM: device.minimumFocusDistance,
                alternativeMinimumFocusDistanceMM: alternative.minimumFocusDistance
            )
        }
    }

    override func activePreviewRect() -> CGRect? {
        guard isARActive else { return super.activePreviewRect() }
        return arDevice == nil ? nil : sceneView.frame
    }

    override func focusDevicePoint(forViewPoint point: CGPoint) -> CGPoint? {
        guard isARActive else { return super.focusDevicePoint(forViewPoint: point) }
        guard let frame = sceneView.session.currentFrame else { return nil }
        let size = sceneView.bounds.size
        return CameraFocusGeometry.devicePoint(
            forViewPoint: sceneView.convert(point, from: view),
            viewSize: size,
            displayTransform: frame.displayTransform(for: sceneOrientation, viewportSize: size)
        )
    }

    override func applyFocus(_ request: CameraFocusRequest) {
        guard isARActive else {
            super.applyFocus(request)
            return
        }
        guard let arDevice else { return }
        CameraDeviceConfigurator.apply(request, to: arDevice)
    }

    private func checkForCloseUp(in frame: ARFrame) {
        guard !isCapturing, var detector = closeUpDetector, let device = arDevice else { return }
        let now = frame.timestamp
        guard now - lastCloseUpCheck >= Self.closeUpCheckInterval else { return }
        lastCloseUpCheck = now
        let shouldHandOff = detector.update(
            now: now,
            lensPosition: device.lensPosition,
            subjectDistanceMM: subjectDistanceMM(in: frame, at: device.focusPointOfInterest)
        )
        closeUpDetector = detector
        if shouldHandOff {
            handOffToCloseUpCamera()
        }
    }

    /// How far the surface under the focus point is, when ARKit can tell.
    private func subjectDistanceMM(in frame: ARFrame, at imagePoint: CGPoint) -> Double? {
        guard case .normal = frame.camera.trackingState else { return nil }
        let camera = frame.camera.transform.columns.3
        for target in [ARRaycastQuery.Target.existingPlaneGeometry, .estimatedPlane] {
            let query = frame.raycastQuery(from: imagePoint, allowing: target, alignment: .any)
            if let hit = sceneView.session.raycast(query).first {
                let surface = hit.worldTransform.columns.3
                let meters = simd_distance(SIMD3(camera.x, camera.y, camera.z), SIMD3(surface.x, surface.y, surface.z))
                return Double(meters) * 1000
            }
        }
        return nil
    }

    /// Switches to the standard camera, which can move to the macro lens, for the
    /// rest of this shot.
    private func handOffToCloseUpCamera() {
        guard isARActive, !hasLeft, !isCapturing, !sessionFailed else { return }
        isARActive = false
        closeUpDetector = nil
        endFocusTracking()
        let snapshot = UIImageView(image: sceneView.snapshot())
        snapshot.frame = sceneView.frame
        snapshot.contentMode = .scaleAspectFill
        snapshot.clipsToBounds = true
        snapshot.isUserInteractionEnabled = false
        view.insertSubview(snapshot, aboveSubview: sceneView)
        handOffSnapshot = snapshot
        handOffStartAttempts = 0
        sceneView.session.pause()
        sceneView.isHidden = true
        arDevice = nil
        // Lets ARKit release the camera first.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            self?.startStandardCamera()
        }
    }

    private func startStandardCamera() {
        guard !hasLeft, !isARActive else { return }
        handOffStartAttempts += 1
        super.startCamera()
    }

    /// The AR camera never used the flash; the close-up camera that replaces it keeps it off too.
    override var photoFlashMode: AVCaptureDevice.FlashMode { .off }

    // MARK: - Measuring

    private var sceneOrientation: UIInterfaceOrientation {
        view.window?.windowScene?.effectiveGeometry.interfaceOrientation ?? .portrait
    }

    /// No measurement unless tracking is normal and a detected table plane, not
    /// the floor, is under the centre of the frame.
    private func measure(_ frame: ARFrame, orientation: UIInterfaceOrientation) -> PortionScale? {
        guard !sessionFailed, case .normal = frame.camera.trackingState else { return nil }
        let center = CGPoint(x: 0.5, y: 0.5)
        // The food can hide the table under the centre, so extend the detected
        // plane beneath it when the ray misses the plane's mapped area.
        for target in [ARRaycastQuery.Target.existingPlaneGeometry, .existingPlaneInfinite] {
            let results = sceneView.session.raycast(
                frame.raycastQuery(from: center, allowing: target, alignment: .horizontal)
            )
            if let hit = results.first(where: { !Self.isFloor($0) }) {
                let transform = hit.worldTransform
                return PortionScale.measure(
                    cameraTransform: frame.camera.transform,
                    planePoint: SIMD3(transform.columns.3.x, transform.columns.3.y, transform.columns.3.z),
                    planeNormal: SIMD3(transform.columns.1.x, transform.columns.1.y, transform.columns.1.z),
                    intrinsics: frame.camera.intrinsics,
                    imageResolution: frame.camera.imageResolution,
                    portrait: orientation.isPortrait || orientation == .unknown
                )
            }
        }
        return nil
    }

    private static func isFloor(_ result: ARRaycastResult) -> Bool {
        guard let plane = result.anchor as? ARPlaneAnchor else { return false }
        if case .floor = plane.classification { return true }
        return false
    }

    // MARK: - Capture

    override func takePhoto() {
        guard isARActive else {
            super.takePhoto()
            return
        }
        guard let frame = sceneView.session.currentFrame else { return }
        isCapturing = true
        let orientation = sceneOrientation
        let fallbackScale = measure(frame, orientation: orientation)
        sceneView.session.captureHighResolutionFrame { [weak self] highResolutionFrame, _ in
            let finish: () -> Void = {
                self?.finishCapture(
                    highResolutionFrame,
                    fallbackFrame: frame,
                    fallbackScale: fallbackScale,
                    orientation: orientation
                )
            }
            if Thread.isMainThread {
                finish()
            } else {
                DispatchQueue.main.async(execute: finish)
            }
        }
    }

    private func finishCapture(
        _ highResolutionFrame: ARFrame?,
        fallbackFrame: ARFrame,
        fallbackScale: PortionScale?,
        orientation: UIInterfaceOrientation
    ) {
        guard !hasLeft else { return }
        let photoFrame = highResolutionFrame ?? fallbackFrame
        // The high-resolution still has its own intrinsics for its size.
        let scale = (highResolutionFrame.flatMap { measure($0, orientation: orientation) } ?? fallbackScale)
            .flatMap { $0.readiness == .ready ? $0 : nil }
        guard let image = uprightImage(from: photoFrame.capturedImage, orientation: orientation) else {
            isCapturing = false
            return
        }
        deliver(image, scale: scale)
    }

    private func uprightImage(from pixelBuffer: CVPixelBuffer, orientation: UIInterfaceOrientation) -> UIImage? {
        // The sensor buffer is landscape-right; draw it upright so every provider
        // sees the photo the right way up, with or without EXIF support.
        let rotation: CGImagePropertyOrientation
        switch orientation {
        case .portraitUpsideDown: rotation = .left
        case .landscapeRight: rotation = .up
        case .landscapeLeft: rotation = .down
        default: rotation = .right
        }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer).oriented(rotation)
        guard let cgImage = ciContext.createCGImage(ciImage, from: ciImage.extent) else { return nil }
        return UIImage(cgImage: cgImage, scale: 1, orientation: .up)
    }
}
