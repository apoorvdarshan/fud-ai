@preconcurrency import AVFoundation
import CoreGraphics
import CoreMedia
import Foundation

/// Picks the back camera for every in-app camera, and the zoom that makes it look
/// and focus like the system camera.
///
/// The virtual multi-cameras switch lenses on their own: when the subject is closer
/// than the wide lens can focus, they move to the ultra-wide lens on iPhones where
/// that lens has autofocus (the macro the system camera uses). They start at zoom
/// 1.0, though, which *is* the ultra-wide lens (the system camera's 0.5×):
/// fixed-focus on many iPhones and far too wide for food or barcodes. Cameras
/// therefore start at `wideAngleZoomFactor(for:)`, the system camera's 1×.
nonisolated enum BackCameraSelection {
    /// Highest priority first: virtual cameras that include the ultra-wide lens, then
    /// the dual camera, then the wide lens alone.
    static let preferredBackVideoDeviceTypes: [AVCaptureDevice.DeviceType] = [
        .builtInTripleCamera,
        .builtInDualWideCamera,
        .builtInDualCamera,
        .builtInWideAngleCamera
    ]

    static func preferredBackVideoCaptureDevice() -> AVCaptureDevice? {
        for deviceType in preferredBackVideoDeviceTypes {
            if let device = AVCaptureDevice.default(deviceType, for: .video, position: .back) {
                return device
            }
        }

        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: preferredBackVideoDeviceTypes,
            mediaType: .video,
            position: .back
        )
        return discovery.devices.first
    }

    // MARK: - Zoom

    /// The zoom factor at which `device` shows its wide-angle lens: 1.0 for a single
    /// lens or the dual camera, the first switch-over factor (2.0 today) when an
    /// ultra-wide lens comes first.
    static func wideAngleZoomFactor(for device: AVCaptureDevice) -> CGFloat {
        wideAngleZoomFactor(
            constituentDeviceTypes: device.constituentDevices.map(\.deviceType),
            switchOverZoomFactors: device.virtualDeviceSwitchOverVideoZoomFactors.map { CGFloat(truncating: $0) }
        )
    }

    /// Constituent devices are ordered by focal length, and the switch-over factor
    /// before each one is where it takes over from the previous one.
    static func wideAngleZoomFactor(
        constituentDeviceTypes: [AVCaptureDevice.DeviceType],
        switchOverZoomFactors: [CGFloat]
    ) -> CGFloat {
        guard let index = constituentDeviceTypes.firstIndex(of: .builtInWideAngleCamera),
              index > 0,
              switchOverZoomFactors.count == constituentDeviceTypes.count - 1 else { return 1 }
        let factor = switchOverZoomFactors[index - 1]
        return factor.isFinite && factor >= 1 ? factor : 1
    }

    /// The scan frame on the barcode scanners spans this share of the preview width.
    static let barcodeScanFrameWidthFraction: Double = 0.76
    /// A small but common retail barcode (EAN-13 printed at 80%).
    static let barcodeReferenceWidthMM: Double = 30
    /// The most zoom added on top of the wide lens for barcodes.
    static let barcodeMaximumExtraZoom: Double = 2

    /// Apple's barcode guidance (WWDC21, "What's new in camera capture"): if a code
    /// held at the camera's minimum focus distance looks smaller than the scan frame,
    /// people move closer than the lens can focus. Zooming in until such a code fills
    /// the frame keeps them far enough away. Cameras that can switch to a close-focusing
    /// lens report a short minimum focus distance and get no extra zoom.
    static func barcodeZoomFactor(for device: AVCaptureDevice, previewSize: CGSize?) -> CGFloat {
        let dimensions = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        let aspectRatio = formatAspectRatio(width: Int(dimensions.width), height: Int(dimensions.height))
        let visible = previewSize.map { visibleShortSideFraction(previewSize: $0, formatAspectRatio: aspectRatio) } ?? 1
        return barcodeZoomFactor(
            baseZoomFactor: wideAngleZoomFactor(for: device),
            minimumFocusDistanceMM: device.minimumFocusDistance,
            fieldOfViewDegrees: Double(device.activeFormat.videoFieldOfView),
            formatAspectRatio: aspectRatio,
            scanWidthFraction: barcodeScanFrameWidthFraction * visible
        )
    }

    /// - Parameters:
    ///   - baseZoomFactor: the wide lens's zoom factor.
    ///   - minimumFocusDistanceMM: the closest the camera can focus at that zoom (-1 if unknown).
    ///   - fieldOfViewDegrees: the format's horizontal (long side) field of view at zoom 1.0.
    ///   - formatAspectRatio: short side over long side of the video format.
    ///   - scanWidthFraction: the share of the sensor's short side the scan frame covers;
    ///     in portrait that side runs across the screen.
    static func barcodeZoomFactor(
        baseZoomFactor: CGFloat,
        minimumFocusDistanceMM: Int,
        fieldOfViewDegrees: Double,
        formatAspectRatio: Double,
        scanWidthFraction: Double,
        codeWidthMM: Double = barcodeReferenceWidthMM,
        maximumExtraZoom: Double = barcodeMaximumExtraZoom
    ) -> CGFloat {
        let base = baseZoomFactor.isFinite ? max(1, Double(baseZoomFactor)) : 1
        guard minimumFocusDistanceMM > 0,
              fieldOfViewDegrees > 0, fieldOfViewDegrees < 180,
              formatAspectRatio > 0, formatAspectRatio <= 1,
              scanWidthFraction > 0, scanWidthFraction <= 1,
              codeWidthMM > 0 else { return CGFloat(base) }
        let halfAngle = fieldOfViewDegrees * .pi / 360
        // Width the scan frame covers at the minimum focus distance.
        let frameWidthMM = 2 * Double(minimumFocusDistanceMM) * tan(halfAngle) / base
            * formatAspectRatio * scanWidthFraction
        let extra = min(max(1, frameWidthMM / codeWidthMM), max(1, maximumExtraZoom))
        return CGFloat(base * extra)
    }

    /// Short side over long side; 3:4 when the format is unknown.
    static func formatAspectRatio(width: Int, height: Int) -> Double {
        guard width > 0, height > 0 else { return 0.75 }
        return Double(min(width, height)) / Double(max(width, height))
    }

    /// How much of the sensor's short side an aspect-fill portrait preview shows; a
    /// screen taller than the video crops its sides.
    static func visibleShortSideFraction(previewSize: CGSize, formatAspectRatio: Double) -> Double {
        guard previewSize.width > 0, previewSize.height > previewSize.width, formatAspectRatio > 0 else { return 1 }
        return min(1, Double(previewSize.width / previewSize.height) / formatAspectRatio)
    }

    /// Clamps a requested zoom to what the device allows right now.
    static func clampedZoomFactor(_ zoom: CGFloat, minimum: CGFloat, maximum: CGFloat) -> CGFloat {
        let low = minimum.isFinite ? max(1, minimum) : 1
        let high = maximum.isFinite ? max(low, maximum) : low
        guard zoom.isFinite else { return low }
        return min(max(zoom, low), high)
    }

    // MARK: - Photos

    /// About 12 MP, what the system camera saves by default.
    static let photoPixelLimit = 12_500_000

    /// The largest supported photo size up to `photoPixelLimit`, else the smallest.
    static func photoDimensions(from supported: [CMVideoDimensions], pixelLimit: Int = photoPixelLimit) -> CMVideoDimensions? {
        let valid = supported.filter { $0.width > 0 && $0.height > 0 }
        func pixels(_ dimensions: CMVideoDimensions) -> Int { Int(dimensions.width) * Int(dimensions.height) }
        return valid.filter { pixels($0) <= pixelLimit }.max { pixels($0) < pixels($1) }
            ?? valid.min { pixels($0) < pixels($1) }
    }

    // MARK: - Close focus

    /// Whether a camera with `candidateMM` minimum focus distance focuses meaningfully
    /// closer than one with `currentMM`. A virtual camera reports the shortest distance
    /// of its autofocus lenses, so this is how the ultra-wide macro lens shows up.
    static func focusesCloser(_ candidateMM: Int, than currentMM: Int) -> Bool {
        guard candidateMM > 0, currentMM > 0 else { return false }
        return Double(candidateMM) <= Double(currentMM) * 0.6
    }
}
