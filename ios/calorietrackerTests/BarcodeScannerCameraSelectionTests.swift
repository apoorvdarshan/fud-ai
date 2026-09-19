import AVFoundation
import Testing
@testable import calorietracker

struct BarcodeScannerCameraSelectionTests {
    @Test func preferredDeviceTypesPrioritizeVirtualMultiCamerasBeforeWide() {
        let types = BarcodeScannerCameraSelection.preferredBackVideoDeviceTypes
        #expect(types.first == .builtInTripleCamera)
        #expect(types.contains(.builtInDualWideCamera))
        #expect(types.last == .builtInWideAngleCamera)
    }

    @Test func preferredBackDeviceResolvesOnSimulatorOrDevice() {
        // Exercises selection logic; simulators typically fall back to wide or nil.
        _ = BarcodeScannerCameraSelection.preferredBackVideoCaptureDevice()
    }
}
