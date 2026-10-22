import AVFoundation
import Testing
import UIKit
@testable import calorietracker

@MainActor
struct VoiceAssistantCameraLifecycleTests {
    @Test func cameraWaitRespondsToCancellationWithoutWaitingForAFrame() async {
        let camera = VoiceAssistantCamera()
        let capture = Task { await camera.captureStill(timeout: 10) }
        await Task.yield()
        let cancelledAt = ContinuousClock.now
        capture.cancel()
        #expect(await capture.value == nil)
        #expect(cancelledAt.duration(to: .now) < .seconds(2))
    }

    @Test func stillCaptureClosesCameraWithoutCollapsingAssistant() async {
        let camera = StubVoiceCamera()
        camera.still = UIImage()
        camera.frame = Data([1, 2, 3])
        let controller = VoiceAssistantController(camera: camera)
        controller.isExpanded = true
        #expect(await controller.captureStill() != nil)
        #expect(camera.stops == 1)
        #expect(camera.latestJPEG() == nil)
        #expect(controller.isExpanded)
    }

    @Test func failedStillCaptureAlsoClosesCamera() async {
        let camera = StubVoiceCamera()
        let controller = VoiceAssistantController(camera: camera)
        #expect(await controller.captureStill() == nil)
        #expect(camera.stops == 1)
    }

    @Test func cancelledLateCaptureCannotStopReplacementCamera() async {
        let camera = StubVoiceCamera()
        let gate = DeferredStillCapture()
        camera.capture = { await gate.capture() }
        let controller = VoiceAssistantController(camera: camera)
        let capture = Task { await controller.captureStill() }
        await gate.waitUntilStarted()
        controller.closeCamera()
        let replacementFrame = Data([4, 5, 6])
        camera.frame = replacementFrame
        capture.cancel()
        await gate.finish(UIImage())
        #expect(await capture.value == nil)
        #expect(camera.stops == 1)
        #expect(camera.latestJPEG() == replacementFrame)
    }

    @Test func photoPickerClosesCameraAndDismissesOnCancellation() async {
        let camera = StubVoiceCamera()
        let controller = VoiceAssistantController(camera: camera)
        let picker = Task { await controller.pickPhotos(limit: 1, timeout: 5) }
        await Task.yield()
        #expect(camera.stops == 1)
        #expect(controller.photoPickerPresented)
        picker.cancel()
        if case .cancelled = await picker.value {} else { Issue.record("Picker was not cancelled") }
        #expect(!controller.photoPickerPresented)
    }

    @Test func minimizingStopsCaptureAndDiscardsFrames() {
        let camera = StubVoiceCamera()
        let controller = VoiceAssistantController(camera: camera)
        camera.frame = Data([1, 2, 3])
        controller.minimize()
        #expect(!controller.isExpanded)
        #expect(camera.stops == 1)
        #expect(camera.latestJPEG() == nil)

        // Navigation and any future direct collapse must keep the same invariant.
        controller.isExpanded = true
        camera.frame = Data([4, 5])
        controller.isExpanded = false
        #expect(camera.stops == 2)
        #expect(camera.latestJPEG() == nil)
    }

    @Test func staleBarcodeCancellationAndTimeoutCannotFinishReplacement() async {
        let scan = VoiceAssistantBarcodeRequest()
        let first = scan.begin()
        let oldWait = Task { await scan.wait(for: first) }
        await Task.yield()
        let second = scan.begin()
        let newWait = Task { await scan.wait(for: second) }
        await Task.yield()
        scan.finish(.cancelled, request: first)
        scan.finish(.timeout, request: first)
        scan.finish(.value("123456789"), request: second)
        if case .cancelled = await oldWait.value {} else { Issue.record("Old scan should be cancelled") }
        if case .value(let code) = await newWait.value {
            #expect(code == "123456789")
        } else { Issue.record("Old callbacks cancelled the new scan") }
    }

    @Test func barcodeCancellationBeforeContinuationInstallationIsRetained() async {
        let scan = VoiceAssistantBarcodeRequest()
        let request = scan.begin()
        scan.finish(.cancelled, request: request)
        if case .cancelled = await scan.wait(for: request) {} else { Issue.record("Lost cancellation") }
    }
}

nonisolated private final class StubVoiceCamera: VoiceAssistantCameraIO, @unchecked Sendable {
    let session = AVCaptureSession()
    var onBarcode: (@Sendable (String) -> Void)?
    var frame: Data?
    var stops = 0
    var still: UIImage?
    var capture: (@Sendable () async -> UIImage?)?
    func start(mode: VoiceAssistantCamera.Mode) async -> VoiceAssistantCamera.StartResult { .started }
    func setMode(_ mode: VoiceAssistantCamera.Mode) {}
    func stop() { stops += 1; frame = nil }
    func latestJPEG() -> Data? { frame }
    func captureStill(timeout: TimeInterval) async -> UIImage? {
        if let capture { return await capture() }
        return still
    }
    func focus(at devicePoint: CGPoint) {}
}

private actor DeferredStillCapture {
    private var continuation: CheckedContinuation<UIImage?, Never>?
    private var started: [CheckedContinuation<Void, Never>] = []

    func capture() async -> UIImage? {
        await withCheckedContinuation { continuation in
            self.continuation = continuation
            started.forEach { $0.resume() }
            started.removeAll()
        }
    }

    func waitUntilStarted() async {
        if continuation != nil { return }
        await withCheckedContinuation { started.append($0) }
    }

    func finish(_ image: UIImage?) {
        continuation?.resume(returning: image)
        continuation = nil
    }
}
