@preconcurrency import AVFoundation
import CoreMedia
import SwiftUI
import UIKit
import os

nonisolated private let logger = Logger(subsystem: "com.apoorvdarshan.calorietracker", category: "FoodCamera")

// MARK: - SwiftUI

/// The in-app photo camera for chat, custom exercise and ingredient photos, and the
/// food camera when ARKit can't run. It focuses like the system camera; see
/// `FoodCameraController`.
struct CameraView: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    let title: String?
    let onCancel: (() -> Void)?
    @Environment(\.dismiss) private var dismiss

    init(image: Binding<UIImage?>, title: String? = nil, onCancel: (() -> Void)? = nil) {
        _image = image
        self.title = title
        self.onCancel = onCancel
    }

    func makeUIViewController(context: Context) -> FoodCameraController {
        let controller = FoodCameraController(title: title)
        controller.onCapture = { image, _ in
            self.image = image
            dismiss()
        }
        controller.onCancel = {
            onCancel?()
            dismiss()
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: FoodCameraController, context: Context) {}
}

// MARK: - Camera

/// A full-screen photo camera that focuses like the system camera:
/// - the back camera that switches lenses by itself, at the system camera's 1×, so
///   iPhones with a macro-capable ultra-wide lens move to it for close-ups;
/// - continuous autofocus and exposure;
/// - tap to focus and expose, with the focus square, until the scene changes or
///   `CameraFocusState.userFocusTimeout` passes.
///
/// The preview shows the whole 4:3 photo above the shutter bar, so it matches the
/// photo. `ARPortionCameraController` builds on this class.
class FoodCameraController: UIViewController {
    var onCapture: ((UIImage, PortionScale?) -> Void)?
    var onCancel: (() -> Void)?

    static let bottomBarHeight: CGFloat = 140

    let titleText: String?
    /// The AVFoundation preview. The AR camera puts its own view on top of it.
    let capturePreviewView = CameraPreviewView()
    let focusIndicator = CameraFocusIndicatorView()
    let bottomBar = UIView()
    /// Set when the user leaves this camera, so a photo that arrives afterwards is dropped.
    private(set) var hasLeft = false
    /// A photo is being taken; the shutter and taps wait until it's done.
    var isCapturing = false

    private let capture = FoodPhotoCapture()
    private let shutterButton = UIButton(type: .system)
    private var messageView: UIView?
    private var focusState = CameraFocusState()
    private var subjectAreaObserver: NSObjectProtocol?
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var rotationObservation: NSKeyValueObservation?
    /// Grows with every start and stop, so a late start result is ignored.
    private var startCount = 0
    private var isCaptureSessionRunning = false
    /// Camera access was off; turning it on in Settings restarts the camera on return.
    private var isWaitingForCameraAccess = false
    private var appActivationObserver: NSObjectProtocol?

    init(title: String?) {
        titleText = title
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        capturePreviewView.previewLayer.session = capture.session
        capturePreviewView.previewLayer.videoGravity = .resizeAspect
        capturePreviewView.isAccessibilityElement = true
        capturePreviewView.accessibilityLabel = String(localized: "Camera preview")
        capturePreviewView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(capturePreviewView)
        view.addSubview(focusIndicator)
        buildBottomBar()

        // The 4:3 photo letterboxes inside the area above the bar, centred, as the
        // previous camera did.
        NSLayoutConstraint.activate([
            capturePreviewView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            capturePreviewView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            capturePreviewView.topAnchor.constraint(equalTo: view.topAnchor),
            capturePreviewView.bottomAnchor.constraint(equalTo: bottomBar.topAnchor),
        ])

        let tap = UITapGestureRecognizer(target: self, action: #selector(previewTapped(_:)))
        tap.cancelsTouchesInView = false
        view.addGestureRecognizer(tap)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        hasLeft = false
        isCapturing = false
        observeAppActivation()
        startCamera()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        hasLeft = true
        if let appActivationObserver {
            NotificationCenter.default.removeObserver(appActivationObserver)
            self.appActivationObserver = nil
        }
        stopCamera()
    }

    /// Camera access can be turned on in Settings while this camera is open.
    private func observeAppActivation() {
        guard appActivationObserver == nil else { return }
        appActivationObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.restartIfCameraAccessWasGranted()
            }
        }
    }

    private func restartIfCameraAccessWasGranted() {
        guard isWaitingForCameraAccess, !hasLeft,
              AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { return }
        isWaitingForCameraAccess = false
        startCamera()
    }

    // MARK: - Camera lifecycle

    /// Starts the AVFoundation camera. The AR camera runs ARKit instead until it hands off.
    func startCamera() {
        startCount += 1
        let count = startCount
        capture.start { [weak self] result in
            DispatchQueue.main.async {
                guard let self, count == self.startCount, !self.hasLeft else { return }
                self.captureStartDidFinish(result)
            }
        }
    }

    func stopCamera() {
        startCount += 1
        isCaptureSessionRunning = false
        endFocusTracking()
        rotationObservation = nil
        rotationCoordinator = nil
        capture.stop()
    }

    /// Called when the AVFoundation camera is running.
    func cameraDidStart() {}

    /// Called when the AVFoundation camera can't run. Returning true handles it here;
    /// otherwise a message replaces the preview.
    func handleCameraFailure() -> Bool { false }

    private func captureStartDidFinish(_ result: FoodPhotoCapture.StartResult) {
        isWaitingForCameraAccess = false
        switch result {
        case .started(let device):
            isCaptureSessionRunning = true
            removeMessage()
            configureRotation(for: device)
            trackFocus(of: device)
            cameraDidStart()
        case .denied:
            guard !handleCameraFailure() else { return }
            isWaitingForCameraAccess = true
            showMessage(String(localized: "Camera access is off. Turn it on for Fud AI in the iOS Settings app."), offersSettings: true)
        case .unavailable:
            guard !handleCameraFailure() else { return }
            showMessage(String(localized: "Camera is unavailable."), offersSettings: false)
        }
    }

    private func configureRotation(for device: AVCaptureDevice) {
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: capturePreviewView.previewLayer)
        rotationCoordinator = coordinator
        applyPreviewRotation(coordinator.videoRotationAngleForHorizonLevelPreview)
        rotationObservation = coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.new]) { [weak self] _, change in
            guard let angle = change.newValue else { return }
            DispatchQueue.main.async {
                self?.applyPreviewRotation(angle)
            }
        }
    }

    private func applyPreviewRotation(_ angle: CGFloat) {
        guard let connection = capturePreviewView.previewLayer.connection,
              connection.isVideoRotationAngleSupported(angle),
              connection.videoRotationAngle != angle else { return }
        connection.videoRotationAngle = angle
    }

    // MARK: - Focus

    @objc private func previewTapped(_ recognizer: UITapGestureRecognizer) {
        guard recognizer.state == .ended, !hasLeft, !isCapturing else { return }
        let point = recognizer.location(in: view)
        if view.hitTest(point, with: nil) is UIControl { return }
        guard let previewRect = activePreviewRect(), previewRect.contains(point),
              let devicePoint = focusDevicePoint(forViewPoint: point) else { return }
        let request = focusState.userFocus(at: devicePoint)
        applyFocus(request)
        focusIndicator.show(at: point)
        let generation = focusState.generation
        DispatchQueue.main.asyncAfter(deadline: .now() + CameraFocusState.userFocusTimeout) { [weak self] in
            self?.focusHoldTimedOut(generation: generation)
        }
    }

    /// The live preview's frame in this view; taps outside it don't focus.
    func activePreviewRect() -> CGRect? {
        guard isCaptureSessionRunning else { return nil }
        let unit = CGRect(x: 0, y: 0, width: 1, height: 1)
        let rect = capturePreviewView.previewLayer.layerRectConverted(fromMetadataOutputRect: unit)
        guard rect.width > 0, rect.height > 0 else { return nil }
        return capturePreviewView.convert(rect, to: view)
    }

    /// A point in this view as a focus point of interest.
    func focusDevicePoint(forViewPoint point: CGPoint) -> CGPoint? {
        let layerPoint = capturePreviewView.convert(point, from: view)
        return capturePreviewView.previewLayer.captureDevicePointConverted(fromLayerPoint: layerPoint)
    }

    func applyFocus(_ request: CameraFocusRequest) {
        capture.applyFocus(request)
    }

    /// Listens for `device`'s scene changes, which hand a tap focus back to the centre.
    func trackFocus(of device: AVCaptureDevice) {
        endFocusTracking()
        // A virtual camera may report the change from the lens in use.
        let deviceIDs = Set([device.uniqueID] + device.constituentDevices.map(\.uniqueID))
        subjectAreaObserver = NotificationCenter.default.addObserver(
            forName: AVCaptureDevice.subjectAreaDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let changed = notification.object as? AVCaptureDevice, deviceIDs.contains(changed.uniqueID) else { return }
            MainActor.assumeIsolated {
                self?.sceneChanged()
            }
        }
    }

    /// Forgets any tap focus and stops listening for scene changes.
    func endFocusTracking() {
        if let subjectAreaObserver {
            NotificationCenter.default.removeObserver(subjectAreaObserver)
            self.subjectAreaObserver = nil
        }
        focusState.reset()
        focusIndicator.hide()
    }

    private func focusHoldTimedOut(generation: Int) {
        guard let request = focusState.holdTimedOut(generation: generation) else { return }
        applyFocus(request)
    }

    private func sceneChanged() {
        guard let request = focusState.sceneChanged() else { return }
        applyFocus(request)
        focusIndicator.hide()
    }

    // MARK: - Capture

    @objc private func shutterTapped() {
        guard !isCapturing, !hasLeft else { return }
        takePhoto()
    }

    /// Automatic, like the camera this replaced (the system image picker).
    var photoFlashMode: AVCaptureDevice.FlashMode { .auto }

    /// Takes a photo with AVFoundation. The AR camera overrides this while ARKit runs.
    func takePhoto() {
        guard isCaptureSessionRunning else { return }
        isCapturing = true
        // The photo comes out the way the phone is held, like the system camera's.
        let angle = rotationCoordinator?.videoRotationAngleForHorizonLevelCapture ?? 90
        capture.capturePhoto(
            videoRotationAngle: angle,
            flashMode: photoFlashMode,
            willCapture: { [weak self] in
                DispatchQueue.main.async { self?.flashPreview() }
            },
            completion: { [weak self] image in
                DispatchQueue.main.async { self?.photoCaptureDidFinish(image) }
            }
        )
    }

    private func photoCaptureDidFinish(_ image: UIImage?) {
        guard !hasLeft else { return }
        guard let image else {
            isCapturing = false
            return
        }
        deliver(image, scale: nil)
    }

    /// Hands the photo over; this camera is done.
    func deliver(_ image: UIImage, scale: PortionScale?) {
        hasLeft = true
        onCapture?(image, scale)
    }

    private func flashPreview() {
        capturePreviewView.layer.opacity = 0
        UIView.animate(withDuration: 0.25) {
            self.capturePreviewView.layer.opacity = 1
        }
    }

    @objc private func cancelTapped() {
        hasLeft = true
        onCancel?()
    }

    // MARK: - Layout

    private func buildBottomBar() {
        bottomBar.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bottomBar)

        let shutterOuter = UIView()
        shutterOuter.backgroundColor = .white
        shutterOuter.layer.cornerRadius = 37
        shutterOuter.isUserInteractionEnabled = false
        shutterOuter.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.addSubview(shutterOuter)

        let shutterInner = UIView()
        shutterInner.backgroundColor = .white
        shutterInner.layer.cornerRadius = 32
        shutterInner.layer.borderWidth = 2
        shutterInner.layer.borderColor = UIColor.black.withAlphaComponent(0.15).cgColor
        shutterInner.isUserInteractionEnabled = false
        shutterInner.translatesAutoresizingMaskIntoConstraints = false
        shutterOuter.addSubview(shutterInner)

        shutterButton.accessibilityLabel = String(localized: "Take photo")
        shutterButton.translatesAutoresizingMaskIntoConstraints = false
        shutterButton.addTarget(self, action: #selector(shutterTapped), for: .touchUpInside)
        bottomBar.addSubview(shutterButton)

        let cancelButton = UIButton(type: .system)
        cancelButton.setTitle(String(localized: "Cancel"), for: .normal)
        cancelButton.setTitleColor(.white, for: .normal)
        cancelButton.titleLabel?.font = .systemFont(ofSize: 17)
        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        bottomBar.addSubview(cancelButton)

        NSLayoutConstraint.activate([
            bottomBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomBar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            bottomBar.heightAnchor.constraint(equalToConstant: Self.bottomBarHeight),

            shutterOuter.centerXAnchor.constraint(equalTo: bottomBar.centerXAnchor),
            shutterOuter.centerYAnchor.constraint(equalTo: bottomBar.topAnchor, constant: 50),
            shutterOuter.widthAnchor.constraint(equalToConstant: 74),
            shutterOuter.heightAnchor.constraint(equalToConstant: 74),
            shutterInner.centerXAnchor.constraint(equalTo: shutterOuter.centerXAnchor),
            shutterInner.centerYAnchor.constraint(equalTo: shutterOuter.centerYAnchor),
            shutterInner.widthAnchor.constraint(equalToConstant: 64),
            shutterInner.heightAnchor.constraint(equalToConstant: 64),
            shutterButton.leadingAnchor.constraint(equalTo: shutterOuter.leadingAnchor),
            shutterButton.trailingAnchor.constraint(equalTo: shutterOuter.trailingAnchor),
            shutterButton.topAnchor.constraint(equalTo: shutterOuter.topAnchor),
            shutterButton.bottomAnchor.constraint(equalTo: shutterOuter.bottomAnchor),

            cancelButton.leadingAnchor.constraint(equalTo: bottomBar.leadingAnchor, constant: 20),
            cancelButton.centerYAnchor.constraint(equalTo: shutterOuter.centerYAnchor),
        ])

        if let titleText {
            let label = UILabel()
            label.text = titleText
            label.textColor = .white
            label.font = .systemFont(ofSize: 17, weight: .semibold)
            label.translatesAutoresizingMaskIntoConstraints = false
            bottomBar.addSubview(label)
            NSLayoutConstraint.activate([
                label.centerXAnchor.constraint(equalTo: bottomBar.centerXAnchor),
                label.topAnchor.constraint(equalTo: shutterOuter.bottomAnchor, constant: 14),
            ])
        }
    }

    private func showMessage(_ text: String, offersSettings: Bool) {
        removeMessage()
        shutterButton.isEnabled = false

        let label = UILabel()
        label.text = text
        label.textColor = .white
        label.font = .preferredFont(forTextStyle: .headline)
        label.adjustsFontForContentSizeCategory = true
        label.textAlignment = .center
        label.numberOfLines = 0

        let stack = UIStackView(arrangedSubviews: [label])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 16
        if offersSettings {
            var configuration = UIButton.Configuration.filled()
            configuration.title = String(localized: "Open Settings")
            configuration.baseBackgroundColor = .white
            configuration.baseForegroundColor = .black
            configuration.cornerStyle = .capsule
            let button = UIButton(configuration: configuration, primaryAction: UIAction { _ in
                guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                UIApplication.shared.open(url)
            })
            stack.addArrangedSubview(button)
        }
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.insertSubview(stack, belowSubview: bottomBar)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: capturePreviewView.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: capturePreviewView.centerYAnchor),
            stack.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -64),
            label.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -64),
        ])
        messageView = stack
    }

    private func removeMessage() {
        messageView?.removeFromSuperview()
        messageView = nil
        shutterButton.isEnabled = true
    }
}

/// A view whose layer is the capture preview.
final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        // The layer class above guarantees the type.
        layer as! AVCaptureVideoPreviewLayer
    }
}

// MARK: - Capture session

/// The AVFoundation side of `FoodCameraController`. Session work runs on its own queue.
nonisolated final class FoodPhotoCapture: NSObject, @unchecked Sendable {
    enum StartResult: @unchecked Sendable {
        case started(AVCaptureDevice)
        case denied
        case unavailable
    }

    let session = AVCaptureSession()

    private let queue = DispatchQueue(label: "com.apoorvdarshan.calorietracker.foodcamera")
    private let photoOutput = AVCapturePhotoOutput()
    private let lock = NSLock()
    /// Guarded by `lock`. Grows with every start and stop, so a start that finishes
    /// after `stop()` (for example behind the permission prompt) doesn't run the camera.
    private var generation = 0
    // Confined to `queue`.
    /// Set once the session has its camera and photo output; until then every start retries.
    private var device: AVCaptureDevice?
    private var photoDimensions: CMVideoDimensions?
    private var shouldRun = false
    private var capturesInFlight: [Int64: PhotoCaptureDelegate] = [:]
    private var runtimeErrorObserver: NSObjectProtocol?

    deinit {
        if let runtimeErrorObserver {
            NotificationCenter.default.removeObserver(runtimeErrorObserver)
        }
    }

    /// Asks for camera access when needed, then starts the camera.
    func start(completion: @escaping @Sendable (StartResult) -> Void) {
        let number = lock.withLock {
            generation += 1
            return generation
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            queue.async { [self] in completion(startOnQueue(number)) }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [self] granted in
                guard granted else {
                    completion(.denied)
                    return
                }
                queue.async { [self] in completion(startOnQueue(number)) }
            }
        default:
            completion(.denied)
        }
    }

    func stop() {
        lock.withLock { generation += 1 }
        queue.async { [self] in
            shouldRun = false
            if session.isRunning {
                session.stopRunning()
            }
        }
    }

    func applyFocus(_ request: CameraFocusRequest) {
        queue.async { [self] in
            guard let device, session.isRunning else { return }
            CameraDeviceConfigurator.apply(request, to: device)
        }
    }

    /// Takes a photo rotated to `videoRotationAngle`; `completion` gets nil if it fails.
    func capturePhoto(
        videoRotationAngle: CGFloat,
        flashMode: AVCaptureDevice.FlashMode,
        willCapture: @escaping @Sendable () -> Void,
        completion: @escaping @Sendable (UIImage?) -> Void
    ) {
        queue.async { [self] in
            guard session.isRunning, let connection = photoOutput.connection(with: .video) else {
                completion(nil)
                return
            }
            if connection.isVideoRotationAngleSupported(videoRotationAngle) {
                connection.videoRotationAngle = videoRotationAngle
            }
            // JPEG with balanced quality, as the default settings are.
            let settings = AVCapturePhotoSettings()
            if let photoDimensions {
                settings.maxPhotoDimensions = photoDimensions
            }
            if photoOutput.supportedFlashModes.contains(flashMode) {
                settings.flashMode = flashMode
            }
            let id = settings.uniqueID
            let delegate = PhotoCaptureDelegate(willCapture: willCapture) { [weak self] image in
                self?.queue.async { [weak self] in
                    self?.capturesInFlight[id] = nil
                }
                completion(image)
            }
            // The photo output holds its delegate weakly.
            capturesInFlight[id] = delegate
            photoOutput.capturePhoto(with: settings, delegate: delegate)
        }
    }

    // MARK: Queue

    private func startOnQueue(_ number: Int) -> StartResult {
        guard lock.withLock({ number == generation }) else { return .unavailable }
        if device == nil {
            configure()
        }
        guard let device else { return .unavailable }
        if !session.isRunning {
            // Every start looks like the system camera: 1×, continuous autofocus.
            CameraDeviceConfigurator.configureForCapture(
                device,
                zoomFactor: BackCameraSelection.wideAngleZoomFactor(for: device),
                rangeRestriction: .none
            )
            session.startRunning()
        }
        shouldRun = session.isRunning
        return session.isRunning ? .started(device) : .unavailable
    }

    /// Leaves the session empty when it fails, so a later start can try again.
    private func configure() {
        session.beginConfiguration()
        if session.canSetSessionPreset(.photo) {
            session.sessionPreset = .photo
        }
        guard let camera = BackCameraSelection.preferredBackVideoCaptureDevice(),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input) else {
            session.commitConfiguration()
            logger.error("[FoodCamera] The back camera isn't available")
            return
        }
        session.addInput(input)
        guard session.canAddOutput(photoOutput) else {
            session.removeInput(input)
            session.commitConfiguration()
            logger.error("[FoodCamera] Can't add the photo output")
            return
        }
        session.addOutput(photoOutput)
        // Allows optical image stabilisation for photos; each photo still asks for balanced.
        photoOutput.maxPhotoQualityPrioritization = .quality
        session.commitConfiguration()

        // The session picks the format on commit, and the photo size depends on it.
        if let dimensions = BackCameraSelection.photoDimensions(from: camera.activeFormat.supportedMaxPhotoDimensions) {
            photoOutput.maxPhotoDimensions = dimensions
            photoDimensions = dimensions
        }
        device = camera
        if runtimeErrorObserver == nil {
            observeRuntimeErrors()
        }
    }

    private func observeRuntimeErrors() {
        runtimeErrorObserver = NotificationCenter.default.addObserver(
            forName: AVCaptureSession.runtimeErrorNotification,
            object: session,
            queue: nil
        ) { [weak self] notification in
            guard let error = notification.userInfo?[AVCaptureSessionErrorKey] as? AVError,
                  error.code == .mediaServicesWereReset else { return }
            self?.queue.async { [weak self] in
                guard let self, shouldRun, !session.isRunning else { return }
                session.startRunning()
            }
        }
    }
}

nonisolated private final class PhotoCaptureDelegate: NSObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    private let willCapture: @Sendable () -> Void
    private let completion: @Sendable (UIImage?) -> Void
    // Written and read only by the photo output's callbacks, which arrive in order.
    private var image: UIImage?

    init(willCapture: @escaping @Sendable () -> Void, completion: @escaping @Sendable (UIImage?) -> Void) {
        self.willCapture = willCapture
        self.completion = completion
    }

    func photoOutput(_ output: AVCapturePhotoOutput, willCapturePhotoFor resolvedSettings: AVCaptureResolvedPhotoSettings) {
        willCapture()
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error {
            logger.error("[FoodCamera] Photo failed: \(error.localizedDescription, privacy: .public)")
            return
        }
        image = photo.fileDataRepresentation().flatMap { UIImage(data: $0) }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        completion(image)
    }
}
