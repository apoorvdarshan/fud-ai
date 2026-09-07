@preconcurrency import AVFoundation
import SwiftUI
import UIKit

nonisolated enum HeartRateCameraFailure: Equatable, Sendable {
    case unavailable
    case permissionDenied
    case interrupted
    case timedOut
}

nonisolated enum HeartRateCameraEvent: Equatable, Sendable {
    case waitingForContact
    case measuring(progress: Double)
    case improveSignal(progress: Double)
    case completed(HeartRatePPGResult)
    case failed(HeartRateCameraFailure)
}

struct HeartRateCameraMeasurementView: View {
    let onSave: (HeartRatePPGResult) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var event: HeartRateCameraEvent = .waitingForContact
    @State private var captureID = UUID()
    @State private var didSave = false

    private var progress: Double {
        switch event {
        case let .measuring(progress), let .improveSignal(progress): progress
        case .completed: 1
        default: 0
        }
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            HeartRateCameraCaptureView { newEvent in
                handle(newEvent)
            }
            .id(captureID)
            .ignoresSafeArea()
            .accessibilityHidden(true)

            LinearGradient(
                colors: [.black.opacity(0.60), .black.opacity(0.12), .black.opacity(0.82)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)

            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 22) {
                        HStack {
                            Button("Cancel") { dismiss() }
                                .buttonStyle(.bordered)
                                .tint(.white)
                                .frame(minHeight: 44)
                            Spacer()
                        }

                        Text("Measure Heart Rate")
                            .font(.system(.title2, design: .rounded, weight: .bold))
                            .foregroundStyle(.white)

                        let diameter = measurementDiameter(in: geometry.size)
                        ZStack {
                            Circle()
                                .stroke(.white.opacity(0.18), lineWidth: 14)
                            Circle()
                                .trim(from: 0, to: progress)
                                .stroke(
                                    LinearGradient(
                                        colors: AppColors.calorieGradient,
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    ),
                                    style: StrokeStyle(lineWidth: 14, lineCap: .round)
                                )
                                .rotationEffect(.degrees(-90))
                                .animation(.linear(duration: 0.18), value: progress)

                            if case let .completed(result) = event {
                                VStack(spacing: 2) {
                                    Text(result.bpm.formatted())
                                        .font(.system(size: 58, weight: .bold, design: .rounded))
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.55)
                                    Text("bpm")
                                        .font(.system(.headline, design: .rounded))
                                }
                                .foregroundStyle(.white)
                                .padding(.horizontal, 24)
                            } else {
                                Image(systemName: "heart.fill")
                                    .font(.system(size: min(58, diameter * 0.32), weight: .semibold))
                                    .foregroundStyle(AppColors.calorie)
                                    .symbolEffect(.pulse, options: .repeating, value: progress > 0)
                            }
                        }
                        .frame(width: diameter, height: diameter)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("Heart rate measurement")
                        .accessibilityValue(accessibilityMeasurementValue)

                        VStack(spacing: 10) {
                            Text(statusTitle)
                                .font(.system(.title3, design: .rounded, weight: .semibold))
                                .foregroundStyle(.white)
                                .multilineTextAlignment(.center)

                            Text(statusGuidance)
                                .font(.system(.body, design: .rounded))
                                .foregroundStyle(.white.opacity(0.78))
                                .multilineTextAlignment(.center)

                            if case let .completed(result) = event {
                                Label(
                                    "Quality \(Int((result.quality * 100).rounded()))%",
                                    systemImage: "checkmark.seal.fill"
                                )
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                .foregroundStyle(.white)
                            }
                        }
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityElement(children: .combine)

                        measurementActions

                        VStack(spacing: 8) {
                            if event.showsWarmthWarning {
                                Label("The flash may feel warm. Remove your finger if it becomes uncomfortable.", systemImage: "thermometer.medium")
                                    .font(.system(.caption, design: .rounded, weight: .medium))
                                    .foregroundStyle(.orange)
                            }
                            Text("For general wellness only. Fud AI is not a medical device. Do not use this estimate for diagnosis or emergencies.")
                                .font(.system(.caption2, design: .rounded))
                                .foregroundStyle(.white.opacity(0.65))
                        }
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: geometry.size.height, alignment: .top)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 18)
                }
                .scrollIndicators(.hidden)
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        .interactiveDismissDisabled(!event.isCompleted)
        .onChange(of: event.accessibilityPhase) { _, _ in
            guard UIAccessibility.isVoiceOverRunning else { return }
            UIAccessibility.post(notification: .announcement, argument: statusAnnouncement)
        }
    }

    @ViewBuilder
    private var measurementActions: some View {
        if case .completed = event {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) { completedActionButtons }
                VStack(spacing: 10) { completedActionButtons }
            }
            .controlSize(.large)
        } else if event.canRetry {
            Button(action: restartMeasurement) {
                Label("Try Again", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.calorie)
            .controlSize(.large)
        }
    }

    @ViewBuilder
    private var completedActionButtons: some View {
        Button(action: restartMeasurement) {
            Label("Try Again", systemImage: "arrow.clockwise")
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .tint(.white)

        Button(action: saveCompletedReading) {
            Label("Save Reading", systemImage: "checkmark.circle.fill")
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        .tint(AppColors.calorie)
    }

    private func measurementDiameter(in size: CGSize) -> CGFloat {
        min(220, max(150, min(size.width - 48, size.height * 0.36)))
    }

    private func handle(_ newEvent: HeartRateCameraEvent) {
        // Once a quality-checked estimate is ready, keep it available until the
        // person explicitly saves, retries, or cancels. A late camera-session
        // interruption (for example, when the app backgrounds) must not replace
        // the unsaved result with an error state.
        guard !event.isCompleted else { return }
        event = newEvent
    }

    private func saveCompletedReading() {
        guard case let .completed(result) = event, !didSave else { return }
        didSave = true
        onSave(result)
        dismiss()
    }

    private func restartMeasurement() {
        didSave = false
        event = .waitingForContact
        captureID = UUID()
    }

    private var statusTitle: LocalizedStringKey {
        switch event {
        case .waitingForContact: "Cover the Main Camera"
        case .measuring: "Measuring…"
        case .improveSignal: "Almost There"
        case .completed: "Estimated Heart Rate"
        case .failed(.permissionDenied): "Camera Access Needed"
        case .failed(.unavailable): "Camera Unavailable"
        case .failed(.interrupted): "Measurement Interrupted"
        case .failed(.timedOut): "Couldn’t Get a Stable Reading"
        }
    }

    private var statusGuidance: LocalizedStringKey {
        switch event {
        case .waitingForContact:
            "Put your fingertip over the main rear camera (the lens closest to the flash). You do not need to cover the flash—it only needs to light the side of your finger. Press gently."
        case .measuring:
            "Keep your fingertip still over the main camera while Fud AI checks the pulse."
        case .improveSignal:
            "Still checking for a stable pulse. Keep the main camera covered and press gently, not hard."
        case .completed:
            "Review the quality-checked estimate, then save it to Heart Rate History if it looks plausible."
        case .failed(.permissionDenied):
            "Allow camera access in Settings to measure a pulse with your fingertip."
        case .failed(.unavailable):
            "This device does not provide the rear camera and flash needed for measurement."
        case .failed(.interrupted):
            "The camera session stopped. Try again when the app is active."
        case .failed(.timedOut):
            "No stable pulse was found. Cover the main camera next to the flash (flash can stay uncovered), press gently, and try again."
        }
    }

    private var statusAnnouncement: String {
        switch event {
        case .waitingForContact: String(localized: "Cover the Main Camera")
        case .measuring: String(localized: "Measuring…")
        case .improveSignal: String(localized: "Almost There")
        case let .completed(result):
            String(localized: "\(result.bpm) beats per minute, ready to save")
        case .failed(.permissionDenied): String(localized: "Camera Access Needed")
        case .failed(.unavailable): String(localized: "Camera Unavailable")
        case .failed(.interrupted): String(localized: "Measurement Interrupted")
        case .failed(.timedOut): String(localized: "Couldn’t Get a Stable Reading")
        }
    }

    private var accessibilityMeasurementValue: String {
        switch event {
        case let .completed(result):
            String(localized: "\(result.bpm) beats per minute, ready to save")
        case .measuring, .improveSignal:
            String(localized: "\(Int((progress * 100).rounded())) percent complete")
        default:
            String(localized: "Not measuring")
        }
    }
}

private extension HeartRateCameraEvent {
    var accessibilityPhase: Int {
        switch self {
        case .waitingForContact: 0
        case .measuring: 1
        case .improveSignal: 2
        case .completed: 3
        case .failed(.unavailable): 4
        case .failed(.permissionDenied): 5
        case .failed(.interrupted): 6
        case .failed(.timedOut): 7
        }
    }

    var isCompleted: Bool {
        if case .completed = self { return true }
        return false
    }

    var canRetry: Bool {
        switch self {
        case .failed(.timedOut), .failed(.interrupted): true
        default: false
        }
    }

    var endsCapture: Bool {
        switch self {
        case .completed, .failed: true
        default: false
        }
    }

    var showsWarmthWarning: Bool {
        switch self {
        case .waitingForContact, .measuring, .improveSignal, .completed: true
        case .failed: false
        }
    }
}

private struct HeartRateCameraCaptureView: UIViewControllerRepresentable {
    let onEvent: (HeartRateCameraEvent) -> Void

    func makeUIViewController(context: Context) -> HeartRateCaptureViewController {
        HeartRateCaptureViewController(onEvent: onEvent)
    }

    func updateUIViewController(_ uiViewController: HeartRateCaptureViewController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: HeartRateCaptureViewController, coordinator: ()) {
        uiViewController.stopCapture()
    }
}

@MainActor
private final class HeartRateCaptureViewController: UIViewController {
    private static let maximumWallClockCaptureDuration: TimeInterval = 45

    private let onEvent: (HeartRateCameraEvent) -> Void
    private let sessionQueue = DispatchQueue(label: "ai.fud.heartrate.session", qos: .userInitiated)
    private let sampleQueue = DispatchQueue(label: "ai.fud.heartrate.samples", qos: .userInitiated)
    private lazy var analyzer = HeartRateFrameAnalyzer { [weak self] event in
        self?.report(event)
    }
    private var session: AVCaptureSession?
    private var camera: AVCaptureDevice?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var notificationTokens: [NSObjectProtocol] = []
    private let captureGate = HeartRateCaptureGate()
    private var wallClockWatchdog: DispatchWorkItem?

    init(onEvent: @escaping (HeartRateCameraEvent) -> Void) {
        self.onEvent = onEvent
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        observeLifecycle()
        requestCameraAccess()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopCapture()
    }

    deinit {
        notificationTokens.forEach(NotificationCenter.default.removeObserver)
    }

    func stopCapture() {
        guard captureGate.stop() else { return }
        let watchdog = wallClockWatchdog
        wallClockWatchdog = nil
        watchdog?.cancel()
        analyzer.stop()
        let session = session
        let camera = camera
        sessionQueue.async {
            if let camera, camera.isTorchAvailable {
                do {
                    try camera.lockForConfiguration()
                    camera.torchMode = .off
                    camera.unlockForConfiguration()
                } catch {}
            }
            if session?.isRunning == true { session?.stopRunning() }
        }
    }

    private func startWallClockWatchdog() {
        let reportTimeout: @MainActor @Sendable () -> Void = { [weak self] in
            self?.report(.failed(.timedOut))
        }
        let watchdog = DispatchWorkItem {
            Task { @MainActor in reportTimeout() }
        }
        guard !captureGate.isStopped else { return }
        wallClockWatchdog?.cancel()
        wallClockWatchdog = watchdog
        DispatchQueue.global(qos: .userInitiated).asyncAfter(
            deadline: .now() + Self.maximumWallClockCaptureDuration,
            execute: watchdog
        )
    }

    private func requestCameraAccess() {
#if targetEnvironment(simulator)
        // The simulator may expose a proxied camera, but it cannot provide the
        // rear-camera torch/contact conditions required for fingertip PPG.
        // Defer the state callback until the representable has mounted; a
        // synchronous callback from viewDidLoad can be discarded by SwiftUI.
        Task { @MainActor [weak self] in
            self?.report(.failed(.unavailable))
        }
#else
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureSession()
        case .notDetermined:
            let handleAuthorization: @MainActor @Sendable (Bool) -> Void = { [weak self] granted in
                guard let self else { return }
                if granted {
                    self.configureSession()
                } else {
                    self.report(.failed(.permissionDenied))
                }
            }
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    handleAuthorization(granted)
                }
            }
        case .denied, .restricted:
            report(.failed(.permissionDenied))
        @unknown default:
            report(.failed(.unavailable))
        }
#endif
    }

    private func configureSession() {
        guard !isStopped else { return }
        let session = AVCaptureSession()
        session.beginConfiguration()
        session.sessionPreset = .medium

        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              camera.hasTorch,
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input) else {
            session.commitConfiguration()
            report(.failed(.unavailable))
            return
        }
        session.addInput(input)

        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            report(.failed(.unavailable))
            return
        }
        session.addOutput(output)
        output.setSampleBufferDelegate(analyzer, queue: sampleQueue)
        session.commitConfiguration()

        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = view.bounds
        view.layer.addSublayer(previewLayer)
        self.previewLayer = previewLayer
        self.session = session
        self.camera = camera

        let reportInterruption: @MainActor @Sendable () -> Void = { [weak self] in
            self?.report(.failed(.interrupted))
        }

        notificationTokens.append(
            NotificationCenter.default.addObserver(
                forName: .AVCaptureSessionRuntimeError,
                object: session,
                queue: .main
            ) { _ in
                Task { @MainActor in reportInterruption() }
            }
        )

        notificationTokens.append(
            NotificationCenter.default.addObserver(
                forName: .AVCaptureSessionWasInterrupted,
                object: session,
                queue: .main
            ) { _ in
                Task { @MainActor in reportInterruption() }
            }
        )

        let captureGate = captureGate
        let reportUnavailable: @MainActor @Sendable () -> Void = { [weak self] in
            self?.report(.failed(.unavailable))
        }
        sessionQueue.async {
            guard !captureGate.isStopped else { return }
            do {
                // A physical camera's torch may be unavailable until its capture session is
                // running. Starting the torch first caused real devices to fail before they
                // could deliver their first frame.
                session.startRunning()
                guard session.isRunning, !captureGate.isStopped else {
                    if session.isRunning { session.stopRunning() }
                    return
                }
                try camera.lockForConfiguration()
                do {
                    try camera.setTorchModeOn(level: min(0.55, AVCaptureDevice.maxAvailableTorchLevel))
                    if camera.isFocusModeSupported(.locked) { camera.focusMode = .locked }
                    camera.unlockForConfiguration()
                } catch {
                    camera.unlockForConfiguration()
                    throw error
                }
                guard !captureGate.isStopped else {
                    session.stopRunning()
                    return
                }
                Task { @MainActor [weak self] in
                    self?.startWallClockWatchdog()
                }
            } catch {
                Task { @MainActor in
                    reportUnavailable()
                }
            }
        }
    }

    private func observeLifecycle() {
        let reportInterruption: @MainActor @Sendable () -> Void = { [weak self] in
            self?.report(.failed(.interrupted))
        }
        notificationTokens.append(
            NotificationCenter.default.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil,
                queue: .main
            ) { _ in
                Task { @MainActor in reportInterruption() }
            }
        )
    }

    private func report(_ event: HeartRateCameraEvent) {
        guard !isStopped else { return }
        onEvent(event)
        if event.endsCapture { stopCapture() }
    }

    private var isStopped: Bool {
        captureGate.isStopped
    }
}

/// A small nonisolated cancellation gate shared with the capture queue. UI and
/// observer callbacks stay MainActor-owned, while the serial camera queue only
/// reads this lock-protected lifecycle state and never reaches into the view
/// controller's actor-isolated properties.
nonisolated private final class HeartRateCaptureGate: @unchecked Sendable {
    private let lock = NSLock()
    private var stopped = false

    var isStopped: Bool {
        lock.withLock { stopped }
    }

    @discardableResult
    func stop() -> Bool {
        lock.withLock {
            guard !stopped else { return false }
            stopped = true
            return true
        }
    }
}

nonisolated private final class HeartRateFrameAnalyzer: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    private static let maximumContinuousContactDuration: TimeInterval = 30
    private static let minimumAcceptedQuality = 0.32

    private let onEvent: @MainActor @Sendable (HeartRateCameraEvent) -> Void
    private let lock = NSLock()
    private var processor = HeartRatePPGProcessor(
        configuration: HeartRatePPGProcessor.Configuration(
            minimumMeasurementDuration: 15,
            maximumMeasurementDuration: 30
        )
    )
    private var contactStartedAt: TimeInterval?
    private var finished = false

    init(onEvent: @escaping @MainActor @Sendable (HeartRateCameraEvent) -> Void) {
        self.onEvent = onEvent
    }

    func stop() {
        lock.withLock {
            finished = true
            processor.reset()
            contactStartedAt = nil
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              let channels = Self.meanChannels(from: imageBuffer) else { return }
        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds
        guard timestamp.isFinite else { return }

        let event: HeartRateCameraEvent? = lock.withLock {
            guard !finished else { return nil }
            let update = processor.process(
                sample: HeartRatePPGSample(
                    timestamp: timestamp,
                    red: channels.red,
                    green: channels.green,
                    blue: channels.blue,
                    redClippedFraction: channels.redClippedFraction,
                    redSpatialStandardDeviation: channels.redSpatialStandardDeviation
                )
            )

            guard update.contactDetected else {
                contactStartedAt = nil
                return .waitingForContact
            }
            if contactStartedAt == nil { contactStartedAt = timestamp }
            let contactElapsed = timestamp - (contactStartedAt ?? timestamp)
            // Stretch the ring across the full contact budget so it does not look "done"
            // while quality checks are still running after the minimum window.
            let displayProgress = min(max(contactElapsed / Self.maximumContinuousContactDuration, 0), 1)

            if let result = update.result,
               update.status == .ready,
               result.quality >= Self.minimumAcceptedQuality {
                finished = true
                return .completed(result)
            }

            if let contactStartedAt,
               timestamp - contactStartedAt >= Self.maximumContinuousContactDuration {
                finished = true
                return .failed(.timedOut)
            }

            switch update.status {
            case .waitingForContact:
                return .waitingForContact
            case .collecting where displayProgress < 0.5:
                return .measuring(progress: displayProgress)
            case .collecting, .ready, .rejected:
                return .improveSignal(progress: displayProgress)
            }
        }

        guard let event else { return }
        Task { @MainActor [onEvent] in onEvent(event) }
    }

    private static func meanChannels(from pixelBuffer: CVPixelBuffer) -> (
        red: Double,
        green: Double,
        blue: Double,
        redClippedFraction: Double,
        redSpatialStandardDeviation: Double
    )? {
        guard CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_32BGRA else { return nil }
        guard CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        guard width > 0, height > 0 else { return nil }

        let xStart = width / 3
        let xEnd = width * 2 / 3
        let yStart = height / 3
        let yEnd = height * 2 / 3
        let xStep = max(1, (xEnd - xStart) / 20)
        let yStep = max(1, (yEnd - yStart) / 20)
        let bytes = baseAddress.assumingMemoryBound(to: UInt8.self)
        var red = 0.0
        var redSquared = 0.0
        var redClipped = 0
        var green = 0.0
        var blue = 0.0
        var count = 0

        for y in stride(from: yStart, to: yEnd, by: yStep) {
            let row = bytes.advanced(by: y * bytesPerRow)
            for x in stride(from: xStart, to: xEnd, by: xStep) {
                let pixel = row.advanced(by: x * 4)
                blue += Double(pixel[0])
                green += Double(pixel[1])
                let redValue = Double(pixel[2])
                red += redValue
                redSquared += redValue * redValue
                if pixel[2] >= 250 { redClipped += 1 }
                count += 1
            }
        }

        guard count > 0 else { return nil }
        let countValue = Double(count)
        let channelScale = 1 / (countValue * 255)
        let redMean = red / countValue
        let redVariance = max(redSquared / countValue - redMean * redMean, 0)
        return (
            red * channelScale,
            green * channelScale,
            blue * channelScale,
            Double(redClipped) / countValue,
            sqrt(redVariance) / 255
        )
    }
}
