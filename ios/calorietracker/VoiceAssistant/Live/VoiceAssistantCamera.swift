@preconcurrency import AVFoundation
import CoreImage
import CoreMedia
import SwiftUI
import UIKit
import os

nonisolated private let logger = Logger(subsystem: "com.apoorvdarshan.calorietracker", category: "VoiceAssistantCamera")

nonisolated protocol VoiceAssistantCameraIO: AnyObject, Sendable {
    var session: AVCaptureSession { get }
    var onBarcode: (@Sendable (String) -> Void)? { get set }
    func start(mode: VoiceAssistantCamera.Mode) async -> VoiceAssistantCamera.StartResult
    func setMode(_ mode: VoiceAssistantCamera.Mode)
    func stop()
    func latestJPEG() -> Data?
    func captureStill(timeout: TimeInterval) async -> UIImage?
    /// Focuses and exposes at a tapped point, in capture device coordinates.
    func focus(at devicePoint: CGPoint)
}

/// The back camera inside the voice assistant. It shows a live preview, keeps the
/// latest frame as a small JPEG for the model, takes full-size stills for food
/// analysis, and reads barcodes in barcode mode. Nothing is recorded; a still is
/// kept only when the user logs the food it shows.
nonisolated final class VoiceAssistantCamera: NSObject, VoiceAssistantCameraIO, AVCaptureVideoDataOutputSampleBufferDelegate,
    AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    enum Mode: String, Sendable {
        case food
        case barcode
    }

    enum StartResult: Sendable {
        case started
        case denied
        case unavailable
    }

    /// Frames for the model: at most this long on the longer side (contract: ≤ 768 px).
    static let frameMaximumSide: CGFloat = 768
    /// Model frames are encoded at most this often (seconds); they are sent less often.
    static let frameEncodeInterval: TimeInterval = 0.5

    static let barcodeTypes: [AVMetadataObject.ObjectType] = [
        .ean13, .ean8, .upce, .code128, .code39, .code93, .itf14, .interleaved2of5,
    ]
    /// The barcode frame in the camera panel spans about this share of its width.
    static let barcodeFrameWidthFraction: Double = 0.75

    let session = AVCaptureSession()

    /// Called on the main queue with each new barcode while in barcode mode.
    var onBarcode: (@Sendable (String) -> Void)?

    private let queue = DispatchQueue(label: "com.apoorvdarshan.calorietracker.voiceassistant.camera")
    private let videoOutput = AVCaptureVideoDataOutput()
    private let metadataOutput = AVCaptureMetadataOutput()
    private let context = CIContext()
    private let lock = NSLock()
    private var latestFrame: Data?
    private var lastEncodedAt: TimeInterval = 0
    private var stillWaiters: [UUID: CheckedContinuation<CGImage?, Never>] = [:]
    private var mode: Mode = .food
    private var isConfigured = false
    private var lastBarcode: (code: String, at: TimeInterval)?
    private var generation = 0
    // Confined to `queue`.
    private var device: AVCaptureDevice?
    private var focusState = CameraFocusState()
    private var subjectAreaObserver: NSObjectProtocol?

    deinit {
        if let subjectAreaObserver {
            NotificationCenter.default.removeObserver(subjectAreaObserver)
        }
    }

    static var isDenied: Bool {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        return status == .denied || status == .restricted
    }

    /// Asks for access when needed, then starts the camera in `mode`.
    func start(mode: Mode) async -> StartResult {
        let number = lock.withLock {
            generation += 1
            return generation
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            break
        case .notDetermined:
            guard await AVCaptureDevice.requestAccess(for: .video) else { return .denied }
        default:
            return .denied
        }
        let device = BackCameraSelection.preferredBackVideoCaptureDevice()
        return await withCheckedContinuation { continuation in
            queue.async { [self] in
                guard lock.withLock({ number == generation }) else {
                    continuation.resume(returning: .unavailable)
                    return
                }
                lock.withLock { self.mode = mode }
                if !isConfigured {
                    configure(device: device)
                }
                guard isConfigured else {
                    continuation.resume(returning: .unavailable)
                    return
                }
                applyMode(mode)
                if !session.isRunning {
                    session.startRunning()
                    logger.info("[Camera] Started")
                }
                continuation.resume(returning: session.isRunning ? .started : .unavailable)
            }
        }
    }

    func setMode(_ mode: Mode) {
        queue.async { [self] in
            lock.withLock {
                self.mode = mode
                lastBarcode = nil
            }
            guard isConfigured else { return }
            applyMode(mode)
        }
    }

    func stop() {
        lock.withLock { generation += 1 }
        queue.async { [self] in
            // A tap's pending timeout or scene change must not touch the camera after it
            // closes, when another camera may be using it.
            if focusState.isUserFocused, let device {
                CameraDeviceConfigurator.apply(.continuous, to: device)
            }
            focusState.reset()
            if session.isRunning {
                session.stopRunning()
                logger.info("[Camera] Stopped")
            }
            let waiters: [CheckedContinuation<CGImage?, Never>] = lock.withLock {
                latestFrame = nil
                lastBarcode = nil
                defer { stillWaiters.removeAll() }
                return Array(stillWaiters.values)
            }
            for waiter in waiters { waiter.resume(returning: nil) }
        }
    }

    /// The most recent frame for the model as JPEG, or nil if none arrived yet.
    func latestJPEG() -> Data? {
        lock.withLock { latestFrame }
    }

    /// A full-size still from the next frame, or nil if none arrives in time.
    func captureStill(timeout: TimeInterval = 3) async -> UIImage? {
        let token = UUID()
        let image: CGImage? = await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                let cancelled = lock.withLock {
                    // Cancellation can arrive before this waiter is installed.
                    guard !Task.isCancelled else { return true }
                    stillWaiters[token] = continuation
                    return false
                }
                if cancelled {
                    continuation.resume(returning: nil)
                    return
                }
                queue.asyncAfter(deadline: .now() + timeout) { [self] in
                    let waiter = lock.withLock { stillWaiters.removeValue(forKey: token) }
                    waiter?.resume(returning: nil)
                }
            }
        } onCancel: { [self] in
            let waiter = lock.withLock { stillWaiters.removeValue(forKey: token) }
            waiter?.resume(returning: nil)
        }
        return Task.isCancelled ? nil : image.map { UIImage(cgImage: $0) }
    }

    /// Tap to focus and expose there, like the system camera; see `CameraFocusState`.
    func focus(at devicePoint: CGPoint) {
        queue.async { [self] in
            guard isConfigured, session.isRunning, let device else { return }
            CameraDeviceConfigurator.apply(focusState.userFocus(at: devicePoint), to: device)
            let tap = focusState.generation
            queue.asyncAfter(deadline: .now() + CameraFocusState.userFocusTimeout) { [weak self] in
                self?.focusHoldTimedOut(generation: tap)
            }
        }
    }

    // MARK: - Configuration (camera queue)

    private func configure(device: AVCaptureDevice?) {
        // The live conversation owns the audio session; the camera must not change it.
        session.automaticallyConfiguresApplicationAudioSession = false
        session.beginConfiguration()
        let added = addInputAndOutputs(device: device)
        session.commitConfiguration()
        guard added, let device else { return }
        // Zoom and focus are set per mode (`applyMode`), once the session has its format.
        self.device = device
        observeSubjectAreaChanges(of: device)
        isConfigured = true
    }

    /// False when the camera can't be used.
    private func addInputAndOutputs(device: AVCaptureDevice?) -> Bool {
        if session.canSetSessionPreset(.hd1920x1080) {
            session.sessionPreset = .hd1920x1080
        } else {
            session.sessionPreset = .high
        }

        guard let device,
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            logger.error("[Camera] The back camera isn't available")
            return false
        }
        session.addInput(input)

        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOutput.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(videoOutput) else {
            logger.error("[Camera] Can't add the video output")
            return false
        }
        session.addOutput(videoOutput)
        if let connection = videoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
            // Portrait, as the user holds the phone.
            connection.videoRotationAngle = 90
        }

        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: queue)
        }
        return true
    }

    /// Food: the system camera's 1×, focusing at any distance, and the virtual camera
    /// switches to the ultra-wide macro lens by itself for close-ups where the iPhone
    /// has one. (At its default zoom it would stay on the 0.5× ultra-wide lens, which
    /// is fixed-focus on many iPhones.) Barcodes: focus limited to near subjects, and
    /// on iPhones that can't focus close, a little zoom so codes fill the frame from a
    /// distance the lens can focus at. Either way a mode change ends any tap focus.
    private func applyMode(_ mode: Mode) {
        if session.outputs.contains(metadataOutput) {
            let available = Self.barcodeTypes.filter { metadataOutput.availableMetadataObjectTypes.contains($0) }
            metadataOutput.metadataObjectTypes = mode == .barcode ? available : []
        }
        guard let device else { return }
        focusState.reset()
        switch mode {
        case .food:
            CameraDeviceConfigurator.configureForCapture(
                device,
                zoomFactor: BackCameraSelection.wideAngleZoomFactor(for: device),
                rangeRestriction: .none
            )
        case .barcode:
            // The panel shows the frame's full width, so there is no preview crop to allow for.
            CameraDeviceConfigurator.configureForCapture(
                device,
                zoomFactor: BackCameraSelection.barcodeZoomFactor(
                    baseZoomFactor: BackCameraSelection.wideAngleZoomFactor(for: device),
                    minimumFocusDistanceMM: device.minimumFocusDistance,
                    fieldOfViewDegrees: Double(device.activeFormat.videoFieldOfView),
                    formatAspectRatio: Self.formatAspectRatio(of: device),
                    scanWidthFraction: Self.barcodeFrameWidthFraction
                ),
                rangeRestriction: .near
            )
        }
    }

    private static func formatAspectRatio(of device: AVCaptureDevice) -> Double {
        let dimensions = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        return BackCameraSelection.formatAspectRatio(width: Int(dimensions.width), height: Int(dimensions.height))
    }

    private func focusHoldTimedOut(generation: Int) {
        guard session.isRunning, let device, let request = focusState.holdTimedOut(generation: generation) else { return }
        CameraDeviceConfigurator.apply(request, to: device)
    }

    private func sceneChanged() {
        guard session.isRunning, let device, let request = focusState.sceneChanged() else { return }
        CameraDeviceConfigurator.apply(request, to: device)
    }

    private func observeSubjectAreaChanges(of device: AVCaptureDevice) {
        // A virtual camera may report the change from the lens in use.
        let deviceIDs = Set([device.uniqueID] + device.constituentDevices.map(\.uniqueID))
        subjectAreaObserver = NotificationCenter.default.addObserver(
            forName: AVCaptureDevice.subjectAreaDidChangeNotification,
            object: nil,
            queue: nil
        ) { [weak self] notification in
            guard let changed = notification.object as? AVCaptureDevice, deviceIDs.contains(changed.uniqueID) else { return }
            self?.queue.async { [weak self] in
                self?.sceneChanged()
            }
        }
    }

    // MARK: - Delegates (camera queue)

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let image = CIImage(cvPixelBuffer: pixelBuffer)

        let waiters: [CheckedContinuation<CGImage?, Never>] = lock.withLock {
            defer { stillWaiters.removeAll() }
            return Array(stillWaiters.values)
        }
        if !waiters.isEmpty {
            let still = context.createCGImage(image, from: image.extent)
            for waiter in waiters { waiter.resume(returning: still) }
        }

        let now = CACurrentMediaTime()
        guard now - lastEncodedAt >= Self.frameEncodeInterval else { return }
        lastEncodedAt = now
        var frame = image
        let scale = min(1, Self.frameMaximumSide / max(frame.extent.width, frame.extent.height))
        if scale < 1 {
            frame = frame.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        }
        guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
              let jpeg = context.jpegRepresentation(
                of: frame,
                colorSpace: colorSpace,
                options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.7]
              ) else { return }
        lock.withLock { latestFrame = jpeg }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let code = object.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
              !code.isEmpty else { return }
        let now = CACurrentMediaTime()
        let isNew: Bool = lock.withLock {
            guard mode == .barcode else { return false }
            // The same code stays in view for a while; report it once.
            if let lastBarcode, lastBarcode.code == code, now - lastBarcode.at < 5 { return false }
            lastBarcode = (code, now)
            return true
        }
        guard isNew, let onBarcode else { return }
        DispatchQueue.main.async { onBarcode(code) }
    }
}

// MARK: - Preview

/// The live preview of the assistant's camera. A tap focuses there, with the focus square.
struct VoiceAssistantCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    /// Gets the tapped point in capture device coordinates.
    var onFocus: ((CGPoint) -> Void)?

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.onFocus = onFocus
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.onFocus = onFocus
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

        var onFocus: ((CGPoint) -> Void)?
        private let focusIndicator = CameraFocusIndicatorView()

        var previewLayer: AVCaptureVideoPreviewLayer {
            // The layer class above guarantees the type.
            layer as! AVCaptureVideoPreviewLayer
        }

        override init(frame: CGRect) {
            super.init(frame: frame)
            addSubview(focusIndicator)
            addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped(_:))))
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) is not used")
        }

        @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended, let onFocus, previewLayer.connection != nil else { return }
            let point = recognizer.location(in: self)
            onFocus(previewLayer.captureDevicePointConverted(fromLayerPoint: point))
            focusIndicator.show(at: point)
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            if let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(90) {
                connection.videoRotationAngle = 90
            }
        }
    }
}
