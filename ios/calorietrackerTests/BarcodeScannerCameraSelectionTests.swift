import AVFoundation
import Testing
@testable import calorietracker

struct BarcodeScannerCameraSelectionTests {
    @Test func preferredDeviceTypesPrioritizeVirtualMultiCamerasBeforeWide() {
        #expect(BarcodeScannerCameraSelection.preferredBackVideoDeviceTypes == [
            .builtInTripleCamera,
            .builtInDualWideCamera,
            .builtInDualCamera,
            .builtInWideAngleCamera
        ])
    }

    @Test func preferredBackDeviceResolvesOnSimulatorOrDevice() {
        // Exercises selection logic; simulators typically fall back to wide or nil.
        _ = BarcodeScannerCameraSelection.preferredBackVideoCaptureDevice()
    }
}
