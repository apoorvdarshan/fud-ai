import AVFoundation
import CoreGraphics
import CoreMedia
import Testing
@testable import calorietracker

struct BackCameraSelectionTests {
    @Test func preferredDeviceTypesPrioritizeVirtualMultiCamerasBeforeWide() {
        #expect(BackCameraSelection.preferredBackVideoDeviceTypes == [
            .builtInTripleCamera,
            .builtInDualWideCamera,
            .builtInDualCamera,
            .builtInWideAngleCamera
        ])
    }

    @Test func preferredBackDeviceResolvesOnSimulatorOrDevice() {
        // Exercises selection logic; simulators typically fall back to wide or nil.
        _ = BackCameraSelection.preferredBackVideoCaptureDevice()
    }

    // MARK: - Wide-angle zoom

    private func wideZoom(_ types: [AVCaptureDevice.DeviceType], _ factors: [CGFloat]) -> CGFloat {
        BackCameraSelection.wideAngleZoomFactor(constituentDeviceTypes: types, switchOverZoomFactors: factors)
    }

    @Test func virtualCamerasWithAnUltraWideLensStartAtTheWideLens() {
        // Dual wide and triple cameras: zoom 1.0 is the ultra-wide (0.5×) lens.
        #expect(wideZoom([.builtInUltraWideCamera, .builtInWideAngleCamera], [2]) == 2)
        #expect(wideZoom([.builtInUltraWideCamera, .builtInWideAngleCamera, .builtInTelephotoCamera], [2, 6]) == 2)
    }

    @Test func camerasThatStartAtTheWideLensStayAtOne() {
        #expect(wideZoom([.builtInWideAngleCamera, .builtInTelephotoCamera], [2]) == 1)
        #expect(wideZoom([], []) == 1)
        #expect(wideZoom([.builtInUltraWideCamera, .builtInTelephotoCamera], [3]) == 1)
    }

    @Test func malformedSwitchOverFactorsFallBackToOne() {
        #expect(wideZoom([.builtInUltraWideCamera, .builtInWideAngleCamera], []) == 1)
        #expect(wideZoom([.builtInUltraWideCamera, .builtInWideAngleCamera], [0.5]) == 1)
        #expect(wideZoom([.builtInUltraWideCamera, .builtInWideAngleCamera], [.nan]) == 1)
    }

    // MARK: - Barcode zoom

    private func barcodeZoom(
        base: CGFloat = 2,
        focusMM: Int = 150,
        fieldOfView: Double = 107,
        aspect: Double = 9.0 / 16.0,
        fraction: Double = 0.76
    ) -> CGFloat {
        BackCameraSelection.barcodeZoomFactor(
            baseZoomFactor: base,
            minimumFocusDistanceMM: focusMM,
            fieldOfViewDegrees: fieldOfView,
            formatAspectRatio: aspect,
            scanWidthFraction: fraction
        )
    }

    @Test func macroCapableCameraGetsNoExtraBarcodeZoom() {
        // The ultra-wide macro lens focuses from about 2 cm.
        #expect(barcodeZoom(focusMM: 20) == 2)
    }

    @Test func longMinimumFocusDistanceZoomsUntilACodeFillsTheFrame() {
        // The wide lens alone: 100 mm minimum focus distance, 69.4° field of view. The
        // frame covers 2 × 100 × tan(34.7°) × 0.5625 × 0.76 ≈ 59.2 mm there, so a 30 mm
        // code needs about 1.97× to fill it.
        let zoom = barcodeZoom(base: 1, focusMM: 100, fieldOfView: 69.4)
        #expect(abs(zoom - 1.97) < 0.02)
    }

    @Test func extraBarcodeZoomIsCapped() {
        #expect(barcodeZoom(focusMM: 400) == CGFloat(2 * BackCameraSelection.barcodeMaximumExtraZoom))
    }

    @Test func unknownFocusDistanceOrBadInputKeepsTheBaseZoom() {
        #expect(barcodeZoom(focusMM: -1) == 2)
        #expect(barcodeZoom(focusMM: 0) == 2)
        #expect(barcodeZoom(fieldOfView: 0) == 2)
        #expect(barcodeZoom(fieldOfView: 180) == 2)
        #expect(barcodeZoom(aspect: 0) == 2)
        #expect(barcodeZoom(fraction: 0) == 2)
        #expect(barcodeZoom(base: .nan, focusMM: -1) == 1)
    }

    @Test func aspectFillPortraitPreviewCropsTheShortSide() {
        // iPhone 16 portrait (393 × 852) over 16:9 video shows about 82% of the short side.
        let phone = BackCameraSelection.visibleShortSideFraction(previewSize: CGSize(width: 393, height: 852), formatAspectRatio: 9.0 / 16.0)
        #expect(abs(phone - 0.82) < 0.01)
        #expect(BackCameraSelection.visibleShortSideFraction(previewSize: CGSize(width: 300, height: 400), formatAspectRatio: 9.0 / 16.0) == 1)
        #expect(BackCameraSelection.visibleShortSideFraction(previewSize: .zero, formatAspectRatio: 9.0 / 16.0) == 1)
        #expect(BackCameraSelection.visibleShortSideFraction(previewSize: CGSize(width: 852, height: 393), formatAspectRatio: 9.0 / 16.0) == 1)
    }

    @Test func formatAspectRatioIsShortSideOverLongSide() {
        #expect(BackCameraSelection.formatAspectRatio(width: 1920, height: 1080) == 0.5625)
        #expect(BackCameraSelection.formatAspectRatio(width: 3024, height: 4032) == 0.75)
        #expect(BackCameraSelection.formatAspectRatio(width: 0, height: 1080) == 0.75)
    }

    @Test func zoomIsClampedToTheDeviceRange() {
        #expect(BackCameraSelection.clampedZoomFactor(2, minimum: 1, maximum: 10) == 2)
        #expect(BackCameraSelection.clampedZoomFactor(0.5, minimum: 1, maximum: 10) == 1)
        #expect(BackCameraSelection.clampedZoomFactor(20, minimum: 1, maximum: 10) == 10)
        #expect(BackCameraSelection.clampedZoomFactor(.nan, minimum: 1, maximum: 10) == 1)
        #expect(BackCameraSelection.clampedZoomFactor(3, minimum: 1, maximum: 1) == 1)
        #expect(BackCameraSelection.clampedZoomFactor(3, minimum: 2, maximum: 1) == 2)
    }

    // MARK: - Photo size

    @Test func photoSizeIsTheLargestUpToTwelveMegapixels() {
        let chosen = BackCameraSelection.photoDimensions(from: [
            CMVideoDimensions(width: 8064, height: 6048),
            CMVideoDimensions(width: 4032, height: 3024),
            CMVideoDimensions(width: 5712, height: 4284),
            CMVideoDimensions(width: 1920, height: 1440),
        ])
        #expect(chosen?.width == 4032)
        #expect(chosen?.height == 3024)
    }

    @Test func photoSizeFallsBackToTheSmallestWhenAllAreLarger() {
        let chosen = BackCameraSelection.photoDimensions(from: [
            CMVideoDimensions(width: 8064, height: 6048),
            CMVideoDimensions(width: 5712, height: 4284),
        ])
        #expect(chosen?.width == 5712)
        #expect(BackCameraSelection.photoDimensions(from: []) == nil)
        #expect(BackCameraSelection.photoDimensions(from: [CMVideoDimensions(width: 0, height: 0)]) == nil)
    }

    // MARK: - Close focus

    @Test func closerFocusNeedsAClearlyShorterDistance() {
        #expect(BackCameraSelection.focusesCloser(20, than: 150))
        #expect(!BackCameraSelection.focusesCloser(150, than: 150))
        #expect(!BackCameraSelection.focusesCloser(100, than: 150))
        #expect(!BackCameraSelection.focusesCloser(-1, than: 150))
        #expect(!BackCameraSelection.focusesCloser(20, than: -1))
    }
}
